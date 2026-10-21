package dev.anydm.engine.net

import dev.anydm.engine.core.ScheduleRuleId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.datetime.DayOfWeek
import kotlin.test.Test

/** The network rule model (#543): declared order and the ranges each class refuses. */
class NetworkRulesTest {
    private val monday = setOf(DayOfWeek.MONDAY)

    @Test
    fun actionsAreDeclaredInOrderOfStrictness() {
        (RuleAction.ALLOW < RuleAction.SLOW) shouldBe true
        (RuleAction.SLOW < RuleAction.PAUSE) shouldBe true
    }

    @Test
    fun aScheduleRuleNeedsDaysAndMinutesIn0To1439() {
        ScheduleRule(ScheduleRuleId(1), monday, 0, 1439, RuleAction.PAUSE)
        shouldThrow<IllegalArgumentException> { ScheduleRule(ScheduleRuleId(1), emptySet(), 0, 10, RuleAction.PAUSE) }
        shouldThrow<IllegalArgumentException> { ScheduleRule(ScheduleRuleId(1), monday, -1, 10, RuleAction.PAUSE) }
        shouldThrow<IllegalArgumentException> { ScheduleRule(ScheduleRuleId(1), monday, 0, 1440, RuleAction.PAUSE) }
        shouldThrow<IllegalArgumentException> { ScheduleRule(ScheduleRuleId(1), monday, 1440, 10, RuleAction.PAUSE) }
        NewScheduleRule(monday, 0, 1439, RuleAction.SLOW)
        shouldThrow<IllegalArgumentException> { NewScheduleRule(emptySet(), 0, 10, RuleAction.PAUSE) }
        shouldThrow<IllegalArgumentException> { NewScheduleRule(monday, -1, 10, RuleAction.PAUSE) }
        shouldThrow<IllegalArgumentException> { NewScheduleRule(monday, 0, 1440, RuleAction.PAUSE) }
    }

    @Test
    fun anAllowanceNeedsBytesAtLeast0AndWarnIn1To99() {
        Allowance(0, AllowancePeriod.Daily)
        Allowance(10, AllowancePeriod.Daily, warnPercent = 1)
        Allowance(10, AllowancePeriod.Daily, warnPercent = 99)
        shouldThrow<IllegalArgumentException> { Allowance(-1, AllowancePeriod.Daily) }
        shouldThrow<IllegalArgumentException> { Allowance(10, AllowancePeriod.Daily, warnPercent = 0) }
        shouldThrow<IllegalArgumentException> { Allowance(10, AllowancePeriod.Daily, warnPercent = 100) }
    }

    @Test
    fun aMonthlyResetDayIs1To31() {
        AllowancePeriod.Monthly(1)
        AllowancePeriod.Monthly(31)
        shouldThrow<IllegalArgumentException> { AllowancePeriod.Monthly(0) }
        shouldThrow<IllegalArgumentException> { AllowancePeriod.Monthly(32) }
    }

    @Test
    fun policyLimitsMustBePositive() {
        NetworkPolicy(speedLimit = 1, uploadLimit = 1)
        shouldThrow<IllegalArgumentException> { NetworkPolicy(speedLimit = 0) }
        shouldThrow<IllegalArgumentException> { NetworkPolicy(uploadLimit = 0) }
        shouldThrow<IllegalArgumentException> { NetworkPolicy(speedLimit = -5) }
    }

    @Test
    fun networkKeysMayNotBeBlankOrStar() {
        NetworkRules(networks = mapOf("wifi" to NetworkPolicy()))
        for (key in listOf("", " ", "*")) {
            shouldThrow<IllegalArgumentException> { NetworkRules(networks = mapOf(key to NetworkPolicy())) }
        }
    }

    @Test
    fun aSlowModeLimitMustBePositive() {
        SlowMode(true, 1)
        shouldThrow<IllegalArgumentException> { SlowMode(true, 0) }
        SlowMode.DEFAULT_LIMIT shouldBe 1_048_576L
    }

    @Test
    fun policyForFallsBackToTheDefault() {
        val default = NetworkPolicy(speedLimit = 1000)
        val wifi = NetworkPolicy(uploadLimit = 2000)
        NetworkRules(default).policyFor("unknown") shouldBe default
        NetworkRules(default, mapOf("unknown" to wifi)).policyFor("unknown") shouldBe wifi
        NetworkRules(default, mapOf("wifi" to wifi)).policyFor("eth") shouldBe default
    }
}
