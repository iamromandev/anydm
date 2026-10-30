package dev.anydm.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import dev.anydm.engine.core.ScheduleRuleId
import dev.anydm.engine.net.Allowance
import dev.anydm.engine.net.AllowancePeriod
import dev.anydm.engine.net.BondingState
import dev.anydm.engine.net.NetworkPolicy
import dev.anydm.engine.net.NetworkRules
import dev.anydm.engine.net.RuleAction
import dev.anydm.engine.net.ScheduleRule
import kotlinx.datetime.DayOfWeek
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
internal class RulesScreenTest : PanelScreenTest(Section.RULES) {
    private val night = ScheduleRule(ScheduleRuleId(4), setOf(DayOfWeek.MONDAY), 22 * 60, 6 * 60, RuleAction.SLOW)

    @Test
    fun `it shows the default limits and save is off`() {
        source.rules = NetworkRules(NetworkPolicy(speedLimit = 2048, uploadLimit = 1024))
        show()
        rule.onNodeWithTag("rules-down").assertTextContains("2")
        rule.onNodeWithTag("rules-up").assertTextContains("1")
        rule.onNodeWithTag("rules-save-policy").assertIsNotEnabled()
    }

    @Test
    fun `an edit saves the default policy`() {
        show()
        type("rules-down", "500")
        rule.onNodeWithTag("rules-save-policy").assertIsEnabled()
        click("rules-save-policy")
        rule.waitForIdle()
        assertEquals(500 * KB, source.rules.default.speedLimit)
        tag("rules-message").assertTextContains("Saved.")
        rule.onNodeWithTag("rules-save-policy").assertIsNotEnabled()
    }

    @Test
    fun `a bad limit is flagged and keeps save off`() {
        show()
        type("rules-down", "fast")
        tag("rules-down-error").assertTextContains("whole number", substring = true)
        rule.onNodeWithTag("rules-save-policy").assertIsNotEnabled()
    }

    @Test
    fun `an allowance is shown, edited and saved`() {
        show()
        click("rules-allowance")
        type("rules-allowance-mb", "5000")
        click("rules-period-weekly")
        click("rules-weekday-sunday")
        type("rules-warn", "80")
        click("rules-atlimit-slow")
        click("rules-save-policy")
        rule.waitForIdle()
        val allowance: Allowance = source.rules.default.allowance!!
        assertEquals(5000 * MB, allowance.bytes)
        assertEquals(AllowancePeriod.Weekly(DayOfWeek.SUNDAY), allowance.period)
        assertEquals(80, allowance.warnPercent)
    }

    @Test
    fun `an allowance with no amount is flagged`() {
        show()
        click("rules-allowance")
        tag("rules-allowance-mb-error").assertTextContains("whole number", substring = true)
        rule.onNodeWithTag("rules-save-policy").assertIsNotEnabled()
    }

    @Test
    fun `a network gets its own policy and can go back to the default`() {
        source.networks.value = BondingState(listOf(network("en0")))
        show()
        click("rules-target-en0")
        tag("rules-override-note").assertTextContains("uses the default", substring = true)
        type("rules-down", "64")
        click("rules-save-policy")
        rule.waitForIdle()
        assertEquals(
            64 * KB,
            source.rules.networks
                .getValue("en0")
                .speedLimit,
        )
        tag("rules-override-note").assertTextContains("has its own policy", substring = true)
        click("rules-remove-override")
        rule.waitForIdle()
        assertEquals(emptyMap(), source.rules.networks)
    }

    @Test
    fun `the schedule lists its rules`() {
        source.scheduleList = listOf(night)
        show()
        tag("rule-text-4").assertTextContains("overnight", substring = true)
        tag("rule-text-4").assertTextContains("Mon", substring = true)
    }

    @Test
    fun `an empty schedule says so`() {
        show()
        tag("rule-empty").assertTextContains("No rules", substring = true)
    }

    @Test
    fun `a rule is added with days and times`() {
        show()
        click("rule-add")
        rule.onNodeWithTag("rule-save").assertIsNotEnabled()
        tag("rule-days-error").assertTextContains("at least one day", substring = true)
        click("rule-day-saturday")
        click("rule-day-sunday")
        type("rule-start", "09:00")
        type("rule-end", "17:30")
        click("rule-action-pause")
        rule.onNodeWithTag("rule-action-pause").assertIsSelected()
        click("rule-save")
        rule.waitForIdle()
        val saved = source.scheduleList.single()
        assertEquals(setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY), saved.days)
        assertEquals(9 * 60, saved.startMinute)
        assertEquals(17 * 60 + 30, saved.endMinute)
        assertEquals(RuleAction.PAUSE, saved.action)
        tag("rule-row-${saved.id.value}").assertExists()
    }

    @Test
    fun `a bad time is flagged`() {
        show()
        click("rule-add")
        click("rule-day-monday")
        type("rule-start", "25:61")
        tag("rule-start-error").assertTextContains("22:00", substring = true)
        rule.onNodeWithTag("rule-save").assertIsNotEnabled()
    }

    @Test
    fun `a rule is edited and removed`() {
        source.scheduleList = listOf(night)
        show()
        click("rule-edit-4")
        click("rule-action-pause")
        click("rule-save")
        rule.waitForIdle()
        assertEquals(RuleAction.PAUSE, source.scheduleList.single().action)
        click("rule-remove-4")
        rule.waitForIdle()
        assertEquals(emptyList(), source.scheduleList)
    }
}
