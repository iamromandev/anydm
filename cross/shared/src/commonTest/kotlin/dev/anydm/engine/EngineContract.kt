package dev.anydm.engine

import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.DownloadStatus.COMPLETED
import dev.anydm.engine.core.DownloadStatus.DOWNLOADING
import dev.anydm.engine.core.DownloadStatus.PAUSED
import dev.anydm.engine.protocol.FakeFile
import dev.anydm.engine.protocol.FakeProtocol
import dev.anydm.engine.queue.RemoveFiles
import dev.anydm.engine.store.NewDownload
import dev.anydm.engine.store.StoreTestBase
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
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

    // Progress, speed and events

    @Test
    fun progressIsPublishedAtMostEvery200Ms() =
        runTest {
            val h = started { EngineHarness(it, newStore(), FakeProtocol(bytesPerSecond = 64 * 1024)) }
            val seen = mutableListOf<Pair<Long, Long>>()
            val a = h.addJob()
            backgroundScope.launch {
                h.engine.downloads.collect { list ->
                    val job = list.find { it.download.id == a } ?: return@collect
                    if (job.download.status == DOWNLOADING && seen.lastOrNull()?.second != job.downloaded) {
                        seen += testScheduler.currentTime to job.downloaded
                    }
                }
            }
            settle()

            (seen.size >= 3) shouldBe true
            seen.zipWithNext().all { (x, y) -> y.first - x.first >= 200 } shouldBe true
            h.snapshot(a)!!.downloaded shouldBe 64 * 1024L
            h.engine.stop()
        }

    @Test
    fun speedIsSampledEverySecond() =
        runTest {
            val h = started { EngineHarness(it, newStore(), FakeProtocol(files = listOf(FakeFile("big.bin", 256L * 1024)))) }
            val a = h.addJob()
            advanceTimeBy(5_001.milliseconds)
            runCurrent()

            val snapshot = h.snapshot(a)!!
            snapshot.speedSamples.size shouldBe 5
            snapshot.speedSamples.all { it in 12_288L..20_480L } shouldBe true
            snapshot.speed shouldBe snapshot.speedSamples.last()
            h.engine.totalSpeed.value.samples shouldBe snapshot.speedSamples

            settle()
            h.snapshot(a)!!.speed shouldBe 0L
            h.snapshot(a)!!.speedSamples.isNotEmpty() shouldBe true
            h.engine.stop()
        }

    @Test
    fun eventsReachACollector() =
        runTest {
            val h = started()
            val events = mutableListOf<EngineEvent>()
            backgroundScope.launch { h.engine.events.collect { events += it } }
            runCurrent()
            val a = h.addJob()
            settle()

            events.first() shouldBe EngineEvent.Added(a)
            events.last().shouldBeInstanceOf<EngineEvent.Completed>().id shouldBe a
            events.filterIsInstance<EngineEvent.StatusChanged>().last().to shouldBe COMPLETED
            h.engine.stop()
        }

    @Test
    fun aSlowEventCollectorNeverBlocksTheQueue() =
        runTest {
            val h = started()
            backgroundScope.launch { h.engine.events.collect { awaitCancellation() } }
            runCurrent()
            val ids = List(5) { h.addJob() }
            settle()

            ids.map { h.status(it) } shouldBe List(5) { COMPLETED }
            h.engine.stop()
        }

    @Test
    fun lateProgressForARemovedJobIsIgnored() =
        runTest {
            val h = started()
            val a = h.addJob()
            advanceTimeBy(1.seconds)

            h.engine.remove(a, RemoveFiles.KEEP)
            settle()

            h.snapshot(a) shouldBe null
            h.engine.stop()
        }

    @Test
    fun aPausedJobShowsItsSavedBytesAndNoSpeed() =
        runTest {
            val h = started()
            val a = h.addJob()
            advanceTimeBy(2_100.milliseconds)

            h.engine.pause(a)
            runCurrent()

            val snapshot = h.snapshot(a)!!
            val saved =
                h.store.downloads
                    .files(a)
                    .sumOf { f ->
                        h.store.downloads
                            .segments(f.id)
                            .sumOf { it.written }
                    }
            snapshot.downloaded shouldBe saved
            (saved > 0) shouldBe true
            snapshot.speed shouldBe 0L
            h.engine.stop()
        }
}
