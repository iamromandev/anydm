package dev.anydm.desktop

import dev.anydm.engine.platform.ProcessOutcome
import dev.anydm.engine.platform.ProcessResult
import dev.anydm.engine.platform.ProcessRunner
import dev.anydm.engine.platform.ProcessSpec
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists

/** Whether the OS opens `magnet:` and `anydm://` links with this app, and who is in charge of that. */
enum class LinkSupport {
    REGISTERED,
    NOT_REGISTERED,

    /** The installed app bundle declares them (macOS); there is nothing for the app to write. */
    BY_INSTALLER,

    /** This run can't register (not installed, or the system has no tool for it). */
    UNAVAILABLE,
}

data class LinkHandlerStatus(
    val support: LinkSupport,
    /** One sentence for the settings screen. */
    val detail: String,
)

/**
 * The OS's handlers for `magnet:` and `anydm://` links (#611). The installers write the `.torrent` association and, on macOS,
 * the schemes; where they can't (Windows, Linux), the installed app registers itself for the current user when asked, and
 * [unregister] takes it back. Every change is per user: no administrator rights, nothing outside the user's own folders.
 */
interface LinkHandlers {
    suspend fun status(): LinkHandlerStatus

    /** Null when it worked, or what went wrong. */
    suspend fun register(): String?

    /** Null when it worked, or what went wrong. */
    suspend fun unregister(): String?
}

/** Stands in where nothing can be registered: tests, and a run from the build folder. */
class NoLinkHandlers(
    private val reason: String = "Only the installed app can register itself for links; this run is from the build folder.",
) : LinkHandlers {
    override suspend fun status() = LinkHandlerStatus(LinkSupport.UNAVAILABLE, reason)

    override suspend fun register(): String = reason

    override suspend fun unregister(): String = reason
}

internal val LINK_SCHEMES = listOf("magnet", "anydm")

/** The handlers for this machine's OS; [appPath] is the installed launcher (jpackage's `jpackage.app-path`), null for a run from the build. */
fun linkHandlersForCurrentOs(
    runner: ProcessRunner,
    appPath: String? = System.getProperty("jpackage.app-path"),
    osName: String = System.getProperty("os.name"),
    applications: Path = defaultApplicationsFolder(),
): LinkHandlers =
    when {
        appPath.isNullOrBlank() -> NoLinkHandlers()
        osName.startsWith("Mac", ignoreCase = true) -> MacLinkHandlers()
        osName.startsWith("Windows", ignoreCase = true) -> WindowsLinkHandlers(runner, appPath)
        else -> LinuxLinkHandlers(runner, appPath, applications)
    }

private fun defaultApplicationsFolder(): Path {
    val data = System.getenv("XDG_DATA_HOME")?.takeIf { it.startsWith("/") } ?: "${System.getProperty("user.home")}/.local/share"
    return Path.of(data, "applications")
}

class MacLinkHandlers : LinkHandlers {
    private val detail =
        "macOS registers magnet: and anydm:// links when anydm is in the Applications folder and has been opened once. " +
            "To change which app opens magnet links, use that app's own setting; deleting anydm removes it from the list."

    override suspend fun status() = LinkHandlerStatus(LinkSupport.BY_INSTALLER, detail)

    override suspend fun register(): String = detail

    override suspend fun unregister(): String = detail
}

/** Per-user keys under `HKCU\Software\Classes`, written with the `reg` tool that ships with Windows. */
class WindowsLinkHandlers(
    private val runner: ProcessRunner,
    private val exe: String,
) : LinkHandlers {
    private suspend fun reg(vararg args: String) = runner.run(ProcessSpec(listOf("reg", *args)))

    private fun classes(scheme: String) = "HKCU\\Software\\Classes\\$scheme"

    private fun choice(scheme: String) = "HKCU\\Software\\Microsoft\\Windows\\Shell\\Associations\\UrlAssociations\\$scheme\\UserChoice"

    override suspend fun status(): LinkHandlerStatus {
        // Since Windows 8 the app the user picked in Default apps wins over whatever the Classes key says.
        for (scheme in LINK_SCHEMES) {
            val picked = reg("query", choice(scheme), "/v", "ProgId")
            val progId =
                (picked as? ProcessOutcome.Ok)?.value?.takeIf { it.exitCode == 0 }?.let {
                    PROG_ID
                        .find(
                            it.stdout,
                        )?.groupValues
                        ?.get(1)
                }
            if (progId != null && !progId.equals(scheme, ignoreCase = true)) {
                return LinkHandlerStatus(
                    LinkSupport.NOT_REGISTERED,
                    "$scheme: links open in another app you chose. Change it in Windows Settings, Apps, Default apps.",
                )
            }
        }
        val ours =
            LINK_SCHEMES.all { scheme ->
                val out = reg("query", "${classes(scheme)}\\shell\\open\\command", "/ve")
                out is ProcessOutcome.Ok && out.value.exitCode == 0 && out.value.stdout.contains(exe, ignoreCase = true)
            }
        return if (ours) {
            LinkHandlerStatus(LinkSupport.REGISTERED, "magnet: and anydm:// links open in anydm for your Windows account.")
        } else {
            LinkHandlerStatus(LinkSupport.NOT_REGISTERED, "magnet: and anydm:// links don't open in anydm yet.")
        }
    }

    // The command first, so a scheme is never left half written; if any step fails, the keys written so far go again.
    override suspend fun register(): String? {
        // The JDK passes an argument that starts and ends with a quote to the program as it is, so the quotes inside
        // are escaped here for the program's own parser: reg.exe sees  "exe" "%1".
        val command = "\"\\\"$exe\\\" \\\"%1\\\"\""
        for (scheme in LINK_SCHEMES) {
            val steps =
                listOf(
                    arrayOf("add", "${classes(scheme)}\\shell\\open\\command", "/ve", "/d", command, "/f"),
                    arrayOf("add", classes(scheme), "/ve", "/d", "URL:$scheme link", "/f"),
                    arrayOf("add", classes(scheme), "/v", "URL Protocol", "/d", "", "/f"),
                )
            for (step in steps) {
                failure("reg add", reg(*step))?.let { problem ->
                    LINK_SCHEMES.forEach { reg("delete", classes(it), "/f") }
                    return problem
                }
            }
        }
        return null
    }

    // The keys may not be there; what counts is that none is left after.
    override suspend fun unregister(): String? {
        for (scheme in LINK_SCHEMES) reg("delete", classes(scheme), "/f")
        val left =
            LINK_SCHEMES.filter { scheme ->
                val out = reg("query", classes(scheme))
                out is ProcessOutcome.Ok && out.value.exitCode == 0
            }
        return if (left.isEmpty()) {
            null
        } else {
            "These registry keys are still there: ${left.joinToString {
                classes(
                    it,
                )
            }}. Delete them in regedit."
        }
    }

    private companion object {
        val PROG_ID = Regex("""ProgId\s+REG_SZ\s+(\S+)""")
    }
}

/** A `.desktop` file in the user's applications folder, made the default for the schemes with `xdg-mime`. */
class LinuxLinkHandlers(
    private val runner: ProcessRunner,
    private val exe: String,
    private val applications: Path,
) : LinkHandlers {
    private val file = applications.resolve(DESKTOP_FILE)
    private val mimeTypes = LINK_SCHEMES.map { "x-scheme-handler/$it" } + "application/x-bittorrent"

    override suspend fun status(): LinkHandlerStatus {
        val query = runner.run(ProcessSpec(listOf("xdg-mime", "query", "default", "x-scheme-handler/magnet")))
        if (query !is ProcessOutcome.Ok) {
            return LinkHandlerStatus(LinkSupport.UNAVAILABLE, "This system has no xdg-mime, so anydm can't register itself for links.")
        }
        return if (Files.exists(file) && query.value.stdout.trim() == DESKTOP_FILE) {
            LinkHandlerStatus(LinkSupport.REGISTERED, "magnet: and anydm:// links open in anydm.")
        } else {
            LinkHandlerStatus(LinkSupport.NOT_REGISTERED, "magnet: and anydm:// links don't open in anydm yet.")
        }
    }

    override suspend fun register(): String? {
        if (exe.any {
                it.isISOControl()
            }
        ) {
            return "The app's path has a line break or control character in it, so it can't be written to a desktop file."
        }
        runCatching {
            Files.createDirectories(applications)
            Files.writeString(file, entry())
        }.onFailure { return "Couldn't write $file: ${it.message}" }
        failure("xdg-mime", runner.run(ProcessSpec(listOf("xdg-mime", "default", DESKTOP_FILE) + mimeTypes)))?.let { return it }
        refreshDatabase()
        return null
    }

    override suspend fun unregister(): String? {
        runCatching { file.deleteIfExists() }.onFailure { return "Couldn't remove $file: ${it.message}" }
        refreshDatabase()
        return null
    }

    // Best effort: desktops that watch the folder pick the change up anyway.
    private suspend fun refreshDatabase() {
        runner.run(ProcessSpec(listOf("update-desktop-database", applications.toString())))
    }

    private fun entry(): String =
        """
        [Desktop Entry]
        Type=Application
        Name=anydm links
        Exec="${execEscaped(exe)}" %u
        Icon=anydm
        Terminal=false
        NoDisplay=true
        MimeType=${mimeTypes.joinToString(";")};
        """.trimIndent() + "\n"

    // Inside a quoted Exec argument, " ` $ and \ take a backslash, and the file's own string syntax doubles each backslash; a lone %
    // would be a field code.
    private fun execEscaped(text: String) =
        buildString {
            for (c in text) {
                when (c) {
                    '\\' -> append("\\\\\\\\")
                    '"', '`', '$' -> append("\\\\").append(c)
                    '%' -> append("%%")
                    else -> append(c)
                }
            }
        }

    private companion object {
        const val DESKTOP_FILE = "anydm-links.desktop"
    }
}

/** What went wrong in [outcome] as one sentence, or null if the program ran and exited 0. */
private fun failure(
    what: String,
    outcome: ProcessOutcome<ProcessResult>,
): String? =
    when {
        outcome is ProcessOutcome.Failed -> "$what could not start: ${outcome.failure}"

        outcome is ProcessOutcome.Ok && outcome.value.exitCode != 0 -> "$what failed: ${outcome.value.stderr.trim().ifEmpty {
            "exit ${outcome.value.exitCode}"
        }}"

        else -> null
    }
