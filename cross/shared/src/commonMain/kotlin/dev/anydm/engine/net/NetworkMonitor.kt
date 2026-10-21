package dev.anydm.engine.net

import dev.anydm.engine.log.Log
import dev.anydm.engine.platform.MeteredDetector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** The network now (NET-13, NET-14). */
data class NetworkState(
    val online: Boolean,
    /** The [NetworkIdentity] key; `"unknown"` when offline or unreadable. */
    val key: String,
    /** What the detector says; null = it cannot tell or there is no detector. */
    val metered: Boolean?,
    /** When online or the key last changed. */
    val since: Instant,
) {
    companion object {
        /** Before the monitor has looked. */
        val Unknown = NetworkState(online = true, key = "unknown", metered = null, since = Instant.DISTANT_PAST)
    }
}

/**
 * A [state] with the number of drops (online to offline changes) seen so far. A flow of states conflates, so a drop
 * that ended before the collector ran can only be told from this count.
 */
internal data class Observed(
    val state: NetworkState,
    val drops: Int,
)

/**
 * Polls whether the machine is online and on which network (NET-13). A regular [tick] goes offline at the second miss in
 * a row; [pollNow] and [first] believe one answer. One lock covers every function, so the queue may call [pollNow] from
 * its own coroutine while the publisher ticks.
 */
internal class NetworkMonitor(
    private val probe: OnlineProbe,
    private val identity: NetworkIdentity,
    private val clock: Clock,
    private val log: Log,
) {
    private val lock = Mutex()
    private val current = MutableStateFlow(NetworkState.Unknown)
    private val seen = MutableStateFlow(Observed(NetworkState.Unknown, 0))
    private var misses = 0
    private var drops = 0

    /** The network now; [NetworkState.Unknown] until [first]. */
    val state: StateFlow<NetworkState> = current.asStateFlow()

    /** [state] with the drop count, updated together with it under the lock. */
    val observed: StateFlow<Observed> = seen.asStateFlow()

    /** One poll taken as true at once (no debounce), at startup. Never counts a drop. */
    suspend fun first(): NetworkState =
        lock.withLock {
            misses = 0
            apply(poll(), counted = false)
        }

    /** One regular poll: a good one goes online at once, the second miss in a row goes offline. */
    suspend fun tick(): NetworkState =
        lock.withLock {
            if (poll()) {
                misses = 0
                apply(true, counted = true)
            } else {
                misses++
                apply(if (misses >= 2) false else current.value.online, counted = true)
            }
        }

    /** An immediate poll: offline is confirmed at once. */
    suspend fun pollNow(): NetworkState =
        lock.withLock {
            val online = poll()
            misses = if (online) 0 else 2
            apply(online, counted = true)
        }

    /** The detector's answer for [key]; dropped when [key] is no longer the current network. */
    suspend fun setMetered(
        key: String,
        value: Boolean?,
    ) {
        lock.withLock {
            val now = current.value
            if (!now.online || now.key != key || now.metered == value) return@withLock
            publish(now.copy(metered = value))
        }
    }

    private fun poll(): Boolean =
        try {
            probe.isOnline()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            false
        }

    private fun readKey(): String =
        try {
            identity.fresh()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            "unknown"
        }

    private fun apply(
        online: Boolean,
        counted: Boolean,
    ): NetworkState {
        val before = current.value
        val key = if (online) readKey() else "unknown"
        val first = before === NetworkState.Unknown
        val changed = first || online != before.online || key != before.key
        if (counted && before.online && !online && !first) drops++
        val next =
            if (changed) {
                NetworkState(online, key, null, clock.now())
            } else {
                before
            }
        if (first || online != before.online) {
            log.info("net") { if (online) "network: online ($key)" else "network: offline" }
        } else if (key != before.key) {
            log.info("net") { "network: ${before.key} -> $key" }
        }
        publish(next)
        return next
    }

    private fun publish(next: NetworkState) {
        current.value = next
        seen.value = Observed(next, drops)
    }
}

/** What the engine does after a change in what the monitor observed. */
internal sealed interface NetworkStep {
    data object Lost : NetworkStep

    data object Restored : NetworkStep

    data class Changed(
        val from: String,
        val to: String,
    ) : NetworkStep

    data object MeteredChanged : NetworkStep
}

/**
 * Turns what the monitor observed into steps. It remembers what the engine last acted on, so a flow that skipped a state
 * still gives the right steps: a drop that ended before the collector ran gives [NetworkStep.Lost] then
 * [NetworkStep.Restored] (the queue's own failed poll may have set it offline).
 */
internal class NetworkWatch(
    start: Observed,
) {
    private var told = start.state.online
    private var lastKey = start.state.key
    private var lastMetered = start.state.metered
    private var lastDrops = start.drops

    fun next(now: Observed): List<NetworkStep> {
        val steps = mutableListOf<NetworkStep>()
        val state = now.state
        if (told && (!state.online || now.drops != lastDrops)) {
            steps += NetworkStep.Lost
            told = false
        }
        if (!told && state.online) {
            steps += NetworkStep.Restored
            told = true
        }
        if (steps.isEmpty() && state.online) {
            if (state.key != lastKey && state.key != "unknown" && lastKey != "unknown") {
                steps += NetworkStep.Changed(lastKey, state.key)
            } else if (state.metered != lastMetered) {
                steps += NetworkStep.MeteredChanged
            }
        }
        lastKey = state.key
        lastMetered = state.metered
        lastDrops = now.drops
        return steps
    }
}

/**
 * Asks the [detector] whether the current network is metered and gives the answer to the [monitor] (NET-14). A regular
 * [refresh] runs in its own coroutine on [scope], so a slow command never delays the 3 s poll; only [first] waits.
 * A timeout or failure keeps the last answer for the same network, or null for a new one.
 */
internal class MeteredRefresh(
    private val detector: MeteredDetector,
    private val monitor: NetworkMonitor,
    private val clock: Clock,
    private val log: Log,
    private val scope: CoroutineScope,
    private val every: Duration = 5.minutes,
    private val timeout: Duration = 5.seconds,
) {
    private val cache = mutableMapOf<String, Boolean>()
    private var lastKey: String? = null
    private var lastOnline = false
    private var lastAt: Instant? = null
    private var inFlight: Job? = null

    /** Detects for [state] and waits for the answer (startup). */
    suspend fun first(state: NetworkState) {
        lastOnline = state.online
        if (!state.online) return
        lastKey = state.key
        lastAt = clock.now()
        detect(state.key)
    }

    /** Launches one detection when due; none while one for this network is in flight. */
    fun refresh(state: NetworkState) {
        val wasOnline = lastOnline
        lastOnline = state.online
        if (!state.online) return
        val at = lastAt
        val keyChanged = state.key != lastKey
        val due = keyChanged || !wasOnline || at == null || clock.now() - at >= every
        if (!due) return
        if (!keyChanged && inFlight?.isActive == true) return
        inFlight?.cancel()
        lastKey = state.key
        lastAt = clock.now()
        val key = state.key
        val known = keyChanged && cache.containsKey(key)
        inFlight =
            scope.launch {
                if (known) monitor.setMetered(key, cache[key])
                detect(key)
            }
    }

    private suspend fun detect(key: String) {
        val answer =
            try {
                withTimeoutOrNull(timeout) { detector.isMetered(key) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                log.debug("net") { "metered detection failed: ${e.message}" }
                null
            }
        if (answer == null) {
            log.debug("net") { "metered detection gave no answer for $key" }
        } else {
            cache[key] = answer
        }
        monitor.setMetered(key, answer ?: cache[key])
    }
}
