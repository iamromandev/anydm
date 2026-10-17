package dev.anydm.engine.http

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * Connection slots per origin, shared by every download to it (HTTP-06): at most [cap] at once, read on every acquire,
 * so a lower cap applies to the next one and held slots are never taken away. An owner is one transfer run. Waiters are
 * served in order, and an owner that already holds a slot can't take another while someone waits.
 */
internal class HostSlots(
    private val cap: () -> Int,
) {
    private class Waiter(
        val owner: Any,
    ) {
        val granted = CompletableDeferred<Unit>()
    }

    private class Host {
        val held = mutableMapOf<Any, Int>()
        var total = 0
        val waiters = ArrayDeque<Waiter>()

        fun take(owner: Any) {
            held[owner] = (held[owner] ?: 0) + 1
            total++
        }

        fun give(owner: Any) {
            val left = checkNotNull(held[owner]) { "owner holds no slot" } - 1
            if (left == 0) held.remove(owner) else held[owner] = left
            total--
        }
    }

    private val lock = Mutex()
    private val hosts = mutableMapOf<String, Host>()

    /** Waits until [origin] has a free slot, then takes it for [owner]. */
    suspend fun acquire(
        origin: String,
        owner: Any,
    ) {
        val waiter =
            lock.withLock {
                val host = hosts.getOrPut(origin) { Host() }
                if (host.waiters.isEmpty() && host.total < cap()) {
                    host.take(owner)
                    return
                }
                Waiter(owner).also { host.waiters.addLast(it) }
            }
        try {
            waiter.granted.await()
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                lock.withLock {
                    val host = hosts.getValue(origin)
                    // Granted just as it was cancelled: the slot is ours, so hand it on.
                    if (!host.waiters.remove(waiter)) giveBack(origin, host, owner)
                }
            }
            throw e
        }
    }

    /** Takes a slot for [owner] if one is free and nobody is waiting; never waits. */
    suspend fun tryAcquire(
        origin: String,
        owner: Any,
    ): Boolean =
        lock.withLock {
            val host = hosts.getOrPut(origin) { Host() }
            if (host.waiters.isNotEmpty() || host.total >= cap()) return@withLock false
            host.take(owner)
            true
        }

    suspend fun release(
        origin: String,
        owner: Any,
    ) = lock.withLock { giveBack(origin, hosts.getValue(origin), owner) }

    /** True when [owner] holds more than one slot and another owner waits for [origin]. */
    suspend fun shouldYield(
        origin: String,
        owner: Any,
    ): Boolean =
        lock.withLock {
            val host = hosts[origin] ?: return@withLock false
            (host.held[owner] ?: 0) > 1 && host.waiters.any { it.owner !== owner }
        }

    suspend fun held(origin: String): Int = lock.withLock { hosts[origin]?.total ?: 0 }

    private fun giveBack(
        origin: String,
        host: Host,
        owner: Any,
    ) {
        host.give(owner)
        while (host.waiters.isNotEmpty() && host.total < cap()) {
            val next = host.waiters.removeFirst()
            host.take(next.owner)
            next.granted.complete(Unit)
        }
        if (host.total == 0 && host.waiters.isEmpty()) hosts.remove(origin)
    }
}
