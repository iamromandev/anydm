package dev.anydm.engine.http

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class HostSlotsTest {
    private val a = "https://a:443"
    private val b = "https://b:443"
    private val first = Any()
    private val second = Any()

    @Test
    fun theCapHoldsAcrossOwners() =
        runTest {
            val slots = HostSlots { 2 }
            slots.acquire(a, first)
            slots.acquire(a, first)
            var got = false
            launch {
                slots.acquire(a, second)
                got = true
            }
            runCurrent()
            got shouldBe false
            slots.release(a, first)
            runCurrent()
            got shouldBe true
            slots.held(a) shouldBe 2
        }

    @Test
    fun tryAcquireNeverWaits() =
        runTest {
            val slots = HostSlots { 1 }
            slots.tryAcquire(a, first) shouldBe true
            slots.tryAcquire(a, first) shouldBe false
        }

    @Test
    fun aLowerCapAppliesToTheNextAcquire() =
        runTest {
            var cap = 4
            val slots = HostSlots { cap }
            repeat(3) { slots.acquire(a, first) }
            cap = 2
            slots.tryAcquire(a, first) shouldBe false
            slots.release(a, first)
            slots.tryAcquire(a, first) shouldBe false
            slots.release(a, first)
            slots.tryAcquire(a, first) shouldBe true
        }

    @Test
    fun aWaitingOwnerBlocksGrowth() =
        runTest {
            val slots = HostSlots { 4 }
            repeat(4) { slots.acquire(a, first) }
            var got = false
            launch {
                slots.acquire(a, second)
                got = true
            }
            runCurrent()
            slots.shouldYield(a, first) shouldBe true
            slots.shouldYield(a, second) shouldBe false
            slots.release(a, first)
            runCurrent()
            got shouldBe true
            slots.tryAcquire(a, first) shouldBe false
            slots.shouldYield(a, first) shouldBe false
        }

    @Test
    fun aCancelledWaiterStopsWaiting() =
        runTest {
            val slots = HostSlots { 2 }
            slots.acquire(a, first)
            slots.acquire(a, first)
            val waiter = launch { slots.acquire(a, second) }
            runCurrent()
            slots.shouldYield(a, first) shouldBe true
            waiter.cancel()
            runCurrent()
            slots.shouldYield(a, first) shouldBe false
            slots.release(a, first)
            slots.release(a, first)
            slots.held(a) shouldBe 0
        }

    // Final review: a raised cap left a queued download waiting until another slot came back.
    @Test
    fun raisingTheCapServesAWaiter() =
        runTest {
            var cap = 1
            val slots = HostSlots { cap }
            slots.acquire(a, first)
            var got = false
            launch {
                slots.acquire(a, second)
                got = true
            }
            runCurrent()
            cap = 4
            slots.tryAcquire(a, first) shouldBe true
            runCurrent()
            got shouldBe true
            slots.held(a) shouldBe 3
        }

    @Test
    fun originsAreSeparate() =
        runTest {
            val slots = HostSlots { 1 }
            slots.acquire(a, first)
            slots.tryAcquire(b, first) shouldBe true
        }

    // Two workers of one owner checking shouldYield at once could both leave; tryYield decides and releases in one step.
    @Test
    fun tryYieldReleasesOnlyWhenSomeoneWaits() =
        runTest {
            val slots = HostSlots { 2 }
            slots.acquire(a, first)
            slots.acquire(a, first)
            slots.tryYield(a, first) shouldBe false
            var got = false
            launch {
                slots.acquire(a, second)
                got = true
            }
            runCurrent()
            slots.tryYield(a, first) shouldBe true
            runCurrent()
            got shouldBe true
            slots.tryYield(a, first) shouldBe false
            slots.held(a) shouldBe 2
        }
}
