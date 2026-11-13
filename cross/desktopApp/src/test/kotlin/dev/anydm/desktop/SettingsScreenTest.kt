package dev.anydm.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.anydm.engine.torrent.TorrentResult
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/** Never opens an OS dialog and never touches a keychain or the real home: the picker and the files are fakes. */
private class SettingsPicker(
    var folder: String? = null,
) : FilePicker {
    var lastCurrent: String? = null

    override fun pickTorrent(): Path? = null

    override fun pickFolder(current: String): String? {
        lastCurrent = current
        return folder
    }
}

@OptIn(ExperimentalTestApi::class)
class SettingsScreenTest {
    private val picker = SettingsPicker()
    private val env =
        FilesEnv(
            picker,
            object : TorrentLoader {
                override suspend fun load(path: Path): TorrentResult = TorrentResult.Err("Can't read file")

                override suspend fun bytes(path: Path): ByteArray? = null
            },
            "/home/test",
        )
    private var tick = 0L

    private fun ComposeUiTest.home(dark: Boolean = true) =
        setContent {
            HomeScreen(
                populatedSample(),
                dark,
                clipboardText = { null },
                files = env,
                bus =
                    CommandBus {
                        tick += 1_000
                        tick
                    },
                platform = Platform.Mac,
            )
        }

    private fun ComposeUiTest.press(key: Key) = onNodeWithTag("home-root").performKeyInput { pressKey(key) }

    private fun ComposeUiTest.modComma() =
        onNodeWithTag("home-root").performKeyInput {
            keyDown(Key.MetaLeft)
            pressKey(Key.Comma)
            keyUp(Key.MetaLeft)
        }

    private fun ComposeUiTest.nav(section: String) = onNodeWithTag("settings-nav-$section").performClick()

    private fun ComposeUiTest.themeIs(state: String) =
        assertEquals(state, onNodeWithTag("home-root").fetchSemanticsNode().config[SemanticsProperties.StateDescription])

    private fun ComposeUiTest.pageGone() = onNodeWithTag("settings-page").assertDoesNotExist()

    // AC1

    @Test
    fun modCommaOpensSettingsOnGeneralWithTheBackControl() =
        runComposeUiTest {
            home()
            modComma()
            onNodeWithTag("settings-page").assertIsDisplayed()
            onNodeWithText("‹ Downloads").assertIsDisplayed()
            onNodeWithTag("settings-nav-general").assertIsSelected()
            onNodeWithTag("settings-folder").assertIsDisplayed()
            onNodeWithTag("download-list").assertDoesNotExist()
        }

    @Test
    fun theSidebarListsTheFourSections() =
        runComposeUiTest {
            home()
            modComma()
            for (s in listOf("general", "appearance", "speeddata", "networks")) onNodeWithTag("settings-nav-$s").assertIsDisplayed()
        }

    @Test
    fun escapeReturnsToTheListKeepingSelectionAndFilter() =
        runComposeUiTest {
            home()
            onNodeWithTag("filter").performClick()
            onNodeWithTag("filter-downloading").performClick()
            onNodeWithTag("select-1").performClick()
            modComma()
            onNodeWithTag("selection-bar").assertDoesNotExist()
            press(Key.Escape)
            pageGone()
            onNodeWithTag("select-1").assertIsOn()
            onNodeWithTag("selection-bar").assertIsDisplayed()
            onNodeWithTag("row-2").assertDoesNotExist()
        }

    @Test
    fun theBackControlReturnsToTheList() =
        runComposeUiTest {
            home()
            onNodeWithTag("select-1").performClick()
            modComma()
            onNodeWithTag("settings-back").performClick()
            pageGone()
            onNodeWithTag("select-1").assertIsOn()
        }

    @Test
    fun listKeysDoNothingOnTheSettingsPage() =
        runComposeUiTest {
            home()
            modComma()
            press(Key.Spacebar)
            press(Key.Delete)
            onNodeWithTag("remove-dialog").assertDoesNotExist()
            onNodeWithTag("settings-page").assertIsDisplayed()
        }

    @Test
    fun theSettingsPageAndTheDetailViewExcludeEachOther() =
        runComposeUiTest {
            home()
            onNodeWithTag("chevron-1").performClick()
            onNodeWithTag("detail-back").assertIsDisplayed()
            modComma()
            onNodeWithTag("settings-page").assertIsDisplayed()
            onNodeWithTag("detail-back").assertDoesNotExist()
            press(Key.Escape)
            pageGone()
            onNodeWithTag("detail-back").assertDoesNotExist()
            onNodeWithTag("download-list").assertIsDisplayed()
        }

    // AC2

    @Test
    fun generalShowsTheDefaultFolderWithHomeAsTilde() =
        runComposeUiTest {
            home()
            modComma()
            onNodeWithTag("settings-folder").assertTextEquals("~/Downloads")
        }

    @Test
    fun changingTheFolderMakesTheNextNewDownloadProposeIt() =
        runComposeUiTest {
            home()
            modComma()
            picker.folder = "/home/test/Movies"
            onNodeWithTag("settings-folder-change").performClick()
            assertEquals("/home/test/Downloads", picker.lastCurrent)
            onNodeWithTag("settings-folder").assertTextEquals("~/Movies")
            press(Key.Escape)
            onNodeWithTag("new-download").performClick()
            onNodeWithTag("save-folder").assertTextEquals("~/Movies")
        }

    @Test
    fun cancellingThePickerKeepsTheFolder() =
        runComposeUiTest {
            home()
            modComma()
            picker.folder = null
            onNodeWithTag("settings-folder-change").performClick()
            onNodeWithTag("settings-folder").assertTextEquals("~/Downloads")
        }

    // AC3

    @Test
    fun appearanceStartsOnSystemAndFollowsTheOsSetting() =
        runComposeUiTest {
            home(dark = false)
            themeIs("light")
            modComma()
            nav("appearance")
            onNodeWithTag("theme-system").assertIsSelected()
            themeIs("light")
        }

    @Test
    fun systemFollowsADarkOsSetting() =
        runComposeUiTest {
            home(dark = true)
            modComma()
            nav("appearance")
            onNodeWithTag("theme-system").assertIsSelected()
            themeIs("dark")
        }

    @Test
    fun choosingLightAndDarkOverridesTheOsSetting() =
        runComposeUiTest {
            home(dark = true)
            modComma()
            nav("appearance")
            onNodeWithTag("theme-light").performClick()
            themeIs("light")
            onNodeWithTag("theme-light").assertIsSelected()
            onNodeWithTag("theme-dark").performClick()
            themeIs("dark")
            onNodeWithTag("theme-system").performClick()
            themeIs("dark")
        }

    @Test
    fun systemTracksALaterChangeOfTheOsSetting() =
        runComposeUiTest {
            var os by mutableStateOf(true)
            setContent { HomeScreen(populatedSample(), os, files = env) }
            themeIs("dark")
            os = false
            waitForIdle()
            themeIs("light")
        }

    @Test
    fun theFooterToggleSetsAnExplicitThemeAndSettingsReflectsIt() =
        runComposeUiTest {
            home(dark = true)
            onNodeWithTag("theme-toggle").performClick()
            themeIs("light")
            modComma()
            nav("appearance")
            onNodeWithTag("theme-light").assertIsSelected()
            onNodeWithTag("theme-toggle").performClick()
            themeIs("dark")
            onNodeWithTag("theme-dark").assertIsSelected()
        }

    @Test
    fun theUnitRadiosAndTheFooterStayInSync() =
        runComposeUiTest {
            home()
            modComma()
            nav("appearance")
            onNodeWithTag("settings-unit-mbps-bytes").assertIsSelected()
            onNodeWithTag("settings-unit-mbps").performClick()
            onNodeWithTag("settings-unit-mbps").assertIsSelected()
            onNodeWithTag("unit-mbps").assert(hasContentDescription("Mbps selected"))
            onNodeWithTag("unit-mbps-bytes").performClick()
            onNodeWithTag("settings-unit-mbps-bytes").assertIsSelected()
        }

    // AC4

    @Test
    fun speedAndDataShowsTheEditorOnAllDownloads() =
        runComposeUiTest {
            home()
            modComma()
            nav("speeddata")
            onNodeWithTag("limits-editor").assertIsDisplayed()
            onNodeWithTag("limits-nav-general").assertIsSelected()
            onNodeWithTag("limits-nav-wifi").assertIsDisplayed()
            onNodeWithTag("limits-nav-usb").assertIsDisplayed()
            onNodeWithTag("total-none").assertIsSelected()
        }

    @Test
    fun theNetworksMenuLinkOpensSpeedAndDataOnAllDownloads() =
        runComposeUiTest {
            home()
            onNodeWithTag("network-chip").performClick()
            onNodeWithTag("networks-limits-link").performClick()
            onNodeWithTag("networks-menu").assertDoesNotExist()
            onNodeWithTag("settings-nav-speeddata").assertIsSelected()
            onNodeWithTag("limits-nav-general").assertIsSelected()
        }

    @Test
    fun aNetworkPageLinkOpensSpeedAndDataOnThatNetwork() =
        runComposeUiTest {
            home()
            onNodeWithTag("network-chip").performClick()
            onNodeWithTag("network-chevron-usb").performClick()
            onNodeWithTag("network-page-limits").performClick()
            onNodeWithTag("settings-nav-speeddata").assertIsSelected()
            onNodeWithTag("limits-nav-usb").assertIsSelected()
        }

    @Test
    fun saveAppliesTheDraftAndStaysOnThePage() =
        runComposeUiTest {
            home()
            modComma()
            nav("speeddata")
            onNodeWithTag("total-limit").performClick()
            onNodeWithTag("total-field").performTextClearance()
            onNodeWithTag("total-field").performTextInput("20")
            onNodeWithTag("limits-save").performClick()
            onNodeWithTag("settings-page").assertIsDisplayed()
            nav("general")
            nav("speeddata")
            onNodeWithTag("total-field").assertTextEquals("20")
        }

    @Test
    fun cancelDiscardsTheDraftAndSavesNothing() =
        runComposeUiTest {
            home()
            modComma()
            nav("speeddata")
            onNodeWithTag("total-limit").performClick()
            onNodeWithTag("total-field").performTextClearance()
            onNodeWithTag("total-field").performTextInput("20")
            onNodeWithTag("limits-cancel").performClick()
            onNodeWithTag("settings-page").assertIsDisplayed()
            onNodeWithTag("total-none").assertIsSelected()
            nav("general")
            nav("speeddata")
            onNodeWithTag("total-none").assertIsSelected()
        }

    // S5: the Speed & data draft belongs to the Settings page, not to the section being shown

    private fun ComposeUiTest.typeTotal(text: String) {
        onNodeWithTag("total-limit").performClick()
        onNodeWithTag("total-field").performTextClearance()
        onNodeWithTag("total-field").performTextInput(text)
    }

    @Test
    fun anUnsavedDraftSurvivesSwitchingToAnotherSectionAndBack() =
        runComposeUiTest {
            home()
            modComma()
            nav("speeddata")
            typeTotal("20")
            nav("appearance")
            nav("networks")
            nav("speeddata")
            onNodeWithTag("total-field").assertTextEquals("20")
        }

    @Test
    fun cancelAfterComingBackStillDiscardsTheDraft() =
        runComposeUiTest {
            home()
            modComma()
            nav("speeddata")
            typeTotal("20")
            nav("general")
            nav("speeddata")
            onNodeWithTag("limits-cancel").performClick()
            onNodeWithTag("total-none").assertIsSelected()
            nav("general")
            nav("speeddata")
            onNodeWithTag("total-none").assertIsSelected()
        }

    @Test
    fun leavingTheSettingsPageDiscardsTheUnsavedDraft() =
        runComposeUiTest {
            home()
            modComma()
            nav("speeddata")
            typeTotal("20")
            press(Key.Escape)
            pageGone()
            modComma()
            nav("speeddata")
            onNodeWithTag("total-none").assertIsSelected()
        }

    @Test
    fun theBackControlAlsoDiscardsTheUnsavedDraft() =
        runComposeUiTest {
            home()
            modComma()
            nav("speeddata")
            typeTotal("20")
            onNodeWithTag("settings-back").performClick()
            pageGone()
            modComma()
            nav("speeddata")
            onNodeWithTag("total-none").assertIsSelected()
        }

    @Test
    fun aDraftKeptAcrossSectionsFollowsAChangeOfUnitMadeInBetween() =
        runComposeUiTest {
            home()
            modComma()
            nav("speeddata")
            typeTotal("20")
            nav("appearance")
            onNodeWithTag("settings-unit-mbps").performClick()
            nav("speeddata")
            onNodeWithTag("total-field").assertTextEquals("160")
        }

    // AC5

    @Test
    fun networksListsTheNetworksAndHidesTheMenuOnlyControls() =
        runComposeUiTest {
            home()
            modComma()
            nav("networks")
            onNodeWithTag("settings-network-wifi").assertIsDisplayed()
            onNodeWithTag("settings-network-usb").assertIsDisplayed()
            onNodeWithTag("network-page").assertIsDisplayed()
            onNodeWithTag("network-page-back").assertDoesNotExist()
            onNodeWithTag("network-page-limits").assertDoesNotExist()
        }

    @Test
    fun selectingANetworkShowsItsPage() =
        runComposeUiTest {
            home()
            modComma()
            nav("networks")
            onNodeWithTag("settings-network-usb").performClick()
            onNodeWithTag("network-page-name").assertTextEquals("USB Tethering")
        }

    @Test
    fun aColorChosenHereShowsInTheNetworksMenuToo() =
        runComposeUiTest {
            home()
            modComma()
            nav("networks")
            onNodeWithTag("swatch-orange").performClick()
            onNodeWithTag("swatch-orange").assertIsSelected()
        }

    @Test
    fun renameAppliesWithTheSameRulesAsTheMenu() =
        runComposeUiTest {
            home()
            modComma()
            nav("networks")
            onNodeWithTag("network-page-rename").performClick()
            onNodeWithTag("network-rename-field").performTextClearance()
            onNodeWithTag("network-rename-field").performTextInput("Office")
            onNodeWithTag("network-rename-field").performKeyInput { pressKey(Key.Enter) }
            onNodeWithTag("network-page-name").assertTextEquals("Office")
            onNodeWithTag("settings-network-wifi").assertIsDisplayed()
        }

    @Test
    fun anEmptyNameIsRejectedAndEscapeCancelsTheEditWithoutLeaving() =
        runComposeUiTest {
            home()
            modComma()
            nav("networks")
            onNodeWithTag("network-page-rename").performClick()
            onNodeWithTag("network-rename-field").performTextClearance()
            onNodeWithTag("network-rename-field").performKeyInput { pressKey(Key.Enter) }
            onNodeWithTag("network-rename-field").assertIsDisplayed()
            onNodeWithTag("network-rename-field").performKeyInput { pressKey(Key.Escape) }
            onNodeWithTag("settings-page").assertIsDisplayed()
            onNodeWithTag("network-page-name").assertTextEquals("Wi-Fi")
        }

    @Test
    fun proxySaveWorksAndEscapeInAProxyFieldDoesNotLeave() =
        runComposeUiTest {
            home()
            modComma()
            nav("networks")
            onNodeWithTag("network-page-proxy-edit").performClick()
            onNodeWithTag("proxy-page-type-http").performClick()
            onNodeWithTag("proxy-page-host").performTextInput("proxy.example.com")
            onNodeWithTag("proxy-page-port").performTextInput("8080")
            onNodeWithTag("proxy-page-save").assertIsEnabled()
            onNodeWithTag("proxy-page-host").performKeyInput { pressKey(Key.Escape) }
            onNodeWithTag("settings-page").assertIsDisplayed()
            onNodeWithTag("proxy-page-save").assertIsEnabled()
            onNodeWithTag("proxy-page-save").performClick()
            onNodeWithTag("network-page-proxy-summary", useUnmergedTree = true).assertTextEquals("HTTP proxy.example.com:8080")
        }

    @Test
    fun theProxySaveStaysDisabledForABadHost() =
        runComposeUiTest {
            home()
            modComma()
            nav("networks")
            onNodeWithTag("network-page-proxy-edit").performClick()
            onNodeWithTag("proxy-page-type-http").performClick()
            onNodeWithTag("proxy-page-save").assertIsNotEnabled()
        }

    // Settings in the menu bar and the Networks menu keep working.

    @Test
    fun settingsPageWorksInTheLightTheme() =
        runComposeUiTest {
            home(dark = false)
            modComma()
            onNodeWithTag("settings-page").assertIsDisplayed()
            themeIs("light")
        }
}
