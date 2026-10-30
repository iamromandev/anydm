package dev.anydm.engine.media

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * Spaces out requests to one host (MED-20): each start at an origin is at least a gap (jittered by up to a quarter,
 * so the rhythm isn't mechanical) after the one before it, however many fetches ask at once. Different hosts do not wait
 * for each other.
 */
class HostPacer(
    private val timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
    private val random: Random = Random.Default,
) {
    private val lock = Mutex()
    private val earliest = mutableMapOf<String, ComparableTimeMark>()

    /** Waits for this start's turn at [origin]; a gap of zero or less never waits. */
    suspend fun pace(
        origin: String,
        gap: Duration,
    ) {
        if (gap <= Duration.ZERO) return
        val wait =
            lock.withLock {
                val now = timeSource.markNow()
                val turn = earliest[origin]?.takeIf { it > now } ?: now
                earliest[origin] = turn + gap * (1.0 + random.nextDouble(-JITTER, JITTER))
                turn - now
            }
        if (wait > Duration.ZERO) delay(wait)
    }

    private companion object {
        const val JITTER = 0.25
    }
}
