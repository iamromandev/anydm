package dev.anydm.engine.http

import kotlin.jvm.Synchronized
import kotlin.math.ceil

/**
 * A byte-rate cap shared by all downloads. [reserve] books bytes and says how long the caller must wait
 * before writing them. Idle time builds at most one second of burst; debt is how later callers queue up.
 */
class TokenBucket(
    rate: Long?,
) {
    private var rate: Long? = rate
    private var available = 0.0
    private var last = -1L

    @Synchronized
    fun setRate(rate: Long?) {
        this.rate = rate
        if (rate != null) available = available.coerceIn(-rate / 10.0, rate.toDouble())
    }

    /** The largest read worth reserving at once: about 100 ms of the rate, so a changed cap is felt quickly. */
    @Synchronized
    fun maxChunk(): Int = rate?.let { (it / 10).coerceIn(1, 65_536).toInt() } ?: 65_536

    @Synchronized
    fun reserve(
        bytes: Long,
        nowMs: Long,
    ): Long {
        val r = rate ?: return 0
        if (last >= 0) available = minOf(r.toDouble(), available + (nowMs - last) * r / 1000.0)
        last = nowMs
        available -= bytes
        return if (available >= 0) 0 else ceil(-available * 1000.0 / r).toLong()
    }
}
