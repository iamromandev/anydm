package dev.anydm.engine.media

import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.ErrorClass
import dev.anydm.engine.core.File
import dev.anydm.engine.core.FileId
import dev.anydm.engine.core.Format
import dev.anydm.engine.core.Mirror
import dev.anydm.engine.core.MirrorId
import dev.anydm.engine.core.partialPath
import dev.anydm.engine.files.NameFields
import dev.anydm.engine.http.HttpProbe
import dev.anydm.engine.http.HttpProtocol
import dev.anydm.engine.http.OkHttpTransport
import dev.anydm.engine.platform.FakeFileSystem
import dev.anydm.engine.platform.FakeProcessRunner
import dev.anydm.engine.protocol.FinalizeResult
import dev.anydm.engine.protocol.StepResult
import dev.anydm.engine.protocol.Transfer
import dev.anydm.engine.protocol.TransferFile
import dev.anydm.engine.store.Extraction
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
import kotlin.time.Instant

class SiteProtocolFinalizeTest {
    private val store = Store(openInMemoryDatabase(), Dispatchers.IO)
    private val fs = FakeFileSystem()
    private var hasFfmpeg = true
    private val pageUrl = "https://www.example.com/watch?v=abc"

    @AfterTest
    fun close() = store.close()

    private fun tool(tool: Tool) =
        ResolvedTool(tool, ToolVersion.parse("2026.1.1")!!, tool.executables.associateWith { Path("/tools/$it") }, managed = true)

    private fun site(runner: FakeProcessRunner) =
        SiteProtocol(
            HttpProtocol(OkHttpTransport()),
            runner,
            { if (it == Tool.FFMPEG && !hasFfmpeg) null else tool(it) },
            store.extractions,
            fs,
        )

    private fun ffmpeg(
        exit: Int = 0,
        stderr: List<String> = emptyList(),
    ) = FakeProcessRunner(listOf(FakeProcessRunner.Scripted(listOf("/tools/ffmpeg"), exit, emptyList(), stderr)))

    private data class Case(
        val transfer: Transfer,
        val outputPartial: Path,
        val inputs: List<Path>,
        val selection: Selection,
    )

    private suspend fun case(
        json: String,
        format: Format,
    ): Case {
        val info = (YtDlpJson.parse(json) as MediaParse.Ok).info
        val selection = (FormatSelector.choose(info, format) as Choice.Ok).selection
        val download = store.downloads.add(NewDownload(DownloadKind.SITE, mirrors = listOf(pageUrl), saveFolder = "/dl"))
        val probes =
            selection.streams.associate { it.formatId to HttpProbe(it.formatId, 100, null, true, it.url, null) }
        val probe =
            SiteProbe(info, selection, format, pageUrl, null, probes, "Test Video." + selection.outputExtension, 200, null, NameFields())
        val stem = "Test Video"
        val files =
            selection.streams.mapIndexed { i, stream ->
                val path = if (selection.needsFfmpeg) "$stem.f${stream.formatId}.${stream.ext}" else probe.name
                val target = Path("/dl", path)
                val partial = partialPath(target, download.id)
                fs.put(partial, byteArrayOf(1, 2, 3))
                TransferFile(File(FileId(i + 1L), download.id, i, path, 100, true), emptyList(), partial, target)
            }
        val transfer = Transfer(download, Mirror(MirrorId(1), download.id, pageUrl, 0), probe, files)
        return Case(transfer, partialPath(Path("/dl", probe.name), download.id), files.map { it.partial }, selection)
    }

    private val mp4 = Format(audioCodec = "mp4a")

    @Test
    fun noFfmpegWhenOneStreamNeedsNone() =
        runBlocking<Unit> {
            val runner = ffmpeg()
            val c = case(MediaFixtures.mixedOnlyJson(), Format())

            val result = site(runner).finalize(c.transfer)

            result shouldBe StepResult.Ok(FinalizeResult(listOf("Test Video.mp4")))
            runner.started shouldBe emptyList()
        }

    @Test
    fun mergeRunsFfmpegOnThePartialsAndNamesTheOutput() =
        runBlocking<Unit> {
            val runner = ffmpeg()
            val c = case(MediaFixtures.splitVideoJson(), mp4)

            val result = site(runner).finalize(c.transfer)

            runner.started.single().command shouldBe Ffmpeg.args(Path("/tools/ffmpeg"), c.inputs, c.outputPartial, c.selection)
            result shouldBe
                StepResult.Ok(FinalizeResult(listOf("Test Video.mp4"), consumed = listOf("Test Video.f137.mp4", "Test Video.f140.m4a")))
        }

    @Test
    fun aFailedMergeKeepsTheStreamsAndTheMessage() =
        runBlocking<Unit> {
            val c = case(MediaFixtures.splitVideoJson(), mp4)
            fs.put(c.outputPartial, byteArrayOf(9))

            val result = site(ffmpeg(1, listOf("noise", "Invalid data found when processing input"))).finalize(c.transfer)

            val failed = result.shouldBeInstanceOf<StepResult.Failed>()
            failed.message shouldContain "ffmpeg failed (1)"
            failed.message shouldContain "Invalid data found"
            c.inputs.all { fs.exists(it) } shouldBe true
            fs.exists(c.outputPartial) shouldBe false
        }

    @Test
    fun aMissingFfmpegIsToolMissingAtFinalize() =
        runBlocking<Unit> {
            val c = case(MediaFixtures.splitVideoJson(), mp4)
            hasFfmpeg = false

            val failed = site(ffmpeg()).finalize(c.transfer).shouldBeInstanceOf<StepResult.Failed>()

            failed.errorClass shouldBe ErrorClass.TOOL_MISSING
            failed.message shouldBe "ffmpeg"
        }

    @Test
    fun aBinaryThatVanishedIsToolMissing() =
        runBlocking<Unit> {
            val c = case(MediaFixtures.splitVideoJson(), mp4)

            val failed = site(FakeProcessRunner(emptyList())).finalize(c.transfer).shouldBeInstanceOf<StepResult.Failed>()

            failed.errorClass shouldBe ErrorClass.TOOL_MISSING
            failed.message shouldBe "ffmpeg"
        }

    @Test
    fun aSuccessfulMergeDropsTheExtractionRow() =
        runBlocking<Unit> {
            val c = case(MediaFixtures.splitVideoJson(), mp4)
            store.extractions.put(Extraction(c.transfer.download.id, pageUrl, "{}", Instant.fromEpochMilliseconds(1)))

            site(ffmpeg()).finalize(c.transfer).shouldBeInstanceOf<StepResult.Ok<*>>()

            store.extractions.get(c.transfer.download.id) shouldBe null
        }

    @Test
    fun aUserNameIsTheOutputName() =
        runBlocking<Unit> {
            val c = case(MediaFixtures.splitVideoJson(), mp4)
            val named = Transfer(c.transfer.download.copy(fileName = "mine.mp4"), c.transfer.mirror, c.transfer.probe, c.transfer.files)

            val result = site(ffmpeg()).finalize(named).shouldBeInstanceOf<StepResult.Ok<FinalizeResult>>()

            result.value.outputs shouldBe listOf("mine.mp4")
        }
}
