package dev.anydm.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.anydm.engine.DownloadSnapshot
import dev.anydm.engine.FileSnapshot
import dev.anydm.engine.TorrentActionResult
import dev.anydm.engine.TorrentDetailResult
import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadStatus
import dev.anydm.engine.core.DownloadStatus.DOWNLOADING
import dev.anydm.engine.core.DownloadStatus.SEEDING
import dev.anydm.engine.core.File
import dev.anydm.engine.core.FileId
import dev.anydm.engine.core.PauseReason
import dev.anydm.engine.queue.CommandResult
import dev.anydm.engine.torrent.PieceMap
import dev.anydm.engine.torrent.SeedRule
import dev.anydm.engine.torrent.TorrentDetail
import dev.anydm.engine.torrent.TorrentPeer
import dev.anydm.engine.torrent.TorrentSummary
import dev.anydm.engine.torrent.TorrentTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Rule
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class TorrentDetailTest {
    @get:Rule
    val rule = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val source = FakeSource()
    private lateinit var model: AppModel
    private val mib = 1024L * 1024

    @AfterTest
    fun close() = scope.cancel()

    private fun show(
        vararg snapshots: DownloadSnapshot,
        width: Dp? = null,
    ) {
        source.downloads.value = snapshots.toList()
        model =
            AppModel(
                source,
                object : FirstRun {
                    override fun isDone() = true

                    override fun markDone() = Unit
                },
                scope,
                FakeFiles(),
            )
        model.start()
        rule.setContent {
            AnydmTheme(dark = false) {
                if (width == null) Shell(model) else Box(Modifier.width(width).height(700.dp).clipToBounds()) { Shell(model) }
            }
        }
    }

    private fun open(
        id: Long,
        tab: String? = null,
    ) {
        rule.onNodeWithTag("row-$id").performClick()
        rule.waitForIdle()
        if (tab != null) {
            rule.onNodeWithTag("tab-$tab").performClick()
            rule.waitForIdle()
        }
    }

    private fun summary(
        ratio: Double? = 0.5,
        finished: Long = 0,
    ) = TorrentSummary(
        peers = 7,
        seeds = 3,
        availability = 1.5,
        downloadRate = 0,
        uploadRate = 2048,
        uploaded = 4 * mib,
        ratio = ratio,
        finishedSeconds = finished,
    )

    private fun tfile(
        id: Long,
        index: Int,
        path: String,
        size: Long = 10 * mib,
    ) = FileSnapshot(File(FileId(index + 1L), DownloadId(id), index, path, size = size), emptyList(), done = size / 2)

    private fun torrent(
        id: Long = 1,
        status: DownloadStatus = SEEDING,
        files: List<FileSnapshot> = listOf(tfile(id, 0, "a/x"), tfile(id, 1, "a/y"), tfile(id, 2, "b")),
        total: Long? = 30 * mib,
        downloaded: Long = 15 * mib,
        speed: Long = 0,
        summary: TorrentSummary = summary(),
    ) = snapshot(id, status, name = "t$id", files = files, total = total, downloaded = downloaded, speed = speed, torrent = summary)

    private fun tracker(
        url: String = "http://tr.example/announce",
        enabled: Boolean = true,
        verified: Boolean = true,
        message: String? = null,
        fails: Int = 0,
        updating: Boolean = false,
        next: Long? = null,
        seeds: Int? = null,
        peers: Int? = null,
    ) = TorrentTracker(url, 0, verified, enabled, false, message, seeds, peers, fails, updating, next)

    private fun detail(
        trackers: List<TorrentTracker> = emptyList(),
        priorities: List<Int> = emptyList(),
        ratio: SeedRule<Double> = SeedRule.Inherit,
        minutes: SeedRule<Int> = SeedRule.Inherit,
    ) = TorrentDetailResult.Detail(TorrentDetail(emptyList(), trackers, PieceMap.Empty), priorities, ratio, minutes)

    @Test
    fun `a torrent has the torrent tabs and opens on the overview`() {
        source.torrent = detail()
        show(torrent())
        open(1)
        listOf("overview", "files", "trackers", "peers", "proxy", "attempts", "errors").forEach {
            rule.onNodeWithTag("tab-$it").assertIsDisplayed()
        }
        rule.onNodeWithTag("tab-segments").assertDoesNotExist()
        rule.onNodeWithTag("tab-mirrors").assertDoesNotExist()
        rule.onNodeWithTag("detail-overview").assertIsDisplayed()
    }

    @Test
    fun `an http job keeps its tabs and opens on the files`() {
        show(snapshot(1, DOWNLOADING, name = "big.iso"))
        open(1)
        listOf("files", "segments", "attempts", "mirrors", "proxy", "errors").forEach {
            rule.onNodeWithTag("tab-$it").assertIsDisplayed()
        }
        listOf("overview", "trackers", "peers").forEach { rule.onNodeWithTag("tab-$it").assertDoesNotExist() }
        rule.onNodeWithTag("detail-files").assertIsDisplayed()
        assertEquals(emptyList(), source.calls.filter { it.startsWith("torrentDetail") })
    }

    @Test
    fun `the overview shows the eta while downloading`() {
        source.torrent = detail()
        show(torrent(status = DOWNLOADING, total = 200 * mib, downloaded = 100 * mib, speed = mib))
        open(1)
        rule.onNodeWithTag("overview-eta").assertTextContains("1m 40s", substring = true)
        rule.onNodeWithTag("overview-progress").assertTextContains("50%", substring = true)
    }

    @Test
    fun `the overview shows a dash for the eta at no speed`() {
        source.torrent = detail()
        show(torrent(status = DOWNLOADING, total = 200 * mib, downloaded = 100 * mib, speed = 0))
        open(1)
        rule.onNodeWithTag("overview-eta").assertTextEquals("ETA —")
    }

    @Test
    fun `the overview shows ratio, uploaded and seeding time`() {
        source.torrent = detail()
        show(torrent(summary = summary(ratio = 0.8, finished = 3700)))
        open(1)
        rule.onNodeWithTag("overview-ratio").assertTextContains("0.80", substring = true)
        rule.onNodeWithTag("overview-uploaded").assertTextContains("4.0 MB", substring = true)
        rule.onNodeWithTag("overview-seeding-time").assertTextContains("1h 01m", substring = true)
    }

    @Test
    fun `the files tab shows a folder tree`() {
        source.torrent = detail()
        show(torrent())
        open(1, "files")
        rule.onNodeWithTag("torrent-folder-toggle-a").assertIsDisplayed()
        rule.onNodeWithTag("torrent-file-0").assertTextContains("x", substring = true)
        rule.onNodeWithTag("torrent-file-1").assertTextContains("y", substring = true)
        rule.onNodeWithTag("torrent-file-2").assertTextContains("b", substring = true)
        rule.onNodeWithTag("torrent-file-priority-0-normal").assertIsSelected()
    }

    @Test
    fun `a folder can be collapsed`() {
        source.torrent = detail()
        show(torrent())
        open(1, "files")
        rule.onNodeWithTag("torrent-folder-toggle-a").performClick()
        rule.onNodeWithTag("torrent-file-0").assertDoesNotExist()
        rule.onNodeWithTag("torrent-file-2").assertIsDisplayed()
    }

    @Test
    fun `skipping a file sends every priority with that one at zero`() {
        source.torrent = detail(priorities = listOf(4, 7, 4))
        show(torrent())
        open(1, "files")
        rule.onNodeWithTag("torrent-file-priority-0-skip").performClick()
        rule.waitForIdle()
        assertEquals("torrentFiles 1 [0, 7, 4]", source.calls.last())
        rule.onNodeWithTag("torrent-file-priority-0-skip").assertIsSelected()
    }

    @Test
    fun `no stored priorities mean every file is normal`() {
        source.torrent = detail()
        show(torrent())
        open(1, "files")
        rule.onNodeWithTag("torrent-file-priority-2-high").performClick()
        rule.waitForIdle()
        assertEquals("torrentFiles 1 [4, 4, 7]", source.calls.last())
    }

    @Test
    fun `a folder sets all its files`() {
        source.torrent = detail()
        show(torrent())
        open(1, "files")
        rule.onNodeWithTag("torrent-folder-chips-a-high").performClick()
        rule.waitForIdle()
        assertEquals("torrentFiles 1 [7, 7, 4]", source.calls.last())
    }

    @Test
    fun `skipping a file warns next to it, normal and high do not`() {
        source.torrent = detail()
        show(torrent())
        open(1, "files")
        rule.onNodeWithTag("torrent-skip-note").assertDoesNotExist()
        rule.onNodeWithTag("torrent-file-priority-2-high").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("torrent-skip-note").assertDoesNotExist()
        rule.onNodeWithTag("torrent-files-note").assertDoesNotExist()
        rule.onNodeWithTag("torrent-file-priority-0-skip").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("torrent-skip-note").assertIsDisplayed()
        rule.onNodeWithTag("torrent-file-priority-0-normal").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("torrent-skip-note").assertIsDisplayed()
        rule.onNodeWithTag("torrent-file-priority-1-high").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("torrent-skip-note").assertDoesNotExist()
    }

    @Test
    fun `a failed files change does not discard a newer one`() {
        source.torrent = detail()
        source.results["torrentFiles 1 [0, 4, 4]"] = CommandResult.Invalid("no")
        show(torrent())
        open(1, "files")
        rule.onNodeWithTag("torrent-file-priority-0-skip").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("torrent-file-priority-0-normal").assertIsSelected()
        // The refusal's banner takes room, so the chip may need scrolling to.
        rule.onNodeWithTag("torrent-file-priority-2-high").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals("torrentFiles 1 [4, 4, 7]", source.calls.last())
        rule.onNodeWithTag("torrent-file-priority-2-high").assertIsSelected()
    }

    @Test
    fun `a priority change shows right after switching tabs`() {
        source.torrent = detail()
        show(torrent())
        open(1, "files")
        rule.onNodeWithTag("torrent-file-priority-2-high").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("tab-trackers").performClick()
        rule.onNodeWithTag("tab-files").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("torrent-file-priority-2-high").assertIsSelected()
    }

    @Test
    fun `a large torrent opens collapsed, caps its rows and shows more on request`() {
        val files = (0 until 320).map { tfile(1, it, "d/f$it") }
        source.torrent = detail()
        show(torrent(files = files))
        open(1, "files")
        rule.onNodeWithTag("torrent-file-0").assertDoesNotExist()
        rule.onNodeWithTag("torrent-folder-toggle-d").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("torrent-file-0").assertExists()
        rule.onNodeWithTag("torrent-file-319").assertDoesNotExist()
        rule.onNodeWithTag("torrent-files-more").assertTextContains("21", substring = true)
        rule.onNodeWithTag("torrent-files-show-more").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("torrent-file-319").assertExists()
        rule.onNodeWithTag("torrent-files-more").assertDoesNotExist()
    }

    @Test
    fun `nested and unicode paths build nested folders`() {
        source.torrent = detail()
        show(torrent(files = listOf(tfile(1, 0, "ä/日本/x.txt"), tfile(1, 1, "ä/日本/y.txt"), tfile(1, 2, "ä/z"))))
        open(1, "files")
        rule.onNodeWithTag("torrent-folder-toggle-ä").assertIsDisplayed()
        rule.onNodeWithTag("torrent-folder-toggle-ä/日本").assertIsDisplayed()
        rule.onNodeWithTag("torrent-file-0").assertTextContains("x.txt", substring = true)
        rule.onNodeWithTag("torrent-folder-chips-ä/日本-skip").performClick()
        rule.waitForIdle()
        assertEquals("torrentFiles 1 [0, 0, 4]", source.calls.last())
    }

    @Test
    fun `a tracker with a plain message still shows working`() {
        source.torrent =
            detail(listOf(tracker(message = "Tracker says hi"), tracker("http://b.example/announce", message = "bad", fails = 2)))
        show(torrent())
        open(1, "trackers")
        rule.onNodeWithTag("tracker-status-0").assertTextEquals("Working")
        rule.onNodeWithTag("tracker-message-0").assertTextContains("Tracker says hi", substring = true)
        rule.onNodeWithTag("tracker-status-1").assertTextEquals("bad")
    }

    @Test
    fun `a refused priority change goes back`() {
        source.torrent = detail()
        source.results["torrentFiles 1 [0, 4, 4]"] = CommandResult.Invalid("no")
        show(torrent())
        open(1, "files")
        rule.onNodeWithTag("torrent-file-priority-0-skip").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("torrent-file-priority-0-normal").assertIsSelected()
    }

    @Test
    fun `the files tab says it is waiting for a magnet's metadata`() {
        source.torrent = detail()
        show(torrent(files = emptyList(), total = null))
        open(1, "files")
        rule.onNodeWithTag("torrent-no-metadata").assertIsDisplayed()
    }

    @Test
    fun `a tracker row shows its error and reannounces`() {
        source.torrent =
            detail(
                listOf(
                    tracker("http://a.example/announce", message = "tracker is down", fails = 3, seeds = 12, peers = 4),
                    tracker("http://b.example/announce", next = 65),
                ),
            )
        show(torrent())
        open(1, "trackers")
        rule.onNodeWithTag("tracker-status-0").assertTextContains("tracker is down", substring = true)
        rule.onNodeWithTag("tracker-fails-0").assertTextContains("3", substring = true)
        rule.onNodeWithTag("tracker-scrape-0").assertTextContains("12", substring = true)
        rule.onNodeWithTag("tracker-reannounce-0").performClick()
        rule.waitForIdle()
        assertEquals("reannounce 1 http://a.example/announce", source.calls.last())
        rule.onNodeWithTag("tracker-reannounce-all").performClick()
        rule.waitForIdle()
        assertEquals("reannounce 1 all", source.calls.last())
    }

    @Test
    fun `every torrent tab is reachable at 900 dp with a long name and a long status`() {
        source.torrent = detail()
        val job =
            torrent().let {
                it.copy(
                    download =
                        it.download.copy(
                            fileName = "n".repeat(120),
                            status = DownloadStatus.PAUSED,
                            pauseReasons = setOf(PauseReason.LOW_DISK),
                        ),
                )
            }
        show(job, width = 900.dp)
        rule.onNodeWithTag("row-1").performClick()
        rule.waitForIdle()
        listOf("overview", "files", "trackers", "peers", "proxy", "attempts", "errors").forEach {
            rule.onNodeWithTag("tab-$it").performClick()
            rule.waitForIdle()
            rule.onNodeWithTag("detail-$it").assertIsDisplayed()
        }
    }

    @Test
    fun `a long tracker url is available in full`() {
        val url = "http://tracker.example/" + "x".repeat(200) + "/announce"
        source.torrent = detail(listOf(tracker(url)))
        show(torrent())
        open(1, "trackers")
        rule.onNodeWithTag("tracker-0").assertContentDescriptionEquals(url)
    }

    @Test
    fun `a peer address and client are available in full`() {
        val client = "c".repeat(150)
        val address = "[2001:db8::" + "a".repeat(100) + "]:6881"
        source.torrent =
            TorrentDetailResult.Detail(
                TorrentDetail(listOf(TorrentPeer(address, client, 0.5, 0, 0, 0, 0, false)), emptyList(), PieceMap.Empty),
                emptyList(),
                SeedRule.Inherit,
                SeedRule.Inherit,
            )
        show(torrent())
        open(1, "peers")
        rule.onNodeWithTag("peer-address-0").assertContentDescriptionEquals(address)
        rule.onNodeWithTag("peer-client-0").assertContentDescriptionEquals(client)
    }

    @Test
    fun `the next announce is shown only when known`() {
        source.torrent = detail(listOf(tracker("http://a.example/announce"), tracker("http://b.example/announce", next = 65)))
        show(torrent())
        open(1, "trackers")
        rule.onNodeWithTag("tracker-next-0").assertDoesNotExist()
        rule.onNodeWithTag("tracker-next-1").assertTextEquals("Next announce in 1m 05s")
    }

    @Test
    fun `a disabled tracker cannot be reannounced`() {
        source.torrent = detail(listOf(tracker(enabled = false), tracker("http://b.example/announce")))
        show(torrent())
        open(1, "trackers")
        rule.onNodeWithTag("tracker-reannounce-0").assertIsNotEnabled()
        rule.onNodeWithTag("tracker-reannounce-1").assertIsEnabled()
    }

    @Test
    fun `a torrent without trackers says so`() {
        source.torrent = detail()
        show(torrent())
        open(1, "trackers")
        rule.onNodeWithTag("trackers-none").assertTextEquals("No trackers.")
    }

    @Test
    fun `a ratio rule is saved and shown again`() {
        source.torrent = detail()
        show(torrent())
        open(1)
        rule.onNodeWithTag("seed-ratio-at").performScrollTo().performClick()
        rule.onNodeWithTag("seed-ratio-field").performScrollTo().performTextReplacement("1.5")
        rule.onNodeWithTag("seed-ratio-set").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals("seedRules 1 ${SeedRule.At(1.5)} ${SeedRule.Inherit}", source.calls.last())

        source.torrent = detail(ratio = SeedRule.At(1.5), minutes = SeedRule.At(90))
        rule.mainClock.advanceTimeBy(2_100)
        rule.onNodeWithTag("tab-files").performClick()
        rule.onNodeWithTag("tab-overview").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("seed-ratio-at").assertIsSelected()
        rule.onNodeWithTag("seed-ratio-field").assertTextContains("1.5", substring = true)
        rule.onNodeWithTag("seed-minutes-field").assertTextContains("90", substring = true)
    }

    @Test
    fun `switching tabs right after Set keeps the saved rule`() {
        source.torrent = detail()
        show(torrent())
        open(1)
        rule.onNodeWithTag("seed-ratio-at").performScrollTo().performClick()
        rule.onNodeWithTag("seed-ratio-field").performScrollTo().performTextReplacement("1.5")
        rule.onNodeWithTag("seed-ratio-set").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("tab-files").performClick()
        rule.onNodeWithTag("tab-overview").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("seed-ratio-at").assertIsSelected()
        rule.onNodeWithTag("seed-ratio-field").assertTextContains("1.5", substring = true)
    }

    @Test
    fun `a failed poll in the middle of an edit keeps the editor and the draft`() {
        source.torrent = detail()
        show(torrent())
        open(1)
        rule.onNodeWithTag("seed-ratio-at").performScrollTo().performClick()
        rule.onNodeWithTag("seed-ratio-field").performScrollTo().performTextReplacement("2")
        source.detailThrows = IllegalStateException("poll failed")
        rule.mainClock.advanceTimeBy(2_100)
        rule.waitForIdle()
        rule.onNodeWithTag("seed-ratio-field").assertTextContains("2", substring = true)
        rule.onNodeWithTag("seed-ratio-set").assertIsEnabled()
    }

    @Test
    fun `a poll that says not in session keeps the draft but turns the controls off`() {
        source.torrent = detail()
        show(torrent())
        open(1)
        rule.onNodeWithTag("seed-ratio-at").performScrollTo().performClick()
        rule.onNodeWithTag("seed-ratio-field").performScrollTo().performTextReplacement("2")
        source.torrent = TorrentDetailResult.NotInSession
        rule.mainClock.advanceTimeBy(2_100)
        rule.waitForIdle()
        rule.onNodeWithTag("seed-ratio-field").assertTextContains("2", substring = true)
        rule.onNodeWithTag("torrent-recheck").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `a refused older save does not undo a newer one`() {
        source.torrent = detail()
        source.results["seedRules 1 ${SeedRule.Off} ${SeedRule.Inherit}"] = CommandResult.Invalid("no")
        source.seedGate = kotlinx.coroutines.CompletableDeferred()
        show(torrent())
        open(1)
        rule.onNodeWithTag("seed-ratio-off").performScrollTo().performClick()
        rule.onNodeWithTag("seed-minutes-off").performScrollTo().performClick()
        rule.waitForIdle()
        source.seedGate?.complete(Unit)
        rule.waitForIdle()
        rule.onNodeWithTag("seed-minutes-off").assertIsSelected()
    }

    @Test
    fun `an old poll does not undo a save`() {
        source.torrent = detail()
        show(torrent())
        open(1)
        rule.onNodeWithTag("seed-ratio-off").performScrollTo().performClick()
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(2_100)
        rule.waitForIdle()
        rule.onNodeWithTag("seed-ratio-off").assertIsSelected()
    }

    @Test
    fun `a refused save goes back`() {
        source.torrent = detail()
        source.results["seedRules 1 ${SeedRule.Off} ${SeedRule.Inherit}"] = CommandResult.Invalid("no")
        show(torrent())
        open(1)
        rule.onNodeWithTag("seed-ratio-off").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("seed-ratio-inherit").assertIsSelected()
    }

    @Test
    fun `invalid seed input shows an error and sends nothing`() {
        source.torrent = detail()
        show(torrent())
        open(1)
        rule.onNodeWithTag("seed-ratio-at").performScrollTo().performClick()
        listOf("0", "-1", "abc", "NaN", "Infinity", "1e999", "1.5d", "0x1p3").forEach { bad ->
            rule.onNodeWithTag("seed-ratio-field").performScrollTo().performTextReplacement(bad)
            rule.onNodeWithTag("seed-ratio-error").assertIsDisplayed()
            rule.onNodeWithTag("seed-ratio-set").assertIsNotEnabled()
        }
        rule.onNodeWithTag("seed-minutes-at").performScrollTo().performClick()
        listOf("0", "-5", "1.5", "x", "99999999999").forEach { bad ->
            rule.onNodeWithTag("seed-minutes-field").performScrollTo().performTextReplacement(bad)
            rule.onNodeWithTag("seed-minutes-error").assertIsDisplayed()
            rule.onNodeWithTag("seed-minutes-set").assertIsNotEnabled()
        }
        assertEquals(emptyList(), source.calls.filter { it.startsWith("seedRules") })
        rule.onNodeWithTag("seed-minutes-field").performScrollTo().performTextReplacement("90")
        rule.onNodeWithTag("seed-minutes-error").assertDoesNotExist()
        rule.onNodeWithTag("seed-minutes-set").assertIsEnabled()
    }

    @Test
    fun `committing one rule keeps a draft in the other editor`() {
        source.torrent = detail(minutes = SeedRule.At(30))
        show(torrent())
        open(1)
        rule.onNodeWithTag("seed-minutes-at").performScrollTo().performClick()
        rule.onNodeWithTag("seed-minutes-field").performScrollTo().performTextReplacement("45")
        rule.onNodeWithTag("seed-ratio-off").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("seed-ratio-off").assertIsSelected()
        rule.onNodeWithTag("seed-minutes-field").assertTextContains("45", substring = true)
    }

    @Test
    fun `a refused commit keeps a draft in the other editor`() {
        source.torrent = detail(minutes = SeedRule.At(30))
        source.results["seedRules 1 ${SeedRule.Off} ${SeedRule.At(30)}"] = CommandResult.Invalid("no")
        show(torrent())
        open(1)
        rule.onNodeWithTag("seed-minutes-at").performScrollTo().performClick()
        rule.onNodeWithTag("seed-minutes-field").performScrollTo().performTextReplacement("45")
        rule.onNodeWithTag("seed-ratio-off").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("seed-minutes-field").assertTextContains("45", substring = true)
    }

    @Test
    fun `a very small saved ratio is shown as a plain decimal and can be set`() {
        source.torrent = detail(ratio = SeedRule.At(0.0001))
        show(torrent())
        open(1)
        rule.onNodeWithTag("seed-ratio-field").performScrollTo().assertTextContains("0.0001", substring = true)
        rule.onNodeWithTag("seed-ratio-error").assertDoesNotExist()
        rule.onNodeWithTag("seed-ratio-set").assertIsEnabled()
    }

    @Test
    fun `a rule set to off is sent at once and keeps the other rule`() {
        source.torrent = detail(minutes = SeedRule.At(30))
        show(torrent())
        open(1)
        rule.onNodeWithTag("seed-ratio-off").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals("seedRules 1 ${SeedRule.Off} ${SeedRule.At(30)}", source.calls.last())
    }

    @Test
    fun `recheck, reannounce all and stop seeding reach the engine`() {
        source.torrent = detail()
        show(torrent())
        open(1)
        rule.onNodeWithTag("torrent-recheck").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals("recheck 1", source.calls.last())
        rule.onNodeWithTag("torrent-reannounce").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals("reannounce 1 all", source.calls.last())
        rule.onNodeWithTag("torrent-stop-seeding").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals("stopSeeding 1", source.calls.last())
    }

    @Test
    fun `a refused recheck is reported`() {
        source.torrent = detail()
        source.recheckAction = TorrentActionResult.NotInSession
        show(torrent())
        open(1)
        rule.onNodeWithTag("torrent-recheck").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(
            1,
            model.state.value.banners
                .filterIsInstance<Banner.Error>()
                .size,
        )
    }

    @Test
    fun `a recheck that went through says so`() {
        source.torrent = detail()
        show(torrent())
        open(1)
        rule.onNodeWithTag("torrent-recheck-started").assertDoesNotExist()
        rule.onNodeWithTag("torrent-recheck").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("torrent-recheck-started").assertIsDisplayed()
    }

    @Test
    fun `an engine exception becomes a banner`() {
        source.torrent = detail()
        source.torrentThrows = RuntimeException("boom")
        show(torrent())
        open(1)
        rule.onNodeWithTag("torrent-recheck").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(
            1,
            model.state.value.banners
                .filterIsInstance<Banner.Error>()
                .size,
        )
        rule.onNodeWithTag("torrent-recheck-started").assertDoesNotExist()
    }

    @Test
    fun `the overview says loading before the first poll answers`() {
        source.torrent = detail()
        source.gate = kotlinx.coroutines.CompletableDeferred()
        show(torrent())
        open(1)
        rule.onNodeWithTag("torrent-loading").assertIsDisplayed()
        rule.onNodeWithTag("torrent-unavailable").assertDoesNotExist()
        source.gate?.complete(Unit)
        rule.waitForIdle()
        rule.onNodeWithTag("torrent-loading").assertDoesNotExist()
    }

    @Test
    fun `there is no stop seeding for a torrent that is downloading`() {
        source.torrent = detail()
        show(torrent(status = DOWNLOADING))
        open(1)
        rule.onNodeWithTag("torrent-stop-seeding").assertDoesNotExist()
    }

    @Test
    fun `every section is unavailable and the controls are off when the torrent is not in the session`() {
        source.torrent = TorrentDetailResult.NotInSession
        show(torrent())
        open(1)
        rule.onNodeWithTag("torrent-unavailable").assertIsDisplayed()
        rule.onNodeWithTag("seed-ratio-at").assertDoesNotExist()
        rule.onNodeWithTag("seed-minutes-at").assertDoesNotExist()
        rule.onNodeWithTag("torrent-recheck").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag("torrent-reannounce").assertIsNotEnabled()
        rule.onNodeWithTag("torrent-stop-seeding").assertIsNotEnabled()
        rule.onNodeWithTag("tab-files").performClick()
        rule.onNodeWithTag("torrent-unavailable").assertIsDisplayed()
        rule.onNodeWithTag("torrent-file-priority-0-skip").assertIsNotEnabled()
        rule.onNodeWithTag("tab-trackers").performClick()
        rule.onNodeWithTag("torrent-unavailable").assertIsDisplayed()
        rule.onNodeWithTag("tracker-reannounce-all").assertIsNotEnabled()
        rule.onNodeWithTag("tab-peers").performClick()
        rule.onNodeWithTag("peers-unavailable").assertIsDisplayed()
    }

    @Test
    fun `an unavailable engine does not crash the sections`() {
        source.torrent = TorrentDetailResult.Unavailable
        show(torrent())
        open(1, "trackers")
        rule.onNodeWithTag("torrent-unavailable").assertIsDisplayed()
    }

    @Test
    fun `a detail answer does not carry over to another selection`() {
        source.torrentById[1] = detail(listOf(tracker("http://one.example/announce")))
        source.torrentById[2] = TorrentDetailResult.NotInSession
        show(torrent(1), torrent(2))
        open(1, "trackers")
        rule.onNodeWithTag("tracker-0").assertIsDisplayed()
        open(2, "trackers")
        rule.onNodeWithTag("tracker-0").assertDoesNotExist()
        rule.onNodeWithTag("torrent-unavailable").assertIsDisplayed()
    }
}
