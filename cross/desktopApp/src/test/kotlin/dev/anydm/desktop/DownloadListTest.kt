package dev.anydm.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.anydm.engine.FileSnapshot
import dev.anydm.engine.core.Attempt
import dev.anydm.engine.core.AttemptId
import dev.anydm.engine.core.CollectionKind
import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.DownloadStatus.COMPLETED
import dev.anydm.engine.core.DownloadStatus.DOWNLOADING
import dev.anydm.engine.core.DownloadStatus.FAILED
import dev.anydm.engine.core.DownloadStatus.PAUSED
import dev.anydm.engine.core.DownloadStatus.QUEUED
import dev.anydm.engine.core.DownloadStatus.SEEDING
import dev.anydm.engine.core.ErrorClass
import dev.anydm.engine.core.File
import dev.anydm.engine.core.FileId
import dev.anydm.engine.core.ItemCounts
import dev.anydm.engine.core.Mirror
import dev.anydm.engine.core.MirrorId
import dev.anydm.engine.core.PauseReason
import dev.anydm.engine.core.ProxyId
import dev.anydm.engine.core.Step
import dev.anydm.engine.core.StepFailure
import dev.anydm.engine.net.ProxyChoice
import dev.anydm.engine.net.ProxyEntry
import dev.anydm.engine.net.ProxyType
import dev.anydm.engine.queue.CommandResult
import dev.anydm.engine.torrent.TorrentSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Rule
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalTestApi::class)
class DownloadListTest {
    @get:Rule
    val rule = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val source = FakeSource()
    private val files = FakeFiles()
    private lateinit var model: AppModel
    private val mib = 1024L * 1024

    @AfterTest
    fun close() = scope.cancel()

    private fun show(
        vararg snapshots: dev.anydm.engine.DownloadSnapshot,
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
                files,
            )
        model.start()
        rule.setContent {
            AnydmTheme(dark = false) {
                if (width == null) Shell(model) else Box(Modifier.width(width).height(800.dp).clipToBounds()) { Shell(model) }
            }
        }
    }

    private fun row(id: Long) = rule.onNodeWithTag("row-$id")

    private val command = if (IsMac) Key.MetaLeft else Key.CtrlLeft

    @Test
    fun `rows show name, progress, eta and status, and follow the snapshots`() {
        show(snapshot(1, DOWNLOADING, name = "big.iso", total = 100 * mib, downloaded = 40 * mib, speed = mib))
        rule.onNodeWithTag("name-1").assertTextEquals("big.iso")
        rule.onNodeWithTag("progress-1").assertTextContains("40%", substring = true)
        rule.onNodeWithTag("eta-1").assertTextEquals("1m 00s")
        rule.onNodeWithTag("status-1").assertTextEquals("Downloading")

        source.downloads.value = listOf(snapshot(1, COMPLETED, name = "big.iso", total = 100 * mib, downloaded = 100 * mib))
        rule.waitForIdle()
        rule.onNodeWithTag("status-1").assertTextEquals("Completed")
        rule.onNodeWithTag("progress-1").assertTextContains("100%", substring = true)
    }

    @Test
    fun `click, command-click and shift-click select`() {
        show(*(1L..4L).map { snapshot(it, QUEUED) }.toTypedArray())
        row(1).performClick()
        assertEquals(setOf(DownloadId(1)), model.state.value.selection)
        row(3).performKeyInput { keyDown(command) }
        row(3).performMouseInput { click() }
        row(3).performKeyInput { keyUp(command) }
        assertEquals(setOf(DownloadId(1), DownloadId(3)), model.state.value.selection)
        row(1).performClick()
        row(3).performKeyInput { keyDown(Key.ShiftLeft) }
        row(3).performMouseInput { click() }
        row(3).performKeyInput { keyUp(Key.ShiftLeft) }
        assertEquals(setOf(DownloadId(1), DownloadId(2), DownloadId(3)), model.state.value.selection)
    }

    @Test
    fun `the bulk toolbar pauses every selected job`() {
        show(snapshot(1, DOWNLOADING), snapshot(2, DOWNLOADING), snapshot(3, COMPLETED))
        model.selectAll()
        rule.waitForIdle()
        rule.onNodeWithTag("cmd-retry").assertIsNotEnabled()
        rule.onNodeWithTag("cmd-pause").assertIsEnabled().performClick()
        assertEquals(listOf("pause 1", "pause 2"), source.calls)
    }

    @Test
    fun `the context menu offers only what applies`() {
        show(snapshot(1, FAILED), snapshot(2, DOWNLOADING))
        row(1).performMouseInput { rightClick() }
        rule.onNodeWithTag("menu-retry").assertIsDisplayed()
        rule.onNodeWithTag("menu-pause").assertDoesNotExist()
        rule.onNodeWithTag("menu-retry").performClick()
        assertEquals(listOf("retry 1"), source.calls)
    }

    @Test
    fun `remove asks before it removes`() {
        show(snapshot(1, COMPLETED))
        row(1).performClick()
        rule.onNodeWithTag("cmd-remove").performClick()
        rule.onNodeWithTag("remove-dialog").assertIsDisplayed()
        rule.onNodeWithTag("remove-keep").performClick()
        rule.onNodeWithTag("remove-dialog").assertDoesNotExist()
        assertEquals(listOf("remove 1 KEEP"), source.calls)
    }

    @Test
    fun `a refused relink keeps the dialog with the reason`() {
        val attempt =
            Attempt(AttemptId(1), DownloadId(1), Instant.fromEpochSeconds(0), MirrorId(5), errorClass = ErrorClass.GONE, message = "404")
        show(
            snapshot(1, FAILED, attempts = listOf(attempt), mirrors = listOf(Mirror(MirrorId(5), DownloadId(1), "https://a.example/f", 0))),
        )
        rule.onNodeWithTag("error-1").assertTextEquals("404")
        row(1).performMouseInput { rightClick() }
        rule.onNodeWithTag("menu-relink").performClick()
        source.results["relink 1 5 https://b.example/f"] =
            CommandResult.RelinkFailed(StepFailure(Step.PROBE, ErrorClass.GONE, "still gone"))
        rule.onNodeWithTag("relink-url").performTextReplacement("https://b.example/f")
        rule.onNodeWithTag("relink-ok").performClick()
        rule.onNodeWithTag("relink-error").assertTextEquals("still gone")
        rule.onNodeWithTag("relink-cancel").performClick()
        rule.onNodeWithTag("relink-dialog").assertDoesNotExist()
    }

    @Test
    fun `the sort menu reorders the rows and turns the direction round`() {
        show(snapshot(1, QUEUED, name = "b", size = 10), snapshot(2, QUEUED, name = "a", size = 30))
        rule.onNodeWithTag("sort-menu").performClick()
        SortKey.entries.forEach { rule.onNodeWithTag("sort-${it.name.lowercase()}").assertIsDisplayed() }
        rule.onNodeWithTag("sort-size").performClick()
        assertEquals(
            listOf(1L, 2L),
            model.state.value.rows
                .map { it.id.value },
        )
        rule.onNodeWithTag("sort-menu").performClick()
        rule.onNodeWithTag("sort-size").assertTextContains("↑", substring = true).performClick()
        assertEquals(
            listOf(2L, 1L),
            model.state.value.rows
                .map { it.id.value },
        )
        rule.onNodeWithTag("sort-menu").performClick()
        rule.onNodeWithTag("sort-size").assertTextContains("↓", substring = true)
        rule.onNodeWithTag("sort-name").assertTextEquals("Name")
    }

    @Test
    fun `a card shows badge, source, speed and eta for an http and a torrent download`() {
        val http = snapshot(1, DOWNLOADING, name = "big.iso", total = 100 * mib, downloaded = 40 * mib, speed = mib)
        val torrent =
            snapshot(2, DOWNLOADING, name = "t", total = 100 * mib, downloaded = 50 * mib, speed = 2 * mib, kind = DownloadKind.TORRENT)
        show(
            http.copy(mirrors = listOf(Mirror(MirrorId(1), DownloadId(1), "https://a.example/big.iso", 0))),
            torrent.copy(download = torrent.download.copy(sourceUrl = "magnet:?xt=urn:btih:abc")),
        )
        rule.onNodeWithTag("badge-1").assertTextEquals("HTTP")
        rule.onNodeWithTag("source-1").assertTextEquals("https://a.example/big.iso")
        rule.onNodeWithTag("speed-1").assertTextEquals(formatSpeed(mib))
        rule.onNodeWithTag("eta-1").assertTextEquals("1m 00s")
        rule.onNodeWithTag("badge-2").assertTextEquals("Torrent")
        rule.onNodeWithTag("source-2").assertTextEquals("magnet:?xt=urn:btih:abc")
        rule.onNodeWithTag("speed-2").assertTextEquals(formatSpeed(2 * mib))
        rule.onNodeWithTag("priority-1").assertIsDisplayed()
        rule.onNodeWithTag("size-1").assertIsDisplayed()
    }

    @Test
    fun `a card keeps the disk-full pause text`() {
        show(snapshot(1, PAUSED, pauseReasons = setOf(PauseReason.LOW_DISK)), snapshot(2, PAUSED, pauseReasons = setOf(PauseReason.GLOBAL)))
        rule.onNodeWithTag("status-1").assertTextEquals("Paused (disk full)")
        rule.onNodeWithTag("status-2").assertTextEquals("Paused (all)")
    }

    @Test
    fun `the toolbar pause and resume follow the selection`() {
        show(snapshot(1, DOWNLOADING), snapshot(2, PAUSED))
        rule.onNodeWithTag("cmd-pause").assertIsNotEnabled()
        rule.onNodeWithTag("cmd-resume").assertIsNotEnabled()
        rule.onNodeWithTag("add-open").assertIsEnabled()
        rule.onNodeWithTag("pause-all").assertIsEnabled()
        listOf("cmd-cancel", "cmd-retry", "cmd-remove").forEach { rule.onNodeWithTag(it).assertIsDisplayed().assertIsNotEnabled() }
        row(1).performClick()
        rule.onNodeWithTag("cmd-pause").assertIsEnabled()
        rule.onNodeWithTag("cmd-resume").assertIsNotEnabled()
        row(2).performClick()
        rule.onNodeWithTag("cmd-pause").assertIsNotEnabled()
        rule.onNodeWithTag("cmd-resume").assertIsEnabled()
    }

    private val toolbarTags =
        listOf(
            "add-open",
            "cmd-pause",
            "cmd-resume",
            "cmd-cancel",
            "cmd-retry",
            "cmd-remove",
            "pause-all",
            "resume-all",
            "query",
            "sort-menu",
        )

    private fun assertToolbarInside(width: Dp) {
        toolbarTags.forEach { tag ->
            val bounds = rule.onNodeWithTag(tag).getUnclippedBoundsInRoot()
            assertTrue(
                bounds.right <= width && bounds.left >= 0.dp && bounds.top >= 0.dp && bounds.bottom <= 700.dp,
                "$tag is outside the window: $bounds",
            )
        }
    }

    @Test
    fun `a narrow window keeps every toolbar action reachable`() {
        show(snapshot(1, DOWNLOADING), width = 420.dp)
        row(1).performClick()
        assertToolbarInside(420.dp)
    }

    @Test
    fun `at the minimum width with a selection the toolbar stays inside the window`() {
        show(snapshot(1, DOWNLOADING), width = 900.dp)
        row(1).performClick()
        assertToolbarInside(900.dp)
    }

    @Test
    fun `selecting a row does not move the toolbar`() {
        show(snapshot(1, DOWNLOADING), width = 900.dp)
        val before = rule.onNodeWithTag("query").getUnclippedBoundsInRoot()
        row(1).performClick()
        assertEquals(before, rule.onNodeWithTag("query").getUnclippedBoundsInRoot())
    }

    @Test
    fun `the title is one item`() {
        show(snapshot(1, DOWNLOADING))
        rule.onNodeWithTag("toolbar-title").assertTextContains("anydm", substring = true)
    }

    @Test
    fun `the full source and name are available beyond the ellipsis`() {
        val url = "https://a.example/" + "x".repeat(200) + ".iso"
        val job = snapshot(1, DOWNLOADING, name = "n".repeat(200))
        show(job.copy(mirrors = listOf(Mirror(MirrorId(1), DownloadId(1), url, 0))))
        rule.onNodeWithTag("source-1").assertContentDescriptionEquals(url)
        rule.onNodeWithTag("name-1").assertContentDescriptionEquals("n".repeat(200))
    }

    @Test
    fun `the source line is hidden when blank or the same as the name`() {
        val same = snapshot(1, DOWNLOADING, name = "big.iso")
        val blank = snapshot(2, DOWNLOADING)
        show(same, blank.copy(download = blank.download.copy(fileName = "x", sourceUrl = " ")))
        rule.onNodeWithTag("source-1").assertDoesNotExist()
        rule.onNodeWithTag("source-2").assertDoesNotExist()
    }

    @Test
    fun `the card list still shows its cards beside the open detail pane`() {
        val job = snapshot(1, DOWNLOADING, name = "big.iso")
        show(job.copy(mirrors = listOf(Mirror(MirrorId(1), DownloadId(1), "https://a.example/big.iso", 0))), width = 900.dp)
        row(1).performClick()
        rule.onNodeWithTag("detail").assertIsDisplayed()
        rule.onNodeWithTag("source-1").assertIsDisplayed()
        rule.onNodeWithTag("status-1").assertIsDisplayed()
    }

    private fun heightOf(tag: String) = rule.onNodeWithTag(tag).getUnclippedBoundsInRoot().let { it.bottom - it.top }

    private fun dragHandle(dy: Float) =
        rule.onNodeWithTag("detail-handle").performMouseInput {
            moveTo(center)
            press()
            moveBy(Offset(0f, dy))
            release()
        }

    @Test
    fun `the detail panel sits below the list across the full width`() {
        show(snapshot(1, DOWNLOADING), snapshot(2, QUEUED), width = 900.dp)
        row(1).performClick()
        val list = rule.onNodeWithTag("list").getUnclippedBoundsInRoot()
        val detail = rule.onNodeWithTag("detail").getUnclippedBoundsInRoot()
        assertTrue(detail.top >= list.bottom - 1.dp, "detail $detail should be below list $list")
        assertTrue(kotlin.math.abs(((detail.right - detail.left) - (list.right - list.left)).value) < 2f, "detail $detail vs list $list")
        assertEquals(280.dp, detail.bottom - detail.top)
    }

    @Test
    fun `dragging the handle up makes the panel taller and down shorter`() {
        show(snapshot(1, DOWNLOADING), width = 900.dp)
        row(1).performClick()
        dragHandle(-60f)
        assertTrue(kotlin.math.abs((heightOf("detail") - 340.dp).value) < 4f, "got ${heightOf("detail")}")
        dragHandle(100f)
        assertTrue(kotlin.math.abs((heightOf("detail") - 240.dp).value) < 4f, "got ${heightOf("detail")}")
    }

    @Test
    fun `the panel never gets smaller than its minimum or leaves the list too little room`() {
        show(snapshot(1, DOWNLOADING), width = 900.dp)
        row(1).performClick()
        dragHandle(2000f)
        assertEquals(160.dp, heightOf("detail"))
        dragHandle(-4000f)
        assertTrue(heightOf("list") >= 239.dp, "list ${heightOf("list")}")
        assertTrue(heightOf("detail") > 280.dp)
    }

    @Test
    fun `the panel keeps its height when another row is selected`() {
        show(snapshot(1, DOWNLOADING), snapshot(2, QUEUED), width = 900.dp)
        row(1).performClick()
        dragHandle(-60f)
        val resized = heightOf("detail")
        row(2).performClick()
        rule.onNodeWithTag("detail").assertIsDisplayed()
        assertEquals(resized, heightOf("detail"))
    }

    @Test
    fun `the panel keeps its height across a visit to settings`() {
        show(snapshot(1, DOWNLOADING), width = 900.dp)
        row(1).performClick()
        dragHandle(-60f)
        val resized = heightOf("detail")
        model.navigate(Route.Settings)
        rule.waitForIdle()
        model.navigate(Route.Downloads(Filter.All))
        rule.waitForIdle()
        rule.onNodeWithTag("detail").assertIsDisplayed()
        assertEquals(resized, heightOf("detail"))
    }

    @Test
    fun `the handle is described for assistive tools`() {
        show(snapshot(1, DOWNLOADING), width = 900.dp)
        row(1).performClick()
        rule.onNodeWithTag("detail-handle").assertContentDescriptionEquals("Resize detail panel")
    }

    @Test
    fun `the panel closes unless exactly one row is selected`() {
        show(snapshot(1, DOWNLOADING), snapshot(2, QUEUED), width = 900.dp)
        row(1).performClick()
        rule.onNodeWithTag("detail-handle").assertIsDisplayed()
        row(2).performKeyInput { keyDown(Key.ShiftLeft) }
        row(2).performMouseInput { click() }
        row(2).performKeyInput { keyUp(Key.ShiftLeft) }
        assertEquals(setOf(DownloadId(1), DownloadId(2)), model.state.value.selection)
        rule.onNodeWithTag("detail").assertDoesNotExist()
        rule.onNodeWithTag("detail-handle").assertDoesNotExist()
    }

    @Test
    fun `the filter field narrows the list and keeps its keys`() {
        show(snapshot(1, QUEUED, name = "Ubuntu.iso"), snapshot(2, DOWNLOADING, name = "song.mp3"))
        rule.onNodeWithTag("query").performTextInput("iso ")
        row(2).assertDoesNotExist()
        row(1).assertIsDisplayed()
        // The space went into the field, not to pause/resume.
        assertEquals(emptyList(), source.calls)
        rule.onNodeWithTag("query").performTextReplacement("nothing")
        rule.onNodeWithTag("no-match").assertIsDisplayed()
    }

    @Test
    fun `a collection expands to its items`() {
        show(
            snapshot(10, DOWNLOADING, name = "list", collection = CollectionKind.PLAYLIST, items = ItemCounts(2, 0, 1, 0, 1, 0)),
            snapshot(11, COMPLETED, name = "one", parent = 10, playlistIndex = 0),
            snapshot(12, DOWNLOADING, name = "two", parent = 10, playlistIndex = 1),
        )
        rule.onNodeWithTag("items-10").assertTextEquals("1/2 done")
        row(11).assertDoesNotExist()
        rule.onNodeWithTag("expand-10").performClick()
        row(11).assertIsDisplayed()
        row(12).assertIsDisplayed()
    }

    @Test
    fun `keys move, pause and remove`() {
        show(snapshot(1, DOWNLOADING), snapshot(2, DOWNLOADING))
        rule.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        rule.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        assertEquals(setOf(DownloadId(2)), model.state.value.selection)
        rule.onRoot().performKeyInput { pressKey(Key.Spacebar) }
        assertEquals(listOf("pause 2"), source.calls)
        rule.onRoot().performKeyInput { withKeyDown(command) { pressKey(Key.A) } }
        assertEquals(setOf(DownloadId(1), DownloadId(2)), model.state.value.selection)
        rule.onRoot().performKeyInput { pressKey(Key.Delete) }
        rule.onNodeWithTag("remove-dialog").assertIsDisplayed()
    }

    @Test
    fun `the detail pane shows the selected job's tabs`() {
        val file = FileSnapshot(File(FileId(1), DownloadId(1), 0, "big.iso", size = 100), emptyList())
        show(snapshot(1, DOWNLOADING, name = "big.iso", files = listOf(file)), snapshot(2, QUEUED))
        rule.onNodeWithTag("detail").assertDoesNotExist()
        row(1).performClick()
        rule.onNodeWithTag("detail-name").assertTextEquals("big.iso")
        rule.onNodeWithTag("detail-files").assertIsDisplayed()
        rule.onNodeWithTag("tab-attempts").performClick()
        rule.onNodeWithTag("detail-attempts").assertIsDisplayed()
        rule.onNodeWithTag("tab-peers").assertDoesNotExist()
    }

    @Test
    fun `the proxy tab shows the job's proxy and changes it`() {
        source.proxyList = listOf(ProxyEntry(ProxyId(7), "Office", ProxyType.SOCKS5, "p.example", 1080, null, emptyList()))
        val job = snapshot(1, DOWNLOADING, name = "big.iso")
        show(job.copy(download = job.download.copy(proxy = ProxyChoice.Direct)))
        row(1).performClick()
        rule.onNodeWithTag("tab-proxy").performClick()
        rule.onNodeWithTag("detail-proxy").assertIsDisplayed()
        rule.onNodeWithTag("detail-proxy-direct").assertIsSelected()
        rule.onNodeWithTag("detail-proxy-7").performClick()
        rule.waitForIdle()
        assertEquals("setDownloadProxy 1 ${ProxyChoice.Named(ProxyId(7))}", source.calls.last())
    }

    @Test
    fun `a refused proxy change is reported`() {
        source.results["setDownloadProxy 1 ${ProxyChoice.System}"] = CommandResult.Invalid("no such proxy")
        show(snapshot(1, DOWNLOADING, name = "big.iso"))
        row(1).performClick()
        rule.onNodeWithTag("tab-proxy").performClick()
        rule.onNodeWithTag("detail-proxy-inherit").assertIsSelected()
        rule.onNodeWithTag("detail-proxy-system").performClick()
        rule.waitForIdle()
        assertEquals(
            1,
            model.state.value.banners
                .filterIsInstance<Banner.Error>()
                .size,
        )
    }

    @Test
    fun `a torrent has a peers tab`() {
        val summary =
            TorrentSummary(
                peers = 7,
                seeds = 3,
                availability = 1.0,
                downloadRate = 0,
                uploadRate = 0,
                uploaded = 0,
                ratio = 0.5,
                finishedSeconds = 0,
            )
        show(snapshot(1, SEEDING, name = "t", torrent = summary))
        row(1).performClick()
        rule.onNodeWithTag("tab-peers").performClick()
        rule.onNodeWithTag("detail-peers").assertIsDisplayed()
        rule.onNodeWithText("Peers 7 · Seeds 3").assertIsDisplayed()
        rule.onNodeWithTag("peers-unavailable").assertIsDisplayed()
        assertEquals("torrentDetail 1", source.calls.first())
    }

    @Test
    fun `list shortcuts do nothing outside the downloads section`() {
        show(snapshot(1, FAILED))
        row(1).performClick()
        model.navigate(Route.Settings)
        rule.waitForIdle()
        rule.onRoot().performKeyInput { withKeyDown(command) { pressKey(Key.R) } }
        assertEquals(emptyList(), source.calls)
    }
}
