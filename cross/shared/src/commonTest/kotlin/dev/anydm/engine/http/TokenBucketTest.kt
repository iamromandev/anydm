package dev.anydm.engine.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TokenBucketTest {
    @Test
    fun unlimitedNeverWaits() = assertEquals(0, TokenBucket(null).reserve(10_000_000, 0))

    @Test
    fun waitsForTheDebt() {
        val b = TokenBucket(1_000)
        b.reserve(0, 0)
        assertEquals(1_000, b.reserve(1_000, 0)) // 1000 B at 1000 B/s
        assertEquals(2_000, b.reserve(1_000, 0)) // queued behind the first
    }

    @Test
    fun throughputOverTimeMatchesRate() {
        val b = TokenBucket(1_000_000)
        var now = 0L
        var sent = 0L
        while (now < 3_000) {
            now += b.reserve(65_536, now)
            sent += 65_536
        }
        // everything reserved so far fits the rate over `now` ms
        val perSec = sent * 1000 / now
        assertTrue(perSec in 900_000..1_100_000, "got $perSec")
    }

    @Test
    fun idleTimeBuildsAtMostOneSecondOfBurst() {
        val b = TokenBucket(1_000)
        b.reserve(0, 0)
        assertEquals(0, b.reserve(1_000, 60_000)) // burst = 1 s of rate
        assertEquals(1_000, b.reserve(1_000, 60_000)) // but not more
    }

    @Test
    fun setRateAppliesToNextReserve() {
        val b = TokenBucket(1_000)
        b.reserve(0, 0)
        b.setRate(2_000)
        assertEquals(500, b.reserve(1_000, 0))
        b.setRate(null)
        assertEquals(0, b.reserve(1_000_000, 0))
    }

    @Test
    fun raisingTheRateForgivesLongDebt() {
        val b = TokenBucket(100)
        b.reserve(0, 0)
        b.reserve(1_000_000, 0) // huge debt at 100 B/s
        b.setRate(1_000_000)
        assertTrue(b.reserve(1_000, 0) <= 1_000)
    }

    @Test
    fun chunkLimitShrinksWithLowRates() {
        assertEquals(65_536, TokenBucket(null).maxChunk())
        assertEquals(65_536, TokenBucket(100_000_000).maxChunk())
        assertEquals(100, TokenBucket(1_000).maxChunk())
        assertEquals(1, TokenBucket(1).maxChunk())
    }
}
