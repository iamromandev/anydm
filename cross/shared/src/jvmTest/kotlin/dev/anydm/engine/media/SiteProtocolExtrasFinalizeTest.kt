package dev.anydm.engine.media

import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.EmbedChoice
import dev.anydm.engine.core.ErrorClass
import dev.anydm.engine.core.File
import dev.anydm.engine.core.FileId
import dev.anydm.engine.core.Format
import dev.anydm.engine.core.Mirror
import dev.anydm.engine.core.MirrorId
import dev.anydm.engine.core.SubtitleChoice
import dev.anydm.engine.core.SubtitleFormat
import dev.anydm.engine.core.SubtitleMode
import dev.anydm.engine.core.partialPath
import dev.anydm.engine.http.FaultServer
import dev.anydm.engine.http.HttpProtocol
import dev.anydm.engine.http.OkHttpTransport
import dev.anydm.engine.platform.FakeFileSystem
import dev.anydm.engine.platform.ProcessFailure
import dev.anydm.engine.platform.ProcessOutcome
import dev.anydm.engine.platform.ProcessResult
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
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import kotlin.test.AfterTest
import kotlin.test.Test

class SiteProtocolExtrasFinalizeTest {
    private val server = FaultServer()
    private val store = Store(openInMemoryDatabase(), Dispatchers.IO)
    private val fs = FakeFileSystem()
    private val base get() = server.url("/").trimEnd('/')
    private val pageUrl = "https://www.example.com/watch?v=e1"
    private val ffmpeg = Path("/tools/ffmpeg")
    private var hasFfmpeg = true

    /** Plays yt-dlp (the page) and ffmpeg (exit 0, or 1 when [failWhen] says so); records every ffmpeg command. */
    private inner class Tools(
        private val json: String,
        private val failWhen: (List<String>) -> Boolean = { false },
        private val onRun: (List<String>) -> Unit = {},
        private val cannotStartWhen: (List<String>) -> Boolean = { false },
    ) : ProcessRunner {
        val ffmpegCommands = mutableListOf<List<String>>()

        private fun result(spec: ProcessSpec): ProcessOutcome<ProcessResult> {
            if (spec.command.first().endsWith("yt-dlp")) return ProcessOutcome.Ok(ProcessResult(0, json + "\n", "", false))
            ffmpegCommands += spec.command
            onRun(spec.command)
            if (cannotStartWhen(spec.command)) {
                return ProcessOutcome.Failed(ProcessFailure.StartFailed(spec.command.first(), "bad argument"))
            }
            return if (failWhen(spec.command)) {
                ProcessOutcome.Ok(ProcessResult(1, "", "boom: bad input", false))
            } else {
                ProcessOutcome.Ok(ProcessResult(0, "", "", false))
            }
        }

        override suspend fun run(spec: ProcessSpec) = result(spec)

        override suspend fun start(spec: ProcessSpec): ProcessOutcome<RunningProcess> {
            if (!spec.command.first().endsWith("yt-dlp")) return ProcessOutcome.Failed(ProcessFailure.NotFound(spec.command.first()))
            return dev.anydm.engine.platform
                .FakeProcessRunner(
                    listOf(
                        dev.anydm.engine.platform.FakeProcessRunner
                            .Scripted(listOf(spec.command.first()), 0, listOf(json)),
                    ),
                ).start(spec)
        }
    }

    @AfterTest
    fun close() {
        server.close()
        store.close()
    }

    private fun tool(tool: Tool) =
        ResolvedTool(tool, ToolVersion.parse("2026.1.1")!!, tool.executables.associateWith { Path("/tools/$it") }, managed = true)

    private class Rig(
        val site: SiteProtocol,
        val probe: SiteProbe,
        val plan: List<PlannedFile>,
        val request: ProbeRequest,
    ) {
        val download get() = request.download

        fun extrasFolder() = Path("/dl", ".Extras.${download.id.value}.extras")

        fun subtitlePath(index: Int) = Path(extrasFolder(), subtitleFileName(index, probe.extras.subtitles[index]))

        fun coverPath() = Path(extrasFolder(), coverFileName(probe.extras.thumbnail!!))

        fun transfer(fileName: String? = null): Transfer {
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
            return Transfer(download.copy(fileName = fileName), Mirror(MirrorId(1), download.id, probe.url, 0), probe, files)
        }
    }

    private suspend fun rig(
        json: String,
        format: Format,
        tools: Tools,
    ): Rig {
        server.serve("/v/v1", ByteArray(100) { 1 })
        server.serve("/v/140", ByteArray(100) { 2 })
        val site =
            SiteProtocol(HttpProtocol(OkHttpTransport()), tools, {
                if (it == Tool.FFMPEG &&
                    !hasFfmpeg
                ) {
                    null
                } else {
                    tool(it)
                }
            }, store.extractions, fs)
        val download = store.downloads.add(NewDownload(DownloadKind.SITE, mirrors = listOf(pageUrl), saveFolder = "/dl"))
        val request = ProbeRequest(download, store.downloads.mirrors(download.id).single(), null, format)
        val probe = (site.probe(request) as StepResult.Ok).value as SiteProbe
        val plan = ((site.plan(probe, PlanContext(download)) as StepResult.Ok).value as TransferPlan).files
        for (planned in plan) fs.put(partialPath(Path("/dl", planned.path), download.id), ByteArray(10) { 7 })
        val rig = Rig(site, probe, plan, request)
        for (i in probe.extras.subtitles.indices) fs.put(rig.subtitlePath(i), ByteArray(10) { 7 })
        if (probe.extras.thumbnail != null) fs.put(rig.coverPath(), ByteArray(10) { 7 })
        return rig
    }

    private fun mergeCommand(tools: Tools) =
        tools.ffmpegCommands.single {
            it.contains("-map") && it.contains("-f") &&
                !it.contains("-frames:v")
        }

    private fun ok(result: StepResult<FinalizeResult>) = result.shouldBeInstanceOf<StepResult.Ok<FinalizeResult>>().value

    private val embedAll =
        Format(subtitles = SubtitleChoice(listOf("en"), mode = SubtitleMode.EMBED), embed = EmbedChoice(true, true, true))

    @Test
    fun mainOutputUsesTheExtrasBuilder() =
        runBlocking<Unit> {
            var chaptersText: String? = null
            lateinit var chaptersPath: String
            val tools =
                Tools(MediaFixtures.extrasVideoJson(base), onRun = { cmd ->
                    if (cmd.contains("-map_chapters")) {
                        val inputs = cmd.indices.filter { cmd[it] == "-i" }.map { cmd[it + 1] }
                        chaptersPath = inputs[cmd[cmd.indexOf("-map_chapters") + 1].toInt()]
                        chaptersText = fs.read(Path(chaptersPath))?.decodeToString()
                    }
                })
            val rig = rig(MediaFixtures.extrasVideoJson(base), embedAll, tools)
            val transfer = rig.transfer()

            val result = ok(rig.site.finalize(transfer))

            val id = rig.download.id.value
            val files = transfer.files
            val expected =
                Ffmpeg.args(
                    ffmpeg,
                    listOf(files[0].partial, files[1].partial),
                    partialPath(Path("/dl", "Extras.mp4"), rig.download.id),
                    rig.probe.selection,
                    MergeExtras(
                        listOf(EmbeddedSubtitle(rig.subtitlePath(0), "en", "srt")),
                        Path("/dl", "Extras.chapters.$id.tmp"),
                        Path("/dl", "Extras.cover.$id.tmp.jpg"),
                        rig.probe.extras.metadata,
                    ),
                )
            mergeCommand(tools) shouldBe expected
            result.outputs shouldBe listOf("Extras.mp4")
            result.consumed shouldBe files.map { it.file.path }
            chaptersText shouldBe ffmetadataOf(rig.probe.extras.chapters)
            fs.exists(Path("/dl", "Extras.chapters.$id.tmp")) shouldBe false
            fs.exists(Path("/dl", "Extras.cover.$id.tmp.jpg")) shouldBe false
        }

    @Test
    fun theThumbnailIsMadeAJpegFirst() =
        runBlocking<Unit> {
            val tools = Tools(MediaFixtures.extrasVideoJson(base))
            val rig = rig(MediaFixtures.extrasVideoJson(base), Format(embed = EmbedChoice(thumbnail = true)), tools)
            val transfer = rig.transfer()

            ok(rig.site.finalize(transfer))

            val cover = Path("/dl", "Extras.cover.${rig.download.id.value}.tmp.jpg")
            tools.ffmpegCommands.first() shouldBe Ffmpeg.thumbnailArgs(ffmpeg, rig.coverPath(), cover)
        }

    @Test
    fun aFailedThumbnailConversionDropsItAndMergesAnyway() =
        runBlocking<Unit> {
            val tools = Tools(MediaFixtures.extrasVideoJson(base), failWhen = { it.contains("-frames:v") })
            val rig = rig(MediaFixtures.extrasVideoJson(base), Format(embed = EmbedChoice(thumbnail = true, metadata = true)), tools)

            val result = ok(rig.site.finalize(rig.transfer()))

            result.outputs shouldBe listOf("Extras.mp4")
            mergeCommand(tools).contains("-disposition:v:1") shouldBe false
            mergeCommand(tools).contains("-attach") shouldBe false
        }

    @Test
    fun separateSubtitlesGetTheirOwnOutputsAndTheVideoIsNotMerged() =
        runBlocking<Unit> {
            val tools = Tools(MediaFixtures.extrasMuxedJson(base))
            val rig = rig(MediaFixtures.extrasMuxedJson(base), Format(subtitles = SubtitleChoice(listOf("en", "fr"))), tools)
            val transfer = rig.transfer()

            val result = ok(rig.site.finalize(transfer))

            result.outputs shouldBe listOf("Extras.mp4", "Extras.en.srt", "Extras.fr.vtt")
            result.consumed shouldBe emptyList()
            result.cleanup shouldBe listOf(".Extras.${rig.download.id.value}.extras")
            tools.ffmpegCommands.size shouldBe 2
            tools.ffmpegCommands.none { it.contains("-map") } shouldBe true
            tools.ffmpegCommands[0] shouldBe
                Ffmpeg.subtitleArgs(
                    ffmpeg,
                    rig.subtitlePath(0),
                    partialPath(Path("/dl", "Extras.en.srt"), rig.download.id),
                    null,
                    "srt",
                )
        }

    @Test
    fun aConvertedSubtitleUsesConvertToAndAnAutoTrackIsNamedAuto() =
        runBlocking<Unit> {
            server.serve("/a/de.vtt", ByteArray(5))
            val tools = Tools(MediaFixtures.extrasMuxedJson(base))
            val format = Format(subtitles = SubtitleChoice(listOf("en", "de"), includeAuto = true, convertTo = SubtitleFormat.VTT))
            val rig = rig(MediaFixtures.extrasMuxedJson(base), format, tools)

            val result = ok(rig.site.finalize(rig.transfer()))

            result.outputs shouldBe listOf("Extras.mp4", "Extras.en.vtt", "Extras.de-auto.vtt")
            tools.ffmpegCommands[0].windowed(2).contains(listOf("-f", "webvtt")) shouldBe true
        }

    @Test
    fun anEmptyRawSubtitleIsSkipped() =
        runBlocking<Unit> {
            val tools = Tools(MediaFixtures.extrasMuxedJson(base))
            val rig = rig(MediaFixtures.extrasMuxedJson(base), Format(subtitles = SubtitleChoice(listOf("en", "fr"))), tools)
            val transfer = rig.transfer()
            fs.put(rig.subtitlePath(1), ByteArray(0))

            val result = ok(rig.site.finalize(transfer))

            result.outputs shouldBe listOf("Extras.mp4", "Extras.en.srt")
            tools.ffmpegCommands.size shouldBe 1
        }

    @Test
    fun aFailedSubtitleConversionSkipsOnlyThatTrack() =
        runBlocking<Unit> {
            val tools = Tools(MediaFixtures.extrasMuxedJson(base), failWhen = { cmd -> cmd.any { it.contains("sub1.fr.vtt") } })
            val rig = rig(MediaFixtures.extrasMuxedJson(base), Format(subtitles = SubtitleChoice(listOf("en", "fr"))), tools)

            val result = ok(rig.site.finalize(rig.transfer()))

            result.outputs shouldBe listOf("Extras.mp4", "Extras.en.srt")
            fs.exists(partialPath(Path("/dl", "Extras.fr.vtt"), rig.download.id)) shouldBe false
        }

    @Test
    fun embedOnlyWithASinglePlainStreamStillRunsFfmpeg() =
        runBlocking<Unit> {
            val tools = Tools(MediaFixtures.extrasMuxedJson(base))
            val rig = rig(MediaFixtures.extrasMuxedJson(base), Format(embed = EmbedChoice(metadata = true)), tools)

            val result = ok(rig.site.finalize(rig.transfer()))

            result.outputs shouldBe listOf("Extras.mp4")
            result.consumed shouldBe listOf("Extras.fv1.mp4")
            mergeCommand(tools).contains("title=Extras") shouldBe true
        }

    @Test
    fun aFailedMergeKeepsEverythingAndRemovesTheTempFiles() =
        runBlocking<Unit> {
            val tools = Tools(MediaFixtures.extrasVideoJson(base), failWhen = { it.contains("-map") })
            val rig = rig(MediaFixtures.extrasVideoJson(base), embedAll, tools)
            val transfer = rig.transfer()
            val id = rig.download.id

            val failed = rig.site.finalize(transfer).shouldBeInstanceOf<StepResult.Failed>()

            failed.message shouldContain "ffmpeg failed (1)"
            failed.message shouldContain "bad input"
            transfer.files.all { fs.exists(it.partial) } shouldBe true
            fs.exists(Path("/dl", "Extras.chapters.${id.value}.tmp")) shouldBe false
            fs.exists(Path("/dl", "Extras.cover.${id.value}.tmp.jpg")) shouldBe false
            fs.exists(partialPath(Path("/dl", "Extras.mp4"), id)) shouldBe false
        }

    @Test
    fun aSubtitleFfmpegCannotReadCostsNoVideo() =
        runBlocking<Unit> {
            val tools = Tools(MediaFixtures.extrasVideoJson(base), failWhen = { it.contains("mov_text") })
            val rig = rig(MediaFixtures.extrasVideoJson(base), embedAll, tools)

            val result = ok(rig.site.finalize(rig.transfer()))

            result.outputs shouldBe listOf("Extras.mp4")
            val merges = tools.ffmpegCommands.filter { it.contains("-map") }
            merges.size shouldBe 2
            merges[1].contains("mov_text") shouldBe false
            merges[1].contains("-map_chapters") shouldBe false
            merges[1].contains("title=Extras") shouldBe true
        }

    @Test
    fun aProcessThatCannotStartIsNotReportedAsAMissingTool() =
        runBlocking<Unit> {
            val tools = Tools(MediaFixtures.extrasVideoJson(base), cannotStartWhen = { it.contains("-map") })
            val rig = rig(MediaFixtures.extrasVideoJson(base), embedAll, tools)

            val failed = rig.site.finalize(rig.transfer()).shouldBeInstanceOf<StepResult.Failed>()

            failed.errorClass shouldBe null
            failed.message shouldContain "couldn't start ffmpeg"
        }

    @Test
    fun aMissingFfmpegIsToolMissingWhenSubtitlesNeedIt() =
        runBlocking<Unit> {
            val tools = Tools(MediaFixtures.extrasMuxedJson(base))
            val rig = rig(MediaFixtures.extrasMuxedJson(base), Format(subtitles = SubtitleChoice(listOf("en"))), tools)
            hasFfmpeg = false

            val failed = rig.site.finalize(rig.transfer()).shouldBeInstanceOf<StepResult.Failed>()

            failed.errorClass shouldBe ErrorClass.TOOL_MISSING
            failed.message shouldBe "ffmpeg"
        }
}
