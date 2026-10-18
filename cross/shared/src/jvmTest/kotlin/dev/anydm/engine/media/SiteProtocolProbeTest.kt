package dev.anydm.engine.media

import dev.anydm.engine.core.AudioFormat
import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.ErrorClass
import dev.anydm.engine.core.File
import dev.anydm.engine.core.FileId
import dev.anydm.engine.core.Format
import dev.anydm.engine.core.Segment
import dev.anydm.engine.core.SegmentId
import dev.anydm.engine.files.NameFields
import dev.anydm.engine.files.TemplateField
import dev.anydm.engine.http.Fault
import dev.anydm.engine.http.FaultServer
import dev.anydm.engine.http.HttpProbe
import dev.anydm.engine.http.HttpProtocol
import dev.anydm.engine.http.OkHttpTransport
import dev.anydm.engine.platform.DesktopFileSystem
import dev.anydm.engine.platform.FakeProcessRunner
import dev.anydm.engine.protocol.PlanContext
import dev.anydm.engine.protocol.PlannedFile
import dev.anydm.engine.protocol.ProbeRequest
import dev.anydm.engine.protocol.StepResult
import dev.anydm.engine.protocol.TransferFile
import dev.anydm.engine.protocol.TransferPlan
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
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class SiteProtocolProbeTest {
    private val server = FaultServer()
    private val store = Store(openInMemoryDatabase(), Dispatchers.IO)
    private var now = Instant.fromEpochMilliseconds(1_700_000_000_000)
    private val clock =
        object : Clock {
            override fun now() = now
        }
    private val base get() = server.url("/").trimEnd('/')
    private val pageUrl = "https://www.example.com/watch?v=abc"
    private val resolved = mutableListOf<Tool>()
    private var available: Set<Tool> = setOf(Tool.YT_DLP, Tool.FFMPEG, Tool.DENO)
    private val video = ByteArray(4000) { 1 }
    private val audio = ByteArray(1000) { 2 }

    @AfterTest
    fun close() {
        server.close()
        store.close()
    }

    private fun tool(tool: Tool) =
        ResolvedTool(tool, ToolVersion.parse("2026.1.1")!!, tool.executables.associateWith { Path("/tools/$it") }, managed = true)

    private fun protocol(
        runner: dev.anydm.engine.platform.ProcessRunner,
        beforeExtract: suspend () -> Unit = {},
    ) = SiteProtocol(
        HttpProtocol(OkHttpTransport()),
        runner,
        { t ->
            resolved += t
            if (t in available) tool(t) else null
        },
        store.extractions,
        DesktopFileSystem(),
        clock,
        beforeExtract,
    )

    private fun yt(
        stdout: List<String> = emptyList(),
        stderr: List<String> = emptyList(),
        exit: Int = 0,
    ) = FakeProcessRunner(listOf(FakeProcessRunner.Scripted(listOf("/tools/yt-dlp"), exit, stdout, stderr)))

    private fun serveStreams() {
        server.serve("/v/137", video)
        server.serve("/v/140", audio)
        server.serve("/v/251", audio)
        server.serve("/v/22", video)
    }

    private suspend fun request(
        url: String = pageUrl,
        format: Format? = null,
    ): ProbeRequest {
        val download = store.downloads.add(NewDownload(DownloadKind.SITE, mirrors = listOf(url), saveFolder = "/dl"))
        return ProbeRequest(download, store.downloads.mirrors(download.id).single(), null, format)
    }

    private fun failed(result: StepResult<*>) = result.shouldBeInstanceOf<StepResult.Failed>()

    private fun siteProbe(result: StepResult<*>) = (result.shouldBeInstanceOf<StepResult.Ok<*>>().value as SiteProbe)

    private val mp4 = Format(audioCodec = "mp4a")

    @Test
    fun probeRunsYtDlpAndChoosesTheStreams() =
        runBlocking<Unit> {
            serveStreams()
            val runner = yt(listOf(MediaFixtures.splitVideoJson(base)))

            val probe = siteProbe(protocol(runner).probe(request(format = mp4)))

            runner.started.single().command shouldBe listOf("/tools/yt-dlp", "-J", "--no-playlist", "--no-warnings", "--", pageUrl)
            probe.selection.streams.map { it.formatId } shouldBe listOf("137", "140")
            probe.name shouldBe "Test Video.mp4"
            probe.size shouldBe 5000L
            probe.nameFields.values[TemplateField.TITLE] shouldBe "Test Video"
            probe.nameFields.values[TemplateField.UPLOADER] shouldBe "Some Channel"
            probe.nameFields.values[TemplateField.DATE] shouldBe "2026-01-31"
            probe.nameFields.values[TemplateField.RESOLUTION] shouldBe "1080p"
            probe.nameFields.values[TemplateField.ID] shouldBe "abc123"
        }

    @Test
    fun aFreshCacheIsUsedAndAnOldOneIsNot() =
        runBlocking<Unit> {
            serveStreams()
            val runner = yt(listOf(MediaFixtures.splitVideoJson(base)))
            val site = protocol(runner)
            val req = request(format = mp4)

            site.probe(req)
            site.probe(req)
            runner.started.size shouldBe 1

            now += 61.minutes
            site.probe(req)
            runner.started.size shouldBe 2
        }

    @Test
    fun beforeExtractRunsOncePerFreshExtraction() =
        runBlocking<Unit> {
            serveStreams()
            var calls = 0
            val site = protocol(yt(listOf(MediaFixtures.splitVideoJson(base))), beforeExtract = { calls++ })
            val req = request(format = mp4)

            site.probe(req)
            site.probe(req)
            calls shouldBe 1

            now += 61.minutes
            site.probe(req)
            calls shouldBe 2
        }

    @Test
    fun aMissingYtDlpIsToolMissing() =
        runBlocking<Unit> {
            available = setOf(Tool.FFMPEG)

            val result = failed(protocol(yt()).probe(request()))

            result.errorClass shouldBe ErrorClass.TOOL_MISSING
            result.message shouldBe "yt-dlp"
        }

    @Test
    fun aBinaryThatCannotStartIsToolMissing() =
        runBlocking<Unit> {
            val result = failed(protocol(FakeProcessRunner(emptyList())).probe(request()))

            result.errorClass shouldBe ErrorClass.TOOL_MISSING
            result.message shouldBe "yt-dlp"
        }

    @Test
    fun youtubeAsksForDenoAndOthersDoNot() =
        runBlocking<Unit> {
            serveStreams()
            val json = MediaFixtures.splitVideoJson(base)
            val runner = yt(listOf(json))

            protocol(runner).probe(request(format = mp4))
            resolved.contains(Tool.DENO) shouldBe false

            available = setOf(Tool.YT_DLP, Tool.FFMPEG)
            val missing = failed(protocol(yt(listOf(json))).probe(request("https://www.youtube.com/watch?v=abc", mp4)))
            missing.errorClass shouldBe ErrorClass.TOOL_MISSING
            missing.message shouldBe "deno"

            available = setOf(Tool.YT_DLP, Tool.FFMPEG, Tool.DENO)
            val withDeno = yt(listOf(json))
            protocol(withDeno).probe(request("https://youtu.be/abc", mp4))
            withDeno.started
                .single()
                .command
                .takeLast(4) shouldBe
                listOf("--js-runtimes", "deno:/tools/deno", "--", "https://youtu.be/abc")
        }

    @Test
    fun ffmpegIsAskedForOnlyWhenNeeded() =
        runBlocking<Unit> {
            serveStreams()
            available = setOf(Tool.YT_DLP)

            siteProbe(protocol(yt(listOf(MediaFixtures.mixedOnlyJson(base)))).probe(request()))
            resolved.contains(Tool.FFMPEG) shouldBe false

            val merge = failed(protocol(yt(listOf(MediaFixtures.splitVideoJson(base)))).probe(request(format = mp4)))
            merge.errorClass shouldBe ErrorClass.TOOL_MISSING
            merge.message shouldBe "ffmpeg"
        }

    @Test
    fun ytDlpErrorsAreClassified() =
        runBlocking<Unit> {
            val private =
                failed(
                    protocol(
                        yt(stderr = listOf("ERROR: [youtube] abc: Private video. Sign in if you've been granted access"), exit = 1),
                    ).probe(request()),
                )
            private.errorClass shouldBe ErrorClass.GONE
            private.message shouldContain "Private video"

            val limited = failed(protocol(yt(stderr = listOf("ERROR: HTTP Error 429: Too Many Requests"), exit = 1)).probe(request()))
            limited.errorClass shouldBe ErrorClass.RATE_LIMITED

            val offline = failed(protocol(yt(stderr = listOf("ERROR: Unable to download webpage: timed out"), exit = 1)).probe(request()))
            offline.errorClass shouldBe ErrorClass.TRANSIENT

            val unsupported = failed(protocol(yt(stderr = listOf("ERROR: Unsupported URL: https://x.test/a"), exit = 1)).probe(request()))
            unsupported.errorClass shouldBe null
            unsupported.message shouldContain "Unsupported URL"
        }

    @Test
    fun warningsBeforeTheJsonAreSkipped() =
        runBlocking<Unit> {
            serveStreams()
            val runner = yt(listOf("WARNING: something odd", MediaFixtures.splitVideoJson(base)))

            siteProbe(protocol(runner).probe(request(format = mp4))).selection.streams.size shouldBe 2
        }

    @Test
    fun junkOutputIsAFailureNotACrash() =
        runBlocking<Unit> {
            failed(protocol(yt(listOf("<html>"))).probe(request())).message shouldContain "JSON"
            failed(protocol(yt(listOf("{not json"))).probe(request())).message shouldContain "JSON"
            failed(protocol(yt()).probe(request())).message shouldContain "JSON"
        }

    @Test
    fun playlistAndLiveAreRefusedWithAMessage() =
        runBlocking<Unit> {
            failed(protocol(yt(listOf(MediaFixtures.playlistJson()))).probe(request())).message shouldContain "playlist"
            failed(protocol(yt(listOf(MediaFixtures.liveJson(base)))).probe(request())).message shouldContain "live"
        }

    @Test
    fun hlsOnlyIsRefusedWithAReason() =
        runBlocking<Unit> {
            val result = failed(protocol(yt(listOf(MediaFixtures.hlsOnlyJson(base)))).probe(request()))

            result.message shouldContain "HLS"
            result.errorClass shouldBe null
        }

    @Test
    fun aJunkReplyIsNotCached() =
        runBlocking<Unit> {
            val runner = yt(listOf("{not json"))
            val site = protocol(runner)
            val req = request()

            site.probe(req)
            site.probe(req)

            runner.started.size shouldBe 2
        }

    @Test
    fun aStaleCachedUrlIsExtractedAgain() =
        runBlocking<Unit> {
            serveStreams()
            val runner = yt(listOf(MediaFixtures.splitVideoJson(base)))
            val site = protocol(runner)
            val req = request(format = mp4)
            site.probe(req)
            server.fault("/v/137", Fault.Once(Fault.Status(403)))

            siteProbe(site.probe(req))

            runner.started.size shouldBe 2
        }

    @Test
    fun awkwardTitlesBecomeSafeStems() {
        fileStem("a/b:c*?", "x") shouldBe "a_b_c__"
        fileStem("a\u0000b\tc", "x") shouldBe "a_b c"
        fileStem("  name. . ", "x") shouldBe "name"
        fileStem("   ", "abc123") shouldBe "abc123"
        fileStem("...", "abc123") shouldBe "abc123"
        fileStem("", "") shouldBe "media"
        fileStem("a".repeat(300), "x").length shouldBe 120
        fileStem("a".repeat(119) + "😀", "x") shouldBe "a".repeat(119)
        fileStem("😀 ok", "x") shouldBe "😀 ok"
        fileStem("lone\uD83Dsurrogate", "x") shouldBe "lone_surrogate"
    }

    private suspend fun planned(
        site: SiteProtocol,
        probe: SiteProbe,
        stored: List<TransferFile> = emptyList(),
        fileName: String? = null,
    ): List<PlannedFile> {
        val download = store.downloads.add(NewDownload(DownloadKind.SITE, mirrors = listOf(pageUrl), fileName = fileName))
        val plan = site.plan(probe, PlanContext(download, stored))
        return plan.shouldBeInstanceOf<StepResult.Ok<*>>().value.let { (it as TransferPlan).files }
    }

    @Test
    fun planNamesStreamFilesAndCoversTheirSizes() =
        runBlocking<Unit> {
            serveStreams()
            val site = protocol(yt(listOf(MediaFixtures.splitVideoJson(base))))
            val probe = siteProbe(site.probe(request(format = mp4)))

            val files = planned(site, probe)

            files.map { it.path } shouldBe listOf("Test Video.f137.mp4", "Test Video.f140.m4a")
            files.map { it.index } shouldBe listOf(0, 1)
            files.map { it.size } shouldBe listOf(4000L, 1000L)
            files.forEach {
                it.segments.first().start shouldBe 0L
                it.segments.last().end shouldBe it.size
            }
        }

    @Test
    fun aSingleStreamUsesTheOutputName() =
        runBlocking<Unit> {
            serveStreams()
            val site = protocol(yt(listOf(MediaFixtures.mixedOnlyJson(base))))
            val probe = siteProbe(site.probe(request()))

            planned(site, probe).map { it.path } shouldBe listOf("Test Video.mp4")
            planned(site, probe, fileName = "mine.mp4").map { it.path } shouldBe listOf("mine.mp4")
        }

    @Test
    fun aConvertedStreamKeepsItsOwnFileNameSoTheOutputCanTakeTheName() =
        runBlocking<Unit> {
            serveStreams()
            val site = protocol(yt(listOf(MediaFixtures.audioOnlyJson(base))))
            val probe = siteProbe(site.probe(request(format = Format(audioOnly = AudioFormat.MP3))))

            probe.name shouldBe "Test Video.mp3"
            planned(site, probe).map { it.path } shouldBe listOf("Test Video.f251.webm")
        }

    @Test
    fun planResumesStoredSegments() =
        runBlocking<Unit> {
            serveStreams()
            val site = protocol(yt(listOf(MediaFixtures.mixedOnlyJson(base))))
            val probe = siteProbe(site.probe(request()))
            val file = File(FileId(1), DownloadId(1), 0, "Test Video.mp4", 4000L, true)
            val stored =
                TransferFile(
                    file,
                    listOf(Segment(SegmentId(1), FileId(1), 0, 1000), Segment(SegmentId(2), FileId(1), 1000, 4000)),
                    Path("/dl/p.anydm"),
                    Path("/dl/Test Video.mp4"),
                )

            val segments = planned(site, probe, listOf(stored)).single().segments

            segments.map { it.start to it.end } shouldBe listOf(0L to 1000L, 1000L to 4000L)
        }

    @Test
    fun aStreamOfUnknownSizePlansNoSegments() =
        runBlocking<Unit> {
            val info = (YtDlpJson.parse(MediaFixtures.mixedOnlyJson(base)) as MediaParse.Ok).info
            val selection = (FormatSelector.choose(info, Format()) as Choice.Ok).selection
            val unknown = HttpProbe("22", null, null, true, "$base/v/22", null)
            val probe =
                SiteProbe(info, selection, Format(), pageUrl, null, mapOf("22" to unknown), "Test Video.mp4", null, null, NameFields())

            val site = protocol(yt())
            val file = planned(site, probe).single()

            file.size shouldBe null
            file.segments shouldBe emptyList()
            totalSize(listOf(unknown, unknown.copy(size = 5))) shouldBe null
            totalSize(listOf(unknown.copy(size = 3), unknown.copy(size = 5))) shouldBe 8L
        }

    @Test
    fun aStreamThatIgnoresRangesPlansNoSegments() =
        runBlocking<Unit> {
            serveStreams()
            server.fault("/v/22", Fault.IgnoreRanges)
            val site = protocol(yt(listOf(MediaFixtures.mixedOnlyJson(base))))
            val probe = siteProbe(site.probe(request()))

            planned(site, probe).single().segments shouldBe emptyList()
        }

    @Test
    fun aFreshExtractionKeepsTheStreamsAlreadyChosen() =
        runBlocking<Unit> {
            serveStreams()
            server.serve("/v/999", video)
            val first = yt(listOf(MediaFixtures.splitVideoJson(base)))
            val switching = Switching(first)
            val site = protocol(switching)
            val req = request()
            val before = siteProbe(site.probe(req)).selection.streams.map { it.formatId }

            val better =
                MediaFixtures.splitVideoJson(base).replace(
                    """"formats":[""",
                    """"formats":[{"format_id":"999","url":"$base/v/999","ext":"mp4","protocol":"https","vcodec":"avc1","acodec":"none","height":2160,"fps":30,"tbr":9000,"filesize":1},""",
                )
            switching.current = yt(listOf(better))
            now += 61.minutes

            siteProbe(site.probe(req)).selection.streams.map { it.formatId } shouldBe before
        }

    @Test
    fun aChosenStreamThatIsGoneIsChosenAgain() =
        runBlocking<Unit> {
            serveStreams()
            val switching = Switching(yt(listOf(MediaFixtures.splitVideoJson(base))))
            val site = protocol(switching)
            val req = request()
            siteProbe(site.probe(req)).selection.streams.map { it.formatId } shouldBe listOf("137", "251")

            switching.current = yt(listOf(MediaFixtures.splitVideoJson(base).replace("\"format_id\":\"251\"", "\"format_id\":\"252\"")))
            server.serve("/v/252", audio)
            now += 61.minutes

            siteProbe(site.probe(req)).selection.streams.map { it.formatId } shouldBe listOf("137", "252")
        }

    @Test
    fun templateFieldsHaveNoPathCharacters() =
        runBlocking<Unit> {
            serveStreams()
            val json =
                MediaFixtures
                    .withTitle(
                        "Interview: Part 1",
                        base,
                    ).replace("\"uploader\":\"Some Channel\"", "\"uploader\":\"Ann\\\\Bo\"")

            val probe = siteProbe(protocol(yt(listOf(json))).probe(request()))

            probe.nameFields.values[TemplateField.TITLE] shouldBe "Interview_ Part 1"
            probe.nameFields.values[TemplateField.UPLOADER] shouldBe "Ann_Bo"
        }

    /** A runner whose answer can be changed between calls. */
    private class Switching(
        var current: FakeProcessRunner,
    ) : dev.anydm.engine.platform.ProcessRunner {
        override suspend fun run(spec: dev.anydm.engine.platform.ProcessSpec) = current.run(spec)

        override suspend fun start(spec: dev.anydm.engine.platform.ProcessSpec) = current.start(spec)
    }
}
