package dev.anydm.engine.net

import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** A token bucket with a one-second burst (NET-01), on virtual time. */
class TokenBucketTest {
    private val kib = 1024L

    private fun TestScope.bucket(rate: Long) = TokenBucket(rate, testScheduler.timeSource)

    /** Every window [t(i), t(j)] holds at most rate × its length + one burst + the largest chunk. */
    private fun checkWindows(
        rate: Long,
        takes: List<Pair<Long, Int>>,
    ) {
        val largest = takes.maxOf { it.second }
        for (i in takes.indices) {
            var sum = 0L
            for (j in i until takes.size) {
                sum += takes[j].second
                val window = (takes[j].first - takes[i].first) / 1000.0
                withClue("window ${takes[i].first}..${takes[j].first} ms") {
                    sum.toDouble() shouldBeLessThanOrEqual rate * window + rate + largest
                }
            }
        }
    }

    @Test
    fun throughputStaysUnderTheLimitInEveryWindow() =
        runTest {
            checkAll(30, Arb.long(1024L..(1L shl 20)), Arb.list(Arb.int(1..65536), 50..120), Arb.list(Arb.long(0L..500L), 50..120)) {
                rate,
                chunks,
                gaps,
                ->
                val bucket = bucket(rate)
                val takes = mutableListOf<Pair<Long, Int>>()
                for ((i, n) in chunks.withIndex()) {
                    delay(gaps[i % gaps.size].milliseconds)
                    bucket.take(n)
                    takes += currentTime to n
                }
                checkWindows(rate, takes)
            }
        }

    @Test
    fun backloggedThroughputReachesTheRate() =
        runTest {
            val rate = 100 * kib
            val bucket = bucket(rate)
            var total = 0L
            val start = currentTime
            while (currentTime - start < 30_000) {
                bucket.take(16 * 1024)
                total += 16 * 1024
            }
            total.toDouble() shouldBeGreaterThanOrEqual 0.95 * 30 * rate
        }

    @Test
    fun aChunkAboveTheBurstPassesOnDebt() =
        runTest {
            val bucket = bucket(kib)
            bucket.take(4096)
            currentTime shouldBe 0L
            bucket.take(1)
            currentTime shouldBeGreaterThanOrEqual 2900L
        }

    @Test
    fun loweringTheRateAppliesWithinASecond() =
        runTest {
            val bucket = bucket(100 * kib)
            val takes = mutableListOf<Pair<Long, Int>>()
            val job =
                launch {
                    while (true) {
                        bucket.take(1024)
                        takes += currentTime to 1024
                    }
                }
            advanceTimeBy(5.seconds)
            bucket.setRate(10 * kib)
            advanceTimeBy(11.seconds)
            job.cancel()
            val inWindow = takes.filter { it.first in 6_000L..16_000L }.sumOf { it.second.toLong() }
            inWindow.toDouble() shouldBeLessThanOrEqual 10 * 10 * kib * 1.15
            inWindow.toDouble() shouldBeGreaterThanOrEqual 10 * 10 * kib * 0.85
        }

    @Test
    fun raisingTheRateWakesAWaiter() =
        runTest {
            val bucket = bucket(kib)
            bucket.take(60 * 1024) // a minute of debt
            val waiting = async { bucket.take(1).let { currentTime } }
            advanceTimeBy(1.seconds)
            runCurrent()
            bucket.setRate(1L shl 20)
            waiting.await() shouldBeLessThan 2_000L
        }

    @Test
    fun aTinyRateWithBigChunks() =
        runTest {
            val bucket = bucket(kib)
            val takes = mutableListOf<Pair<Long, Int>>()
            repeat(10) {
                bucket.take(65536)
                takes += currentTime to 65536
            }
            checkWindows(kib, takes)
            takes.last().first shouldBeGreaterThanOrEqual (9 * 63_000L)
        }

    @Test
    fun aHugeRateNeverOverflows() =
        runTest {
            val bucket = bucket(Long.MAX_VALUE)
            delay(1000.days)
            val start = currentTime
            repeat(3) { bucket.take(65536) }
            currentTime shouldBe start
        }
}
