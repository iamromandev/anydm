package dev.anydm.engine.net

import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.protocol.Limiter
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.max
import kotlin.math.pow
import kotlin.time.Duration
import kotlin.time.TimeSource

/** A download's share of the global limit: 2^priority, priority clamped to -3..3, so each level doubles it (NET-03). */
internal fun weightOf(priority: Int): Double = 2.0.pow(priority.coerceIn(-3, 3))

/**
 * Every running download's limiter (NET-01..03). A download takes from its own bucket, if it has a limit, then from the
 * global one, if there is one: the strictest limit wins. The global bucket serves waiting downloads by start-time fair
 * queuing: each request is tagged `start = max(virtual time, the download's last finish)`, `finish = start + bytes /
 * weight`, and whenever the bucket has room the waiting request with the lowest start tag takes. So downloads that all wait share by weight, and one that
 * doesn't wait takes no turns: its share goes to the others. Every setter applies at once to running downloads.
 */
internal class Bandwidth(
    private val timeSource: TimeSource.WithComparableMarks,
) {
    private class Flow(
        var weight: Double,
        var bucket: TokenBucket?,
    ) {
        var lastFinish = 0.0
    }

    private class Waiter(
        val start: Double,
        val seq: Long,
    )

    /** Guards every field below. */
    private val lock = Mutex()
    private val flows = mutableMapOf<DownloadId, Flow>()
    private var global: TokenBucket? = null

    /** Requests waiting at the global bucket, by start tag then arrival; the first takes when the bucket allows. */
    private val waiters = mutableListOf<Waiter>()
    private var virtual = 0.0
    private var arrivals = 0L

    /** Bumped whenever the head of [waiters] or the global bucket changes: what waiters wake on. */
    private val changes = MutableStateFlow(0L)

    /** [id]'s limiter, with its own [limit] (bytes/s, null for none) and [priority]; registering again replaces it. */
    suspend fun register(
        id: DownloadId,
        limit: Long?,
        priority: Int,
    ): Limiter {
        val flow = Flow(weightOf(priority), limit?.let { TokenBucket(it, timeSource) })
        lock.withLock { flows[id] = flow }
        return object : Limiter {
            override suspend fun acquire(bytes: Int) {
                lock.withLock { flow.bucket }?.take(bytes)
                shareGlobal(flow, bytes)
            }
        }
    }

    /**
     * What [id] may take now, in bytes per second, or null for no limit (or no such job): the smaller of its own limit and
     * its weighted slice of the global one (`global * weight / the registered weights`). A job on its own at the global
     * limit gets all of it. Fair queuing is work-conserving, so this is the share it is owed, not what it will use.
     */
    suspend fun shareOf(id: DownloadId): Long? {
        val (bucket, globalBucket, fraction) =
            lock.withLock {
                val flow = flows[id] ?: return null
                Triple(flow.bucket, global, flow.weight / flows.values.sumOf { it.weight })
            }
        val own = bucket?.rateNow()?.takeIf { it < Long.MAX_VALUE }
        val slice = globalBucket?.rateNow()?.takeIf { it < Long.MAX_VALUE }?.let { (it * fraction).toLong().coerceAtLeast(1) }
        return listOfNotNull(own, slice).minOrNull()
    }

    suspend fun unregister(id: DownloadId) {
        lock.withLock { flows.remove(id) }
    }

    suspend fun setLimit(
        id: DownloadId,
        limit: Long?,
    ) {
        val released =
            lock.withLock {
                val flow = flows[id] ?: return
                val old = flow.bucket
                when {
                    limit == null -> flow.bucket = null
                    old == null -> flow.bucket = TokenBucket(limit, timeSource)
                    else -> return@withLock old to limit
                }
                old?.let { it to null }
            }
        released?.let { (bucket, limit) -> bucket.setRate(limit ?: Long.MAX_VALUE) }
    }

    suspend fun setPriority(
        id: DownloadId,
        priority: Int,
    ) {
        lock.withLock { flows[id]?.weight = weightOf(priority) }
    }

    suspend fun setGlobal(limit: Long?) {
        val (bucket, rate) =
            lock.withLock {
                val old = global
                when {
                    limit == null -> global = null
                    old == null -> global = TokenBucket(limit, timeSource)
                    else -> return@withLock old to limit
                }
                old to null
            }
        bucket?.setRate(rate ?: Long.MAX_VALUE)
        changed()
    }

    /**
     * Waits until this request is the first by start tag and the global bucket has room, then takes. The turn is decided
     * when there is room, not when the previous request finished, so a download that asks again right after its turn is
     * in line by then.
     */
    private suspend fun shareGlobal(
        flow: Flow,
        bytes: Int,
    ) {
        val waiter =
            lock.withLock {
                if (global == null) return
                val start = max(virtual, flow.lastFinish)
                flow.lastFinish = start + bytes / flow.weight
                Waiter(start, arrivals++).also { waiter ->
                    val at = waiters.indexOfFirst { it.start > waiter.start }
                    if (at < 0) waiters += waiter else waiters.add(at, waiter)
                    if (waiters.first() === waiter) changed()
                }
            }
        try {
            while (true) {
                val seen = changes.value
                val wait =
                    lock.withLock {
                        val bucket = global ?: return
                        if (waiters.first() !== waiter) {
                            Duration.INFINITE
                        } else {
                            bucket.tryTake(bytes) ?: run {
                                waiters.removeFirst()
                                virtual = waiter.start
                                changed()
                                return
                            }
                        }
                    }
                withTimeoutOrNull(wait) { changes.first { it != seen } }
            }
        } finally {
            withContext(NonCancellable) {
                lock.withLock { if (waiters.remove(waiter)) changed() }
            }
        }
    }

    private fun changed() = changes.update { it + 1 }
}
