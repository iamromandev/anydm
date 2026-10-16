package dev.anydm.engine.platform

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class VirtualClockTest {
    @Test
    fun startsAtItsOrigin() =
        runTest {
            val origin = Instant.parse("2026-01-01T00:00:00Z")
            virtualClock(origin).now() shouldBe origin
        }

    @Test
    fun movesWithDelayWithoutWaiting() =
        runTest {
            val clock = virtualClock()
            delay(15.seconds) // the checkpoint interval, FND-09
            clock.now() shouldBe virtualEpoch + 15.seconds
        }

    @Test
    fun agreesWithTimersInOtherCoroutines() =
        runTest {
            val clock = virtualClock()
            var firedAt: Instant? = null
            launch {
                delay(30.seconds)
                firedAt = clock.now()
            }
            testScheduler.advanceTimeBy(10.seconds)
            clock.now() shouldBe virtualEpoch + 10.seconds
            firedAt shouldBe null
            testScheduler.advanceUntilIdle()
            firedAt shouldBe virtualEpoch + 30.seconds
        }

    @Test
    fun aSavedDeadlineFiresOnTime() =
        runTest {
            // The retry pattern (#553): store an Instant, later wait until it.
            val clock = virtualClock()
            val nextAttemptAt = clock.now() + 90.seconds
            delay(nextAttemptAt - clock.now())
            clock.now() shouldBe nextAttemptAt
        }
}
