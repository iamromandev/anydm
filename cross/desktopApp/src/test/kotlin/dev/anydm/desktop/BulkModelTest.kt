package dev.anydm.desktop

import androidx.compose.ui.unit.IntOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BulkModelTest {
    private fun item(
        id: String,
        status: DownloadStatus,
    ) = DownloadItem(id, "f$id.zip", 100, if (status == DownloadStatus.Finished) 100 else 10, 0, status)

    private val down = item("1", DownloadStatus.Downloading)
    private val paused = item("2", DownloadStatus.Paused)
    private val done = item("3", DownloadStatus.Finished)
    private val all = listOf(down, paused, done)

    @Test
    fun targetsOfKeepsListOrderAndSkipsUnknownIds() {
        assertEquals(listOf(down, done), targetsOf(all, setOf("3", "1", "9")))
    }

    @Test
    fun canPauseNeedsADownloadingOrQueuedRow() {
        assertTrue(canPause(listOf(down, done)))
        assertTrue(canPause(listOf(item("4", DownloadStatus.Queued), done)))
        assertFalse(canPause(listOf(paused, done)))
        assertFalse(canPause(emptyList()))
    }

    @Test
    fun canResumeNeedsAPausedRow() {
        assertTrue(canResume(listOf(paused, done)))
        assertFalse(canResume(listOf(down, done)))
        assertFalse(canResume(emptyList()))
    }

    @Test
    fun allFinishedNeedsNonEmptyAndEveryRowFinished() {
        assertTrue(allFinished(listOf(done)))
        assertFalse(allFinished(listOf(done, down)))
        assertFalse(allFinished(emptyList()))
    }

    @Test
    fun rightClickOnASelectedRowTargetsTheWholeSelection() {
        assertEquals(setOf("1", "2"), targetsFor(setOf("1", "2"), "2"))
    }

    @Test
    fun rightClickOnAnUnselectedRowTargetsOnlyThatRow() {
        assertEquals(setOf("3"), targetsFor(setOf("1", "2"), "3"))
    }

    @Test
    fun pruneDropsIdsThatNoLongerExist() {
        assertEquals(setOf("1"), prune(setOf("1", "9"), all))
    }

    @Test
    fun menuForFinishedRowsOffersOpenFolderRemove() {
        assertEquals(listOf(MenuAction.Open, MenuAction.ShowInFolder, MenuAction.Remove), menuActions(listOf(done), emptyList()))
    }

    @Test
    fun menuForMixedRowsHidesOpenAndFolder() {
        assertEquals(listOf(MenuAction.Pause, MenuAction.Remove), menuActions(listOf(down, done), emptyList()))
        assertEquals(listOf(MenuAction.Pause, MenuAction.Resume, MenuAction.Remove), menuActions(listOf(down, paused), emptyList()))
        assertEquals(listOf(MenuAction.Resume, MenuAction.Remove), menuActions(listOf(paused), emptyList()))
    }

    private val q1 = item("q1", DownloadStatus.Queued)
    private val q2 = item("q2", DownloadStatus.Queued)
    private val q3 = item("q3", DownloadStatus.Queued)
    private val queue = listOf("q1", "q2", "q3")

    private val move = listOf(MenuAction.MoveToTop, MenuAction.MoveUp, MenuAction.MoveDown)

    @Test
    fun menuForTheFirstQueuedRowHidesMovesUp() {
        assertEquals(
            listOf(MenuAction.Pause, MenuAction.MoveDown, MenuAction.StartNow, MenuAction.Remove),
            menuActions(listOf(q1), queue),
        )
    }

    @Test
    fun menuForTheLastQueuedRowHidesMoveDown() {
        assertEquals(
            listOf(MenuAction.Pause, MenuAction.MoveToTop, MenuAction.MoveUp, MenuAction.StartNow, MenuAction.Remove),
            menuActions(listOf(q3), queue),
        )
    }

    @Test
    fun menuForAMiddleQueuedRowOffersAllMoves() {
        assertEquals(
            listOf(MenuAction.Pause) + move + listOf(MenuAction.StartNow, MenuAction.Remove),
            menuActions(listOf(q2), queue),
        )
    }

    @Test
    fun menuForTheWholeQueueHidesMoves() {
        val actions = menuActions(listOf(q1, q2, q3), queue)
        assertTrue(move.none { it in actions })
        assertTrue(MenuAction.StartNow in actions)
    }

    @Test
    fun menuForAMixedSelectionOffersStartNowAndMovesThatChangeTheQueue() {
        val actions = menuActions(listOf(down, q2), queue)
        assertEquals(listOf(MenuAction.Pause) + move + listOf(MenuAction.StartNow, MenuAction.Remove), actions)
    }

    @Test
    fun menuWithoutQueuedTargetsHasNoQueueActions() {
        val actions = menuActions(listOf(down, paused), queue)
        assertTrue(move.none { it in actions })
        assertFalse(MenuAction.StartNow in actions)
    }

    @Test
    fun menuForNoTargetsIsEmpty() {
        assertEquals(emptyList(), menuActions(emptyList(), emptyList()))
    }

    @Test
    fun labelsUseSingularForOne() {
        assertEquals("1 selected", selectionLabel(1))
        assertEquals("3 selected", selectionLabel(3))
        assertEquals("Remove 1 download?", removeTitle(1))
        assertEquals("Remove 2 downloads?", removeTitle(2))
    }

    @Test
    fun clampMenuShiftsTheCardInsideTheWindow() {
        assertEquals(IntOffset(10, 20), clampMenu(10, 20, 200, 100, 900, 600))
        assertEquals(IntOffset(700, 500), clampMenu(890, 590, 200, 100, 900, 600))
        assertEquals(IntOffset(0, 0), clampMenu(5, 5, 1000, 700, 900, 600))
    }
}
