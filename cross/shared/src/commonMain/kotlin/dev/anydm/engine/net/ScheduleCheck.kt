package dev.anydm.engine.net

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

private fun ScheduleRule.matches(local: LocalDateTime): Boolean {
    val t = local.hour * 60 + local.minute
    val today = local.date.dayOfWeek
    return if (startMinute < endMinute) {
        today in days && t >= startMinute && t < endMinute
    } else {
        (today in days && t >= startMinute) || (previous(today) in days && t < endMinute)
    }
}

private fun previous(day: DayOfWeek): DayOfWeek = DayOfWeek((day.isoDayNumber + 5) % 7 + 1)

/** The strictest action of the rules that hold at [local]; ALLOW with none. */
internal fun scheduleAction(
    rules: List<ScheduleRule>,
    local: LocalDateTime,
): RuleAction = rules.filter { it.matches(local) }.maxOfOrNull { it.action } ?: RuleAction.ALLOW

/** The earliest instant after [now] at which a rule starts or ends, or null with no rules. */
internal fun nextScheduleBoundary(
    rules: List<ScheduleRule>,
    now: Instant,
    zone: TimeZone,
): Instant? {
    if (rules.isEmpty()) return null
    val today = now.toLocalDateTime(zone).date
    var best: Instant? = null
    for (offset in -1..8) {
        val date = today.plus(DatePeriod(days = offset))
        for (rule in rules) {
            if (date.dayOfWeek !in rule.days) continue
            val end = if (rule.endMinute > rule.startMinute) date to rule.endMinute else date.plus(DatePeriod(days = 1)) to rule.endMinute
            for ((d, minute) in listOf(date to rule.startMinute, end)) {
                val at = boundaryInstant(d, minute, zone)
                if (at > now && (best == null || at < best)) best = at
            }
        }
    }
    return best
}

/**
 * The first instant whose local time is at or after [minute] on [date]. In a spring-forward gap that is the gap's end; in
 * a fall-back overlap, the first occurrence.
 */
internal fun boundaryInstant(
    date: LocalDate,
    minute: Int,
    zone: TimeZone,
): Instant {
    val wanted = LocalDateTime(date, LocalTime(minute / 60, minute % 60))
    val naive = wanted.toInstant(zone)
    val landed = naive.toLocalDateTime(zone)
    if (landed == wanted) return naive
    // A gap: the library moved the time forward by the gap's length. The gap's end is the first second not before [wanted].
    val gap = landed.toInstant(TimeZone.UTC).epochSeconds - wanted.toInstant(TimeZone.UTC).epochSeconds
    var low = naive.epochSeconds - gap
    var high = naive.epochSeconds
    while (low < high) {
        val mid = low + (high - low) / 2
        if (Instant.fromEpochSeconds(mid).toLocalDateTime(zone) >= wanted) high = mid else low = mid + 1
    }
    return Instant.fromEpochSeconds(low)
}
