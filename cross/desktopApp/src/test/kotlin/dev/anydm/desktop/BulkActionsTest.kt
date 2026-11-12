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
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class BulkActionsTest {
    private fun ComposeUiTest.home(
        state: HomeState = detailSample(),
        clipboard: String? = null,
    ) = setContent { HomeScreen(state, true, clipboardText = { clipboard }) }

    private fun ComposeUiTest.tick(vararg ids: String) = ids.forEach { onNodeWithTag("select-$it").performClick() }

    private fun ComposeUiTest.barGone() = onNodeWithTag("selection-bar").assertDoesNotExist()

    private fun ComposeUiTest.dialogGone() = onNodeWithTag("remove-dialog").assertDoesNotExist()

    private fun ComposeUiTest.count(text: String) = onNodeWithTag("selection-count").assertTextEquals(text)

    private fun ComposeUiTest.rowsRemain(vararg ids: String) = ids.forEach { onNodeWithTag("row-$it").assertIsDisplayed() }

    private fun ComposeUiTest.escape() = onRoot().performKeyInput { pressKey(Key.Escape) }

    private fun hasRole(role: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)

    @Test
    fun barAppearsWithCountAndAllButtonsOnlyWhenARowIsSelected() =
        runDesktopComposeUiTest {
            home()
            barGone()
            tick("1")
            count("1 selected")
            listOf("bar-pause", "bar-resume", "bar-remove", "bar-clear").forEach { onNodeWithTag(it).assertIsDisplayed() }
            tick("2")
            count("2 selected")
        }

    @Test
    fun pauseAppliesToDownloadingRowsOnlyAndKeepsSelection() =
        runDesktopComposeUiTest {
            home()
            tick("1", "2")
            onNodeWithTag("bar-pause").assertIsEnabled()
            onNodeWithTag("bar-resume").assertIsNotEnabled()
            onNodeWithTag("bar-pause").performClick()
            onNodeWithTag("speed-1").assertTextEquals("Paused")
            onNodeWithTag("select-1").assertIsOn()
            onNodeWithTag("select-2").assertIsOn()
            count("2 selected")
            onNodeWithTag("bar-pause").assertIsNotEnabled()
        }

    @Test
    fun resumeAppliesToPausedRowsAndOnlyFinishedDisablesBoth() =
        runDesktopComposeUiTest {
            home()
            onNodeWithTag("pause-1").performClick()
            tick("1")
            onNodeWithTag("bar-resume").assertIsEnabled()
            onNodeWithTag("bar-resume").performClick()
            onNodeWithTag("speed-1").assertTextEquals("4.0 MB/s")
            tick("1", "2")
            onNodeWithTag("bar-pause").assertIsNotEnabled()
            onNodeWithTag("bar-resume").assertIsNotEnabled()
        }

    @Test
    fun clearEmptiesTheSelectionAndHidesTheBar() =
        runDesktopComposeUiTest {
            home()
            tick("1", "2")
            onNodeWithTag("bar-clear").performClick()
            barGone()
            onNodeWithTag("select-1").assertIsOff()
            onNodeWithTag("select-2").assertIsOff()
        }

    @Test
    fun removeOpensDialogWithTitleAndThreeButtons() =
        runDesktopComposeUiTest {
            home()
            tick("1", "2")
            onNodeWithTag("bar-remove").performClick()
            onNodeWithTag("remove-dialog").assertIsDisplayed()
            onNodeWithTag("remove-title").assertTextEquals("Remove 2 downloads?")
            onNodeWithTag("remove-list").assertTextEquals("Remove from list")
            onNodeWithTag("remove-trash").assertTextEquals("Move files to Trash")
            onNodeWithTag("remove-cancel").assertTextEquals("Cancel")
            listOf("remove-list", "remove-trash", "remove-cancel").forEach { onNodeWithTag(it).assert(hasRole(Role.Button)) }
        }

    @Test
    fun singleRowTitleIsSingular() =
        runDesktopComposeUiTest {
            home()
            tick("2")
            onNodeWithTag("bar-remove").performClick()
            onNodeWithTag("remove-title").assertTextEquals("Remove 1 download?")
        }

    private fun removeWith(button: String) =
        runDesktopComposeUiTest {
            home()
            tick("1", "2")
            onNodeWithTag("bar-remove").performClick()
            onNodeWithTag(button).performClick()
            dialogGone()
            barGone()
            onNodeWithTag("row-1").assertDoesNotExist()
            onNodeWithTag("row-2").assertDoesNotExist()
            rowsRemain("3")
            onNodeWithTag("select-3").assertIsOff()
        }

    @Test
    fun removeFromListRemovesTargetsAndKeepsOthers() = removeWith("remove-list")

    @Test
    fun moveToTrashRemovesTargetsAndKeepsOthers() = removeWith("remove-trash")

    @Test
    fun removingEveryRowShowsTheEmptyState() =
        runDesktopComposeUiTest {
            home(populatedSample())
            tick("1", "2")
            onNodeWithTag("bar-remove").performClick()
            onNodeWithTag("remove-list").performClick()
            barGone()
            onNodeWithTag("home-root").assertIsDisplayed()
            onNodeWithTag("clear-finished").assertDoesNotExist()
        }

    @Test
    fun cancelKeepsEverythingAndFocusStartsOnCancel() =
        runDesktopComposeUiTest {
            home()
            tick("1", "2")
            onNodeWithTag("bar-remove").performClick()
            onNodeWithTag("remove-cancel").assertIsFocused()
            onNodeWithTag("remove-cancel").performClick()
            dialogGone()
            rowsRemain("1", "2", "3")
            count("2 selected")
        }

    @Test
    fun enterOnTheInitialFocusCancelsSafely() =
        runDesktopComposeUiTest {
            home()
            tick("1")
            onNodeWithTag("bar-remove").performClick()
            onNodeWithTag("remove-cancel").performKeyInput { pressKey(Key.Enter) }
            dialogGone()
            rowsRemain("1", "2", "3")
        }

    @Test
    fun escapeClosesTheDialogWithoutRemoving() =
        runDesktopComposeUiTest {
            home()
            tick("1")
            onNodeWithTag("bar-remove").performClick()
            escape()
            dialogGone()
            rowsRemain("1", "2", "3")
        }

    @Test
    fun clickOutsideTheCardClosesTheDialogWithoutRemoving() =
        runDesktopComposeUiTest {
            home()
            tick("1")
            onNodeWithTag("bar-remove").performClick()
            onRoot().performMouseInput { click(Offset(4f, 4f)) }
            dialogGone()
            rowsRemain("1", "2", "3")
            count("1 selected")
        }

    @Test
    fun clickInsideTheCardDoesNotClose() =
        runDesktopComposeUiTest {
            home()
            tick("1")
            onNodeWithTag("bar-remove").performClick()
            onNodeWithTag("remove-title").performClick()
            onNodeWithTag("remove-dialog").assertIsDisplayed()
        }

    private fun ComposeUiTest.pasteShortcut() =
        onRoot().performKeyInput {
            val mod = if (System.getProperty("os.name").startsWith("Mac")) Key.MetaLeft else Key.CtrlLeft
            keyDown(mod)
            pressKey(Key.V)
            keyUp(mod)
        }

    @Test
    fun pasteShortcutOpensNewDownloadWhenNoDialogIsOpen() =
        runDesktopComposeUiTest {
            home(clipboard = "https://example.com/a.zip")
            pasteShortcut()
            waitForIdle()
            onNodeWithTag("new-download-dialog").assertIsDisplayed()
        }

    @Test
    fun dialogBlocksRowOpenChipAndPaste() =
        runDesktopComposeUiTest {
            home(clipboard = "https://example.com/a.zip")
            tick("1")
            onNodeWithTag("bar-remove").performClick()
            pasteShortcut()
            waitForIdle()
            onNodeWithTag("new-download-dialog").assertDoesNotExist()
            onNodeWithTag("remove-dialog").assertIsDisplayed()
            // Row and chip sit under the scrim: a click there only dismisses the dialog.
            onNodeWithTag("network-chip").performClick()
            onNodeWithTag("networks-menu").assertDoesNotExist()
            onNodeWithTag("detail-screen").assertDoesNotExist()
            dialogGone()
            onNodeWithTag("row-1").performClick()
            onNodeWithTag("detail-screen").assertIsDisplayed()
        }

    @Test
    fun rightClickUnderTheScrimOfAnyDialogOpensNoMenu() =
        runDesktopComposeUiTest {
            home()
            tick("1")
            onNodeWithTag("bar-remove").performClick()
            onNodeWithTag("row-2").performMouseInput { rightClick(center) }
            onNodeWithTag("row-menu").assertDoesNotExist()
            onNodeWithTag("remove-cancel").performClick()
            onNodeWithTag("new-download").performClick()
            onNodeWithTag("new-download-dialog").assertIsDisplayed()
            onNodeWithTag("row-2").performMouseInput { rightClick(center) }
            onNodeWithTag("row-menu").assertDoesNotExist()
        }

    @Test
    fun dialogButtonsFitInsideTheCardAtSmallWindows() =
        runDesktopComposeUiTest(900, 600) {
            home()
            tick("1")
            onNodeWithTag("bar-remove").performClick()
            val card = onNodeWithTag("remove-dialog").fetchSemanticsNode().boundsInRoot
            for (tag in listOf("remove-cancel", "remove-trash", "remove-list")) {
                val b = onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
                assertTrue(b.left >= card.left && b.right <= card.right, "$tag $b outside card $card")
            }
            onNodeWithTag("remove-list").assertTextEquals("Remove from list")
        }

    @Test
    fun noBarOnTheDetailScreenAndItReturnsWithTheSelection() =
        runDesktopComposeUiTest {
            home()
            tick("1")
            onNodeWithTag("chevron-1").performClick()
            onNodeWithTag("detail-screen").assertIsDisplayed()
            barGone()
            onNodeWithTag("detail-back").performClick()
            count("1 selected")
            onNodeWithTag("select-1").assertIsOn()
        }

    private fun ComposeUiTest.showOnly(filter: String) {
        onNodeWithTag("filter").performClick()
        onNodeWithTag("filter-$filter").performClick()
    }

    @Test
    fun barCountsOnlyVisibleSelectedRowsAndKeepsHiddenOnesSelected() =
        runDesktopComposeUiTest {
            home()
            tick("1", "2")
            showOnly("finished")
            count("1 selected")
            showOnly("all")
            count("2 selected")
            onNodeWithTag("select-1").assertIsOn()
            onNodeWithTag("select-2").assertIsOn()
        }

    @Test
    fun pauseOnlyTouchesVisibleSelectedRows() =
        runDesktopComposeUiTest {
            home()
            tick("1", "3")
            showOnly("finished")
            barGone()
            showOnly("downloading")
            count("2 selected")
            tick("1")
            count("1 selected")
            onNodeWithTag("bar-pause").performClick()
            onNodeWithTag("speed-3").assertTextEquals("Paused")
            showOnly("all")
            onNodeWithTag("speed-1").assertTextEquals("4.0 MB/s")
            onNodeWithTag("select-1").assertIsOff()
        }

    @Test
    fun removeOnlyTouchesVisibleSelectedRowsAndHiddenStaySelected() =
        runDesktopComposeUiTest {
            home()
            tick("1", "2")
            showOnly("finished")
            onNodeWithTag("bar-remove").performClick()
            onNodeWithTag("remove-title").assertTextEquals("Remove 1 download?")
            onNodeWithTag("remove-list").performClick()
            barGone()
            showOnly("all")
            onNodeWithTag("row-2").assertDoesNotExist()
            rowsRemain("1", "3")
            onNodeWithTag("select-1").assertIsOn()
            count("1 selected")
        }

    @Test
    fun clearFinishedListDropsRemovedRowsFromTheCount() =
        runDesktopComposeUiTest {
            home()
            tick("1", "2")
            onNodeWithTag("clear-finished").performClick()
            count("1 selected")
        }

    @Test
    fun barButtonsHaveButtonRoles() =
        runDesktopComposeUiTest {
            home()
            tick("1")
            listOf("bar-pause", "bar-resume", "bar-remove", "bar-clear").forEach { onNodeWithTag(it).assert(hasRole(Role.Button)) }
        }

    @Test
    fun barSitsBetweenTopBarAndListAtSmallWindows() {
        for ((w, h) in listOf(900 to 600, 1280 to 800)) {
            runDesktopComposeUiTest(w, h) {
                home()
                tick("1", "2")
                onNodeWithTag("bar-remove").assertIsDisplayed()
                onNodeWithTag("bar-clear").assertIsDisplayed()
                onNodeWithTag("theme-toggle").assertIsDisplayed()
                val bar = onNodeWithTag("selection-bar").fetchSemanticsNode().boundsInRoot
                val header = onNodeWithTag("select-all-downloading").fetchSemanticsNode().boundsInRoot
                assertTrue(bar.bottom <= header.top, "bar $bar overlaps list header $header at ${w}x$h")
                assertTrue(bar.right <= w && bar.left >= 0f, "bar $bar outside the window at ${w}x$h")
            }
        }
    }
}
