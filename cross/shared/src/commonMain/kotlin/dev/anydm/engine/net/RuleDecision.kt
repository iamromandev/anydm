package dev.anydm.engine.net

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** What [decide] needs: the time, the zone, the network and the stored rules, and the bytes used on that network in its period. */
internal data class RuleInputs(
    val now: Instant,
    val zone: TimeZone,
    val network: String,
    val networks: NetworkRules,
    val schedule: List<ScheduleRule>,
    val slowMode: SlowMode,
    /** Bytes on [network] in its allowance period; ignored with no allowance. */
    val used: Long,
    /** The OS says this network is metered (NET-14); false when it can't tell. */
    val detectedMetered: Boolean = false,
)

/** What the queue is told: pause everything that can pause, and cap the speed in each direction. */
internal data class RuleEffect(
    val pause: Boolean,
    val downloadCap: Long?,
    val uploadCap: Long?,
) {
    companion object {
        val None = RuleEffect(false, null, null)
    }
}

internal data class RuleDecision(
    val status: RuleStatus,
    val effect: RuleEffect,
)

/** The policy's allowance period at the local date of [now], or null with no allowance. */
internal fun allowancePeriod(
    networks: NetworkRules,
    network: String,
    now: Instant,
    zone: TimeZone,
): Period? {
    val allowance = networks.policyFor(network).allowance ?: return null
    return periodOf(allowance.period, now.toLocalDateTime(zone).date)
}

internal fun minOfNotNull(
    a: Long?,
    b: Long?,
): Long? =
    if (a == null) {
        b
    } else if (b == null) {
        a
    } else {
        minOf(a, b)
    }

/** The strictest of the schedule, the allowance and slow mode, and the caps that follow. Reads no clock. */
internal fun decide(inputs: RuleInputs): RuleDecision {
    val policy = inputs.networks.policyFor(inputs.network)
    val scheduleAction = scheduleAction(inputs.schedule, inputs.now.toLocalDateTime(inputs.zone))
    val allowance = policy.allowance
    val period = allowancePeriod(inputs.networks, inputs.network, inputs.now, inputs.zone)
    val over = allowance != null && inputs.used >= allowance.bytes
    val allowanceAction =
        when {
            !over -> RuleAction.ALLOW
            allowance!!.atLimit == AllowanceAction.PAUSE -> RuleAction.PAUSE
            else -> RuleAction.SLOW
        }
    val slowAction = if (inputs.slowMode.on) RuleAction.SLOW else RuleAction.ALLOW

    val meteredNow = policy.metered || inputs.detectedMetered
    val meteredAction =
        when {
            !meteredNow -> RuleAction.ALLOW
            policy.onMetered == MeteredAction.SLOW -> RuleAction.SLOW
            policy.onMetered == MeteredAction.PAUSE -> RuleAction.PAUSE
            else -> RuleAction.ALLOW
        }

    val sources =
        listOf(
            RuleSource.SCHEDULE to scheduleAction,
            RuleSource.ALLOWANCE to allowanceAction,
            RuleSource.SLOW_MODE to slowAction,
            RuleSource.METERED to meteredAction,
        )
    val action = sources.maxOf { it.second }
    val because = if (action == RuleAction.ALLOW) emptySet() else sources.filter { it.second == action }.map { it.first }.toSet()

    val slowCap = if (action != RuleAction.ALLOW) inputs.slowMode.limit else null
    val downloadCap = minOfNotNull(policy.speedLimit, slowCap)
    val uploadCap = minOfNotNull(policy.uploadLimit, slowCap)

    val resetsAt = period?.let { resetAt(it, inputs.zone) }
    val boundary = nextScheduleBoundary(inputs.schedule, inputs.now, inputs.zone)
    val nextChange =
        if (boundary == null) {
            resetsAt
        } else if (resetsAt == null) {
            boundary
        } else {
            minOf(boundary, resetsAt)
        }

    val allowanceStatus =
        if (allowance == null || period == null || resetsAt == null) {
            null
        } else {
            AllowanceStatus(
                network = inputs.network,
                limit = allowance.bytes,
                used = inputs.used,
                from = period.from,
                to = period.to,
                resetsAt = resetsAt,
                warnAt = allowance.warnPercent?.let { allowance.bytes * it / 100 },
                over = over,
            )
        }
    val status =
        RuleStatus(inputs.network, action, because, inputs.slowMode, downloadCap, uploadCap, allowanceStatus, nextChange, meteredNow)
    return RuleDecision(status, RuleEffect(action == RuleAction.PAUSE, downloadCap, uploadCap))
}
