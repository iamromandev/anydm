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
import dev.anydm.engine.http.Fault
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import java.nio.file.Files
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
    private val root = Files.createTempDirectory("anydm-extras-test-").toRealPath().toFile()

    @AfterTest
    fun close() {
        server.close()
        store.close()
        root.deleteRecursively()
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
        val root: java.io.File,
    ) {
        val download get() = request.download

        /** A file in the job's extras folder, or null when it isn't there. */
        fun extra(name: String): ByteArray? =
            java.io
                .File(root, ".Extras.${download.id.value}.extras/$name")
                .takeIf { it.exists() }
                ?.readBytes()

        fun extrasFolder() = java.io.File(root, ".Extras.${download.id.value}.extras")

        fun transfer(): Transfer {
            val files =
                plan.mapIndexed { i, planned ->
                    val target = Path(root.toString(), planned.path)
                    TransferFile(
                        File(FileId(i + 1L), download.id, i, planned.path, planned.size, true),
                        emptyList(),
                        Path(root.toString(), "${planned.path}.${download.id.value}.anydm"),
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
        val download = store.downloads.add(NewDownload(DownloadKind.SITE, mirrors = listOf(pageUrl), saveFolder = root.toString()))
        val request = ProbeRequest(download, store.downloads.mirrors(download.id).single(), context, format)
        val probe = (site.probe(request) as StepResult.Ok).value as SiteProbe
        val plan = ((site.plan(probe, PlanContext(download)) as StepResult.Ok).value as TransferPlan).files
        return Rig(site, request, probe, plan, root)
    }

    private val both = Format(subtitles = SubtitleChoice(listOf("en", "fr")), embed = EmbedChoice(thumbnail = true))

    @Test
    fun theSubtitlesAndTheCoverAreNotPartOfThePlan() =
        runBlocking<Unit> {
            serveAll()

            val rig = rig(MediaFixtures.extrasVideoJson(base), both)

            rig.plan.map { it.path } shouldBe listOf("Extras.fv1.mp4", "Extras.f140.m4a")
            rig.plan.all { it.size != null } shouldBe true
        }

    @Test
    fun thePlanDoesNotDependOnWhatTheSubtitleListHolds() =
        runBlocking<Unit> {
            serveAll()
            server.serve("/a/de.vtt", frVtt)

            val withTracks = rig(MediaFixtures.extrasVideoJson(base), both).plan
            val withAuto =
                rig(
                    MediaFixtures.extrasVideoJson(base),
                    Format(subtitles = SubtitleChoice(listOf("de"), includeAuto = true)),
                ).plan
            val without = rig(MediaFixtures.extrasVideoJson(base), Format(embed = EmbedChoice(metadata = true))).plan

            withAuto shouldBe withTracks
            without shouldBe withTracks
        }

    @Test
    fun separateSubtitlesAloneKeepTheMainFileName() =
        runBlocking<Unit> {
            serveAll()

            val rig = rig(MediaFixtures.extrasMuxedJson(base), Format(subtitles = SubtitleChoice(listOf("en"))))

            rig.plan.map { it.path } shouldBe listOf("Extras.mp4")
        }

    @Test
    fun transferKeepsSubtitlesAndTheThumbnailInTheExtrasFolder() =
        runBlocking<Unit> {
            serveAll()
            val rig = rig(MediaFixtures.extrasVideoJson(base), both)
            val io = CollectingIo()

            rig.site.transfer(rig.transfer(), io) shouldBe StepResult.Ok(Unit)

            io.bytes(FileId(1)).contentEquals(video) shouldBe true
            rig.extra("sub0.en.srt")!!.contentEquals(enSrt) shouldBe true
            rig.extra("sub1.fr.vtt")!!.contentEquals(frVtt) shouldBe true
            rig.extra("cover.jpg")!!.contentEquals(jpg) shouldBe true
            rig
                .extrasFolder()
                .list()
                .orEmpty()
                .toSet() shouldBe setOf("sub0.en.srt", "sub1.fr.vtt", "cover.jpg")
        }

    @Test
    fun anAutoTrackIsNamedByItsLanguage() =
        runBlocking<Unit> {
            serveAll()
            server.serve("/a/de.vtt", frVtt)
            val rig = rig(MediaFixtures.extrasMuxedJson(base), Format(subtitles = SubtitleChoice(listOf("de"), includeAuto = true)))

            rig.site.transfer(rig.transfer(), CollectingIo()) shouldBe StepResult.Ok(Unit)

            rig.extra("sub0.de.vtt")!!.contentEquals(frVtt) shouldBe true
        }

    @Test
    fun aMissingSubtitleIsLeftOutAndTheJobGoes() =
        runBlocking<Unit> {
            serveAll()
            val brokenPage = MediaFixtures.extrasVideoJson(base).replace("$base/s/fr.vtt", "$base/s/missing.vtt")
            val broken = rig(brokenPage, both)
            val io = CollectingIo()

            broken.site.transfer(broken.transfer(), io) shouldBe StepResult.Ok(Unit)

            broken.extra("sub0.en.srt")!!.contentEquals(enSrt) shouldBe true
            broken.extra("sub1.fr.vtt") shouldBe null
            broken.extra("cover.jpg")!!.contentEquals(jpg) shouldBe true
            requestsTo("/s/missing.vtt") shouldBe 1
        }

    @Test
    fun anHtmlOrEmptySubtitleIsLeftOut() =
        runBlocking<Unit> {
            serveAll()
            server.serve("/s/en.srt", "<html>blocked</html>".toByteArray())
            server.serve("/s/fr.vtt", ByteArray(0))
            val rig = rig(MediaFixtures.extrasVideoJson(base), both)

            rig.site.transfer(rig.transfer(), CollectingIo()) shouldBe StepResult.Ok(Unit)

            rig.extra("sub0.en.srt") shouldBe null
            rig.extra("sub1.fr.vtt") shouldBe null
            rig.extra("cover.jpg")!!.contentEquals(jpg) shouldBe true
        }

    @Test
    fun aPassingErrorIsTriedAgain() =
        runBlocking<Unit> {
            serveAll()
            server.fault("/s/en.srt", Fault.Once(Fault.Status(503)))
            server.fault("/s/fr.vtt", Fault.Once(Fault.Status(429)))
            val rig = rig(MediaFixtures.extrasVideoJson(base), both)

            rig.site.transfer(rig.transfer(), CollectingIo()) shouldBe StepResult.Ok(Unit)

            rig.extra("sub0.en.srt")!!.contentEquals(enSrt) shouldBe true
            rig.extra("sub1.fr.vtt")!!.contentEquals(frVtt) shouldBe true
            requestsTo("/s/en.srt") shouldBe 2
        }

    @Test
    fun aRefusalIsNotTriedAgain() =
        runBlocking<Unit> {
            serveAll()
            server.fault("/s/en.srt", Fault.Status(403))
            val rig = rig(MediaFixtures.extrasVideoJson(base), both)

            rig.site.transfer(rig.transfer(), CollectingIo()) shouldBe StepResult.Ok(Unit)

            requestsTo("/s/en.srt") shouldBe 1
            rig.extra("sub0.en.srt") shouldBe null
        }

    @Test
    fun aRerunKeepsWhatItAlreadyHas() =
        runBlocking<Unit> {
            serveAll()
            val rig = rig(MediaFixtures.extrasVideoJson(base), both)
            rig.site.transfer(rig.transfer(), CollectingIo())
            val before = server.requests.size

            rig.site.transfer(rig.transfer(), CollectingIo()) shouldBe StepResult.Ok(Unit)

            server.requests.drop(before).count { it.url.encodedPath.startsWith("/s/") || it.url.encodedPath.startsWith("/t/") } shouldBe 0
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
    fun progressCountsTheStreamsAndNeverGoesDown() =
        runBlocking<Unit> {
            serveAll()
            val rig = rig(MediaFixtures.extrasVideoJson(base), both)
            val io = CollectingIo()

            rig.site.transfer(rig.transfer(), io)

            io.progress shouldBe io.progress.sorted()
            io.progress.last() shouldBe (video.size + audio.size).toLong()
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

    @Test
    fun theExtrasFolderIsAWorkFolderOfTheJob() =
        runBlocking<Unit> {
            serveAll()
            val rig = rig(MediaFixtures.extrasVideoJson(base), both)

            rig.site.workFolders(rig.download.copy(fileName = "Extras.mp4")) shouldBe
                listOf(
                    ".Extras.${rig.download.id.value}.frag",
                    ".Extras.${rig.download.id.value}.ytdlp",
                    ".Extras.${rig.download.id.value}.extras",
                )
        }

    private fun requestsTo(path: String) = server.requests.count { it.url.encodedPath == path }
}
