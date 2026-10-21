package dev.anydm.engine.net

import dev.anydm.engine.EngineEvent
import kotlinx.datetime.LocalDate

/**
 * Tells each allowance level (warning, reached) once per network, period and allowance value (NET-07). A new period or an
 * edited allowance starts over; going back to a network in the same period does not. Not thread-safe: the keeper uses it
 * from the publisher.
 */
internal class AllowanceAlerts {
    private enum class Level { WARN, REACHED }

    private data class Told(
        val network: String,
        val from: LocalDate,
        val allowance: Allowance,
        val level: Level,
    )

    private val told = mutableSetOf<Told>()

    /** Marks the levels already crossed in [status] as told, without events (engine start). */
    fun prime(
        status: AllowanceStatus?,
        allowance: Allowance?,
    ) {
        levels(status, allowance).forEach { (key, _) -> told += key }
    }

    /** The events for the levels [status] crossed that weren't told yet for this network, period and allowance. */
    fun crossed(
        status: AllowanceStatus?,
        allowance: Allowance?,
    ): List<EngineEvent> = levels(status, allowance).mapNotNull { (key, event) -> if (told.add(key)) event else null }

    private fun levels(
        status: AllowanceStatus?,
        allowance: Allowance?,
    ): List<Pair<Told, EngineEvent>> {
        if (status == null || allowance == null) return emptyList()
        val found = mutableListOf<Pair<Told, EngineEvent>>()
        if (status.warnAt != null && status.used >= status.warnAt) {
            found +=
                Told(status.network, status.from, allowance, Level.WARN) to
                EngineEvent.AllowanceWarning(status.network, status.used, status.limit, status.resetsAt)
        }
        if (status.over) {
            found +=
                Told(status.network, status.from, allowance, Level.REACHED) to
                EngineEvent.AllowanceReached(status.network, status.used, status.limit, allowance.atLimit, status.resetsAt)
        }
        return found
    }
}
