package dev.anydm.engine.net

import io.kotest.matchers.shouldBe
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.time.Instant

/** Allowance periods (NET-07) and when they reset. */
class AllowancePeriodTest {
    private fun d(s: String) = LocalDate.parse(s)

    private fun p(
        from: String,
        to: String,
    ) = Period(d(from), d(to))

    @Test
    fun dailyIsTheDayItself() {
        periodOf(AllowancePeriod.Daily, d("2026-10-21")) shouldBe p("2026-10-21", "2026-10-21")
    }

    @Test
    fun weeklyStartsOnTheLatestResetDay() {
        periodOf(AllowancePeriod.Weekly(DayOfWeek.MONDAY), d("2026-10-25")) shouldBe p("2026-10-19", "2026-10-25")
    }

    @Test
    fun weeklyOnItsResetDayStartsThatDay() {
        periodOf(AllowancePeriod.Weekly(DayOfWeek.MONDAY), d("2026-10-19")) shouldBe p("2026-10-19", "2026-10-25")
    }

    @Test
    fun monthlyFirstIsTheCalendarMonth() {
        periodOf(AllowancePeriod.Monthly(1), d("2026-10-21")) shouldBe p("2026-10-01", "2026-10-31")
    }

    @Test
    fun monthly31BeforeFebruarysLastDayStartedInJanuary() {
        periodOf(AllowancePeriod.Monthly(31), d("2026-02-15")) shouldBe p("2026-01-31", "2026-02-27")
    }

    @Test
    fun monthly31OnFebruarysLastDayRunsToMarch30() {
        periodOf(AllowancePeriod.Monthly(31), d("2026-02-28")) shouldBe p("2026-02-28", "2026-03-30")
    }

    @Test
    fun monthly31InALeapFebruary() {
        periodOf(AllowancePeriod.Monthly(31), d("2028-02-29")) shouldBe p("2028-02-29", "2028-03-30")
        periodOf(AllowancePeriod.Monthly(31), d("2028-02-28")) shouldBe p("2028-01-31", "2028-02-28")
    }

    @Test
    fun monthly31InApril() {
        periodOf(AllowancePeriod.Monthly(31), d("2026-04-30")) shouldBe p("2026-04-30", "2026-05-30")
        periodOf(AllowancePeriod.Monthly(31), d("2026-04-29")) shouldBe p("2026-03-31", "2026-04-29")
    }

    @Test
    fun resetAtIsMidnightAfterTheLastDayInBerlin() {
        val berlin = TimeZone.of("Europe/Berlin")
        val march28 = periodOf(AllowancePeriod.Daily, d("2026-03-28"))
        val march29 = periodOf(AllowancePeriod.Daily, d("2026-03-29"))
        resetAt(march28, berlin) shouldBe Instant.parse("2026-03-28T23:00:00Z")
        resetAt(march29, berlin) shouldBe Instant.parse("2026-03-29T22:00:00Z")
    }

    @Test
    fun resetAtInUtc() {
        resetAt(periodOf(AllowancePeriod.Monthly(1), d("2026-10-21")), TimeZone.UTC) shouldBe Instant.parse("2026-11-01T00:00:00Z")
    }
}
