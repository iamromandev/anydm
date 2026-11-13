package dev.anydm.engine.http

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConnectionPermitsTest {
    @Test
    fun oneWaitingDownloadMakesOneExtraConnectionYieldNotAll() =
        runBlocking {
            val p = ConnectionPermits(4)
            p.acquireFirst()
            repeat(3) { assertTrue(p.tryAcquireExtra()) }
            val waiter = async(start = CoroutineStart.UNDISPATCHED) { p.acquireFirst() }
            assertFalse(waiter.isCompleted)
            assertTrue(p.shouldYield(), "the first extra connection gives way")
            assertFalse(p.shouldYield(), "one waiter, one permit on its way: no second extra connection yields")
            p.release(yielded = true)
            waiter.await()
            assertFalse(p.shouldYield(), "the waiter has its permit: nobody else yields")
        }

    @Test
    fun twoWaitersMakeTwoExtraConnectionsYield() =
        runBlocking {
            val p = ConnectionPermits(3)
            p.acquireFirst()
            repeat(2) { assertTrue(p.tryAcquireExtra()) }
            val waiters = List(2) { async(start = CoroutineStart.UNDISPATCHED) { p.acquireFirst() } }
            assertTrue(p.shouldYield())
            assertTrue(p.shouldYield())
            assertFalse(p.shouldYield())
            repeat(2) { p.release(yielded = true) }
            waiters.forEach { it.await() }
        }

    @Test
    fun noExtraConnectionTakesAPermitWhileADownloadWaitsForItsFirst() =
        runBlocking {
            val p = ConnectionPermits(1)
            p.acquireFirst()
            val waiter = async(start = CoroutineStart.UNDISPATCHED) { p.acquireFirst() }
            p.release() // goes straight to the waiter
            assertFalse(p.tryAcquireExtra(), "the permit that was released is the waiter's")
            waiter.await()
        }

    @Test
    fun aWaiterThatIsCancelledGivesUpItsPlaceAndNoPermitIsLost() =
        runBlocking {
            val p = ConnectionPermits(1)
            p.acquireFirst()
            val waiter = async(start = CoroutineStart.UNDISPATCHED) { p.acquireFirst() }
            waiter.cancelAndJoin()
            assertFalse(p.shouldYield(), "nobody waits any more")
            p.release()
            assertTrue(p.tryAcquireExtra(), "the permit is free again")
            delay(1)
        }

    @Test
    fun aPermitHandedToAWaiterThatIsCancelledBeforeItResumesIsNotLost() =
        runBlocking {
            val p = ConnectionPermits(1)
            p.acquireFirst()
            val waiter = async(start = CoroutineStart.UNDISPATCHED) { p.acquireFirst() }
            p.release() // goes straight to the waiter, which is resumed only when this thread gets to it
            assertFalse(waiter.isCompleted, "the waiter has not run yet")
            waiter.cancelAndJoin()
            assertTrue(waiter.isCancelled, "the waiter was cancelled while its permit was on its way")
            assertFalse(p.shouldYield(), "nobody waits any more")
            assertTrue(p.tryAcquireExtra(), "the permit that was handed over came back")
        }
}
