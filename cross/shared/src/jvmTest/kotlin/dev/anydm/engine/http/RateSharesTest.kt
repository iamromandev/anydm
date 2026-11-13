package dev.anydm.engine.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class RateSharesTest {
    /** The wait for 1 MB after the bucket was primed: 1 s at 1 MB/s, 2 s at 500 KB/s. */
    private fun waitFor(
        b: TokenBucket,
        bytes: Long = 1_000_000,
    ): Long {
        b.reserve(0, 0)
        return b.reserve(bytes, 0)
    }

    @Test
    fun aSingleDownloadGetsTheWholeCap() {
        val shares = RateShares()
        shares.setRate(1_000_000)
        assertEquals(1_000, waitFor(shares.register("a")))
    }

    @Test
    fun twoDownloadsShareTheCapEvenly() {
        val shares = RateShares()
        shares.setRate(1_000_000)
        val a = shares.register("a")
        val b = shares.register("b")
        assertEquals(2_000, waitFor(a)) // 500 KB/s each
        assertEquals(2_000, waitFor(b))
    }

    @Test
    fun aDownloadThatEndsGivesItsShareBack() {
        val shares = RateShares()
        shares.setRate(1_000_000)
        val a = shares.register("a")
        shares.register("b")
        shares.unregister("b")
        assertEquals(1_000, waitFor(a))
    }

    @Test
    fun aCapSetBeforeAnyDownloadAppliesToTheOnesThatComeLater() {
        val shares = RateShares()
        shares.setRate(900_000)
        shares.register("a")
        shares.register("b")
        assertEquals(3_000, waitFor(shares.bucket("a"), 1_350_000)) // 450 KB/s
    }

    @Test
    fun noCapLiftsEveryBucket() {
        val shares = RateShares()
        shares.setRate(1_000)
        val a = shares.register("a")
        val b = shares.register("b")
        shares.setRate(null)
        assertEquals(0, a.reserve(50_000_000, 0))
        assertEquals(0, b.reserve(50_000_000, 0))
        shares.setRate(1_000_000)
        assertEquals(2_000, waitFor(a))
    }

    @Test
    fun registeringAgainReturnsTheSameBucket() {
        val shares = RateShares()
        val a = shares.register("a")
        assertSame(a, shares.register("a"))
        assertSame(a, shares.bucket("a"))
        shares.unregister("a")
        shares.unregister("a") // harmless
    }
}
