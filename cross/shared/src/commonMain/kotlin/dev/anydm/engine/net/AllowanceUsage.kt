package dev.anydm.engine.net

import dev.anydm.engine.store.DayTraffic
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * The usage of every network that has an allowance, each in its own period at [now]: the networks with a policy of their
 * own, and the [known] ones that fall back to the default policy. Counted as the rules count it: bytes received and sent
 * on the network over the period's local days, from [days] (which must cover them) plus [pending], the unsaved bytes of
 * a network over `from..to` (`YYYY-MM-DD`). A network with no allowance has no entry.
 */
internal fun allowanceUsageOf(
    rules: NetworkRules,
    days: List<DayTraffic>,
    now: Instant,
    zone: TimeZone,
    known: Set<String> = emptySet(),
    pending: (network: String, from: String, to: String) -> Long = { _, _, _ -> 0L },
): Map<String, AllowanceStatus> =
    (rules.networks.keys + known)
        .mapNotNullTo(mutableListOf()) { network ->
            val allowance = rules.policyFor(network).allowance ?: return@mapNotNullTo null
            val period = periodOf(allowance.period, now.toLocalDateTime(zone).date)
            val from = period.from.toString()
            val to = period.to.toString()
            val used =
                days.filter { it.network == network && it.day >= from && it.day <= to }.sumOf { it.bytes + it.bytesUp } +
                    pending(network, from, to)
            network to
                AllowanceStatus(
                    network = network,
                    limit = allowance.bytes,
                    used = used,
                    from = period.from,
                    to = period.to,
                    resetsAt = resetAt(period, zone),
                    warnAt = allowance.warnPercent?.let { allowance.bytes * it / 100 },
                    over = used >= allowance.bytes,
                )
        }.toMap()

/** The local days (`YYYY-MM-DD`, inclusive) that hold every period [allowanceUsageOf] counts; null when no network has an allowance. */
internal fun allowanceDays(
    rules: NetworkRules,
    now: Instant,
    zone: TimeZone,
    known: Set<String> = emptySet(),
): Pair<String, String>? {
    val today = now.toLocalDateTime(zone).date
    val periods =
        (rules.networks.keys + known).mapNotNull { network ->
            rules.policyFor(network).allowance?.let { periodOf(it.period, today) }
        }
    if (periods.isEmpty()) return null
    return periods.minOf { it.from }.toString() to periods.maxOf { it.to }.toString()
}
