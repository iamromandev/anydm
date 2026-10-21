package dev.anydm.engine.net

import dev.anydm.engine.core.ScheduleRuleId
import io.kotest.matchers.shouldBe
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.DayOfWeek.FRIDAY
import kotlinx.datetime.DayOfWeek.MONDAY
import kotlinx.datetime.DayOfWeek.SUNDAY
import kotlinx.datetime.DayOfWeek.THURSDAY
import kotlinx.datetime.DayOfWeek.TUESDAY
import kotlinx.datetime.DayOfWeek.WEDNESDAY
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.time.Instant

/** Schedule rules (NET-08): which action holds at a local time, and when it next changes. 2026-10-21 is a Wednesday. */
class ScheduleCheckTest {
    private val weekdays = setOf(MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY)
    private val berlin = TimeZone.of("Europe/Berlin")

    private fun rule(
        days: Set<DayOfWeek>,
        start: Int,
        end: Int,
        action: RuleAction,
        id: Long = 1,
    ) = ScheduleRule(ScheduleRuleId(id), days, start, end, action)

    private fun at(s: String) = LocalDateTime.parse(s)

    private fun utc(s: String) = Instant.parse(s)

    @Test
    fun aWeekdayRangeMatchesInsideAndNotAtItsEnd() {
        val rules = listOf(rule(weekdays, 540, 1020, RuleAction.PAUSE))
        scheduleAction(rules, at("2026-10-21T09:00")) shouldBe RuleAction.PAUSE
        scheduleAction(rules, at("2026-10-21T16:59")) shouldBe RuleAction.PAUSE
        scheduleAction(rules, at("2026-10-21T17:00")) shouldBe RuleAction.ALLOW
        scheduleAction(rules, at("2026-10-21T08:59")) shouldBe RuleAction.ALLOW
        scheduleAction(rules, at("2026-10-24T10:00")) shouldBe RuleAction.ALLOW
    }

    @Test
    fun aCrossingRangeBelongsToItsStartDay() {
        val rules = listOf(rule(setOf(FRIDAY), 1320, 360, RuleAction.SLOW))
        scheduleAction(rules, at("2026-10-23T22:00")) shouldBe RuleAction.SLOW
        scheduleAction(rules, at("2026-10-24T05:59")) shouldBe RuleAction.SLOW
        scheduleAction(rules, at("2026-10-24T06:00")) shouldBe RuleAction.ALLOW
        scheduleAction(rules, at("2026-10-23T05:00")) shouldBe RuleAction.ALLOW
    }

    @Test
    fun equalStartAndEndIsTwentyFourHours() {
        val sunday = listOf(rule(setOf(SUNDAY), 0, 0, RuleAction.PAUSE))
        scheduleAction(sunday, at("2026-10-25T00:00")) shouldBe RuleAction.PAUSE
        scheduleAction(sunday, at("2026-10-25T23:59")) shouldBe RuleAction.PAUSE
        scheduleAction(sunday, at("2026-10-26T00:00")) shouldBe RuleAction.ALLOW
        val tenToTen = listOf(rule(setOf(SUNDAY), 600, 600, RuleAction.PAUSE))
        scheduleAction(tenToTen, at("2026-10-26T09:59")) shouldBe RuleAction.PAUSE
        scheduleAction(tenToTen, at("2026-10-26T10:00")) shouldBe RuleAction.ALLOW
    }

    @Test
    fun overlapsGiveTheStrictest() {
        val rules = listOf(rule(weekdays, 540, 1020, RuleAction.SLOW, 1), rule(weekdays, 720, 780, RuleAction.PAUSE, 2))
        scheduleAction(rules, at("2026-10-21T12:30")) shouldBe RuleAction.PAUSE
        scheduleAction(rules, at("2026-10-21T10:00")) shouldBe RuleAction.SLOW
    }

    @Test
    fun noRulesAllowAndHaveNoBoundary() {
        scheduleAction(emptyList(), at("2026-10-21T10:00")) shouldBe RuleAction.ALLOW
        nextScheduleBoundary(emptyList(), utc("2026-10-21T10:00:00Z"), TimeZone.UTC) shouldBe null
    }

    @Test
    fun theNextBoundaryOfAWeekdayRuleFromSaturdayIsMondaysStart() {
        val rules = listOf(rule(weekdays, 540, 1020, RuleAction.PAUSE))
        nextScheduleBoundary(rules, utc("2026-10-24T12:00:00Z"), TimeZone.UTC) shouldBe utc("2026-10-26T09:00:00Z")
    }

    @Test
    fun insideARangeTheNextBoundaryIsItsEnd() {
        val rules = listOf(rule(weekdays, 540, 1020, RuleAction.PAUSE))
        nextScheduleBoundary(rules, utc("2026-10-21T10:00:00Z"), TimeZone.UTC) shouldBe utc("2026-10-21T17:00:00Z")
    }

    @Test
    fun theBoundaryIsStrictlyAfterNow() {
        val rules = listOf(rule(weekdays, 540, 1020, RuleAction.PAUSE))
        nextScheduleBoundary(rules, utc("2026-10-26T09:00:00Z"), TimeZone.UTC) shouldBe utc("2026-10-26T17:00:00Z")
    }

    @Test
    fun aCrossingRangesEndIsFoundFromYesterday() {
        val rules = listOf(rule(setOf(FRIDAY), 1320, 360, RuleAction.SLOW))
        nextScheduleBoundary(rules, utc("2026-10-24T01:00:00Z"), TimeZone.UTC) shouldBe utc("2026-10-24T06:00:00Z")
    }

    @Test
    fun aRuleSixDaysAheadIsFound() {
        // Wednesday 2026-10-21; the rule is on Tuesday 2026-10-27 and Wednesday is not in it.
        val rules = listOf(rule(setOf(TUESDAY), 600, 660, RuleAction.PAUSE))
        nextScheduleBoundary(rules, utc("2026-10-21T10:00:00Z"), TimeZone.UTC) shouldBe utc("2026-10-27T10:00:00Z")
    }

    @Test
    fun aBoundaryInTheSpringGapFiresAtTheGapsEnd() {
        val rules = listOf(rule(setOf(SUNDAY), 150, 240, RuleAction.PAUSE))
        nextScheduleBoundary(rules, utc("2026-03-29T00:30:00Z"), berlin) shouldBe utc("2026-03-29T01:00:00Z")
        scheduleAction(rules, utc("2026-03-29T01:00:00Z").toLocalDateTime(berlin)) shouldBe RuleAction.PAUSE
        scheduleAction(rules, at("2026-03-29T03:00")) shouldBe RuleAction.PAUSE
    }

    @Test
    fun aBoundaryInTheAutumnOverlapIsItsFirstOccurrence() {
        val rules = listOf(rule(setOf(SUNDAY), 150, 300, RuleAction.PAUSE))
        nextScheduleBoundary(rules, utc("2026-10-25T00:00:00Z"), berlin) shouldBe utc("2026-10-25T00:30:00Z")
    }
}
