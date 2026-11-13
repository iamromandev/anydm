package dev.anydm.desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class SpeedDataTest {
    private fun ComposeUiTest.home(clipboard: String? = null) =
        setContent { HomeScreen(populatedSample(), true, clipboardText = { clipboard }) }

    private fun ComposeUiTest.openSpeedData() {
        onNodeWithTag("network-chip").performClick()
        onNodeWithTag("networks-limits-link").performClick()
    }

    /** Leaves Settings for the list and comes back, to see what was kept. */
    private fun ComposeUiTest.reopen() {
        onNodeWithTag("settings-back").performClick()
        openSpeedData()
    }

    private fun ComposeUiTest.summary(
        pane: String,
        text: String,
    ) = onNodeWithTag("limits-nav-summary-$pane", useUnmergedTree = true).assertTextEquals(text)

    private fun ComposeUiTest.field(
        tag: String,
        text: String,
    ) = onNodeWithTag(tag).assertTextEquals(text)

    private fun ComposeUiTest.gone() = onNodeWithTag("settings-page").assertDoesNotExist()

    private fun ComposeUiTest.setTotal(text: String) {
        onNodeWithTag("total-limit").performClick()
        onNodeWithTag("total-field").performTextClearance()
        onNodeWithTag("total-field").performTextInput(text)
    }

    private fun hasRole(role: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)

    private fun ComposeUiTest.role(
        role: Role,
        vararg tags: String,
    ) = tags.forEach { onNodeWithTag(it).assert(hasRole(role)) }

    private fun ComposeUiTest.pressEnter() = onNodeWithTag("total-field").performKeyInput { pressKey(Key.Enter) }

    @Test
    fun linkOpensSpeedAndDataOnAllDownloadsAndClosesTheMenu() =
        runComposeUiTest {
            home()
            openSpeedData()
            onNodeWithTag("limits-editor").assertIsDisplayed()
            onNodeWithTag("networks-menu").assertDoesNotExist()
            onNodeWithTag("settings-nav-speeddata").assertIsSelected()
            onNodeWithTag("total-none").assertIsSelected()
            summary("general", "No limit")
            summary("wifi", "No limit")
            summary("usb", "No limit")
        }

    @Test
    fun sidebarListsDisabledNetworksMarkedOff() =
        runComposeUiTest {
            home()
            onNodeWithTag("network-chip").performClick()
            onNodeWithTag("network-dot-usb").performClick()
            onNodeWithTag("networks-limits-link").performClick()
            onNodeWithTag("limits-nav-usb").assertIsDisplayed()
            onNodeWithTag("limits-off-usb", useUnmergedTree = true).assertIsDisplayed()
            onNodeWithTag("limits-off-wifi", useUnmergedTree = true).assertDoesNotExist()
        }

    @Test
    fun escapeClosesWithoutSaving() =
        runComposeUiTest {
            home()
            openSpeedData()
            setTotal("20")
            onNodeWithTag("total-field").performKeyInput { pressKey(Key.Escape) }
            gone()
            openSpeedData()
            summary("general", "No limit")
        }

    @Test
    fun cancelDiscardsEdits() =
        runComposeUiTest {
            home()
            openSpeedData()
            setTotal("20")
            onNodeWithTag("limits-cancel").performClick()
            onNodeWithTag("limits-editor").assertIsDisplayed()
            summary("general", "No limit")
            onNodeWithTag("total-none").assertIsSelected()
            reopen()
            summary("general", "No limit")
        }

    @Test
    fun savingALimitShowsItInTheSidebarOnReopen() =
        runComposeUiTest {
            home()
            openSpeedData()
            setTotal("20")
            summary("general", "20 MB/s")
            onNodeWithTag("limits-save").performClick()
            onNodeWithTag("limits-editor").assertIsDisplayed()
            reopen()
            summary("general", "20 MB/s")
            onNodeWithTag("total-limit").assertIsSelected()
            field("total-field", "20")
        }

    @Test
    fun speedFieldIsDisabledWhileNoLimitIsSelected() =
        runComposeUiTest {
            home()
            openSpeedData()
            onNodeWithTag("total-field").assertIsNotEnabled()
            onNodeWithTag("total-limit").performClick()
            onNodeWithTag("total-field").assertIsEnabled()
            onNodeWithTag("total-none").performClick()
            onNodeWithTag("total-field").assertIsNotEnabled()
        }

    @Test
    fun slowModeShowsTheReplaceNote() =
        runComposeUiTest {
            home()
            openSpeedData()
            val note = "Replaces the total speed limit while enabled"
            onNodeWithText(note).assertDoesNotExist()
            onNodeWithTag("slow-enabled").assertIsOff()
            field("slow-field", "2")
            onNodeWithTag("slow-enabled").performClick()
            onNodeWithText(note).assertIsDisplayed()
            onNodeWithTag("slow-enabled").assertIsOn()
            summary("general", "Slow · 2 MB/s")
        }

    @Test
    fun slowModeStartsFromTheFooterSwitchAndSavesBackToIt() =
        runComposeUiTest {
            home()
            onNodeWithTag("slow-mode").performClick()
            openSpeedData()
            onNodeWithTag("slow-enabled").assertIsOn()
            onNodeWithTag("slow-enabled").performClick()
            onNodeWithTag("limits-save").performClick()
            onNodeWithTag("slow-mode").assertIsOff()
            reopen()
            onNodeWithTag("slow-enabled").assertIsOff()
            onNodeWithTag("slow-enabled").performClick()
            onNodeWithTag("limits-save").performClick()
            onNodeWithTag("slow-mode").assertIsOn()
        }

    @Test
    fun cancelLeavesTheFooterSlowSwitchAlone() =
        runComposeUiTest {
            home()
            openSpeedData()
            onNodeWithTag("slow-enabled").performClick()
            onNodeWithTag("limits-cancel").performClick()
            onNodeWithTag("slow-mode").assertIsOff()
        }

    @Test
    fun concurrencyStaysWithinOneAndTen() =
        runComposeUiTest {
            home()
            openSpeedData()
            onNodeWithTag("concurrency-value").assertTextEquals("2")
            onNodeWithTag("concurrency-minus").performClick()
            onNodeWithTag("concurrency-value").assertTextEquals("1")
            onNodeWithTag("concurrency-minus").performClick()
            onNodeWithTag("concurrency-value").assertTextEquals("1")
            repeat(12) { onNodeWithTag("concurrency-plus").performClick() }
            onNodeWithTag("concurrency-value").assertTextEquals("10")
        }

    @Test
    fun connectionsPerDownloadStartAtEightAndStayWithinOneAndSixteen() =
        runComposeUiTest {
            home()
            openSpeedData()
            onNodeWithTag("connections-value").assertTextEquals("8")
            repeat(9) { onNodeWithTag("connections-minus").performClick() }
            onNodeWithTag("connections-value").assertTextEquals("1")
            onNodeWithTag("connections-minus").assertIsNotEnabled()
            repeat(20) { onNodeWithTag("connections-plus").performClick() }
            onNodeWithTag("connections-value").assertTextEquals("16")
            onNodeWithTag("connections-plus").assertIsNotEnabled()
        }

    @Test
    fun stepperButtonsHaveLabels() =
        runComposeUiTest {
            home()
            openSpeedData()
            onNodeWithContentDescriptionTag("concurrency-minus", "Fewer")
            onNodeWithContentDescriptionTag("concurrency-plus", "More")
        }

    private fun ComposeUiTest.onNodeWithContentDescriptionTag(
        tag: String,
        description: String,
    ) = onNodeWithTag(tag).assert(hasContentDescription(description))

    @Test
    fun editsSurviveSwitchingPanes() =
        runComposeUiTest {
            home()
            openSpeedData()
            onNodeWithTag("limits-nav-wifi").performClick()
            onNodeWithTag("net-speed-limit").performClick()
            onNodeWithTag("net-speed-field").performTextInput("10")
            onNodeWithTag("limits-nav-general").performClick()
            onNodeWithTag("total-none").assertIsDisplayed()
            onNodeWithTag("limits-nav-wifi").performClick()
            field("net-speed-field", "10")
            onNodeWithTag("net-speed-limit").assertIsSelected()
            summary("wifi", "10 MB/s")
        }

    @Test
    fun networkPaneShowsUsageSpeedAndDataSectionsWithMonthSelected() =
        runComposeUiTest {
            home()
            openSpeedData()
            onNodeWithTag("limits-nav-wifi").performClick()
            onNodeWithTag("limits-usage").assertTextEquals("200.1 MB used this month")
            onNodeWithTag("net-speed-none").assertIsSelected()
            onNodeWithTag("net-data-none").assertIsSelected()
            onNodeWithTag("net-period-month").assertIsSelected()
            onNodeWithTag("net-period-day").assertIsNotSelected()
            onNodeWithTag("limits-nav-usb").performClick()
            onNodeWithTag("limits-usage").assertTextEquals("1.5 GB used this month")
        }

    @Test
    fun dataLimitSavesWithItsPeriod() =
        runComposeUiTest {
            home()
            openSpeedData()
            onNodeWithTag("limits-nav-wifi").performClick()
            onNodeWithTag("net-data-limit").performClick()
            onNodeWithTag("net-data-field").performTextInput("5")
            onNodeWithTag("net-period-week").performClick()
            summary("wifi", "5 GB/Week")
            onNodeWithTag("limits-save").performClick()
            reopen()
            onNodeWithTag("limits-nav-wifi").performClick()
            field("net-data-field", "5")
            onNodeWithTag("net-period-week").assertIsSelected()
        }

    @Test
    fun removeLimitsResetsBothSectionsAndDisablesItself() =
        runComposeUiTest {
            home()
            openSpeedData()
            onNodeWithTag("limits-nav-wifi").performClick()
            onNodeWithTag("remove-limits").assertIsNotEnabled()
            onNodeWithTag("net-speed-limit").performClick()
            onNodeWithTag("net-speed-field").performTextInput("10")
            onNodeWithTag("net-data-limit").performClick()
            onNodeWithTag("net-data-field").performTextInput("5")
            onNodeWithTag("remove-limits").assertIsEnabled()
            onNodeWithTag("remove-limits").performClick()
            onNodeWithTag("net-speed-none").assertIsSelected()
            onNodeWithTag("net-data-none").assertIsSelected()
            onNodeWithTag("remove-limits").assertIsNotEnabled()
            summary("wifi", "No limit")
        }

    @Test
    fun resetDataUsageZeroesTheUsageLine() =
        runComposeUiTest {
            home()
            openSpeedData()
            onNodeWithTag("limits-nav-wifi").performClick()
            onNodeWithTag("reset-usage").performClick()
            onNodeWithTag("limits-usage").assertTextEquals("0 B used this month")
        }

    @Test
    fun emptyOrZeroLimitShowsAnErrorAndDisablesSave() =
        runComposeUiTest {
            home()
            openSpeedData()
            onNodeWithTag("limits-save").assertIsEnabled()
            onNodeWithTag("limits-error").assertDoesNotExist()
            onNodeWithTag("total-limit").performClick()
            onNodeWithTag("limits-error").assertIsDisplayed()
            onNodeWithTag("limits-save").assertIsNotEnabled()
            onNodeWithTag("total-field").performTextInput("0")
            onNodeWithTag("limits-save").assertIsNotEnabled()
            onNodeWithTag("total-field").performTextClearance()
            onNodeWithTag("total-field").performTextInput("20")
            onNodeWithTag("limits-error").assertDoesNotExist()
            onNodeWithTag("limits-save").assertIsEnabled()
        }

    @Test
    fun errorMarksTheSidebarRowOfThePane() =
        runComposeUiTest {
            home()
            openSpeedData()
            onNodeWithTag("limits-nav-wifi").performClick()
            onNodeWithTag("net-speed-limit").performClick()
            onNodeWithTag("limits-nav-error-wifi", useUnmergedTree = true).assertIsDisplayed()
            onNodeWithTag("limits-nav-error-general", useUnmergedTree = true).assertDoesNotExist()
            onNodeWithTag("limits-save").assertIsNotEnabled()
            onNodeWithTag("net-speed-none").performClick()
            onNodeWithTag("limits-nav-error-wifi", useUnmergedTree = true).assertDoesNotExist()
            onNodeWithTag("limits-save").assertIsEnabled()
        }

    @Test
    fun commaDecimalIsAnError() =
        runComposeUiTest {
            home()
            openSpeedData()
            setTotal("1,5")
            onNodeWithTag("limits-error").assertIsDisplayed()
            onNodeWithTag("limits-save").assertIsNotEnabled()
        }

    @Test
    fun mbpsUnitShowsStoredLimitConverted() =
        runComposeUiTest {
            home()
            openSpeedData()
            setTotal("20")
            onNodeWithTag("limits-save").performClick()
            onNodeWithTag("unit-mbps").performClick()
            reopen()
            field("total-field", "160")
            summary("general", "160 Mbps")
        }

    @Test
    fun changingTheUnitKeepsTheUnsavedDraftConverted() =
        runComposeUiTest {
            home()
            openSpeedData()
            setTotal("20")
            onNodeWithTag("unit-mbps").performClick()
            field("total-field", "160")
            onNodeWithTag("limits-save").performClick()
            onNodeWithTag("unit-mbps-bytes").performClick()
            field("total-field", "20")
        }

    @Test
    fun theFooterSlowSwitchSyncsIntoTheDraftWithoutDiscardingIt() =
        runComposeUiTest {
            home()
            openSpeedData()
            setTotal("20")
            onNodeWithTag("slow-mode").performClick()
            onNodeWithTag("slow-enabled").assertIsOn()
            field("total-field", "20")
            onNodeWithTag("limits-save").performClick()
            onNodeWithTag("slow-mode").assertIsOn()
            summary("general", "Slow · 2 MB/s")
        }

    @Test
    fun anUnsavedDraftSurvivesBothTheUnitAndTheSlowSwitch() =
        runComposeUiTest {
            home()
            openSpeedData()
            onNodeWithTag("slow-enabled").performClick()
            setTotal("20")
            onNodeWithTag("unit-mbps").performClick()
            onNodeWithTag("slow-mode").performClick()
            onNodeWithTag("slow-mode").performClick()
            onNodeWithTag("slow-enabled").assertIsOff()
            field("total-field", "160")
        }

    @Test
    fun valueTypedInMbpsIsStoredInBytes() =
        runComposeUiTest {
            home()
            onNodeWithTag("unit-mbps").performClick()
            openSpeedData()
            setTotal("160")
            onNodeWithTag("limits-save").performClick()
            onNodeWithTag("unit-mbps-bytes").performClick()
            reopen()
            field("total-field", "20")
        }

    @Test
    fun pasteShortcutIsIgnoredWhileSettingsIsOpen() =
        runComposeUiTest {
            home(clipboard = "https://example.com/a.zip")
            openSpeedData()
            onRoot().performKeyInput {
                val mod = if (System.getProperty("os.name").startsWith("Mac")) Key.MetaLeft else Key.CtrlLeft
                keyDown(mod)
                pressKey(Key.V)
                keyUp(mod)
            }
            waitForIdle()
            onNodeWithTag("new-download-dialog").assertDoesNotExist()
            onNodeWithTag("limits-editor").assertIsDisplayed()
        }

    @Test
    fun generalControlsExposeRoles() =
        runComposeUiTest {
            home()
            openSpeedData()
            role(Role.RadioButton, "total-limit", "total-none")
            role(Role.Switch, "slow-enabled", "schedule-enabled")
            role(Role.Tab, "limits-nav-general", "limits-nav-wifi", "limits-nav-usb")
            scheduleOn()
            role(Role.Checkbox, *days.map { "schedule-day-$it" }.toTypedArray())
        }

    @Test
    fun networkControlsExposeRoles() =
        runComposeUiTest {
            home()
            openSpeedData()
            onNodeWithTag("limits-nav-wifi").performClick()
            role(Role.RadioButton, "net-speed-none", "net-speed-limit", "net-data-none", "net-data-limit")
        }

    @Test
    fun numberFieldsHaveDistinctLabels() =
        runComposeUiTest {
            home()
            openSpeedData()
            onNodeWithTag("total-field").assert(hasContentDescription("Total speed limit"))
            onNodeWithTag("slow-field").assert(hasContentDescription("Slow mode speed"))
            scheduleOn()
            onNodeWithTag("schedule-start").assert(hasContentDescription("Schedule start"))
            onNodeWithTag("schedule-end").assert(hasContentDescription("Schedule end"))
            onNodeWithTag("limits-nav-wifi").performClick()
            onNodeWithTag("net-speed-field").assert(hasContentDescription("Speed limit"))
            onNodeWithTag("net-data-field").assert(hasContentDescription("Data limit"))
        }

    @Test
    fun stepperButtonsAreDisabledAtTheirBounds() =
        runComposeUiTest {
            home()
            openSpeedData()
            onNodeWithTag("concurrency-minus").assertIsEnabled()
            onNodeWithTag("concurrency-minus").performClick()
            onNodeWithTag("concurrency-minus").assertIsNotEnabled()
            onNodeWithTag("concurrency-plus").assertIsEnabled()
            repeat(9) { onNodeWithTag("concurrency-plus").performClick() }
            onNodeWithTag("concurrency-plus").assertIsNotEnabled()
            onNodeWithTag("concurrency-minus").assertIsEnabled()
        }

    @Test
    fun enterSavesWhenValid() =
        runComposeUiTest {
            home()
            openSpeedData()
            setTotal("20")
            pressEnter()
            reopen()
            summary("general", "20 MB/s")
        }

    @Test
    fun enterDoesNothingWhileThereAreErrors() =
        runComposeUiTest {
            home()
            openSpeedData()
            setTotal("0")
            pressEnter()
            onNodeWithTag("limits-editor").assertIsDisplayed()
            onNodeWithTag("limits-error").assertIsDisplayed()
        }

    @Test
    fun invalidTotalShowsInvalidInTheSidebarSummary() =
        runComposeUiTest {
            home()
            openSpeedData()
            onNodeWithTag("total-limit").performClick()
            summary("general", "Invalid")
            onNodeWithTag("total-field").performTextInput("20")
            summary("general", "20 MB/s")
        }

    @Test
    fun invalidSlowTextOnlyBlocksSaveWhileSlowModeIsOn() =
        runComposeUiTest {
            home()
            openSpeedData()
            onNodeWithTag("slow-field").performTextClearance()
            onNodeWithTag("limits-error").assertDoesNotExist()
            onNodeWithTag("limits-save").assertIsEnabled()
            onNodeWithTag("slow-enabled").performClick()
            onNodeWithTag("limits-error").assertIsDisplayed()
            onNodeWithTag("limits-save").assertIsNotEnabled()
            onNodeWithTag("slow-enabled").performClick()
            onNodeWithTag("limits-save").assertIsEnabled()
            onNodeWithTag("limits-save").performClick()
            reopen()
            field("slow-field", "2")
        }

    @Test
    fun saveWithoutEditsKeepsEverything() =
        runComposeUiTest {
            home()
            openSpeedData()
            onNodeWithTag("limits-save").performClick()
            reopen()
            summary("general", "No limit")
            onNodeWithTag("concurrency-value").assertTextEquals("2")
        }

    @Test
    fun editorWorksInLightTheme() =
        runComposeUiTest {
            setContent { HomeScreen(populatedSample(), false) }
            openSpeedData()
            onNodeWithTag("limits-editor").assertIsDisplayed()
        }

    private val days = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")

    // The pane is taller with the connections stepper: the schedule switch may sit below the fold of a small window.
    private fun ComposeUiTest.scheduleOn() = onNodeWithTag("schedule-enabled").performScrollTo().performClick()

    @Test
    fun scheduleOnShowsWeekdaysAndDefaultTimesAndOffHidesThemKeepingValues() =
        runComposeUiTest {
            home()
            openSpeedData()
            onNodeWithTag("schedule-day-mon").assertDoesNotExist()
            scheduleOn()
            for (d in days.take(5)) onNodeWithTag("schedule-day-$d").assertIsOn()
            for (d in days.drop(5)) onNodeWithTag("schedule-day-$d").assertIsOff()
            field("schedule-start", "09:00")
            field("schedule-end", "17:00")
            onNodeWithTag("schedule-day-sat").performClick()
            onNodeWithTag("schedule-start").performTextClearance()
            onNodeWithTag("schedule-start").performTextInput("08:30")
            scheduleOn()
            onNodeWithTag("schedule-day-mon").assertDoesNotExist()
            onNodeWithTag("schedule-start").assertDoesNotExist()
            scheduleOn()
            onNodeWithTag("schedule-day-sat").assertIsOn()
            field("schedule-start", "08:30")
        }

    @Test
    fun scheduleWithNoDayDisablesSaveAndLastDayCanBeRemoved() =
        runComposeUiTest {
            home()
            openSpeedData()
            scheduleOn()
            for (d in days.take(5)) onNodeWithTag("schedule-day-$d").performClick()
            onNodeWithTag("limits-save").assertIsNotEnabled()
            onNodeWithTag("schedule-error").performScrollTo().assertIsDisplayed()
            onNodeWithTag("limits-nav-error-general", useUnmergedTree = true).assertIsDisplayed()
            onNodeWithTag("schedule-day-tue").performClick()
            onNodeWithTag("limits-save").assertIsEnabled()
        }

    @Test
    fun scheduleEndNotAfterStartDisablesSave() =
        runComposeUiTest {
            home()
            openSpeedData()
            scheduleOn()
            onNodeWithTag("schedule-end").performTextClearance()
            onNodeWithTag("schedule-end").performTextInput("09:00")
            onNodeWithTag("limits-save").assertIsNotEnabled()
            onNodeWithTag("schedule-error").performScrollTo().assertIsDisplayed()
            onNodeWithTag("schedule-end").performTextClearance()
            onNodeWithTag("schedule-end").performTextInput("09:01")
            onNodeWithTag("limits-save").assertIsEnabled()
            onNodeWithTag("schedule-end").performTextClearance()
            onNodeWithTag("schedule-end").performTextInput("9:5")
            onNodeWithTag("limits-save").assertIsNotEnabled()
        }

    @Test
    fun disabledScheduleNeverBlocksSave() =
        runComposeUiTest {
            home()
            openSpeedData()
            scheduleOn()
            onNodeWithTag("schedule-end").performTextClearance()
            onNodeWithTag("schedule-end").performTextInput("01:00")
            scheduleOn()
            onNodeWithTag("limits-save").assertIsEnabled()
        }

    @Test
    fun resetToDefaultsRestoresTheWholeGeneralPane() =
        runComposeUiTest {
            home()
            openSpeedData()
            setTotal("20")
            onNodeWithTag("slow-field").performTextClearance()
            onNodeWithTag("slow-field").performTextInput("5")
            onNodeWithTag("slow-enabled").performClick()
            onNodeWithTag("concurrency-plus").performClick()
            scheduleOn()
            onNodeWithTag("schedule-day-sun").performClick()
            onNodeWithTag("limits-reset-defaults").performScrollTo().performClick()
            onNodeWithTag("total-none").assertIsSelected()
            field("slow-field", "2")
            onNodeWithTag("slow-enabled").assertIsOff()
            onNodeWithTag("concurrency-value").assertTextEquals("2")
            onNodeWithTag("schedule-day-mon").assertDoesNotExist()
            summary("general", "No limit")
            onNodeWithTag("limits-save").assertIsEnabled()
        }

    @Test
    fun resetToDefaultsLeavesNetworkEditsAlone() =
        runComposeUiTest {
            home()
            openSpeedData()
            onNodeWithTag("limits-nav-wifi").performClick()
            onNodeWithTag("net-speed-limit").performClick()
            onNodeWithTag("net-speed-field").performTextInput("10")
            onNodeWithTag("limits-nav-general").performClick()
            onNodeWithTag("limits-reset-defaults").performScrollTo().performClick()
            summary("wifi", "10 MB/s")
        }

    @Test
    fun smallWindowKeepsFooterVisibleAndBodyScrolls() =
        runDesktopComposeUiTest(900, 600) {
            setContent { HomeScreen(populatedSample(), true) }
            openSpeedData()
            scheduleOn()
            onNodeWithTag("limits-save").assertIsDisplayed()
            onNodeWithTag("limits-cancel").assertIsDisplayed()
            onNodeWithTag("limits-reset-defaults").assertIsNotDisplayed()
            onNodeWithTag("limits-reset-defaults").performScrollTo().assertIsDisplayed()
            onNodeWithTag("limits-save").assertIsDisplayed()
        }
}
