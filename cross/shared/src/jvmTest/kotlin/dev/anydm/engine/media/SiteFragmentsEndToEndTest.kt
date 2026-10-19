package dev.anydm.engine.media

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
import dev.anydm.engine.protocol.Limiter
import dev.anydm.engine.protocol.Meter
import dev.anydm.engine.store.NewDownload
import dev.anydm.engine.store.Store
import dev.anydm.engine.store.openInMemoryDatabase
import dev.anydm.engine.tools.ResolvedTool
import dev.anydm.engine.tools.Tool
import dev.anydm.engine.tools.ToolVersion
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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
 * HLS and DASH pages, and the yt-dlp fallback, through the real HTTP engine, runner, file system and process runner
 * (MED-03, MED-05, MED-07). Only the two programs are stand-ins: an ffmpeg that joins its inputs, and a yt-dlp that prints
 * a recorded page for `-J` and otherwise writes a file and prints progress lines.
 */
class SiteFragmentsEndToEndTest {
    private val root = Files.createTempDirectory("anydm-frags-e2e-").toRealPath().toFile()
    private val dl = File(root, "dl").also { it.mkdirs() }
    private val bin = File(root, "bin").also { it.mkdirs() }
    private val server = FaultServer()
    private val store = Store(openInMemoryDatabase(), Dispatchers.IO)
    private val pageUrl = "https://www.example.com/watch?v=abc"
    private val ytLog = File(bin, "yt-dlp.log")
    private val failMerge = File(bin, "fail")

    private fun script(
        name: String,
        body: String,
    ): File =
        File(bin, name).also {
            it.writeText("#!/bin/sh\n$body")
            it.setExecutable(true)
        }

    private val ytDlpScript =
        script(
            "yt-dlp",
            """
            echo "${'$'}@" >> '$ytLog'
            for a in "${'$'}@"; do if [ "${'$'}a" = "-J" ]; then cat '${File(bin, "page.json")}'; exit 0; fi; done
            out=""; prev=""
            for a in "${'$'}@"; do if [ "${'$'}prev" = "-o" ]; then out="${'$'}a"; fi; prev="${'$'}a"; done
            dir=${'$'}(dirname "${'$'}out")
            mkdir -p "${'$'}dir"
            echo '{"status":"downloading","filename":"out.mp4","downloaded_bytes":100,"total_bytes":300}'
            printf 'made-by-yt-dlp' > "${'$'}dir/out.mp4"
            echo '{"status":"finished","filename":"out.mp4","downloaded_bytes":300,"total_bytes":300}'
            """.trimIndent() + "\n",
        )
    private val ffmpegScript =
        script(
            "ffmpeg",
            """
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
    }

    @AfterTest
    fun close() {
        server.close()
        store.close()
        root.deleteRecursively()
    }

    private val base get() = server.url("/").trimEnd('/')

    private fun fragmentBytes(index: Int) = ByteArray(300) { (it + index * 31).toByte() }

    /** An HLS media playlist with an init segment and [count] fragments, all served; fragment i is served at `/hls/s<i>.ts`. */
    private fun serveHls(
        count: Int,
        extra: String = "",
    ) {
        val text =
            buildString {
                append("#EXTM3U\n$extra#EXT-X-MAP:URI=\"init.mp4\"\n")
                for (i in 0 until count) append("#EXTINF:4,\ns$i.ts\n")
                append("#EXT-X-ENDLIST\n")
            }
        server.serve("/hls/index.m3u8", text.toByteArray())
        server.serve("/hls/init.mp4", fragmentBytes(0))
        for (i in 0 until count) server.serve("/hls/s$i.ts", fragmentBytes(i + 1))
        File(bin, "page.json").writeText(MediaFixtures.hlsPageJson(base))
    }

    private fun hlsBytes(count: Int) = (0..count).map { fragmentBytes(it) }.reduce { a, b -> a + b }

    private suspend fun added(format: Format = Format()): Pair<Download, Mirror> {
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

    private fun requestsTo(
        path: String,
        after: Int = 0,
    ) = server.requests.drop(after).count { it.url.encodedPath == path }

    @Test
    fun anHlsPageDownloadsAndRewraps() =
        runBlocking<Unit> {
            serveHls(3)
            val (download, mirror) = added()

            val finished = runner.run(prepared(download, mirror)).shouldBeInstanceOf<RunOutcome.Finished>()

            File(dl, "Test Video.mp4").readBytes().contentEquals(hlsBytes(3)) shouldBe true
            names() shouldBe setOf("Test Video.mp4")
            finished.outputs.map { it.name } shouldBe listOf("Test Video.mp4")
            store.downloads.files(download.id).map { it.path } shouldBe listOf("Test Video.mp4")
        }

    @Test
    fun limiterAndMeterCoverTheFragments() =
        runBlocking<Unit> {
            serveHls(3)
            val (download, mirror) = added()
            val limiter = RecordingLimiter()
            val meter = RecordingMeter()

            runner.run(prepared(download, mirror), limiter = limiter, meter = meter).shouldBeInstanceOf<RunOutcome.Finished>()

            limiter.bytes shouldBe hlsBytes(3).size.toLong()
            meter.bytes shouldBe hlsBytes(3).size.toLong()
        }

    @Test
    fun aStopMidFragmentsResumesWithoutRefetching() =
        runBlocking<Unit> {
            serveHls(8)
            for (i in 4 until 8) server.fault("/hls/s$i.ts", Fault.Throttle(150))
            val (download, mirror) = added()
            val folder = File(dl, ".Test Video.${download.id.value}.frag/hls-720")
            val job = launch(Dispatchers.Default) { runner.run(prepared(download, mirror)) }
            while (true) {
                if ((folder.list().orEmpty().count { it.matches(Regex("\\d{6}")) }) >= 3) break
                delay(20)
            }
            job.isActive shouldBe true
            job.cancelAndJoin()
            val saved =
                folder
                    .list()
                    .orEmpty()
                    .filter { it.matches(Regex("\\d{6}")) }
                    .map { it.toInt() }
            val before = server.requests.size
            for (i in 4 until 8) server.clear("/hls/s$i.ts")

            runner.run(prepared(download, mirror)).shouldBeInstanceOf<RunOutcome.Finished>()

            File(dl, "Test Video.mp4").readBytes().contentEquals(hlsBytes(8)) shouldBe true
            for (index in saved) requestsTo(if (index == 0) "/hls/init.mp4" else "/hls/s${index - 1}.ts", after = before) shouldBe 0
            names() shouldBe setOf("Test Video.mp4")
        }

    @Test
    fun aMergeFailureKeepsTheFragmentsAndARetryFetchesNothing() =
        runBlocking<Unit> {
            serveHls(3)
            failMerge.writeText("x")
            val (download, mirror) = added()
            val failed = runner.run(prepared(download, mirror)).shouldBeInstanceOf<RunOutcome.Failed>()
            failed.failure.message shouldContain "boom: cannot merge"
            File(dl, ".Test Video.${download.id.value}.frag/hls-720").list().orEmpty().count { it.matches(Regex("\\d{6}")) } shouldBe 4
            failMerge.delete()
            val before = server.requests.size

            runner.run(prepared(download, mirror)).shouldBeInstanceOf<RunOutcome.Finished>()

            File(dl, "Test Video.mp4").readBytes().contentEquals(hlsBytes(3)) shouldBe true
            listOf("/hls/init.mp4", "/hls/s0.ts", "/hls/s1.ts", "/hls/s2.ts").sumOf { requestsTo(it, after = before) } shouldBe 0
            names() shouldBe setOf("Test Video.mp4")
        }

    @Test
    fun aDashPageUsesItsFragmentList() =
        runBlocking<Unit> {
            val pieces = listOf("v-init.mp4", "v-1.m4s", "v-2.m4s", "a-init.mp4", "a-1.m4s")
            pieces.forEachIndexed { i, name -> server.serve("/dash/$name", fragmentBytes(i)) }
            File(bin, "page.json").writeText(MediaFixtures.dashPageJson(base))
            val (download, mirror) = added()

            runner.run(prepared(download, mirror)).shouldBeInstanceOf<RunOutcome.Finished>()

            File(dl, "Dash.mp4").readBytes().contentEquals((0 until 5).map { fragmentBytes(it) }.reduce { a, b -> a + b }) shouldBe true
            names() shouldBe setOf("Dash.mp4")
        }

    @Test
    fun anEncryptedPlaylistUsesTheFallback() =
        runBlocking<Unit> {
            serveHls(3, extra = "#EXT-X-KEY:METHOD=AES-128,URI=\"k.key\"\n")
            val (download, mirror) = added()
            val meter = RecordingMeter()

            val finished =
                runner
                    .run(prepared(download, mirror), RunListener.None, Limiter.None, meter) { 1_000_000L }
                    .shouldBeInstanceOf<RunOutcome.Finished>()

            File(dl, "Test Video.mp4").readText() shouldBe "made-by-yt-dlp"
            names() shouldBe setOf("Test Video.mp4")
            finished.outputs.map { it.name } shouldBe listOf("Test Video.mp4")
            ytLog.readLines().last() shouldContain "--limit-rate 1000000"
            ytLog.readLines().last() shouldContain "-f hls-720"
            meter.bytes shouldBe 300L
            requestsTo("/hls/s0.ts") shouldBe 0
        }
}
