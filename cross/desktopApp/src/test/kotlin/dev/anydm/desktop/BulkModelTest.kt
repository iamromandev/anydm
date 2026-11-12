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
    fun canPauseNeedsADownloadingRow() {
        assertTrue(canPause(listOf(down, done)))
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
    fun pauseChangesOnlySelectedDownloadingRows() {
        val out = pause(all, setOf("1", "2", "3"))
        assertEquals(listOf(DownloadStatus.Paused, DownloadStatus.Paused, DownloadStatus.Finished), out.map { it.status })
        assertEquals(done, out[2])
        assertEquals(all, pause(all, emptySet()))
    }

    @Test
    fun pauseLeavesUnselectedRowsAlone() {
        val other = down.copy(id = "4")
        assertEquals(DownloadStatus.Downloading, pause(all + other, setOf("1")).last().status)
    }

    @Test
    fun resumeChangesOnlySelectedPausedRows() {
        val out = resume(all, setOf("1", "2", "3"))
        assertEquals(listOf(DownloadStatus.Downloading, DownloadStatus.Downloading, DownloadStatus.Finished), out.map { it.status })
        assertEquals(all, resume(all, setOf("1", "3")))
    }

    @Test
    fun removeDropsOnlyTheGivenIds() {
        assertEquals(listOf(paused), remove(all, setOf("1", "3", "9")))
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
        assertEquals(listOf(MenuAction.Open, MenuAction.ShowInFolder, MenuAction.Remove), menuActions(listOf(done)))
    }

    @Test
    fun menuForMixedRowsHidesOpenAndFolder() {
        assertEquals(listOf(MenuAction.Pause, MenuAction.Remove), menuActions(listOf(down, done)))
        assertEquals(listOf(MenuAction.Pause, MenuAction.Resume, MenuAction.Remove), menuActions(listOf(down, paused)))
        assertEquals(listOf(MenuAction.Resume, MenuAction.Remove), menuActions(listOf(paused)))
    }

    @Test
    fun menuForNoTargetsIsEmpty() {
        assertEquals(emptyList(), menuActions(emptyList()))
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
