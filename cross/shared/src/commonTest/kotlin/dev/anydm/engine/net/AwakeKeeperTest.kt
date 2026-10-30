package dev.anydm.engine.net

import dev.anydm.engine.platform.FakeKeepAwake
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** When the keep-awake hold is taken and let go (NET-11), over the fake. */
class AwakeKeeperTest {
    private fun TestScope.keeper(keep: FakeKeepAwake) = AwakeKeeper(keep, backgroundScope)

    @Test
    fun wantedAcquiresAtOnceAndOnEveryEvaluation() =
        runTest {
            val keep = FakeKeepAwake()
            val keeper = keeper(keep)
            keeper.setEnabled(true)

            keeper.update(true)
            keep.acquires shouldBe 1
            keep.held shouldBe true

            keeper.update(true)
            keep.acquires shouldBe 2
            keep.releases shouldBe 0
        }

    @Test
    fun notWantedReleasesAfterFiveSeconds() =
        runTest {
            val keep = FakeKeepAwake()
            val keeper = keeper(keep)
            keeper.setEnabled(true)
            keeper.update(true)

            keeper.update(false)
            advanceTimeBy(4999.milliseconds)
            runCurrent()
            keep.held shouldBe true
            keep.releases shouldBe 0

            advanceTimeBy(1.milliseconds)
            runCurrent()
            keep.releases shouldBe 1
            keep.held shouldBe false
        }

    @Test
    fun wantedAgainWithinTheGraceKeepsTheHold() =
        runTest {
            val keep = FakeKeepAwake()
            val keeper = keeper(keep)
            keeper.setEnabled(true)
            keeper.update(true)
            keeper.update(false)
            advanceTimeBy(3.seconds)

            keeper.update(true)
            advanceTimeBy(1.minutes)
            runCurrent()

            keep.releases shouldBe 0
            keep.held shouldBe true
            keep.acquires shouldBe 2
        }

    @Test
    fun theGraceStartsAgainAfterAWantedMoment() =
        runTest {
            val keep = FakeKeepAwake()
            val keeper = keeper(keep)
            keeper.setEnabled(true)
            keeper.update(true)
            keeper.update(false)
            advanceTimeBy(3.seconds)
            keeper.update(true)
            advanceTimeBy(1.seconds)
            keeper.update(false)

            // 5 s after the first "not wanted" is only 1 s after the last one.
            advanceTimeBy(1.seconds)
            runCurrent()
            keep.releases shouldBe 0

            advanceTimeBy(4.seconds)
            runCurrent()
            keep.releases shouldBe 1
        }

    @Test
    fun disabledReleasesAtOnceAndNeverAcquires() =
        runTest {
            val off = FakeKeepAwake()
            val never = keeper(off)
            never.setEnabled(false)
            never.update(true)
            advanceTimeBy(1.minutes)
            runCurrent()
            off.acquires shouldBe 0
            off.releases shouldBe 0

            val keep = FakeKeepAwake()
            val keeper = keeper(keep)
            keeper.setEnabled(true)
            keeper.update(true)

            keeper.setEnabled(false)
            runCurrent()
            keep.releases shouldBe 1
            keep.held shouldBe false

            keeper.update(true)
            keep.acquires shouldBe 1

            keeper.setEnabled(true)
            keep.acquires shouldBe 2
            keep.held shouldBe true
        }

    @Test
    fun neverHeldReleasesNothing() =
        runTest {
            val keep = FakeKeepAwake()
            val keeper = keeper(keep)
            keeper.setEnabled(true)

            keeper.update(false)
            advanceTimeBy(1.minutes)
            runCurrent()

            keep.acquires shouldBe 0
            keep.releases shouldBe 0
        }

    @Test
    fun closeReleasesAndLaterUpdatesDoNothing() =
        runTest {
            val keep = FakeKeepAwake()
            val keeper = keeper(keep)
            keeper.setEnabled(true)
            keeper.update(true)
            keeper.update(false)

            keeper.close()
            keep.releases shouldBe 1

            advanceTimeBy(1.minutes)
            runCurrent()
            keep.releases shouldBe 1

            keeper.update(true)
            keeper.setEnabled(true)
            keeper.close()
            keep.acquires shouldBe 1
            keep.releases shouldBe 1
        }
}
