package dev.anydm.desktop

import dev.anydm.engine.core.ScheduleRuleId
import dev.anydm.engine.net.Allowance
import dev.anydm.engine.net.AllowanceAction
import dev.anydm.engine.net.AllowancePeriod
import dev.anydm.engine.net.MeteredAction
import dev.anydm.engine.net.NetworkPolicy
import dev.anydm.engine.net.NewScheduleRule
import dev.anydm.engine.net.RuleAction
import dev.anydm.engine.net.ScheduleRule
import kotlinx.datetime.DayOfWeek

// The network rules forms (#609): text as typed, checked against the engine's own limits before anything is sent.

enum class RulesField { DOWN, UP, ALLOWANCE, RESET_DAY, WARN, DAYS, START, END }

enum class PeriodKind(
    val label: String,
) {
    DAILY("Daily"),
    WEEKLY("Weekly"),
    MONTHLY("Monthly"),
}

/** The limits, data allowance and metered behaviour of one network, or of the default. Speeds are KB/s and the allowance MB; empty is none. */
data class PolicyForm(
    val downKbps: String = "",
    val upKbps: String = "",
    val allowanceOn: Boolean = false,
    val allowanceMb: String = "",
    val period: PeriodKind = PeriodKind.MONTHLY,
    val weekDay: DayOfWeek = DayOfWeek.MONDAY,
    val resetDay: String = "1",
    val warnPercent: String = "",
    val atLimit: AllowanceAction = AllowanceAction.PAUSE,
    val metered: Boolean = false,
    val onMetered: MeteredAction = MeteredAction.NONE,
)

fun policyForm(policy: NetworkPolicy): PolicyForm {
    val allowance = policy.allowance
    val base =
        PolicyForm(
            downKbps = policy.speedLimit?.let(::kbps).orEmpty(),
            upKbps = policy.uploadLimit?.let(::kbps).orEmpty(),
            metered = policy.metered,
            onMetered = policy.onMetered,
        )
    if (allowance == null) return base
    val period = allowance.period
    return base.copy(
        allowanceOn = true,
        allowanceMb = ((allowance.bytes + MB - 1) / MB).toString(),
        period =
            when (period) {
                AllowancePeriod.Daily -> PeriodKind.DAILY
                is AllowancePeriod.Weekly -> PeriodKind.WEEKLY
                is AllowancePeriod.Monthly -> PeriodKind.MONTHLY
            },
        weekDay = (period as? AllowancePeriod.Weekly)?.resetDay ?: DayOfWeek.MONDAY,
        resetDay = ((period as? AllowancePeriod.Monthly)?.resetDay ?: 1).toString(),
        warnPercent = allowance.warnPercent?.toString().orEmpty(),
        atLimit = allowance.atLimit,
    )
}

fun PolicyForm.validate(): Map<RulesField, String> =
    buildMap {
        limit(downKbps)?.let { put(RulesField.DOWN, it) }
        limit(upKbps)?.let { put(RulesField.UP, it) }
        if (allowanceOn) {
            whole(allowanceMb, 0, Long.MAX_VALUE / MB)?.let { put(RulesField.ALLOWANCE, it) }
            if (period == PeriodKind.MONTHLY) whole(resetDay, 1, 31)?.let { put(RulesField.RESET_DAY, it) }
            if (warnPercent.isNotBlank()) whole(warnPercent, 1, 99)?.let { put(RulesField.WARN, it) }
        }
    }

/** The policy this form says; only for a form that [validate]s. */
fun PolicyForm.toPolicy(): NetworkPolicy =
    NetworkPolicy(
        speedLimit =
            downKbps
                .trim()
                .takeIf { it.isNotEmpty() }
                ?.toLong()
                ?.times(KB),
        uploadLimit =
            upKbps
                .trim()
                .takeIf { it.isNotEmpty() }
                ?.toLong()
                ?.times(KB),
        allowance =
            if (!allowanceOn) {
                null
            } else {
                Allowance(
                    bytes = allowanceMb.trim().toLong() * MB,
                    period =
                        when (period) {
                            PeriodKind.DAILY -> AllowancePeriod.Daily
                            PeriodKind.WEEKLY -> AllowancePeriod.Weekly(weekDay)
                            PeriodKind.MONTHLY -> AllowancePeriod.Monthly(resetDay.trim().toInt())
                        },
                    warnPercent = warnPercent.trim().takeIf { it.isNotEmpty() }?.toInt(),
                    atLimit = atLimit,
                )
            },
        metered = metered,
        onMetered = onMetered,
    )

/** A weekly schedule rule as typed: times are `HH:mm`, local. */
data class ScheduleForm(
    val id: ScheduleRuleId? = null,
    val days: Set<DayOfWeek> = emptySet(),
    val start: String = "22:00",
    val end: String = "06:00",
    val action: RuleAction = RuleAction.SLOW,
)

fun scheduleForm(rule: ScheduleRule) = ScheduleForm(rule.id, rule.days, clock(rule.startMinute), clock(rule.endMinute), rule.action)

fun ScheduleForm.validate(): Map<RulesField, String> =
    buildMap {
        if (days.isEmpty()) put(RulesField.DAYS, "Pick at least one day.")
        if (minutes(start) == null) put(RulesField.START, "A time like 22:00.")
        if (minutes(end) == null) put(RulesField.END, "A time like 06:00.")
    }

fun ScheduleForm.toNew() = NewScheduleRule(days, minutes(start)!!, minutes(end)!!, action)

fun ScheduleForm.toRule(id: ScheduleRuleId) = ScheduleRule(id, days, minutes(start)!!, minutes(end)!!, action)

private val TIME = Regex("""(\d{1,2}):(\d{2})""")

/** The minute of the day [text] (`H:mm` or `HH:mm`) names, or null. */
fun minutes(text: String): Int? {
    val match = TIME.matchEntire(text.trim()) ?: return null
    val hours = match.groupValues[1].toInt()
    val mins = match.groupValues[2].toInt()
    return if (hours in 0..23 && mins in 0..59) hours * 60 + mins else null
}

private fun clock(minute: Int) = "%02d:%02d".format(minute / 60, minute % 60)

val DayOfWeek.short: String get() = name.take(3).lowercase().replaceFirstChar { it.uppercase() }

val RuleAction.label: String
    get() =
        when (this) {
            RuleAction.ALLOW -> "Allow full speed"
            RuleAction.SLOW -> "Slow down"
            RuleAction.PAUSE -> "Pause"
        }

/** A schedule rule in a line: the days, the hours and what it does. */
fun describe(rule: ScheduleRule): String {
    val span =
        when {
            rule.endMinute < rule.startMinute -> "${clock(rule.startMinute)}–${clock(rule.endMinute)} (overnight)"
            rule.endMinute == rule.startMinute -> "${clock(rule.startMinute)} for 24 hours"
            else -> "${clock(rule.startMinute)}–${clock(rule.endMinute)}"
        }
    return "${rule.days.sortedBy { it.ordinal }.joinToString(", ") { it.short }} $span: ${rule.action.label.lowercase()}"
}
