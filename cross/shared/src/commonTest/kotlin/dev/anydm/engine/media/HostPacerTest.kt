package dev.anydm.engine.media

import io.kotest.matchers.longs.shouldBeBetween
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class HostPacerTest {
    private fun TestScope.pacer() = HostPacer(testScheduler.timeSource, Random(7))

    @Test
    fun theSecondStartWaitsTheGap() =
        runTest {
            val pacer = pacer()

            pacer.pace("a", 4.seconds)
            currentTime shouldBe 0L
            pacer.pace("a", 4.seconds)

            currentTime.shouldBeBetween(3000L, 5000L)
        }

    @Test
    fun differentHostsDoNotWaitForEachOther() =
        runTest {
            val pacer = pacer()

            pacer.pace("a", 4.seconds)
            pacer.pace("b", 4.seconds)

            currentTime shouldBe 0L
        }

    @Test
    fun aZeroGapNeverWaits() =
        runTest {
            val pacer = pacer()

            repeat(3) { pacer.pace("a", Duration.ZERO) }

            currentTime shouldBe 0L
        }

    @Test
    fun aLongIdleTimeNeedsNoWait() =
        runTest {
            val pacer = pacer()
            pacer.pace("a", 2.seconds)
            advanceTimeBy(10.seconds)
            val before = currentTime

            pacer.pace("a", 2.seconds)

            currentTime shouldBe before
        }

    @Test
    fun jitterStaysWithinAQuarter() =
        runTest {
            val pacer = pacer()
            var last = -1L

            repeat(20) {
                pacer.pace("a", 4.seconds)
                if (last >= 0) (currentTime - last).shouldBeBetween(3000L, 5000L)
                last = currentTime
            }
        }

    @Test
    fun concurrentStartsAreSpacedOutOneAfterAnother() =
        runTest {
            val pacer = pacer()
            val starts = mutableListOf<Long>()

            repeat(3) { launch { pacer.pace("a", 2.seconds).also { starts += currentTime } } }
            testScheduler.advanceUntilIdle()

            starts.sorted().let { (a, b, c) ->
                a shouldBe 0L
                (b - a).shouldBeBetween(1500L, 2500L)
                (c - b).shouldBeBetween(1500L, 2500L)
            }
        }
}
