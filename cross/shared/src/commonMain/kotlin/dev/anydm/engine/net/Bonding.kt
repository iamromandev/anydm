package dev.anydm.engine.net

import dev.anydm.engine.log.Log
import dev.anydm.engine.platform.NetworkBinder
import dev.anydm.engine.platform.NetworkLister
import dev.anydm.engine.store.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** One network as bonding sees it (BND-01). */
data class BondedNetwork(
    val info: NetworkInfo,
    /** The user turned it on. */
    val enabled: Boolean,
    val status: BindStatus,
    /** Bytes per second it carried recently, smoothed; null before it has carried anything. */
    val speed: Double?,
    /** It failed a bound connection a moment ago and is left out for a while. */
    val faulted: Boolean,
) {
    /** Connections are put on it: on, up, bindable and not failing. */
    val active: Boolean
        get() = enabled && info.up && !faulted && (status == BindStatus.ISOLATED || status == BindStatus.BEST_EFFORT)
}

/** Every network the machine has, and which of them bonding uses (BND-01). */
data class BondingState(
    val networks: List<BondedNetwork>,
) {
    val active: List<BondedNetwork> get() = networks.filter { it.active }

    /** Two or more networks are in use: downloads are bound to them. With fewer, nothing is bound (BND-01). */
    val bonded: Boolean get() = active.size >= 2
}

/** Which networks the user turned on (BND-01); kept apart from the settings so a test needs no database. */
internal interface BondChoices {
    suspend fun enabled(): Set<String>

    suspend fun set(
        key: String,
        enabled: Boolean,
    )
}

internal class SettingsBondChoices(
    private val settings: SettingsStore,
) : BondChoices {
    override suspend fun enabled(): Set<String> = settings.load().bondedNetworks

    override suspend fun set(
        key: String,
        enabled: Boolean,
    ) {
        settings.update { it.copy(bondedNetworks = if (enabled) it.bondedNetworks + key else it.bondedNetworks - key) }
    }
}

/** What a download's workers ask of bonding while they run (BND-03). */
internal interface Bond {
    /** The networks connections may be put on now: on, up, bindable and not failing. Two or more make a bonded download. */
    fun active(): List<NetworkInfo>

    /** Whether connections may still be put on this network. */
    fun isActive(key: String): Boolean

    /** Bytes per second each network carried recently. */
    fun speeds(): Map<String, Double>

    /** A bound connection failed on this network: it is left out for a while, and the engine restarts the connections. */
    suspend fun fault(key: String)
}

/** A whole download's place on one network (FTP, SFTP); [close] gives it back. */
internal class WholeLease(
    val network: NetworkInfo,
    private val release: () -> Unit,
) : AutoCloseable {
    private val closed = MutableStateFlow(false)

    override fun close() {
        if (closed.compareAndSet(expect = false, update = true)) release()
    }
}

/**
 * The networks, which the user turned on, and which of them downloads are bound to (BND-01, BND-03). [refresh] is called
 * on the engine's regular tick: it lists the networks, probes each new one once to see whether a connection can be tied to
 * it (BND-02) and publishes the result in [state]. Bonding is on only with two or more networks in use; with fewer every
 * download goes by the default route as before. Speeds are measured from the bytes each network carries ([observe]) and
 * decide the share of connections each gets.
 */
internal class Bonding(
    private val lister: NetworkLister,
    private val binder: NetworkBinder,
    private val choices: BondChoices,
    private val clock: Clock,
    private val log: Log,
) : Bond {
    private class Probed(
        val addresses: List<String>,
        val up: Boolean,
        val status: BindStatus,
    )

    private val lock = Mutex()
    private val probed = mutableMapOf<String, Probed>()
    private val faults = mutableMapOf<String, Instant>()
    private var roster: List<NetworkInfo> = emptyList()
    private var enabled: Set<String> = emptySet()

    private val speedMap = MutableStateFlow<Map<String, Double>>(emptyMap())
    private val running = MutableStateFlow<Map<String, Int>>(emptyMap())
    private val current = MutableStateFlow(BondingState(emptyList()))

    val state: StateFlow<BondingState> = current.asStateFlow()

    /** Lists the networks again, probes the new ones and publishes what changed. A listing that fails keeps what was known. */
    suspend fun refresh() =
        lock.withLock {
            val listed =
                try {
                    lister.list()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("net", e) { "the networks can't be listed" }
                    return@withLock
                }
            enabled = choices.enabled()
            for (info in listed) {
                val last = probed[info.key]
                probed[info.key] =
                    when {
                        !info.up -> {
                            Probed(info.addresses, up = false, status = BindStatus.UNKNOWN)
                        }

                        last == null || !last.up || last.addresses != info.addresses -> {
                            Probed(
                                info.addresses,
                                up = true,
                                status = probe(info),
                            )
                        }

                        else -> {
                            last
                        }
                    }
            }
            probed.keys.retainAll(listed.map { it.key }.toSet())
            val now = clock.now()
            faults.entries.removeAll { it.value <= now }
            roster = listed
            publish()
        }

    private suspend fun probe(info: NetworkInfo): BindStatus =
        try {
            binder.probe(info).also { log.debug("net") { "network ${info.key}: $it" } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("net", e) { "network ${info.key} can't be probed" }
            BindStatus.UNSUPPORTED
        }

    /** Turns [key] on or off. A network that is not there now keeps its choice for when it returns. */
    suspend fun setEnabled(
        key: String,
        enabled: Boolean,
    ) {
        choices.set(key, enabled)
        lock.withLock {
            this.enabled = choices.enabled()
            publish()
        }
    }

    // Under the lock.
    private fun publish() {
        val now = clock.now()
        val speeds = speedMap.value
        val next =
            BondingState(
                roster.map { info ->
                    BondedNetwork(
                        info = info,
                        enabled = info.key in enabled,
                        status = probed[info.key]?.status ?: BindStatus.UNKNOWN,
                        speed = speeds[info.key],
                        faulted = faults[info.key]?.let { it > now } == true,
                    )
                },
            )
        if (next != current.value) current.value = next
    }

    /** One second of traffic: the bytes each network carried in it. A network that carried none keeps the speed it had. */
    fun observe(bytes: Map<String, Long>) {
        for ((key, count) in bytes) {
            if (count <= 0) continue
            speedMap.update { speeds ->
                val old = speeds[key]
                speeds + (key to if (old == null) count.toDouble() else old + SMOOTHING * (count - old))
            }
        }
    }

    override fun speeds(): Map<String, Double> = speedMap.value

    /** [total] connections shared among the networks in use by measured speed; empty when fewer than two are in use. */
    fun targets(total: Int): Map<String, Int> {
        val active = current.value.active
        if (active.size < 2) return emptyMap()
        val speeds = speedMap.value
        return apportion(total, active.associate { it.info.key to (speeds[it.info.key] ?: 0.0) })
    }

    override fun active(): List<NetworkInfo> = current.value.active.map { it.info }

    override fun isActive(key: String): Boolean = current.value.active.any { it.info.key == key }

    override suspend fun fault(key: String) {
        lock.withLock {
            faults[key] = clock.now() + FAULT_HOLD
            log.warn("net") { "network $key failed a bound connection and is left out for $FAULT_HOLD" }
            publish()
        }
    }

    /** A place for a whole download on the network that carries the fewest per unit of speed; null when bonding is off. */
    fun leaseWhole(): WholeLease? {
        val active = current.value.active
        if (active.size < 2) return null
        val keys = active.map { it.info.key }
        while (true) {
            val before = running.value
            val key = pickForDownload(keys, before, speedMap.value)
            if (running.compareAndSet(before, before + (key to (before[key] ?: 0) + 1))) {
                val info = active.first { it.info.key == key }.info
                return WholeLease(info) { running.update { m -> m + (key to maxOf((m[key] ?: 1) - 1, 0)) } }
            }
        }
    }

    private companion object {
        /** How much of a new second counts in a network's speed. */
        const val SMOOTHING = 0.3

        /** How long a network that failed a bound connection is left out. */
        val FAULT_HOLD = 30.seconds
    }
}
