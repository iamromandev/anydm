package dev.anydm.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

class HomeModelTest {
    private fun item(
        id: String,
        status: DownloadStatus,
        name: String = "$id.zip",
    ) = DownloadItem(id, name, totalBytes = 100, doneBytes = 50, bytesPerSec = 10, status = status)

    private val state =
        HomeState(
            downloads =
                listOf(
                    item("a", DownloadStatus.Downloading),
                    item("b", DownloadStatus.Paused),
                    item("c", DownloadStatus.Finished),
                ),
            network = NetworkInfo(1, 10),
            freeBytes = 1000,
        )

    @Test
    fun allKeepsEverything() {
        assertEquals(listOf("a", "b", "c"), filtered(state, Filter.All).map { it.id })
    }

    @Test
    fun downloadingFilterKeepsActiveAndPausedRows() {
        assertEquals(listOf("a", "b"), filtered(state, Filter.Downloading).map { it.id })
    }

    @Test
    fun finishedFilterKeepsOnlyFinishedRows() {
        assertEquals(listOf("c"), filtered(state, Filter.Finished).map { it.id })
    }

    @Test
    fun badgeIsTheUpperCasedExtension() {
        assertEquals("ZIP", badgeFor("Project.zip"))
        assertEquals("PDF", badgeFor("a.b.pdf"))
    }

    @Test
    fun badgeIsCutToFourCharacters() {
        assertEquals("TORR", badgeFor("x.torrent"))
    }

    @Test
    fun badgeFallsBackToFile() {
        assertEquals("FILE", badgeFor("download"))
        assertEquals("FILE", badgeFor("trailingdot."))
        assertEquals("FILE", badgeFor(".hidden"))
    }

    @Test
    fun progressIsClampedToZeroAndOne() {
        assertEquals(0.5f, progress(item("a", DownloadStatus.Downloading)))
        assertEquals(1f, progress(item("a", DownloadStatus.Downloading).copy(doneBytes = 500)))
        assertEquals(0f, progress(item("a", DownloadStatus.Downloading).copy(doneBytes = -5)))
        assertEquals(0f, progress(item("a", DownloadStatus.Downloading).copy(totalBytes = 0)))
    }

    @Test
    fun percentIsRoundedDownWithIntegerMath() {
        val base = item("a", DownloadStatus.Downloading)
        assertEquals(53, percent(base.copy(doneBytes = 53, totalBytes = 100)))
        assertEquals(53, percent(base.copy(doneBytes = 106, totalBytes = 200)))
        assertEquals(0, percent(base.copy(totalBytes = 0)))
        assertEquals(100, percent(base.copy(doneBytes = 500, totalBytes = 100)))
        assertEquals(0, percent(base.copy(doneBytes = -5, totalBytes = 100)))
    }
}
