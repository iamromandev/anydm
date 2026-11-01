package dev.anydm.desktop

import dev.anydm.engine.net.Allowance
import dev.anydm.engine.net.AllowanceAction
import dev.anydm.engine.net.AllowancePeriod
import dev.anydm.engine.net.AllowanceStatus
import dev.anydm.engine.net.BondingState
import dev.anydm.engine.net.NetworkPolicy
import dev.anydm.engine.net.NetworkRules
import dev.anydm.engine.queue.CommandResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.datetime.LocalDate
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

internal fun usage(
    network: String,
    used: Long,
    limit: Long,
) = AllowanceStatus(
    network = network,
    limit = limit,
    used = used,
    from = LocalDate(2026, 1, 1),
    to = LocalDate(2026, 1, 1),
    resetsAt = Instant.parse("2026-01-02T00:00:00Z"),
    warnAt = null,
    over = used >= limit,
)

class NetworksStripModelTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val source = FakeSource()
    private val prefs = MemoryPrefs()
    private val window = WindowModel(prefs, trayAvailable = false)
    private val mb = 1024L * 1024

    private fun model() = NetworksStripModel(source, scope, window)

    @AfterTest
    fun close() = scope.cancel()

    private fun two() {
        source.networks.value =
            BondingState(
                listOf(
                    network("wifi", enabled = true, speed = 4.0 * mb),
                    network("usb", enabled = true, speed = 2.0 * mb),
                ),
            )
    }

    @Test
    fun `two active networks give a total and shares by speed`() {
        two()
        val state = model().state.value
        assertEquals(6 * mb, state.total)
        assertEquals(listOf("wifi", "usb"), state.rows.map { it.key })
        assertEquals(67, (state.rows[0].share * 100).let { Math.round(it) })
        assertEquals(33, (state.rows[1].share * 100).let { Math.round(it) })
    }

    @Test
    fun `a network that goes down shows no speed and drops out of the split`() {
        two()
        val model = model()
        source.networks.value =
            BondingState(
                listOf(
                    network("wifi", enabled = true, speed = 4.0 * mb),
                    network("usb", enabled = true, speed = 2.0 * mb, up = false),
                ),
            )
        val state = model.state.value
        val usb = state.rows.first { it.key == "usb" }
        assertNull(usb.speed)
        assertFalse(usb.up)
        assertEquals(0f, usb.share)
        assertEquals(1f, state.rows.first { it.key == "wifi" }.share)
        assertEquals(4 * mb, state.total)
    }

    @Test
    fun `a faulted network shows no speed`() {
        source.networks.value = BondingState(listOf(network("wifi", enabled = true, speed = 1.0 * mb, faulted = true)))
        assertNull(
            model()
                .state.value.rows
                .single()
                .speed,
        )
    }

    @Test
    fun `no traffic gives zero shares and no NaN`() {
        source.networks.value = BondingState(listOf(network("wifi", enabled = true), network("usb", enabled = true, speed = 0.0)))
        val state = model().state.value
        assertEquals(0L, state.total)
        assertTrue(state.rows.all { it.share == 0f })
        assertNull(state.rows[0].speed)
        assertEquals(0L, state.rows[1].speed)
    }

    @Test
    fun `a single network is a row with the whole share`() {
        source.networks.value = BondingState(listOf(network("wifi", enabled = true, speed = 1.0 * mb)))
        assertEquals(
            1f,
            model()
                .state.value.rows
                .single()
                .share,
        )
    }

    @Test
    fun `no networks gives no rows`() {
        assertTrue(
            model()
                .state.value.rows
                .isEmpty(),
        )
    }

    @Test
    fun `the cap and the usage of a network come from the rules and the usage`() {
        two()
        source.rules = NetworkRules(networks = mapOf("usb" to NetworkPolicy(speedLimit = mb)))
        source.usage = mapOf("usb" to usage("usb", 1536 * mb, 2048 * mb))
        val rows = model().state.value.rows
        assertEquals(mb, rows.first { it.key == "usb" }.cap)
        assertNull(rows.first { it.key == "wifi" }.cap)
        assertEquals(
            2048 * mb,
            rows
                .first { it.key == "usb" }
                .usage
                ?.limit,
        )
        assertNull(rows.first { it.key == "wifi" }.usage)
    }

    @Test
    fun `the strip follows the usage on refresh`() {
        two()
        val model = model()
        source.usage = mapOf("wifi" to usage("wifi", 3 * mb, 2 * mb))
        model.refresh()
        assertTrue(
            model.state.value.rows
                .first()
                .usage!!
                .over,
        )
    }

    @Test
    fun `collapsing is kept in the preferences`() {
        val model = model()
        assertFalse(model.state.value.collapsed)
        model.setCollapsed(true)
        assertTrue(model.state.value.collapsed)
        assertTrue(prefs.stripCollapsed)
        prefs.stripCollapsed = true
        assertTrue(NetworksStripModel(source, scope, WindowModel(prefs, false)).state.value.collapsed)
    }

    @Test
    fun `switching a network goes to the engine`() {
        two()
        model().enable("usb", false)
        assertEquals(listOf("setNetworkEnabled usb false"), source.calls)
    }

    @Test
    fun `a refused switch says why`() {
        two()
        source.results["setNetworkEnabled usb false"] = CommandResult.Invalid("nope")
        val model = model()
        model.enable("usb", false)
        assertEquals("nope", model.state.value.message)
    }

    @Test
    fun `the usage follows the engine every few seconds and survives a failed read`() =
        kotlinx.coroutines.test.runTest {
            two()
            val model = NetworksStripModel(source, backgroundScope, window, every = 10.milliseconds)
            source.usage = mapOf("wifi" to usage("wifi", 1 * mb, 2 * mb))
            testScheduler.advanceTimeBy(15.milliseconds)
            testScheduler.runCurrent()
            assertEquals(
                1 * mb,
                model.state.value.rows
                    .first()
                    .usage
                    ?.used,
            )
            source.usageThrows = IllegalArgumentException("broken")
            testScheduler.advanceTimeBy(15.milliseconds)
            testScheduler.runCurrent()
            source.usageThrows = null
            source.usage = mapOf("wifi" to usage("wifi", 2 * mb, 3 * mb))
            testScheduler.advanceTimeBy(15.milliseconds)
            testScheduler.runCurrent()
            assertEquals(
                2 * mb,
                model.state.value.rows
                    .first()
                    .usage
                    ?.used,
            )
        }

    // --- limits

    @Test
    fun `saving limits changes only the cap and keeps an allowance of another period`() {
        two()
        val before =
            NetworkPolicy(
                speedLimit = 500 * 1024L,
                uploadLimit = 777L,
                allowance = Allowance(10 * mb, AllowancePeriod.Monthly(5), 50, AllowanceAction.SLOW),
                metered = true,
            )
        source.rules = NetworkRules(networks = mapOf("usb" to before))
        val model = model()
        model.openLimits("usb")
        model.saveLimits("usb", model.limitsFormFor("usb").copy(capKbps = "1024"))
        assertEquals(listOf("setNetworkPolicy usb"), source.calls)
        assertEquals(before.copy(speedLimit = mb), source.rules.policyFor("usb"))
        assertNull(model.state.value.editing)
        assertEquals(
            mb,
            model.state.value.rows
                .first { it.key == "usb" }
                .cap,
        )
    }

    @Test
    fun `saving limits writes the cap and a daily budget over a daily one or none`() {
        two()
        source.rules =
            NetworkRules(
                networks =
                    mapOf("usb" to NetworkPolicy(uploadLimit = 777L, allowance = Allowance(mb, AllowancePeriod.Daily), metered = true)),
            )
        val model = model()
        model.saveLimits("usb", LimitsForm(capKbps = "1024", budgetMb = "2048", warnPercent = "80", atLimit = AllowanceAction.SLOW))
        val saved = source.rules.policyFor("usb")
        assertEquals(mb, saved.speedLimit)
        assertEquals(777L, saved.uploadLimit)
        assertTrue(saved.metered)
        assertEquals(Allowance(2048 * mb, AllowancePeriod.Daily, 80, AllowanceAction.SLOW), saved.allowance)
    }

    @Test
    fun `opening the limits reads the rules again, so a change made elsewhere is not overwritten`() {
        two()
        val model = model()
        source.rules = NetworkRules(networks = mapOf("usb" to NetworkPolicy(speedLimit = 256 * 1024L)))
        model.openLimits("usb")
        assertEquals("256", model.limitsFormFor("usb").capKbps)
        assertEquals("usb", model.state.value.editing)
    }

    @Test
    fun `an engine refusal of a save stays as the message and the limits stay open`() {
        two()
        source.results["setNetworkPolicy usb"] = CommandResult.Invalid("not allowed")
        val model = model()
        model.openLimits("usb")
        model.saveLimits("usb", LimitsForm(capKbps = "100"))
        assertEquals("not allowed", model.state.value.message)
        assertEquals("usb", model.state.value.editing)
    }

    @Test
    fun `the allowance of another period is summed up and left alone by the form`() {
        val monthly = Allowance(10L * 1024 * mb, AllowancePeriod.Monthly(1))
        assertEquals("Monthly 10 GB", allowanceSummary(monthly))
        val form = limitsForm(NetworkPolicy(allowance = monthly))
        assertEquals(monthly, form.other)
        assertEquals("", form.budgetMb)
    }

    @Test
    fun `a network without its own policy starts from the default`() {
        two()
        source.rules = NetworkRules(default = NetworkPolicy(uploadLimit = 5L))
        val model = model()
        model.saveLimits("usb", LimitsForm(capKbps = "100"))
        assertEquals(5L, source.rules.policyFor("usb").uploadLimit)
        assertEquals(100 * 1024L, source.rules.policyFor("usb").speedLimit)
        assertNull(source.rules.policyFor("usb").allowance)
    }

    @Test
    fun `an empty cap is unlimited and an empty budget removes the allowance`() {
        two()
        source.rules =
            NetworkRules(networks = mapOf("usb" to NetworkPolicy(speedLimit = mb, allowance = Allowance(mb, AllowancePeriod.Daily))))
        model().saveLimits("usb", LimitsForm())
        assertNull(source.rules.policyFor("usb").speedLimit)
        assertNull(source.rules.policyFor("usb").allowance)
    }

    @Test
    fun `a budget that is not a number or a cap that is not a number is refused and nothing is sent`() {
        two()
        val model = model()
        model.saveLimits("usb", LimitsForm(budgetMb = "lots"))
        assertNotNull(model.state.value.message)
        assertNotNull(
            model.state.value.message
                ?.takeIf { it.isNotBlank() },
        )
        model.closeLimits()
        assertNull(model.state.value.message)
        model.saveLimits("usb", LimitsForm(capKbps = "fast"))
        assertNotNull(model.state.value.message)
        assertTrue(source.calls.isEmpty())
        assertTrue(LimitsForm(budgetMb = "lots").validate().containsKey(RulesField.ALLOWANCE))
        assertTrue(LimitsForm(capKbps = "fast").validate().containsKey(RulesField.DOWN))
        assertTrue(LimitsForm(budgetMb = "5", warnPercent = "100").validate().containsKey(RulesField.WARN))
        assertTrue(LimitsForm(capKbps = "100", budgetMb = "5", warnPercent = "80").validate().isEmpty())
    }

    @Test
    fun `an allowance of another period set elsewhere after the dialog opened is not overwritten`() {
        two()
        source.rules = NetworkRules(networks = mapOf("usb" to NetworkPolicy(allowance = Allowance(mb, AllowancePeriod.Daily))))
        val model = model()
        model.openLimits("usb")
        val form = model.limitsFormFor("usb").copy(budgetMb = "500")
        val monthly = Allowance(10 * mb, AllowancePeriod.Monthly(3))
        source.rules = NetworkRules(networks = mapOf("usb" to NetworkPolicy(allowance = monthly)))
        model.saveLimits("usb", form)
        assertEquals(monthly, source.rules.policyFor("usb").allowance)
    }

    @Test
    fun `a save starts from the rules as the engine has them at save time`() {
        two()
        val model = model()
        model.openLimits("usb")
        source.rules = NetworkRules(networks = mapOf("usb" to NetworkPolicy(uploadLimit = 9L)))
        model.saveLimits("usb", LimitsForm(capKbps = "100"))
        assertEquals(9L, source.rules.policyFor("usb").uploadLimit)
        assertEquals(100 * 1024L, source.rules.policyFor("usb").speedLimit)
    }

    @Test
    fun `a stopped engine on save says so`() {
        two()
        val model = model()
        model.openLimits("usb")
        source.refuseAll = true
        model.saveLimits("usb", LimitsForm(capKbps = "100"))
        assertEquals("The download engine has stopped, so nothing was changed.", model.state.value.message)
        assertEquals("usb", model.state.value.editing)
    }

    @Test
    fun `overlapping reads end with the newest rules, not the one that finished last`() {
        two()
        val model = model()
        source.rules = NetworkRules(networks = mapOf("usb" to NetworkPolicy(speedLimit = 1 * mb)))
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        source.rulesGate = gate
        model.refresh()
        source.rules = NetworkRules(networks = mapOf("usb" to NetworkPolicy(speedLimit = 2 * mb)))
        source.rulesGate = null
        model.refresh()
        gate.complete(Unit)
        assertEquals(
            2 * mb,
            model.state.value.rows
                .first { it.key == "usb" }
                .cap,
        )
    }

    @Test
    fun `a cap saved in the strip shows in the rules panel`() {
        two()
        model().saveLimits("usb", LimitsForm(capKbps = "1024"))
        val rules = RulesModel(source, scope)
        rules.load()
        rules.select("usb")
        assertEquals("1024", rules.state.value.policy.downKbps)
    }

    @Test
    fun `a cap saved in the rules panel shows in the strip after a refresh`() {
        two()
        val model = model()
        val rules = RulesModel(source, scope)
        rules.load()
        rules.select("usb")
        rules.editPolicy { it.copy(downKbps = "256") }
        rules.savePolicy()
        model.refresh()
        assertEquals(
            256 * 1024L,
            model.state.value.rows
                .first { it.key == "usb" }
                .cap,
        )
    }

    @Test
    fun `closing the limits without saving changes nothing`() {
        two()
        val model = model()
        model.openLimits("usb")
        assertEquals("usb", model.state.value.editing)
        model.closeLimits()
        assertNull(model.state.value.editing)
        assertTrue(source.calls.isEmpty())
    }

    @Test
    fun `the limits close when their network disappears`() {
        two()
        val model = model()
        model.openLimits("usb")
        source.networks.value = BondingState(listOf(network("wifi", enabled = true)))
        assertNull(model.state.value.editing)
        assertTrue(source.calls.isEmpty())
    }

    @Test
    fun `the form starts from the policy`() {
        val form =
            limitsForm(
                NetworkPolicy(
                    speedLimit = 2048L * 1024,
                    allowance = Allowance(3 * mb, AllowancePeriod.Weekly(kotlinx.datetime.DayOfWeek.MONDAY), 90, AllowanceAction.SLOW),
                ),
            )
        assertEquals(
            LimitsForm(
                "2048",
                "",
                "",
                AllowanceAction.PAUSE,
                other = Allowance(3 * mb, AllowancePeriod.Weekly(kotlinx.datetime.DayOfWeek.MONDAY), 90, AllowanceAction.SLOW),
            ),
            form,
        )
        assertEquals(
            LimitsForm("100", "3", "90", AllowanceAction.SLOW),
            limitsForm(
                NetworkPolicy(
                    speedLimit = 100 * 1024L,
                    allowance =
                        Allowance(3 * mb, AllowancePeriod.Daily, 90, AllowanceAction.SLOW),
                ),
            ),
        )
        assertEquals(LimitsForm(), limitsForm(NetworkPolicy()))
    }

    @Test
    fun `usage reads as used over limit with the reset`() {
        assertEquals("1.5 / 2 GB", budgetText(usage("usb", 1536 * mb, 2048 * mb)))
        assertEquals("5.0 MB / 2 GB", budgetText(usage("usb", 5 * mb, 2048 * mb)))
    }
}
