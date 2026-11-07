package dev.anydm.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.NativeClipboard
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import java.awt.datatransfer.StringSelection
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class NetworksMenuTest {
    private fun ComposeUiTest.home(
        state: HomeState = populatedSample(),
        dark: Boolean = true,
    ) = setContent { HomeScreen(state, dark) }

    private fun ComposeUiTest.openMenu() = onNodeWithTag("network-chip").performClick()

    /** The chip toggles, so with the menu open this click closes it. */
    private fun ComposeUiTest.closeMenuViaChip() = openMenu()

    private fun ComposeUiTest.menuGone() = onNodeWithTag("networks-menu").assertDoesNotExist()

    private fun ComposeUiTest.speed(
        id: String,
        text: String,
    ) = onNodeWithTag("network-speed-$id", useUnmergedTree = true).assertTextEquals(text)

    private fun ComposeUiTest.chip(
        label: String,
        speed: String,
    ) {
        onNodeWithTag("network-chip").assertIsDisplayed()
        onNodeWithText(label).assertIsDisplayed()
        onNodeWithTag("chip-speed", useUnmergedTree = true).assertTextEquals(speed)
    }

    private fun ComposeUiTest.name(
        id: String,
        text: String,
    ) = onNodeWithTag("network-name-$id", useUnmergedTree = true).assertTextEquals(text)

    private fun ComposeUiTest.startRename(id: String) = onNodeWithTag("network-rename-$id").performClick()

    private fun ComposeUiTest.type(text: String) = onNodeWithTag("network-rename-field").performTextReplacement(text)

    private fun ComposeUiTest.press(key: Key) = onNodeWithTag("network-rename-field").performKeyInput { pressKey(key) }

    private fun ComposeUiTest.fieldStays() = onNodeWithTag("network-rename-field").assertIsDisplayed()

    @Test
    fun menuIsClosedUntilTheChipIsClicked() =
        runComposeUiTest {
            home()
            menuGone()
        }

    @Test
    fun chipOpensPanelWithHeadingRowsAndLink() =
        runComposeUiTest {
            home()
            openMenu()
            onNodeWithTag("networks-menu").assertIsDisplayed()
            onNodeWithText("Networks").assertIsDisplayed()
            onNodeWithTag("network-row-wifi").assertIsDisplayed()
            onNodeWithTag("network-row-usb").assertIsDisplayed()
            speed("wifi", "7.1 MB/s")
            speed("usb", "6.9 MB/s")
            name("wifi", "Wi-Fi")
            name("usb", "USB Tethering")
            onNodeWithTag("networks-limits-link").assertIsDisplayed()
        }

    @Test
    fun panelWorksInLightTheme() =
        runComposeUiTest {
            home(dark = false)
            openMenu()
            onNodeWithTag("networks-menu").assertIsDisplayed()
            speed("wifi", "7.1 MB/s")
        }

    @Test
    fun clickingTheChipAgainClosesThePanel() =
        runComposeUiTest {
            home()
            openMenu()
            openMenu()
            menuGone()
        }

    @Test
    fun clickingOutsideClosesThePanel() =
        runComposeUiTest {
            home()
            openMenu()
            onNodeWithTag("slow-mode").performClick()
            menuGone()
        }

    @Test
    fun escapeClosesThePanel() =
        runComposeUiTest {
            home()
            openMenu()
            onNodeWithTag("networks-menu").performKeyInput { pressKey(Key.Escape) }
            menuGone()
        }

    @Test
    fun mbpsUnitConvertsRowSpeeds() =
        runComposeUiTest {
            home()
            onNodeWithTag("unit-mbps").performClick()
            openMenu()
            speed("wifi", "56.8 Mbps")
            speed("usb", "55.2 Mbps")
        }

    @Test
    fun togglingUsbShowsOffAndUpdatesTheChip() =
        runComposeUiTest {
            home()
            chip("2 networks", "14.0 MB/s")
            openMenu()
            onNodeWithTag("network-dot-usb").performClick()
            speed("usb", "Off")
            speed("wifi", "7.1 MB/s")
            chip("1 network", "7.1 MB/s")
        }

    @Test
    fun lastEnabledNetworkCannotBeTurnedOff() =
        runComposeUiTest {
            home()
            openMenu()
            onNodeWithTag("network-dot-wifi").assertIsEnabled()
            onNodeWithTag("network-dot-usb").performClick()
            onNodeWithTag("network-dot-wifi").assertIsNotEnabled()
            onNodeWithTag("network-dot-wifi").performClick()
            speed("wifi", "7.1 MB/s")
            chip("1 network", "7.1 MB/s")
        }

    @Test
    fun turningUsbBackOnRestoresTheTotal() =
        runComposeUiTest {
            home()
            openMenu()
            onNodeWithTag("network-dot-usb").performClick()
            onNodeWithTag("network-dot-usb").performClick()
            speed("usb", "6.9 MB/s")
            onNodeWithTag("network-dot-wifi").assertIsEnabled()
            chip("2 networks", "14.0 MB/s")
        }

    @Test
    fun emptySampleShowsOneIdleNetwork() =
        runComposeUiTest {
            home(emptySample())
            chip("1 network", "0 B/s")
            openMenu()
            speed("wifi", "Idle")
        }

    @Test
    fun renameWithEnterSavesTheTrimmedName() =
        runComposeUiTest {
            home()
            openMenu()
            startRename("wifi")
            onNodeWithTag("network-rename-field").assertIsFocused()
            onNodeWithTag("network-rename-field").assertTextEquals("Wi-Fi")
            type("  Home ")
            press(Key.Enter)
            onNodeWithTag("network-rename-field").assertDoesNotExist()
            name("wifi", "Home")
        }

    @Test
    fun renameFieldIsPrefilledWithTheCurrentName() =
        runComposeUiTest {
            home()
            openMenu()
            startRename("usb")
            onNodeWithTag("network-rename-field").assertTextEquals("USB Tethering")
        }

    @Test
    fun escapeCancelsTheRenameAndKeepsThePanelOpen() =
        runComposeUiTest {
            home()
            openMenu()
            startRename("wifi")
            type("Home")
            press(Key.Escape)
            onNodeWithTag("network-rename-field").assertDoesNotExist()
            onNodeWithTag("networks-menu").assertIsDisplayed()
            name("wifi", "Wi-Fi")
        }

    @Test
    fun blankNameIsRejectedAndTheFieldStays() =
        runComposeUiTest {
            home()
            openMenu()
            startRename("wifi")
            onNodeWithTag("network-rename-field").performTextClearance()
            press(Key.Enter)
            fieldStays()
            press(Key.Escape)
            name("wifi", "Wi-Fi")
        }

    @Test
    fun overlongNameIsRejected() =
        runComposeUiTest {
            home()
            openMenu()
            startRename("wifi")
            type("x".repeat(25))
            press(Key.Enter)
            fieldStays()
            press(Key.Escape)
            name("wifi", "Wi-Fi")
        }

    @Test
    fun duplicateNameIgnoringCaseIsRejected() =
        runComposeUiTest {
            home()
            openMenu()
            startRename("wifi")
            type("usb tethering")
            press(Key.Enter)
            fieldStays()
            press(Key.Escape)
            name("wifi", "Wi-Fi")
            name("usb", "USB Tethering")
        }

    @Test
    fun savingTheUnchangedNameIsAccepted() =
        runComposeUiTest {
            home()
            openMenu()
            startRename("wifi")
            press(Key.Enter)
            onNodeWithTag("network-rename-field").assertDoesNotExist()
            name("wifi", "Wi-Fi")
        }

    @Test
    fun losingFocusSavesTheRename() =
        runComposeUiTest {
            home()
            openMenu()
            startRename("wifi")
            type("Home")
            onNodeWithTag("networks-menu").performClick()
            onNodeWithTag("network-rename-field").assertDoesNotExist()
            name("wifi", "Home")
        }

    @Test
    fun startingASecondRenameEndsTheFirstOne() =
        runComposeUiTest {
            home()
            openMenu()
            startRename("wifi")
            type("Home")
            startRename("usb")
            name("wifi", "Home")
            onNodeWithTag("network-rename-field").assertTextEquals("USB Tethering")
        }

    @Test
    fun togglingWhileRenamingSavesAValidEdit() =
        runComposeUiTest {
            home()
            openMenu()
            startRename("wifi")
            type("Home")
            onNodeWithTag("network-dot-usb").performClick()
            onNodeWithTag("network-rename-field").assertDoesNotExist()
            name("wifi", "Home")
            speed("usb", "Off")
        }

    @Test
    fun togglingWhileRenamingCancelsAnInvalidEdit() =
        runComposeUiTest {
            home()
            openMenu()
            startRename("wifi")
            type("")
            onNodeWithTag("network-dot-usb").performClick()
            speed("usb", "Off")
            onNodeWithTag("network-rename-field").assertDoesNotExist()
            name("wifi", "Wi-Fi")
        }

    @Test
    fun dialogListsOnlyEnabledNetworks() =
        runComposeUiTest {
            home()
            openMenu()
            onNodeWithTag("network-dot-usb").performClick()
            closeMenuViaChip()
            onNodeWithTag("new-download").performClick()
            onNodeWithTag("url-field").performTextInput("http://127.0.0.1:8765/Project.zip")
            onNodeWithTag("network-Wi-Fi").assertIsDisplayed()
            onNodeWithTag("network-USB Tethering").assertDoesNotExist()
            onNodeWithTag("streams-auto").assertDoesNotExist()
        }

    @Test
    fun dialogShowsTheRenamedNetwork() =
        runComposeUiTest {
            home()
            openMenu()
            startRename("wifi")
            type("Home")
            press(Key.Enter)
            closeMenuViaChip()
            onNodeWithTag("new-download").performClick()
            onNodeWithTag("network-Home").assertIsDisplayed()
            onNodeWithTag("network-Wi-Fi").assertDoesNotExist()
            onNodeWithTag("network-USB Tethering").assertIsDisplayed()
        }

    @Test
    fun dialogKeepsStreamsWithTwoEnabledNetworks() =
        runComposeUiTest {
            home()
            onNodeWithTag("new-download").performClick()
            onNodeWithTag("url-field").performTextInput("http://127.0.0.1:8765/Project.zip")
            onNodeWithTag("streams-auto").assertIsDisplayed()
        }

    @Test
    fun openingTheDialogClosesTheMenu() =
        runComposeUiTest {
            home()
            openMenu()
            onNodeWithTag("new-download").performClick()
            menuGone()
            onNodeWithTag("new-download-dialog").assertIsDisplayed()
        }

    @Test
    fun menuDoesNotOpenWhileTheDialogIsOpen() =
        runComposeUiTest {
            home()
            onNodeWithTag("new-download").performClick()
            openMenu()
            menuGone()
            onNodeWithTag("new-download-dialog").assertIsDisplayed()
        }

    @Test
    fun limitsLinkAndChevronsDoNothing() =
        runComposeUiTest {
            home()
            openMenu()
            onNodeWithTag("networks-limits-link").performClick()
            onNodeWithTag("network-chevron-wifi").performClick()
            onNodeWithTag("network-chevron-usb").performClick()
            onNodeWithTag("networks-menu").assertIsDisplayed()
            speed("wifi", "7.1 MB/s")
            speed("usb", "6.9 MB/s")
            chip("2 networks", "14.0 MB/s")
        }

    @Test
    fun escapeStillClosesAfterClickingTheHeading() =
        runComposeUiTest {
            home()
            openMenu()
            onNodeWithText("Networks").performClick()
            onNodeWithTag("networks-menu").performKeyInput { pressKey(Key.Escape) }
            menuGone()
        }

    @Test
    fun pasteWhileRenamingGoesIntoTheFieldNotTheDialog() =
        runComposeUiTest {
            setContent {
                CompositionLocalProvider(LocalClipboard provides NetworksFakeClipboard("PASTED")) {
                    HomeScreen(populatedSample(), true, clipboardText = { "https://other.test/b.zip" })
                }
            }
            openMenu()
            startRename("wifi")
            type("")
            paste()
            waitForIdle()
            onNodeWithTag("new-download-dialog").assertDoesNotExist()
            onNodeWithTag("network-rename-field").assertTextEquals("PASTED")
        }

    @Test
    fun pasteWithMenuOpenAndNotRenamingClosesItAndOpensTheDialog() =
        runComposeUiTest {
            setContent { HomeScreen(populatedSample(), true, clipboardText = { "https://other.test/b.zip" }) }
            openMenu()
            paste()
            menuGone()
            onNodeWithTag("new-download-dialog").assertIsDisplayed()
            onNodeWithTag("url-field").assertTextEquals("https://other.test/b.zip")
        }

    @Test
    fun homeRegainsPasteShortcutAfterTheMenuCloses() =
        runComposeUiTest {
            setContent { HomeScreen(populatedSample(), true, clipboardText = { "https://other.test/b.zip" }) }
            openMenu()
            onNodeWithTag("networks-menu").performKeyInput { pressKey(Key.Escape) }
            menuGone()
            paste()
            onNodeWithTag("new-download-dialog").assertIsDisplayed()
        }

    private fun ComposeUiTest.paste() =
        onRoot().performKeyInput {
            val mod = if (System.getProperty("os.name").startsWith("Mac")) Key.MetaLeft else Key.CtrlLeft
            keyDown(mod)
            pressKey(Key.V)
            keyUp(mod)
        }
}

@OptIn(ExperimentalComposeUiApi::class)
private class NetworksFakeClipboard(
    private val text: String,
) : Clipboard {
    override val nativeClipboard: NativeClipboard get() = error("unused")

    override suspend fun getClipEntry(): ClipEntry = ClipEntry(StringSelection(text))

    override suspend fun setClipEntry(clipEntry: ClipEntry?) {}
}
