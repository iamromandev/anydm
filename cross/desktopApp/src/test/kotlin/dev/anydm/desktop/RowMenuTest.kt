package dev.anydm.desktop

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class RowMenuTest {
    private fun ComposeUiTest.home(state: HomeState = detailSample()) = setContent { HomeScreen(state, true, clipboardText = { null }) }

    private fun ComposeUiTest.tick(vararg ids: String) = ids.forEach { onNodeWithTag("select-$it").performClick() }

    private fun ComposeUiTest.rightClickRow(id: String) =
        onNodeWithTag("row-$id").performMouseInput {
            rightClick(
                Offset(
                    width / 2f,
                    height / 2f,
                ),
            )
        }

    private fun ComposeUiTest.menuGone() = onNodeWithTag("row-menu").assertDoesNotExist()

    private fun ComposeUiTest.escape() = onRoot().performKeyInput { pressKey(Key.Escape) }

    private fun ComposeUiTest.has(vararg tags: String) = tags.forEach { onNodeWithTag(it).assertIsDisplayed() }

    private fun ComposeUiTest.hidden(vararg tags: String) = tags.forEach { onNodeWithTag(it).assertDoesNotExist() }

    @Test
    fun rightClickOnAnUnselectedFinishedRowSelectsItAndListsOpenFolderRemove() =
        runDesktopComposeUiTest {
            home()
            rightClickRow("2")
            onNodeWithTag("row-menu").assertIsDisplayed()
            has("menu-open", "menu-folder", "menu-remove")
            hidden("menu-pause", "menu-resume")
            onNodeWithTag("select-2").assertIsOn()
            onNodeWithTag("select-1").assertIsOff()
            onNodeWithTag("selection-count").assertTextEquals("1 selected")
        }

    @Test
    fun rightClickNeverOpensTheDetailOrTogglesTheCheckbox() =
        runDesktopComposeUiTest {
            home()
            tick("1")
            rightClickRow("1")
            onNodeWithTag("detail-screen").assertDoesNotExist()
            onNodeWithTag("select-1").assertIsOn()
            onNodeWithTag("selection-count").assertTextEquals("1 selected")
        }

    @Test
    fun rightClickOnTheCheckboxPauseAndChevronCountsAsARowRightClick() =
        runDesktopComposeUiTest {
            home()
            for (tag in listOf("select-1", "pause-1", "chevron-1")) {
                onNodeWithTag(tag).performMouseInput { rightClick() }
                onNodeWithTag("row-menu").assertIsDisplayed()
                onNodeWithTag("detail-screen").assertDoesNotExist()
                onNodeWithTag("speed-1").assertTextEquals("4.0 MB/s")
                onNodeWithTag("select-1").assertIsOn()
                escape()
                menuGone()
                onNodeWithTag("bar-clear").performClick()
            }
        }

    @Test
    fun leftClickAndEnterOnTheRowStillOpenTheDetail() =
        runDesktopComposeUiTest {
            home()
            onNodeWithTag("row-1").performClick()
            onNodeWithTag("detail-screen").assertIsDisplayed()
            menuGone()
        }

    @Test
    fun rightClickOnASelectionActsOnTheWholeSelectionAndPauseOnlyTouchesDownloading() =
        runDesktopComposeUiTest {
            home()
            tick("1", "2")
            rightClickRow("1")
            onNodeWithTag("selection-count").assertTextEquals("2 selected")
            hidden("menu-open", "menu-folder", "menu-resume")
            onNodeWithTag("menu-pause").performClick()
            onNodeWithTag("speed-1").assertTextEquals("Paused")
            onNodeWithTag("speed-3").assertTextEquals("11.0 MB/s")
            menuGone()
            onNodeWithTag("selection-count").assertTextEquals("2 selected")
        }

    @Test
    fun mixedSelectionHidesOpenAndShowInFolder() =
        runDesktopComposeUiTest {
            home()
            tick("1", "2")
            rightClickRow("2")
            hidden("menu-open", "menu-folder")
            has("menu-pause", "menu-remove")
        }

    @Test
    fun pausedTargetsOfferResume() =
        runDesktopComposeUiTest {
            home()
            onNodeWithTag("pause-1").performClick()
            rightClickRow("1")
            has("menu-resume", "menu-remove")
            hidden("menu-pause")
            onNodeWithTag("menu-resume").performClick()
            onNodeWithTag("speed-1").assertTextEquals("4.0 MB/s")
        }

    @Test
    fun removeInTheMenuOpensTheDialogForTheTargets() =
        runDesktopComposeUiTest {
            home()
            tick("1", "2")
            rightClickRow("2")
            onNodeWithTag("menu-remove").performClick()
            menuGone()
            onNodeWithTag("remove-title").assertTextEquals("Remove 2 downloads?")
            onNodeWithTag("remove-list").performClick()
            onNodeWithTag("row-1").assertDoesNotExist()
            onNodeWithTag("row-2").assertDoesNotExist()
            onNodeWithTag("row-3").assertIsDisplayed()
        }

    @Test
    fun openAndShowInFolderAreNoOpsThatCloseTheMenu() =
        runDesktopComposeUiTest {
            home()
            for (tag in listOf("menu-open", "menu-folder")) {
                rightClickRow("2")
                onNodeWithTag(tag).performClick()
                menuGone()
                onNodeWithTag("detail-screen").assertDoesNotExist()
                onNodeWithTag("remove-dialog").assertDoesNotExist()
            }
        }

    @Test
    fun escapeClosesTheMenuFirstAndKeepsTheSelection() =
        runDesktopComposeUiTest {
            home()
            rightClickRow("2")
            escape()
            menuGone()
            onNodeWithTag("selection-count").assertTextEquals("1 selected")
        }

    @Test
    fun clickOutsideClosesTheMenuWithoutActing() =
        runDesktopComposeUiTest {
            home()
            rightClickRow("2")
            onRoot().performMouseInput { click(Offset(4f, 4f)) }
            menuGone()
            onNodeWithTag("detail-screen").assertDoesNotExist()
            onNodeWithTag("remove-dialog").assertDoesNotExist()
        }

    @Test
    fun openingTheNetworksMenuClosesTheRowMenuAndTheReverse() =
        runDesktopComposeUiTest {
            home()
            rightClickRow("2")
            onNodeWithTag("network-chip").performClick()
            menuGone()
            onNodeWithTag("networks-menu").assertIsDisplayed()
            rightClickRow("2")
            onNodeWithTag("row-menu").assertIsDisplayed()
            onNodeWithTag("networks-menu").assertDoesNotExist()
        }

    @Test
    fun aSecondRightClickMovesTheMenu() =
        runDesktopComposeUiTest {
            home()
            rightClickRow("2")
            rightClickRow("1")
            onNodeWithTag("selection-count").assertTextEquals("1 selected")
            onNodeWithTag("select-1").assertIsOn()
            has("menu-pause")
            hidden("menu-open")
        }

    @Test
    fun menuStaysInsideTheWindowNearTheRightAndBottomEdges() {
        val (w, h) = 900 to 600
        for (target in listOf("row-3", "row-2")) {
            runDesktopComposeUiTest(w, h) {
                home()
                val node = onNodeWithTag(target)
                val b = node.fetchSemanticsNode().boundsInRoot
                val pos = Offset(b.width - 2f, minOf(b.height - 2f, h - b.top - 2f))
                node.performMouseInput { rightClick(pos) }
                val m = onNodeWithTag("row-menu").fetchSemanticsNode().boundsInRoot
                assertTrue(m.left >= 0f && m.top >= 0f && m.right <= w && m.bottom <= h, "menu $m outside ${w}x$h")
            }
        }
    }

    @Test
    fun menuItemsHaveButtonRoles() =
        runDesktopComposeUiTest {
            home()
            tick("1")
            rightClickRow("1")
            for (tag in listOf("menu-pause", "menu-remove")) {
                onNodeWithTag(tag).assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            }
            onNodeWithTag("menu-pause").assertTextEquals("Pause")
            onNodeWithTag("menu-remove").assertTextEquals("Remove")
        }

    @Test
    fun arrowKeysMoveWithinTheMenuAndEnterActivates() =
        runDesktopComposeUiTest {
            home()
            tick("1")
            rightClickRow("1")
            onNodeWithTag("menu-pause").assertIsFocused()
            onNodeWithTag("menu-pause").performKeyInput { pressKey(Key.DirectionDown) }
            onNodeWithTag("menu-remove").assertIsFocused()
            onNodeWithTag("menu-remove").performKeyInput { pressKey(Key.DirectionUp) }
            onNodeWithTag("menu-pause").assertIsFocused()
            onNodeWithTag("menu-pause").performKeyInput { pressKey(Key.Enter) }
            menuGone()
            onNodeWithTag("speed-1").assertTextEquals("Paused")
        }

    @Test
    fun tabReachesMenuItemsAndSpaceActivates() =
        runDesktopComposeUiTest {
            home()
            tick("1")
            rightClickRow("1")
            onNodeWithTag("menu-pause").performKeyInput { pressKey(Key.Tab) }
            onNodeWithTag("menu-remove").assertIsFocused()
            onNodeWithTag("menu-remove").performKeyInput { pressKey(Key.Spacebar) }
            onNodeWithTag("remove-dialog").assertIsDisplayed()
        }

    @Test
    fun tabReachesBarButtonsAndSpaceActivates() =
        runDesktopComposeUiTest {
            home()
            tick("1")
            onNodeWithTag("bar-pause").requestFocus()
            onNodeWithTag("bar-pause").assertIsFocused()
            onNodeWithTag("bar-pause").performKeyInput { pressKey(Key.Tab) }
            onNodeWithTag("bar-remove").assertIsFocused()
            onNodeWithTag("bar-pause").requestFocus()
            onNodeWithTag("bar-pause").performKeyInput { pressKey(Key.Spacebar) }
            onNodeWithTag("speed-1").assertTextEquals("Paused")
            onNodeWithTag("bar-clear").requestFocus()
            onNodeWithTag("bar-clear").performKeyInput { pressKey(Key.Enter) }
            onNodeWithTag("selection-bar").assertDoesNotExist()
        }

    @Test
    fun tabReachesDialogButtonsAndEnterActivates() =
        runDesktopComposeUiTest {
            home()
            tick("1")
            onNodeWithTag("bar-remove").performClick()
            onNodeWithTag("remove-cancel").assertIsFocused()
            onNodeWithTag("remove-cancel").performKeyInput { pressKey(Key.Tab) }
            onNodeWithTag("remove-trash").assertIsFocused()
            onNodeWithTag("remove-trash").performKeyInput { pressKey(Key.Tab) }
            onNodeWithTag("remove-list").assertIsFocused()
            onNodeWithTag("remove-list").performKeyInput { pressKey(Key.Enter) }
            onNodeWithTag("row-1").assertDoesNotExist()
        }

    @Test
    fun menuClosesWhenItsTargetsAreNoLongerVisible() =
        runDesktopComposeUiTest {
            home()
            rightClickRow("1")
            onNodeWithTag("row-menu").assertIsDisplayed()
            onNodeWithTag("filter").performSemanticsAction(SemanticsActions.OnClick)
            onNodeWithTag("filter-finished").performSemanticsAction(SemanticsActions.OnClick)
            waitForIdle()
            menuGone()
        }

    @Test
    fun menuClosesWhenTheListScrolls() =
        runDesktopComposeUiTest(900, 600) {
            val many =
                detailSample().let { st ->
                    st.copy(
                        downloads =
                            st.downloads + List(40) { st.downloads[0].copy(id = "x$it", name = "f$it.zip") },
                    )
                }
            home(many)
            rightClickRow("1")
            onNodeWithTag("row-menu").assertIsDisplayed()
            onNodeWithTag("download-list").performScrollToIndex(30)
            waitForIdle()
            menuGone()
        }

    @Test
    fun aSecondButtonPressedWhileRightIsHeldNeitherReopensTheMenuNorClicksTheRow() =
        runDesktopComposeUiTest {
            home()
            onNodeWithTag("row-1").performMouseInput {
                moveTo(center)
                press(MouseButton.Secondary)
                press(MouseButton.Primary)
                release(MouseButton.Primary)
                release(MouseButton.Secondary)
            }
            onNodeWithTag("row-menu").assertIsDisplayed()
            onNodeWithTag("detail-screen").assertDoesNotExist()
            escape()
            menuGone()
            onNodeWithTag("row-1").performClick()
            onNodeWithTag("detail-screen").assertIsDisplayed()
        }
}
