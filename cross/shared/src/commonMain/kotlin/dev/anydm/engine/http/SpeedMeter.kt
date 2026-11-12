package dev.anydm.engine.http

/** Moving average of throughput over the last [windowMs]. */
class SpeedMeter(
    private val windowMs: Long = 3_000,
) {
    private val times = ArrayDeque<Long>()
    private val sizes = ArrayDeque<Long>()
    private var sum = 0L
    private var first = -1L

    fun add(
        nowMs: Long,
        bytes: Long,
    ) {
        if (first < 0) first = nowMs
        times.addLast(nowMs)
        sizes.addLast(bytes)
        sum += bytes
        expire(nowMs)
    }

    fun bytesPerSec(nowMs: Long): Long {
        expire(nowMs)
        if (sum == 0L) return 0
        val span = (nowMs - first).coerceIn(1_000, windowMs)
        return sum * 1000 / span
    }

    private fun expire(nowMs: Long) {
        while (times.isNotEmpty() && times.first() <= nowMs - windowMs) {
            times.removeFirst()
            sum -= sizes.removeFirst()
        }
    }
}
