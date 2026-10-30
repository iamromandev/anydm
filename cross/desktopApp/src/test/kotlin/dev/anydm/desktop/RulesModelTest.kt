package dev.anydm.desktop

import dev.anydm.engine.core.ScheduleRuleId
import dev.anydm.engine.net.Allowance
import dev.anydm.engine.net.AllowanceAction
import dev.anydm.engine.net.AllowancePeriod
import dev.anydm.engine.net.MeteredAction
import dev.anydm.engine.net.NetworkPolicy
import dev.anydm.engine.net.NetworkRules
import dev.anydm.engine.net.RuleAction
import dev.anydm.engine.net.ScheduleRule
import dev.anydm.engine.queue.CommandResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.datetime.DayOfWeek
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RulesModelTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val source = FakeSource()
    private val night = ScheduleRule(ScheduleRuleId(4), setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), 22 * 60, 6 * 60, RuleAction.SLOW)

    private fun model() = RulesModel(source, scope).also { it.load() }

    @AfterTest
    fun close() = scope.cancel()

    // --- limits and allowances

    @Test
    fun `it shows the default policy and the schedule`() {
        source.rules = NetworkRules(default = NetworkPolicy(speedLimit = 2048, uploadLimit = 1024))
        source.scheduleList = listOf(night)
        val state = model().state.value
        assertTrue(state.loaded)
        assertEquals("2", state.policy.downKbps)
        assertEquals("1", state.policy.upKbps)
        assertNull(state.target)
        assertEquals(listOf(night), state.schedule)
    }

    @Test
    fun `an edit is dirty until saved and then sends the default policy`() {
        val model = model()
        assertFalse(model.state.value.canSavePolicy)
        model.editPolicy { it.copy(downKbps = "500", upKbps = "100") }
        assertTrue(model.state.value.canSavePolicy)

        model.savePolicy()

        assertEquals(NetworkPolicy(speedLimit = 500 * KB, uploadLimit = 100 * KB), source.rules.default)
        assertEquals(listOf("setNetworkPolicy *"), source.calls)
        assertEquals("Saved.", model.state.value.message)
        assertFalse(model.state.value.canSavePolicy)
    }

    @Test
    fun `a limit must be a whole number or empty`() {
        val model = model()
        model.editPolicy { it.copy(downKbps = "fast") }
        assertTrue(RulesField.DOWN in model.state.value.errors)
        assertFalse(model.state.value.canSavePolicy)
        model.editPolicy { it.copy(downKbps = "", upKbps = "0") }
        assertEquals(setOf(RulesField.UP), model.state.value.errors.keys)
        model.editPolicy { it.copy(upKbps = "") }
        assertTrue(
            model.state.value.errors
                .isEmpty(),
        )
    }

    @Test
    fun `an allowance needs an amount and a sensible reset day and warning`() {
        val model = model()
        model.editPolicy { it.copy(allowanceOn = true, allowanceMb = "") }
        assertTrue(RulesField.ALLOWANCE in model.state.value.errors)
        model.editPolicy { it.copy(allowanceMb = "1000", period = PeriodKind.MONTHLY, resetDay = "32") }
        assertEquals(setOf(RulesField.RESET_DAY), model.state.value.errors.keys)
        model.editPolicy { it.copy(resetDay = "15", warnPercent = "100") }
        assertEquals(setOf(RulesField.WARN), model.state.value.errors.keys)
        model.editPolicy { it.copy(warnPercent = "80") }
        assertTrue(
            model.state.value.errors
                .isEmpty(),
        )
    }

    @Test
    fun `an allowance is saved with its period, warning and action`() {
        val model = model()
        model.editPolicy {
            it.copy(
                allowanceOn = true,
                allowanceMb = "2048",
                period = PeriodKind.WEEKLY,
                weekDay = DayOfWeek.SUNDAY,
                warnPercent = "90",
                atLimit = AllowanceAction.SLOW,
                metered = true,
                onMetered = MeteredAction.PAUSE,
            )
        }
        model.savePolicy()
        assertEquals(
            NetworkPolicy(
                allowance = Allowance(2048 * MB, AllowancePeriod.Weekly(DayOfWeek.SUNDAY), 90, AllowanceAction.SLOW),
                metered = true,
                onMetered = MeteredAction.PAUSE,
            ),
            source.rules.default,
        )
    }

    @Test
    fun `a saved allowance shows back in the form`() {
        source.rules = NetworkRules(NetworkPolicy(allowance = Allowance(5 * MB, AllowancePeriod.Monthly(3), null, AllowanceAction.PAUSE)))
        val form = model().state.value.policy
        assertTrue(form.allowanceOn)
        assertEquals("5", form.allowanceMb)
        assertEquals(PeriodKind.MONTHLY, form.period)
        assertEquals("3", form.resetDay)
    }

    @Test
    fun `a network has its own policy that replaces the default`() {
        source.rules = NetworkRules(NetworkPolicy(speedLimit = 1 * MB), mapOf("en0" to NetworkPolicy(speedLimit = 64 * KB)))
        val model = model()
        model.select("en0")
        assertEquals("64", model.state.value.policy.downKbps)
        assertTrue(model.state.value.hasOverride)

        model.select("en1")
        assertEquals("1024", model.state.value.policy.downKbps)
        assertFalse(model.state.value.hasOverride)
        model.editPolicy { it.copy(downKbps = "10") }
        model.savePolicy()
        assertEquals(NetworkPolicy(speedLimit = 10 * KB), source.rules.networks["en1"])
        assertEquals("setNetworkPolicy en1", source.calls.last())
    }

    @Test
    fun `switching network during a save does not mark the new one unsaved`() {
        val model = model()
        model.editPolicy { it.copy(downKbps = "5") }
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        source.gate = gate
        model.savePolicy()
        model.select("en0")
        gate.complete(Unit)
        assertEquals("", model.state.value.policy.downKbps)
        assertFalse(model.state.value.canSavePolicy)
        assertNull(model.state.value.message)
        assertEquals(NetworkPolicy(speedLimit = 5 * KB), source.rules.default)
    }

    @Test
    fun `an override can be removed so the network uses the default again`() {
        source.rules = NetworkRules(NetworkPolicy(), mapOf("en0" to NetworkPolicy(speedLimit = 64 * KB)))
        val model = model()
        model.select("en0")
        model.removeOverride()
        assertEquals(emptyMap(), source.rules.networks)
        assertFalse(model.state.value.hasOverride)
    }

    @Test
    fun `switching target drops an unsaved edit`() {
        val model = model()
        model.editPolicy { it.copy(downKbps = "1") }
        model.select("en0")
        assertFalse(model.state.value.canSavePolicy)
        assertEquals("", model.state.value.policy.downKbps)
    }

    @Test
    fun `a refused policy keeps the edit and says why`() {
        source.results["setNetworkPolicy *"] = CommandResult.Invalid("speed limit must be > 0")
        val model = model()
        model.editPolicy { it.copy(downKbps = "5") }
        model.savePolicy()
        assertEquals("speed limit must be > 0", model.state.value.message)
        assertTrue(model.state.value.canSavePolicy)
    }

    // --- schedule

    @Test
    fun `a new rule needs days and times`() {
        val model = model()
        model.addRule()
        assertTrue(RulesField.DAYS in model.state.value.errors)
        model.editRule { it.copy(days = setOf(DayOfWeek.SATURDAY), start = "25:00", end = "6") }
        assertEquals(setOf(RulesField.START, RulesField.END), model.state.value.errors.keys)
        model.editRule { it.copy(start = "9:30", end = "17:05") }
        assertTrue(
            model.state.value.errors
                .isEmpty(),
        )
    }

    @Test
    fun `a rule is saved and listed`() {
        val model = model()
        model.addRule()
        model.editRule {
            it.copy(
                days = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY),
                start = "09:30",
                end = "17:05",
                action = RuleAction.PAUSE,
            )
        }

        model.saveRule()

        val saved = source.scheduleList.single()
        assertEquals(setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY), saved.days)
        assertEquals(9 * 60 + 30, saved.startMinute)
        assertEquals(17 * 60 + 5, saved.endMinute)
        assertEquals(RuleAction.PAUSE, saved.action)
        assertEquals(listOf(saved), model.state.value.schedule)
        assertNull(model.state.value.ruleForm)
    }

    @Test
    fun `a rule is changed in place`() {
        source.scheduleList = listOf(night)
        val model = model()
        model.editRule(night)
        assertEquals(
            "22:00",
            model.state.value.ruleForm!!
                .start,
        )
        assertEquals(
            "06:00",
            model.state.value.ruleForm!!
                .end,
        )
        model.editRule { it.copy(action = RuleAction.PAUSE) }
        model.saveRule()
        assertEquals(RuleAction.PAUSE, source.scheduleList.single().action)
        assertEquals(night.id, source.scheduleList.single().id)
    }

    @Test
    fun `a rule is removed`() {
        source.scheduleList = listOf(night)
        val model = model()
        model.removeRule(night.id)
        assertEquals(emptyList(), source.scheduleList)
        assertEquals(emptyList(), model.state.value.schedule)
    }

    @Test
    fun `a rule that crosses midnight is said so`() {
        assertTrue(describe(night).contains("overnight"))
        assertTrue(describe(night).contains("22:00"))
        assertFalse(describe(night.copy(startMinute = 60, endMinute = 120)).contains("overnight"))
    }

    @Test
    fun `a stopped engine is reported`() {
        val model = model()
        source.refuseAll = true
        model.editPolicy { it.copy(downKbps = "1") }
        model.savePolicy()
        assertEquals("The download engine has stopped, so nothing was changed.", model.state.value.message)
    }
}
