package dev.anydm.engine.tools

import dev.anydm.engine.core.Checksum
import dev.anydm.engine.core.ChecksumAlgorithm
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.ErrorClass
import dev.anydm.engine.core.PrepareOutcome
import dev.anydm.engine.core.RunListener
import dev.anydm.engine.core.RunOutcome
import dev.anydm.engine.core.StepRunner
import dev.anydm.engine.http.FaultServer
import dev.anydm.engine.http.HttpProtocol
import dev.anydm.engine.http.OkHttpTransport
import dev.anydm.engine.platform.DesktopFileSystem
import dev.anydm.engine.platform.DesktopProcessRunner
import dev.anydm.engine.store.NewDownload
import dev.anydm.engine.store.Store
import dev.anydm.engine.store.openInMemoryDatabase
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/** A TOOL job through the real HTTP engine, runner, unpacker and file system (BIN-01, BIN-02, BIN-04). */
class ToolProtocolHttpTest {
    private val root = Files.createTempDirectory("anydm-tool-test-").toRealPath()
    private val server = FaultServer()
    private val store = Store(openInMemoryDatabase(), Dispatchers.IO)
    private val processes = DesktopProcessRunner()
    private val tools =
        ToolProtocol(HttpProtocol(OkHttpTransport()), processes, DesktopUnpacker(processes), DesktopFileSystem(), OsFamily.LINUX)
    private val runner =
        StepRunner(DesktopFileSystem(), store.downloads, mapOf(DownloadKind.TOOL to tools), Path(root.toString()), freeSpaceReserve = 0)

    @BeforeTest
    fun notOnWindows() = assumeFalse(System.getProperty("os.name").startsWith("Windows"))

    @AfterTest
    fun close() {
        server.close()
        store.close()
        root.toFile().deleteRecursively()
    }

    private fun script(
        output: String,
        exit: Int = 0,
    ) = "#!/bin/sh\necho \"$output\"\nexit $exit\n"

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { out ->
            for ((name, text) in entries) {
                out.putNextEntry(ZipEntry(name))
                out.write(text.toByteArray())
                out.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Serves [bytes] and adds the TOOL job for it under `tools/<tool>/<version>`; returns its run outcome. */
    private fun install(
        tool: String,
        fileName: String,
        bytes: ByteArray,
        checksum: String = sha256(bytes),
    ): RunOutcome =
        runBlocking {
            server.serve("/$fileName", bytes)
            val folder = root.resolve("tools/$tool/1.0.0")
            val download =
                store.downloads.add(
                    NewDownload(
                        DownloadKind.TOOL,
                        mirrors = listOf(server.url("/$fileName")),
                        saveFolder = folder.toString(),
                        fileName = fileName,
                        checksum = Checksum(ChecksumAlgorithm.SHA256, checksum),
                    ),
                )
            val mirror = store.downloads.mirrors(download.id).single()
            val ready = runner.prepare(download, mirror, RunListener.None).shouldBeInstanceOf<PrepareOutcome.Ready>()
            runner.run(ready, RunListener.None)
        }

    private fun toolFile(relative: String) = root.resolve("tools").resolve(relative)

    @Test
    fun aZipToolInstallsAndItsExecutablesRun() {
        val archive =
            zip(
                "pkg/bin/ffmpeg" to script("ffmpeg version 8.1.3-test"),
                "pkg/bin/ffprobe" to script("ffprobe version 8.1.3-test"),
            )

        install("ffmpeg", "ffmpeg.zip", archive).shouldBeInstanceOf<RunOutcome.Finished>()

        Files.isExecutable(toolFile("ffmpeg/1.0.0/pkg/bin/ffmpeg")) shouldBe true
        Files.exists(toolFile("ffmpeg/1.0.0/ffmpeg.zip")) shouldBe true
        val out =
            ProcessBuilder(
                toolFile("ffmpeg/1.0.0/pkg/bin/ffprobe").toString(),
                "--version",
            ).start().inputStream.bufferedReader().readText()
        out.trim() shouldBe "ffprobe version 8.1.3-test"
    }

    @Test
    fun aTarXzToolInstalls() {
        val source = Files.createDirectories(root.resolve("src/bin"))
        Files.writeString(source.resolve("deno"), script("deno 2.9.7 (test)"))
        val archive = root.resolve("deno.tar.xz")
        val made = ProcessBuilder("tar", "-cJf", archive.toString(), "-C", root.resolve("src").toString(), ".").start().waitFor()
        assumeTrue("tar can't write xz here", made == 0)
        Files.setPosixFilePermissions(
            source.resolve("deno"),
            java.nio.file.attribute.PosixFilePermissions
                .fromString("rwxr-xr-x"),
        )

        install("deno", "deno.tar.xz", Files.readAllBytes(archive)).shouldBeInstanceOf<RunOutcome.Finished>()

        Files.exists(toolFile("deno/1.0.0/bin/deno")) shouldBe true
    }

    @Test
    fun aBareBinaryIsInstalledUnderItsOwnName() {
        install("yt-dlp", "yt-dlp", script("2026.10.01").toByteArray()).shouldBeInstanceOf<RunOutcome.Finished>()

        Files.isExecutable(toolFile("yt-dlp/1.0.0/yt-dlp")) shouldBe true
        Files.list(toolFile("yt-dlp/1.0.0")).use { it.map { p -> p.fileName.toString() }.toList() } shouldBe listOf("yt-dlp")
    }

    @Test
    fun aWrongChecksumFailsTheJobAndUnpacksNothing() {
        val archive = zip("bin/deno" to script("deno 2.9.7"))

        val outcome = install("deno", "deno.zip", archive, checksum = "0".repeat(64)).shouldBeInstanceOf<RunOutcome.Failed>()

        outcome.failure.errorClass shouldBe ErrorClass.INTEGRITY
        Files.exists(toolFile("deno/1.0.0/bin/deno")) shouldBe false
    }

    @Test
    fun aFailingSelfTestFailsTheJobAndPlacesNothing() {
        val archive = zip("bin/deno" to script("deno 2.9.7", exit = 1))

        val outcome = install("deno", "deno.zip", archive).shouldBeInstanceOf<RunOutcome.Failed>()

        outcome.failure.errorClass shouldBe null
        Files.exists(toolFile("deno/1.0.0/deno.zip")) shouldBe false
    }
}
