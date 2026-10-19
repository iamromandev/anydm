package dev.anydm.engine.media

import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.File
import dev.anydm.engine.core.FileId
import dev.anydm.engine.core.Format
import dev.anydm.engine.core.Mirror
import dev.anydm.engine.core.MirrorId
import dev.anydm.engine.http.Fault
import dev.anydm.engine.http.FaultServer
import dev.anydm.engine.http.HttpProtocol
import dev.anydm.engine.http.OkHttpTransport
import dev.anydm.engine.platform.DesktopFileSystem
import dev.anydm.engine.platform.FakeProcessRunner
import dev.anydm.engine.platform.ProcessOutcome
import dev.anydm.engine.platform.ProcessRunner
import dev.anydm.engine.platform.ProcessSpec
import dev.anydm.engine.platform.RunningProcess
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
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import io.kotest.matchers.collections.shouldContain as shouldHold

class SiteProtocolFragmentsTest {
    private val server = FaultServer()
    private val store = Store(openInMemoryDatabase(), Dispatchers.IO)
    private val root = Files.createTempDirectory("anydm-frags-test-").toRealPath().toFile()
    private val base get() = server.url("/").trimEnd('/')
    private val pageUrl = "https://www.example.com/watch?v=abc"
    private val parts =
        listOf("init", "s0", "s1", "s2")
            .map { name ->
                name to
                    ByteArray(300) { (it + name.length * 17 + name.last().code).toByte() }
            }.toMap()

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
        root.deleteRecursively()
    }

    private fun tool(tool: Tool) =
        ResolvedTool(tool, ToolVersion.parse("2026.1.1")!!, tool.executables.associateWith { Path("/tools/$it") }, managed = true)

    private fun yt(json: String) = FakeProcessRunner(listOf(FakeProcessRunner.Scripted(listOf("/tools/yt-dlp"), 0, listOf(json))))

    private fun site(runner: ProcessRunner) =
        SiteProtocol(HttpProtocol(OkHttpTransport()), runner, {
            tool(it)
        }, store.extractions, DesktopFileSystem())

    private fun playlist(
        prefix: String = "",
        count: Int = 3,
        extra: String = "",
        endList: Boolean = true,
    ) = buildString {
        append("#EXTM3U\n#EXT-X-VERSION:7\n$extra")
        append("#EXT-X-MAP:URI=\"init.mp4\"\n")
        for (i in 0 until count) append("#EXTINF:4,\ns$i.ts\n")
        if (endList) append("#EXT-X-ENDLIST\n")
    }.also { server.serve("$prefix/hls/index.m3u8", it.toByteArray()) }

    private fun serveHls(
        prefix: String = "",
        count: Int = 3,
    ) {
        playlist(prefix, count)
        server.serve("$prefix/hls/init.mp4", parts.getValue("init"))
        for (i in 0 until count) server.serve("$prefix/hls/s$i.ts", parts.getValue("s${i.coerceAtMost(2)}"))
    }

    private val joined get() = parts.getValue("init") + parts.getValue("s0") + parts.getValue("s1") + parts.getValue("s2")

    private suspend fun request(format: Format? = null): ProbeRequest {
        val download = store.downloads.add(NewDownload(DownloadKind.SITE, mirrors = listOf(pageUrl), saveFolder = root.toString()))
        return ProbeRequest(download, store.downloads.mirrors(download.id).single(), null, format)
    }

    private fun siteProbe(result: StepResult<*>) = result.shouldBeInstanceOf<StepResult.Ok<*>>().value as SiteProbe

    private fun failed(result: StepResult<*>) = result.shouldBeInstanceOf<StepResult.Failed>()

    private class Rig(
        val site: SiteProtocol,
        val request: ProbeRequest,
        val probe: SiteProbe,
        val plan: List<PlannedFile>,
        val root: java.io.File,
    ) {
        val download get() = request.download

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

        fun folder(vararg more: String) =
            java.io.File(root, ".Test Video.${download.id.value}.frag").let { f -> more.fold(f) { a, b -> java.io.File(a, b) } }
    }

    private suspend fun rig(
        runner: ProcessRunner,
        format: Format? = null,
    ): Rig {
        val site = site(runner)
        val request = request(format)
        val probe = siteProbe(site.probe(request))
        val plan = ((site.plan(probe, PlanContext(request.download)) as StepResult.Ok).value as TransferPlan).files
        return Rig(site, request, probe, plan, root)
    }

    private fun requestsTo(path: String) = server.requests.count { it.url.encodedPath == path }

    @Test
    fun anHlsPageResolvesItsFragments() =
        runBlocking<Unit> {
            serveHls()

            val probe = siteProbe(site(yt(MediaFixtures.hlsPageJson(base))).probe(request()))

            probe.route shouldBe StreamRoute.FRAGMENTS
            probe.fragments.getValue("hls-720").map { it.url.substringAfter(base) } shouldBe
                listOf("/hls/init.mp4", "/hls/s0.ts", "/hls/s1.ts", "/hls/s2.ts")
            probe.probes shouldBe emptyMap()
            probe.size shouldBe null
            probe.name shouldBe "Test Video.mp4"
        }

    @Test
    fun anEncryptedPlaylistSwitchesTheJobToTheFallback() =
        runBlocking<Unit> {
            serveHls()
            playlist(extra = "#EXT-X-KEY:METHOD=AES-128,URI=\"k.key\"\n")

            val probe = siteProbe(site(yt(MediaFixtures.hlsPageJson(base))).probe(request()))

            probe.route shouldBe StreamRoute.YTDLP
            probe.fragments shouldBe emptyMap()
            probe.probes shouldBe emptyMap()
            requestsTo("/hls/s0.ts") shouldBe 0
        }

    @Test
    fun aLivePlaylistIsRefused() =
        runBlocking<Unit> {
            playlist(endList = false)

            failed(site(yt(MediaFixtures.hlsPageJson(base))).probe(request())).message shouldContain "live"
        }

    @Test
    fun aMasterPlaylistIsRefused() =
        runBlocking<Unit> {
            server.serve("/hls/index.m3u8", "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000\nlow.m3u8\n".toByteArray())

            failed(site(yt(MediaFixtures.hlsPageJson(base))).probe(request())).message shouldContain "other playlists"
        }

    @Test
    fun aPlaylistThatIsNotOneIsRefused() =
        runBlocking<Unit> {
            server.serve("/hls/index.m3u8", "<html>nope</html>".toByteArray())

            failed(site(yt(MediaFixtures.hlsPageJson(base))).probe(request())).message shouldContain "playlist"
        }

    @Test
    fun aDashPageUsesItsFragmentList() =
        runBlocking<Unit> {
            val probe = siteProbe(site(yt(MediaFixtures.dashPageJson(base))).probe(request()))

            probe.route shouldBe StreamRoute.FRAGMENTS
            probe.fragments.getValue("dash-v").size shouldBe 3
            probe.fragments.getValue("dash-a").size shouldBe 2
            server.requests.none { it.url.encodedPath.startsWith("/dash/") } shouldBe true
            probe.name shouldBe "Dash.mp4"
        }

    @Test
    fun planGivesFragmentStreamsNoSizeAndTheFragSuffix() =
        runBlocking<Unit> {
            val rig = rig(yt(MediaFixtures.dashPageJson(base)))

            rig.plan.map { it.path } shouldBe listOf("Dash.fdash-v.mp4", "Dash.fdash-a.m4a")
            rig.plan.map { it.size } shouldBe listOf(null, null)
            rig.plan.all { it.segments.isEmpty() } shouldBe true
        }

    @Test
    fun transferFetchesAndJoinsInOrder() =
        runBlocking<Unit> {
            serveHls()
            val rig = rig(yt(MediaFixtures.hlsPageJson(base)))
            val io = CollectingIo()

            rig.site.transfer(rig.transfer(), io) shouldBe StepResult.Ok(Unit)

            io.bytes(FileId(1)).contentEquals(joined) shouldBe true
            rig
                .folder("hls-720")
                .list()
                .orEmpty()
                .sorted() shouldBe listOf("000000", "000001", "000002", "000003")
            io.limiter.bytes shouldBe joined.size.toLong()
        }

    @Test
    fun aFinishedFragmentIsNotFetchedAgain() =
        runBlocking<Unit> {
            serveHls()
            val rig = rig(yt(MediaFixtures.hlsPageJson(base)))
            val folder = rig.folder("hls-720").also { it.mkdirs() }
            java.io.File(folder, "000000").writeBytes(parts.getValue("init"))
            java.io.File(folder, "000001").writeBytes(parts.getValue("s0"))
            val before = server.requests.size

            rig.site.transfer(rig.transfer(), CollectingIo()) shouldBe StepResult.Ok(Unit)

            server.requests.drop(before).none { it.url.encodedPath == "/hls/init.mp4" || it.url.encodedPath == "/hls/s0.ts" } shouldBe true
        }

    @Test
    fun anExpiredPlaylistUrlIsExtractedAgainAndResumesByIndex() =
        runBlocking<Unit> {
            serveHls()
            val switching = Switching(yt(MediaFixtures.hlsPageJson(base)))
            val rig = rig(switching)
            serveHls("/new")
            val second = yt(MediaFixtures.hlsPageJson("$base/new"))
            switching.current = second
            server.fault("/hls/s1.ts", Fault.Status(403))
            val io = CollectingIo()

            rig.site.transfer(rig.transfer(), io) shouldBe StepResult.Ok(Unit)

            second.started.size shouldBe 1
            io.bytes(FileId(1)).contentEquals(joined) shouldBe true
            requestsTo("/hls/init.mp4") shouldBe 1
        }

    @Test
    fun aChangedFragmentCountFails() =
        runBlocking<Unit> {
            serveHls()
            val switching = Switching(yt(MediaFixtures.hlsPageJson(base)))
            val rig = rig(switching)
            serveHls("/new", count = 2)
            switching.current = yt(MediaFixtures.hlsPageJson("$base/new"))
            server.fault("/hls/s1.ts", Fault.Status(403))

            failed(rig.site.transfer(rig.transfer(), CollectingIo())).message shouldContain "changed"
        }

    @Test
    fun progressNeverGoesDownAcrossAPlainAndAnHlsStream() =
        runBlocking<Unit> {
            serveHls()
            server.serve("/v/137", ByteArray(4000) { 7 })
            val rig = rig(yt(MediaFixtures.mixedRoutesJson(base)))
            val io = CollectingIo()

            rig.probe.route shouldBe StreamRoute.FRAGMENTS
            rig.site.transfer(rig.transfer(), io) shouldBe StepResult.Ok(Unit)

            io.progress shouldBe io.progress.sorted()
            io.progress.last() shouldBe 4000L + joined.size
        }

    @Test
    fun finalizeNamesTheFragmentFolderAsCleanup() =
        runBlocking<Unit> {
            serveHls()
            val ffmpeg = FakeProcessRunner(listOf(FakeProcessRunner.Scripted(listOf("/tools/ffmpeg"), 0)))
            val rig = rig(Combined(yt(MediaFixtures.hlsPageJson(base)), ffmpeg))
            val transfer = rig.transfer()

            val result =
                rig.site
                    .finalize(transfer)
                    .shouldBeInstanceOf<StepResult.Ok<dev.anydm.engine.protocol.FinalizeResult>>()
                    .value

            result.outputs shouldBe listOf("Test Video.mp4")
            result.consumed shouldBe listOf("Test Video.fhls-720.mp4")
            result.cleanup shouldBe listOf(".Test Video.${rig.download.id.value}.frag")
            ffmpeg.started.single().command shouldHold "aac_adtstoasc"
        }

    /** Answers yt-dlp from one runner and everything else from another. */
    private class Combined(
        private val ytDlp: FakeProcessRunner,
        private val other: FakeProcessRunner,
    ) : ProcessRunner {
        private fun pick(spec: ProcessSpec) = if (spec.command.first().endsWith("yt-dlp")) ytDlp else other

        override suspend fun run(spec: ProcessSpec) = pick(spec).run(spec)

        override suspend fun start(spec: ProcessSpec): ProcessOutcome<RunningProcess> = pick(spec).start(spec)
    }
}
