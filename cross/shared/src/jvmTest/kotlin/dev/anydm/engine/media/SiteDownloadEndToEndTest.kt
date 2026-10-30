package dev.anydm.engine.media

import dev.anydm.engine.core.AudioFormat
import dev.anydm.engine.core.Download
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.Format
import dev.anydm.engine.core.Mirror
import dev.anydm.engine.core.PrepareOutcome
import dev.anydm.engine.core.RecordingLimiter
import dev.anydm.engine.core.RecordingMeter
import dev.anydm.engine.core.RunListener
import dev.anydm.engine.core.RunOutcome
import dev.anydm.engine.core.StepRunner
import dev.anydm.engine.http.Fault
import dev.anydm.engine.http.FaultServer
import dev.anydm.engine.http.HttpProtocol
import dev.anydm.engine.http.OkHttpTransport
import dev.anydm.engine.platform.DesktopFileSystem
import dev.anydm.engine.platform.DesktopProcessRunner
import dev.anydm.engine.store.NewDownload
import dev.anydm.engine.store.Store
import dev.anydm.engine.store.openInMemoryDatabase
import dev.anydm.engine.tools.ResolvedTool
import dev.anydm.engine.tools.Tool
import dev.anydm.engine.tools.ToolVersion
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import org.junit.Assume.assumeFalse
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds

/**
 * A media page through the real HTTP engine, runner, file system and process runner (MED-01 to MED-11). Only the two
 * programs are stand-ins: a yt-dlp that prints a recorded page and an ffmpeg that joins its inputs.
 */
class SiteDownloadEndToEndTest {
    private val root = Files.createTempDirectory("anydm-site-test-").toRealPath().toFile()
    private val dl = File(root, "dl").also { it.mkdirs() }
    private val bin = File(root, "bin").also { it.mkdirs() }
    private val server = FaultServer()
    private val store = Store(openInMemoryDatabase(), Dispatchers.IO)
    private val video = ByteArray(4000) { (it % 251).toByte() }
    private val audio = ByteArray(1000) { (it % 241).toByte() }
    private val pageUrl = "https://www.example.com/watch?v=abc"
    private val ffmpegLog = File(bin, "ffmpeg.log")
    private val failMerge = File(bin, "fail")

    private fun script(
        name: String,
        body: String,
    ): File =
        File(bin, name).also {
            it.writeText("#!/bin/sh\n$body")
            it.setExecutable(true)
        }

    private val ytDlpScript get() = script("yt-dlp", "cat '${File(bin, "page.json")}'\n")
    private val ffmpegScript =
        script(
            "ffmpeg",
            """
            echo run >> '$ffmpegLog'
            if [ -f '$failMerge' ]; then echo "boom: cannot merge" >&2; exit 1; fi
            out=""
            for a in "${'$'}@"; do out="${'$'}a"; done
            : > "${'$'}out"
            prev=""
            for a in "${'$'}@"; do
              if [ "${'$'}prev" = "-i" ]; then cat "${'$'}a" >> "${'$'}out"; fi
              prev="${'$'}a"
            done
            """.trimIndent() + "\n",
        )

    private fun tool(
        tool: Tool,
        file: File,
    ) = ResolvedTool(tool, ToolVersion.parse("2026.1.1")!!, mapOf(tool.id to Path(file.toString())), managed = true)

    private val site =
        SiteProtocol(
            HttpProtocol(OkHttpTransport()),
            DesktopProcessRunner(),
            {
                when (it) {
                    Tool.YT_DLP -> tool(it, ytDlpScript)
                    Tool.FFMPEG -> tool(it, ffmpegScript)
                    else -> null
                }
            },
            store.extractions,
            DesktopFileSystem(),
        )
    private val runner =
        StepRunner(
            DesktopFileSystem(),
            store.downloads,
            mapOf(DownloadKind.SITE to site),
            Path(dl.toString()),
            freeSpaceReserve = 0,
            checkpointEvery = 50.milliseconds,
        )

    @BeforeTest
    fun setUp() {
        assumeFalse(System.getProperty("os.name").startsWith("Windows"))
        server.serve("/v/137", video)
        server.serve("/v/140", audio)
        File(bin, "page.json").writeText(MediaFixtures.splitVideoJson(server.url("/").trimEnd('/')))
    }

    @AfterTest
    fun close() {
        server.close()
        store.close()
        root.deleteRecursively()
    }

    private suspend fun added(format: Format): Pair<Download, Mirror> {
        val download =
            store.downloads.add(
                NewDownload(DownloadKind.SITE, mirrors = listOf(pageUrl), saveFolder = dl.toString(), format = format),
            )
        return download to store.downloads.mirrors(download.id).single()
    }

    private suspend fun prepared(
        download: Download,
        mirror: Mirror,
    ) = runner.prepare(download, mirror, RunListener.None).shouldBeInstanceOf<PrepareOutcome.Ready>()

    private fun names() = dl.list().orEmpty().toSet()

    private val mp4 = Format(audioCodec = "mp4a")

    @Test
    fun videoAndAudioDownloadAndMerge() =
        runBlocking<Unit> {
            val (download, mirror) = added(mp4)

            val finished = runner.run(prepared(download, mirror)).shouldBeInstanceOf<RunOutcome.Finished>()

            File(dl, "Test Video.mp4").readBytes().contentEquals(video + audio) shouldBe true
            names() shouldBe setOf("Test Video.mp4")
            finished.outputs.map { it.name } shouldBe listOf("Test Video.mp4")
            val rows = store.downloads.files(download.id)
            rows.map { it.path } shouldBe listOf("Test Video.mp4")
            rows.single().placedPath shouldBe "Test Video.mp4"
            ffmpegLog.readLines().size shouldBe 1
        }

    @Test
    fun audioOnlyM4aNeedsNoFfmpeg() =
        runBlocking<Unit> {
            val (download, mirror) = added(Format(audioOnly = AudioFormat.M4A))

            runner.run(prepared(download, mirror)).shouldBeInstanceOf<RunOutcome.Finished>()

            File(dl, "Test Video.m4a").readBytes().contentEquals(audio) shouldBe true
            names() shouldBe setOf("Test Video.m4a")
            ffmpegLog.exists() shouldBe false
        }

    @Test
    fun limiterAndMeterCoverBothStreams() =
        runBlocking<Unit> {
            val (download, mirror) = added(mp4)
            val limiter = RecordingLimiter()
            val meter = RecordingMeter()

            runner.run(prepared(download, mirror), limiter = limiter, meter = meter).shouldBeInstanceOf<RunOutcome.Finished>()

            meter.bytes shouldBe 5000L
            limiter.bytes shouldBe 5000L
        }

    @Test
    fun aMergeFailureFailsTheJobAndKeepsTheStreams() =
        runBlocking<Unit> {
            failMerge.writeText("x")
            val (download, mirror) = added(mp4)

            val failed = runner.run(prepared(download, mirror)).shouldBeInstanceOf<RunOutcome.Failed>()

            failed.failure.message.contains("boom: cannot merge") shouldBe true
            names().any { it.startsWith("Test Video.f137.mp4") } shouldBe true
            names().any { it.startsWith("Test Video.f140.m4a") } shouldBe true
            names().none { it == "Test Video.mp4" } shouldBe true
        }

    @Test
    fun aRetryAfterAFailedMergeDownloadsNothingAgain() =
        runBlocking<Unit> {
            failMerge.writeText("x")
            val (download, mirror) = added(mp4)
            runner.run(prepared(download, mirror)).shouldBeInstanceOf<RunOutcome.Failed>()
            failMerge.delete()
            val before = server.requests.size

            runner.run(prepared(download, mirror)).shouldBeInstanceOf<RunOutcome.Finished>()

            File(dl, "Test Video.mp4").readBytes().contentEquals(video + audio) shouldBe true
            names() shouldBe setOf("Test Video.mp4")
            val ranged = server.requests.drop(before).filter { it.headers["Range"] != null && it.headers["Range"] != "bytes=0-1023" }
            ranged shouldBe emptyList()
        }

    @Test
    fun aStopBetweenStreamsResumesOnlyTheSecond() =
        runBlocking<Unit> {
            server.fault("/v/140", Fault.Throttle(100))
            val (download, mirror) = added(mp4)
            val job = launch(Dispatchers.Default) { runner.run(prepared(download, mirror)) }
            while (true) {
                val files = store.downloads.files(download.id)
                val first = files.firstOrNull { it.index == 0 }
                if (first != null && store.downloads.segments(first.id).let { it.isNotEmpty() && it.all { s -> s.isComplete } }) break
                delay(20)
            }
            job.isActive shouldBe true
            job.cancelAndJoin()
            val before = server.requests.size

            server.clear("/v/140")
            runner.run(prepared(download, mirror)).shouldBeInstanceOf<RunOutcome.Finished>()

            File(dl, "Test Video.mp4").readBytes().contentEquals(video + audio) shouldBe true
            val videoBlocks =
                server.requests.drop(before).filter {
                    it.url.encodedPath == "/v/137" && it.headers["Range"] != null && it.headers["Range"] != "bytes=0-1023"
                }
            videoBlocks shouldBe emptyList()
        }
}
