package dev.anydm.engine.media

import dev.anydm.engine.AddPlaylistResult
import dev.anydm.engine.Engine
import dev.anydm.engine.SyncOutcome
import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.DownloadStatus
import dev.anydm.engine.core.Format
import dev.anydm.engine.core.SponsorBlockChoice
import dev.anydm.engine.core.SponsorBlockMode
import dev.anydm.engine.files.NewProfile
import dev.anydm.engine.files.ProfileResult
import dev.anydm.engine.http.FaultServer
import dev.anydm.engine.http.HttpProtocol
import dev.anydm.engine.http.OkHttpTransport
import dev.anydm.engine.platform.DesktopFileSystem
import dev.anydm.engine.platform.DesktopProcessRunner
import dev.anydm.engine.queue.CommandResult
import dev.anydm.engine.queue.RemoveFiles
import dev.anydm.engine.store.NewDownload
import dev.anydm.engine.store.Store
import dev.anydm.engine.store.openInMemoryDatabase
import dev.anydm.engine.tools.ResolvedTool
import dev.anydm.engine.tools.Tool
import dev.anydm.engine.tools.ToolVersion
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path
import org.junit.Assume.assumeFalse
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A playlist as a collection through the real engine, queue, runner, HTTP engine, file system and process runner
 * (MED-16, MED-17, SET-02). Only the programs are stand-ins: a yt-dlp that prints a recorded flat listing for
 * `--flat-playlist`, a recorded page for `-J`, and for a download writes `out.mp4`; and an ffmpeg nobody calls.
 */
class SiteCollectionsEndToEndTest {
    private val root = Files.createTempDirectory("anydm-collections-e2e-").toRealPath().toFile()
    private val dl = File(root, "dl").also { it.mkdirs() }
    private val bin = File(root, "bin").also { it.mkdirs() }
    private val server = FaultServer()

    // One thread for the database, as the desktop store has it: several jobs write at once.
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val store = Store(openInMemoryDatabase(), Dispatchers.IO.limitedParallelism(1))
    private val ytLog = File(bin, "yt-dlp.log")
    private val playlistUrl = "https://www.example.com/playlist?list=PL1"
    private val base get() = server.url("/").trimEnd('/')

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
            flat=0; url=""; out=""; prev=""
            for a in "${'$'}@"; do
              if [ "${'$'}a" = "--flat-playlist" ]; then flat=1; fi
              if [ "${'$'}prev" = "-o" ]; then out="${'$'}a"; fi
              prev="${'$'}a"; url="${'$'}a"
            done
            if [ ${'$'}flat = 1 ]; then cat '${File(bin, "list.json")}'; exit 0; fi
            for a in "${'$'}@"; do
              if [ "${'$'}a" = "-J" ]; then id=${'$'}{url##*v=}; cat "$bin/page-${'$'}id.json"; exit 0; fi
            done
            dir=${'$'}(dirname "${'$'}out")
            mkdir -p "${'$'}dir"
            echo '{"status":"downloading","filename":"out.mp4","downloaded_bytes":100,"total_bytes":300}'
            printf 'made-by-yt-dlp' > "${'$'}dir/out.mp4"
            echo '{"status":"finished","filename":"out.mp4","downloaded_bytes":300,"total_bytes":300}'
            """.trimIndent() + "\n",
        )
    private val ffmpegScript = script("ffmpeg", "exit 0\n")

    private fun tool(
        tool: Tool,
        file: File,
    ) = ResolvedTool(tool, ToolVersion.parse("2026.1.1")!!, mapOf(tool.id to Path(file.toString())), managed = true)

    private val resolve: suspend (Tool) -> ResolvedTool? = {
        when (it) {
            Tool.YT_DLP -> tool(it, ytDlpScript)
            Tool.FFMPEG -> tool(it, ffmpegScript)
            else -> null
        }
    }

    private val engine =
        Engine(
            store = store,
            fileSystem = DesktopFileSystem(),
            protocols =
                mapOf(
                    DownloadKind.SITE to
                        SiteProtocol(
                            HttpProtocol(OkHttpTransport()),
                            DesktopProcessRunner(),
                            resolve,
                            store.extractions,
                            DesktopFileSystem(),
                        ),
                ),
            defaultFolder = Path(dl.toString()),
            dispatcher = Dispatchers.Default,
            checkpointEvery = 50.milliseconds,
            playlists = YtDlpPlaylistSource(DesktopProcessRunner(), resolve),
        )

    @BeforeTest
    fun setUp() {
        assumeFalse(System.getProperty("os.name").startsWith("Windows"))
    }

    @AfterTest
    fun close() {
        runBlocking { engine.stop() }
        server.close()
        root.deleteRecursively()
    }

    private fun video(n: Int) {
        server.serve("/v/$n", ByteArray(200) { n.toByte() })
        File(bin, "page-v$n.json").writeText(
            """{"id":"v$n","title":"Video $n","formats":[{"format_id":"22","url":"$base/v/$n","ext":"mp4","protocol":"https",""" +
                """"vcodec":"avc1","acodec":"mp4a","height":720,"filesize":200}]}""",
        )
    }

    private fun listing(count: Int) {
        (1..count).forEach(::video)
        File(bin, "list.json").writeText(
            """{"_type":"playlist","id":"PL1","title":"My List","uploader":"Chan","extractor_key":"YoutubeTab",""" +
                """"webpage_url":"$playlistUrl","entries":[""" +
                (1..count).joinToString(",") {
                    """{"ie_key":"Youtube","id":"v$it","url":"https://www.example.com/watch?v=v$it","title":"Video $it","duration":${60 + it}}"""
                } + "]}",
        )
    }

    // The finish hook runs just after a job is reported COMPLETED, so the playlist file is waited for too.
    private suspend fun awaitDone(
        collection: DownloadId,
        items: Int,
    ) {
        try {
            withTimeout(30.seconds) {
                engine.downloads.first { snapshots ->
                    val snapshot = snapshots.firstOrNull { it.download.id == collection }
                    snapshot?.download?.status == DownloadStatus.COMPLETED && snapshot.items?.completed == items
                }
                while (File(folder, "My List.m3u8")
                        .takeIf { it.exists() }
                        ?.readLines()
                        .orEmpty()
                        .count { !it.startsWith("#") } <
                    items
                ) {
                    delay(50)
                }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            val state =
                engine.downloads.value.joinToString("\n") { snap ->
                    "${snap.download.id.value} ${snap.download.status} items=${snap.items} failure=${snap.lastFailure?.message} " +
                        "parent=${snap.download.parentId?.value}"
                }
            throw AssertionError(
                "timed out\n$state\nfolder=${folder.list()?.toList()}\nm3u8=${File(
                    folder,
                    "My List.m3u8",
                ).takeIf { it.exists() }?.readText()}\n" +
                    "yt-dlp log:\n${if (ytLog.exists()) ytLog.readText() else "(none)"}",
            )
        }
    }

    private val folder get() = File(dl, "My List")

    private fun playlistFile() = File(folder, "My List.m3u8").readLines()

    @Test
    fun aThreeVideoPlaylistCompletesWithAPlaylistFile() =
        runBlocking<Unit> {
            listing(3)
            engine.start()

            val added = engine.addPlaylist(playlistUrl).shouldBeInstanceOf<AddPlaylistResult.Added>()
            awaitDone(added.collection.id, 3)

            folder.list().orEmpty().toSet() shouldBe setOf("Video 1.mp4", "Video 2.mp4", "Video 3.mp4", "My List.m3u8")
            File(folder, "Video 2.mp4").readBytes().all { it == 2.toByte() } shouldBe true
            playlistFile() shouldBe
                listOf(
                    "#EXTM3U",
                    "#EXTINF:61,Video 1",
                    "Video 1.mp4",
                    "#EXTINF:62,Video 2",
                    "Video 2.mp4",
                    "#EXTINF:63,Video 3",
                    "Video 3.mp4",
                )
            store.downloads.children(added.collection.id).map { it.playlistIndex } shouldBe listOf(1, 2, 3)
        }

    @Test
    fun aSecondSyncDownloadsOnlyTheNewVideo() =
        runBlocking<Unit> {
            listing(3)
            engine.start()
            val added = engine.addPlaylist(playlistUrl).shouldBeInstanceOf<AddPlaylistResult.Added>()
            awaitDone(added.collection.id, 3)
            val before = server.requests.size
            listing(4)

            val synced = engine.syncCollection(added.collection.id).shouldBeInstanceOf<SyncOutcome.Done>()
            awaitDone(added.collection.id, 4)

            synced.result.added shouldBe 1
            server.requests
                .drop(before)
                .map { it.url.encodedPath }
                .filter { it.startsWith("/v/") }
                .toSet() shouldBe setOf("/v/4")
            playlistFile().filter { !it.startsWith("#") } shouldBe listOf("Video 1.mp4", "Video 2.mp4", "Video 3.mp4", "Video 4.mp4")
        }

    @Test
    fun removingACollectionAndKeepingItsFilesLeavesThem() =
        runBlocking<Unit> {
            listing(2)
            engine.start()
            val added = engine.addPlaylist(playlistUrl).shouldBeInstanceOf<AddPlaylistResult.Added>()
            awaitDone(added.collection.id, 2)

            engine.remove(added.collection.id, RemoveFiles.KEEP).shouldBeInstanceOf<CommandResult.Done>()

            store.downloads.list() shouldBe emptyList()
            folder.list().orEmpty().toSet() shouldBe setOf("Video 1.mp4", "Video 2.mp4", "My List.m3u8")
        }

    @Test
    fun aProfileFormatReachesEveryChild() =
        runBlocking<Unit> {
            listing(3)
            engine.start()
            val profile =
                engine
                    .addProfile(NewProfile("Lectures", Format(sponsorBlock = SponsorBlockChoice(SponsorBlockMode.CUT))))
                    .shouldBeInstanceOf<ProfileResult.Saved>()
                    .profile

            val added =
                engine
                    .addPlaylist(playlistUrl, template = NewDownload(DownloadKind.SITE, profileId = profile.id))
                    .shouldBeInstanceOf<AddPlaylistResult.Added>()
            awaitDone(added.collection.id, 3)

            val downloads = ytLog.readLines().filter { it.contains("-o ") }
            downloads.size shouldBe 3
            downloads.all { it.contains("--sponsorblock-remove") } shouldBe true
            File(folder, "Video 1.mp4").readText() shouldBe "made-by-yt-dlp"
        }
}
