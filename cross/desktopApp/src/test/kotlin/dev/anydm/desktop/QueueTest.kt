package dev.anydm.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class QueueTest {
    private fun ComposeUiTest.home(state: HomeState = detailSample()) = setContent { HomeScreen(state, true, clipboardText = { null }) }

    private fun ComposeUiTest.add(name: String) {
        onNodeWithTag("new-download").performClick()
        onNodeWithTag("url-field").performTextInput("https://example.com/files/$name")
        onNodeWithTag("download").performClick()
    }

    private fun ComposeUiTest.tick(vararg ids: String) = ids.forEach { onNodeWithTag("select-$it").performClick() }

    private fun ComposeUiTest.speed(
        id: String,
        text: String,
    ) = onNodeWithTag("speed-$id").assertTextEquals(text)

    private fun ComposeUiTest.rightClickRow(id: String) =
        onNodeWithTag("row-$id").performMouseInput {
            rightClick(
                Offset(
                    width / 2f,
                    height / 2f,
                ),
            )
        }

    private fun ComposeUiTest.confirmRemove() = onNodeWithTag("remove-confirm").performClick()

    private fun ComposeUiTest.setLimit(target: Int) {
        onNodeWithTag("network-chip").performClick()
        onNodeWithTag("networks-limits-link").performClick()
        val current =
            onNodeWithTag("concurrency-value")
                .fetchSemanticsNode()
                .config[SemanticsProperties.Text]
                .first()
                .text
                .toInt()
        repeat(target - current) { onNodeWithTag("concurrency-plus").performClick() }
        repeat(current - target) { onNodeWithTag("concurrency-minus").performClick() }
        onNodeWithTag("limits-save").performClick()
        onNodeWithTag("settings-back").performClick()
    }

    /** Detail sample at limit 2 with archive.zip (4) queued #1 and notes.pdf (5) queued #2. */
    private fun ComposeUiTest.queued() {
        home()
        add("archive.zip")
        add("notes.pdf")
    }

    private fun row(
        id: String,
        status: DownloadStatus,
    ) = DownloadItem(id, "file$id.zip", 100_000_000L, 10_000_000L, 0L, status)

    private fun withRows(vararg rows: DownloadItem) = populatedSample().copy(downloads = rows.toList())

    @Test
    fun anInitialQueuedRowIsShownUnderQueuedWhenTheSlotsAreFull() =
        runDesktopComposeUiTest {
            home(withRows(row("1", DownloadStatus.Downloading), row("2", DownloadStatus.Downloading), row("3", DownloadStatus.Queued)))
            onNodeWithText("QUEUED 1").assertIsDisplayed()
            speed("3", "#1 · Queued")
            onNodeWithText("2 downloading").assertIsDisplayed()
        }

    @Test
    fun anInitialQueuedRowStartsWhenASlotIsFree() =
        runDesktopComposeUiTest {
            home(withRows(row("1", DownloadStatus.Downloading), row("3", DownloadStatus.Queued)))
            onNodeWithText("QUEUED 1").assertDoesNotExist()
            onNodeWithText("2 downloading").assertIsDisplayed()
            onNodeWithTag("pause-3").assertIsDisplayed()
        }

    @Test
    fun aNewDownloadQueuesWhenBothSlotsAreTaken() =
        runDesktopComposeUiTest {
            home()
            add("archive.zip")
            speed("4", "#1 · Queued")
            onNodeWithText("2 downloading").assertIsDisplayed()
            onNodeWithText("QUEUED 1").assertIsDisplayed()
        }

    @Test
    fun aNewDownloadStartsWhenASlotIsFree() =
        runDesktopComposeUiTest {
            home(populatedSample())
            add("archive.zip")
            onNodeWithText("QUEUED 1").assertDoesNotExist()
            onNodeWithText("2 downloading").assertIsDisplayed()
            onNodeWithTag("pause-3").assertIsDisplayed()
        }

    @Test
    fun pausingARunningRowStartsTheFirstQueuedRow() =
        runDesktopComposeUiTest {
            queued()
            onNodeWithTag("pause-1").performClick()
            speed("1", "Paused")
            onNodeWithText("QUEUED 1").assertIsDisplayed()
            speed("5", "#1 · Queued")
            onNodeWithText("2 downloading").assertIsDisplayed()
            onNodeWithTag("pause-4").assertIsDisplayed()
        }

    @Test
    fun pausingAQueuedRowPausesItAndClosesTheGap() =
        runDesktopComposeUiTest {
            queued()
            onNodeWithTag("pause-4").performClick()
            speed("4", "Paused")
            speed("5", "#1 · Queued")
        }

    @Test
    fun resumingWithFullSlotsQueuesAtTheEnd() =
        runDesktopComposeUiTest {
            home()
            onNodeWithTag("pause-1").performClick()
            add("archive.zip")
            onNodeWithTag("pause-1").performClick()
            speed("1", "#1 · Queued")
            onNodeWithText("QUEUED 1").assertIsDisplayed()
        }

    @Test
    fun resumingWithAFreeSlotStartsTheRow() =
        runDesktopComposeUiTest {
            home()
            onNodeWithTag("pause-1").performClick()
            onNodeWithTag("pause-1").performClick()
            onNodeWithText("QUEUED 1").assertDoesNotExist()
            onNodeWithText("2 downloading").assertIsDisplayed()
        }

    @Test
    fun removingARunningRowFromTheMenuStartsTheFirstQueuedRow() =
        runDesktopComposeUiTest {
            queued()
            rightClickRow("1")
            onNodeWithTag("menu-remove").performClick()
            confirmRemove()
            onNodeWithTag("row-1").assertDoesNotExist()
            speed("5", "#1 · Queued")
            onNodeWithTag("pause-4").assertIsDisplayed()
        }

    @Test
    fun removingFromTheBarStartsTheFirstQueuedRow() =
        runDesktopComposeUiTest {
            queued()
            tick("3")
            onNodeWithTag("bar-remove").performClick()
            confirmRemove()
            onNodeWithTag("row-3").assertDoesNotExist()
            speed("5", "#1 · Queued")
        }

    @Test
    fun removingAQueuedRowClosesTheGap() =
        runDesktopComposeUiTest {
            queued()
            tick("4")
            onNodeWithTag("bar-remove").performClick()
            confirmRemove()
            speed("5", "#1 · Queued")
            onNodeWithText("QUEUED 1").assertIsDisplayed()
        }

    @Test
    fun removingFromTheDetailViewStartsTheFirstQueuedRow() =
        runDesktopComposeUiTest {
            queued()
            onNodeWithTag("chevron-1").performClick()
            onNodeWithTag("detail-remove").performClick()
            onNodeWithTag("row-1").assertDoesNotExist()
            speed("5", "#1 · Queued")
        }

    @Test
    fun raisingTheLimitStartsQueuedRows() =
        runDesktopComposeUiTest {
            queued()
            setLimit(4)
            onNodeWithText("QUEUED 1").assertDoesNotExist()
            onNodeWithText("4 downloading").assertIsDisplayed()
        }

    @Test
    fun loweringTheLimitKeepsRunningRowsAndQueuesNewOnes() =
        runDesktopComposeUiTest {
            home()
            setLimit(1)
            onNodeWithText("2 downloading").assertIsDisplayed()
            add("archive.zip")
            speed("4", "#1 · Queued")
            onNodeWithText("2 downloading").assertIsDisplayed()
        }

    @Test
    fun bulkPauseAndResumeFollowTheQueueRules() =
        runDesktopComposeUiTest {
            queued()
            add("c.zip")
            // Pausing 1 starts 4; now 3 and 4 run, queue is 5 #1 and 6 #2, and 1 is paused.
            onNodeWithTag("pause-1").performClick()
            tick("1", "3", "5")
            onNodeWithTag("bar-pause").performClick()
            speed("1", "Paused")
            speed("3", "Paused")
            speed("5", "Paused")
            // The queue's first row (6) took the freed slot.
            onNodeWithTag("pause-6").assertIsDisplayed()
            onNodeWithText("QUEUED 1").assertDoesNotExist()
            onNodeWithTag("bar-resume").performClick()
            onNodeWithText("QUEUED 3").assertIsDisplayed()
            speed("1", "#1 · Queued")
            speed("3", "#2 · Queued")
            speed("5", "#3 · Queued")
        }

    @Test
    fun sectionsAppearInOrderAndShowQueuePositions() =
        runDesktopComposeUiTest {
            queued()
            val tops =
                listOf("DOWNLOADING 2", "QUEUED 2", "FINISHED 1").map {
                    onNodeWithText(it).fetchSemanticsNode().boundsInRoot.top
                }
            assertTrue(tops == tops.sorted(), "sections out of order: $tops")
            speed("4", "#1 · Queued")
            speed("5", "#2 · Queued")
            onNodeWithContentDescription("Queued, number 2 in line").assertIsDisplayed()
        }

    @Test
    fun selectAllOnQueuedSelectsExactlyTheQueuedRows() =
        runDesktopComposeUiTest {
            queued()
            onNodeWithTag("select-all-queued").performClick()
            onNodeWithTag("select-4").assertIsOn()
            onNodeWithTag("select-5").assertIsOn()
            onNodeWithTag("selection-count").assertTextEquals("2 selected")
            onNodeWithTag("select-1").assertIsOff()
        }

    @Test
    fun theDownloadingFilterShowsQueuedRows() =
        runDesktopComposeUiTest {
            queued()
            onNodeWithTag("filter").performClick()
            onNodeWithTag("filter-downloading").performClick()
            onNodeWithText("QUEUED 2").assertIsDisplayed()
            onNodeWithTag("row-4").assertIsDisplayed()
            onNodeWithText("FINISHED 1").assertDoesNotExist()
        }

    @Test
    fun theFooterStaysVisibleWithThreeSectionsAndTheBar() =
        runDesktopComposeUiTest(900, 600) {
            queued()
            add("a.zip")
            add("b.zip")
            tick("4")
            onNodeWithTag("selection-bar").assertIsDisplayed()
            onNodeWithTag("slow-mode").assertIsDisplayed()
            onNodeWithText("78.5 GB free").assertIsDisplayed()
            onAllNodesWithTag("download-list").assertCountEquals(1)
        }

    @Test
    fun theMenuOnTheFirstQueuedRowHidesMovesUpAndShowsStartNow() =
        runDesktopComposeUiTest {
            queued()
            rightClickRow("4")
            onNodeWithTag("menu-move-top").assertDoesNotExist()
            onNodeWithTag("menu-move-up").assertDoesNotExist()
            onNodeWithTag("menu-move-down").assertIsDisplayed()
            onNodeWithTag("menu-start-now").assertIsDisplayed()
        }

    @Test
    fun theMenuOnTheLastQueuedRowHidesMoveDown() =
        runDesktopComposeUiTest {
            queued()
            rightClickRow("5")
            onNodeWithTag("menu-move-top").assertIsDisplayed()
            onNodeWithTag("menu-move-up").assertIsDisplayed()
            onNodeWithTag("menu-move-down").assertDoesNotExist()
        }

    @Test
    fun theMenuOnARunningRowHasNoQueueActions() =
        runDesktopComposeUiTest {
            queued()
            rightClickRow("1")
            onNodeWithTag("menu-move-top").assertDoesNotExist()
            onNodeWithTag("menu-move-up").assertDoesNotExist()
            onNodeWithTag("menu-move-down").assertDoesNotExist()
            onNodeWithTag("menu-start-now").assertDoesNotExist()
        }

    @Test
    fun moveToTopPutsTheRowFirstInLine() =
        runDesktopComposeUiTest {
            queued()
            rightClickRow("5")
            onNodeWithTag("menu-move-top").performClick()
            speed("5", "#1 · Queued")
            speed("4", "#2 · Queued")
        }

    @Test
    fun moveUpAndMoveDownSwapNeighbours() =
        runDesktopComposeUiTest {
            queued()
            rightClickRow("4")
            onNodeWithTag("menu-move-down").performClick()
            speed("5", "#1 · Queued")
            speed("4", "#2 · Queued")
            rightClickRow("4")
            onNodeWithTag("menu-move-up").performClick()
            speed("4", "#1 · Queued")
            speed("5", "#2 · Queued")
        }

    @Test
    fun startNowRunsTheRowOverTheLimit() =
        runDesktopComposeUiTest {
            queued()
            rightClickRow("5")
            onNodeWithTag("menu-start-now").performClick()
            onNodeWithText("3 downloading").assertIsDisplayed()
            onNodeWithTag("pause-5").assertIsDisplayed()
            speed("4", "#1 · Queued")
            onNodeWithText("QUEUED 1").assertIsDisplayed()
        }

    @Test
    fun startNowOnTheWholeQueueEmptiesIt() =
        runDesktopComposeUiTest {
            queued()
            onNodeWithTag("select-all-queued").performClick()
            rightClickRow("4")
            onNodeWithTag("menu-start-now").performClick()
            onNodeWithText("4 downloading").assertIsDisplayed()
            onNodeWithText("QUEUED 2").assertDoesNotExist()
        }

    @Test
    fun aRowStartedNowKeepsRunningWhenARunningRowIsPaused() =
        runDesktopComposeUiTest {
            queued()
            rightClickRow("5")
            onNodeWithTag("menu-start-now").performClick()
            onNodeWithTag("pause-1").performClick()
            // Three were running, one paused: two run, so the queue stays put.
            onNodeWithText("2 downloading").assertIsDisplayed()
            speed("4", "#1 · Queued")
        }

    private fun ComposeUiTest.assertButton(
        tag: String,
        label: String,
    ) {
        val config = onNodeWithTag(tag).fetchSemanticsNode().config
        assertEquals(Role.Button, config[SemanticsProperties.Role], "$tag role")
        assertEquals(listOf(label), config[SemanticsProperties.Text].map { it.text }, "$tag label")
    }

    @Test
    fun theNewMenuItemsAreButtonsWithTheirLabels() =
        runDesktopComposeUiTest {
            home(withRows(row("1", DownloadStatus.Downloading), row("2", DownloadStatus.Downloading)))
            add("a.zip")
            add("b.zip")
            add("c.zip")
            rightClickRow("4")
            assertButton("menu-move-down", "Move down")
            assertButton("menu-start-now", "Start now")
            onNodeWithTag("row-menu").performMouseInput { moveTo(Offset(-1f, -1f)) }
            onNodeWithText("Move down").performClick()
            rightClickRow("4")
            assertButton("menu-move-top", "Move to top")
            assertButton("menu-move-up", "Move up")
        }

    @Test
    fun everyQueuedRowExposesItsPositionToAccessibility() =
        runDesktopComposeUiTest {
            home(withRows(row("1", DownloadStatus.Downloading), row("2", DownloadStatus.Downloading)))
            add("a.zip")
            add("b.zip")
            add("c.zip")
            onNodeWithContentDescription("Queued, number 1 in line").assertIsDisplayed()
            onNodeWithContentDescription("Queued, number 2 in line").assertIsDisplayed()
            onNodeWithContentDescription("Queued, number 3 in line").assertIsDisplayed()
            onNodeWithText("#1 · Queued").assertIsDisplayed()
            onNodeWithText("#3 · Queued").assertIsDisplayed()
        }

    @Test
    fun theDetailStartNowIsAButtonWithItsLabel() =
        runDesktopComposeUiTest {
            queued()
            openDetail("5")
            assertButton("detail-start-now", "Start now")
        }

    private fun ComposeUiTest.openDetail(id: String) = onNodeWithTag("chevron-$id").performClick()

    @Test
    fun aQueuedDetailShowsStartNowRemoveAndFinderWithoutPause() =
        runDesktopComposeUiTest {
            queued()
            openDetail("5")
            onNodeWithTag("detail-start-now").assertIsDisplayed()
            onNodeWithTag("detail-remove").assertIsDisplayed()
            onNodeWithTag("detail-finder").assertIsDisplayed()
            onNodeWithTag("detail-pause").assertDoesNotExist()
            onNodeWithTag("detail-queued").assertTextEquals("Queued · #2 in line")
            onNodeWithTag("detail-average").assertDoesNotExist()
        }

    @Test
    fun aQueuedDetailReplacesTheSpeedDiagramWithTheQueuePosition() =
        runDesktopComposeUiTest {
            queued()
            openDetail("4")
            onNodeWithTag("detail-queued").assertTextEquals("Queued · #1 in line")
            onNodeWithTag("detail-diagram").assertDoesNotExist()
        }

    @Test
    fun startNowInTheDetailSwitchesToTheRunningLayoutOverTheLimit() =
        runDesktopComposeUiTest {
            queued()
            openDetail("5")
            onNodeWithTag("detail-start-now").performClick()
            onNodeWithTag("detail-queued").assertDoesNotExist()
            onNodeWithTag("detail-start-now").assertDoesNotExist()
            onNodeWithTag("detail-pause").assertIsDisplayed()
            onNodeWithTag("detail-back").performClick()
            onNodeWithText("3 downloading").assertIsDisplayed()
            speed("4", "#1 · Queued")
        }

    @Test
    fun removingFromAQueuedDetailClosesTheGapAndReturns() =
        runDesktopComposeUiTest {
            queued()
            openDetail("4")
            onNodeWithTag("detail-remove").performClick()
            onNodeWithTag("row-4").assertDoesNotExist()
            speed("5", "#1 · Queued")
        }

    @Test
    fun anOpenQueuedDetailFollowsTheRowWhenItAutoStartsOrMoves() =
        runDesktopComposeUiTest {
            var item by mutableStateOf(detailSample().downloads.first().copy(id = "9", status = DownloadStatus.Queued))
            var position by mutableStateOf<Int?>(2)
            setContent {
                DetailScreen(item, emptyList(), SpeedUnit.MBps, false, {}, {}, {}, {}, {}, position, {}, home = "/home/user")
            }
            onNodeWithTag("detail-queued").assertTextEquals("Queued · #2 in line")
            position = 1
            onNodeWithTag("detail-queued").assertTextEquals("Queued · #1 in line")
            item = item.copy(status = DownloadStatus.Downloading)
            position = null
            onNodeWithTag("detail-queued").assertDoesNotExist()
            onNodeWithTag("detail-pause").assertIsDisplayed()
            onNodeWithTag("detail-start-now").assertDoesNotExist()
        }

    private fun ComposeUiTest.openNewDownload(name: String) {
        onNodeWithTag("new-download").performClick()
        onNodeWithTag("url-field").performTextInput("https://example.com/files/$name")
    }

    @Test
    fun newDownloadHintsItStartsAfterOneWhenAllSlotsAreTaken() =
        runDesktopComposeUiTest {
            home()
            openNewDownload("a.zip")
            onNodeWithTag("queue-hint").assertTextEquals("Starts after 1 download")
        }

    @Test
    fun newDownloadHintCountsTheQueue() =
        runDesktopComposeUiTest {
            queued()
            openNewDownload("c.zip")
            onNodeWithTag("queue-hint").assertTextEquals("Starts after 3 downloads")
        }

    @Test
    fun newDownloadHasNoHintWhenASlotIsFree() =
        runDesktopComposeUiTest {
            home(populatedSample())
            openNewDownload("a.zip")
            onNodeWithTag("preview-name").assertIsDisplayed()
            onNodeWithTag("queue-hint").assertDoesNotExist()
        }

    @Test
    fun newDownloadHasNoHintWithoutAPreview() =
        runDesktopComposeUiTest {
            home()
            onNodeWithTag("new-download").performClick()
            onNodeWithTag("queue-hint").assertDoesNotExist()
        }
}
