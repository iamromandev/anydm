package dev.anydm.engine.net

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Notices that the machine slept or froze (NET-12). Fed once per [tick] with the wall clock and a monotonic reading. On
 * macOS and Linux the monotonic clock stops in sleep, so the wall clock jumps; on Windows both may jump. Either one
 * exceeding [tick] plus [gap] counts. A wall clock set back is ignored, and a daylight-saving change never moves an
 * [Instant].
 */
internal class WakeDetector(
    private val tick: Duration = 5.seconds,
    private val gap: Duration = 30.seconds,
) {
    private var last: Pair<Instant, Duration>? = null

    /** The time that passed when the machine slept or froze since the last call, else null. */
    fun observe(
        wall: Instant,
        monotonic: Duration,
    ): Duration? {
        val before = last
        last = wall to monotonic
        if (before == null) return null
        val elapsed = maxOf(wall - before.first, monotonic - before.second)
        return if (elapsed - tick > gap) elapsed else null
    }
}
