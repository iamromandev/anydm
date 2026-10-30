package dev.anydm.desktop

import dev.anydm.engine.platform.ProcessFailure
import dev.anydm.engine.platform.ProcessOutcome
import dev.anydm.engine.platform.ProcessResult
import dev.anydm.engine.platform.ProcessRunner
import dev.anydm.engine.platform.ProcessSpec
import dev.anydm.engine.platform.RunningProcess
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Answers each command line a test scripted with [answer]; [ran] is what was run, in order. */
internal class ScriptedRunner(
    private val answer: (List<String>) -> ProcessOutcome<ProcessResult> = { ok() },
) : ProcessRunner {
    val ran = mutableListOf<List<String>>()

    override suspend fun run(spec: ProcessSpec): ProcessOutcome<ProcessResult> {
        ran += spec.command
        return answer(spec.command)
    }

    override suspend fun start(spec: ProcessSpec): ProcessOutcome<RunningProcess> = error("not used")

    companion object {
        fun ok(out: String = "") = ProcessOutcome.Ok(ProcessResult(0, out, "", false))

        fun exit(
            code: Int,
            err: String = "",
        ) = ProcessOutcome.Ok(ProcessResult(code, "", err, false))
    }
}

internal class LinkHandlersTest {
    private val folder = Files.createTempDirectory("applications")
    private val exe = "/opt/any dm/bin/anydm"

    @AfterTest
    fun close() {
        folder.toFile().deleteRecursively()
    }

    @Test
    fun `an app that is not installed cannot register itself`() =
        runBlocking {
            val handlers = linkHandlersForCurrentOs(ScriptedRunner(), appPath = null, osName = "Linux", applications = folder)
            assertEquals(LinkSupport.UNAVAILABLE, handlers.status().support)
            assertTrue(handlers.register()!!.contains("installed"))
        }

    @Test
    fun `macos links are registered by the installed bundle`() =
        runBlocking {
            val runner = ScriptedRunner()
            val handlers = linkHandlersForCurrentOs(runner, appPath = "/Applications/anydm.app/Contents/MacOS/anydm", osName = "Mac OS X")
            assertEquals(LinkSupport.BY_INSTALLER, handlers.status().support)
            assertTrue(handlers.register()!!.contains("Applications"))
            assertTrue(runner.ran.isEmpty())
        }

    @Test
    fun `windows registers each scheme for the user and points it at the app`() =
        runBlocking {
            val runner = ScriptedRunner()
            val handlers = WindowsLinkHandlers(runner, "C:\\Program Files\\anydm\\anydm.exe")
            assertNull(handlers.register())
            val commands = runner.ran.map { it.joinToString(" ") }
            for (scheme in listOf("magnet", "anydm")) {
                val key = "HKCU\\Software\\Classes\\$scheme"
                assertTrue(commands.any { it.startsWith("reg add $key ") && "/v URL Protocol" in it }, commands.toString())
                // The JDK copies an argument that starts and ends with a quote as it is, so the inner quotes come escaped.
                val value = "\"\\\"C:\\Program Files\\anydm\\anydm.exe\\\" \\\"%1\\\"\""
                val command = runner.ran.single { it[2] == "$key\\shell\\open\\command" }
                assertEquals(value, command[command.indexOf("/d") + 1])
            }
            assertTrue(runner.ran.all { it.first() == "reg" })
        }

    @Test
    fun `windows reports a registry that refused`() =
        runBlocking {
            val handlers = WindowsLinkHandlers(ScriptedRunner { ScriptedRunner.exit(1, "Access is denied.") }, "C:\\anydm.exe")
            assertTrue(handlers.register()!!.contains("Access is denied."))
        }

    @Test
    fun `windows is registered when both schemes open this app`() =
        runBlocking {
            val ours = ScriptedRunner { ScriptedRunner.ok("    (Default)    REG_SZ    \"C:\\anydm.exe\" \"%1\"\r\n") }
            assertEquals(LinkSupport.REGISTERED, WindowsLinkHandlers(ours, "C:\\anydm.exe").status().support)
            val other = ScriptedRunner { ScriptedRunner.ok("    (Default)    REG_SZ    \"C:\\other.exe\" \"%1\"\r\n") }
            assertEquals(LinkSupport.NOT_REGISTERED, WindowsLinkHandlers(other, "C:\\anydm.exe").status().support)
            val none = ScriptedRunner { ScriptedRunner.exit(1) }
            assertEquals(LinkSupport.NOT_REGISTERED, WindowsLinkHandlers(none, "C:\\anydm.exe").status().support)
        }

    @Test
    fun `windows removes the keys it wrote`() =
        runBlocking {
            val runner = ScriptedRunner { cmd -> if (cmd[1] == "query") ScriptedRunner.exit(1) else ScriptedRunner.ok() }
            assertNull(WindowsLinkHandlers(runner, "C:\\anydm.exe").unregister())
            val deletes = runner.ran.filter { it[1] == "delete" }
            assertEquals(
                listOf("HKCU\\Software\\Classes\\magnet", "HKCU\\Software\\Classes\\anydm"),
                deletes.map { it[2] },
            )
        }

    @Test
    fun `linux writes a desktop file for the schemes and sets it as their default`() =
        runBlocking {
            val runner = ScriptedRunner()
            assertNull(LinuxLinkHandlers(runner, exe, folder).register())
            val text = Files.readString(folder.resolve("anydm-links.desktop"))
            assertTrue("Exec=\"/opt/any dm/bin/anydm\" %u" in text, text)
            assertTrue("MimeType=x-scheme-handler/magnet;x-scheme-handler/anydm;application/x-bittorrent;" in text, text)
            assertTrue("NoDisplay=true" in text, text)
            val xdg = runner.ran.single { it.first() == "xdg-mime" }
            assertEquals(
                listOf(
                    "xdg-mime",
                    "default",
                    "anydm-links.desktop",
                    "x-scheme-handler/magnet",
                    "x-scheme-handler/anydm",
                    "application/x-bittorrent",
                ),
                xdg,
            )
        }

    @Test
    fun `linux escapes what the desktop entry spec reserves in the path`() =
        runBlocking {
            LinuxLinkHandlers(ScriptedRunner(), "/opt/a\$b\"c/anydm", folder).register()
            val text = Files.readString(folder.resolve("anydm-links.desktop"))
            assertTrue("Exec=\"/opt/a\\\\\$b\\\\\"c/anydm\" %u" in text, text)
        }

    @Test
    fun `linux says so when xdg-mime is missing or fails`() =
        runBlocking {
            val missing = ScriptedRunner { ProcessOutcome.Failed(ProcessFailure.NotFound("xdg-mime")) }
            assertTrue(LinuxLinkHandlers(missing, exe, folder).register()!!.contains("xdg-mime"))
            assertEquals(LinkSupport.UNAVAILABLE, LinuxLinkHandlers(missing, exe, folder).status().support)
            val failing =
                ScriptedRunner { cmd ->
                    if (cmd.first() ==
                        "xdg-mime"
                    ) {
                        ScriptedRunner.exit(4, "no mime")
                    } else {
                        ScriptedRunner.ok()
                    }
                }
            assertTrue(LinuxLinkHandlers(failing, exe, folder).register()!!.contains("no mime"))
        }

    @Test
    fun `linux is registered only while the default is this desktop file`() =
        runBlocking {
            Files.writeString(folder.resolve("anydm-links.desktop"), "x")
            val ours = ScriptedRunner { ScriptedRunner.ok("anydm-links.desktop\n") }
            assertEquals(LinkSupport.REGISTERED, LinuxLinkHandlers(ours, exe, folder).status().support)
            val other = ScriptedRunner { ScriptedRunner.ok("transmission.desktop\n") }
            assertEquals(LinkSupport.NOT_REGISTERED, LinuxLinkHandlers(other, exe, folder).status().support)
        }

    @Test
    fun `linux removes its desktop file`() =
        runBlocking {
            val runner = ScriptedRunner()
            val handlers = LinuxLinkHandlers(runner, exe, folder)
            handlers.register()
            assertNull(handlers.unregister())
            assertFalse(Files.exists(folder.resolve("anydm-links.desktop")))
        }

    @Test
    fun `windows takes back the keys it wrote when a step fails`() =
        runBlocking {
            val runner =
                ScriptedRunner { cmd ->
                    if (cmd[1] == "add" &&
                        cmd[2].endsWith("\\command")
                    ) {
                        ScriptedRunner.exit(1, "bad")
                    } else {
                        ScriptedRunner.ok()
                    }
                }
            assertTrue(WindowsLinkHandlers(runner, "C:\\anydm.exe").register()!!.contains("bad"))
            assertTrue(runner.ran.any { it[1] == "delete" && it[2] == "HKCU\\Software\\Classes\\magnet" })
        }

    @Test
    fun `windows is not registered while the user chose another app for the scheme`() =
        runBlocking {
            val runner =
                ScriptedRunner { cmd ->
                    if (cmd.any { "UserChoice" in it }) {
                        ScriptedRunner.ok("    ProgId    REG_SZ    qBittorrent.magnet\r\n")
                    } else {
                        ScriptedRunner.ok("    (Default)    REG_SZ    \"C:\\anydm.exe\" \"%1\"\r\n")
                    }
                }
            val status = WindowsLinkHandlers(runner, "C:\\anydm.exe").status()
            assertEquals(LinkSupport.NOT_REGISTERED, status.support)
            assertTrue(status.detail.contains("Default apps"), status.detail)
        }

    @Test
    fun `windows says which key is still there after removing`() =
        runBlocking {
            val runner =
                ScriptedRunner { cmd ->
                    if (cmd[1] == "query" &&
                        cmd[2] == "HKCU\\Software\\Classes\\anydm"
                    ) {
                        ScriptedRunner.ok("HKEY")
                    } else {
                        ScriptedRunner.exit(1)
                    }
                }
            assertTrue(WindowsLinkHandlers(runner, "C:\\anydm.exe").unregister()!!.contains("anydm"))
        }

    @Test
    fun `linux doubles backslashes and percent signs and refuses a path with a line break`() =
        runBlocking {
            LinuxLinkHandlers(ScriptedRunner(), "/opt/a\\b%c/anydm", folder).register()
            val text = Files.readString(folder.resolve("anydm-links.desktop"))
            assertTrue("Exec=\"/opt/a\\\\\\\\b%%c/anydm\" %u" in text, text)
            val broken = LinuxLinkHandlers(ScriptedRunner(), "/opt/a\nb/anydm", folder)
            assertTrue(broken.register()!!.contains("path"))
        }
}
