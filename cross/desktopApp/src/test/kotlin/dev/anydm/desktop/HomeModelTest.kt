package dev.anydm.desktop

import dev.anydm.engine.torrent.PieceSet
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
            networks = listOf(Network("w", "Wi-Fi", NetworkKind.WiFi, true, 10)),
            freeBytes = 1000,
        )

    @Test
    fun populatedSampleSumsToExactly14MBps() {
        val nets = populatedSample().networks
        assertEquals(listOf("Wi-Fi", "USB Tethering"), nets.map { it.name })
        assertEquals(14L * 1024 * 1024, totalSpeed(nets))
        assertEquals("7.1 MB/s", formatSpeed(nets[0].bytesPerSec, SpeedUnit.MBps))
        assertEquals("6.9 MB/s", formatSpeed(nets[1].bytesPerSec, SpeedUnit.MBps))
    }

    @Test
    fun emptySampleHasOneIdleWifi() {
        val nets = emptySample().networks
        assertEquals(1, nets.size)
        assertEquals(NetworkKind.WiFi, nets[0].kind)
        assertEquals(0L, nets[0].bytesPerSec)
    }

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

    @Test
    fun failedRowsStayInTheDownloadingFilterAndNotInFinished() {
        val failedRow = DownloadItem("9", "a.zip", 100, 10, 0, DownloadStatus.Failed, error = "HTTP 404")
        val state = populatedSample().copy(downloads = listOf(failedRow))
        assertEquals(listOf(failedRow), filtered(state, Filter.Downloading))
        assertEquals(emptyList(), filtered(state, Filter.Finished))
    }

    @Test
    fun unknownSizeShowsNoProgress() {
        val row = DownloadItem("9", "a.zip", UNKNOWN_SIZE, 10, 5, DownloadStatus.Downloading)
        assertEquals(0, percent(row))
        assertEquals(0f, progress(row))
    }

    private val magnetUrl = "magnet:?xt=urn:btih:${"ab".repeat(20)}&tr=udp%3A%2F%2Ft.example%3A1"
    private val magnetRow =
        DownloadItem(
            "9",
            "dn",
            UNKNOWN_SIZE,
            0,
            0,
            DownloadStatus.Downloading,
            DownloadKind.Torrent,
            url = magnetUrl,
            infoHash = "ab".repeat(20),
        )

    @Test
    fun aMagnetRowWithNoFilesYetIsAwaitingItsMetadataAndOneWithFilesIsNot() {
        assertEquals(true, magnetRow.awaitingMetadata)
        assertEquals(false, magnetRow.copy(files = listOf(SampleFile("a", 1))).awaitingMetadata)
        assertEquals(true, magnetRow.copy(url = magnetUrl.uppercase().replace("%3A", "%3a")).awaitingMetadata, "any case of the scheme")
    }

    // review M1: a row that has pieces or is Finished has its files on disk, whatever the row's file list says
    @Test
    fun aMagnetRowThatHasPiecesOrIsFinishedIsNotAwaitingItsMetadata() {
        assertEquals(false, magnetRow.copy(pieces = PieceSet.of(8, listOf(0))).awaitingMetadata)
        assertEquals(false, magnetRow.copy(status = DownloadStatus.Finished).awaitingMetadata)
    }

    // review m2: a magnet row has a url too, but it makes no file named after it next to an http download's
    @Test
    fun onlyDirectDownloadsReserveNamesAgainstANewHttpDownload() {
        val direct = item("1", DownloadStatus.Downloading, name = "a.zip").copy(url = "https://x.test/a.zip", folder = "/dl")
        val sameFolderMagnet = magnetRow.copy(name = "movie", folder = "/dl")
        assertEquals(setOf("a.zip", "a.zip.part"), takenNames(listOf(direct, sameFolderMagnet), "/dl", "/home"))
        assertEquals(emptySet(), takenNames(listOf(sameFolderMagnet), "/dl", "/home"))
    }

    @Test
    fun noOtherRowAwaitsMetadata() {
        assertEquals(false, magnetRow.copy(infoHash = null).awaitingMetadata)
        assertEquals(false, magnetRow.copy(url = null).awaitingMetadata, "a .torrent row has its copy from the start")
        assertEquals(false, magnetRow.copy(kind = DownloadKind.Direct, url = "https://x.test/a").awaitingMetadata)
    }

    @Test
    fun aMagnetRowWaitingForMetadataListsNoFilesNotTheSintelSample() {
        assertEquals(emptyList(), magnetRow.fileList())
        assertEquals(sintelFiles, DownloadItem("1", "Sintel", 1, 0, 0, DownloadStatus.Paused, DownloadKind.Torrent).fileList())
    }
}
