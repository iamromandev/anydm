package dev.anydm.engine

import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.DownloadStatus.COMPLETED
import dev.anydm.engine.core.DownloadStatus.PAUSED
import dev.anydm.engine.protocol.FakeProtocol
import dev.anydm.engine.store.NewDownload
import dev.anydm.engine.store.StoreTestBase
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** What the Engine (#555) must do, on a new database from [newDriver] for each test. */
abstract class EngineContract : StoreTestBase() {
    // advanceUntilIdle doesn't run coroutines outside the test; move virtual time on instead.
    internal fun TestScope.settle() {
        advanceTimeBy(10.minutes)
        runCurrent()
    }

    internal suspend fun TestScope.started(make: (TestScope) -> EngineHarness = { EngineHarness(it, newStore()) }): EngineHarness =
        make(this).also { it.engine.start() }

    @Test
    fun startPublishesTheStoredJobs() =
        runTest {
            val store = newStore()
            val a = store.downloads.add(NewDownload(DownloadKind.DIRECT))
            val b = store.downloads.add(NewDownload(DownloadKind.DIRECT))
            store.settings.update { it.copy(globalPause = true) }
            val h = EngineHarness(this, store)

            h.engine.start()
            runCurrent()

            h.engine.state.value shouldBe EngineState.Running
            h.engine.downloads.value
                .map { it.download.id } shouldBe listOf(a.id, b.id)
            h.engine.downloads.value
                .map { it.download.status } shouldBe listOf(PAUSED, PAUSED)
            h.engine.stop()
        }

    @Test
    fun commandsGoThroughTheEngine() =
        runTest {
            val h = started()
            val a = h.addJob()
            h.engine.pause(a)
            runCurrent()
            h.status(a) shouldBe PAUSED

            h.engine.resume(a)
            settle()

            h.status(a) shouldBe COMPLETED
            h.engine.stop()
        }

    @Test
    fun aStopThatTakesTooLongReportsTheJob() =
        runTest {
            val h = started { EngineHarness(it, newStore(), FakeProtocol(stopDelay = 30.seconds), stopTimeout = 1.seconds) }
            val events = mutableListOf<EngineEvent>()
            backgroundScope.launch { h.engine.events.collect { events += it } }
            val a = h.addJob()
            advanceTimeBy(1.seconds)

            h.engine.stop()
            runCurrent()

            events shouldContain EngineEvent.StopTimedOut(listOf(a))
            h.engine.state.value shouldBe EngineState.Stopped
        }

    @Test
    fun aStoreFailureFailsTheEngine() =
        runTest {
            val h = started()
            val events = mutableListOf<EngineEvent>()
            backgroundScope.launch { h.engine.events.collect { events += it } }
            runCurrent()
            h.store.close()

            shouldThrow<Exception> { h.addJob() }
            runCurrent()

            h.engine.state.value
                .shouldBeInstanceOf<EngineState.Failed>()
            events.filterIsInstance<EngineEvent.EngineFailed>().size shouldBe 1
            shouldThrow<IllegalStateException> { h.addJob() }
        }

    @Test
    fun startTwiceThrows() =
        runTest {
            val h = started()

            shouldThrow<IllegalStateException> { h.engine.start() }
            h.engine.stop()
        }

    @Test
    fun stopTwiceIsANoOp() =
        runTest {
            val h = started()
            h.engine.stop()

            h.engine.stop()

            h.engine.state.value shouldBe EngineState.Stopped
            shouldThrow<IllegalStateException> { h.addJob() }
        }

    @Test
    fun stopAnswersACommandWaitingOnASlot() =
        runTest {
            val h = started { EngineHarness(it, newStore(), FakeProtocol(stopDelay = 30.seconds), stopTimeout = 1.seconds) }
            val a = h.addJob()
            advanceTimeBy(1.seconds)
            val paused = async { runCatching { h.engine.pause(a) } }
            runCurrent()

            h.engine.stop()
            runCurrent()

            paused.isCompleted shouldBe true
            paused.await().exceptionOrNull().shouldBeInstanceOf<IllegalStateException>()
        }

    @Test
    fun stopBeforeStartClosesTheStore() =
        runTest {
            val h = EngineHarness(this, newStore())
            val added = async { runCatching { h.addJob() } }
            runCurrent()

            h.engine.stop()
            runCurrent()

            added.await().exceptionOrNull().shouldBeInstanceOf<IllegalStateException>()
            h.engine.state.value shouldBe EngineState.Stopped
        }
}
