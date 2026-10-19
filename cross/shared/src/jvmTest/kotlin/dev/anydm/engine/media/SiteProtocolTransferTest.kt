package dev.anydm.engine.media

import dev.anydm.engine.core.Download
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.ErrorClass
import dev.anydm.engine.core.File
import dev.anydm.engine.core.FileId
import dev.anydm.engine.core.Format
import dev.anydm.engine.core.RecordingLimiter
import dev.anydm.engine.core.RecordingMeter
import dev.anydm.engine.core.Segment
import dev.anydm.engine.core.SegmentId
import dev.anydm.engine.http.Fault
import dev.anydm.engine.http.FaultServer
import dev.anydm.engine.http.HttpProtocol
import dev.anydm.engine.http.OkHttpTransport
import dev.anydm.engine.platform.DesktopFileSystem
import dev.anydm.engine.platform.FakeProcessRunner
import dev.anydm.engine.platform.ProcessOutcome
import dev.anydm.engine.platform.ProcessRunner
import dev.anydm.engine.platform.ProcessSpec
import dev.anydm.engine.protocol.PlanContext
import dev.anydm.engine.protocol.ProbeRequest
import dev.anydm.engine.protocol.StepResult
import dev.anydm.engine.protocol.Transfer
import dev.anydm.engine.protocol.TransferFile
import dev.anydm.engine.protocol.TransferIo
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

class SiteProtocolTransferTest {
    private val server = FaultServer()
    private val store = Store(openInMemoryDatabase(), Dispatchers.IO)
    private val base get() = server.url("/").trimEnd('/')
    private val pageUrl = "https://www.example.com/watch?v=abc"
    private val video = ByteArray(4000) { (it % 251).toByte() }
    private val audio = ByteArray(1000) { (it % 241).toByte() }
    private val mp4 = Format(audioCodec = "mp4a")

    /** A runner whose answer can be changed between calls. */
    private class Switching(
        var current: FakeProcessRunner,
    ) : ProcessRunner {
        override suspend fun run(spec: ProcessSpec) = current.run(spec)

        override suspend fun start(spec: ProcessSpec): ProcessOutcome<dev.anydm.engine.platform.RunningProcess> = current.start(spec)
    }

    private class MultiIo(
        sizes: Map<FileId, Int>,
    ) : TransferIo {
        val data = sizes.mapValues { ByteArray(it.value) }
        val progress = mutableListOf<Long>()
        override val limiter = RecordingLimiter()
        override val meter = RecordingMeter()

        override suspend fun write(
            file: FileId,
            offset: Long,
            bytes: ByteArray,
            startIndex: Int,
            endIndex: Int,
        ): StepResult<Unit> {
            bytes.copyInto(data.getValue(file), offset.toInt(), startIndex, endIndex)
            return StepResult.Ok(Unit)
        }

        override suspend fun checkpoint(watermarks: Map<SegmentId, Long>) = Unit

        override fun progress(downloaded: Long) {
            progress += downloaded
        }
    }

    @AfterTest
    fun close() {
        server.close()
        store.close()
    }

    private fun yt(json: String) = FakeProcessRunner(listOf(FakeProcessRunner.Scripted(listOf("/tools/yt-dlp"), 0, listOf(json))))

    private fun tool(tool: Tool) =
        ResolvedTool(tool, ToolVersion.parse("2026.1.1")!!, tool.executables.associateWith { Path("/tools/$it") }, managed = true)

    private fun site(runner: ProcessRunner) =
        SiteProtocol(HttpProtocol(OkHttpTransport()), runner, { tool(it) }, store.extractions, DesktopFileSystem())

    private fun serve(prefix: String = "") {
        server.serve("$prefix/v/137", video)
        server.serve("$prefix/v/140", audio)
    }

    private class Rig(
        val site: SiteProtocol,
        val download: Download,
        val probe: SiteProbe,
        val plan: List<dev.anydm.engine.protocol.PlannedFile>,
    ) {
        fun transfer(
            segments: (Int, dev.anydm.engine.protocol.PlannedFile) -> List<Segment> = { i, planned ->
                planned.segments.mapIndexed { j, s -> Segment(SegmentId(100L * i + j + 1), FileId(i + 1L), s.start, s.end) }
            },
        ): Transfer {
            val files =
                plan.mapIndexed { i, planned ->
                    val id = FileId(i + 1L)
                    TransferFile(
                        File(id, download.id, i, planned.path, planned.size, true),
                        segments(i, planned),
                        Path("/dl/${planned.path}.anydm"),
                        Path("/dl/${planned.path}"),
                    )
                }
            return Transfer(
                download,
                dev.anydm.engine.core
                    .Mirror(
                        dev.anydm.engine.core
                            .MirrorId(1),
                        download.id,
                        probe.url,
                        0,
                    ),
                probe,
                files,
            )
        }

        fun io() = MultiIo(plan.mapIndexed { i, p -> FileId(i + 1L) to p.size!!.toInt() }.toMap())
    }

    private suspend fun rig(runner: ProcessRunner): Rig {
        val site = site(runner)
        val download = store.downloads.add(NewDownload(DownloadKind.SITE, mirrors = listOf(pageUrl), saveFolder = "/dl"))
        val mirror = store.downloads.mirrors(download.id).single()
        val probe = (site.probe(ProbeRequest(download, mirror, null, mp4)) as StepResult.Ok).value as SiteProbe
        val plan = ((site.plan(probe, PlanContext(download)) as StepResult.Ok).value as TransferPlan).files
        return Rig(site, download, probe, plan)
    }

    private fun failed(result: StepResult<*>) = result.shouldBeInstanceOf<StepResult.Failed>()

    @Test
    fun everyStreamLandsInItsOwnPartial() =
        runBlocking<Unit> {
            serve()
            val rig = rig(yt(MediaFixtures.splitVideoJson(base)))
            val io = rig.io()

            rig.site.transfer(rig.transfer(), io) shouldBe StepResult.Ok(Unit)

            io.data.getValue(FileId(1)).contentEquals(video) shouldBe true
            io.data.getValue(FileId(2)).contentEquals(audio) shouldBe true
        }

    @Test
    fun progressCountsTheOtherStreamsToo() =
        runBlocking<Unit> {
            serve()
            val rig = rig(yt(MediaFixtures.splitVideoJson(base)))
            val io = rig.io()

            rig.site.transfer(rig.transfer(), io)

            io.progress.last() shouldBe 5000L
            io.progress shouldBe io.progress.sorted()
        }

    @Test
    fun anExpiredUrlIsExtractedAgainAndTheDownloadResumes() =
        runBlocking<Unit> {
            serve()
            val switching = Switching(yt(MediaFixtures.splitVideoJson(base)))
            val rig = rig(switching)
            serve("/new")
            val second = yt(MediaFixtures.splitVideoJson("$base/new"))
            switching.current = second
            server.fault("/v/137", Fault.Status(403))
            val io = rig.io()

            rig.site.transfer(rig.transfer(), io) shouldBe StepResult.Ok(Unit)

            second.started.size shouldBe 1
            store.extractions.get(rig.download.id)!!.withoutCookies shouldBe false
            io.data.getValue(FileId(1)).contentEquals(video) shouldBe true
            io.data.getValue(FileId(2)).contentEquals(audio) shouldBe true
        }

    @Test
    fun aSecondFailureIsReturned() =
        runBlocking<Unit> {
            serve()
            val switching = Switching(yt(MediaFixtures.splitVideoJson(base)))
            val rig = rig(switching)
            serve("/new")
            val second = yt(MediaFixtures.splitVideoJson("$base/new"))
            switching.current = second
            server.fault("/v/137", Fault.Status(403))
            server.fault("/new/v/137", Fault.Status(403))

            val result = failed(rig.site.transfer(rig.transfer(), rig.io()))

            result.errorClass shouldBe ErrorClass.AUTH
            second.started.size shouldBe 1
        }

    @Test
    fun aChangedSizeFails() =
        runBlocking<Unit> {
            serve()
            val switching = Switching(yt(MediaFixtures.splitVideoJson(base)))
            val rig = rig(switching)
            server.serve("/new/v/137", ByteArray(3999))
            server.serve("/new/v/140", audio)
            switching.current = yt(MediaFixtures.splitVideoJson("$base/new"))
            server.fault("/v/137", Fault.Status(403))

            failed(rig.site.transfer(rig.transfer(), rig.io())).message shouldContain "changed"
        }

    @Test
    fun aMissingFormatIdFails() =
        runBlocking<Unit> {
            serve()
            val switching = Switching(yt(MediaFixtures.splitVideoJson(base)))
            val rig = rig(switching)
            switching.current = yt(MediaFixtures.mixedOnlyJson(base))
            server.fault("/v/137", Fault.Status(403))

            failed(rig.site.transfer(rig.transfer(), rig.io())).message shouldContain "changed"
        }

    @Test
    fun aFinishedStreamIsNotFetchedAgain() =
        runBlocking<Unit> {
            serve()
            val rig = rig(yt(MediaFixtures.splitVideoJson(base)))
            val before = server.requests.size

            rig.site.transfer(
                rig.transfer { i, planned ->
                    planned.segments.mapIndexed { j, s ->
                        Segment(SegmentId(100L * i + j + 1), FileId(i + 1L), s.start, s.end, watermark = if (i == 0) s.end else s.start)
                    }
                },
                rig.io(),
            ) shouldBe StepResult.Ok(Unit)

            server.requests.drop(before).none { it.url.encodedPath == "/v/137" } shouldBe true
            server.requests.drop(before).any { it.url.encodedPath == "/v/140" } shouldBe true
        }

    @Test
    fun thePagesCredentialsStayAwayFromAnotherHost() =
        runBlocking<Unit> {
            serve()
            val own =
                MediaFixtures
                    .splitVideoJson(
                        base,
                    ).replace("\"User-Agent\":\"UA-137\"", "\"User-Agent\":\"UA-137\",\"Cookie\":\"cdn=1\"")
            val site = site(yt(own))
            val download = store.downloads.add(NewDownload(DownloadKind.SITE, mirrors = listOf(pageUrl), saveFolder = "/dl"))
            val mirror = store.downloads.mirrors(download.id).single()
            val context =
                dev.anydm.engine.core
                    .RequestContext(listOf("Authorization" to "Bearer secret"), cookies = "session=1")
            val probe = (site.probe(ProbeRequest(download, mirror, context, mp4)) as StepResult.Ok).value as SiteProbe
            val plan = ((site.plan(probe, PlanContext(download)) as StepResult.Ok).value as TransferPlan).files
            val rig = Rig(site, download, probe, plan)

            site.transfer(rig.transfer(), rig.io()) shouldBe StepResult.Ok(Unit)

            server.requests.none { it.headers["Authorization"] != null } shouldBe true
            server.requests.none { it.headers["Cookie"]?.contains("session=1") == true } shouldBe true
            server.requests.filter { it.url.encodedPath == "/v/137" }.all { it.headers["Cookie"] == "cdn=1" } shouldBe true
        }
}
