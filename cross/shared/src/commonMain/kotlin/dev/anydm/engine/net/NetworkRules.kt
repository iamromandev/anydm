package dev.anydm.engine.net

import dev.anydm.engine.core.ScheduleRuleId
import kotlinx.datetime.DayOfWeek

/** What a rule asks of the downloads, declared in order of strictness: the strictest of several wins. */
enum class RuleAction { ALLOW, SLOW, PAUSE }

/** Where a [RuleAction] came from. */
enum class RuleSource { SCHEDULE, ALLOWANCE, SLOW_MODE, METERED }

/** What a metered network does (NET-14), declared in order of strictness: nothing, slow mode's limit, or a rule pause. */
enum class MeteredAction { NONE, SLOW, PAUSE }

private fun requireMinutes(
    days: Set<DayOfWeek>,
    startMinute: Int,
    endMinute: Int,
) {
    require(days.isNotEmpty()) { "a schedule rule needs at least one day" }
    require(startMinute in 0..1439) { "start minute must be 0..1439: $startMinute" }
    require(endMinute in 0..1439) { "end minute must be 0..1439: $endMinute" }
}

/**
 * A weekly schedule rule (NET-08): [action] holds from [startMinute] to [endMinute] (local minutes of the day) on each of
 * [days], the days the range starts on. An end at or before the start crosses midnight; equal is 24 hours.
 */
data class ScheduleRule(
    val id: ScheduleRuleId,
    val days: Set<DayOfWeek>,
    val startMinute: Int,
    val endMinute: Int,
    val action: RuleAction,
) {
    init {
        requireMinutes(days, startMinute, endMinute)
    }
}

/** A [ScheduleRule] not stored yet. */
data class NewScheduleRule(
    val days: Set<DayOfWeek>,
    val startMinute: Int,
    val endMinute: Int,
    val action: RuleAction,
) {
    init {
        requireMinutes(days, startMinute, endMinute)
    }
}

/** How often a data allowance starts again, in local days. */
sealed interface AllowancePeriod {
    data object Daily : AllowancePeriod

    data class Weekly(
        val resetDay: DayOfWeek,
    ) : AllowancePeriod

    /** A [resetDay] beyond the month's length resets on its last day. */
    data class Monthly(
        val resetDay: Int,
    ) : AllowancePeriod {
        init {
            require(resetDay in 1..31) { "monthly reset day must be 1..31: $resetDay" }
        }
    }
}

/** What happens at the limit of an [Allowance]. */
enum class AllowanceAction { PAUSE, SLOW }

/** A data allowance (NET-07): [bytes] of download plus upload per [period]. 0 is over at once. */
data class Allowance(
    val bytes: Long,
    val period: AllowancePeriod,
    val warnPercent: Int? = null,
    val atLimit: AllowanceAction = AllowanceAction.PAUSE,
) {
    init {
        require(bytes >= 0) { "allowance must be >= 0: $bytes" }
        require(warnPercent == null || warnPercent in 1..99) { "warn percent must be 1..99: $warnPercent" }
    }
}

/**
 * What applies on one network (NET-05): limits in bytes per second, and an [allowance]. [metered] and [onMetered] are
 * NET-14's: the user marks the network metered (detection can also make it so), and [onMetered] says what that does.
 */
data class NetworkPolicy(
    val speedLimit: Long? = null,
    val uploadLimit: Long? = null,
    val allowance: Allowance? = null,
    /** The user marks this network metered (NET-14). Detection can also make it metered; either is enough. */
    val metered: Boolean = false,
    /** What a metered network does: nothing, slow mode's limit, or a rule pause. */
    val onMetered: MeteredAction = MeteredAction.NONE,
) {
    init {
        require(speedLimit == null || speedLimit > 0) { "speed limit must be > 0: $speedLimit" }
        require(uploadLimit == null || uploadLimit > 0) { "upload limit must be > 0: $uploadLimit" }
    }
}

/** The [default] policy, and an entry per network key that replaces all of it for that network. */
data class NetworkRules(
    val default: NetworkPolicy = NetworkPolicy(),
    val networks: Map<String, NetworkPolicy> = emptyMap(),
) {
    init {
        networks.keys.forEach { require(it.isNotBlank() && it != "*") { "network key must not be blank or *: $it" } }
    }

    fun policyFor(network: String): NetworkPolicy = networks[network] ?: default
}

/** Slow mode (NET-06): while [on], downloads are capped at [limit] bytes per second in each direction. */
data class SlowMode(
    val on: Boolean,
    val limit: Long,
) {
    init {
        require(limit > 0) { "slow-mode limit must be >= 1: $limit" }
    }

    companion object {
        const val DEFAULT_LIMIT = 1_048_576L
    }
}
