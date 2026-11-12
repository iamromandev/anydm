package dev.anydm.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DetailModelTest {
    private val mib = 1024L * 1024
    private val wifi = Network("wifi", "Wi-Fi", NetworkKind.WiFi, true, 0)
    private val usb = Network("usb", "USB Tethering", NetworkKind.UsbTethering, true, 0)
    private val third = Network("lan", "Office LAN", NetworkKind.WiFi, true, 0)
    private val two = listOf(wifi, usb)
    private val project = DownloadItem("1", "Project.zip", 200 * mib, 176 * mib, 4 * mib, DownloadStatus.Downloading)
    private val report = DownloadItem("2", "report.pdf", 5 * mib, 5 * mib, 0, DownloadStatus.Finished)
    private val sintel = detailSample().downloads.first { it.id == "3" }

    @Test
    fun rowsCarryTheNetworksColourChoice() {
        val d = buildDetail(project, listOf(wifi.copy(color = NetworkColor.Pink), usb))
        assertEquals(listOf<NetworkColor?>(NetworkColor.Pink, null), d.rows.map { it.color })
    }

    @Test
    fun sameInputsBuildEqualDetails() {
        assertEquals(buildDetail(project, two), buildDetail(project, two))
        assertEquals(buildDetail(sintel, two), buildDetail(sintel, two))
    }

    @Test
    fun pausedItemHasZeroSpeedEverywhereAndFlatRecentSeries() {
        val d = buildDetail(project.copy(status = DownloadStatus.Paused), two)
        assertEquals(0L, d.total)
        assertTrue(d.rows.all { it.speed == 0L })
        assertTrue(d.series.all { s -> s.takeLast(10).all { it == 0L } })
        assertEquals(0, d.cells.count { it.state == CellState.InFlight })
    }

    @Test
    fun queuedItemHasZeroSpeedsSeriesAverageAndPeak() {
        val d = buildDetail(project.copy(status = DownloadStatus.Queued), two)
        assertEquals(0L, d.total)
        assertEquals(0L, d.avg)
        assertEquals(0L, d.peak)
        assertEquals(0, d.retries)
        assertTrue(d.rows.all { it.speed == 0L })
        assertTrue(d.series.all { s -> s.all { it == 0L } })
        assertEquals(0, d.cells.count { it.state == CellState.InFlight })
    }

    @Test
    fun totalSpeedSplitsEvenlyAcrossEnabledNetworks() {
        val d = buildDetail(project, two)
        assertEquals(listOf(2 * mib, 2 * mib), d.rows.map { it.speed })
        assertEquals(4 * mib, d.total)
        assertEquals(listOf(50, 50), d.rows.map { it.share })
        assertEquals(2.0, d.aloneFactor)
        assertEquals(listOf(88 * mib, 88 * mib), d.rows.map { it.downloaded })
    }

    @Test
    fun sharesSumToOneHundredWithThreeNetworks() {
        val item = project.copy(doneBytes = 100 * mib + 1)
        val d = buildDetail(item, listOf(wifi, usb, third))
        assertEquals(100, d.rows.sumOf { it.share })
        assertEquals(project.bytesPerSec, d.total)
        assertEquals(item.doneBytes, d.rows.sumOf { it.downloaded })
    }

    @Test
    fun switchedOffNetworkGetsNoSpeedAndNoShare() {
        val d = buildDetail(project, two, setOf("usb"))
        val u = d.rows.first { it.id == "usb" }
        assertFalse(u.on)
        assertEquals(0L, u.speed)
        assertEquals(0, u.share)
        assertEquals(100, d.rows.first { it.id == "wifi" }.share)
        assertEquals(4 * mib, d.total)
        assertNull(d.aloneFactor)
    }

    @Test
    fun networkDisabledInTheMenuIsOffInTheTable() {
        val d = buildDetail(project, listOf(wifi, usb.copy(enabled = false)))
        assertFalse(d.rows[1].on)
        assertEquals(2, d.rows.size)
        assertEquals(4 * mib, d.total)
    }

    @Test
    fun onlyMenuDisabledRowsAreFlaggedDisabled() {
        val menu = buildDetail(project, listOf(wifi, usb.copy(enabled = false)))
        assertEquals(listOf(false, true), menu.rows.map { it.disabled })
        val local = buildDetail(project, two, setOf("usb"))
        assertEquals(listOf(false, false), local.rows.map { it.disabled })
    }

    @Test
    fun toggleOffNeverTurnsOffTheLastEnabledNetwork() {
        val off = toggleOff(emptySet(), two, "usb")
        assertEquals(setOf("usb"), off)
        assertEquals(off, toggleOff(off, two, "wifi"))
        assertEquals(emptySet(), toggleOff(off, two, "usb"))
    }

    @Test
    fun toggleOffIgnoresNetworksDisabledInTheMenu() {
        assertEquals(emptySet(), toggleOff(emptySet(), listOf(wifi, usb.copy(enabled = false)), "usb"))
    }

    @Test
    fun mapShowsForTwoNetworksOrTorrentsOnly() {
        assertTrue(showMap(buildDetail(project, two)))
        assertFalse(showMap(buildDetail(project, two, setOf("usb"))))
        assertTrue(showMap(buildDetail(sintel, listOf(wifi))))
        assertFalse(showMap(buildDetail(project.copy(totalBytes = 0, doneBytes = 0), two)))
    }

    @Test
    fun chunkCellsFollowTheProgress() {
        val d = buildDetail(project, two)
        assertEquals(67, d.cells.size)
        assertEquals(58, d.cells.count { it.state == CellState.Done })
        assertEquals(4, d.cells.count { it.state == CellState.InFlight })
        assertEquals(5, d.cells.count { it.state == CellState.Pending })
        assertEquals(3 * mib, d.cellBytes)
        assertTrue(d.cells.filter { it.state == CellState.Done }.all { it.network in 0..1 })
        assertTrue(d.cells.filter { it.state != CellState.Done }.all { it.network == -1 })
        assertEquals(
            setOf(0, 1),
            d.cells
                .filter { it.state == CellState.Done }
                .map { it.network }
                .toSet(),
        )
    }

    @Test
    fun largeDirectFilesCapAtOneHundredTwentyEightChunks() {
        val d = buildDetail(project.copy(totalBytes = 400 * mib, doneBytes = 275 * mib), two)
        assertEquals(128, d.cells.size)
        assertEquals((400 * mib + 127) / 128, d.cellBytes)
    }

    @Test
    fun torrentUsesPiecesAndPeers() {
        val d = buildDetail(sintel, two)
        assertEquals(987, d.cells.size)
        assertEquals(128L * 1024, d.cellBytes)
        assertEquals(9, d.peers)
        assertEquals(9, d.rows.sumOf { it.streams })
        assertEquals(0, d.retries)
        assertTrue(d.rows.all { it.up == 0L && it.uploaded == 0L })
    }

    @Test
    fun directRowsHaveFourStreamsEach() {
        assertEquals(listOf(4, 4), buildDetail(project, two).rows.map { it.streams })
        assertEquals(0, buildDetail(project, two).peers)
    }

    @Test
    fun retriesNeedTwoNetworksAndAreStable() {
        val d = buildDetail(project, two)
        assertEquals("1".hashCode() % 40, d.retries)
        assertEquals(0, buildDetail(project, two, setOf("usb")).retries)
    }

    @Test
    fun seriesHasSixtyPointsRampsUpAndEndsAtTheSpeed() {
        val d = buildDetail(project, two)
        assertTrue(d.series.all { it.size == 60 })
        assertEquals(0L, d.series[0][0])
        assertEquals(listOf(2 * mib, 2 * mib), d.series.map { it.last() })
        val totals = (0 until 60).map { i -> d.series.sumOf { it[i] } }
        assertEquals(totals.max(), d.peak)
        assertEquals(totals.sum() / 60, d.avg)
        assertTrue(d.avg <= d.peak)
        val wobble = d.series[0].subList(5, 59)
        assertTrue(wobble.all { it in (1.9 * mib).toLong()..(2.1 * mib).toLong() + 1 })
    }

    @Test
    fun finishedItemHasClockAverageAndPeak() {
        val d = buildDetail(report, two)
        assertEquals(2L, d.elapsedSec)
        assertEquals(5 * mib / 2, d.avg)
        assertEquals((d.avg * 1.4).toLong(), d.peak)
        assertEquals(0L, d.total)
        assertEquals(100, d.rows.sumOf { it.share })
        assertEquals(5 * mib, d.rows.sumOf { it.downloaded })
        assertEquals(d.peak, (0 until 60).maxOf { i -> d.series.sumOf { it[i] } })
        assertEquals(0, d.cells.count { it.state != CellState.Done })
    }

    @Test
    fun zeroByteItemHasNoCellsAndNoElapsedDivision() {
        val d = buildDetail(report.copy(totalBytes = 0, doneBytes = 0), two)
        assertTrue(d.cells.isEmpty())
        assertEquals(1L, d.elapsedSec)
        assertEquals(0L, d.avg)
    }

    @Test
    fun niceTicksUseRoundNumbersInTheDisplayUnit() {
        assertEquals(listOf(2 * mib, 4 * mib, 6 * mib), niceTicks((4.7 * mib).toLong(), SpeedUnit.MBps))
        assertEquals(listOf(20 * mib / 8, 40 * mib / 8, 60 * mib / 8), niceTicks((4.7 * mib).toLong(), SpeedUnit.Mbps))
        assertEquals(3, niceTicks(0, SpeedUnit.MBps).size)
        assertTrue(niceTicks(0, SpeedUnit.MBps).all { it > 0 })
    }

    @Test
    fun formatClockPadsSeconds() {
        assertEquals("0:02", formatClock(2))
        assertEquals("2:05", formatClock(125))
        assertEquals("61:00", formatClock(3660))
    }

    @Test
    fun graphDescriptionNamesThePeakInTheUnit() {
        val d = buildDetail(project, two)
        assertEquals("Throughput, last 60 seconds, peak ${formatSpeed(d.peak, SpeedUnit.MBps)}", graphDescription(d, SpeedUnit.MBps))
    }

    @Test
    fun finishedGraphIsDescribedAsTheWholeDownload() {
        val d = buildDetail(report, two)
        assertEquals("Speed over the download, peak ${formatSpeed(d.peak, SpeedUnit.MBps)}", graphDescription(d, SpeedUnit.MBps))
    }

    @Test
    fun finishedTorrentSharesSumToOneHundredAndAllPiecesAreDone() {
        val done = sintel.copy(status = DownloadStatus.Finished, doneBytes = sintel.totalBytes, bytesPerSec = 0)
        val d = buildDetail(done, two)
        assertEquals(100, d.rows.sumOf { it.share })
        assertEquals(done.totalBytes, d.rows.sumOf { it.downloaded })
        assertEquals(987, d.cells.count { it.state == CellState.Done })
        assertEquals(9, d.peers)
    }

    @Test
    fun detailSampleAddsOnlyTheTorrentRow() {
        assertEquals(listOf("1", "2"), populatedSample().downloads.map { it.id })
        val extra = detailSample().downloads.last()
        assertEquals("Sintel", extra.name)
        assertEquals(DownloadKind.Torrent, extra.kind)
        assertEquals(sintelFiles.sumOf { it.bytes }, extra.totalBytes)
        assertEquals(74, percent(extra))
    }

    @Test
    fun pausedItemKeepsHistoryAverageAndPeakButNoTotal() {
        val d = buildDetail(project.copy(status = DownloadStatus.Paused), two)
        assertEquals(0L, d.total)
        assertTrue(d.avg > 0)
        assertTrue(d.peak >= d.avg)
        assertEquals("Throughput, last 60 seconds, peak ${formatSpeed(d.peak, SpeedUnit.MBps)}", graphDescription(d, SpeedUnit.MBps))
        assertTrue(d.peak > 0)
    }

    @Test
    fun finishedPeakIsOnePointFourTimesTheAverage() {
        val big = report.copy(totalBytes = 123 * mib, doneBytes = 123 * mib)
        for (item in listOf(report, big)) {
            val d = buildDetail(item, two)
            assertEquals((d.avg * 1.4).toLong(), d.peak)
            assertTrue(d.peak > d.avg)
        }
    }

    @Test
    fun allNetworksDisabledInTheMenuBuildAnEmptyButSaneDetail() {
        val none = two.map { it.copy(enabled = false) }
        for (item in listOf(project, report, sintel, project.copy(status = DownloadStatus.Paused))) {
            val d = buildDetail(item, none)
            assertEquals(0L, d.total)
            assertTrue(d.rows.all { !it.on && it.disabled && it.speed == 0L && it.share == 0 && it.streams == 0 })
            assertTrue(d.series.all { s -> s.all { it == 0L } } || item.status == DownloadStatus.Finished)
            assertTrue(d.cells.none { it.network >= 0 })
            assertNull(d.aloneFactor)
            assertEquals(0, d.peers)
        }
    }

    @Test
    fun torrentsAreBadgedTorAndOthersByExtension() {
        assertEquals("TOR", badgeFor(sintel))
        assertEquals("ZIP", badgeFor(project))
        assertEquals("FILE", badgeFor(project.copy(name = "download")))
    }

    @Test
    fun detailCarriesTheFolderItIsGiven() {
        assertEquals("~/Downloads", buildDetail(project, two).folder)
        assertEquals("~/Movies", buildDetail(project, two, folder = "~/Movies").folder)
        assertEquals("~/Movies", buildDetail(report, two, folder = "~/Movies").folder)
    }

    @Test
    fun folderLabelUsesTheItemsFolderElseTheDefaultUnderHome() {
        assertEquals("~/Downloads", folderLabel(project, "/Users/x"))
        assertEquals("~/Movies", folderLabel(project.copy(folder = "/Users/x/Movies"), "/Users/x"))
        assertEquals("/Volumes/Media", folderLabel(project.copy(folder = "/Volumes/Media"), "/Users/x"))
    }
}
