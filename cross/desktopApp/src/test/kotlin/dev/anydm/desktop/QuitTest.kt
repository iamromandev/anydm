package dev.anydm.desktop

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal class QuitTest {
    @Test
    fun `a second quit waits for the first stop and does not run another`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            var runs = 0
            val once =
                Once {
                    runs++
                    gate.await()
                }
            val first = async { once.run() }
            advanceUntilIdle()
            val second = async { once.run() }
            advanceUntilIdle()
            assertFalse(second.isCompleted, "the second quit must wait for the stop in progress")
            gate.complete(Unit)
            first.await()
            second.await()
            assertEquals(1, runs)
        }

    @Test
    fun `a failed stop is seen by every quit`() =
        runTest {
            val once = Once { error("disk full") }
            assertFailsWith<IllegalStateException> { once.run() }
            assertFailsWith<IllegalStateException> { once.run() }
        }

    @Test
    fun `a start that throws is reported and does not escape`() =
        runTest {
            val source = FakeSource(startError = IllegalStateException("no store"))
            val problems = mutableListOf<Throwable>()
            source.startGuarded { problems += it }
            assertEquals(listOf("no store"), problems.map { it.message })
        }

    @Test
    fun `a start that works reports nothing`() =
        runTest {
            val source = FakeSource()
            val problems = mutableListOf<Throwable>()
            source.startGuarded { problems += it }
            assertTrue(problems.isEmpty())
            assertEquals(1, source.started)
        }
}
