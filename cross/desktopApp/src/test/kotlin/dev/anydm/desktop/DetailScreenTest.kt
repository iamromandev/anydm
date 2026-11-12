package dev.anydm.desktop

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class DetailScreenTest {
    private fun ComposeUiTest.home(clipboard: String? = null) =
        setContent { HomeScreen(detailSample(), true, clipboardText = { clipboard }) }

    private fun ComposeUiTest.openProject() {
        home()
        onNodeWithTag("chevron-1").performClick()
    }

    private fun ComposeUiTest.text(
        tag: String,
        text: String,
    ) = onNodeWithTag(tag).assertTextEquals(text)

    private fun ComposeUiTest.escape() = onRoot().performKeyInput { pressKey(Key.Escape) }

    @Test
    fun chevronOpensTheDetailInPlaceOfTheList() =
        runComposeUiTest {
            openProject()
            onNodeWithTag("detail-screen").assertIsDisplayed()
            onNodeWithTag("detail-back").assertIsDisplayed()
            onNodeWithTag("detail-name").assertTextEquals("Project.zip")
            onNodeWithTag("row-1").assertDoesNotExist()
            onNodeWithText("DOWNLOADING 2").assertDoesNotExist()
            onNodeWithTag("filter").assertDoesNotExist()
        }

    @Test
    fun clickingTheRowTitleOpensTheDetail() =
        runComposeUiTest {
            home()
            onNodeWithText("Project.zip").performClick()
            onNodeWithTag("detail-screen").assertIsDisplayed()
        }

    @Test
    fun checkboxAndPauseButtonDoNotOpenTheDetail() =
        runComposeUiTest {
            home()
            onNodeWithTag("select-1").performClick()
            onNodeWithTag("select-1").assertIsOn()
            onNodeWithTag("pause-1").performClick()
            text("speed-1", "Paused")
            onNodeWithTag("detail-screen").assertDoesNotExist()
        }

    @Test
    fun backKeepsFilterAndSelection() =
        runComposeUiTest {
            home()
            onNodeWithTag("filter").performClick()
            onNodeWithTag("filter-downloading").performClick()
            onNodeWithTag("select-1").performClick()
            onNodeWithTag("chevron-1").performClick()
            onNodeWithTag("detail-back").performClick()
            onNodeWithTag("detail-screen").assertDoesNotExist()
            onNodeWithTag("filter").assertTextEquals("Downloading")
            onNodeWithTag("select-1").assertIsOn()
            onNodeWithTag("select-3").assertIsOff()
        }

    @Test
    fun escapeReturnsToTheListEvenAfterClickingATableCheckbox() =
        runComposeUiTest {
            openProject()
            onNodeWithTag("detail-check-usb").performClick()
            escape()
            onNodeWithTag("detail-screen").assertDoesNotExist()
            onNodeWithTag("row-1").assertIsDisplayed()
        }

    @Test
    fun pasteIsIgnoredAndNetworksChipIsAbsentWhileOpen() =
        runComposeUiTest {
            home("https://example.com/a.zip")
            onNodeWithTag("chevron-1").performClick()
            val mod = if (System.getProperty("os.name").startsWith("Mac")) Key.MetaLeft else Key.CtrlLeft
            onRoot().performKeyInput {
                keyDown(mod)
                pressKey(Key.V)
                keyUp(mod)
            }
            waitForIdle()
            onNodeWithTag("new-download-dialog").assertDoesNotExist()
            onNodeWithTag("network-chip").assertDoesNotExist()
            onNodeWithTag("new-download").assertDoesNotExist()
        }

    @Test
    fun openingClosesTheNetworksMenu() =
        runComposeUiTest {
            home()
            onNodeWithTag("network-chip").performClick()
            onNodeWithTag("chevron-1").performClick()
            onNodeWithTag("networks-menu").assertDoesNotExist()
            onNodeWithTag("detail-back").performClick()
            onNodeWithTag("networks-menu").assertDoesNotExist()
        }

    @Test
    fun footerStaysAndConvertsTheDetailUnit() =
        runComposeUiTest {
            openProject()
            onNodeWithText("78.5 GB free").assertIsDisplayed()
            onNodeWithTag("theme-toggle").assertIsDisplayed()
            text("detail-total", "4.0 MB/s")
            onNodeWithTag("unit-mbps").performClick()
            text("detail-total", "32.0 Mbps")
            text("detail-speed-wifi", "16.0 Mbps")
            onNodeWithTag("detail-tick-0").assert(hasText("Mbps", substring = true))
        }

    @Test
    fun activeHeaderShowsThreeButtons() =
        runComposeUiTest {
            openProject()
            for (label in listOf("Cancel download", "Pause", "Show in Finder")) {
                onNodeWithText(label).assertIsDisplayed()
                onNodeWithText(label).assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            }
            onNodeWithTag("detail-finder").performClick()
            onNodeWithTag("detail-screen").assertIsDisplayed()
        }

    @Test
    fun pauseTogglesTheSameStateAsTheListRow() =
        runComposeUiTest {
            openProject()
            onNodeWithTag("detail-pause").performClick()
            onNodeWithTag("detail-pause").assert(hasText("Resume"))
            text("detail-total", "0 B/s")
            text("detail-speed-wifi", "0 B/s")
            onNodeWithTag("detail-back").performClick()
            text("speed-1", "Paused")
            onNodeWithTag("chevron-1").performClick()
            onNodeWithTag("detail-pause").performClick()
            onNodeWithTag("detail-pause").assert(hasText("Pause"))
            text("detail-total", "4.0 MB/s")
        }

    @Test
    fun cancelRemovesTheItemAndReturnsToTheList() =
        runComposeUiTest {
            openProject()
            onNodeWithTag("detail-cancel").performClick()
            // The list is back (filter shown) because the item is gone and the detail closed;
            // no unknown-id state is involved, the other rows are still there.
            onNodeWithTag("detail-screen").assertDoesNotExist()
            onNodeWithTag("filter").assertIsDisplayed()
            onNodeWithTag("row-1").assertDoesNotExist()
            onNodeWithTag("row-2").assertIsDisplayed()
            onNodeWithTag("row-3").assertIsDisplayed()
            onNodeWithText("DOWNLOADING 1").assertIsDisplayed()
        }

    @Test
    fun diagramShowsTotalAverageAndPeakAndTheAloneFactor() =
        runComposeUiTest {
            openProject()
            onNodeWithTag("detail-diagram").assertIsDisplayed()
            text("detail-total", "4.0 MB/s")
            text("detail-diagram-speed-wifi", "2.0 MB/s")
            text("detail-diagram-speed-usb", "2.0 MB/s")
            onNodeWithTag("detail-avg").assert(hasText("AVG ", substring = true))
            onNodeWithTag("detail-peak").assert(hasText("PEAK ", substring = true))
            text("detail-alone", "2.0× WI-FI ALONE")
        }

    @Test
    fun graphAndDiagramCarryTextDescriptions() =
        runComposeUiTest {
            openProject()
            onNodeWithContentDescription("Throughput, last 60 seconds, peak", substring = true).assertIsDisplayed()
            onNodeWithText("60s ago").assertIsDisplayed()
            onNodeWithText("now").assertIsDisplayed()
            onNodeWithText("THROUGHPUT · LAST 60 S").assertIsDisplayed()
            onNodeWithContentDescription("Speed diagram", substring = true).assertIsDisplayed()
        }

    @Test
    fun cardShowsProgressRetriesAndFolder() =
        runComposeUiTest {
            openProject()
            text("detail-card", "176.0 MB of 200.0 MB · 88% · 6s left")
            text("detail-retries", "${"1".hashCode() % 40} retries")
            onNodeWithText("~/Downloads").assertIsDisplayed()
            onNodeWithTag("detail-progress").assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.ProgressBarRangeInfo,
                    androidx.compose.ui.semantics.ProgressBarRangeInfo(
                        0.88f,
                        0f..1f,
                    ),
                ),
            )
        }

    @Test
    fun mapShowsLegendAndChunkCountForTwoNetworks() =
        runComposeUiTest {
            openProject()
            onNodeWithTag("detail-map").assertIsDisplayed()
            onNodeWithTag("detail-map").assert(hasContentDescription("Chunk map", substring = true))
            text("detail-map-caption", "67 chunks · 3.0 MB each")
            onNodeWithTag("detail-legend-wifi").assertIsDisplayed()
            onNodeWithTag("detail-legend-usb").assertIsDisplayed()
        }

    @Test
    fun tableListsOneRowPerNetwork() =
        runComposeUiTest {
            openProject()
            onNodeWithTag("detail-net-wifi").assertIsDisplayed()
            onNodeWithTag("detail-net-usb").assertIsDisplayed()
            onNodeWithTag("detail-check-wifi").assertIsOn()
            text("detail-share-wifi", "50%")
            text("detail-share-usb", "50%")
            text("detail-speed-usb", "2.0 MB/s")
            text("detail-downloaded-usb", "88.0 MB")
            text("detail-streams-wifi", "4 streams")
        }

    @Test
    fun uncheckingUsbRenormalizesAndHidesTheMap() =
        runComposeUiTest {
            openProject()
            onNodeWithTag("detail-check-usb").performClick()
            onNodeWithTag("detail-check-usb").assertIsOff()
            text("detail-speed-usb", "0 B/s")
            text("detail-share-wifi", "100%")
            text("detail-share-usb", "0%")
            text("detail-total", "4.0 MB/s")
            onNodeWithTag("detail-alone").assertDoesNotExist()
            onNodeWithTag("detail-map").assertDoesNotExist()
            onNodeWithTag("detail-retries").assertDoesNotExist()
            onNodeWithTag("detail-check-wifi").performClick()
            onNodeWithTag("detail-check-wifi").assertIsOn()
            text("detail-share-wifi", "100%")
            onNodeWithTag("detail-check-usb").performClick()
            onNodeWithTag("detail-check-usb").assertIsOn()
            onNodeWithTag("detail-map").assertIsDisplayed()
        }

    @Test
    fun networkDisabledInTheMenuIsListedOff() =
        runComposeUiTest {
            home()
            onNodeWithTag("network-chip").performClick()
            onNodeWithTag("network-dot-usb").performClick()
            onNodeWithTag("chevron-1").performClick()
            onNodeWithTag("detail-net-usb").assertIsDisplayed()
            onNodeWithTag("detail-check-usb").assertIsOff()
            text("detail-share-usb", "0%")
            text("detail-share-wifi", "100%")
        }

    @Test
    fun removedOpenItemFallsBackToTheList() =
        runComposeUiTest {
            home()
            onNodeWithTag("chevron-3").performClick()
            onNodeWithTag("detail-cancel").performClick()
            onNodeWithTag("row-3").assertDoesNotExist()
            onNodeWithTag("row-1").assertIsDisplayed()
        }

    @Test
    fun contentScrollsAndKeepsTheFooterAt900x600() =
        runDesktopComposeUiTest(900, 600) {
            home()
            onNodeWithTag("chevron-1").performClick()
            onNodeWithTag("theme-toggle").assertIsDisplayed()
            onNodeWithTag("detail-net-usb").performScrollTo().assertIsDisplayed()
            onNodeWithTag("theme-toggle").assertIsDisplayed()
            onNodeWithTag("detail-map").performScrollTo().assertIsDisplayed()
        }

    @Test
    fun contentFitsAt1280x800() =
        runDesktopComposeUiTest(1280, 800) {
            home()
            onNodeWithTag("chevron-1").performClick()
            onNodeWithTag("detail-net-usb").performScrollTo().assertIsDisplayed()
            onNodeWithTag("theme-toggle").assertIsDisplayed()
        }

    private val mib = 1024L * 1024

    private fun ComposeUiTest.homeWith(vararg extra: DownloadItem) {
        val base = detailSample()
        setContent { HomeScreen(base.copy(downloads = base.downloads + extra), true) }
    }

    private val finishedTorrent
        get() =
            detailSample().downloads.first { it.id == "3" }.let {
                it.copy(id = "4", status = DownloadStatus.Finished, doneBytes = it.totalBytes, bytesPerSec = 0)
            }

    @Test
    fun networkDisabledInTheMenuShowsAnOffLabelInsteadOfStreams() =
        runComposeUiTest {
            home()
            onNodeWithTag("network-chip").performClick()
            onNodeWithTag("network-dot-usb").performClick()
            onNodeWithTag("chevron-1").performClick()
            text("detail-off-usb", "Off")
            onNodeWithTag("detail-streams-usb").assertDoesNotExist()
            onNodeWithTag("detail-off-wifi").assertDoesNotExist()
            text("detail-streams-wifi", "4 streams")
        }

    @Test
    fun uncheckedRowIsNotLabelledOff() =
        runComposeUiTest {
            openProject()
            onNodeWithTag("detail-check-usb").performClick()
            onNodeWithTag("detail-off-usb").assertDoesNotExist()
            onNodeWithTag("detail-streams-usb").assertIsDisplayed()
        }

    @Test
    fun finishedHeaderShowsItsActionsAndNotTheActiveOnes() =
        runComposeUiTest {
            home()
            onNodeWithTag("chevron-2").performClick()
            for (label in listOf("Remove from list", "Move file to Trash", "Open", "Show in Finder")) {
                onNodeWithText(label).assertIsDisplayed()
                onNodeWithText(label).assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            }
            onNodeWithTag("detail-cancel").assertDoesNotExist()
            onNodeWithTag("detail-pause").assertDoesNotExist()
            onNodeWithTag("detail-open").performClick()
            onNodeWithTag("detail-finder").performClick()
            onNodeWithTag("detail-screen").assertIsDisplayed()
        }

    @Test
    fun removeFromListRemovesTheRowAndReturns() =
        runComposeUiTest {
            home()
            onNodeWithTag("chevron-2").performClick()
            onNodeWithTag("detail-remove").performClick()
            onNodeWithTag("detail-screen").assertDoesNotExist()
            onNodeWithTag("row-2").assertDoesNotExist()
            onNodeWithTag("row-1").assertIsDisplayed()
        }

    @Test
    fun moveToTrashRemovesTheRowAndReturns() =
        runComposeUiTest {
            home()
            onNodeWithTag("chevron-2").performClick()
            onNodeWithTag("detail-trash").performClick()
            onNodeWithTag("detail-screen").assertDoesNotExist()
            onNodeWithTag("row-2").assertDoesNotExist()
        }

    @Test
    fun finishedViewShowsCheckAverageStatsAndSubtitle() =
        runComposeUiTest {
            home()
            onNodeWithTag("chevron-2").performClick()
            onNodeWithContentDescription("Download finished").assertIsDisplayed()
            text("detail-name", "report.pdf")
            text("detail-subtitle", "5.0 MB · ~/Downloads")
            onNodeWithText("AVERAGE").assertIsDisplayed()
            text("detail-average", "2.5 MB/s")
            text("detail-stat-size", "5.0 MB")
            text("detail-stat-time", "0:02")
            text("detail-stat-peak", "3.5 MB/s")
            text("detail-stat-networks", "2")
            text("detail-stat-streams", "8")
            onNodeWithTag("detail-stat-peers").assertDoesNotExist()
            onNodeWithTag("unit-mbps").performClick()
            text("detail-average", "20.0 Mbps")
            text("detail-stat-peak", "28.0 Mbps")
        }

    @Test
    fun finishedViewShowsSpeedGraphContributionAndFooter() =
        runComposeUiTest {
            home()
            onNodeWithTag("chevron-2").performClick()
            onNodeWithText("SPEED OVER THE DOWNLOAD").assertIsDisplayed()
            onNodeWithText("60s before the end").assertIsDisplayed()
            onNodeWithText("end").assertIsDisplayed()
            onNodeWithContentDescription("Speed over the download, peak", substring = true).assertIsDisplayed()
            onNodeWithText("CONTRIBUTION BY NETWORK").assertIsDisplayed()
            onNodeWithTag("detail-contribution").assertIsDisplayed()
            text("detail-contrib-wifi", "2.5 MB · 50%")
            text("detail-contrib-usb", "2.5 MB · 50%")
            text("detail-footnote", "written in 2 chunks · ${"2".hashCode() % 40} retries")
            onNodeWithTag("detail-table").assertDoesNotExist()
            onNodeWithText("78.5 GB free").assertIsDisplayed()
        }

    @Test
    fun finishedContributionSharesSumToOneHundred() =
        runComposeUiTest {
            home()
            onNodeWithTag("chevron-2").performClick()
            onNodeWithTag("detail-contribution").assertIsDisplayed()
            val pcts =
                listOf("wifi", "usb").map { id ->
                    val t = onNodeWithTag("detail-contrib-$id").fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("")
                    t.substringAfter("· ").removeSuffix("%").toInt()
                }
            kotlin.test.assertEquals(100, pcts.sum())
        }

    @Test
    fun finishedTorrentShowsPeersPiecesAndUploadFooter() =
        runComposeUiTest {
            homeWith(finishedTorrent)
            onNodeWithTag("chevron-4").performClick()
            text("detail-subtitle", "11 files · 123.3 MB · ~/Downloads")
            text("detail-stat-peers", "9")
            onNodeWithTag("detail-stat-streams").assertDoesNotExist()
            text("detail-footnote", "written in 987 pieces · uploaded 0 B · 0 retries")
        }

    @Test
    fun finishedTorrentWithItsOwnFilesCountsThemInTheSubtitle() =
        runComposeUiTest {
            val own = listOf(SampleFile("a/one.bin", 1_048_576), SampleFile("two.bin", 1_048_576))
            homeWith(finishedTorrent.copy(totalBytes = 2_097_152, doneBytes = 2_097_152, files = own))
            onNodeWithTag("chevron-4").performClick()
            text("detail-subtitle", "2 files · 2.0 MB · ~/Downloads")
        }

    @Test
    fun torrentCardShowsTagPeersAndFilesChip() =
        runComposeUiTest {
            home()
            onNodeWithTag("chevron-3").performClick()
            onNodeWithTag("detail-torrent-tag").assertIsDisplayed()
            onNodeWithTag("detail-card").assert(hasText("74% · 9 peers", substring = true))
            onNodeWithTag("detail-eta").assert(hasText("left", substring = true))
            onNodeWithTag("detail-files").assert(hasText("11 files"))
            onNodeWithTag("detail-files").assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            onNodeWithTag("detail-files-menu").assertDoesNotExist()
        }

    @Test
    fun directCardHasNoTorrentTagOrFilesChip() =
        runComposeUiTest {
            openProject()
            onNodeWithTag("detail-torrent-tag").assertDoesNotExist()
            onNodeWithTag("detail-files").assertDoesNotExist()
        }

    @Test
    fun filesMenuListsTheSampleFilesReadOnly() =
        runComposeUiTest {
            home()
            onNodeWithTag("chevron-3").performClick()
            onNodeWithTag("detail-files").performClick()
            onNodeWithTag("detail-files-menu").assertIsDisplayed()
            sintelFiles.forEachIndexed { i, f ->
                onNodeWithTag("detail-file-$i").assert(hasText(f.name, substring = true))
                onNodeWithTag("detail-file-$i").assert(hasText(formatBytes(f.bytes), substring = true))
            }
            onNodeWithTag("detail-file-0").performClick()
            onNodeWithTag("detail-files-menu").assertIsDisplayed()
        }

    @Test
    fun clickingOutsideTheFilesMenuClosesIt() =
        runComposeUiTest {
            home()
            onNodeWithTag("chevron-3").performClick()
            onNodeWithTag("detail-files").performClick()
            onNodeWithTag("detail-files-outside").performTouchInput { click(Offset(4f, 4f)) }
            onNodeWithTag("detail-files-menu").assertDoesNotExist()
            onNodeWithTag("detail-screen").assertIsDisplayed()
        }

    @Test
    fun escapeClosesTheFilesMenuBeforeLeavingTheDetail() =
        runComposeUiTest {
            home()
            onNodeWithTag("chevron-3").performClick()
            onNodeWithTag("detail-files").performClick()
            escape()
            onNodeWithTag("detail-files-menu").assertDoesNotExist()
            onNodeWithTag("detail-screen").assertIsDisplayed()
            escape()
            onNodeWithTag("detail-screen").assertDoesNotExist()
            onNodeWithTag("row-3").assertIsDisplayed()
        }

    @Test
    fun torrentTableShowsPeersAndDownUpColumns() =
        runComposeUiTest {
            home()
            onNodeWithTag("chevron-3").performClick()
            onNodeWithText("TRANSFERRED").assertIsDisplayed()
            text("detail-streams-wifi", "5 peers")
            text("detail-streams-usb", "4 peers")
            onNodeWithTag("detail-speed-wifi").assert(hasText("↓ ", substring = true))
            text("detail-up-wifi", "↑ –")
            onNodeWithTag("detail-downloaded-wifi").assert(hasText("↓ ", substring = true))
            text("detail-uploaded-wifi", "↑ 0 B")
        }

    @Test
    fun directTableKeepsTheDownloadedColumn() =
        runComposeUiTest {
            openProject()
            onNodeWithText("DOWNLOADED").assertIsDisplayed()
            onNodeWithTag("detail-up-wifi").assertDoesNotExist()
        }

    @Test
    fun torrentPieceMapScrollsAndKeepsTheTableReachableAt900x600() =
        runDesktopComposeUiTest(900, 600) {
            home()
            onNodeWithTag("chevron-3").performClick()
            onNodeWithTag("detail-map").performScrollTo().assertIsDisplayed()
            onNodeWithTag("detail-map").assert(hasContentDescription("987 pieces", substring = true))
            onNodeWithTag("detail-map-caption").assert(hasText("987 pieces · ", substring = true))
            onNodeWithTag("detail-net-usb").performScrollTo().assertIsDisplayed()
            onNodeWithTag("theme-toggle").assertIsDisplayed()
        }

    @Test
    fun finishedViewFitsAt900x600() =
        runDesktopComposeUiTest(900, 600) {
            home()
            onNodeWithTag("chevron-2").performClick()
            onNodeWithTag("detail-footnote").performScrollTo().assertIsDisplayed()
            onNodeWithTag("theme-toggle").assertIsDisplayed()
        }

    @Test
    fun escapeReturnsToTheListAfterClickingTheFooterToggles() =
        runComposeUiTest {
            openProject()
            onNodeWithTag("unit-mbps").performClick()
            escape()
            onNodeWithTag("detail-screen").assertDoesNotExist()
            onNodeWithTag("row-1").assertIsDisplayed()
        }

    @Test
    fun escapeReturnsToTheListAfterClickingTheThemeToggle() =
        runComposeUiTest {
            openProject()
            onNodeWithTag("theme-toggle").performClick()
            escape()
            onNodeWithTag("detail-screen").assertDoesNotExist()
        }

    @Test
    fun escapeAfterAFooterClickClosesOnlyTheFilesMenuFirst() =
        runComposeUiTest {
            home()
            onNodeWithTag("chevron-3").performClick()
            onNodeWithTag("detail-files").performClick()
            onNodeWithTag("unit-mbps").performClick()
            escape()
            onNodeWithTag("detail-files-menu").assertDoesNotExist()
            onNodeWithTag("detail-screen").assertIsDisplayed()
            escape()
            onNodeWithTag("detail-screen").assertDoesNotExist()
        }

    @Test
    fun enterOnAFocusedRowOpensTheDetail() =
        runComposeUiTest {
            home()
            onNodeWithTag("row-1").requestFocus()
            onNodeWithTag("row-1").performKeyInput { pressKey(Key.Enter) }
            onNodeWithTag("detail-screen").assertIsDisplayed()
            onNodeWithTag("detail-name").assertTextEquals("Project.zip")
        }

    @Test
    fun enterOnAFocusedRowOpensTheDetailAndSpaceDoesNot() =
        runComposeUiTest {
            home()
            onNodeWithTag("row-2").requestFocus()
            onNodeWithTag("row-2").performKeyInput { pressKey(Key.Spacebar) }
            onNodeWithTag("detail-name").assertDoesNotExist()
            onNodeWithTag("row-2").performKeyInput { pressKey(Key.Enter) }
            onNodeWithTag("detail-name").assertTextEquals("report.pdf")
        }

    @Test
    fun torrentsWearATorBadgeInTheRowAndTheDetail() =
        runComposeUiTest {
            home()
            onNodeWithTag("badge-3").assertTextEquals("TOR")
            onNodeWithTag("badge-1").assertTextEquals("ZIP")
            onNodeWithTag("chevron-3").performClick()
            onNodeWithTag("detail-badge").assertTextEquals("TOR")
        }

    private fun ComposeUiTest.assertMenuClearsChip() {
        onNodeWithTag("detail-files").performScrollTo().performClick()
        val chip = onNodeWithTag("detail-files").fetchSemanticsNode().boundsInRoot
        val menu = onNodeWithTag("detail-files-menu").fetchSemanticsNode().boundsInRoot
        val root = onRoot().fetchSemanticsNode().boundsInRoot
        kotlin.test.assertTrue(!chip.overlaps(menu), "menu $menu covers chip $chip")
        kotlin.test.assertTrue(menu.top >= root.top && menu.bottom <= root.bottom, "menu $menu leaves the window $root")
    }

    @Test
    fun filesMenuNeverCoversItsChipAt900x600() =
        runDesktopComposeUiTest(900, 600) {
            home()
            onNodeWithTag("chevron-3").performClick()
            assertMenuClearsChip()
        }

    @Test
    fun filesMenuNeverCoversItsChipAt1280x800() =
        runDesktopComposeUiTest(1280, 800) {
            home()
            onNodeWithTag("chevron-3").performClick()
            assertMenuClearsChip()
        }

    @Test
    fun filesMenuFlipsAboveTheChipWhenThereIsNoRoomBelow() =
        runDesktopComposeUiTest(900, 360) {
            home()
            onNodeWithTag("chevron-3").performClick()
            assertMenuClearsChip()
        }
}
