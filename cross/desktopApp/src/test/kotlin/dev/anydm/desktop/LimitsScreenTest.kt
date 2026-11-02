package dev.anydm.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.anydm.engine.core.ScheduleRuleId
import dev.anydm.engine.net.Allowance
import dev.anydm.engine.net.AllowanceAction
import dev.anydm.engine.net.AllowancePeriod
import dev.anydm.engine.net.AllowanceStatus
import dev.anydm.engine.net.BindStatus
import dev.anydm.engine.net.BondedNetwork
import dev.anydm.engine.net.BondingState
import dev.anydm.engine.net.NetworkInfo
import dev.anydm.engine.net.NetworkPolicy
import dev.anydm.engine.net.NetworkRules
import dev.anydm.engine.net.RuleAction
import dev.anydm.engine.net.ScheduleRule
import dev.anydm.engine.store.TransferSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant
import java.time.LocalDate as JavaDate

@OptIn(ExperimentalTestApi::class, androidx.compose.ui.InternalComposeUiApi::class)
internal class LimitsScreenTest {
    private class Shown(
        val source: FakeSource,
        val model: LimitsModel,
        val keys: WindowKeys,
        val closed: MutableList<Unit>,
        val scope: CoroutineScope,
    )

    private val rows =
        listOf(
            NetworkRow("en0", "Wi-Fi", "en0", NetworkKind.WIFI, "teal", null, true),
            NetworkRow("usb0", "USB Tethering", "usb0", NetworkKind.USB, "amber", null, true),
        )

    private fun net(key: String) = BondedNetwork(NetworkInfo(key, key, true, emptyList()), true, BindStatus.ISOLATED, null, false)

    private fun ComposeUiTest.show(
        page: String? = null,
        unit: SpeedUnit = SpeedUnit.BYTES,
        configure: (FakeSource) -> Unit = {},
    ): Shown {
        val source = FakeSource()
        source.networks.value = BondingState(listOf(net("en0"), net("usb0")))
        configure(source)
        val scope = CoroutineScope(Dispatchers.Unconfined + Job())
        val model = LimitsModel(source, unit, scope, page, pollMillis = 0, today = { JavaDate.of(2026, 11, 15) })
        val keys = WindowKeys()
        // The window's shortcuts, as the home screen has them: they would take Backspace from a field if not held back.
        keys.handler = { it.type == KeyEventType.KeyDown && (it.key == Key.Backspace || it.key == Key.Delete) }
        val closed = mutableListOf<Unit>()
        val window = FocusRequester()
        setContent {
            Box(Modifier.focusRequester(window).focusTarget().onPreviewKeyEvent { keys.dispatch(it) }) {
                PlexoTheme(ThemeChoice.DARK) { LimitsDialog(model, rows, keys) { closed += Unit } }
            }
            LaunchedEffect(Unit) { window.requestFocus() }
        }
        return Shown(source, model, keys, closed, scope)
    }

    private fun ComposeUiTest.until(what: () -> Boolean) = waitUntil(timeoutMillis = 5_000) { what() }

    private fun ComposeUiTest.has(text: String) = onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.hasPart(text: String) = onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.hasDescription(text: String) = onAllNodes(hasContentDescription(text)).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.save(shown: Shown) {
        onNodeWithContentDescription("Save limits").performClick()
        until { shown.closed.isNotEmpty() }
    }

    @Test
    fun `it opens on all downloads with the sidebar and the sections`() =
        runComposeUiTest {
            val shown = show()
            onNodeWithText("Speed & data limits").assertIsDisplayed()
            onNodeWithText("GENERAL").assertIsDisplayed()
            onNodeWithText("NETWORKS").assertIsDisplayed()
            onNodeWithContentDescription("Limits page: All downloads").assertIsDisplayed()
            onNodeWithContentDescription("Limits page: Wi-Fi").assertIsDisplayed()
            onNodeWithContentDescription("Limits page: USB Tethering").assertIsDisplayed()
            onNodeWithText("No active downloads").assertIsDisplayed()
            onNodeWithText("Total speed").assertIsDisplayed()
            onNodeWithText("Slow mode").assertIsDisplayed()
            onNodeWithText("Downloads at once").assertIsDisplayed()
            onNodeWithText("Download schedule").assertIsDisplayed()
            onNodeWithText("Reset to defaults").assertIsDisplayed()
            // All downloads and each network say No limit in the sidebar, and the radios say it too.
            assertTrue(onAllNodes(hasText("No limit")).fetchSemanticsNodes().size >= 3)
            shown.scope.cancel()
        }

    @Test
    fun `save is off until something changes and cancel closes without writing`() =
        runComposeUiTest {
            val shown = show()
            onNodeWithContentDescription("Save limits (nothing to save)").assertIsNotEnabled()
            onNodeWithContentDescription("Increase simultaneous downloads").performClick()
            onNodeWithContentDescription("Save limits").assertIsEnabled()
            onNodeWithText("Cancel").performClick()
            until { shown.closed.isNotEmpty() }
            assertTrue(shown.source.calls.isEmpty())
            assertEquals(TransferSettings(), shown.source.transfer)
            shown.scope.cancel()
        }

    @Test
    fun `escape cancels and a question open takes the escape instead`() =
        runComposeUiTest {
            val shown = show { it.transfer = TransferSettings(concurrencyLimit = 2) }
            onNodeWithText("Reset to defaults").performClick()
            onNodeWithText("Reset all downloads to defaults?").assertIsDisplayed()
            onAllNodes(isRoot())[0].performKeyInput { pressKey(Key.Escape) }
            waitForIdle()
            // The question closed; the dialog is still there.
            assertFalse(has("Reset all downloads to defaults?"))
            assertTrue(shown.closed.isEmpty())
            onAllNodes(isRoot())[0].performKeyInput { pressKey(Key.Escape) }
            until { shown.closed.isNotEmpty() }
            assertTrue(shown.source.calls.isEmpty())
            shown.scope.cancel()
        }

    @Test
    fun `a total speed limit typed in is saved to the engine`() =
        runComposeUiTest {
            val shown = show()
            onNodeWithContentDescription("Total speed: Limit to").performClick()
            onNodeWithContentDescription("Total speed amount").performTextClearance()
            onNodeWithContentDescription("Total speed amount").performTextInput("8")
            save(shown)
            assertEquals(8 * MIB, shown.source.transfer.globalSpeedLimit)
            shown.scope.cancel()
        }

    @Test
    fun `a speed that is no number shows why and turns save off`() =
        runComposeUiTest {
            val shown = show()
            onNodeWithContentDescription("Total speed: Limit to").performClick()
            onNodeWithContentDescription("Total speed amount").performTextClearance()
            onNodeWithText("Enter a speed above 0.").assertIsDisplayed()
            onNodeWithContentDescription("Save limits (nothing to save)").assertIsNotEnabled()
            onNodeWithContentDescription("Total speed amount").performTextInput("3")
            assertFalse(has("Enter a speed above 0."))
            onNodeWithContentDescription("Save limits").assertIsEnabled()
            shown.scope.cancel()
        }

    @Test
    fun `backspace reaches a number field although the window's shortcuts take backspace`() =
        runComposeUiTest {
            val shown = show { it.transfer = TransferSettings(globalSpeedLimit = 25 * MIB) }
            onNodeWithContentDescription("Total speed amount").assertTextEquals("25")
            onNodeWithContentDescription("Total speed amount").performClick()
            onNodeWithContentDescription("Total speed amount").performKeyInput {
                pressKey(Key.MoveEnd)
                pressKey(Key.Backspace)
            }
            onNodeWithContentDescription("Total speed amount").assertTextEquals("2")
            assertTrue(shown.keys.blocked)
            shown.scope.cancel()
        }

    @Test
    fun `the window's shortcuts are held back while the dialog is up`() =
        runComposeUiTest {
            val shown = show()
            assertTrue(shown.keys.blocked)
            assertFalse(
                shown.keys.dispatch(
                    androidx.compose.ui.input.key
                        .KeyEvent(Key.Backspace, KeyEventType.KeyDown),
                ),
            )
            shown.scope.cancel()
        }

    @Test
    fun `the stepper moves between one and four`() =
        runComposeUiTest {
            val shown = show { it.transfer = TransferSettings(concurrencyLimit = 3) }
            onNodeWithContentDescription("Downloads at once").assertTextEquals("3")
            onNodeWithContentDescription("Increase simultaneous downloads").performClick()
            onNodeWithContentDescription("Downloads at once").assertTextEquals("4")
            onNodeWithContentDescription("Increase simultaneous downloads").assertIsNotEnabled()
            onNodeWithContentDescription("Increase simultaneous downloads").performClick()
            onNodeWithContentDescription("Downloads at once").assertTextEquals("4")
            shown.scope.cancel()
        }

    @Test
    fun `slow mode has its own speed and switch`() =
        runComposeUiTest {
            val shown = show()
            onNodeWithContentDescription("Slow mode speed").assertTextEquals("1")
            onNodeWithContentDescription("Slow mode speed").performTextClearance()
            onNodeWithContentDescription("Slow mode speed").performTextInput("2")
            onNodeWithContentDescription("Slow mode enabled").performClick()
            save(shown)
            assertTrue(shown.source.transfer.slowMode)
            assertEquals(2 * MIB, shown.source.transfer.slowModeLimit)
            shown.scope.cancel()
        }

    @Test
    fun `speeds are labelled and typed in the unit of the footer`() =
        runComposeUiTest {
            val shown = show(unit = SpeedUnit.BITS)
            assertTrue(has("Mbps"))
            assertFalse(has("MB/s"))
            shown.scope.cancel()
        }

    @Test
    fun `reset to defaults asks first and puts the draft back, saving writes it`() =
        runComposeUiTest {
            val shown = show { it.transfer = TransferSettings(globalSpeedLimit = 5 * MIB, concurrencyLimit = 2, slowMode = true) }
            onNodeWithText("Reset to defaults").performClick()
            onNodeWithText("Reset all downloads to defaults?").assertIsDisplayed()
            // Declining changes nothing.
            onAllNodes(hasText("Cancel")).onLast().performClick()
            onNodeWithContentDescription("Save limits (nothing to save)").assertIsNotEnabled()
            onNodeWithText("Reset to defaults").performClick()
            onAllNodes(hasText("Reset to defaults"))[1].performClick()
            onNodeWithContentDescription("Save limits").assertIsEnabled()
            onNodeWithContentDescription("Downloads at once").assertTextEquals("3")
            save(shown)
            assertEquals(null, shown.source.transfer.globalSpeedLimit)
            assertFalse(shown.source.transfer.slowMode)
            assertEquals(3, shown.source.transfer.concurrencyLimit)
            shown.scope.cancel()
        }

    @Test
    fun `the schedule's fields show only while it is on and save as pause rules`() =
        runComposeUiTest {
            val shown = show()
            assertFalse(hasDescription("Start at hour"))
            onNodeWithContentDescription("Enable download schedule").performClick()
            onNodeWithContentDescription("Start at hour").assertTextEquals("00")
            onNodeWithContentDescription("Stop at hour").assertTextEquals("07")
            onNodeWithContentDescription("Monday").assertIsDisplayed()
            onNodeWithContentDescription("Stop at hour").performTextClearance()
            onNodeWithContentDescription("Stop at hour").performTextInput("23")
            onNodeWithContentDescription("Sunday").performClick()
            save(shown)
            assertTrue(shown.source.scheduleList.isNotEmpty())
            assertTrue(shown.source.scheduleList.all { it.action == dev.anydm.engine.net.RuleAction.PAUSE })
            shown.scope.cancel()
        }

    @Test
    fun `a schedule with the same start and stop is refused`() =
        runComposeUiTest {
            val shown = show()
            onNodeWithContentDescription("Enable download schedule").performClick()
            onNodeWithContentDescription("Stop at hour").performTextClearance()
            onNodeWithContentDescription("Stop at hour").performTextInput("00")
            onNodeWithContentDescription("Stop at minute").performTextClearance()
            onNodeWithContentDescription("Stop at minute").performTextInput("00")
            // The page scrolls: the line is there whether or not the window is tall enough to show it.
            onAllNodes(hasText("The schedule must start and stop at different times.")).onFirst().assertExists()
            onNodeWithContentDescription("Save limits (nothing to save)").assertIsNotEnabled()
            shown.scope.cancel()
        }

    // Pause rules set up elsewhere

    // A pause rule some other tool made: Monday to Friday, 09:00 to 17:00.
    private fun withWorkHours(source: FakeSource) {
        source.scheduleList = listOf(ScheduleRule(ScheduleRuleId(50), DayOfWeek.entries.take(5).toSet(), 9 * 60, 17 * 60, RuleAction.PAUSE))
    }

    private fun FakeSource.schedulePuts() =
        synchronized(calls) {
            calls.filter {
                it.startsWith("addScheduleRule") ||
                    it.startsWith("removeScheduleRule")
            }
        }

    @Test
    fun `pause rules from elsewhere stay named in the hint while the schedule switch is off and on`() =
        runComposeUiTest {
            val shown = show(configure = ::withWorkHours)
            assertTrue(hasPart("Pause Mon–Fri 09:00–17:00"))
            assertTrue(hasPart("replaces"))
            onNodeWithContentDescription("Enable download schedule").performClick()
            assertTrue(hasPart("Pause Mon–Fri 09:00–17:00"))
            assertTrue(hasPart("Saving"))
            shown.scope.cancel()
        }

    @Test
    fun `no hint about other pause rules when there are none`() =
        runComposeUiTest {
            val shown = show()
            assertFalse(hasPart("Pause "))
            shown.scope.cancel()
        }

    @Test
    fun `saving a schedule over pause rules from elsewhere asks first and names them`() =
        runComposeUiTest {
            val shown = show(configure = ::withWorkHours)
            onNodeWithContentDescription("Enable download schedule").performClick()
            onNodeWithContentDescription("Save limits").performClick()
            onNodeWithText("Replace the other pause rules?").assertIsDisplayed()
            assertTrue(hasPart("1 pause rule set up elsewhere"))
            assertTrue(onAllNodes(hasText("Pause Mon–Fri 09:00–17:00", substring = true)).fetchSemanticsNodes().size >= 2)
            // Nothing is written while the question is open.
            assertTrue(shown.source.schedulePuts().isEmpty())
            // Declining keeps the dialog, the draft and the rules.
            onAllNodes(hasText("Cancel")).onLast().performClick()
            assertFalse(has("Replace the other pause rules?"))
            assertTrue(shown.closed.isEmpty())
            onNodeWithContentDescription("Save limits").assertIsEnabled()
            assertTrue(shown.source.schedulePuts().isEmpty())
            assertEquals(listOf(50L), shown.source.scheduleList.map { it.id.value })
            // Confirming writes the schedule over them.
            onNodeWithContentDescription("Save limits").performClick()
            onNodeWithText("Replace and save").performClick()
            until { shown.closed.isNotEmpty() }
            assertTrue(shown.source.scheduleList.none { it.id.value == 50L })
            assertTrue(shown.source.scheduleList.isNotEmpty())
            shown.scope.cancel()
        }

    @Test
    fun `saving something else with the schedule off asks nothing and keeps the other pause rules`() =
        runComposeUiTest {
            val shown = show(configure = ::withWorkHours)
            onNodeWithContentDescription("Increase simultaneous downloads").performClick()
            save(shown)
            assertEquals(listOf(50L), shown.source.scheduleList.map { it.id.value })
            shown.scope.cancel()
        }

    @Test
    fun `reset to defaults does not claim to reset pause rules from elsewhere`() =
        runComposeUiTest {
            val shown =
                show {
                    it.transfer = TransferSettings(concurrencyLimit = 2)
                    withWorkHours(it)
                }
            onNodeWithText("Reset to defaults").performClick()
            assertTrue(hasPart("the pause rules set up elsewhere stay unchanged"))
            assertFalse(hasPart("download schedule"))
            shown.scope.cancel()
        }

    @Test
    fun `reset to defaults says it resets the schedule when the schedule is the dialog's own`() =
        runComposeUiTest {
            val shown = show { it.transfer = TransferSettings(concurrencyLimit = 2) }
            onNodeWithText("Reset to defaults").performClick()
            assertTrue(hasPart("downloads at once and the download schedule"))
            shown.scope.cancel()
        }

    @Test
    fun `a time box cleared shows why the schedule can't be saved until it is filled in`() =
        runComposeUiTest {
            val shown = show()
            onNodeWithContentDescription("Enable download schedule").performClick()
            onNodeWithContentDescription("Stop at hour").performTextClearance()
            onAllNodes(hasText("Enter the start and stop times as hours and minutes.")).onFirst().assertExists()
            onNodeWithContentDescription("Save limits (nothing to save)").assertIsNotEnabled()
            onNodeWithContentDescription("Stop at hour").performTextInput("6")
            assertFalse(has("Enter the start and stop times as hours and minutes."))
            onNodeWithContentDescription("Save limits").assertIsEnabled()
            save(shown)
            assertEquals(
                scheduleRulesFor(DownloadSchedule(true, 0, 6 * 60)).map { it.startMinute to it.endMinute },
                shown.source.scheduleList.map { it.startMinute to it.endMinute },
            )
            shown.scope.cancel()
        }

    // Mbps

    @Test
    fun `reset to defaults is off in Mbps when all downloads are at their defaults`() =
        runComposeUiTest {
            val shown = show(unit = SpeedUnit.BITS)
            onNodeWithContentDescription("Slow mode speed").assertTextEquals("8.39")
            onNodeWithText("Reset to defaults").assertIsNotEnabled()
            shown.scope.cancel()
        }

    // A network's page

    private fun withUsb(source: FakeSource) {
        source.rules =
            NetworkRules(
                networks =
                    mapOf("usb0" to NetworkPolicy(speedLimit = 4 * MIB, allowance = Allowance(5 * GIB, AllowancePeriod.Monthly(1)))),
            )
        source.usage =
            mapOf(
                "usb0" to
                    AllowanceStatus(
                        "usb0",
                        5 * GIB,
                        GIB,
                        LocalDate(2026, 11, 1),
                        LocalDate(2026, 11, 30),
                        Instant.parse("2026-12-01T00:00:00Z"),
                        null,
                        false,
                    ),
            )
    }

    @Test
    fun `a network's page says what anydm used and its limits`() =
        runComposeUiTest {
            val shown = show(page = "usb0", configure = ::withUsb)
            onNodeWithText("1.0 GB used by anydm this month").assertIsDisplayed()
            onNodeWithText("Speed").assertIsDisplayed()
            onNodeWithText("Data limit").assertIsDisplayed()
            onNodeWithContentDescription("USB Tethering speed amount").assertTextEquals("4")
            onNodeWithContentDescription("USB Tethering data limit amount").assertTextEquals("5")
            onNodeWithContentDescription("USB Tethering data limit period").assertIsDisplayed()
            onNodeWithContentDescription("Data usage").assertIsDisplayed()
            // The sidebar lines come from the same draft.
            onNodeWithText("Limit 4.0 MB/s").assertIsDisplayed()
            shown.scope.cancel()
        }

    @Test
    fun `the sidebar opens a network's page and back to all downloads`() =
        runComposeUiTest {
            val shown = show()
            onNodeWithContentDescription("Limits page: Wi-Fi").performClick()
            onNodeWithText("Limits the combined download speed on Wi-Fi.").assertIsDisplayed()
            onNodeWithContentDescription("Limits page: All downloads").performClick()
            onNodeWithText("Limits the combined download speed across all networks.").assertIsDisplayed()
            shown.scope.cancel()
        }

    @Test
    fun `a network's speed and data limit with its period are saved`() =
        runComposeUiTest {
            val shown = show(page = "en0")
            onNodeWithContentDescription("Wi-Fi speed: Limit to").performClick()
            onNodeWithContentDescription("Wi-Fi speed amount").performTextClearance()
            onNodeWithContentDescription("Wi-Fi speed amount").performTextInput("6")
            onNodeWithContentDescription("Wi-Fi data limit: Limit to").performClick()
            onNodeWithContentDescription("Wi-Fi data limit amount").performTextClearance()
            onNodeWithContentDescription("Wi-Fi data limit amount").performTextInput("2")
            onNodeWithText("Week").performClick()
            save(shown)
            val policy =
                shown.source.rules.networks
                    .getValue("en0")
            assertEquals(6 * MIB, policy.speedLimit)
            assertEquals(2 * GIB, policy.allowance?.bytes)
            assertEquals(AllowancePeriod.Weekly(kotlinx.datetime.DayOfWeek.MONDAY), policy.allowance?.period)
            shown.scope.cancel()
        }

    @Test
    fun `remove limits asks first and empties the draft, saving drops the entry`() =
        runComposeUiTest {
            val shown = show(page = "usb0", configure = ::withUsb)
            onNodeWithText("Remove limits").performClick()
            onNodeWithText("Remove limits for USB Tethering?").assertIsDisplayed()
            onAllNodes(hasText("Remove limits"))[1].performClick()
            onNodeWithContentDescription("USB Tethering speed: No limit").assertIsSelected()
            onNodeWithContentDescription("USB Tethering data limit: No limit").assertIsSelected()
            onNodeWithText("Remove limits").assertIsNotEnabled()
            // Nothing was written yet.
            assertTrue(shown.source.calls.none { it.startsWith("setNetworkPolicy") })
            save(shown)
            assertFalse("usb0" in shown.source.rules.networks)
            shown.scope.cancel()
        }

    @Test
    fun `reset data usage asks first, then the engine resets and the usage reads zero`() =
        runComposeUiTest {
            val shown = show(page = "usb0", configure = ::withUsb)
            onNodeWithText("Reset data usage").performClick()
            onNodeWithText("Reset data usage for USB Tethering?").assertIsDisplayed()
            assertTrue(shown.source.calls.none { it.startsWith("resetAllowanceUsage") })
            onAllNodes(hasText("Reset data usage"))[1].performClick()
            until { "resetAllowanceUsage usb0" in shown.source.calls }
            until { has("0 B used by anydm this month") }
            // It is an action, not part of the draft.
            onNodeWithContentDescription("Save limits (nothing to save)").assertIsNotEnabled()
            shown.scope.cancel()
        }

    @Test
    fun `the reset question says what is deleted and that it can't be cancelled with the dialog`() =
        runComposeUiTest {
            val shown = show(page = "usb0", configure = ::withUsb)
            onNodeWithText("Reset data usage").performClick()
            assertTrue(hasPart("Takes effect now, even if you Cancel"))
            assertTrue(hasPart("Deletes this network's recorded traffic for this month"))
            assertTrue(hasPart("Downloads can use this network again"))
            shown.scope.cancel()
        }

    @Test
    fun `the remove limits question says the recorded traffic is not deleted`() =
        runComposeUiTest {
            val shown = show(page = "usb0", configure = ::withUsb)
            onNodeWithText("Remove limits").performClick()
            assertTrue(hasPart("Its recorded traffic is not deleted."))
            assertFalse(hasPart("stays unchanged"))
            shown.scope.cancel()
        }

    @Test
    fun `the data limit hint says downloads and uploads on the network count`() =
        runComposeUiTest {
            val shown = show(page = "usb0", configure = ::withUsb)
            onNodeWithText(
                "Stops downloads on USB Tethering at the limit. Downloads and uploads on this network count; other apps do not.",
            ).assertIsDisplayed()
            assertFalse(hasPart("Uploads and other apps don't count"))
            shown.scope.cancel()
        }

    private fun withSlowingUsb(source: FakeSource) {
        withUsb(source)
        source.rules =
            NetworkRules(
                networks =
                    mapOf(
                        "usb0" to
                            NetworkPolicy(
                                speedLimit = 4 * MIB,
                                allowance = Allowance(5 * GIB, AllowancePeriod.Monthly(1), atLimit = AllowanceAction.SLOW),
                            ),
                    ),
            )
    }

    @Test
    fun `a data limit that slows at the limit says slows and not stops`() =
        runComposeUiTest {
            val shown = show(page = "usb0", configure = ::withSlowingUsb)
            onNodeWithText(
                "Slows downloads on USB Tethering at the limit. Downloads and uploads on this network count; other apps do not.",
            ).assertIsDisplayed()
            assertFalse(hasPart("Stops downloads"))
            assertTrue(hasPart("end the slowdown"))
            onNodeWithText("Reset data usage").performClick()
            assertTrue(hasPart("no longer slowed"))
            assertFalse(hasPart("can use this network again"))
            shown.scope.cancel()
        }

    @Test
    fun `reset data usage is off for a network with no usage or no saved data limit`() =
        runComposeUiTest {
            val shown = show(page = "en0")
            onNodeWithText("Reset data usage").assertIsNotEnabled()
            shown.scope.cancel()
        }

    @Test
    fun `a data limit not saved yet can't have its usage reset and says to save first`() =
        runComposeUiTest {
            val shown = show(page = "en0")
            onNodeWithContentDescription("Wi-Fi data limit: Limit to").performClick()
            onNodeWithText("Save the data limit first to be able to reset its usage.").assertIsDisplayed()
            shown.scope.cancel()
        }

    @Test
    fun `a refused save stays open and says why`() =
        runComposeUiTest {
            val shown = show()
            shown.source.settingsResult =
                dev.anydm.engine.queue.CommandResult
                    .Invalid("not today")
            onNodeWithContentDescription("Increase simultaneous downloads").performClick()
            onNodeWithContentDescription("Save limits").performClick()
            until { has("Not saved: not today") }
            assertTrue(shown.closed.isEmpty())
            shown.scope.cancel()
        }
}
