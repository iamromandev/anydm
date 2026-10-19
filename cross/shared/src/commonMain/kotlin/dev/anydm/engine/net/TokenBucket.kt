package dev.anydm.engine.net

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.min
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit
import kotlin.time.TimeSource

/**
 * A token bucket (NET-01): [rate] bytes per second, holding at most one second of them (the burst). It starts full and
 * refills continuously. A take of more than the burst still passes once the bucket is full, and leaves it in debt for
 * the next taker to wait off, so over any window W at most `rate × W + burst + the largest take` pass. Takers are
 * served in turn; [setRate] applies at once and wakes a waiter to recompute. Arithmetic is in Double: no rate overflows.
 */
internal class TokenBucket(
    rate: Long,
    private val timeSource: TimeSource.WithComparableMarks,
) {
    /** Guards [rate], [tokens] and [last]; held only briefly, never while waiting. */
    private val state = Mutex()

    /** One taker at a time, in arrival order. */
    private val turns = Mutex()

    private var rate: Double
    private var tokens: Double
    private var last: ComparableTimeMark = timeSource.markNow()

    /** Bumped by [setRate]: what a waiter wakes on. */
    private val changes = MutableStateFlow(0L)

    init {
        require(rate > 0) { "rate must be > 0: $rate" }
        this.rate = rate.toDouble()
        tokens = this.rate
    }

    suspend fun take(bytes: Int) {
        require(bytes >= 0) { "bytes must be >= 0: $bytes" }
        turns.withLock {
            while (true) {
                val seen = changes.value
                val wait =
                    state.withLock {
                        refill()
                        val need = min(bytes.toDouble(), rate)
                        if (tokens >= need) {
                            tokens -= bytes
                            return
                        }
                        ((need - tokens) / rate).seconds.coerceAtLeast(1.milliseconds)
                    }
                withTimeoutOrNull(wait) { changes.first { it != seen } }
            }
        }
    }

    /**
     * Takes [bytes] now if the bucket allows it (as [take] would) and returns null; otherwise takes nothing and returns
     * how long until it would. For a caller that orders takers itself.
     */
    suspend fun tryTake(bytes: Int): Duration? =
        state.withLock {
            refill()
            val need = min(bytes.toDouble(), rate)
            if (tokens >= need) {
                tokens -= bytes
                null
            } else {
                ((need - tokens) / rate).seconds.coerceAtLeast(1.milliseconds)
            }
        }

    /** The rate now, in bytes per second (Long.MAX_VALUE after a limit was lifted). */
    suspend fun rateNow(): Long = state.withLock { rate.toLong() }

    suspend fun setRate(rate: Long) {
        require(rate > 0) { "rate must be > 0: $rate" }
        state.withLock {
            refill()
            this.rate = rate.toDouble()
            tokens = min(tokens, this.rate)
        }
        changes.update { it + 1 }
    }

    /** Adds what [rate] earned since [last], up to one second's worth. */
    private fun refill() {
        val now = timeSource.markNow()
        val elapsed = (now - last).toDouble(DurationUnit.SECONDS)
        last = now
        tokens = min(rate, tokens + elapsed * rate)
    }
}
