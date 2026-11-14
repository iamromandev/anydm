package dev.anydm.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.NativeClipboard
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import java.awt.datatransfer.StringSelection
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class NetworkSettingsTest {
    private fun coloured(
        wifi: NetworkColor? = null,
        usb: NetworkColor? = null,
    ): HomeState {
        val s = populatedSample()
        return s.copy(networks = listOf(s.networks[0].copy(color = wifi), s.networks[1].copy(color = usb)))
    }

    private fun ComposeUiTest.home(
        state: HomeState = populatedSample(),
        dark: Boolean = true,
    ) = setContent { HomeScreen(state, dark) }

    private fun ComposeUiTest.dot(
        tag: String,
        color: String,
    ) = onNodeWithTag(tag, useUnmergedTree = true).assert(
        SemanticsMatcher("state description is $color") { it.config.getOrNull(SemanticsProperties.StateDescription) == color },
    )

    @Test
    fun topBarChipDrawsOneDotPerEnabledNetworkInItsColor() =
        runComposeUiTest {
            home(coloured(NetworkColor.Orange, NetworkColor.Pink))
            dot("chip-dot-wifi", "Orange")
            dot("chip-dot-usb", "Pink")
        }

    @Test
    fun topBarChipDotsFallBackToDefaultColors() =
        runComposeUiTest {
            home()
            dot("chip-dot-wifi", "Default")
            dot("chip-dot-usb", "Default")
        }

    @Test
    fun menuDotsUseTheChosenColor() =
        runComposeUiTest {
            home(coloured(usb = NetworkColor.Orange))
            onNodeWithTag("network-chip").performClick()
            dot("network-color-usb", "Orange")
            dot("network-color-wifi", "Default")
        }

    @Test
    fun newDownloadChipsUseTheChosenColor() =
        runComposeUiTest {
            home(coloured(usb = NetworkColor.Teal))
            onNodeWithTag("new-download").performClick()
            dot("pill-dot-usb", "Teal")
            dot("pill-dot-wifi", "Default")
        }

    @Test
    fun limitsSidebarDotsUseTheChosenColor() =
        runComposeUiTest {
            home(coloured(usb = NetworkColor.Yellow))
            onNodeWithTag("network-chip").performClick()
            onNodeWithTag("networks-limits-link").performClick()
            dot("limits-dot-usb", "Yellow")
        }

    private fun ComposeUiTest.openMenu() = onNodeWithTag("network-chip").performClick()

    private fun ComposeUiTest.openPage(id: String = "usb") {
        openMenu()
        onNodeWithTag("network-chevron-$id").performClick()
    }

    private fun ComposeUiTest.esc() = onNodeWithTag("networks-menu").performKeyInput { pressKey(Key.Escape) }

    private fun ComposeUiTest.listShown() = onNodeWithTag("network-row-usb").assertIsDisplayed()

    private fun ComposeUiTest.pageGone() = onNodeWithTag("network-page").assertDoesNotExist()

    private fun ComposeUiTest.menuGone() = onNodeWithTag("networks-menu").assertDoesNotExist()

    private fun ComposeUiTest.swatch(color: NetworkColor) = onNodeWithTag("swatch-${color.name.lowercase()}")

    private fun ComposeUiTest.rename(text: String) {
        onNodeWithTag("network-page-rename").performClick()
        onNodeWithTag("network-rename-field").performTextReplacement(text)
        onNodeWithTag("network-rename-field").performKeyInput { pressKey(Key.Enter) }
    }

    @Test
    fun chevronOpensThePageAndHidesTheList() =
        runComposeUiTest {
            home()
            openPage()
            onNodeWithTag("network-page").assertIsDisplayed()
            onNodeWithTag("network-page-title", useUnmergedTree = true).assertTextEquals("USB Tethering")
            onNodeWithTag("network-page-name", useUnmergedTree = true).assertTextEquals("USB Tethering")
            onNodeWithTag("network-page-limits").assertIsDisplayed()
            onNodeWithTag("network-page-proxy-summary", useUnmergedTree = true).assertTextEquals("None")
            for (k in NetworkColor.entries) swatch(k).assertIsDisplayed()
            onNodeWithTag("network-row-usb").assertDoesNotExist()
            onNodeWithTag("networks-limits-link").assertDoesNotExist()
        }

    @Test
    fun backControlReturnsToTheList() =
        runComposeUiTest {
            home()
            openPage()
            onNodeWithContentDescription("Back to networks").performClick()
            pageGone()
            listShown()
        }

    @Test
    fun escapeGoesBackThenClosesTheMenu() =
        runComposeUiTest {
            home()
            openPage()
            esc()
            pageGone()
            listShown()
            esc()
            menuGone()
        }

    @Test
    fun clickingOutsideClosesTheMenuFromThePage() =
        runComposeUiTest {
            home()
            openPage()
            onNodeWithTag("slow-mode").performClick()
            menuGone()
        }

    @Test
    fun renameOnThePageUpdatesTitleListAndNewDownloadChip() =
        runComposeUiTest {
            home()
            openPage()
            rename("Phone")
            onNodeWithTag("network-page-title", useUnmergedTree = true).assertTextEquals("Phone")
            onNodeWithTag("network-page-name", useUnmergedTree = true).assertTextEquals("Phone")
            onNodeWithContentDescription("Back to networks").performClick()
            onNodeWithTag("network-name-usb", useUnmergedTree = true).assertTextEquals("Phone")
            onNodeWithTag("slow-mode").performClick()
            onNodeWithTag("new-download").performClick()
            onNodeWithTag("network-Phone").assertIsDisplayed()
        }

    @Test
    fun duplicateNameIsRejectedOnThePage() =
        runComposeUiTest {
            home()
            openPage()
            rename("wi-fi")
            onNodeWithTag("network-rename-field").assertIsDisplayed()
            onNodeWithTag("network-page-name", useUnmergedTree = true).assertDoesNotExist()
        }

    @Test
    fun emptyNameIsRejectedOnThePage() =
        runComposeUiTest {
            home()
            openPage()
            rename("   ")
            onNodeWithTag("network-rename-field").assertIsDisplayed()
        }

    @Test
    fun escapeWhileRenamingCancelsOnlyTheEdit() =
        runComposeUiTest {
            home()
            openPage()
            onNodeWithTag("network-page-rename").performClick()
            onNodeWithTag("network-rename-field").performTextReplacement("Zed")
            onNodeWithTag("network-rename-field").performKeyInput { pressKey(Key.Escape) }
            onNodeWithTag("network-page").assertIsDisplayed()
            onNodeWithTag("network-page-name", useUnmergedTree = true).assertTextEquals("USB Tethering")
            esc()
            pageGone()
            listShown()
        }

    @Test
    fun pickingASwatchMarksItAndRecolorsEverywhere() =
        runComposeUiTest {
            home()
            openPage()
            swatch(NetworkColor.Orange).assertIsNotSelected()
            swatch(NetworkColor.Orange).performClick()
            swatch(NetworkColor.Orange).assertIsSelected()
            swatch(NetworkColor.Pink).assertIsNotSelected()
            dot("chip-dot-usb", "Orange")
            dot("chip-dot-wifi", "Default")
            onNodeWithContentDescription("Back to networks").performClick()
            dot("network-color-usb", "Orange")
            onNodeWithTag("networks-limits-link").performClick()
            dot("limits-dot-usb", "Orange")
        }

    @Test
    fun swatchesAreRadioButtonsWithColorNames() =
        runComposeUiTest {
            home()
            openPage()
            for (k in NetworkColor.entries) {
                onNodeWithContentDescription(k.label).assert(
                    SemanticsMatcher("is a radio button") {
                        it.config.getOrNull(SemanticsProperties.Role) ==
                            androidx.compose.ui.semantics.Role.RadioButton
                    },
                )
            }
            assertTrue(onAllNodesWithTag("swatch-orange").fetchSemanticsNodes().size == 1)
        }

    @Test
    fun noSwatchIsMarkedWhenNoColorWasChosen() =
        runComposeUiTest {
            home()
            for (id in listOf("wifi", "usb")) {
                onNodeWithTag("network-chevron-$id").let { if (id == "wifi") openPage("wifi") else it.performClick() }
                for (k in NetworkColor.entries) swatch(k).assertIsNotSelected()
                onNodeWithTag("network-page-color-default", useUnmergedTree = true).assertTextEquals("Default")
                onNodeWithContentDescription("Back to networks").performClick()
            }
        }

    @Test
    fun theDefaultHintGoesAwayOnceAColorIsChosen() =
        runComposeUiTest {
            home()
            openPage()
            swatch(NetworkColor.Teal).performClick()
            swatch(NetworkColor.Teal).assertIsSelected()
            onNodeWithTag("network-page-color-default", useUnmergedTree = true).assertDoesNotExist()
        }

    @Test
    fun anotherNetworksPageShowsItsOwnValues() =
        runComposeUiTest {
            home(coloured(wifi = NetworkColor.Pink, usb = NetworkColor.Teal))
            openPage("wifi")
            onNodeWithTag("network-page-name", useUnmergedTree = true).assertTextEquals("Wi-Fi")
            swatch(NetworkColor.Pink).assertIsSelected()
            onNodeWithContentDescription("Back to networks").performClick()
            onNodeWithTag("network-chevron-usb").performClick()
            onNodeWithTag("network-page-name", useUnmergedTree = true).assertTextEquals("USB Tethering")
            swatch(NetworkColor.Teal).assertIsSelected()
            swatch(NetworkColor.Pink).assertIsNotSelected()
        }

    @Test
    fun limitsLinkClosesTheMenuAndOpensThatNetworksPane() =
        runComposeUiTest {
            home()
            openPage()
            onNodeWithTag("network-page-limits").performClick()
            menuGone()
            onNodeWithTag("settings-nav-speeddata").assertIsSelected()
            onNodeWithTag("limits-editor").assertIsDisplayed()
            onNodeWithTag("limits-nav-usb").assertIsSelected()
        }

    @Test
    fun listLimitsLinkStillOpensTheGeneralPane() =
        runComposeUiTest {
            home()
            openMenu()
            onNodeWithTag("networks-limits-link").performClick()
            onNodeWithTag("limits-nav-$GENERAL_PANE").assertIsSelected()
        }

    @Test
    fun openingNewDownloadFromThePageClosesTheMenu() =
        runComposeUiTest {
            home()
            openPage()
            onNodeWithTag("new-download").performClick()
            menuGone()
            onNodeWithTag("new-download-dialog").assertIsDisplayed()
        }

    private fun shortWindow(
        width: Int,
        height: Int,
    ) = runDesktopComposeUiTest(width, height) {
        setContent { HomeScreen(populatedSample(), true) }
        openPage()
        val b = onNodeWithTag("networks-menu").fetchSemanticsNode().boundsInRoot
        assertTrue(b.left >= 0f && b.top >= 0f, "menu starts inside the window: $b")
        assertTrue(b.right <= width && b.bottom <= height, "menu ends inside the window: $b")
        val chip = onNodeWithTag("network-chip").fetchSemanticsNode().boundsInRoot
        assertTrue(b.top >= chip.bottom, "menu sits below the chip")
    }

    @Test
    fun pageStaysInsideTheWindowAt900x600() = shortWindow(900, 600)

    @Test
    fun pageStaysInsideTheWindowAt1280x800() = shortWindow(1280, 800)

    @Test
    fun pageIsCappedAndScrollsInAVeryShortWindow() {
        shortWindow(900, 260)
        runDesktopComposeUiTest(900, 260) {
            setContent { HomeScreen(populatedSample(), true) }
            openPage()
            onNodeWithTag("network-page-limits").performScrollTo()
            onNodeWithTag("network-page-limits").assertIsDisplayed()
        }
    }

    // ---- Proxy editor on the page ----

    private val secret = "hunter2xyz"

    private fun ComposeUiTest.editProxy() = onNodeWithTag("network-page-proxy-edit").performClick()

    private fun ComposeUiTest.proxyType(
        type: String,
        p: String = "page",
    ) = onNodeWithTag("proxy-$p-type-$type").performClick()

    private fun ComposeUiTest.field(
        name: String,
        text: String,
        p: String = "page",
    ) = onNodeWithTag("proxy-$p-$name").performTextReplacement(text)

    private fun ComposeUiTest.fillProxy(
        host: String,
        port: String,
        user: String = "",
        password: String = "",
        p: String = "page",
    ) {
        field("host", host, p)
        field("port", port, p)
        if (user.isNotEmpty()) field("user", user, p)
        if (password.isNotEmpty()) typePassword(password, p)
    }

    /** The hidden field exposes only SetText, so this drives that action rather than text input. */
    private fun ComposeUiTest.typePassword(
        text: String,
        p: String = "page",
    ) {
        onNodeWithTag("proxy-$p-password").performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString(text)) }
    }

    private fun ComposeUiTest.save(p: String = "page") = onNodeWithTag("proxy-$p-save").performClick()

    private fun ComposeUiTest.summary(text: String) =
        onNodeWithTag("network-page-proxy-summary", useUnmergedTree = true).assertTextEquals(text)

    private fun ComposeUiTest.saveSocks() {
        editProxy()
        proxyType("socks5")
        fillProxy("127.0.0.1", "1080", "alice")
        save()
    }

    private fun leaks(
        node: SemanticsNode,
        value: String,
    ): Boolean = node.config.any { it.value.toString().contains(value) } || node.children.any { leaks(it, value) }

    private fun ComposeUiTest.treeHas(value: String) = leaks(onRoot(useUnmergedTree = true).fetchSemanticsNode(), value)

    @Test
    fun theProxySectionShowsNoneAndAnEditButton() =
        runComposeUiTest {
            home()
            openPage()
            summary("None")
            onNodeWithTag("network-page-proxy-edit").assertIsDisplayed()
            onNodeWithTag("proxy-page-type-none").assertDoesNotExist()
        }

    @Test
    fun hostPortUserAndPasswordAreHiddenForNoneAndShownForHttp() =
        runComposeUiTest {
            home()
            openPage()
            editProxy()
            onNodeWithTag("proxy-page-type-none").assertIsSelected()
            for (f in listOf("host", "port", "user", "password")) onNodeWithTag("proxy-page-$f").assertDoesNotExist()
            proxyType("http")
            onNodeWithTag("proxy-page-type-http").assertIsSelected()
            for (f in listOf("host", "port", "user", "password")) onNodeWithTag("proxy-page-$f").assertIsDisplayed()
            proxyType("none")
            onNodeWithTag("proxy-page-host").assertDoesNotExist()
        }

    @Test
    fun invalidHostsAndPortsShowAnErrorAndDisableSave() =
        runComposeUiTest {
            home()
            openPage()
            editProxy()
            proxyType("http")
            onNodeWithTag("proxy-page-save").assertIsNotEnabled()
            for (host in listOf("my proxy", "http://x")) {
                fillProxy(host, "8080")
                onNodeWithTag("proxy-page-error-host", useUnmergedTree = true).assertTextEquals("Enter a host name or IPv4 address")
                onNodeWithTag("proxy-page-save").assertIsNotEnabled()
            }
            for (port in listOf("0", "70000")) {
                fillProxy("10.0.0.2", port)
                onNodeWithTag("proxy-page-error-port", useUnmergedTree = true).assertTextEquals("Port must be 1\u201365535")
                onNodeWithTag("proxy-page-save").assertIsNotEnabled()
            }
            fillProxy("10.0.0.2", "8080")
            onNodeWithTag("proxy-page-error-host", useUnmergedTree = true).assertDoesNotExist()
            onNodeWithTag("proxy-page-error-port", useUnmergedTree = true).assertDoesNotExist()
            onNodeWithTag("proxy-page-save").assertIsEnabled()
        }

    @Test
    fun aPasswordWithoutAUsernameIsAnErrorUntilAUsernameIsGiven() =
        runComposeUiTest {
            home()
            openPage()
            editProxy()
            proxyType("http")
            fillProxy("10.0.0.2", "8080", password = secret)
            onNodeWithTag("proxy-page-error-auth", useUnmergedTree = true).assertTextEquals("Add a username for this password")
            onNodeWithTag("proxy-page-save").assertIsNotEnabled()
            field("user", "alice")
            onNodeWithTag("proxy-page-error-auth", useUnmergedTree = true).assertDoesNotExist()
            onNodeWithTag("proxy-page-save").assertIsEnabled()
        }

    @Test
    fun savingShowsTheSummaryAndClosesTheEditor() =
        runComposeUiTest {
            home()
            openPage()
            saveSocks()
            summary("SOCKS5 alice@127.0.0.1:1080")
            onNodeWithTag("proxy-page-save").assertDoesNotExist()
        }

    @Test
    fun cancelDiscardsTheEditsAndKeepsTheSavedValue() =
        runComposeUiTest {
            home()
            openPage()
            saveSocks()
            editProxy()
            onNodeWithTag("proxy-page-host").assertTextEquals("127.0.0.1", includeEditableText = true)
            field("host", "9.9.9.9")
            onNodeWithTag("proxy-page-cancel").performClick()
            summary("SOCKS5 alice@127.0.0.1:1080")
            editProxy()
            onNodeWithTag("proxy-page-host").assertTextEquals("127.0.0.1", includeEditableText = true)
        }

    @Test
    fun goingBackDiscardsTheEdits() =
        runComposeUiTest {
            home()
            openPage()
            saveSocks()
            editProxy()
            field("host", "9.9.9.9")
            onNodeWithContentDescription("Back to networks").performClick()
            onNodeWithTag("network-chevron-usb").performClick()
            summary("SOCKS5 alice@127.0.0.1:1080")
            editProxy()
            onNodeWithTag("proxy-page-host").assertTextEquals("127.0.0.1", includeEditableText = true)
        }

    @Test
    fun savingNoneClearsTheProxyAndTheNextEditStartsBlank() =
        runComposeUiTest {
            home()
            openPage()
            saveSocks()
            editProxy()
            proxyType("none")
            save()
            summary("None")
            editProxy()
            proxyType("http")
            onNodeWithTag("proxy-page-host").assertTextEquals("", includeEditableText = true)
            onNodeWithTag("proxy-page-port").assertTextEquals("", includeEditableText = true)
        }

    @Test
    fun anotherNetworksProxyDraftIsNotCarriedOver() =
        runComposeUiTest {
            home()
            openPage("wifi")
            editProxy()
            proxyType("http")
            fillProxy("10.1.1.1", "3128")
            onNodeWithContentDescription("Back to networks").performClick()
            onNodeWithTag("network-chevron-usb").performClick()
            summary("None")
            onNodeWithTag("proxy-page-host").assertDoesNotExist()
            onNodeWithContentDescription("Back to networks").performClick()
            onNodeWithTag("network-chevron-wifi").performClick()
            summary("None")
        }

    @Test
    fun renamingKeepsTheProxyDraft() =
        runComposeUiTest {
            home()
            openPage()
            editProxy()
            proxyType("http")
            fillProxy("10.1.1.1", "3128")
            rename("Phone")
            onNodeWithTag("network-page-title", useUnmergedTree = true).assertTextEquals("Phone")
            onNodeWithTag("proxy-page-host").assertTextEquals("10.1.1.1", includeEditableText = true)
            save()
            summary("HTTP 10.1.1.1:3128")
        }

    @Test
    fun theHiddenPasswordNeverAppearsInTheSemanticsTree() =
        runComposeUiTest {
            home()
            openPage()
            editProxy()
            proxyType("http")
            fillProxy("10.0.0.2", "8080", "alice", secret)
            onNodeWithContentDescription("Password, hidden").assertIsDisplayed()
            assertTrue(!treeHas(secret), "the typed password must not be in any semantics node while hidden")
            assertTrue(treeHas("10.0.0.2"), "the tree check does see ordinary field values")
        }

    @Test
    fun showRevealsThePasswordAndHideMasksItAgain() =
        runComposeUiTest {
            home()
            openPage()
            editProxy()
            proxyType("http")
            fillProxy("10.0.0.2", "8080", "alice", secret)
            onNodeWithContentDescription("Show password").performClick()
            onNodeWithContentDescription("Password").assertTextEquals(secret, includeEditableText = true)
            assertTrue(treeHas(secret))
            onNodeWithContentDescription("Hide password").performClick()
            onNodeWithContentDescription("Password, hidden").assertIsDisplayed()
            assertTrue(!treeHas(secret))
        }

    @Test
    fun theSummaryNeverIncludesThePassword() =
        runComposeUiTest {
            home()
            openPage()
            editProxy()
            proxyType("http")
            fillProxy("10.0.0.2", "8080", "alice", secret)
            save()
            summary("HTTP alice@10.0.0.2:8080")
            assertTrue(!treeHas(secret))
        }

    @Test
    fun aSavedPasswordIsStillMaskedWhenTheEditorReopens() =
        runComposeUiTest {
            home()
            openPage()
            editProxy()
            proxyType("http")
            fillProxy("10.0.0.2", "8080", "alice", secret)
            save()
            editProxy()
            onNodeWithContentDescription("Password, hidden").assertIsDisplayed()
            assertTrue(!treeHas(secret))
        }

    @Test
    fun proxyTypesAreRadioButtonsAndFieldsHaveLabels() =
        runComposeUiTest {
            home()
            openPage()
            editProxy()
            for (t in listOf("None", "HTTP", "SOCKS5")) {
                onNodeWithContentDescription(t).assert(
                    SemanticsMatcher("is a radio button") {
                        it.config.getOrNull(SemanticsProperties.Role) == androidx.compose.ui.semantics.Role.RadioButton
                    },
                )
            }
            proxyType("http")
            for (l in listOf("Host", "Port", "Username", "Password, hidden")) onNodeWithContentDescription(l).assertIsDisplayed()
        }

    private fun editorWindow(
        width: Int,
        height: Int,
    ) = runDesktopComposeUiTest(width, height) {
        setContent { HomeScreen(populatedSample(), true) }
        openPage()
        editProxy()
        proxyType("http")
        val b = onNodeWithTag("networks-menu").fetchSemanticsNode().boundsInRoot
        assertTrue(b.left >= 0f && b.top >= 0f && b.right <= width && b.bottom <= height, "menu with editor is inside the window: $b")
        onNodeWithTag("proxy-page-save").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun pageWithTheEditorStaysInsideTheWindowAt900x600() = editorWindow(900, 600)

    @Test
    fun pageWithTheEditorStaysInsideTheWindowAt1280x800() = editorWindow(1280, 800)

    // ---- New download proxy chip and popover ----

    private fun withProxy(proxy: Proxy): HomeState {
        val s = populatedSample()
        return s.copy(networks = listOf(s.networks[0], s.networks[1].copy(proxy = proxy)))
    }

    private fun ComposeUiTest.openNewDownload() = onNodeWithTag("new-download").performClick()

    private fun ComposeUiTest.openChip(id: String = "usb") {
        openNewDownload()
        onNodeWithTag("proxy-chip-$id").performClick()
    }

    private fun ComposeUiTest.chipText(
        id: String,
        text: String,
    ) = onNodeWithTag("proxy-chip-$id").assertTextEquals(text)

    @Test
    fun theProxyChipReadsProxyOrProxyOn() =
        runComposeUiTest {
            home(withProxy(Proxy(ProxyType.Http, "10.0.0.2", 8080)))
            openNewDownload()
            chipText("wifi", "\u270E Proxy")
            chipText("usb", "Proxy on")
        }

    @Test
    fun theProxyChipOpensAPopoverWithTheSavedValues() =
        runComposeUiTest {
            home(withProxy(Proxy(ProxyType.Socks5, "127.0.0.1", 1080, "alice", secret)))
            openChip()
            onNodeWithTag("proxy-popover").assertIsDisplayed()
            onNodeWithTag("proxy-chip-usb-type-socks5").assertIsSelected()
            onNodeWithTag("proxy-chip-usb-host").assertTextEquals("127.0.0.1", includeEditableText = true)
            onNodeWithTag("proxy-chip-usb-port").assertTextEquals("1080", includeEditableText = true)
            onNodeWithTag("proxy-chip-usb-user").assertTextEquals("alice", includeEditableText = true)
            assertTrue(!treeHas(secret))
        }

    @Test
    fun savingFromThePopoverUpdatesTheChipAndTheSettingsPage() =
        runComposeUiTest {
            home()
            openChip()
            proxyType("socks5", "chip-usb")
            fillProxy("127.0.0.1", "1080", "alice", p = "chip-usb")
            save("chip-usb")
            onNodeWithTag("proxy-popover").assertDoesNotExist()
            onNodeWithTag("new-download-dialog").assertIsDisplayed()
            chipText("usb", "Proxy on")
            chipText("wifi", "\u270E Proxy")
            onNodeWithTag("cancel").performClick()
            openPage()
            summary("SOCKS5 alice@127.0.0.1:1080")
        }

    @Test
    fun cancelInThePopoverDiscardsTheEdits() =
        runComposeUiTest {
            home()
            openChip()
            proxyType("http", "chip-usb")
            fillProxy("10.0.0.2", "8080", p = "chip-usb")
            onNodeWithTag("proxy-chip-usb-cancel").performClick()
            onNodeWithTag("proxy-popover").assertDoesNotExist()
            chipText("usb", "\u270E Proxy")
            onNodeWithTag("proxy-chip-usb").performClick()
            onNodeWithTag("proxy-chip-usb-type-none").assertIsSelected()
        }

    @Test
    fun escapeClosesOnlyThePopoverThenTheDialog() =
        runComposeUiTest {
            home()
            openChip()
            proxyType("http", "chip-usb")
            escDialog()
            onNodeWithTag("proxy-popover").assertDoesNotExist()
            onNodeWithTag("new-download-dialog").assertIsDisplayed()
            chipText("usb", "\u270E Proxy")
            escDialog()
            onNodeWithTag("new-download-dialog").assertDoesNotExist()
        }

    private fun ComposeUiTest.escDialog() = onRoot().performKeyInput { pressKey(Key.Escape) }

    @Test
    fun clickingOutsideThePopoverClosesOnlyThePopover() =
        runComposeUiTest {
            home()
            openChip()
            onNodeWithTag("dialog-title").performClick()
            onNodeWithTag("proxy-popover").assertDoesNotExist()
            onNodeWithTag("new-download-dialog").assertIsDisplayed()
        }

    @Test
    fun clickingInsideThePopoverKeepsItOpen() =
        runComposeUiTest {
            home()
            openChip()
            onNodeWithTag("proxy-popover").performClick()
            onNodeWithTag("proxy-popover").assertIsDisplayed()
        }

    @Test
    fun anotherPillsChipSwitchesThePopover() =
        runComposeUiTest {
            home()
            openChip("usb")
            onNodeWithTag("proxy-popover").assertIsDisplayed()
            escDialog()
            onNodeWithTag("proxy-chip-wifi").performClick()
            onNodeWithTag("proxy-chip-wifi-type-none").assertIsSelected()
        }

    private fun popoverBounds(
        width: Int,
        height: Int,
        flipped: Boolean? = null,
    ) = runDesktopComposeUiTest(width, height) {
        setContent { HomeScreen(populatedSample(), true) }
        openNewDownload()
        onNodeWithTag(
            "url-field",
        ).performTextInput("magnet:?xt=urn:btih:08ada5a7a6183aae1e09d831df6748d566095a10&dn=Sintel&tr=udp%3A%2F%2Ftracker.example%3A1337")
        onNodeWithTag("proxy-chip-usb").performClick()
        proxyType("http", "chip-usb")
        val chip = onNodeWithTag("proxy-chip-usb").fetchSemanticsNode().boundsInRoot
        val pop = onNodeWithTag("proxy-popover").fetchSemanticsNode().boundsInRoot
        assertTrue(pop.left >= 0f && pop.top >= 0f && pop.right <= width && pop.bottom <= height, "popover inside window: $pop")
        assertTrue(pop.bottom <= chip.top || pop.top >= chip.bottom, "popover must not cover its chip: popover $pop chip $chip")
        if (flipped == true) assertTrue(pop.bottom <= chip.top, "popover flips above the chip: popover $pop chip $chip")
        if (flipped == false) assertTrue(pop.top >= chip.bottom, "popover opens below the chip: popover $pop chip $chip")
        onNodeWithTag("proxy-chip-usb-save").assertExists()
    }

    @Test
    fun popoverStaysInsideTheWindowAndClearOfItsChipAt900x600() = popoverBounds(900, 600)

    @Test
    fun popoverStaysInsideTheWindowAndClearOfItsChipAt1280x800() = popoverBounds(1280, 800)

    @Test
    fun popoverFlipsAboveItsChipWhenThereIsNoRoomBelow() = popoverBounds(900, 600, flipped = true)

    @Test
    fun popoverOpensBelowItsChipWhenThereIsRoom() = popoverBounds(1280, 1100, flipped = false)

    // ---- Paste, focus and keyboard ----

    private fun ComposeUiTest.pasteHome(clip: String = "pasted.example") =
        setContent {
            CompositionLocalProvider(LocalClipboard provides SettingsFakeClipboard(clip)) {
                HomeScreen(populatedSample(), true, clipboardText = { "https://other.test/b.zip" })
            }
        }

    private fun ComposeUiTest.paste() =
        onRoot().performKeyInput {
            val mod = if (System.getProperty("os.name").startsWith("Mac")) Key.MetaLeft else Key.CtrlLeft
            keyDown(mod)
            pressKey(Key.V)
            keyUp(mod)
        }

    private fun ComposeUiTest.openHttpEditor() {
        openPage()
        editProxy()
        proxyType("http")
    }

    @Test
    fun pasteGoesIntoTheFocusedProxyFieldsNotTheNewDownloadDialog() {
        for (f in listOf("host", "user")) {
            runComposeUiTest {
                pasteHome()
                openHttpEditor()
                onNodeWithTag("proxy-page-$f").performClick()
                paste()
                waitForIdle()
                onNodeWithTag("new-download-dialog").assertDoesNotExist()
                onNodeWithTag("networks-menu").assertIsDisplayed()
                onNodeWithTag("proxy-page-$f").assertTextEquals("pasted.example", includeEditableText = true)
            }
        }
    }

    @Test
    fun pasteGoesIntoTheHiddenPasswordField() =
        runComposeUiTest {
            pasteHome(secret)
            openHttpEditor()
            onNodeWithTag("proxy-page-password").performClick()
            paste()
            waitForIdle()
            onNodeWithTag("new-download-dialog").assertDoesNotExist()
            onNodeWithContentDescription("Show password").performClick()
            onNodeWithContentDescription("Password").assertTextEquals(secret, includeEditableText = true)
        }

    @Test
    fun pasteWithTheEditorOpenButNoFieldFocusedStillOpensNewDownload() =
        runComposeUiTest {
            pasteHome()
            openHttpEditor()
            paste()
            menuGone()
            onNodeWithTag("new-download-dialog").assertIsDisplayed()
        }

    @Test
    fun pasteOnThePageWithNoFieldFocusedStillOpensNewDownload() =
        runComposeUiTest {
            pasteHome()
            openPage()
            paste()
            menuGone()
            onNodeWithTag("new-download-dialog").assertIsDisplayed()
        }

    @Test
    fun pasteReturnsToNewDownloadAfterTheFocusedFieldIsRemoved() =
        runComposeUiTest {
            pasteHome()
            openHttpEditor()
            onNodeWithTag("proxy-page-host").performClick()
            proxyType("none")
            onNodeWithTag("proxy-page-save").performClick()
            waitForIdle()
            paste()
            menuGone()
            onNodeWithTag("new-download-dialog").assertIsDisplayed()
        }

    /** Real text input: AWT KEY_TYPED events sent to the focused field through the scene, as the OS would. */
    @OptIn(InternalComposeUiApi::class)
    @Test
    fun typingRealCharactersIntoTheHiddenPasswordFieldReachesTheValue() =
        runDesktopComposeUiTest {
            home()
            openHttpEditor()
            onNodeWithTag("proxy-page-password").performClick()
            val source = java.awt.Canvas()
            for (ch in "hunter2") {
                val awt =
                    java.awt.event.KeyEvent(
                        source,
                        java.awt.event.KeyEvent.KEY_TYPED,
                        0L,
                        0,
                        java.awt.event.KeyEvent.VK_UNDEFINED,
                        ch,
                    )
                scene.sendKeyEvent(
                    androidx.compose.ui.input.key
                        .KeyEvent(Key.Unknown, KeyEventType.Unknown, ch.code, nativeEvent = awt),
                )
            }
            waitForIdle()
            assertTrue(!treeHas("hunter2"), "typed password must not leak while hidden")
            onNodeWithContentDescription("Show password").performClick()
            onNodeWithContentDescription("Password").assertTextEquals("hunter2", includeEditableText = true)
            onNodeWithContentDescription("Hide password").performClick()
            assertTrue(!treeHas("hunter2"))
            onNodeWithContentDescription("Show password").performClick()
            onNodeWithContentDescription("Password").assertTextEquals("hunter2", includeEditableText = true)
        }

    private fun ComposeUiTest.editProxyThenEsc() = onNodeWithTag("networks-menu").performKeyInput { pressKey(Key.Escape) }

    @Test
    fun escAfterClickingEditGoesBackToTheList() =
        runComposeUiTest {
            home()
            openPage()
            editProxy()
            editProxyThenEsc()
            pageGone()
            listShown()
        }

    @Test
    fun escAfterCancellingTheEditorGoesBackToTheList() =
        runComposeUiTest {
            home()
            openPage()
            editProxy()
            onNodeWithTag("proxy-page-cancel").performClick()
            editProxyThenEsc()
            pageGone()
            listShown()
        }

    @Test
    fun escAfterSavingTheEditorGoesBackToTheList() =
        runComposeUiTest {
            home()
            openPage()
            editProxy()
            proxyType("http")
            fillProxy("10.0.0.2", "8080")
            save()
            editProxyThenEsc()
            pageGone()
            listShown()
        }

    @Test
    fun theNetworksChipIsNotReachableWhileADetailIsOpen() =
        runComposeUiTest {
            home()
            openMenu()
            onNodeWithTag("network-chip").assertIsDisplayed()
            onNodeWithTag("chevron-1").performClick()
            onNodeWithTag("network-chip").assertDoesNotExist()
        }

    private fun windowWithHttpEditor(
        width: Int,
        height: Int,
        withErrors: Boolean,
    ) = runDesktopComposeUiTest(width, height) {
        setContent { HomeScreen(populatedSample(), true) }
        openPage()
        editProxy()
        proxyType("http")
        if (withErrors) fillProxy("bad host", "0", "", secret)
        // boundsInRoot is clipped by the scroll area, so a half-hidden button would pass; use position + full size.
        val menu = onNodeWithTag("networks-menu").fetchSemanticsNode().boundsInRoot
        val node = onNodeWithTag("proxy-page-save").fetchSemanticsNode()
        val top = node.positionInRoot.y
        val bottom = top + node.size.height
        assertTrue(menu.bottom <= height, "menu is inside the window: $menu")
        assertTrue(top >= menu.top && bottom <= menu.bottom, "Save ($top..$bottom) is fully inside the menu ($menu) before any scroll")
    }

    @Test
    fun saveIsVisibleWithoutScrollingAt900x600() = windowWithHttpEditor(900, 600, withErrors = false)

    @Test
    fun saveIsVisibleWithoutScrollingAt900x600WithErrorRows() = windowWithHttpEditor(900, 600, withErrors = true)

    @Test
    fun anEmptyHostAndPortShowRequiredOnceTouched() =
        runComposeUiTest {
            home()
            openPage()
            editProxy()
            proxyType("http")
            onNodeWithTag("proxy-page-error-host", useUnmergedTree = true).assertDoesNotExist()
            onNodeWithTag("proxy-page-error-port", useUnmergedTree = true).assertDoesNotExist()
            onNodeWithTag("proxy-page-host").performClick()
            onNodeWithTag("proxy-page-port").performClick()
            onNodeWithTag("proxy-page-error-host", useUnmergedTree = true).assertTextEquals("Required")
            onNodeWithTag("proxy-page-error-port", useUnmergedTree = true).assertDoesNotExist()
            onNodeWithTag("proxy-page-user").performClick()
            onNodeWithTag("proxy-page-error-port", useUnmergedTree = true).assertTextEquals("Required")
            onNodeWithTag("proxy-page-save").assertIsNotEnabled()
        }

    @Test
    fun clearingATypedHostShowsRequired() =
        runComposeUiTest {
            home()
            openPage()
            editProxy()
            proxyType("http")
            field("port", "8080")
            field("host", "x")
            onNodeWithTag("proxy-page-error-host", useUnmergedTree = true).assertDoesNotExist()
            field("host", "")
            onNodeWithTag("proxy-page-error-host", useUnmergedTree = true).assertTextEquals("Required")
            onNodeWithTag("proxy-page-error-port", useUnmergedTree = true).assertDoesNotExist()
        }

    @Test
    fun everyProxyFieldShowsAVisibleLabelEvenWhenFilled() =
        runComposeUiTest {
            home()
            openPage()
            editProxy()
            proxyType("http")
            field("host", "http://x")
            for ((name, label) in listOf("host" to "Host", "port" to "Port", "user" to "Username", "password" to "Password")) {
                onNodeWithTag("proxy-page-$name-label", useUnmergedTree = true).assertIsDisplayed().assertTextEquals(label)
            }
        }

    @Test
    fun tabStaysInsideTheProxyPopover() =
        runComposeUiTest {
            home()
            openChip()
            proxyType("http", "chip-usb")
            onNodeWithTag("proxy-chip-usb-host").performClick()
            val seen = mutableSetOf<String>()
            repeat(20) { n ->
                // Tab forwards past the last control, then Shift+Tab backwards past the first.
                onRoot().performKeyInput {
                    if (n >= 6) keyDown(Key.ShiftLeft)
                    pressKey(Key.Tab)
                    if (n >= 6) keyUp(Key.ShiftLeft)
                }
                onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Focused, true))
                    .fetchSemanticsNodes()
                    .mapNotNullTo(seen) { it.config.getOrNull(SemanticsProperties.TestTag) }
            }
            assertTrue(
                seen.isNotEmpty() && seen.all { it == "proxy-popover" || it.startsWith("proxy-chip-usb-") },
                "Tab left the popover: $seen",
            )
            onNodeWithTag("proxy-popover").assertIsDisplayed()
        }
}

@OptIn(ExperimentalComposeUiApi::class)
private class SettingsFakeClipboard(
    private val text: String,
) : Clipboard {
    override val nativeClipboard: NativeClipboard get() = error("unused")

    override suspend fun getClipEntry(): ClipEntry = ClipEntry(StringSelection(text))

    override suspend fun setClipEntry(clipEntry: ClipEntry?) {}
}
