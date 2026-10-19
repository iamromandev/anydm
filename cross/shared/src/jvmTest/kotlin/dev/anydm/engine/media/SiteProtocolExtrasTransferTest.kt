package dev.anydm.engine.media

import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.EmbedChoice
import dev.anydm.engine.core.File
import dev.anydm.engine.core.FileId
import dev.anydm.engine.core.Format
import dev.anydm.engine.core.MediaSettings
import dev.anydm.engine.core.Mirror
import dev.anydm.engine.core.MirrorId
import dev.anydm.engine.core.Pacing
import dev.anydm.engine.core.PacingPreset
import dev.anydm.engine.core.PacingValues
import dev.anydm.engine.core.RequestContext
import dev.anydm.engine.core.SubtitleChoice
import dev.anydm.engine.http.FaultServer
import dev.anydm.engine.http.HttpProtocol
import dev.anydm.engine.http.OkHttpTransport
import dev.anydm.engine.platform.DesktopFileSystem
import dev.anydm.engine.platform.FakeProcessRunner
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
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.time.measureTime

class SiteProtocolExtrasTransferTest {
    private val server = FaultServer()
    private val store = Store(openInMemoryDatabase(), Dispatchers.IO)
    private val base get() = server.url("/").trimEnd('/')
    private val pageUrl = "https://www.example.com/watch?v=e1"
    private var settings = MediaSettings()
    private val enSrt = "1\n00:00:00,000 --> 00:00:01,000\nHello\n".toByteArray()
    private val frVtt = "WEBVTT\n\n00:00.000 --> 00:01.000\nBonjour\n".toByteArray()
    private val jpg = ByteArray(300) { if (it == 0) 0xFF.toByte() else 5 }
    private val video = ByteArray(2000) { 1 }
    private val audio = ByteArray(500) { 2 }

    @AfterTest
    fun close() {
        server.close()
        store.close()
    }

    private fun tool(tool: Tool) =
        ResolvedTool(tool, ToolVersion.parse("2026.1.1")!!, tool.executables.associateWith { Path("/tools/$it") }, managed = true)

    private fun yt(json: String) = FakeProcessRunner(listOf(FakeProcessRunner.Scripted(listOf("/tools/yt-dlp"), 0, listOf(json))))

    private fun site(json: String) =
        SiteProtocol(
            HttpProtocol(OkHttpTransport()),
            yt(json),
            { tool(it) },
            store.extractions,
            DesktopFileSystem(),
            mediaSettings = { settings },
        )

    private fun serveAll() {
        server.serve("/v/v1", video)
        server.serve("/v/140", audio)
        server.serve("/s/en.srt", enSrt)
        server.serve("/s/fr.vtt", frVtt)
        server.serve("/t/b.jpg", jpg)
    }

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
                        Path("/dl", "${planned.path}.${download.id.value}.anydm"),
                        target,
                    )
                }
            return Transfer(download, Mirror(MirrorId(1), download.id, probe.url, 0), probe, files)
        }
    }

    private suspend fun rig(
        json: String,
        format: Format,
        context: RequestContext? = null,
    ): Rig {
        val site = site(json)
        val download = store.downloads.add(NewDownload(DownloadKind.SITE, mirrors = listOf(pageUrl), saveFolder = "/dl"))
        val request = ProbeRequest(download, store.downloads.mirrors(download.id).single(), context, format)
        val probe = (site.probe(request) as StepResult.Ok).value as SiteProbe
        val plan = ((site.plan(probe, PlanContext(download)) as StepResult.Ok).value as TransferPlan).files
        return Rig(site, request, probe, plan)
    }

    private val both = Format(subtitles = SubtitleChoice(listOf("en", "fr")), embed = EmbedChoice(thumbnail = true))

    @Test
    fun planAddsAFileForEachChosenSubtitleAndTheThumbnail() =
        runBlocking<Unit> {
            serveAll()

            val rig = rig(MediaFixtures.extrasVideoJson(base), both)

            rig.plan.map { it.path } shouldBe
                listOf("Extras.fv1.mp4", "Extras.f140.m4a", "Extras.sub0.en.srt", "Extras.sub1.fr.vtt", "Extras.thumb.jpg")
            rig.plan.drop(2).all { it.size == null && it.segments.isEmpty() } shouldBe true
            rig.plan.map { it.index } shouldBe listOf(0, 1, 2, 3, 4)
        }

    @Test
    fun separateSubtitlesAloneKeepTheMainFileName() =
        runBlocking<Unit> {
            serveAll()

            val rig = rig(MediaFixtures.extrasMuxedJson(base), Format(subtitles = SubtitleChoice(listOf("en"))))

            rig.plan.map { it.path } shouldBe listOf("Extras.mp4", "Extras.sub0.en.srt")
        }

    @Test
    fun anAutoTrackIsNamedAuto() =
        runBlocking<Unit> {
            serveAll()
            server.serve("/a/de.vtt", frVtt)

            val rig = rig(MediaFixtures.extrasMuxedJson(base), Format(subtitles = SubtitleChoice(listOf("de"), includeAuto = true)))

            rig.plan.map { it.path } shouldBe listOf("Extras.mp4", "Extras.sub0.de-auto.vtt")
        }

    @Test
    fun transferFetchesSubtitlesAndTheThumbnail() =
        runBlocking<Unit> {
            serveAll()
            val rig = rig(MediaFixtures.extrasVideoJson(base), both)
            val io = CollectingIo()

            rig.site.transfer(rig.transfer(), io) shouldBe StepResult.Ok(Unit)

            io.bytes(FileId(1)).contentEquals(video) shouldBe true
            io.bytes(FileId(3)).contentEquals(enSrt) shouldBe true
            io.bytes(FileId(4)).contentEquals(frVtt) shouldBe true
            io.bytes(FileId(5)).contentEquals(jpg) shouldBe true
        }

    @Test
    fun aMissingSubtitleLeavesAnEmptyFileAndTheJobGoes() =
        runBlocking<Unit> {
            serveAll()
            val rig = rig(MediaFixtures.extrasVideoJson(base), both)
            server.clear("/s/fr.vtt")
            server.serve("/gone", ByteArray(1))
            val brokenPage = MediaFixtures.extrasVideoJson(base).replace("$base/s/fr.vtt", "$base/s/missing.vtt")
            val broken = rig(brokenPage, both)
            val io = CollectingIo()

            broken.site.transfer(broken.transfer(), io) shouldBe StepResult.Ok(Unit)

            io.bytes(FileId(3)).contentEquals(enSrt) shouldBe true
            io.bytes(FileId(4)).size shouldBe 0
            io.bytes(FileId(5)).contentEquals(jpg) shouldBe true
            rig.plan.size shouldBe 5
        }

    @Test
    fun anHtmlOrEmptySubtitleLeavesAnEmptyFile() =
        runBlocking<Unit> {
            serveAll()
            server.serve("/s/en.srt", "<html>blocked</html>".toByteArray())
            server.serve("/s/fr.vtt", ByteArray(0))
            val rig = rig(MediaFixtures.extrasVideoJson(base), both)
            val io = CollectingIo()

            rig.site.transfer(rig.transfer(), io) shouldBe StepResult.Ok(Unit)

            io.bytes(FileId(3)).size shouldBe 0
            io.bytes(FileId(4)).size shouldBe 0
            io.bytes(FileId(5)).contentEquals(jpg) shouldBe true
        }

    @Test
    fun extrasAreFetchedAfterTheStreams() =
        runBlocking<Unit> {
            serveAll()
            val rig = rig(MediaFixtures.extrasVideoJson(base), both)
            val before = server.requests.size

            rig.site.transfer(rig.transfer(), CollectingIo())

            val order = server.requests.drop(before).map { it.url.encodedPath }
            order.indexOf("/s/en.srt") shouldBe order.indexOfLast { it == "/v/v1" || it == "/v/140" } + 1
            order.takeLast(3) shouldBe listOf("/s/en.srt", "/s/fr.vtt", "/t/b.jpg")
        }

    @Test
    fun progressNeverGoesDown() =
        runBlocking<Unit> {
            serveAll()
            val rig = rig(MediaFixtures.extrasVideoJson(base), both)
            val io = CollectingIo()

            rig.site.transfer(rig.transfer(), io)

            io.progress shouldBe io.progress.sorted()
            io.progress.last() shouldBe (video.size + audio.size + enSrt.size + frVtt.size + jpg.size).toLong()
        }

    @Test
    fun credentialsStayAwayFromAnotherSubtitleHost() =
        runBlocking<Unit> {
            FaultServer().use { other ->
                serveAll()
                other.serve("/s/en.srt", enSrt)
                other.serve("/s/fr.vtt", frVtt)
                val page = MediaFixtures.extrasVideoJson(base).replace("$base/s/", other.url("/s/").trimEnd('/') + "/")
                val context = RequestContext(listOf("Authorization" to "Bearer secret"), cookies = "session=1")
                val rig = rig(page, Format(subtitles = SubtitleChoice(listOf("en", "fr"))), context)

                rig.site.transfer(rig.transfer(), CollectingIo()) shouldBe StepResult.Ok(Unit)

                other.requests.size shouldBe 2
                other.requests.none { it.headers["Authorization"] != null || it.headers["Cookie"] != null } shouldBe true
            }
        }

    @Test
    fun pacingSpacesTheExtraFetches() =
        runBlocking<Unit> {
            serveAll()
            settings = MediaSettings(pacing = Pacing(PacingPreset.CUSTOM, PacingValues(0.3, 0.0, 0.0)))
            val rig = rig(MediaFixtures.extrasVideoJson(base), both)

            val took = measureTime { rig.site.transfer(rig.transfer(), CollectingIo()) }

            // three extra fetches, two gaps of 0.3 s each jittered down by up to a quarter
            (took.inWholeMilliseconds >= 400) shouldBe true
        }
}
