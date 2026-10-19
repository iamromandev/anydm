package dev.anydm.engine.net

import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.protocol.Limiter
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/** Per-download and global limits, and the global limit shared by priority (NET-02, NET-03), on virtual time. */
class BandwidthTest {
    private val kib = 1024L
    private val a = DownloadId(1)
    private val b = DownloadId(2)

    private class Counter {
        var bytes = 0L
    }

    /** Acquires 16 KiB chunks back to back, counting them. */
    private fun TestScope.backlog(
        limiter: Limiter,
        counter: Counter,
    ): Job =
        launch {
            while (true) {
                limiter.acquire(16 * 1024)
                counter.bytes += 16 * 1024
            }
        }

    /** Bytes per second each counter gained over [seconds] after a 5 s warm-up. */
    private fun TestScope.rates(
        vararg counters: Counter,
        seconds: Int = 60,
    ): List<Double> {
        advanceTimeBy(5.seconds)
        val before = counters.map { it.bytes }
        advanceTimeBy(seconds.seconds)
        return counters.mapIndexed { i, c -> (c.bytes - before[i]).toDouble() / seconds }
    }

    private infix fun Double.near(expected: Double) {
        this shouldBeGreaterThanOrEqual expected * 0.9
        this shouldBeLessThanOrEqual expected * 1.1
    }

    @Test
    fun theStrictestLimitWins() =
        runTest {
            val bandwidth = Bandwidth(testScheduler.timeSource)
            bandwidth.setGlobal(100 * kib)
            val ownLow = Counter()
            val job = backlog(bandwidth.register(a, 10 * kib, 0), ownLow)
            rates(ownLow).single() near 10.0 * kib
            job.cancel()
            bandwidth.unregister(a)

            bandwidth.setGlobal(10 * kib)
            val globalLow = Counter()
            backlog(bandwidth.register(b, 100 * kib, 0), globalLow).also {
                rates(globalLow).single() near 10.0 * kib
                it.cancel()
            }
        }

    @Test
    fun prioritiesShareTheGlobalLimit() =
        runTest {
            val bandwidth = Bandwidth(testScheduler.timeSource)
            bandwidth.setGlobal(90 * kib)
            val low = Counter()
            val high = Counter()
            val jobs = listOf(backlog(bandwidth.register(a, null, 0), low), backlog(bandwidth.register(b, null, 1), high))
            val (lowRate, highRate) = rates(low, high)
            (highRate / lowRate) near 2.0
            (lowRate + highRate) near 90.0 * kib
            jobs.forEach { it.cancel() }
        }

    @Test
    fun prioritiesAreClamped() =
        runTest {
            val bandwidth = Bandwidth(testScheduler.timeSource)
            bandwidth.setGlobal(90 * kib)
            val five = Counter()
            val nine = Counter()
            val jobs = listOf(backlog(bandwidth.register(a, null, 5), five), backlog(bandwidth.register(b, null, 9), nine))
            val (r5, r9) = rates(five, nine)
            (r9 / r5) near 1.0
            jobs.forEach { it.cancel() }
        }

    @Test
    fun anIdleDownloadsShareGoesToTheOthers() =
        runTest {
            val bandwidth = Bandwidth(testScheduler.timeSource)
            bandwidth.setGlobal(90 * kib)
            bandwidth.register(b, null, 3) // registered, never acquires
            val busy = Counter()
            val job = backlog(bandwidth.register(a, null, 0), busy)
            rates(busy).single() shouldBeGreaterThanOrEqual 0.9 * 90 * kib
            job.cancel()
        }

    @Test
    fun setPriorityChangesTheShareLive() =
        runTest {
            val bandwidth = Bandwidth(testScheduler.timeSource)
            bandwidth.setGlobal(100 * kib)
            val first = Counter()
            val second = Counter()
            val jobs = listOf(backlog(bandwidth.register(a, null, 0), first), backlog(bandwidth.register(b, null, 0), second))
            val (r1, r2) = rates(first, second, seconds = 30)
            (r2 / r1) near 1.0
            bandwidth.setPriority(b, 2)
            val (s1, s2) = rates(first, second)
            (s2 / s1) near 4.0
            jobs.forEach { it.cancel() }
        }

    @Test
    fun clearingTheGlobalLimitReleasesWaiters() =
        runTest {
            val bandwidth = Bandwidth(testScheduler.timeSource)
            bandwidth.setGlobal(kib)
            val first = bandwidth.register(a, null, 0)
            val second = bandwidth.register(b, null, 0)
            first.acquire(64 * 1024) // the global bucket is in debt for a minute
            val waiting = listOf(async { first.acquire(1024) }, async { second.acquire(1024) })
            advanceTimeBy(1.seconds)
            runCurrent()
            bandwidth.setGlobal(null)
            runCurrent()
            advanceTimeBy(1.seconds)
            waiting.all { it.isCompleted } shouldBe true
        }

    @Test
    fun unregisterFreesTheShare() =
        runTest {
            val bandwidth = Bandwidth(testScheduler.timeSource)
            bandwidth.setGlobal(90 * kib)
            val stays = Counter()
            val leaves = Counter()
            val keep = backlog(bandwidth.register(a, null, 0), stays)
            val drop = backlog(bandwidth.register(b, null, 0), leaves)
            rates(stays, leaves, seconds = 20)
            drop.cancel()
            bandwidth.unregister(b)
            rates(stays).single() near 90.0 * kib
            keep.cancel()
        }

    @Test
    fun noLimitsMeansNoWaiting() =
        runTest {
            val limiter = Bandwidth(testScheduler.timeSource).register(a, null, 0)
            repeat(100) { limiter.acquire(1 shl 20) }
            currentTime shouldBe 0L
        }

    @Test
    fun aCancelledWaiterDoesntStallTheQueue() =
        runTest {
            val bandwidth = Bandwidth(testScheduler.timeSource)
            bandwidth.setGlobal(kib)
            val first = bandwidth.register(a, null, 0)
            val second = bandwidth.register(b, null, 0)
            first.acquire(4 * 1024) // a few seconds of debt
            val cancelled = launch { first.acquire(1024) }
            runCurrent()
            val served = async { second.acquire(1024).let { currentTime } }
            runCurrent()
            cancelled.cancel()
            advanceTimeBy(10.seconds)
            served.isCompleted shouldBe true
        }

    @Test
    fun aGlobalLimitSetLateApplies() =
        runTest {
            val bandwidth = Bandwidth(testScheduler.timeSource)
            val first = Counter()
            val second = Counter()
            val jobs =
                listOf(
                    backlog(bandwidth.register(a, 40 * kib, 0), first),
                    backlog(bandwidth.register(b, 40 * kib, 0), second),
                )
            advanceTimeBy(3.seconds)
            bandwidth.setGlobal(20 * kib)
            val (r1, r2) = rates(first, second)
            (r1 + r2) near 20.0 * kib
            jobs.forEach { it.cancel() }
        }

    @Test
    fun shareIsTheOwnLimitWhenOnlyThatIsSet() =
        runTest {
            val bandwidth = Bandwidth(testScheduler.timeSource)
            bandwidth.register(a, 500, 0)

            bandwidth.shareOf(a) shouldBe 500L
        }

    @Test
    fun shareIsTheWeightedSliceOfTheGlobalLimit() =
        runTest {
            val bandwidth = Bandwidth(testScheduler.timeSource)
            bandwidth.setGlobal(3000)
            bandwidth.register(a, null, 0)
            bandwidth.register(b, null, 1)

            bandwidth.shareOf(a) shouldBe 1000L
            bandwidth.shareOf(b) shouldBe 2000L
        }

    @Test
    fun shareIsTheSmallerOfTheTwo() =
        runTest {
            val bandwidth = Bandwidth(testScheduler.timeSource)
            bandwidth.setGlobal(3000)
            bandwidth.register(a, 500, 0)
            bandwidth.shareOf(a) shouldBe 500L

            bandwidth.register(a, 5000, 0)
            bandwidth.shareOf(a) shouldBe 3000L
        }

    @Test
    fun noLimitsMeansNoShare() =
        runTest {
            val bandwidth = Bandwidth(testScheduler.timeSource)
            bandwidth.register(a, null, 0)

            bandwidth.shareOf(a) shouldBe null
        }

    @Test
    fun aLimitRemovedAgainMeansNoShare() =
        runTest {
            val bandwidth = Bandwidth(testScheduler.timeSource)
            bandwidth.register(a, 500, 0)
            bandwidth.setLimit(a, null)
            bandwidth.shareOf(a) shouldBe null

            bandwidth.setGlobal(2000)
            bandwidth.shareOf(a) shouldBe 2000L
            bandwidth.setGlobal(null)
            bandwidth.shareOf(a) shouldBe null
        }

    @Test
    fun anUnregisteredJobHasNoShare() =
        runTest {
            val bandwidth = Bandwidth(testScheduler.timeSource)
            bandwidth.shareOf(a) shouldBe null

            bandwidth.register(a, 500, 0)
            bandwidth.unregister(a)
            bandwidth.shareOf(a) shouldBe null
        }
}
