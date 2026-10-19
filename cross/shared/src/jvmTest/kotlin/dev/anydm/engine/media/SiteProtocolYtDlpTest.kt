package dev.anydm.engine.media

import dev.anydm.engine.core.CookieSource
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.EmbedChoice
import dev.anydm.engine.core.ErrorClass
import dev.anydm.engine.core.File
import dev.anydm.engine.core.FileId
import dev.anydm.engine.core.Format
import dev.anydm.engine.core.MediaSettings
import dev.anydm.engine.core.Mirror
import dev.anydm.engine.core.MirrorId
import dev.anydm.engine.core.Pacing
import dev.anydm.engine.core.PacingPreset
import dev.anydm.engine.core.SponsorBlockChoice
import dev.anydm.engine.core.SponsorBlockMode
import dev.anydm.engine.core.SubtitleChoice
import dev.anydm.engine.core.SubtitleMode
import dev.anydm.engine.core.partialPath
import dev.anydm.engine.http.FaultServer
import dev.anydm.engine.http.HttpProtocol
import dev.anydm.engine.http.OkHttpTransport
import dev.anydm.engine.platform.FakeFileSystem
import dev.anydm.engine.platform.FakeProcessRunner
import dev.anydm.engine.platform.ProcessOutcome
import dev.anydm.engine.platform.ProcessRunner
import dev.anydm.engine.platform.ProcessSpec
import dev.anydm.engine.platform.RunningProcess
import dev.anydm.engine.protocol.FinalizeResult
import dev.anydm.engine.protocol.PlanContext
import dev.anydm.engine.protocol.PlannedFile
import dev.anydm.engine.protocol.ProbeRequest
import dev.anydm.engine.protocol.StepResult
import dev.anydm.engine.protocol.Transfer
import dev.anydm.engine.protocol.TransferFile
import dev.anydm.engine.protocol.TransferPlan
import dev.anydm.engine.store.NewDownload
import dev.anydm.engine.store.Store
import dev.anydm.engine.store.openInMemoryDatabase
import dev.anydm.engine.tools.ResolvedTool
import dev.anydm.engine.tools.Tool
import dev.anydm.engine.tools.ToolVersion
import io.kotest.matchers.collections.shouldContainInOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import kotlin.test.AfterTest
import kotlin.test.Test

class SiteProtocolYtDlpTest {
    private val server = FaultServer()
    private val store = Store(openInMemoryDatabase(), Dispatchers.IO)
    private val fs = FakeFileSystem()
    private val pageUrl = "https://www.example.com/watch?v=abc"
    private var available = setOf(Tool.YT_DLP, Tool.FFMPEG, Tool.DENO)

    private class Switching(
        var current: FakeProcessRunner,
    ) : ProcessRunner {
        override suspend fun run(spec: ProcessSpec) = current.run(spec)

        override suspend fun start(spec: ProcessSpec): ProcessOutcome<RunningProcess> = current.start(spec)
    }

    @AfterTest
    fun close() {
        server.close()
        store.close()
    }

    private fun tool(tool: Tool) =
        ResolvedTool(tool, ToolVersion.parse("2026.1.1")!!, tool.executables.associateWith { Path("/tools/$it") }, managed = true)

    private fun yt(
        vararg stdout: String,
        stderr: List<String> = emptyList(),
        exit: Int = 0,
    ) = FakeProcessRunner(listOf(FakeProcessRunner.Scripted(listOf("/tools/yt-dlp"), exit, stdout.toList(), stderr)))

    private fun site(
        runner: ProcessRunner,
        settings: MediaSettings = MediaSettings(),
    ) = SiteProtocol(
        HttpProtocol(OkHttpTransport()),
        runner,
        { if (it in available) tool(it) else null },
        store.extractions,
        fs,
        mediaSettings = { settings },
    )

    private suspend fun request(format: Format = Format()): ProbeRequest {
        val download = store.downloads.add(NewDownload(DownloadKind.SITE, mirrors = listOf(pageUrl), saveFolder = "/dl"))
        return ProbeRequest(download, store.downloads.mirrors(download.id).single(), null, format)
    }

    private fun siteProbe(result: StepResult<*>) = result.shouldBeInstanceOf<StepResult.Ok<*>>().value as SiteProbe

    private class Rig(
        val site: SiteProtocol,
        val request: ProbeRequest,
        val probe: SiteProbe,
        val plan: List<PlannedFile>,
    ) {
        val download get() = request.download

        fun transfer(): Transfer {
            val files =
                plan.mapIndexed { i, planned ->
                    val target = Path("/dl", planned.path)
                    TransferFile(
                        File(FileId(i + 1L), download.id, i, planned.path, planned.size, true),
                        emptyList(),
                        partialPath(target, download.id),
                        target,
                    )
                }
            return Transfer(download, Mirror(MirrorId(1), download.id, probe.url, 0), probe, files)
        }
    }

    private suspend fun rig(
        runner: ProcessRunner,
        format: Format = Format(),
        settings: MediaSettings = MediaSettings(),
        json: () -> String,
    ): Rig {
        val site = site(runner, settings)
        val request = request(format)
        val probe = siteProbe(site.probe(request))
        val plan = ((site.plan(probe, PlanContext(request.download)) as StepResult.Ok).value as TransferPlan).files
        return Rig(site, request, probe, plan)
    }

    private fun failed(result: StepResult<*>) = result.shouldBeInstanceOf<StepResult.Failed>()

    private val staging get() = ".Test Video.1.ytdlp"

    @Test
    fun aStreamWeCannotFetchRoutesToYtDlpWithNoRequest() =
        runBlocking<Unit> {
            val probe = siteProbe(site(yt(MediaFixtures.rtmpMuxedJson())).probe(request()))

            probe.route shouldBe StreamRoute.YTDLP
            probe.probes shouldBe emptyMap()
            probe.size shouldBe null
            server.requests shouldBe emptyList()
        }

    @Test
    fun ytDlpRouteNeedsFfmpegForAMerge() =
        runBlocking<Unit> {
            available = setOf(Tool.YT_DLP)

            val missing = failed(site(yt(MediaFixtures.rtmpPairJson())).probe(request()))

            missing.errorClass shouldBe ErrorClass.TOOL_MISSING
            missing.message shouldBe "ffmpeg"
            siteProbe(site(yt(MediaFixtures.rtmpMuxedJson())).probe(request())).route shouldBe StreamRoute.YTDLP
        }

    @Test
    fun planIsOneUnsizedFile() =
        runBlocking<Unit> {
            val rig = rig(yt(MediaFixtures.rtmpMuxedJson())) { "" }

            rig.plan shouldBe listOf(PlannedFile(index = 0, path = "Test Video.ytdlp"))
        }

    @Test
    fun transferRunsTheCommandWithTheShare() =
        runBlocking<Unit> {
            val switching = Switching(yt(MediaFixtures.rtmpMuxedJson()))
            val rig = rig(switching) { "" }
            val download = yt("""{"status":"downloading","filename":"out.flv","downloaded_bytes":500,"total_bytes":900}""")
            switching.current = download
            val io = ScriptedIo { 4000 }

            rig.site.transfer(rig.transfer(), io) shouldBe StepResult.Ok(Unit)

            val command = download.started.single().command
            command.shouldContainInOrder("/tools/yt-dlp", "-f", "rtmp-1")
            command.shouldContainInOrder("--limit-rate", "4000")
            command.shouldContainInOrder("--ffmpeg-location", "/tools/ffmpeg")
            command.last() shouldBe pageUrl
            command.shouldContainInOrder("-o", "/dl/.Test Video.${rig.download.id.value}.ytdlp/out.%(ext)s")
            io.meter.bytes shouldBe 500L
            fs.exists(Path("/dl", ".Test Video.${rig.download.id.value}.ytdlp")) shouldBe true
        }

    @Test
    fun aFailedDownloadReturnsTheClassifiedFailure() =
        runBlocking<Unit> {
            val switching = Switching(yt(MediaFixtures.rtmpMuxedJson()))
            val rig = rig(switching) { "" }
            switching.current = yt(stderr = listOf("ERROR: [x] a: Private video"), exit = 1)

            failed(rig.site.transfer(rig.transfer(), ScriptedIo())).errorClass shouldBe ErrorClass.GONE
        }

    @Test
    fun finalizeMovesTheStagedFileAndCleansUp() =
        runBlocking<Unit> {
            val rig = rig(yt(MediaFixtures.rtmpMuxedJson())) { "" }
            val folder = ".Test Video.${rig.download.id.value}.ytdlp"
            fs.put(Path("/dl", folder, "out.flv"), byteArrayOf(1, 2, 3))
            fs.put(Path("/dl", folder, "out.flv.part"), byteArrayOf(9))

            val result =
                rig.site
                    .finalize(rig.transfer())
                    .shouldBeInstanceOf<StepResult.Ok<FinalizeResult>>()
                    .value

            result.outputs shouldBe listOf("Test Video.flv")
            result.consumed shouldBe listOf("Test Video.ytdlp")
            result.cleanup shouldBe listOf(folder, ".Test Video.${rig.download.id.value}.frag")
            fs.read(partialPath(Path("/dl", "Test Video.flv"), rig.download.id))!!.toList() shouldBe listOf<Byte>(1, 2, 3)
            fs.exists(Path("/dl", folder, "out.flv")) shouldBe false
        }

    @Test
    fun finalizeFailsWhenNoFileWasLeft() =
        runBlocking<Unit> {
            val rig = rig(yt(MediaFixtures.rtmpMuxedJson())) { "" }
            val folder = ".Test Video.${rig.download.id.value}.ytdlp"
            fs.put(Path("/dl", folder, "out.flv.part"), byteArrayOf(9))

            failed(rig.site.finalize(rig.transfer())).message shouldContain "left no file"
        }

    @Test
    fun finalizePicksTheExactOutputNotAStaleFile() =
        runBlocking<Unit> {
            val rig = rig(yt(MediaFixtures.rtmpMuxedJson())) { "" }
            val folder = ".Test Video.${rig.download.id.value}.ytdlp"
            fs.put(Path("/dl", folder, "out.f1.flv"), byteArrayOf(9, 9))
            fs.put(Path("/dl", folder, "out.flv"), byteArrayOf(1, 2, 3))

            rig.site.finalize(rig.transfer()).shouldBeInstanceOf<StepResult.Ok<FinalizeResult>>()

            fs.read(partialPath(Path("/dl", "Test Video.flv"), rig.download.id))!!.toList() shouldBe listOf<Byte>(1, 2, 3)
        }

    private fun transferCommand(
        format: Format,
        settings: MediaSettings,
    ): List<String> =
        runBlocking {
            val switching = Switching(yt(MediaFixtures.rtmpMuxedJson()))
            val rig = rig(switching, format, settings) { "" }
            val download = yt("""{"status":"downloading","filename":"out.flv","downloaded_bytes":5,"total_bytes":9}""")
            switching.current = download
            rig.site.transfer(rig.transfer(), ScriptedIo { 4000 }) shouldBe StepResult.Ok(Unit)
            download.started.single().command
        }

    @Test
    fun transferPassesTheChoicesTheCookiesAndThePacing() {
        val format =
            Format(
                subtitles = SubtitleChoice(listOf("en"), mode = SubtitleMode.SEPARATE),
                sponsorBlock = SponsorBlockChoice(SponsorBlockMode.CUT),
                embed = EmbedChoice(metadata = true),
            )
        val settings = MediaSettings(CookieSource.File("/home/u/cookies.txt"), Pacing(PacingPreset.BALANCED))

        val command = transferCommand(format, settings)

        command.shouldContainInOrder("--write-subs", "--sub-langs", "en")
        command.contains("--sponsorblock-remove") shouldBe true
        command.contains("--embed-metadata") shouldBe true
        command.shouldContainInOrder("--cookies", "/home/u/cookies.txt")
        command.contains("--sleep-requests") shouldBe true
        command.last() shouldBe pageUrl
    }

    @Test
    fun noCookieSourceMeansNoCookieFlag() {
        val command = transferCommand(Format(), MediaSettings(CookieSource.None, Pacing(PacingPreset.BALANCED)))

        command.contains("--cookies") shouldBe false
        command.contains("--write-subs") shouldBe false
        command.contains("--sleep-requests") shouldBe true
    }

    @Test
    fun aSponsorBlockJobNeedsFfmpeg() =
        runBlocking<Unit> {
            available = setOf(Tool.YT_DLP)

            val missing =
                failed(
                    site(
                        yt(MediaFixtures.rtmpMuxedJson()),
                    ).probe(request(Format(sponsorBlock = SponsorBlockChoice(SponsorBlockMode.MARK)))),
                )

            missing.errorClass shouldBe ErrorClass.TOOL_MISSING
            missing.message shouldBe "ffmpeg"
        }

    @Test
    fun finalizeTakesSubtitleFilesYtDlpLeftAsOutputs() =
        runBlocking<Unit> {
            val rig = rig(yt(MediaFixtures.rtmpMuxedJson()), Format(subtitles = SubtitleChoice(listOf("en", "fr")))) { "" }
            val folder = ".Test Video.${rig.download.id.value}.ytdlp"
            fs.put(Path("/dl", folder, "out.flv"), byteArrayOf(1, 2, 3))
            fs.put(Path("/dl", folder, "out.en.srt"), byteArrayOf(4))
            fs.put(Path("/dl", folder, "out.fr.vtt"), byteArrayOf(5))
            fs.put(Path("/dl", folder, "out.notes.txt"), byteArrayOf(6))

            val result =
                rig.site
                    .finalize(rig.transfer())
                    .shouldBeInstanceOf<StepResult.Ok<FinalizeResult>>()
                    .value

            result.outputs shouldBe listOf("Test Video.flv", "Test Video.en.srt", "Test Video.fr.vtt")
            fs.read(partialPath(Path("/dl", "Test Video.en.srt"), rig.download.id))!!.toList() shouldBe listOf<Byte>(4)
            fs.read(partialPath(Path("/dl", "Test Video.fr.vtt"), rig.download.id))!!.toList() shouldBe listOf<Byte>(5)
            fs.exists(partialPath(Path("/dl", "Test Video.notes.txt"), rig.download.id)) shouldBe false
        }

    @Test
    fun finalizeIgnoresSubtitleFilesWhenNoneWereAsked() =
        runBlocking<Unit> {
            val rig = rig(yt(MediaFixtures.rtmpMuxedJson())) { "" }
            val folder = ".Test Video.${rig.download.id.value}.ytdlp"
            fs.put(Path("/dl", folder, "out.flv"), byteArrayOf(1, 2, 3))
            fs.put(Path("/dl", folder, "out.en.srt"), byteArrayOf(4))

            rig.site
                .finalize(rig.transfer())
                .shouldBeInstanceOf<StepResult.Ok<FinalizeResult>>()
                .value.outputs shouldBe
                listOf("Test Video.flv")
        }

    @Test
    fun workFoldersNameTheFragmentAndStagingFolders() =
        runBlocking<Unit> {
            val rig = rig(yt(MediaFixtures.rtmpMuxedJson())) { "" }
            val id = rig.download.id.value

            rig.site.workFolders(rig.download.copy(fileName = "Test Video.flv")) shouldBe
                listOf(".Test Video.$id.frag", ".Test Video.$id.ytdlp")
            rig.site.workFolders(rig.download) shouldBe emptyList()
        }
}
