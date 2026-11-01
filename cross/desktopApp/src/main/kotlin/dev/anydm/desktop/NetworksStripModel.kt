package dev.anydm.desktop

import dev.anydm.engine.net.Allowance
import dev.anydm.engine.net.AllowanceAction
import dev.anydm.engine.net.AllowancePeriod
import dev.anydm.engine.net.AllowanceStatus
import dev.anydm.engine.net.BondedNetwork
import dev.anydm.engine.net.NetworkPolicy
import dev.anydm.engine.net.NetworkRules
import dev.anydm.engine.queue.CommandResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** One network in the strip. [speed] is null when the network carries nothing now (down, off, faulted) or has not yet carried a byte. */
data class StripRow(
    val key: String,
    val name: String,
    val up: Boolean,
    val enabled: Boolean,
    val active: Boolean,
    val faulted: Boolean,
    /** Bonding can use it (it is not marked unusable), so the switch is available. */
    val switchable: Boolean,
    val speed: Long?,
    /** This network's part of the combined speed, 0..1; 0 when it is out of the split or nothing moves. */
    val share: Float,
    /** The speed cap in bytes per second; null for none. */
    val cap: Long?,
    /** The data allowance against its period; null when the network has none. */
    val usage: AllowanceStatus?,
)

data class StripState(
    val rows: List<StripRow> = emptyList(),
    /** The sum of the speeds of the listed networks. */
    val total: Long = 0,
    val collapsed: Boolean = false,
    /** The network whose limits are open. */
    val editing: String? = null,
    val message: String? = null,
)

private data class Limits(
    val rules: NetworkRules = NetworkRules(),
    val usage: Map<String, AllowanceStatus> = emptyMap(),
)

/**
 * The networks strip above the downloads: each network's live speed and part of the total, its cap and its allowance usage.
 * The speeds come from the engine's own list; the caps and the usage are read again every [every] and after a save.
 */
class NetworksStripModel(
    private val source: EngineSource,
    private val scope: CoroutineScope,
    private val window: WindowModel,
    every: Duration = 5.seconds,
) {
    // One read at a time and the value set inside the lock, so an older read can't overwrite a newer one.
    private val loading = Mutex()

    private val limits = MutableStateFlow(Limits())
    private val editing = MutableStateFlow<String?>(null)
    private val message = MutableStateFlow<String?>(null)

    val state: StateFlow<StripState> =
        combine(source.networks, limits, window.state, editing, message) { bonding, limits, win, editing, message ->
            build(bonding.networks, limits, win.stripCollapsed, editing, message)
        }.stateIn(
            scope,
            SharingStarted.Eagerly,
            build(source.networks.value.networks, limits.value, window.state.value.stripCollapsed, null, null),
        )

    init {
        scope.launch {
            while (isActive) {
                load()
                delay(every)
            }
        }
    }

    /** Reads the caps and the usage again. */
    fun refresh(): Job = scope.launch { load() }

    private suspend fun load() {
        try {
            loading.withLock { limits.value = Limits(source.networkRules(), source.allowanceUsage()) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The engine has stopped or a read failed: what is shown stays, and the next read tries again.
        }
    }

    fun setCollapsed(on: Boolean) = window.setStripCollapsed(on)

    fun enable(
        key: String,
        on: Boolean,
    ) {
        scope.launch { command { source.setNetworkEnabled(key, on) } }
    }

    /** Opens the limits of [key] on what the engine has now, so a change made elsewhere is not written over. */
    fun openLimits(key: String) {
        message.value = null
        scope.launch {
            load()
            editing.value = key
        }
    }

    /** The form of [key]'s limits as its policy has them now. */
    fun limitsFormFor(key: String): LimitsForm = limitsForm(limits.value.rules.policyFor(key))

    fun closeLimits() {
        editing.value = null
        message.value = null
    }

    /** Writes [form] as the cap and the daily budget of [key]; everything else in its policy stays as it is. */
    fun saveLimits(
        key: String,
        form: LimitsForm,
    ) {
        form.validate().values.firstOrNull()?.let {
            message.value = it
            return
        }
        scope.launch {
            val saved =
                command {
                    val current = source.networkRules().policyFor(key)
                    source.setNetworkPolicy(key, form.applyTo(current))
                }
            if (saved) {
                editing.value = null
                load()
            }
        }
    }

    // True when the engine did it; otherwise the reason is the message.
    private suspend fun command(block: suspend () -> CommandResult): Boolean =
        try {
            when (val result = block()) {
                is CommandResult.Done -> {
                    message.value = null
                    true
                }

                is CommandResult.Invalid -> {
                    message.value = result.reason
                    false
                }

                else -> {
                    message.value = result.toString()
                    false
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: IllegalStateException) {
            message.value = "The download engine has stopped, so nothing was changed."
            false
        }

    private fun build(
        networks: List<BondedNetwork>,
        limits: Limits,
        collapsed: Boolean,
        editing: String?,
        message: String?,
    ): StripState {
        val speeds = networks.associate { it.info.key to if (it.active) it.speed?.toLong()?.coerceAtLeast(0) else null }
        val total = speeds.values.sumOf { it ?: 0L }
        val rows =
            networks.map { network ->
                val key = network.info.key
                val speed = speeds[key]
                StripRow(
                    key = key,
                    name = network.info.name,
                    up = network.info.up,
                    enabled = network.enabled,
                    active = network.active,
                    faulted = network.faulted,
                    switchable = canSwitch(network),
                    speed = speed,
                    share = if (total > 0 && speed != null) speed.toFloat() / total else 0f,
                    cap = limits.rules.policyFor(key).speedLimit,
                    usage = limits.usage[key],
                )
            }
        // The limits of a network that went away close without saving.
        return StripState(rows, total, collapsed, editing?.takeIf { key -> rows.any { it.key == key } }, message)
    }
}

/**
 * The cap and the daily budget as typed: the cap in KB/s and the budget in MB; empty is none. An allowance with a period
 * other than daily is [other]: it is shown, not edited here, and a save leaves it as it is.
 */
data class LimitsForm(
    val capKbps: String = "",
    val budgetMb: String = "",
    val warnPercent: String = "",
    val atLimit: AllowanceAction = AllowanceAction.PAUSE,
    val other: Allowance? = null,
)

fun limitsForm(policy: NetworkPolicy): LimitsForm {
    val allowance = policy.allowance
    if (allowance != null && allowance.period != AllowancePeriod.Daily) {
        return LimitsForm(capKbps = policyForm(policy).downKbps, other = allowance)
    }
    val form = policyForm(policy)
    return LimitsForm(
        capKbps = form.downKbps,
        budgetMb = if (form.allowanceOn) form.allowanceMb else "",
        warnPercent = if (form.allowanceOn) form.warnPercent else "",
        atLimit = form.atLimit,
    )
}

// The same checks the rules panel makes, over the fields this form has.
private fun LimitsForm.asPolicyForm() =
    PolicyForm(
        downKbps = capKbps,
        allowanceOn = other == null && budgetMb.isNotBlank(),
        allowanceMb = budgetMb,
        period = PeriodKind.DAILY,
        warnPercent = warnPercent,
        atLimit = atLimit,
    )

fun LimitsForm.validate(): Map<RulesField, String> = asPolicyForm().validate()

/** [policy] with this cap and, unless it has an allowance of another period, a daily budget; the rest stays. Only for a form that [validate]s. */
fun LimitsForm.applyTo(policy: NetworkPolicy): NetworkPolicy {
    val set = asPolicyForm().toPolicy()
    // Decided on the policy as it is now: an allowance of another period, set anywhere since the form was made, stays.
    val keep = other != null || policy.allowance?.period.let { it != null && it != AllowancePeriod.Daily }
    return policy.copy(speedLimit = set.speedLimit, allowance = if (keep) policy.allowance else set.allowance)
}

/** `Monthly 10 GB`: an allowance as the limits show it when they can't change it. */
fun allowanceSummary(allowance: Allowance): String {
    val period =
        when (allowance.period) {
            AllowancePeriod.Daily -> "Daily"
            is AllowancePeriod.Weekly -> "Weekly"
            is AllowancePeriod.Monthly -> "Monthly"
        }
    return "$period ${formatBytes(allowance.bytes).replace(".0 ", " ")}"
}

/** `1.5 / 2 GB`: the used bytes over the limit, in one unit when both have it. */
fun budgetText(usage: AllowanceStatus): String {
    val used = formatBytes(usage.used)
    val limit = formatBytes(usage.limit).replace(".0 ", " ")
    val unit = limit.substringAfter(' ')
    return if (used.endsWith(" $unit")) "${used.removeSuffix(" $unit")} / $limit" else "$used / $limit"
}
