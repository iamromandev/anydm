package dev.anydm.engine.net

import dev.anydm.engine.EngineEvent
import dev.anydm.engine.log.Log
import dev.anydm.engine.store.RuleStore
import dev.anydm.engine.store.SettingsStore
import dev.anydm.engine.store.TrafficStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.TimeZone
import kotlin.time.Clock

/**
 * Holds the network rules in memory, decides what they ask now ([check]), tells the allowance levels crossed, and hands
 * the queue the effect ([push]). It runs on the engine's publisher; one lock serialises [start], [check], [refresh] and
 * [saveTraffic], which wait on the store, and a second one serialises [push].
 *
 * Usage is counted as the stored bytes of the network in its allowance period (read once per network and period, then
 * grown by each saved batch), plus the meter's unsaved bytes and the [leftovers] of runs that ended: so an allowance trips
 * as the bytes arrive, before they are saved, and never counts a byte twice.
 */
internal class RuleKeeper(
    private val rules: RuleStore,
    private val settings: SettingsStore,
    private val traffic: TrafficStore,
    private val meter: TrafficMeter,
    private val network: NetworkIdentity,
    private val clock: Clock,
    private val timeZone: () -> TimeZone,
    private val log: Log,
    /** Unsaved bytes, received and sent, of runs that ended. */
    private val leftovers: () -> Long,
    private val emit: (EngineEvent) -> Unit,
    /** Hands the queue an effect (`Queue.applyRules`). */
    private val apply: suspend (RuleEffect) -> Unit,
) {
    private val lock = Mutex()
    private val pushing = Mutex()
    private val published = MutableStateFlow(RuleStatus.None)

    /** What the rules decide now; [RuleStatus.None] until [start]. */
    val status: StateFlow<RuleStatus> = published.asStateFlow()

    private var started = false
    private var networks = NetworkRules()
    private var schedule = emptyList<ScheduleRule>()
    private var slowMode = SlowMode(false, SlowMode.DEFAULT_LIMIT)

    // The stored bytes of one network in one period, and which they are.
    private var cachedKey: Pair<String, Period>? = null
    private var cachedBytes = 0L

    private val alerts = AllowanceAlerts()
    private var decided = RuleEffect.None
    private var lastPushed = RuleEffect.None
    private var logged: Pair<RuleAction, Set<RuleSource>> = RuleAction.ALLOW to emptySet()

    /**
     * Loads the rules and decides the first effect, which the caller gives the queue at its start. Levels already crossed
     * are marked told, so a restart over the allowance raises no alert. Nothing is pushed: the effect counts as pushed.
     */
    suspend fun start(): RuleEffect =
        lock.withLock {
            load()
            val decision = evaluate()
            alerts.prime(decision.status.allowance, networks.policyFor(decision.status.network).allowance)
            started = true
            lastPushed = decision.effect
            decision.effect
        }

    /** Reads the stored rules again, then checks. Null before [start]. */
    suspend fun refresh(): RuleDecision? =
        lock.withLock {
            if (!started) return@withLock null
            load()
            checkAndTell()
        }

    /** Decides again from the time, the zone, the network and the usage; tells new alerts. Null before [start]. */
    suspend fun check(): RuleDecision? =
        lock.withLock {
            if (!started) return@withLock null
            checkAndTell()
        }

    /** Gives the queue the newest effect, if it is not the one it has. Pushes wait for each other. */
    suspend fun push() {
        pushing.withLock {
            val effect = decided
            if (effect == lastPushed) return@withLock
            apply(effect)
            lastPushed = effect
        }
    }

    /**
     * Runs [save], which drains the meter and stores the batch, under the keeper's lock, so a check never sees the bytes
     * in neither the meter nor the cache. A returned batch is added to the cached usage; null (nothing, or put back after
     * a failure) and a throw add nothing.
     */
    suspend fun saveTraffic(save: suspend () -> TrafficBatch?) {
        lock.withLock {
            val batch = save() ?: return@withLock
            cachedKey?.let { (name, period) -> cachedBytes += batch.networkBytes(name, period.from.toString(), period.to.toString()) }
        }
    }

    private suspend fun load() {
        networks = rules.networkRules()
        schedule = rules.scheduleRules()
        val stored = settings.load()
        slowMode = SlowMode(stored.slowMode, stored.slowModeLimit)
    }

    private suspend fun checkAndTell(): RuleDecision {
        val decision = evaluate()
        val allowance = networks.policyFor(decision.status.network).allowance
        alerts.crossed(decision.status.allowance, allowance).forEach(emit)
        return decision
    }

    private suspend fun evaluate(): RuleDecision {
        val now = clock.now()
        val zone = timeZone()
        val name = network.current()
        val period = allowancePeriod(networks, name, now, zone)
        val used =
            if (period == null) {
                0L
            } else {
                val key = name to period
                if (key != cachedKey) {
                    cachedBytes = traffic.networkBytes(name, period.from.toString(), period.to.toString())
                    cachedKey = key
                }
                cachedBytes + meter.pendingBytes(name, period.from.toString(), period.to.toString()) + leftovers()
            }
        val decision = decide(RuleInputs(now, zone, name, networks, schedule, slowMode, used))
        published.value = decision.status
        decided = decision.effect
        note(decision.status)
        return decision
    }

    private fun note(status: RuleStatus) {
        val now = status.action to status.because
        if (now == logged) return
        logged = now
        log.info("net") {
            if (status.action ==
                RuleAction.ALLOW
            ) {
                "rules: ALLOW"
            } else {
                "rules: ${status.action} (${status.because.sorted().joinToString(", ")})"
            }
        }
    }
}
