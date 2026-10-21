package dev.anydm.engine.net

import kotlinx.datetime.LocalDate
import kotlin.time.Instant

/** One network's data allowance in its current period. */
data class AllowanceStatus(
    val network: String,
    val limit: Long,
    val used: Long,
    /** The period, inclusive local days. */
    val from: LocalDate,
    val to: LocalDate,
    val resetsAt: Instant,
    val warnAt: Long?,
    val over: Boolean,
)

/** What the rules decide now (read model of `Engine.rules`). */
data class RuleStatus(
    val network: String,
    val action: RuleAction,
    /** The sources that gave [action]; empty for ALLOW. */
    val because: Set<RuleSource>,
    val slowMode: SlowMode,
    /** The rules' caps; the engine applies min(global limit, cap). */
    val downloadCap: Long?,
    val uploadCap: Long?,
    /** The current network's allowance, null with none. */
    val allowance: AllowanceStatus?,
    /** The next schedule boundary or allowance reset, null for none. */
    val nextChange: Instant?,
) {
    companion object {
        val None =
            RuleStatus("unknown", RuleAction.ALLOW, emptySet(), SlowMode(false, SlowMode.DEFAULT_LIMIT), null, null, null, null)
    }
}
