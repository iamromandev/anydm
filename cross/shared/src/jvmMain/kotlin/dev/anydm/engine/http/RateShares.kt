package dev.anydm.engine.http

/**
 * The speed cap and one [TokenBucket] per running download: each gets the cap divided by the number running, so the cap
 * is split between downloads, not connections. A null cap lifts every bucket.
 */
class RateShares {
    private var cap: Long? = null
    private val buckets = LinkedHashMap<String, TokenBucket>()

    @Synchronized
    fun register(id: String): TokenBucket {
        val b = buckets.getOrPut(id) { TokenBucket(null) }
        apply()
        return b
    }

    @Synchronized
    fun unregister(id: String) {
        if (buckets.remove(id) != null) apply()
    }

    @Synchronized
    fun setRate(bytesPerSec: Long?) {
        cap = bytesPerSec
        apply()
    }

    /** The bucket of a registered download. */
    @Synchronized
    fun bucket(id: String): TokenBucket = checkNotNull(buckets[id]) { "download $id is not registered" }

    private fun apply() {
        val share = cap?.let { (it / buckets.size.coerceAtLeast(1)).coerceAtLeast(1) }
        buckets.values.forEach { it.setRate(share) }
    }
}
