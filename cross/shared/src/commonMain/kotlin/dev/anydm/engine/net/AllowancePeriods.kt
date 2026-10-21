package dev.anydm.engine.net

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.YearMonth
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.minusMonth
import kotlinx.datetime.number
import kotlinx.datetime.onDay
import kotlinx.datetime.plus
import kotlinx.datetime.plusMonth
import kotlinx.datetime.yearMonth
import kotlin.time.Instant

/** An allowance period: the inclusive local days [from] to [to]. */
internal data class Period(
    val from: LocalDate,
    val to: LocalDate,
)

private val oneDay = DatePeriod(days = 1)

/** The period of [period] that holds [day]. */
internal fun periodOf(
    period: AllowancePeriod,
    day: LocalDate,
): Period =
    when (period) {
        AllowancePeriod.Daily -> {
            Period(day, day)
        }

        is AllowancePeriod.Weekly -> {
            val back = (day.dayOfWeek.isoDayNumber - period.resetDay.isoDayNumber + 7) % 7
            val from = day.minus(DatePeriod(days = back))
            Period(from, from.plus(DatePeriod(days = 6)))
        }

        is AllowancePeriod.Monthly -> {
            val month = day.yearMonth
            val thisReset = resetDate(month, period.resetDay)
            val from = if (day >= thisReset) thisReset else resetDate(month.minusMonth(), period.resetDay)
            val next = resetDate(from.yearMonth.plusMonth(), period.resetDay)
            Period(from, next.minus(oneDay))
        }
    }

private fun resetDate(
    month: YearMonth,
    resetDay: Int,
): LocalDate = month.onDay(minOf(resetDay, month.numberOfDays))

/** Local midnight after the period's last day. */
internal fun resetAt(
    period: Period,
    zone: TimeZone,
): Instant = period.to.plus(oneDay).atStartOfDayIn(zone)
