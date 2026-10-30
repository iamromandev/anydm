package dev.anydm.engine.media

import dev.anydm.engine.core.CookieSource
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.EmbedChoice
import dev.anydm.engine.core.ErrorClass
import dev.anydm.engine.core.Format
import dev.anydm.engine.core.MediaSettings
import dev.anydm.engine.core.Pacing
import dev.anydm.engine.core.PacingPreset
import dev.anydm.engine.core.SubtitleChoice
import dev.anydm.engine.http.FaultServer
import dev.anydm.engine.http.HttpProtocol
import dev.anydm.engine.http.OkHttpTransport
import dev.anydm.engine.log.Redactor
import dev.anydm.engine.platform.DesktopFileSystem
import dev.anydm.engine.platform.FakeProcessRunner
import dev.anydm.engine.platform.ProcessOutcome
import dev.anydm.engine.platform.ProcessRunner
import dev.anydm.engine.platform.ProcessSpec
import dev.anydm.engine.platform.RunningProcess
import dev.anydm.engine.protocol.ProbeRequest
import dev.anydm.engine.protocol.StepResult
import dev.anydm.engine.store.NewDownload
import dev.anydm.engine.store.Store
import dev.anydm.engine.store.openInMemoryDatabase
import dev.anydm.engine.tools.ResolvedTool
import dev.anydm.engine.tools.Tool
import dev.anydm.engine.tools.ToolVersion
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import kotlin.test.AfterTest
import kotlin.test.Test

class SiteProtocolExtrasProbeTest {
    private val server = FaultServer()
    private val store = Store(openInMemoryDatabase(), Dispatchers.IO)
    private val base get() = server.url("/").trimEnd('/')
    private val pageUrl = "https://www.example.com/watch?v=abc"
    private var settings = MediaSettings()
    private val cookieFile = Path(kotlinx.io.files.SystemTemporaryDirectory, "cookies.txt").toString()

    /** Answers the n-th extraction with the n-th script (the last repeats) and records every command. */
    private class Sequence(
        private val answers: List<FakeProcessRunner.Scripted>,
    ) : ProcessRunner {
        val started = mutableListOf<ProcessSpec>()
        private var next = 0

        private fun runner(spec: ProcessSpec): FakeProcessRunner {
            started += spec
            return FakeProcessRunner(listOf(answers[minOf(next++, answers.lastIndex)]))
        }

        override suspend fun run(spec: ProcessSpec) = runner(spec).run(spec)

        override suspend fun start(spec: ProcessSpec): ProcessOutcome<RunningProcess> = runner(spec).start(spec)
    }

    @AfterTest
    fun close() {
        server.close()
        store.close()
    }

    private fun answer(
        json: String,
        stderr: List<String> = emptyList(),
    ) = FakeProcessRunner.Scripted(listOf("/tools/yt-dlp"), 0, listOf(json), stderr)

    private fun tool(tool: Tool) =
        ResolvedTool(tool, ToolVersion.parse("2026.1.1")!!, tool.executables.associateWith { Path("/tools/$it") }, managed = true)

    private fun site(runner: ProcessRunner) =
        SiteProtocol(
            HttpProtocol(OkHttpTransport()),
            runner,
            { tool(it) },
            store.extractions,
            DesktopFileSystem(),
            mediaSettings = { settings },
        )

    private suspend fun request(format: Format? = null): ProbeRequest {
        val download = store.downloads.add(NewDownload(DownloadKind.SITE, mirrors = listOf(pageUrl), saveFolder = "/dl"))
        return ProbeRequest(download, store.downloads.mirrors(download.id).single(), null, format)
    }

    private fun serveAll() {
        for (id in listOf("137", "140", "251", "22", "av1", "v1")) server.serve("/v/$id", ByteArray(1000) { 1 })
    }

    private fun siteProbe(result: StepResult<*>) = result.shouldBeInstanceOf<StepResult.Ok<*>>().value as SiteProbe

    private fun failed(result: StepResult<*>) = result.shouldBeInstanceOf<StepResult.Failed>()

    private val withCookies get() = MediaSettings(CookieSource.File(cookieFile))

    @Test
    fun cookieFlagsGoToTheExtraction() =
        runBlocking<Unit> {
            serveAll()
            settings = withCookies
            val file = Sequence(listOf(answer(MediaFixtures.splitVideoJson(base))))

            siteProbe(site(file).probe(request()))

            file.started
                .single()
                .command
                .windowed(2)
                .contains(listOf("--cookies", cookieFile)) shouldBe true

            settings = MediaSettings(CookieSource.Browser("firefox", "default"))
            val browser = Sequence(listOf(answer(MediaFixtures.splitVideoJson(base))))
            siteProbe(site(browser).probe(request()))
            browser.started
                .single()
                .command
                .windowed(2)
                .contains(listOf("--cookies-from-browser", "firefox:default")) shouldBe true
        }

    @Test
    fun pacingFlagsGoToTheExtraction() =
        runBlocking<Unit> {
            serveAll()
            settings = MediaSettings(pacing = Pacing(PacingPreset.BALANCED))
            val runner = Sequence(listOf(answer(MediaFixtures.splitVideoJson(base))))

            siteProbe(site(runner).probe(request()))

            runner.started
                .single()
                .command
                .windowed(2)
                .contains(listOf("--sleep-requests", "1.0")) shouldBe true
        }

    @Test
    fun aNoMatchWithCookiesRetriesWithoutThem() =
        runBlocking<Unit> {
            serveAll()
            settings = withCookies
            val runner = Sequence(listOf(answer(MediaFixtures.splitVideoJson(base)), answer(MediaFixtures.av1Json(base))))

            val probe = siteProbe(site(runner).probe(request(Format(videoCodec = "av01"))))

            runner.started.size shouldBe 2
            runner.started[0].command.contains("--cookies") shouldBe true
            runner.started[1].command.contains("--cookies") shouldBe false
            probe.withCookies shouldBe false
            probe.selection.video!!.formatId shouldBe "av1"
        }

    @Test
    fun aHintWithAnEqualResultKeepsTheCookieOne() =
        runBlocking<Unit> {
            serveAll()
            settings = withCookies
            val hint = listOf("WARNING: Some web client https formats have been skipped")
            val runner = Sequence(listOf(answer(MediaFixtures.splitVideoJson(base), hint), answer(MediaFixtures.splitVideoJson(base))))

            val probe = siteProbe(site(runner).probe(request()))

            runner.started.size shouldBe 2
            probe.withCookies shouldBe true
        }

    @Test
    fun aHintWithATallerAnonymousResultUsesIt() =
        runBlocking<Unit> {
            serveAll()
            settings = withCookies
            val hint = listOf("WARNING: Some web client https formats have been skipped")
            val runner = Sequence(listOf(answer(MediaFixtures.mixedOnlyJson(base), hint), answer(MediaFixtures.splitVideoJson(base))))
            val req = request()

            val probe = siteProbe(site(runner).probe(req))

            probe.withCookies shouldBe false
            probe.selection.video!!.height shouldBe 1080
            store.extractions.get(req.download.id)!!.withoutCookies shouldBe true
        }

    @Test
    fun bothFailingIsFormatWithheldAndTheRetryHappensOnce() =
        runBlocking<Unit> {
            serveAll()
            settings = withCookies
            val runner = Sequence(listOf(answer(MediaFixtures.splitVideoJson(base))))

            val result = failed(site(runner).probe(request(Format(videoCodec = "av01"))))

            result.errorClass shouldBe ErrorClass.FORMAT_WITHHELD
            runner.started.size shouldBe 2
        }

    @Test
    fun noCookiesMeansNoRetry() =
        runBlocking<Unit> {
            serveAll()
            val runner = Sequence(listOf(answer(MediaFixtures.splitVideoJson(base))))

            val result = failed(site(runner).probe(request(Format(videoCodec = "av01"))))

            result.errorClass shouldBe null
            runner.started.size shouldBe 1
        }

    @Test
    fun aCachedCooklessExtractionIsNotAskedWithCookiesAgain() =
        runBlocking<Unit> {
            serveAll()
            settings = withCookies
            val hint = listOf("WARNING: Some web client https formats have been skipped")
            val runner = Sequence(listOf(answer(MediaFixtures.mixedOnlyJson(base), hint), answer(MediaFixtures.splitVideoJson(base))))
            val site = site(runner)
            val req = request()
            site.probe(req)

            val again = siteProbe(site.probe(req))

            runner.started.size shouldBe 2
            again.withCookies shouldBe false
        }

    @Test
    fun extrasAreComputedFromTheChoice() =
        runBlocking<Unit> {
            serveAll()
            val runner = Sequence(listOf(answer(MediaFixtures.extrasVideoJson(base))))
            val format =
                Format(
                    subtitles = SubtitleChoice(listOf("en", "fr")),
                    embed = EmbedChoice(metadata = true, thumbnail = true, chapters = true),
                )

            val extras = siteProbe(site(runner).probe(request(format))).extras

            extras.subtitles.map { it.lang to it.ext } shouldBe listOf("en" to "srt", "fr" to "vtt")
            extras.thumbnail!!.thumbnail.url shouldBe "$base/t/b.jpg?x=1"
            extras.thumbnail!!.needsConversion shouldBe false
            extras.chapters.map { it.title } shouldBe listOf("Intro", "Main")
            extras.metadata shouldBe
                listOf(
                    "title" to "Extras",
                    "artist" to "Chan",
                    "date" to "2026-01-31",
                    "comment" to "https://www.example.com/watch?v=e1",
                    "description" to "About it",
                )
        }

    @Test
    fun noExtrasWhenNotAsked() =
        runBlocking<Unit> {
            serveAll()
            val runner = Sequence(listOf(answer(MediaFixtures.extrasVideoJson(base))))

            val extras = siteProbe(site(runner).probe(request())).extras

            extras.subtitles shouldBe emptyList()
            extras.thumbnail shouldBe null
            extras.chapters shouldBe emptyList()
            extras.metadata shouldBe emptyList()
        }

    @Test
    fun theCookieFlagIsMaskedInALogLine() {
        val line = "yt-dlp --cookies $cookieFile -J https://x.test/"

        Redactor().redact(line) shouldNotContain cookieFile
        Redactor().redact("yt-dlp --cookies-from-browser firefox:default -J") shouldNotContain "firefox"
    }
}
