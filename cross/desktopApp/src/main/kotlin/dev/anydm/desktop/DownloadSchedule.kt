package dev.anydm.desktop

import dev.anydm.engine.core.ScheduleRuleId
import dev.anydm.engine.net.NewScheduleRule
import dev.anydm.engine.net.RuleAction
import dev.anydm.engine.net.ScheduleRule
import kotlinx.datetime.DayOfWeek

private const val DAY = 1440
private const val WEEK = 7 * DAY

/** The time of a box left empty: outside the day, so the schedule is not valid until it is filled in. */
const val NO_TIME = -1

/** Whether [minute] is a time of the day: 0 to 23:59 ([NO_TIME] is not). */
internal fun isTimeOfDay(minute: Int): Boolean = minute in 0 until DAY

/**
 * The download schedule of the Limits dialog: downloads run from [startMinute] to [endMinute] (minutes after local midnight)
 * on each of [days], the days a window starts on; an end before the start runs into the next day. Outside the window they
 * wait. The engine knows no such thing: it has rules that hold an action over a range of time, so a schedule is stored as
 * PAUSE rules over everything outside its windows ([scheduleRulesFor]) and read back from them ([decodeSchedule]).
 */
data class DownloadSchedule(
    val enabled: Boolean = false,
    val startMinute: Int = 0,
    val endMinute: Int = 7 * 60,
    val days: Set<DayOfWeek> = DayOfWeek.entries.toSet(),
) {
    /** Whether the window runs into the next day: both times are set and the end is before the start. */
    val endsNextDay: Boolean
        get() = isTimeOfDay(startMinute) && isTimeOfDay(endMinute) && endMinute < startMinute

    /** Whether the engine can store it: a start and an end that differ, in the day, and at least one day. */
    val valid: Boolean
        get() = isTimeOfDay(startMinute) && isTimeOfDay(endMinute) && startMinute != endMinute && days.isNotEmpty()
}

/** What the engine's schedule rules say about a download schedule. */
sealed interface ScheduleReading {
    /** No pause rule at all: no schedule. */
    data object Off : ScheduleReading

    /** The pause rules are exactly the outside of one schedule's windows. */
    data class Window(
        val schedule: DownloadSchedule,
    ) : ScheduleReading

    /** There are pause rules that no schedule makes (set up elsewhere); the dialog leaves them alone until a schedule replaces them. */
    data object Foreign : ScheduleReading
}

private fun DayOfWeek.index() = ordinal // Monday 0 .. Sunday 6

/** The minutes of the week (Monday 00:00 is 0) the window of [schedule] holds. */
private fun allowedMinutes(schedule: DownloadSchedule): BooleanArray {
    val allowed = BooleanArray(WEEK)
    val length = (schedule.endMinute - schedule.startMinute + DAY) % DAY
    for (day in schedule.days) {
        val from = day.index() * DAY + schedule.startMinute
        for (offset in 0 until length) allowed[(from + offset) % WEEK] = true
    }
    return allowed
}

private fun pausedMinutes(rules: List<ScheduleRule>): BooleanArray {
    val paused = BooleanArray(WEEK)
    for (rule in rules) {
        if (rule.action != RuleAction.PAUSE) continue
        val length = if (rule.endMinute == rule.startMinute) DAY else (rule.endMinute - rule.startMinute + DAY) % DAY
        for (day in rule.days) {
            val from = day.index() * DAY + rule.startMinute
            for (offset in 0 until length) paused[(from + offset) % WEEK] = true
        }
    }
    return paused
}

/**
 * PAUSE rules over every minute outside the windows of [schedule], one rule for each stretch of a day, days with the same
 * stretch sharing a rule. A stretch that ends at midnight ends at 0 (an end at or before the start crosses midnight; a day
 * long one is 0 to 0). Empty for a schedule that is off or invalid.
 */
fun scheduleRulesFor(schedule: DownloadSchedule): List<NewScheduleRule> {
    if (!schedule.enabled || !schedule.valid) return emptyList()
    val allowed = allowedMinutes(schedule)
    // The paused stretches of each day, as (start, end) in minutes of that day, end exclusive up to 1440.
    val byStretch = linkedMapOf<Pair<Int, Int>, MutableSet<DayOfWeek>>()
    for (day in DayOfWeek.entries) {
        var start = -1
        for (minute in 0..DAY) {
            val paused = minute < DAY && !allowed[day.index() * DAY + minute]
            if (paused && start < 0) start = minute
            if (!paused && start >= 0) {
                byStretch.getOrPut(start to minute) { linkedSetOf() } += day
                start = -1
            }
        }
    }
    return byStretch.map { (stretch, days) ->
        NewScheduleRule(days, stretch.first, stretch.second % DAY, RuleAction.PAUSE)
    }
}

/** Reads a schedule back from the engine's [rules]; see [ScheduleReading]. Only PAUSE rules count. */
fun decodeSchedule(rules: List<ScheduleRule>): ScheduleReading {
    val pause = rules.filter { it.action == RuleAction.PAUSE }
    if (pause.isEmpty()) return ScheduleReading.Off
    val paused = pausedMinutes(pause)
    val runs = allowedRuns(paused) ?: return ScheduleReading.Foreign
    val starts = runs.map { it.first % DAY }.toSet()
    val ends = runs.map { (it.first + it.second) % DAY }.toSet()
    if (starts.size != 1 || ends.size != 1) return ScheduleReading.Foreign
    val schedule =
        DownloadSchedule(
            enabled = true,
            startMinute = starts.single(),
            endMinute = ends.single(),
            days = runs.map { DayOfWeek.entries[it.first / DAY] }.toSet(),
        )
    if (!schedule.valid) return ScheduleReading.Foreign
    // Exact, not near: the rules a schedule would make must hold the same minutes.
    val made = scheduleRulesFor(schedule).map { ScheduleRule(ScheduleRuleId(0), it.days, it.startMinute, it.endMinute, it.action) }
    val again = pausedMinutes(made)
    return if (again.contentEquals(paused)) ScheduleReading.Window(schedule) else ScheduleReading.Foreign
}

/**
 * The stretches of the week not paused, as (first minute of the week, length), going round the end of the week; null when
 * nothing or everything is.
 */
private fun allowedRuns(paused: BooleanArray): List<Pair<Int, Int>>? {
    if (paused.all { it } || paused.none { it }) return null
    // Begin just after a paused minute, so no run is split by the week's end.
    val begin = paused.indices.first { paused[it] }
    val runs = mutableListOf<Pair<Int, Int>>()
    var start = -1
    for (step in 1..WEEK) {
        val minute = (begin + step) % WEEK
        val free = !paused[minute]
        if (free && start < 0) start = minute
        if (!free && start >= 0) {
            val end = minute
            runs += start to ((end - start + WEEK) % WEEK)
            start = -1
        }
    }
    return runs
}

private fun dayName(day: DayOfWeek) =
    day.name
        .lowercase()
        .replaceFirstChar { it.uppercase() }
        .take(3)

/** Days as a person writes them: "every day", "Mon–Fri", "Sat, Sun" or "Mon–Wed, Fri". */
private fun daysText(days: Set<DayOfWeek>): String {
    if (days.size == DayOfWeek.entries.size) return "every day"
    val runs = mutableListOf<List<DayOfWeek>>()
    for (day in DayOfWeek.entries.filter { it in days }) {
        val last = runs.lastOrNull()
        if (last != null && last.last().ordinal == day.ordinal - 1) runs[runs.lastIndex] = last + day else runs += listOf(day)
    }
    return runs.joinToString(", ") { run ->
        if (run.size >= 3) "${dayName(run.first())}–${dayName(run.last())}" else run.joinToString(", ") { dayName(it) }
    }
}

/** A rule in words, for the person to recognise it: "Pause Mon–Fri 09:00–17:00" (equal start and end is a whole day). */
internal fun describeRule(rule: ScheduleRule): String {
    val action =
        when (rule.action) {
            RuleAction.PAUSE -> "Pause"
            RuleAction.SLOW -> "Slow"
            RuleAction.ALLOW -> "Allow"
        }
    val time = if (rule.startMinute == rule.endMinute) "all day" else "${clockText(rule.startMinute)}–${clockText(rule.endMinute)}"
    return "$action ${daysText(rule.days)} $time"
}
