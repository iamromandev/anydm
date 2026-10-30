package dev.anydm.desktop

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
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
import dev.anydm.engine.FileSnapshot
import dev.anydm.engine.core.Attempt
import dev.anydm.engine.core.AttemptId
import dev.anydm.engine.core.CollectionKind
import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadStatus.COMPLETED
import dev.anydm.engine.core.DownloadStatus.DOWNLOADING
import dev.anydm.engine.core.DownloadStatus.FAILED
import dev.anydm.engine.core.DownloadStatus.QUEUED
import dev.anydm.engine.core.DownloadStatus.SEEDING
import dev.anydm.engine.core.ErrorClass
import dev.anydm.engine.core.File
import dev.anydm.engine.core.FileId
import dev.anydm.engine.core.ItemCounts
import dev.anydm.engine.core.Mirror
import dev.anydm.engine.core.MirrorId
import dev.anydm.engine.core.Step
import dev.anydm.engine.core.StepFailure
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

    private fun show(vararg snapshots: dev.anydm.engine.DownloadSnapshot) {
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
        rule.setContent { AnydmTheme(dark = false) { Shell(model) } }
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
        rule.onNodeWithTag("cmd-pause").assertDoesNotExist()
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
    fun `sorting by a column reorders the rows`() {
        show(snapshot(1, QUEUED, name = "b", size = 10), snapshot(2, QUEUED, name = "a", size = 30))
        rule.onNodeWithTag("sort-size").performClick()
        rule.onNodeWithTag("sort-size").performClick()
        assertEquals(
            listOf(2L, 1L),
            model.state.value.rows
                .map { it.id.value },
        )
        rule.onNodeWithTag("sort-name").assertIsDisplayed()
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
