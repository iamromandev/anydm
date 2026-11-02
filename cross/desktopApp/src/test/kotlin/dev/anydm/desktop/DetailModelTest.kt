package dev.anydm.desktop

import dev.anydm.engine.DownloadSnapshot
import dev.anydm.engine.FileSnapshot
import dev.anydm.engine.TorrentDetailResult
import dev.anydm.engine.core.Attempt
import dev.anydm.engine.core.AttemptId
import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.DownloadStatus
import dev.anydm.engine.core.DownloadStatus.CANCELLED
import dev.anydm.engine.core.DownloadStatus.COMPLETED
import dev.anydm.engine.core.DownloadStatus.DOWNLOADING
import dev.anydm.engine.core.DownloadStatus.FAILED
import dev.anydm.engine.core.DownloadStatus.MUXING
import dev.anydm.engine.core.DownloadStatus.PAUSED
import dev.anydm.engine.core.DownloadStatus.PENDING
import dev.anydm.engine.core.DownloadStatus.QUEUED
import dev.anydm.engine.core.DownloadStatus.SEEDING
import dev.anydm.engine.core.ErrorClass
import dev.anydm.engine.core.File
import dev.anydm.engine.core.FileId
import dev.anydm.engine.core.Mirror
import dev.anydm.engine.core.MirrorId
import dev.anydm.engine.core.Segment
import dev.anydm.engine.core.SegmentId
import dev.anydm.engine.net.BindStatus
import dev.anydm.engine.net.BondedNetwork
import dev.anydm.engine.net.BondingState
import dev.anydm.engine.net.NetworkInfo
import dev.anydm.engine.platform.FileResult
import dev.anydm.engine.queue.CommandResult
import dev.anydm.engine.torrent.PieceMap
import dev.anydm.engine.torrent.TorrentDetail
import dev.anydm.engine.torrent.TorrentSummary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
internal class DetailModelTest {
    private val mib = 1024L * 1024
    private val id = DownloadId(1)
    private var nowMillis = 0L
    private var present: (Path) -> Boolean = { true }

    private fun detail(
        initial: DownloadSnapshot? = snapshot(1, DOWNLOADING, name = "Project.zip", size = 400 * mib, saveFolder = "/home/me/Downloads"),
        block: suspend TestScope.(FakeSource, FakeFileActions, DetailModel) -> Unit,
    ) = runTest(UnconfinedTestDispatcher()) {
        val source = FakeSource()
        if (initial != null) source.downloads.value = listOf(initial)
        val files = FakeFileActions()
        block(
            source,
            files,
            DetailModel(
                source,
                files,
                id,
                backgroundScope,
                homeFolder = "/home/me",
                clock = { Instant.fromEpochMilliseconds(nowMillis) },
                exists = { present(it) },
            ),
        )
    }

    // Moves the clock on and lets everything due run (a task due at the last millisecond runs too).
    private fun TestScope.second(count: Long = 1) {
        advanceTimeBy(count * 1000)
        runCurrent()
    }

    private fun network(
        key: String,
        name: String = key,
        enabled: Boolean = true,
    ) = BondedNetwork(NetworkInfo(key, name, true, emptyList()), enabled, BindStatus.ISOLATED, null, false)

    private fun mirror(url: String = "https://example.com/Project.zip") = Mirror(MirrorId(9), id, url, 0)

    private fun fileOf(
        index: Int,
        path: String,
        size: Long = 100,
        selected: Boolean = true,
        placed: String? = null,
        segments: List<Segment> = emptyList(),
    ) = FileSnapshot(File(FileId(index + 1L), id, index, path, size, selected, placed), segments)

    // Which page

    @Test
    fun `the page follows what the download is doing`() {
        fun kind(
            status: DownloadStatus,
            torrent: Boolean = false,
        ) = detailKindOf(
            snapshot(1, status, torrent = if (torrent) torrentSummary() else null),
        )
        for (status in listOf(DOWNLOADING, PAUSED, QUEUED, PENDING, MUXING)) {
            assertEquals(DetailKind.ACTIVE, kind(status), "$status")
            assertEquals(DetailKind.TORRENT, kind(status, torrent = true), "$status torrent")
        }
        assertEquals(DetailKind.FINISHED, kind(COMPLETED))
        assertEquals(DetailKind.FINISHED, kind(COMPLETED, torrent = true))
        assertEquals(DetailKind.FINISHED, kind(SEEDING, torrent = true))
        assertEquals(DetailKind.FAILED, kind(FAILED))
        assertEquals(DetailKind.FAILED, kind(FAILED, torrent = true))
    }

    private fun torrentSummary(peers: Int = 12) = TorrentSummary(peers, 3, 1.0, 0, 0, 0, null, 0)

    @Test
    fun `the state carries the kind the download has now`() =
        detail { source, _, model ->
            assertEquals(DetailKind.ACTIVE, model.state.value.kind)
            source.downloads.value = listOf(snapshot(1, FAILED, name = "Project.zip"))
            assertEquals(DetailKind.FAILED, model.state.value.kind)
            source.downloads.value = listOf(snapshot(1, COMPLETED, name = "Project.zip"))
            assertEquals(DetailKind.FINISHED, model.state.value.kind)
        }

    @Test
    fun `a download that is not listed or was cancelled is gone`() =
        detail(initial = null) { source, _, model ->
            assertTrue(model.state.value.gone)
            source.downloads.value = listOf(snapshot(1, DOWNLOADING, name = "a"))
            assertFalse(model.state.value.gone)
            source.downloads.value = listOf(snapshot(1, CANCELLED, name = "a"))
            assertTrue(model.state.value.gone)
            source.downloads.value = emptyList()
            assertTrue(model.state.value.gone)
        }

    // Actions

    @Test
    fun `the button pauses what moves or waits and resumes what is paused`() {
        for ((status, call) in listOf(DOWNLOADING to "pause 1", QUEUED to "pause 1", PENDING to "pause 1", PAUSED to "resume 1")) {
            detail(snapshot(1, status, name = "a")) { source, _, model ->
                model.pauseOrResume()
                assertEquals(listOf(call), source.calls.filter { !it.startsWith("torrentDetail") }, "$status")
            }
        }
    }

    @Test
    fun `no button for a download that is done`() =
        detail(snapshot(1, COMPLETED, name = "a")) { source, _, model ->
            model.pauseOrResume()
            assertTrue(source.calls.isEmpty())
        }

    @Test
    fun `cancel asks first and only a yes cancels`() =
        detail { source, _, model ->
            model.askCancel()
            assertEquals(DetailConfirm.CANCEL, model.state.value.confirm)
            assertTrue(source.calls.isEmpty())
            model.dismissConfirm()
            assertNull(model.state.value.confirm)
            assertTrue(source.calls.isEmpty())
            model.askCancel()
            model.confirm()
            assertNull(model.state.value.confirm)
            assertEquals(listOf("cancel 1"), source.calls)
        }

    @Test
    fun `move to trash asks first and the engine trashes the files only after the yes`() =
        detail(
            snapshot(1, COMPLETED, name = "Project.zip", saveFolder = "/home/me/Downloads", files = listOf(fileOf(0, "Project.zip"))),
        ) { source, files, model ->
            model.askTrash()
            assertEquals(DetailConfirm.TRASH, model.state.value.confirm)
            assertTrue(source.calls.isEmpty())
            model.dismissConfirm()
            assertTrue(source.calls.isEmpty())
            model.askTrash()
            model.confirm()
            assertEquals(listOf("remove 1 TRASH"), source.calls)
            // No path is worked out and handed to the OS: the engine knows which files are the download's.
            assertTrue(files.calls.isEmpty())
        }

    @Test
    fun `a download with files at the root of the save folder never trashes the folder`() =
        detail(
            snapshot(
                1,
                COMPLETED,
                name = "a",
                saveFolder = "/home/me/Downloads",
                files = listOf(fileOf(0, "a.mkv"), fileOf(1, "b.srt")),
            ),
        ) { source, files, model ->
            model.askTrash()
            model.confirm()
            assertEquals(listOf("remove 1 TRASH"), source.calls)
            assertTrue(files.calls.isEmpty())
        }

    @Test
    fun `a trash the engine refuses keeps the job and says why`() {
        detail(snapshot(1, COMPLETED, name = "a", saveFolder = "/x")) { source, _, model ->
            source.results["remove 1 TRASH"] = CommandResult.TrashUnavailable
            model.askTrash()
            model.confirm()
            assertEquals("Could not move to the trash: there is no trash on this machine.", model.state.value.message)
            model.dismissMessage()
            assertNull(model.state.value.message)
        }
        detail(snapshot(1, COMPLETED, name = "a", saveFolder = "/x")) { source, _, model ->
            source.results["remove 1 TRASH"] = CommandResult.TrashFailed(FileResult.AccessDenied(kotlinx.io.files.Path("/x/a"), "busy"))
            model.askTrash()
            model.confirm()
            assertEquals("Could not move to the trash: a file could not be moved there.", model.state.value.message)
        }
    }

    @Test
    fun `retry calls retry`() =
        detail(snapshot(1, FAILED, name = "a")) { source, _, model ->
            model.retry()
            assertEquals(listOf("retry 1"), source.calls)
        }

    @Test
    fun `remove takes a finished download off the list and keeps its file`() =
        detail(snapshot(1, COMPLETED, name = "a")) { source, files, model ->
            model.remove()
            assertEquals(listOf("remove 1 KEEP"), source.calls)
            assertTrue(files.calls.isEmpty())
        }

    @Test
    fun `removing a seeding torrent asks first`() =
        detail(snapshot(1, SEEDING, name = "a", torrent = torrentSummary())) { source, _, model ->
            model.remove()
            assertEquals(DetailConfirm.REMOVE_SEEDING, model.state.value.confirm)
            assertTrue(source.calls.none { it.startsWith("remove") })
            model.confirm()
            assertEquals(listOf("remove 1 KEEP"), source.calls.filter { it.startsWith("remove") })
        }

    @Test
    fun `a new link goes to the first mirror and closes the dialog`() =
        detail(snapshot(1, FAILED, name = "Project.zip", mirrors = listOf(mirror()))) { source, _, model ->
            assertTrue(model.state.value.canFixLink)
            model.openFixLink()
            assertTrue(model.state.value.fixLinkOpen)
            model.relink("  https://mirror.example/new.zip  ")
            assertEquals(listOf("relink 1 9 https://mirror.example/new.zip"), source.calls)
            assertFalse(model.state.value.fixLinkOpen)
        }

    @Test
    fun `a new link that is refused keeps the dialog open with the reason`() =
        detail(snapshot(1, FAILED, name = "Project.zip", mirrors = listOf(mirror()))) { source, _, model ->
            source.results["relink 1 9 nope"] = CommandResult.Invalid("That is not a link.")
            model.openFixLink()
            model.relink("nope")
            assertTrue(model.state.value.fixLinkOpen)
            assertEquals("That is not a link.", model.state.value.fixLinkError)
            model.dismissFixLink()
            assertFalse(model.state.value.fixLinkOpen)
            assertNull(model.state.value.fixLinkError)
        }

    @Test
    fun `an empty link is not sent`() =
        detail(snapshot(1, FAILED, name = "a", mirrors = listOf(mirror()))) { source, _, model ->
            model.relink("   ")
            assertTrue(source.calls.isEmpty())
        }

    @Test
    fun `fix link is for a failed download that has a link and is not a torrent`() {
        detail(snapshot(1, FAILED, name = "a")) { _, _, model -> assertFalse(model.state.value.canFixLink) }
        detail(
            snapshot(1, DOWNLOADING, name = "a", mirrors = listOf(mirror())),
        ) { _, _, model -> assertFalse(model.state.value.canFixLink) }
    }

    @Test
    fun `open and show in finder act on the file`() =
        detail(
            snapshot(1, COMPLETED, name = "Project.zip", saveFolder = "/home/me/Downloads", files = listOf(fileOf(0, "Project.zip"))),
        ) { _, files, model ->
            model.open()
            model.reveal()
            assertEquals(
                listOf("open /home/me/Downloads/Project.zip", "reveal /home/me/Downloads/Project.zip"),
                files.calls,
            )
        }

    @Test
    fun `show in finder on a file not there yet shows the nearest folder that is`() =
        detail(
            snapshot(1, DOWNLOADING, name = "Project.zip", saveFolder = "/home/me/Downloads", files = listOf(fileOf(0, "Sub/Project.zip"))),
        ) { _, files, model ->
            present = { it == Path.of("/home/me/Downloads") }
            model.reveal()
            assertEquals(listOf("reveal /home/me/Downloads"), files.calls)
        }

    @Test
    fun `show in finder with nothing on disk shows the save folder`() =
        detail(
            snapshot(1, DOWNLOADING, name = "Project.zip", saveFolder = "/home/me/Downloads", files = listOf(fileOf(0, "Project.zip"))),
        ) { _, files, model ->
            present = { false }
            model.reveal()
            assertEquals(listOf("reveal /home/me/Downloads"), files.calls)
        }

    @Test
    fun `a download with no save folder has nothing to open`() =
        detail(snapshot(1, DOWNLOADING, name = "a")) { _, files, model ->
            assertFalse(model.state.value.canOpen)
            model.reveal()
            assertTrue(files.calls.isEmpty())
            assertNotNull(model.state.value.message)
        }

    @Test
    fun `a problem the OS reports shows as a message`() =
        detail(snapshot(1, COMPLETED, name = "a", saveFolder = "/x")) { _, files, model ->
            files.problem = "gone"
            model.open()
            assertEquals("gone", model.state.value.message)
        }

    @Test
    fun `copy link copies the first mirror`() =
        detail(snapshot(1, FAILED, name = "a", mirrors = listOf(mirror("https://x.example/f")))) { _, files, model ->
            model.copyLink()
            assertEquals(listOf("copy https://x.example/f"), files.calls)
        }

    @Test
    fun `a command the engine refuses shows a message`() =
        detail { source, _, model ->
            source.results["pause 1"] = CommandResult.NotFound
            model.pauseOrResume()
            assertNotNull(model.state.value.message)
        }

    @Test
    fun `a stopped engine shows a message`() =
        detail { source, _, model ->
            source.refuseAll = true
            model.pauseOrResume()
            assertEquals("The engine has stopped.", model.state.value.message)
        }

    @Test
    fun `the network checkbox switches the network for all downloads, and says so`() =
        detail { source, _, model ->
            model.setNetworkEnabled("en7", false)
            assertEquals(listOf("setNetworkEnabled en7 false"), source.calls)
            assertEquals("Applies to all downloads", NETWORK_SWITCH_HINT)
        }

    // Networks

    private fun twoNetworks(source: FakeSource) {
        source.networks.value = BondingState(listOf(network("en0", "Wi-Fi"), network("en7", "USB Tethering")))
    }

    @Test
    fun `every network is a row with its share and the shares add up to a hundred`() =
        detail(
            snapshot(
                1,
                DOWNLOADING,
                name = "a",
                total = 400 * mib,
                downloaded = 275 * mib,
                networkShare =
                    mapOf(
                        "en0" to 1000L,
                        "en7" to 1000L,
                        "en9" to 1000L,
                    ),
            ),
        ) { source, _, model ->
            source.networks.value =
                BondingState(listOf(network("en0", "Wi-Fi"), network("en7", "USB Tethering"), network("en9", "Ethernet")))
            val rows = model.state.value.networks
            assertEquals(listOf("Wi-Fi", "USB Tethering", "Ethernet"), rows.map { it.name })
            assertEquals(100, rows.sumOf { it.share })
            assertEquals(listOf(34, 33, 33), rows.map { it.share })
        }

    @Test
    fun `a network that is off but carried part of the file is still drawn, one that carried nothing is not`() =
        detail(snapshot(1, DOWNLOADING, name = "a", total = 100, networkShare = mapOf("en7" to 50L))) { source, _, model ->
            source.networks.value = BondingState(listOf(network("en0", "Wi-Fi", enabled = false), network("en7", "USB", enabled = false)))
            val rows =
                model.state.value.networks
                    .associateBy { it.key }
            assertFalse(rows.getValue("en0").inPlay)
            assertTrue(rows.getValue("en7").inPlay)
            assertFalse(rows.getValue("en7").enabled)
        }

    @Test
    fun `a network the engine no longer lists keeps its row by its key`() =
        detail(snapshot(1, DOWNLOADING, name = "a", total = 100, networkShare = mapOf("en0" to 30L, "gone9" to 70L))) { source, _, model ->
            source.networks.value = BondingState(listOf(network("en0", "Wi-Fi")))
            val rows = model.state.value.networks
            assertEquals(listOf("Wi-Fi", "gone9"), rows.map { it.name })
            assertEquals(listOf(30, 70), rows.map { it.share })
            assertFalse(rows[1].enabled)
        }

    @Test
    fun `each network's speed is what it gained in the last second`() =
        detail(
            snapshot(1, DOWNLOADING, name = "a", total = 100 * mib, speed = 4 * mib, networkShare = mapOf("en0" to 0L, "en7" to 0L)),
        ) { source, _, model ->
            twoNetworks(source)
            second()
            source.downloads.value =
                listOf(
                    snapshot(
                        1,
                        DOWNLOADING,
                        name = "a",
                        total = 100 * mib,
                        speed = 4 * mib,
                        networkShare =
                            mapOf(
                                "en0" to 3 * mib,
                                "en7" to 1 * mib,
                            ),
                    ),
                )
            second()
            source.downloads.value =
                listOf(
                    snapshot(
                        1,
                        DOWNLOADING,
                        name = "a",
                        total = 100 * mib,
                        speed = 4 * mib,
                        networkShare =
                            mapOf(
                                "en0" to 6 * mib,
                                "en7" to 2 * mib,
                            ),
                    ),
                )
            second()
            val rows =
                model.state.value.networks
                    .associateBy { it.key }
            assertEquals(3 * mib, rows.getValue("en0").speed)
            assertEquals(1 * mib, rows.getValue("en7").speed)
            assertTrue(rows.getValue("en0").active)
            assertEquals(
                listOf(3 * mib),
                model.state.value.chart
                    .first { it.key == "en0" }
                    .values
                    .takeLast(1),
            )
        }

    @Test
    fun `a paused download shows no speed and its history stops growing`() =
        detail(
            snapshot(1, DOWNLOADING, name = "a", total = 100 * mib, speed = mib, networkShare = mapOf("en0" to 0L)),
        ) { source, _, model ->
            second()
            source.downloads.value =
                listOf(snapshot(1, DOWNLOADING, name = "a", total = 100 * mib, speed = mib, networkShare = mapOf("en0" to mib)))
            second()
            source.downloads.value =
                listOf(snapshot(1, DOWNLOADING, name = "a", total = 100 * mib, speed = mib, networkShare = mapOf("en0" to 2 * mib)))
            second()
            source.downloads.value = listOf(snapshot(1, PAUSED, name = "a", total = 100 * mib, networkShare = mapOf("en0" to 2 * mib)))
            second(5)
            val state = model.state.value
            assertEquals(0, state.speed)
            assertEquals(0L, state.networks.single().speed)
            assertEquals(listOf(mib, mib), state.chart.single().values)
        }

    // "N× alone"

    @Test
    fun `two networks carrying a download together show the alone badge`() =
        detail(
            snapshot(1, DOWNLOADING, name = "a", total = 100 * mib, speed = 12 * mib, networkShare = mapOf("en0" to 0L, "en7" to 0L)),
        ) { source, _, model ->
            twoNetworks(source)
            second()
            source.downloads.value =
                listOf(
                    snapshot(
                        1,
                        DOWNLOADING,
                        name = "a",
                        total = 100 * mib,
                        speed = 12 * mib,
                        networkShare =
                            mapOf(
                                "en0" to 6 * mib,
                                "en7" to 6 * mib,
                            ),
                    ),
                )
            second()
            assertEquals(
                listOf("2.0× WI-FI ALONE", "2.0× USB TETHERING ALONE"),
                model.state.value.badges
                    .map { it.label },
            )
        }

    @Test
    fun `a paused download or one network has no alone badge`() {
        detail(
            snapshot(1, PAUSED, name = "a", total = 100, downloaded = 50, networkShare = mapOf("en0" to 25L, "en7" to 25L)),
        ) { source, _, model ->
            twoNetworks(source)
            assertTrue(
                model.state.value.badges
                    .isEmpty(),
            )
        }
        detail(snapshot(1, DOWNLOADING, name = "a", total = 100, downloaded = 50, networkShare = mapOf("en0" to 50L))) { source, _, model ->
            source.networks.value = BondingState(listOf(network("en0", "Wi-Fi")))
            assertTrue(
                model.state.value.badges
                    .isEmpty(),
            )
        }
    }

    // What the page says about the download

    @Test
    fun `the numbers of an active download`() =
        detail(
            snapshot(
                1,
                DOWNLOADING,
                name = "Project.zip",
                total = 400 * mib,
                downloaded = 275 * mib,
                speed = 13 * mib,
                speedSamples = listOf(5 * mib, 14 * mib, 9 * mib),
                saveFolder = "/home/me/Downloads",
                attempts = listOf(attempt(1, 0, 100, failed = true), attempt(2, 100, null)),
            ),
        ) { _, _, model ->
            val state = model.state.value
            assertEquals("Project.zip", state.name)
            assertEquals("ZIP", state.badge)
            assertEquals(68, state.percent)
            assertEquals("~/Downloads", state.savedTo)
            assertEquals(14 * mib, state.peakSpeed)
            assertEquals(1, state.retries)
            assertEquals(13 * mib, state.speed)
            assertEquals("10s", state.timeLeft)
            assertFalse(state.paused)
        }

    private fun attempt(
        n: Long,
        start: Long,
        end: Long?,
        failed: Boolean = false,
    ) = Attempt(
        AttemptId(n),
        id,
        Instant.fromEpochSeconds(1_000 + start),
        null,
        end?.let { Instant.fromEpochSeconds(1_000 + it) },
        if (failed) ErrorClass.TRANSIENT else null,
        if (failed) "reset" else null,
    )

    @Test
    fun `a finished download tells size, time, peak, networks and what it was written in`() =
        detail(
            snapshot(
                1,
                COMPLETED,
                name = "Project.zip",
                size = 400 * mib,
                downloaded = 400 * mib,
                speedSamples =
                    listOf(
                        2 * mib,
                        10 * mib,
                        6 * mib,
                    ),
                networkShare = mapOf("en0" to 300 * mib, "en7" to 100 * mib),
                attempts = listOf(attempt(1, 0, 20, failed = true), attempt(2, 30, 50)),
                files =
                    listOf(
                        fileOf(
                            0,
                            "Project.zip",
                            400 * mib,
                            segments =
                                (0 until 8).map {
                                    Segment(
                                        SegmentId(it + 1L),
                                        FileId(1),
                                        it * 10L,
                                        it * 10L + 10,
                                        it * 10L + 10,
                                    )
                                },
                        ),
                    ),
            ),
        ) { source, _, model ->
            twoNetworks(source)
            val done = assertNotNull(model.state.value.finished)
            assertEquals(400 * mib, done.size)
            assertEquals(40L, done.seconds)
            assertEquals(10 * mib, done.avgSpeed)
            assertEquals(10 * mib, done.peak)
            assertEquals(2, done.networks)
            assertEquals(listOf(75, 25), done.contributions.map { it.share })
            assertEquals("Chunks", done.thirdLabel)
            assertEquals(8, done.third)
            assertEquals("written in 8 chunks · 1 retry", done.written)
        }

    @Test
    fun `a failed download shows why and the link to copy`() =
        detail(
            snapshot(1, FAILED, name = "a", attempts = listOf(attempt(1, 0, 5, failed = true)), mirrors = listOf(mirror())),
        ) { _, _, model ->
            assertEquals("reset", model.state.value.failure)
            assertEquals("https://example.com/Project.zip", model.state.value.link)
        }

    @Test
    fun `a failure with no message says it stopped`() =
        detail(snapshot(1, FAILED, name = "a")) { _, _, model ->
            assertEquals("The download stopped unexpectedly. Try again.", model.state.value.failure)
        }

    // Chunks

    @Test
    fun `the chunk map is built from the segments and colored by the networks`() =
        detail(
            snapshot(
                1,
                DOWNLOADING,
                name = "a",
                total = 100,
                networkShare = mapOf("en0" to 50L, "en7" to 50L),
                files =
                    listOf(
                        fileOf(
                            0,
                            "a",
                            100,
                            segments =
                                (0 until 10).map {
                                    Segment(
                                        SegmentId(it + 1L),
                                        FileId(1),
                                        it * 10L,
                                        it * 10L + 10,
                                        if (it < 4) it * 10L + 10 else it * 10L,
                                    )
                                },
                        ),
                    ),
            ),
        ) { _, _, model ->
            val map = model.state.value.chunks
            assertEquals(10, map.chunks)
            assertEquals(4, map.cells.count { it.fill == 1f })
            assertEquals(setOf("en0", "en7"), map.cells.mapNotNull { it.key }.toSet())
        }

    @Test
    fun `a torrent polls its pieces and peers`() =
        detail(
            snapshot(1, DOWNLOADING, name = "t", total = 100, torrent = torrentSummary(), networkShare = mapOf("en0" to 10L)),
        ) { source, _, model ->
            val have = byteArrayOf(0b0000_1111)
            source.torrent = TorrentDetailResult.Detail(TorrentDetail(emptyList(), emptyList(), PieceMap(8, have)))
            second(2)
            val state = model.state.value
            assertEquals("pieces", state.chunkLabel)
            assertEquals(8, state.chunks.chunks)
            assertEquals(4, state.chunks.cells.count { it.fill == 1f })
            assertEquals(0, state.peers)
        }

    // Torrent files

    private fun torrentWithFiles(vararg selected: Boolean) =
        snapshot(
            1,
            DOWNLOADING,
            name = "t",
            total = 100,
            torrent = torrentSummary(),
            files = selected.mapIndexed { i, on -> fileOf(i, "t/f$i", 10, selected = on) },
        )

    @Test
    fun `a file ticked or unticked sends the priorities in torrent order`() =
        detail(torrentWithFiles(true, true, false)) { source, _, model ->
            model.setFileSelected(1, false)
            model.setFileSelected(2, true)
            assertEquals(
                // The engine has not answered the first yet: the second still carries it.
                listOf("torrentFiles 1 [4, 0, 0]", "torrentFiles 1 [4, 0, 4]"),
                source.calls.filter { it.startsWith("torrentFiles") },
            )
            assertEquals(
                listOf(true, false, true),
                model.state.value.files
                    .map { it.selected },
            )
        }

    @Test
    fun `a tick the engine has taken over gives way to the engine's word`() =
        detail(torrentWithFiles(true, true, false)) { source, _, model ->
            model.setFileSelected(1, false)
            source.downloads.value = listOf(torrentWithFiles(true, false, false))
            second()
            source.downloads.value = listOf(torrentWithFiles(true, true, false))
            second()
            assertEquals(
                listOf(true, true, false),
                model.state.value.files
                    .map { it.selected },
            )
        }

    @Test
    fun `a tick the engine refused goes back`() =
        detail(torrentWithFiles(true, true, false)) { source, _, model ->
            source.results["torrentFiles 1 [4, 0, 0]"] = CommandResult.NotFound
            model.setFileSelected(1, false)
            assertEquals(
                listOf(true, true, false),
                model.state.value.files
                    .map { it.selected },
            )
            assertNotNull(model.state.value.message)
        }

    @Test
    fun `the last ticked file cannot be unticked`() =
        detail(torrentWithFiles(true, false)) { source, _, model ->
            model.setFileSelected(0, false)
            assertTrue(source.calls.none { it.startsWith("torrentFiles") })
            assertEquals("Keep at least one file.", model.state.value.message)
        }

    @Test
    fun `the file list opens and closes`() =
        detail(torrentWithFiles(true, true)) { _, _, model ->
            assertFalse(model.state.value.filesOpen)
            model.toggleFiles()
            assertTrue(model.state.value.filesOpen)
            assertEquals(2, model.state.value.files.size)
            model.toggleFiles()
            assertFalse(model.state.value.filesOpen)
        }

    // Commands outlive the page

    @Test
    fun `leaving the page in the middle of a command lets it finish`() =
        runTest(UnconfinedTestDispatcher()) {
            val source = FakeSource()
            source.downloads.value = listOf(snapshot(1, COMPLETED, name = "a", saveFolder = "/x"))
            val page = CoroutineScope(Job() + UnconfinedTestDispatcher(testScheduler))
            val model = DetailModel(source, FakeFileActions(), id, page, homeFolder = "/home/me")
            val gate = CompletableDeferred<Unit>()
            source.gate = gate
            model.askTrash()
            model.confirm()
            assertEquals(listOf("remove 1 TRASH"), source.calls)
            page.cancel()
            gate.complete(Unit)
            runCurrent()
            assertEquals(listOf("remove 1 TRASH"), source.finished)
        }

    // Measured time

    @Test
    fun `speeds are measured over the time that really passed between ticks`() =
        detail(
            snapshot(1, DOWNLOADING, name = "a", total = 100 * mib, speed = 3 * mib, networkShare = mapOf("en0" to 0L)),
        ) { source, _, model ->
            nowMillis = 1000
            second()
            source.downloads.value =
                listOf(snapshot(1, DOWNLOADING, name = "a", total = 100 * mib, speed = 3 * mib, networkShare = mapOf("en0" to 3 * mib)))
            nowMillis = 3000
            second()
            assertEquals(
                3 * mib / 2,
                model.state.value.networks
                    .single()
                    .speed,
            )
        }

    // The alone badge compares like with like

    @Test
    fun `the alone badge compares against the sum of what the networks measured`() =
        detail(
            snapshot(1, DOWNLOADING, name = "a", total = 100 * mib, speed = 100 * mib, networkShare = mapOf("en0" to 0L, "en7" to 0L)),
        ) { source, _, model ->
            twoNetworks(source)
            second()
            source.downloads.value =
                listOf(
                    snapshot(
                        1,
                        DOWNLOADING,
                        name = "a",
                        total = 100 * mib,
                        speed = 100 * mib,
                        networkShare = mapOf("en0" to 3 * mib, "en7" to 3 * mib),
                    ),
                )
            second()
            assertEquals(
                listOf("2.0× WI-FI ALONE", "2.0× USB TETHERING ALONE"),
                model.state.value.badges
                    .map { it.label },
            )
        }

    // The chart

    @Test
    fun `the engine's samples split by network do not overflow on a large file`() =
        detail(
            snapshot(
                1,
                DOWNLOADING,
                name = "a",
                total = 100L * 1024 * mib,
                speedSamples = listOf(200 * mib),
                networkShare = mapOf("en0" to 60L * 1024 * mib, "en7" to 40L * 1024 * mib),
            ),
        ) { source, _, model ->
            twoNetworks(source)
            val chart = model.state.value.chart
            assertEquals(listOf(120 * mib), chart.first { it.key == "en0" }.values)
            assertEquals(listOf(80 * mib), chart.first { it.key == "en7" }.values)
            assertFalse(model.state.value.chartMeasured)
        }

    @Test
    fun `a finished download draws its total only, never invented shapes per network`() =
        detail(
            snapshot(
                1,
                COMPLETED,
                name = "a",
                size = 100,
                speedSamples = listOf(5, 9),
                networkShare = mapOf("en0" to 60L, "en7" to 40L),
            ),
        ) { source, _, model ->
            twoNetworks(source)
            val state = model.state.value
            assertEquals(listOf(ChartSeries("", "Total", listOf(5L, 9L))), state.chart)
            assertFalse(state.noSpeedHistory)
        }

    @Test
    fun `a download with no speed history says so instead of blank axes`() =
        detail(snapshot(1, COMPLETED, name = "a", size = 100, networkShare = mapOf("en0" to 100L))) { _, _, model ->
            assertTrue(model.state.value.noSpeedHistory)
        }

    @Test
    fun `a measured series is the page's own, not a fallback`() =
        detail(
            snapshot(1, DOWNLOADING, name = "a", total = 100 * mib, speed = mib, networkShare = mapOf("en0" to 0L)),
        ) { source, _, model ->
            second()
            for (n in 1..2) {
                source.downloads.value =
                    listOf(snapshot(1, DOWNLOADING, name = "a", total = 100 * mib, speed = mib, networkShare = mapOf("en0" to n * mib)))
                second()
            }
            assertTrue(model.state.value.chartMeasured)
            assertFalse(model.state.value.noSpeedHistory)
        }

    // Torrent polling

    @Test
    fun `a paused torrent is asked about its pieces once it answers, not every poll`() =
        detail(snapshot(1, PAUSED, name = "t", total = 100, torrent = torrentSummary())) { source, _, _ ->
            source.torrent = TorrentDetailResult.Detail(TorrentDetail(emptyList(), emptyList(), PieceMap(8, byteArrayOf(0b0000_1111))))
            second(2)
            val answered = source.calls.count { it.startsWith("torrentDetail") }
            second(10)
            assertEquals(answered, source.calls.count { it.startsWith("torrentDetail") })
        }

    @Test
    fun `a paused torrent is asked again until it answers with a detail`() =
        detail(snapshot(1, PAUSED, name = "t", total = 100, torrent = torrentSummary())) { source, _, _ ->
            // The torrent is not in the session yet: that is no look at its pieces, so the next poll asks again.
            second(5)
            val asked = source.calls.count { it.startsWith("torrentDetail") }
            assertTrue(asked >= 2, "asked $asked times")
            source.torrent = TorrentDetailResult.Detail(TorrentDetail(emptyList(), emptyList(), PieceMap(8, byteArrayOf(0b0000_1111))))
            second(5)
            val answered = source.calls.count { it.startsWith("torrentDetail") }
            second(10)
            assertEquals(answered, source.calls.count { it.startsWith("torrentDetail") })
        }

    @Test
    fun `a torrent is asked once more when its status changes`() =
        detail(snapshot(1, PAUSED, name = "t", total = 100, torrent = torrentSummary())) { source, _, _ ->
            source.torrent = TorrentDetailResult.Detail(TorrentDetail(emptyList(), emptyList(), PieceMap(8, byteArrayOf(0b0000_1111))))
            second(2)
            val answered = source.calls.count { it.startsWith("torrentDetail") }
            source.downloads.value = listOf(snapshot(1, QUEUED, name = "t", total = 100, torrent = torrentSummary()))
            second(5)
            assertEquals(answered + 1, source.calls.count { it.startsWith("torrentDetail") })
            second(10)
            assertEquals(answered + 1, source.calls.count { it.startsWith("torrentDetail") })
        }

    @Test
    fun `a downloading torrent keeps polling`() =
        detail(
            snapshot(1, DOWNLOADING, name = "t", total = 100, torrent = torrentSummary()),
        ) { source, _, _ ->
            source.torrent = TorrentDetailResult.Detail(TorrentDetail(emptyList(), emptyList(), PieceMap(8, byteArrayOf(0b0000_1111))))
            second(10)
            assertTrue(source.calls.count { it.startsWith("torrentDetail") } >= 5)
        }

    // Networks that went away

    @Test
    fun `a network that went away keeps the name it had and is marked disconnected`() =
        detail(snapshot(1, DOWNLOADING, name = "a", total = 100, networkShare = mapOf("en0" to 30L, "en7" to 70L))) { source, _, model ->
            twoNetworks(source)
            assertFalse(
                model.state.value.networks
                    .any { it.disconnected },
            )
            source.networks.value = BondingState(listOf(network("en0", "Wi-Fi")))
            val rows =
                model.state.value.networks
                    .associateBy { it.key }
            assertEquals("USB Tethering", rows.getValue("en7").name)
            assertTrue(rows.getValue("en7").disconnected)
            assertFalse(rows.getValue("en0").disconnected)
        }

    @Test
    fun `nothing is disconnected before the engine has listed any network`() =
        detail(snapshot(1, DOWNLOADING, name = "a", total = 100, networkShare = mapOf("en0" to 30L))) { _, _, model ->
            assertFalse(
                model.state.value.networks
                    .any { it.disconnected },
            )
        }

    @Test
    fun `a finished download does not mark its networks disconnected`() =
        detail(snapshot(1, COMPLETED, name = "a", size = 100, networkShare = mapOf("en0" to 30L, "en7" to 70L))) { source, _, model ->
            twoNetworks(source)
            source.networks.value = BondingState(listOf(network("en0", "Wi-Fi")))
            assertFalse(
                model.state.value.networks
                    .any { it.disconnected },
            )
        }

    @Test
    fun `the name a person gave a network shows in the table, the chart legend and the finished contributions`() =
        runTest(UnconfinedTestDispatcher()) {
            val source = FakeSource()
            source.downloads.value =
                listOf(snapshot(1, DOWNLOADING, name = "a", total = 100, networkShare = mapOf("en0" to 30L, "en7" to 70L)))
            twoNetworks(source)
            val visuals = NetworkVisuals(null)
            val looks = NetworkLooksModel(source, visuals, backgroundScope)
            val model = DetailModel(source, FakeFileActions(), id, backgroundScope, looks = looks.looks)
            assertEquals(
                listOf("Wi-Fi", "USB Tethering"),
                model.state.value.networks
                    .map { it.name },
            )
            visuals.rename("en7", "Pixel")
            assertEquals(
                listOf("Wi-Fi", "Pixel"),
                model.state.value.networks
                    .map { it.name },
            )
            assertTrue(
                model.state.value.chart
                    .none { it.name == "USB Tethering" },
            )
            source.downloads.value =
                listOf(snapshot(1, COMPLETED, name = "a", size = 100, networkShare = mapOf("en0" to 30L, "en7" to 70L)))
            assertEquals(
                setOf("Wi-Fi", "Pixel"),
                model.state.value.finished!!
                    .contributions
                    .map { it.name }
                    .toSet(),
            )
        }

    @Test
    fun `a network listed but down is disconnected too`() =
        detail(snapshot(1, DOWNLOADING, name = "a", total = 100, networkShare = mapOf("en0" to 30L))) { source, _, model ->
            source.networks.value =
                BondingState(listOf(BondedNetwork(NetworkInfo("en0", "Wi-Fi", false, emptyList()), true, BindStatus.ISOLATED, null, false)))
            assertTrue(
                model.state.value.networks
                    .single()
                    .disconnected,
            )
        }

    // Where the output is

    @Test
    fun `the output is the file, the folder the files share, or the save folder`() {
        fun path(
            files: List<FileSnapshot>,
            name: String? = null,
            folder: String? = "/d",
        ) = outputPath(snapshot(1, COMPLETED, name = name, saveFolder = folder, files = files))
        assertEquals(Path.of("/d/a.zip"), path(listOf(fileOf(0, "a.zip"))))
        assertEquals(Path.of("/d/a (1).zip"), path(listOf(fileOf(0, "a.zip", placed = "a (1).zip"))))
        assertEquals(Path.of("/d/Sintel"), path(listOf(fileOf(0, "Sintel/a.mkv"), fileOf(1, "Sintel/b.srt"))))
        assertEquals(Path.of("/d"), path(listOf(fileOf(0, "a.mkv"), fileOf(1, "b.srt"))))
        assertEquals(Path.of("/d/x.bin"), path(emptyList(), name = "x.bin"))
        assertEquals(Path.of("/d"), path(emptyList()))
        assertNull(path(listOf(fileOf(0, "a")), folder = null))
    }

    @Test
    fun `a path under the home folder reads with a tilde`() {
        assertEquals("~/Downloads", toDisplayPath("/home/me/Downloads", "/home/me"))
        assertEquals("~", toDisplayPath("/home/me", "/home/me/"))
        assertEquals("/home/meow/x", toDisplayPath("/home/meow/x", "/home/me"))
        assertEquals("/srv/x", toDisplayPath("/srv/x", ""))
    }
}
