package dev.anydm.desktop

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal class EngineLifecycleTest {
    @Test
    fun `stop waits for a start that is still running`() =
        runTest {
            val source = FakeSource().apply { startGate = CompletableDeferred() }
            val lifecycle = EngineLifecycle(source, grace = 5.seconds) {}
            val starting = async { lifecycle.start() }
            advanceUntilIdle()
            val stopping = async { lifecycle.stopOnce() }
            advanceTimeBy(1.seconds)
            assertEquals(0, source.stopped, "the stop must not overtake the start")
            source.startGate!!.complete(Unit)
            starting.await()
            stopping.await()
            assertEquals(1, source.stopped)
        }

    @Test
    fun `stop does not wait for ever for a start that hangs`() =
        runTest {
            val source = FakeSource().apply { startGate = CompletableDeferred() }
            val lifecycle = EngineLifecycle(source, grace = 5.seconds) {}
            val starting = async { lifecycle.start() }
            advanceUntilIdle()
            val stopping = async { lifecycle.stopOnce() }
            advanceTimeBy(4.seconds)
            assertFalse(stopping.isCompleted)
            advanceTimeBy(2.seconds)
            stopping.await()
            assertEquals(1, source.stopped)
            starting.cancel()
        }

    @Test
    fun `stop still runs once when the start throws`() =
        runTest {
            val source = FakeSource(startError = IllegalStateException("no store"))
            val problems = mutableListOf<Throwable>()
            val lifecycle = EngineLifecycle(source, grace = 5.seconds) { problems += it }
            lifecycle.start()
            lifecycle.stopOnce()
            lifecycle.stopOnce()
            assertEquals(listOf("no store"), problems.map { it.message })
            assertEquals(1, source.stopped)
        }

    @Test
    fun `stop with no start at all still stops after the grace`() =
        runTest {
            val source = FakeSource()
            val lifecycle = EngineLifecycle(source, grace = 5.seconds) {}
            lifecycle.stopOnce()
            assertTrue(source.stopped == 1)
        }
}
