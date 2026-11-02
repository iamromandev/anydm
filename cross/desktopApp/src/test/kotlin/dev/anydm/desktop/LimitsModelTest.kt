package dev.anydm.desktop

import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.DownloadStatus
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
import dev.anydm.engine.queue.CommandResult
import dev.anydm.engine.store.DayTraffic
import dev.anydm.engine.store.TransferSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import java.time.LocalDate as JavaDate

@OptIn(ExperimentalCoroutinesApi::class)
internal class LimitsModelTest {
    private val wifi = "en0"
    private val usb = "en7"

    private fun net(key: String) = BondedNetwork(NetworkInfo(key, key, true, emptyList()), true, BindStatus.ISOLATED, null, false)

    private fun status(
        key: String,
        used: Long,
        limit: Long,
    ) = AllowanceStatus(
        key,
        limit,
        used,
        LocalDate(2026, 11, 1),
        LocalDate(2026, 11, 30),
        Instant.parse("2026-12-01T00:00:00Z"),
        null,
        used >= limit,
    )

    private class Setup(
        val source: FakeSource,
        val model: LimitsModel,
    ) {
        val state get() = model.state.value
        val calls get() = synchronized(source.calls) { source.calls.toList() }
    }

    private fun limits(
        unit: SpeedUnit = SpeedUnit.BYTES,
        page: String? = null,
        configure: (FakeSource) -> Unit = {},
        block: suspend TestScope.(Setup) -> Unit,
    ) = runTest(UnconfinedTestDispatcher()) {
        val source = FakeSource()
        source.networks.value = BondingState(listOf(net(wifi), net(usb)))
        configure(source)
        block(Setup(source, LimitsModel(source, unit, backgroundScope, page, pollMillis = 0, today = { JavaDate.of(2026, 11, 15) })))
    }

    // Opening

    @Test
    fun `it opens with what the engine has`() =
        limits(configure = {
            it.transfer = TransferSettings(globalSpeedLimit = 5 * MIB, slowMode = true, slowModeLimit = MIB / 2, concurrencyLimit = 2)
        }) { s ->
            val g = s.state.draft.general
            assertFalse(s.state.loading)
            assertTrue(g.totalOn)
            assertEquals("5", g.totalText)
            assertTrue(g.slowOn)
            assertEquals("0.5", g.slowText)
            assertEquals(2, g.atOnce)
            assertFalse(s.state.dirty)
        }

    @Test
    fun `no limit keeps a value ready to switch on`() =
        limits { s ->
            val g = s.state.draft.general
            assertFalse(g.totalOn)
            assertEquals("20", g.totalText)
            assertEquals("1", g.slowText)
            val n =
                s.state.draft.networks
                    .getValue(wifi)
            assertFalse(n.speedOn)
            assertEquals("10", n.speedText)
            assertFalse(n.dataOn)
            assertEquals("5", n.dataText)
            assertEquals(DataPeriod.MONTH, n.period)
        }

    @Test
    fun `a network's saved limits are what its page opens with`() =
        limits(configure = {
            it.rules =
                NetworkRules(
                    networks =
                        mapOf(
                            usb to
                                NetworkPolicy(
                                    speedLimit = 3 * MIB,
                                    allowance = Allowance(2 * GIB, AllowancePeriod.Weekly(DayOfWeek.WEDNESDAY)),
                                ),
                        ),
                )
        }) { s ->
            val n =
                s.state.draft.networks
                    .getValue(usb)
            assertTrue(n.speedOn)
            assertEquals("3", n.speedText)
            assertTrue(n.dataOn)
            assertEquals("2", n.dataText)
            assertEquals(DataPeriod.WEEK, n.period)
        }

    @Test
    fun `the page it was asked for is the one shown`() = limits(page = usb) { s -> assertEquals(usb, s.state.page) }

    @Test
    fun `speeds are typed in the unit of the footer`() =
        limits(unit = SpeedUnit.BITS, configure = { it.transfer = TransferSettings(globalSpeedLimit = 1_250_000) }) { s ->
            assertEquals("10", s.state.draft.general.totalText)
            s.model.setTotalText("16")
            s.model.setSlowOn(true)
            s.model.setTotalText("8")
            runSave(s)
            assertEquals(1_000_000L, s.source.transfer.globalSpeedLimit)
        }

    @Test
    fun `a failed read shows why instead of an empty dialog`() =
        limits(configure = { it.refuseAll = true }) { s ->
            assertFalse(s.state.loading)
            assertNotNull(s.state.failure)
            assertFalse(s.state.canSave)
        }

    // Dirty

    @Test
    fun `an untouched draft is clean and can't be saved`() =
        limits { s ->
            assertFalse(s.state.dirty)
            assertFalse(s.state.canSave)
        }

    @Test
    fun `changing a number makes it dirty and changing it back makes it clean`() =
        limits { s ->
            s.model.setTotalLimited(true)
            assertTrue(s.state.dirty)
            s.model.setTotalLimited(false)
            assertFalse(s.state.dirty)
            s.model.stepAtOnce(1)
            assertTrue(s.state.dirty)
            s.model.stepAtOnce(-1)
            assertFalse(s.state.dirty)
        }

    @Test
    fun `retyping a value the same is not a change`() =
        limits(configure = { it.transfer = TransferSettings(globalSpeedLimit = 5 * MIB) }) { s ->
            s.model.setTotalText("5.00")
            assertFalse(s.state.dirty)
            s.model.setTotalText("6")
            assertTrue(s.state.dirty)
        }

    @Test
    fun `a value that does not come out whole in the unit is not a change until it is touched`() =
        limits(configure = { it.transfer = TransferSettings(globalSpeedLimit = 1_000_000) }) { s ->
            assertFalse(s.state.dirty)
            // 1,000,000 B/s reads as 0.954 MB/s; saving something else must not round it.
            s.model.stepAtOnce(1)
            runSave(s)
            assertEquals(1_000_000L, s.source.transfer.globalSpeedLimit)
        }

    @Test
    fun `a text left in a switched-off field is not a change`() =
        limits { s ->
            s.model.setTotalText("33")
            assertFalse(s.state.dirty)
            s.model.setSpeedText(wifi, "7")
            s.model.setDataText(wifi, "9")
            assertFalse(s.state.dirty)
        }

    @Test
    fun `a period picked for a data limit that is off is not a change`() =
        limits { s ->
            s.model.setPeriod(wifi, DataPeriod.DAY)
            assertFalse(s.state.dirty)
            s.model.setDataLimited(wifi, true)
            assertTrue(s.state.dirty)
        }

    // Validation

    @Test
    fun `a speed that is no number above zero is refused and stops the save`() =
        limits { s ->
            s.model.setTotalLimited(true)
            for (bad in listOf("", "0", "0.0", ".", ",")) {
                s.model.setTotalText(bad)
                assertNotNull(s.state.errorOf(null, LimitField.TOTAL), "'$bad'")
                assertFalse(s.state.canSave)
            }
            s.model.setTotalText("1,5")
            assertNull(s.state.errorOf(null, LimitField.TOTAL))
            assertTrue(s.state.canSave)
        }

    @Test
    fun `letters and signs are not taken into a number field`() =
        limits { s ->
            s.model.setTotalText("1e5-x 7")
            assertEquals("157", s.state.draft.general.totalText)
        }

    @Test
    fun `a number above what fits in a long is refused`() =
        limits { s ->
            s.model.setTotalLimited(true)
            s.model.setTotalText("99999999999")
            assertNotNull(s.state.errorOf(null, LimitField.TOTAL))
        }

    @Test
    fun `a field that is switched off is not judged`() =
        limits { s ->
            s.model.setTotalText("")
            s.model.setSpeedText(wifi, "")
            assertTrue(s.state.errors.isEmpty())
        }

    @Test
    fun `a bad number on another page still stops the save and says where`() =
        limits { s ->
            s.model.setDataLimited(usb, true)
            s.model.setDataText(usb, "0")
            s.model.select(null)
            val error = s.state.errors.single()
            assertEquals(usb, error.page)
            assertEquals(LimitField.DATA, error.field)
            assertFalse(s.state.canSave)
        }

    @Test
    fun `the slow mode speed is judged even while slow mode is off`() =
        limits { s ->
            s.model.setSlowText("0")
            assertNotNull(s.state.errorOf(null, LimitField.SLOW))
        }

    @Test
    fun `the stepper stays between one and four`() =
        limits(configure = { it.transfer = TransferSettings(concurrencyLimit = 1) }) { s ->
            s.model.stepAtOnce(-1)
            assertEquals(1, s.state.draft.general.atOnce)
            repeat(10) { s.model.stepAtOnce(1) }
            assertEquals(MAX_AT_ONCE, s.state.draft.general.atOnce)
        }

    @Test
    fun `a number of downloads the stepper can't reach can come down but not go up`() =
        limits(configure = { it.transfer = TransferSettings(concurrencyLimit = 8) }) { s ->
            s.model.stepAtOnce(1)
            assertEquals(8, s.state.draft.general.atOnce)
            s.model.stepAtOnce(-1)
            assertEquals(7, s.state.draft.general.atOnce)
        }

    @Test
    fun `a schedule with equal times or no days is refused`() =
        limits { s ->
            s.model.setScheduleEnabled(true)
            assertTrue(s.state.canSave)
            s.model.setScheduleStart(120)
            s.model.setScheduleEnd(120)
            assertNotNull(s.state.errorOf(null, LimitField.SCHEDULE))
            s.model.setScheduleEnd(180)
            DayOfWeek.entries.forEach(s.model::toggleScheduleDay)
            assertNotNull(s.state.errorOf(null, LimitField.SCHEDULE))
            assertFalse(s.state.canSave)
        }

    // Save: mapping to the engine

    private suspend fun runSave(s: Setup) {
        s.model.save()
    }

    @Test
    fun `save writes the total speed, slow mode and downloads at once`() =
        limits { s ->
            s.model.setTotalLimited(true)
            s.model.setTotalText("12.5")
            s.model.setSlowText("2")
            s.model.setSlowOn(true)
            s.model.stepAtOnce(1)
            runSave(s)
            assertEquals((12.5 * MIB).toLong(), s.source.transfer.globalSpeedLimit)
            assertEquals(2 * MIB, s.source.transfer.slowModeLimit)
            assertTrue(s.source.transfer.slowMode)
            assertEquals(4, s.source.transfer.concurrencyLimit)
            assertTrue(s.state.done)
        }

    @Test
    fun `save leaves a speed limit that was switched off as no limit`() =
        limits(configure = { it.transfer = TransferSettings(globalSpeedLimit = 5 * MIB) }) { s ->
            s.model.setTotalLimited(false)
            runSave(s)
            assertNull(s.source.transfer.globalSpeedLimit)
        }

    @Test
    fun `save writes only the fields that changed so the footer's slow mode is not written over`() =
        limits { s ->
            s.model.stepAtOnce(1)
            // The footer's switch is used while the dialog is open.
            s.source.transfer = s.source.transfer.copy(slowMode = true)
            runSave(s)
            assertTrue(s.source.transfer.slowMode)
            assertEquals(4, s.source.transfer.concurrencyLimit)
        }

    @Test
    fun `save writes nothing for a part that did not change`() =
        limits { s ->
            s.model.setSpeedLimited(wifi, true)
            runSave(s)
            assertFalse("applyTransferSettings" in s.calls)
            assertTrue(s.calls.any { it.startsWith("setNetworkPolicy $wifi") })
            assertFalse(s.calls.any { it.startsWith("setNetworkPolicy $usb") })
        }

    @Test
    fun `save writes a network's speed limit and data limit with its period`() =
        limits { s ->
            s.model.setSpeedLimited(usb, true)
            s.model.setSpeedText(usb, "2.5")
            s.model.setDataLimited(usb, true)
            s.model.setDataText(usb, "10")
            s.model.setPeriod(usb, DataPeriod.DAY)
            runSave(s)
            val policy =
                s.source.rules.networks
                    .getValue(usb)
            assertEquals((2.5 * MIB).toLong(), policy.speedLimit)
            assertEquals(Allowance(10 * GIB, AllowancePeriod.Daily), policy.allowance)
        }

    @Test
    fun `the week starts on monday and the month on the first`() =
        limits { s ->
            s.model.setDataLimited(usb, true)
            s.model.setPeriod(usb, DataPeriod.WEEK)
            s.model.setDataLimited(wifi, true)
            runSave(s)
            assertEquals(
                AllowancePeriod.Weekly(DayOfWeek.MONDAY),
                s.source.rules.networks
                    .getValue(usb)
                    .allowance
                    ?.period,
            )
            assertEquals(
                AllowancePeriod.Monthly(1),
                s.source.rules.networks
                    .getValue(wifi)
                    .allowance
                    ?.period,
            )
        }

    @Test
    fun `changing a limit keeps the rest of the policy and the details of the period`() =
        limits(configure = {
            it.rules =
                NetworkRules(
                    networks =
                        mapOf(
                            usb to
                                NetworkPolicy(
                                    uploadLimit = 500,
                                    metered = true,
                                    allowance = Allowance(2 * GIB, AllowancePeriod.Weekly(DayOfWeek.WEDNESDAY), 80, AllowanceAction.SLOW),
                                ),
                        ),
                )
        }) { s ->
            s.model.setDataText(usb, "3")
            runSave(s)
            val policy =
                s.source.rules.networks
                    .getValue(usb)
            assertEquals(500L, policy.uploadLimit)
            assertTrue(policy.metered)
            assertEquals(Allowance(3 * GIB, AllowancePeriod.Weekly(DayOfWeek.WEDNESDAY), 80, AllowanceAction.SLOW), policy.allowance)
        }

    @Test
    fun `changing the kind of period drops the old reset day`() =
        limits(configure = {
            it.rules =
                NetworkRules(
                    networks =
                        mapOf(usb to NetworkPolicy(allowance = Allowance(2 * GIB, AllowancePeriod.Weekly(DayOfWeek.WEDNESDAY)))),
                )
        }) { s ->
            s.model.setPeriod(usb, DataPeriod.MONTH)
            runSave(s)
            assertEquals(
                AllowancePeriod.Monthly(1),
                s.source.rules.networks
                    .getValue(usb)
                    .allowance
                    ?.period,
            )
        }

    @Test
    fun `a network with nothing left to say loses its entry`() =
        limits(configure = { it.rules = NetworkRules(networks = mapOf(usb to NetworkPolicy(speedLimit = MIB))) }) { s ->
            s.model.setSpeedLimited(usb, false)
            runSave(s)
            assertFalse(usb in s.source.rules.networks)
        }

    @Test
    fun `a network with nothing to say keeps its entry under a default that limits`() =
        limits(configure = {
            it.rules = NetworkRules(default = NetworkPolicy(speedLimit = 9 * MIB), networks = mapOf(usb to NetworkPolicy(speedLimit = MIB)))
        }) { s ->
            s.model.setSpeedLimited(usb, false)
            runSave(s)
            assertEquals(NetworkPolicy(), s.source.rules.networks[usb])
        }

    @Test
    fun `remove limits clears both limits of a network in the draft and save writes it`() =
        limits(configure = {
            it.rules =
                NetworkRules(networks = mapOf(usb to NetworkPolicy(speedLimit = MIB, allowance = Allowance(GIB, AllowancePeriod.Daily))))
        }) { s ->
            assertTrue(s.model.hasLimits(usb))
            s.model.removeLimits(usb)
            assertFalse(s.model.hasLimits(usb))
            // Nothing is written until Save.
            assertTrue(s.calls.none { it.startsWith("setNetworkPolicy") })
            runSave(s)
            assertFalse(usb in s.source.rules.networks)
        }

    @Test
    fun `a limit set elsewhere meanwhile is not lost to a save of another field`() =
        limits(configure = { it.rules = NetworkRules(networks = mapOf(usb to NetworkPolicy(speedLimit = MIB))) }) { s ->
            s.model.setDataLimited(usb, true)
            // A metered mark arrives while the dialog is open.
            s.source.rules = NetworkRules(networks = mapOf(usb to NetworkPolicy(speedLimit = MIB, metered = true)))
            runSave(s)
            assertTrue(
                s.source.rules.networks
                    .getValue(usb)
                    .metered,
            )
        }

    // Save: failures and cancel

    @Test
    fun `a refused save keeps the draft, says why and does not close`() =
        limits { s ->
            s.source.results["setNetworkPolicy $usb"] = CommandResult.Invalid("no way")
            s.model.setSpeedLimited(usb, true)
            s.model.stepAtOnce(1)
            runSave(s)
            assertFalse(s.state.done)
            assertTrue(s.state.message!!.contains("no way"))
            assertTrue(
                s.state.draft.networks
                    .getValue(usb)
                    .speedOn,
            )
            assertTrue(s.state.dirty)
            assertFalse(s.state.saving)
        }

    @Test
    fun `after a partial save only what was refused is still a change`() =
        limits { s ->
            s.model.stepAtOnce(1)
            s.model.setSpeedLimited(usb, true)
            s.source.results["setNetworkPolicy $usb"] = CommandResult.Invalid("no way")
            runSave(s)
            // The networks come first and stop the save; nothing else was written.
            assertEquals(3, s.source.transfer.concurrencyLimit)
            s.source.results.clear()
            runSave(s)
            assertTrue(s.state.done)
            assertEquals(4, s.source.transfer.concurrencyLimit)
            assertTrue(
                s.source.rules.networks
                    .getValue(usb)
                    .speedLimit != null,
            )
        }

    @Test
    fun `a transfer settings refusal is reported and keeps the written network limit`() =
        limits { s ->
            s.model.setSpeedLimited(usb, true)
            s.model.stepAtOnce(1)
            s.source.settingsResult = CommandResult.Invalid("bad settings")
            runSave(s)
            assertTrue(
                s.source.rules.networks
                    .getValue(usb)
                    .speedLimit != null,
            )
            assertTrue(s.state.message!!.contains("bad settings"))
            assertFalse(s.state.done)
            // The network limit is now the engine's own; only the stepper is left as a change.
            assertTrue(s.state.dirty)
        }

    @Test
    fun `a stopped engine at save is said`() =
        limits { s ->
            s.model.stepAtOnce(1)
            s.source.commandThrows = IllegalStateException("the engine has stopped")
            runSave(s)
            assertFalse(s.state.done)
            assertNotNull(s.state.message)
        }

    @Test
    fun `cancel writes nothing and closes`() =
        limits { s ->
            s.model.setTotalLimited(true)
            s.model.setSpeedLimited(usb, true)
            s.model.stepAtOnce(1)
            s.model.cancel()
            assertTrue(s.state.done)
            assertTrue(s.calls.isEmpty())
            assertEquals(TransferSettings(), s.source.transfer)
            assertTrue(
                s.source.rules.networks
                    .isEmpty(),
            )
        }

    @Test
    fun `save does nothing for a clean or invalid draft`() =
        limits { s ->
            runSave(s)
            assertFalse(s.state.done)
            s.model.setTotalLimited(true)
            s.model.setTotalText("0")
            runSave(s)
            assertFalse(s.state.done)
            assertTrue(s.calls.isEmpty())
        }

    @Test
    fun `a saved data limit that slows at the limit is read as such and keeps doing that when saved again`() =
        limits(configure = {
            it.rules =
                NetworkRules(
                    networks =
                        mapOf(usb to NetworkPolicy(allowance = Allowance(2 * GIB, AllowancePeriod.Daily, atLimit = AllowanceAction.SLOW))),
                )
        }) { s ->
            assertEquals(
                AllowanceAction.SLOW,
                s.state.draft.networks
                    .getValue(usb)
                    .atLimit,
            )
            assertEquals(
                AllowanceAction.PAUSE,
                s.state.draft.networks
                    .getValue(wifi)
                    .atLimit,
            )
            s.model.setPeriod(usb, DataPeriod.WEEK)
            runSave(s)
            assertEquals(
                AllowanceAction.SLOW,
                s.source.rules.networks
                    .getValue(usb)
                    .allowance
                    ?.atLimit,
            )
        }

    // Mbps: a default that does not come out whole

    @Test
    fun `the default slow mode speed is the default in Mbps although it reads 8_39`() =
        limits(unit = SpeedUnit.BITS) { s ->
            assertEquals("8.39", s.state.draft.general.slowText)
            assertTrue(s.model.generalIsDefault())
        }

    @Test
    fun `reset to defaults in Mbps writes the exact default speed`() =
        limits(unit = SpeedUnit.BITS, configure = {
            it.transfer = TransferSettings(slowModeLimit = 3 * MIB, concurrencyLimit = 2)
        }) { s ->
            assertFalse(s.model.generalIsDefault())
            s.model.resetGeneral()
            assertTrue(s.model.generalIsDefault())
            assertTrue(s.state.dirty)
            runSave(s)
            assertEquals(MIB, s.source.transfer.slowModeLimit)
            assertEquals(3, s.source.transfer.concurrencyLimit)
        }

    @Test
    fun `the default speed typed back in Mbps is the exact default and not a change from it`() =
        limits(unit = SpeedUnit.BITS) { s ->
            s.model.setSlowText("8.39")
            assertFalse(s.state.dirty)
            s.model.setSlowText("9")
            assertTrue(s.state.dirty)
            s.model.setSlowText("8.39")
            assertFalse(s.state.dirty)
        }

    @Test
    fun `a speed set to 8_39 in Mbps over another one is written as the exact default`() =
        limits(unit = SpeedUnit.BITS, configure = { it.transfer = TransferSettings(slowModeLimit = 3 * MIB) }) { s ->
            s.model.setSlowText("8.39")
            assertTrue(s.state.dirty)
            runSave(s)
            assertEquals(MIB, s.source.transfer.slowModeLimit)
        }

    @Test
    fun `an untouched speed that does not come out whole in Mbps is still not rounded when saving something else`() =
        limits(unit = SpeedUnit.BITS, configure = { it.transfer = TransferSettings(slowModeLimit = 3 * MIB) }) { s ->
            s.model.stepAtOnce(1)
            runSave(s)
            assertEquals(3 * MIB, s.source.transfer.slowModeLimit)
        }

    // Reset to defaults and remove limits

    @Test
    fun `reset to defaults puts all downloads back in the draft and leaves the networks`() =
        limits(configure = {
            it.transfer = TransferSettings(globalSpeedLimit = 5 * MIB, slowMode = true, slowModeLimit = 3 * MIB, concurrencyLimit = 2)
            it.rules = NetworkRules(networks = mapOf(usb to NetworkPolicy(speedLimit = MIB)))
        }) { s ->
            s.model.setScheduleEnabled(true)
            assertFalse(s.model.generalIsDefault())
            s.model.resetGeneral()
            val g = s.state.draft.general
            assertFalse(g.totalOn)
            assertFalse(g.slowOn)
            assertEquals("1", g.slowText)
            assertEquals(TransferSettings().concurrencyLimit, g.atOnce)
            assertFalse(g.schedule.enabled)
            assertTrue(s.model.generalIsDefault())
            assertTrue(
                s.state.draft.networks
                    .getValue(usb)
                    .speedOn,
            )
            // The reset is a draft: the engine still has the old values until Save.
            assertEquals(5 * MIB, s.source.transfer.globalSpeedLimit)
            runSave(s)
            assertNull(s.source.transfer.globalSpeedLimit)
            assertFalse(s.source.transfer.slowMode)
            assertEquals(MIB, s.source.transfer.slowModeLimit)
            assertEquals(3, s.source.transfer.concurrencyLimit)
            assertEquals(
                MIB,
                s.source.rules.networks
                    .getValue(usb)
                    .speedLimit,
            )
        }

    @Test
    fun `a fresh install is already at defaults`() = limits { s -> assertTrue(s.model.generalIsDefault()) }

    // Schedule

    @Test
    fun `a schedule is stored as pause rules outside its window and read back`() =
        limits { s ->
            s.model.setScheduleEnabled(true)
            s.model.setScheduleStart(22 * 60)
            s.model.setScheduleEnd(6 * 60)
            runSave(s)
            val rules = s.source.scheduleList
            assertTrue(rules.isNotEmpty())
            assertTrue(rules.all { it.action == RuleAction.PAUSE })
            val again = LimitsModel(s.source, SpeedUnit.BYTES, backgroundScope, pollMillis = 0)
            val schedule = again.state.value.draft.general.schedule
            assertEquals(DownloadSchedule(true, 22 * 60, 6 * 60, DayOfWeek.entries.toSet()), schedule)
            assertFalse(again.state.value.dirty)
        }

    @Test
    fun `turning the schedule off removes its rules`() =
        limits(configure = {
            it.scheduleList = emptyList()
        }) { s ->
            s.model.setScheduleEnabled(true)
            runSave(s)
            assertTrue(s.source.scheduleList.isNotEmpty())
            s.model.setScheduleEnabled(false)
            runSave(s)
            assertTrue(s.source.scheduleList.isEmpty())
        }

    @Test
    fun `changing a schedule replaces its rules and does not pile them up`() =
        limits { s ->
            s.model.setScheduleEnabled(true)
            runSave(s)
            val first = s.source.scheduleList.size
            s.model.setScheduleStart(60)
            s.model.setScheduleEnd(120)
            runSave(s)
            assertEquals(scheduleRulesFor(DownloadSchedule(true, 60, 120)).size, s.source.scheduleList.size)
            assertTrue(first > 0)
        }

    @Test
    fun `rules that only slow are not touched by the schedule`() =
        limits(configure = {
            it.scheduleList = listOf(ScheduleRule(ScheduleRuleId(50), setOf(DayOfWeek.MONDAY), 60, 120, RuleAction.SLOW))
        }) { s ->
            s.model.setScheduleEnabled(true)
            runSave(s)
            s.model.setScheduleEnabled(false)
            runSave(s)
            assertEquals(listOf(RuleAction.SLOW), s.source.scheduleList.map { it.action })
        }

    @Test
    fun `pause rules from elsewhere are kept until a schedule is turned on`() =
        limits(configure = {
            it.scheduleList = listOf(ScheduleRule(ScheduleRuleId(50), setOf(DayOfWeek.MONDAY), 60, 120, RuleAction.PAUSE))
        }) { s ->
            assertTrue(s.state.foreignSchedule)
            assertFalse(s.state.draft.general.schedule.enabled)
            assertFalse(s.state.dirty)
            s.model.stepAtOnce(1)
            runSave(s)
            assertEquals(1, s.source.scheduleList.size)
            assertEquals(
                50L,
                s.source.scheduleList
                    .single()
                    .id.value,
            )
        }

    @Test
    fun `turning a schedule on replaces the pause rules from elsewhere once that is confirmed`() =
        limits(configure = {
            it.scheduleList = listOf(ScheduleRule(ScheduleRuleId(50), setOf(DayOfWeek.MONDAY), 60, 120, RuleAction.PAUSE))
        }) { s ->
            s.model.setScheduleEnabled(true)
            s.model.save(replaceForeign = true)
            assertTrue(s.state.done)
            assertTrue(s.source.scheduleList.none { it.id.value == 50L })
            assertTrue(s.source.scheduleList.isNotEmpty())
        }

    private fun foreignRule(
        id: Long = 50,
        days: Set<DayOfWeek> = setOf(DayOfWeek.MONDAY),
        start: Int = 60,
        end: Int = 120,
    ) = ScheduleRule(ScheduleRuleId(id), days, start, end, RuleAction.PAUSE)

    private val weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)

    private fun schedulePuts(s: Setup) = s.calls.filter { it.startsWith("addScheduleRule") || it.startsWith("removeScheduleRule") }

    @Test
    fun `pause rules from elsewhere are named in words`() =
        limits(configure = {
            it.scheduleList = listOf(foreignRule(50, weekdays, 9 * 60, 17 * 60), foreignRule(51, setOf(DayOfWeek.SATURDAY), 0, 0))
        }) { s ->
            assertEquals(listOf("Pause Mon–Fri 09:00–17:00", "Pause Sat all day"), s.state.foreignRules)
        }

    @Test
    fun `a schedule made by the dialog is not pause rules from elsewhere`() =
        limits { s ->
            s.model.setScheduleEnabled(true)
            runSave(s)
            val again = LimitsModel(s.source, SpeedUnit.BYTES, backgroundScope, pollMillis = 0)
            assertTrue(
                again.state.value.foreignRules
                    .isEmpty(),
            )
        }

    @Test
    fun `saving a schedule over pause rules from elsewhere writes nothing until the replacement is confirmed`() =
        limits(configure = { it.scheduleList = listOf(foreignRule()) }) { s ->
            s.model.setScheduleEnabled(true)
            assertTrue(s.state.replacesForeign)
            s.model.save()
            assertFalse(s.state.done)
            assertTrue(schedulePuts(s).isEmpty())
            assertEquals(listOf(50L), s.source.scheduleList.map { it.id.value })
        }

    @Test
    fun `nothing is replaced while the schedule stays off, so nothing needs confirming`() =
        limits(configure = { it.scheduleList = listOf(foreignRule()) }) { s ->
            s.model.stepAtOnce(1)
            assertFalse(s.state.replacesForeign)
            s.model.save()
            assertTrue(s.state.done)
            assertEquals(listOf(50L), s.source.scheduleList.map { it.id.value })
        }

    @Test
    fun `a schedule over no other pause rules needs no confirming`() =
        limits { s ->
            s.model.setScheduleEnabled(true)
            assertFalse(s.state.replacesForeign)
            s.model.save()
            assertTrue(s.state.done)
        }

    @Test
    fun `reset to defaults leaves the pause rules from elsewhere and the schedule stays off`() =
        limits(configure = { it.scheduleList = listOf(foreignRule()) }) { s ->
            s.model.setScheduleEnabled(true)
            s.model.resetGeneral()
            assertFalse(s.state.replacesForeign)
            assertFalse(s.state.dirty)
            assertEquals(listOf(50L), s.source.scheduleList.map { it.id.value })
        }

    @Test
    fun `the new schedule rules are added before the old ones are removed`() =
        limits(configure = { it.scheduleList = listOf(foreignRule()) }) { s ->
            s.model.setScheduleEnabled(true)
            s.model.save(replaceForeign = true)
            val puts = schedulePuts(s)
            assertTrue(puts.isNotEmpty())
            assertTrue(puts.indexOfLast { it.startsWith("addScheduleRule") } < puts.indexOfFirst { it.startsWith("removeScheduleRule") })
            assertEquals(listOf("removeScheduleRule 50"), puts.filter { it.startsWith("removeScheduleRule") })
        }

    @Test
    fun `a rule removed since the dialog opened is not removed again`() =
        limits(configure = { it.scheduleList = listOf(foreignRule()) }) { s ->
            s.source.scheduleList = emptyList()
            s.model.setScheduleEnabled(true)
            s.model.save(replaceForeign = true)
            assertTrue(s.state.done)
            assertTrue(s.calls.none { it == "removeScheduleRule 50" })
        }

    @Test
    fun `a pause rule that came while the dialog was open is not removed unseen`() =
        limits { s ->
            s.source.scheduleList = listOf(foreignRule(60))
            s.model.setScheduleEnabled(true)
            s.model.save(replaceForeign = true)
            assertFalse(s.state.done)
            assertTrue(s.state.message!!.contains("Pause Mon 01:00–02:00"))
            assertTrue(schedulePuts(s).isEmpty())
            assertEquals(listOf(60L), s.source.scheduleList.map { it.id.value })
        }

    @Test
    fun `turning the schedule off does not remove a pause rule that came while the dialog was open`() =
        limits(configure = {
            it.scheduleList =
                scheduleRulesFor(DownloadSchedule(true, 60, 120)).mapIndexed { i, r ->
                    ScheduleRule(ScheduleRuleId(i + 1L), r.days, r.startMinute, r.endMinute, r.action)
                }
        }) { s ->
            assertTrue(s.state.draft.general.schedule.enabled)
            val ours = s.source.scheduleList
            s.source.scheduleList = ours + foreignRule(60)
            s.model.setScheduleEnabled(false)
            s.model.save()
            assertFalse(s.state.done)
            assertEquals(ours.map { it.id } + ScheduleRuleId(60), s.source.scheduleList.map { it.id })
        }

    @Test
    fun `a rule that fails to be added takes back the ones added and keeps the old rules`() =
        limits(configure = {
            it.scheduleList = listOf(foreignRule())
            it.addRuleFailsAt = 2
        }) { s ->
            s.model.setScheduleEnabled(true)
            s.model.setScheduleStart(9 * 60)
            s.model.setScheduleEnd(17 * 60)
            s.model.toggleScheduleDay(DayOfWeek.SATURDAY)
            s.model.toggleScheduleDay(DayOfWeek.SUNDAY)
            s.model.save(replaceForeign = true)
            assertFalse(s.state.done)
            assertTrue(s.state.message!!.contains("disk full"))
            assertEquals(listOf(50L), s.source.scheduleList.map { it.id.value })
        }

    @Test
    fun `a rule stored but not answered is taken back by difference`() =
        limits(configure = {
            it.scheduleList = listOf(foreignRule())
            it.addRuleFailsAfterStoringAt = 2
        }) { s ->
            s.model.setScheduleEnabled(true)
            s.model.setScheduleStart(9 * 60)
            s.model.setScheduleEnd(17 * 60)
            s.model.toggleScheduleDay(DayOfWeek.SATURDAY)
            s.model.toggleScheduleDay(DayOfWeek.SUNDAY)
            s.model.save(replaceForeign = true)
            assertFalse(s.state.done)
            assertTrue(s.state.message!!.contains("answer lost"))
            assertEquals(listOf(50L), s.source.scheduleList.map { it.id.value })
        }

    @Test
    fun `a take back that fails is reported and not swallowed`() =
        limits(configure = {
            it.scheduleList = listOf(foreignRule())
            it.addRuleFailsAt = 2
            it.removeRuleFails = setOf(1L)
        }) { s ->
            s.model.setScheduleEnabled(true)
            s.model.setScheduleStart(9 * 60)
            s.model.setScheduleEnd(17 * 60)
            s.model.toggleScheduleDay(DayOfWeek.SATURDAY)
            s.model.toggleScheduleDay(DayOfWeek.SUNDAY)
            s.model.save(replaceForeign = true)
            assertFalse(s.state.done)
            assertTrue(s.state.message!!.contains("disk full"))
            assertTrue(s.state.message!!.contains("1 new pause rule couldn't be taken back"), s.state.message)
        }

    @Test
    fun `old rules that fail to be removed are reported after the others were removed`() =
        limits(configure = {
            it.scheduleList = listOf(foreignRule(50), foreignRule(51), foreignRule(52))
            it.removeRuleFails = setOf(50L, 52L)
        }) { s ->
            s.model.setScheduleEnabled(true)
            s.model.save(replaceForeign = true)
            assertFalse(s.state.done)
            assertEquals("Schedule saved, but 2 old pause rules couldn't be removed.", s.state.message)
            assertTrue(s.source.scheduleList.none { it.id.value == 51L })
            assertTrue(s.source.scheduleList.any { it.id.value == 50L })
            assertTrue(s.source.scheduleList.any { it.id.value == 52L })
            assertEquals(3, s.calls.count { it.startsWith("removeScheduleRule") })
        }

    @Test
    fun `one old rule that fails to be removed is named in the singular`() =
        limits(configure = {
            it.scheduleList = listOf(foreignRule(50))
            it.removeRuleFails = setOf(50L)
        }) { s ->
            s.model.setScheduleEnabled(true)
            s.model.save(replaceForeign = true)
            assertEquals("Schedule saved, but 1 old pause rule couldn't be removed.", s.state.message)
        }

    @Test
    fun `a pause rule that came while the dialog was open stops the save before any policy is written`() =
        limits { s ->
            s.model.setSpeedLimited(wifi, true)
            s.model.setSpeedText(wifi, "2")
            s.model.setSlowOn(true)
            s.model.setScheduleEnabled(true)
            s.source.scheduleList = listOf(foreignRule(70))
            s.model.save(replaceForeign = true)
            assertFalse(s.state.done)
            assertTrue(s.state.message!!.contains("set up while this dialog was open"))
            assertTrue(s.calls.none { it.startsWith("setNetworkPolicy") || it.startsWith("applyTransferSettings") }, s.calls.toString())
        }

    @Test
    fun `a save that is refused for want of confirming a replacement says so`() =
        limits(configure = { it.scheduleList = listOf(foreignRule()) }) { s ->
            s.model.setScheduleEnabled(true)
            s.model.save()
            assertFalse(s.state.done)
            assertNotNull(s.state.message)
            assertTrue(schedulePuts(s).isEmpty())
        }

    // A cleared time box

    @Test
    fun `a window ends the next day only when both times are set`() {
        assertTrue(DownloadSchedule(true, 22 * 60, 6 * 60).endsNextDay)
        assertFalse(DownloadSchedule(true, 6 * 60, 22 * 60).endsNextDay)
        assertFalse(DownloadSchedule(true, 6 * 60, NO_TIME).endsNextDay)
        assertFalse(DownloadSchedule(true, NO_TIME, 6 * 60).endsNextDay)
    }

    @Test
    fun `a time box left empty makes the schedule invalid instead of keeping the old time`() =
        limits { s ->
            s.model.setScheduleEnabled(true)
            s.model.setScheduleEnd(NO_TIME)
            assertNotNull(s.state.errorOf(null, LimitField.SCHEDULE))
            assertFalse(s.state.canSave)
            assertFalse(s.state.draft.general.schedule.valid)
            s.model.setScheduleEnd(6 * 60)
            assertNull(s.state.errorOf(null, LimitField.SCHEDULE))
            assertTrue(s.state.canSave)
        }

    // Usage

    @Test
    fun `usage of a network with a data limit is the engine's usage in its period`() =
        limits(configure = {
            it.rules = NetworkRules(networks = mapOf(usb to NetworkPolicy(allowance = Allowance(2 * GIB, AllowancePeriod.Daily))))
            it.usage = mapOf(usb to status(usb, 700 * MIB, 2 * GIB))
        }) { s ->
            assertEquals(NetworkUsage(700 * MIB, DataPeriod.DAY), s.state.usage.getValue(usb))
            assertTrue(usb in s.state.resettable)
        }

    @Test
    fun `usage of a network with no data limit is its traffic this month`() =
        limits(configure = {
            it.traffic =
                listOf(
                    DayTraffic("2026-11-02", wifi, DownloadKind.DIRECT, 100 * MIB, 5 * MIB),
                    DayTraffic("2026-11-10", wifi, DownloadKind.TORRENT, 50 * MIB),
                    DayTraffic("2026-11-10", usb, DownloadKind.DIRECT, MIB),
                )
        }) { s ->
            assertEquals(NetworkUsage(155 * MIB, DataPeriod.MONTH), s.state.usage.getValue(wifi))
            assertEquals(NetworkUsage(MIB, DataPeriod.MONTH), s.state.usage.getValue(usb))
            assertFalse(wifi in s.state.resettable)
        }

    @Test
    fun `traffic of another month is not this month's usage`() =
        limits(configure = { it.traffic = listOf(DayTraffic("2026-10-31", wifi, DownloadKind.DIRECT, 100 * MIB)) }) { s ->
            assertEquals(0L, s.state.usage[wifi]?.bytes ?: 0L)
        }

    @Test
    fun `reset usage asks the engine and reads the usage again`() =
        limits(configure = {
            it.rules = NetworkRules(networks = mapOf(usb to NetworkPolicy(allowance = Allowance(2 * GIB, AllowancePeriod.Daily))))
            it.usage = mapOf(usb to status(usb, 700 * MIB, 2 * GIB))
        }) { s ->
            s.model.resetUsage(usb)
            assertTrue("resetAllowanceUsage $usb" in s.calls)
            assertEquals(
                0L,
                s.state.usage
                    .getValue(usb)
                    .bytes,
            )
            assertFalse(s.state.dirty)
        }

    @Test
    fun `a refused reset says why and changes nothing`() =
        limits(configure = {
            it.rules = NetworkRules(networks = mapOf(usb to NetworkPolicy(allowance = Allowance(2 * GIB, AllowancePeriod.Daily))))
            it.usage = mapOf(usb to status(usb, 700 * MIB, 2 * GIB))
            it.results["resetAllowanceUsage $usb"] = CommandResult.Invalid("nope")
        }) { s ->
            s.model.resetUsage(usb)
            assertTrue(s.state.message!!.contains("nope"))
            assertEquals(
                700 * MIB,
                s.state.usage
                    .getValue(usb)
                    .bytes,
            )
        }

    @Test
    fun `a reset that throws says why`() =
        limits(configure = {
            it.rules = NetworkRules(networks = mapOf(usb to NetworkPolicy(allowance = Allowance(2 * GIB, AllowancePeriod.Daily))))
            it.usage = mapOf(usb to status(usb, 700 * MIB, 2 * GIB))
            it.commandThrows = java.io.IOException("disk full")
        }) { s ->
            s.model.resetUsage(usb)
            assertEquals("Data usage could not be reset: disk full", s.state.message)
            assertEquals(
                700 * MIB,
                s.state.usage
                    .getValue(usb)
                    .bytes,
            )
        }

    @Test
    fun `a reset on a stopped engine says the engine has stopped`() =
        limits(configure = {
            it.rules = NetworkRules(networks = mapOf(usb to NetworkPolicy(allowance = Allowance(2 * GIB, AllowancePeriod.Daily))))
        }) { s ->
            s.source.commandThrows = IllegalStateException("the engine has stopped")
            s.model.resetUsage(usb)
            assertEquals("Data usage could not be reset: the engine has stopped.", s.state.message)
        }

    @Test
    fun `a reset for a network with no data limit says so`() =
        limits { s ->
            s.model.resetUsage(usb)
            assertTrue(s.state.message!!.startsWith("Data usage could not be reset: "))
            assertTrue(s.state.message!!.contains("no data limit"))
        }

    @Test
    fun `reset usage does not save the draft`() =
        limits { s ->
            s.model.stepAtOnce(1)
            s.model.resetUsage(usb)
            assertEquals(TransferSettings(), s.source.transfer)
            assertTrue(s.state.dirty)
        }

    @Test
    fun `it counts the downloads that are going`() =
        limits(configure = {
            it.downloads.value =
                listOf(
                    snapshot(1, DownloadStatus.DOWNLOADING, name = "a"),
                    snapshot(2, DownloadStatus.PAUSED, name = "b"),
                    snapshot(3, DownloadStatus.DOWNLOADING, name = "c"),
                )
        }) { s -> assertEquals(2, s.state.running) }

    @Test
    fun `the amount helpers round trip what people type`() {
        assertEquals(5 * MIB, amountBytes("5", MIB))
        assertEquals((0.5 * MIB).toLong(), amountBytes("0.5", MIB))
        assertEquals(1_000_000L, amountBytes("8", 125_000))
        assertNull(amountBytes("0", MIB))
        assertNull(amountBytes("-1", MIB))
        assertNull(amountBytes("1e3", MIB))
        assertNull(amountBytes("1.2.3", MIB))
        assertNull(amountBytes("", MIB))
        assertEquals(1_048_576L, readAmount("8,39", 125_000, 1_048_576L))
        assertEquals(1_048_576L, readAmount(" 8.39 ", 125_000, 1_048_576L))
        assertEquals("20", amountText(20 * MIB, MIB))
        assertEquals("0.954", amountText(1_000_000, MIB))
        assertEquals("1.5", amountText((1.5 * MIB).toLong(), MIB))
        assertEquals("2", amountText(2 * GIB, GIB))
    }

    // The sidebar

    @Test
    fun `the sidebar says no limit, a limit, slow mode and the schedule's window`() {
        val base = GeneralDraft(false, "20", false, "1", 3, DownloadSchedule())
        assertEquals(listOf("No limit"), generalSummary(base, SpeedUnit.BYTES).lines)
        assertEquals(listOf("Limit 20.0 MB/s"), generalSummary(base.copy(totalOn = true), SpeedUnit.BYTES).lines)
        assertEquals(listOf("Limit 20.0 Mbps"), generalSummary(base.copy(totalOn = true), SpeedUnit.BITS).lines)
        assertEquals(listOf("Slow mode on"), generalSummary(base.copy(totalOn = true, slowOn = true), SpeedUnit.BYTES).lines)
        assertEquals(
            listOf("No limit", "22:00–06:00"),
            generalSummary(base.copy(schedule = DownloadSchedule(true, 22 * 60, 6 * 60)), SpeedUnit.BYTES).lines,
        )
        assertEquals(listOf("No limit"), generalSummary(base.copy(schedule = DownloadSchedule(true, 5, 5)), SpeedUnit.BYTES).lines)
    }

    @Test
    fun `a network's lines are its speed and its data against its limit, or no limit`() {
        val n = NetworkDraft(false, "10", false, "5", DataPeriod.MONTH)
        assertEquals(listOf("No limit"), networkSummary(n, null, SpeedUnit.BYTES).lines)
        val both = n.copy(speedOn = true, dataOn = true)
        assertEquals(
            listOf("Limit 10.0 MB/s", "1.0 GB of 5.0 GB this month"),
            networkSummary(both, NetworkUsage(GIB, DataPeriod.MONTH), SpeedUnit.BYTES).lines,
        )
        assertEquals(listOf("Limit 10.0 MB/s"), networkSummary(n.copy(speedOn = true), null, SpeedUnit.BYTES).lines)
    }

    @Test
    fun `a network over its data limit says so and not what else it has`() {
        val n = NetworkDraft(true, "10", true, "5", DataPeriod.DAY)
        val summary = networkSummary(n, NetworkUsage(5 * GIB, DataPeriod.DAY), SpeedUnit.BYTES)
        assertEquals(listOf("Data limit reached"), summary.lines)
        assertTrue(summary.reached)
    }

    @Test
    fun `a limit that is not a number is left out of the sidebar`() {
        val n = NetworkDraft(true, "", true, "x", DataPeriod.MONTH)
        assertEquals(listOf("No limit"), networkSummary(n, null, SpeedUnit.BYTES).lines)
    }
}
