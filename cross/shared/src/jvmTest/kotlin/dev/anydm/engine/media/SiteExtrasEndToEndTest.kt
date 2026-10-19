package dev.anydm.engine.media

import dev.anydm.engine.core.CookieSource
import dev.anydm.engine.core.Download
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.Format
import dev.anydm.engine.core.MediaSettings
import dev.anydm.engine.core.Mirror
import dev.anydm.engine.core.Pacing
import dev.anydm.engine.core.PacingPreset
import dev.anydm.engine.core.PrepareOutcome
import dev.anydm.engine.core.RunListener
import dev.anydm.engine.core.RunOutcome
import dev.anydm.engine.core.SponsorBlockChoice
import dev.anydm.engine.core.SponsorBlockMode
import dev.anydm.engine.core.StepRunner
import dev.anydm.engine.core.SubtitleChoice
import dev.anydm.engine.core.SubtitleMode
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
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
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
 * Subtitles, embedding, SponsorBlock, cookies and pacing through the real HTTP engine, runner, file system and process
 * runner (MED-12 to MED-20). Only the two programs are stand-ins: an ffmpeg that joins its inputs and logs its command
 * line, and a yt-dlp that prints a recorded page for `-J`, logs its arguments, and otherwise writes `out.mp4` and
 * `out.en.vtt` beside the `-o` path.
 */
class SiteExtrasEndToEndTest {
    private val root = Files.createTempDirectory("anydm-extras-e2e-").toRealPath().toFile()
    private val dl = File(root, "dl").also { it.mkdirs() }
    private val bin = File(root, "bin").also { it.mkdirs() }
    private val server = FaultServer()
    private val store = Store(openInMemoryDatabase(), Dispatchers.IO)
    private val pageUrl = "https://www.example.com/watch?v=e1"
    private val ytLog = File(bin, "yt-dlp.log")
    private val ffLog = File(bin, "ffmpeg.log")
    private var settings = MediaSettings()

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
            printf 'WEBVTT' > "${'$'}dir/out.en.vtt"
            echo '{"status":"finished","filename":"out.mp4","downloaded_bytes":300,"total_bytes":300}'
            """.trimIndent() + "\n",
        )
    private val ffmpegScript =
        script(
            "ffmpeg",
            """
            echo "${'$'}@" >> '$ffLog'
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
            mediaSettings = { settings },
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

    /** The page of [MediaFixtures.extrasVideoJson] with its streams, an English SRT and a French WebVTT served. */
    private fun servePage() {
        server.serve("/v/v1", ByteArray(100) { 1 })
        server.serve("/v/140", ByteArray(100) { 2 })
        server.serve("/s/en.srt", "1\n00:00:00,000 --> 00:00:01,000\nHello\n".toByteArray())
        server.serve("/s/fr.vtt", "WEBVTT\n".toByteArray())
        File(bin, "page.json").writeText(MediaFixtures.extrasVideoJson(base))
    }

    private suspend fun added(format: Format): Pair<Download, Mirror> {
        val download =
            store.downloads.add(
                NewDownload(DownloadKind.SITE, mirrors = listOf(pageUrl), saveFolder = dl.toString(), format = format),
            )
        return download to store.downloads.mirrors(download.id).single()
    }

    private suspend fun finished(format: Format): Pair<Download, RunOutcome.Finished> {
        val (download, mirror) = added(format)
        val ready = runner.prepare(download, mirror, RunListener.None).shouldBeInstanceOf<PrepareOutcome.Ready>()
        return download to runner.run(ready).shouldBeInstanceOf<RunOutcome.Finished>()
    }

    private fun names() = dl.list().orEmpty().toSet()

    private fun logged(file: File) = if (file.exists()) file.readLines() else emptyList()

    @Test
    fun aVideoWithASeparateSubtitleHasTwoOutputs() =
        runBlocking<Unit> {
            servePage()

            val (download, outcome) = finished(Format(subtitles = SubtitleChoice(listOf("en"))))

            names() shouldBe setOf("Extras.mp4", "Extras.en.srt")
            outcome.outputs.map { it.name }.toSet() shouldBe setOf("Extras.mp4", "Extras.en.srt")
            File(dl, "Extras.en.srt").readText() shouldContain "Hello"
            store.downloads
                .files(download.id)
                .map { it.placedPath }
                .toSet() shouldBe setOf("Extras.mp4", "Extras.en.srt")
        }

    @Test
    fun anEmbeddedSubtitleIsOneOutputWithTheTrackMapped() =
        runBlocking<Unit> {
            servePage()

            finished(Format(subtitles = SubtitleChoice(listOf("en"), mode = SubtitleMode.EMBED)))

            names() shouldBe setOf("Extras.mp4")
            val merge = logged(ffLog).single { it.contains("-map") }
            merge shouldContain "-c:s mov_text"
            merge shouldContain "-metadata:s:s:0 language=eng"
        }

    @Test
    fun aMissingSubtitleStillDeliversTheVideo() =
        runBlocking<Unit> {
            servePage()
            server.fault("/s/en.srt", Fault.Status(404))

            val (_, outcome) = finished(Format(subtitles = SubtitleChoice(listOf("en"))))

            names() shouldBe setOf("Extras.mp4")
            outcome.outputs.map { it.name } shouldBe listOf("Extras.mp4")
        }

    @Test
    fun everyOutputIsARowTheTrashCanFind() =
        runBlocking<Unit> {
            servePage()

            val (download, _) = finished(Format(subtitles = SubtitleChoice(listOf("en", "fr"))))

            val rows = store.downloads.files(download.id)
            rows.map { it.placedPath }.toSet() shouldBe setOf("Extras.mp4", "Extras.en.srt", "Extras.fr.vtt")
            rows.all { File(dl, it.placedPath!!).exists() } shouldBe true
        }

    @Test
    fun aSponsorBlockJobUsesTheFallback() =
        runBlocking<Unit> {
            servePage()

            finished(Format(sponsorBlock = SponsorBlockChoice(SponsorBlockMode.CUT)))

            File(dl, "Extras.mp4").readText() shouldBe "made-by-yt-dlp"
            names() shouldBe setOf("Extras.mp4")
            logged(ytLog).last() shouldContain "--sponsorblock-remove"
            server.requests.none { it.url.encodedPath.startsWith("/v/") } shouldBe true
        }

    @Test
    fun theFallbackKeepsSubtitleFilesAsOutputs() =
        runBlocking<Unit> {
            servePage()

            finished(Format(sponsorBlock = SponsorBlockChoice(SponsorBlockMode.MARK), subtitles = SubtitleChoice(listOf("en"))))

            names() shouldBe setOf("Extras.mp4", "Extras.en.vtt")
            logged(ytLog).last() shouldContain "--write-subs"
        }

    @Test
    fun cookiesReachOnlyTheExtraction() =
        runBlocking<Unit> {
            servePage()
            val cookies = File(root, "cookies.txt").also { it.writeText("# Netscape HTTP Cookie File\n") }
            settings = MediaSettings(CookieSource.File(cookies.toString()))

            finished(Format())

            logged(ytLog).first() shouldContain "-J"
            logged(ytLog).first() shouldContain "--cookies $cookies"
            server.requests.none { it.headers["Cookie"] != null } shouldBe true
        }

    @Test
    fun pacingReachesYtDlp() =
        runBlocking<Unit> {
            servePage()
            settings = MediaSettings(pacing = Pacing(PacingPreset.BALANCED))

            finished(Format())

            logged(ytLog).first() shouldContain "--sleep-requests 1.0"
        }
}
