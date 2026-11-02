package dev.anydm.desktop

import dev.anydm.engine.core.ScheduleRuleId
import dev.anydm.engine.net.RuleAction
import dev.anydm.engine.net.ScheduleRule
import kotlinx.datetime.DayOfWeek
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

internal class DownloadScheduleTest {
    private val all = DayOfWeek.entries.toSet()
    private val weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)

    private fun stored(schedule: DownloadSchedule) =
        scheduleRulesFor(schedule).mapIndexed { i, r -> ScheduleRule(ScheduleRuleId(i + 1L), r.days, r.startMinute, r.endMinute, r.action) }

    // The rules as the engine holds them: a PAUSE rule holds from its start to its end on the days it starts on.
    private fun pausedAt(
        rules: List<ScheduleRule>,
        day: DayOfWeek,
        minute: Int,
    ): Boolean =
        rules.any { rule ->
            if (rule.action != RuleAction.PAUSE) return@any false
            val previous = DayOfWeek.entries[(day.ordinal + 6) % 7]
            if (rule.startMinute < rule.endMinute) {
                day in rule.days && minute >= rule.startMinute && minute < rule.endMinute
            } else {
                (day in rule.days && minute >= rule.startMinute) || (previous in rule.days && minute < rule.endMinute)
            }
        }

    // The window as a person means it: downloads run from start to end on the days the window starts on.
    private fun allowedAt(
        schedule: DownloadSchedule,
        day: DayOfWeek,
        minute: Int,
    ): Boolean {
        val previous = DayOfWeek.entries[(day.ordinal + 6) % 7]
        return if (schedule.startMinute < schedule.endMinute) {
            day in schedule.days && minute >= schedule.startMinute && minute < schedule.endMinute
        } else {
            (day in schedule.days && minute >= schedule.startMinute) || (previous in schedule.days && minute < schedule.endMinute)
        }
    }

    private fun assertRulesPauseExactlyOutside(schedule: DownloadSchedule) {
        val rules = stored(schedule)
        for (day in DayOfWeek.entries) {
            for (minute in 0 until 1440) {
                assertEquals(!allowedAt(schedule, day, minute), pausedAt(rules, day, minute), "$day $minute for $schedule")
            }
        }
    }

    @Test
    fun `an overnight window pauses everything outside it`() = assertRulesPauseExactlyOutside(DownloadSchedule(true, 22 * 60, 6 * 60, all))

    @Test
    fun `a daytime window pauses everything outside it`() =
        assertRulesPauseExactlyOutside(DownloadSchedule(true, 9 * 60, 17 * 60 + 30, all))

    @Test
    fun `a window on weekdays only pauses the weekend and the nights`() =
        assertRulesPauseExactlyOutside(DownloadSchedule(true, 8 * 60, 18 * 60, weekdays))

    @Test
    fun `an overnight window on some days pauses the rest`() =
        assertRulesPauseExactlyOutside(DownloadSchedule(true, 23 * 60 + 30, 1 * 60 + 15, setOf(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY)))

    @Test
    fun `a window from midnight to seven every day is the default and pauses the rest`() =
        assertRulesPauseExactlyOutside(DownloadSchedule(true))

    @Test
    fun `days with the same stretch share a rule`() {
        val rules = scheduleRulesFor(DownloadSchedule(true, 8 * 60, 18 * 60, all))
        // The night before 08:00 and the evening after 18:00, each one rule for all seven days.
        assertEquals(2, rules.size)
        assertTrue(rules.all { it.days == all })
    }

    @Test
    fun `a schedule that is off makes no rules`() {
        assertTrue(scheduleRulesFor(DownloadSchedule(false, 60, 120, all)).isEmpty())
    }

    @Test
    fun `a schedule that can't be stored makes no rules`() {
        assertTrue(scheduleRulesFor(DownloadSchedule(true, 60, 60, all)).isEmpty())
        assertTrue(scheduleRulesFor(DownloadSchedule(true, 60, 120, emptySet())).isEmpty())
        assertFalse(DownloadSchedule(true, 60, 1440, all).valid)
    }

    @Test
    fun `what a schedule stores reads back as the same schedule`() {
        val schedules =
            listOf(
                DownloadSchedule(true, 22 * 60, 6 * 60, all),
                DownloadSchedule(true, 9 * 60, 17 * 60, weekdays),
                DownloadSchedule(true, 23 * 60 + 30, 1 * 60 + 15, setOf(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY)),
                DownloadSchedule(true, 0, 7 * 60, all),
                DownloadSchedule(true, 480, 479, all),
                DownloadSchedule(true, 600, 660, setOf(DayOfWeek.SUNDAY)),
                DownloadSchedule(true, 22 * 60, 6 * 60, setOf(DayOfWeek.SUNDAY, DayOfWeek.MONDAY)),
            )
        for (schedule in schedules) {
            assertEquals(ScheduleReading.Window(schedule), decodeSchedule(stored(schedule)), "$schedule")
        }
    }

    @Test
    fun `no rules is no schedule`() {
        assertEquals(ScheduleReading.Off, decodeSchedule(emptyList()))
    }

    @Test
    fun `rules that only slow or allow are no schedule and are left alone`() {
        val slow = ScheduleRule(ScheduleRuleId(1), all, 60, 120, RuleAction.SLOW)
        assertEquals(ScheduleReading.Off, decodeSchedule(listOf(slow)))
    }

    @Test
    fun `a pause rule that is no window's outside is foreign`() {
        val odd = ScheduleRule(ScheduleRuleId(1), setOf(DayOfWeek.MONDAY), 60, 120, RuleAction.PAUSE)
        assertEquals(ScheduleReading.Foreign, decodeSchedule(listOf(odd)))
    }

    @Test
    fun `a pause inside what the schedule pauses anyway changes nothing`() {
        val schedule = DownloadSchedule(true, 8 * 60, 18 * 60, setOf(DayOfWeek.MONDAY))
        val inside = ScheduleRule(ScheduleRuleId(99), setOf(DayOfWeek.TUESDAY), 9 * 60, 10 * 60, RuleAction.PAUSE)
        assertEquals(ScheduleReading.Window(schedule), decodeSchedule(stored(schedule) + inside))
    }

    @Test
    fun `a pause over the whole week is foreign`() {
        val everything = ScheduleRule(ScheduleRuleId(1), all, 0, 0, RuleAction.PAUSE)
        assertEquals(ScheduleReading.Foreign, decodeSchedule(listOf(everything)))
    }

    @Test
    fun `a pause cut into a window makes two windows of different starts and is foreign`() {
        val schedule = DownloadSchedule(true, 8 * 60, 18 * 60, setOf(DayOfWeek.MONDAY))
        val other = ScheduleRule(ScheduleRuleId(99), setOf(DayOfWeek.MONDAY), 12 * 60, 13 * 60, RuleAction.PAUSE)
        assertIs<ScheduleReading.Foreign>(decodeSchedule(stored(schedule) + other))
    }

    // Rules in words

    private fun pause(
        days: Set<DayOfWeek>,
        start: Int,
        end: Int,
    ) = ScheduleRule(ScheduleRuleId(1), days, start, end, RuleAction.PAUSE)

    @Test
    fun `a rule is described by its action, days and times`() {
        assertEquals("Pause Mon–Fri 09:00–17:00", describeRule(pause(weekdays, 9 * 60, 17 * 60)))
        assertEquals("Pause every day 22:00–06:00", describeRule(pause(all, 22 * 60, 6 * 60)))
        assertEquals("Pause Sat, Sun all day", describeRule(pause(setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY), 0, 0)))
        assertEquals("Pause Mon 00:05–01:00", describeRule(pause(setOf(DayOfWeek.MONDAY), 5, 60)))
        assertEquals(
            "Pause Mon–Wed, Fri, Sun 08:00–09:00",
            describeRule(
                pause(
                    setOf(0, 1, 2, 4, 6)
                        .map {
                            DayOfWeek.entries[it]
                        }.toSet(),
                    480,
                    540,
                ),
            ),
        )
    }

    @Test
    fun `other actions are named too`() {
        val slow = ScheduleRule(ScheduleRuleId(1), setOf(DayOfWeek.MONDAY), 60, 120, RuleAction.SLOW)
        assertEquals("Slow Mon 01:00–02:00", describeRule(slow))
        assertEquals("Allow Mon 01:00–02:00", describeRule(slow.copy(action = RuleAction.ALLOW)))
    }
}
