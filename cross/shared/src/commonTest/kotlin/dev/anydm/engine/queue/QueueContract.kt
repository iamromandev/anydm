package dev.anydm.engine.queue

import dev.anydm.engine.core.Download
import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.DownloadStatus.CANCELLED
import dev.anydm.engine.core.DownloadStatus.COMPLETED
import dev.anydm.engine.core.DownloadStatus.DOWNLOADING
import dev.anydm.engine.core.DownloadStatus.FAILED
import dev.anydm.engine.core.DownloadStatus.PAUSED
import dev.anydm.engine.core.DownloadStatus.PENDING
import dev.anydm.engine.core.DownloadStatus.QUEUED
import dev.anydm.engine.core.FinishHook
import dev.anydm.engine.core.PauseReason
import dev.anydm.engine.core.TransitionResult
import dev.anydm.engine.core.stepRoot
import dev.anydm.engine.platform.virtualEpoch
import dev.anydm.engine.protocol.FakeFailure
import dev.anydm.engine.protocol.FakeProtocol
import dev.anydm.engine.protocol.StepResult
import dev.anydm.engine.store.NewDownload
import dev.anydm.engine.store.StoreTestBase
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** What the queue (#552) must do, on a new database from [newDriver] for each test. */
abstract class QueueContract : StoreTestBase() {
    // advanceUntilIdle doesn't run backgroundScope, where the queue lives: move virtual time on instead.
    private fun TestScope.settle() {
        advanceTimeBy(10.minutes)
        runCurrent()
    }

    private suspend fun TestScope.started(
        limit: Int? = null,
        harness: (TestScope) -> QueueHarness = { QueueHarness(it, newStore()) },
    ): QueueHarness =
        harness(this).also { h ->
            if (limit != null) h.store.settings.update { it.copy(concurrencyLimit = limit) }
            h.queue.start()
        }

    @Test
    fun runsAtMostTheLimitAtOnce() =
        runTest {
            val h = started()
            val ids = List(5) { h.addJob() }
            runCurrent()

            h.queue
                .inspect()
                .running.size shouldBe 3

            settle()
            ids.map { h.status(it) } shouldBe List(5) { COMPLETED }
        }

    @Test
    fun startsByPriorityThenQueuedTime() =
        runTest {
            val h = started(limit = 1)
            val blocker = h.addJob(priority = 0) // takes the only slot while the others are added
            val low = h.addJob(priority = 0)
            advanceTimeBy(1.seconds)
            val first = h.addJob(priority = 5)
            advanceTimeBy(1.seconds)
            val second = h.addJob(priority = 5)

            settle()

            h.finished shouldBe listOf(blocker, first, second, low)
        }

    @Test
    fun aFailedJobFreesItsSlot() =
        runTest {
            val failing = FakeProtocol(failure = FakeFailure.AtProbe(StepResult.Failed(null, "nope")))
            val working = FakeProtocol()
            val h =
                started(limit = 1) {
                    QueueHarness(it, newStore(), working, mapOf(DownloadKind.SITE to failing, DownloadKind.DIRECT to working))
                }
            val bad = h.addJob(priority = 1, kind = DownloadKind.SITE)
            val good = h.addJob()

            settle()

            h.statusesShouldBe(bad to FAILED, good to COMPLETED)
        }

    @Test
    fun aThrowingProtocolFailsOnlyItsJob() =
        runTest {
            val throwing = FakeProtocol(failure = FakeFailure.ThrowAtProbe(IllegalStateException("bug")))
            val working = FakeProtocol()
            val h = started { QueueHarness(it, newStore(), working, mapOf(DownloadKind.SITE to throwing, DownloadKind.DIRECT to working)) }
            val bad = h.addJob(kind = DownloadKind.SITE)
            val good = h.addJob()

            settle()

            h.statusesShouldBe(bad to FAILED, good to COMPLETED)
            h.queue.inspect().running shouldBe emptySet()
        }

    @Test
    fun aThrowAfterTheJobFinishedCountsAsFinished() =
        runTest {
            val throwingHook =
                object : FinishHook {
                    override suspend fun finished(
                        download: Download,
                        outputs: List<Path>,
                    ): Unit = throw IllegalStateException("hook bug")
                }
            val h = started(limit = 1) { QueueHarness(it, newStore(), finish = throwingHook) }
            val a = h.addJob()
            val b = h.addJob()

            settle()

            h.statusesShouldBe(a to COMPLETED, b to COMPLETED)
        }

    @Test
    fun aStoreFailureStopsTheQueue() =
        runTest {
            val failures = mutableListOf<Throwable>()
            val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job() + CoroutineExceptionHandler { _, e -> failures += e })
            val h = started { QueueHarness(it, newStore(), scope = scope) }
            h.store.close()

            val error = shouldThrow<Exception> { h.addJob() }
            runCurrent()

            failures shouldBe listOf(error)
            shouldThrow<IllegalStateException> { h.addJob() }
        }

    @Test
    fun commandsBeforeStartWaitForIt() =
        runTest {
            val h = QueueHarness(this, newStore())
            val added = async { h.queue.add(NewDownload(DownloadKind.DIRECT, mirrors = listOf("https://example.com/f"))) }
            runCurrent()
            added.isCompleted shouldBe false

            h.queue.start()

            added.await().shouldBeInstanceOf<CommandResult.Done>()
        }

    @Test
    fun aJobWithoutAMirrorFails() =
        runTest {
            val h = started()
            val added =
                h.queue
                    .add(NewDownload(DownloadKind.DIRECT))
                    .shouldBeInstanceOf<CommandResult.Done>()
                    .download!!

            settle()

            h.status(added.id) shouldBe FAILED
        }

    @Test
    fun aJobStartsWaitingWhenAdded() =
        runTest {
            val h = started()
            advanceTimeBy(5.seconds)

            val added =
                h.queue
                    .add(NewDownload(DownloadKind.DIRECT))
                    .shouldBeInstanceOf<CommandResult.Done>()
                    .download!!

            added.queuedAt shouldBe virtualEpoch + 5.seconds
            added.status shouldBe PENDING
        }

    @Test
    fun aRunningJobMovesThroughDownloading() =
        runTest {
            val h = started()
            val id = h.addJob()
            advanceTimeBy(1.seconds)

            h.status(id) shouldBe DOWNLOADING
            h.job(id)!!.saveFolder shouldBe
                dev.anydm.engine.core.stepRoot
                    .toString()
        }

    // Stopping a running job and the per-job commands

    @Test
    fun aPausedJobFreesItsSlot() =
        runTest {
            val h = started(limit = 1)
            val a = h.addJob()
            val b = h.addJob()
            advanceTimeBy(1.seconds)

            val paused =
                h.queue
                    .pause(a)
                    .shouldBeInstanceOf<CommandResult.Done>()
                    .download!!
            paused.status shouldBe PAUSED
            paused.pauseReasons shouldBe setOf(PauseReason.USER)
            advanceTimeBy(1.seconds)

            h.statusesShouldBe(a to PAUSED, b to DOWNLOADING)
        }

    @Test
    fun pausingAWaitingJobNeedsNoSlot() =
        runTest {
            val h = started(limit = 1)
            val a = h.addJob()
            val b = h.addJob()
            advanceTimeBy(1.seconds)

            h.queue
                .pause(b)
                .shouldBeInstanceOf<CommandResult.Done>()
                .download!!
                .status shouldBe PAUSED
            settle()

            h.statusesShouldBe(a to COMPLETED, b to PAUSED)
        }

    @Test
    fun resumeQueuesItAtTheBackOfItsPriority() =
        runTest {
            val h = started(limit = 1)
            val a = h.addJob()
            val b = h.addJob()
            val c = h.addJob()
            h.queue.pause(b)
            advanceTimeBy(1.seconds)

            val resumed =
                h.queue
                    .resume(b)
                    .shouldBeInstanceOf<CommandResult.Done>()
                    .download!!
            resumed.status shouldBe QUEUED
            resumed.pauseReasons shouldBe emptySet()
            resumed.queuedAt shouldBe virtualEpoch + 1.seconds
            settle()

            h.finished shouldBe listOf(a, c, b)
        }

    @Test
    fun resumeOfAJobThatIsNotPausedIsRejected() =
        runTest {
            val h = started()
            val a = h.addJob()
            advanceTimeBy(1.seconds)

            h.queue.resume(a) shouldBe CommandResult.Rejected(TransitionResult.Rejected(DOWNLOADING, QUEUED))
        }

    @Test
    fun cancelStopsTheJobAndKeepsItsPartials() =
        runTest {
            val h = started()
            val a = h.addJob()
            advanceTimeBy(1.seconds)

            h.queue
                .cancel(a)
                .shouldBeInstanceOf<CommandResult.Done>()
                .download!!
                .status shouldBe CANCELLED
            settle()

            h.status(a) shouldBe CANCELLED
            h.fs.paths().map { it.name } shouldBe listOf("job-1.zip.${a.value}.anydm")
            h.queue.inspect().running shouldBe emptySet()
        }

    @Test
    fun removeKeepDeletesTheRowAndLeavesTheFiles() =
        runTest {
            val h = started()
            val a = h.addJob()
            advanceTimeBy(1.seconds)

            h.queue.remove(a, RemoveFiles.KEEP) shouldBe CommandResult.Done(null)

            h.job(a) shouldBe null
            h.fs.paths().size shouldBe 1
            h.queue.inspect().running shouldBe emptySet()
        }

    @Test
    fun removeTrashWithoutATrashKeepsTheJob() =
        runTest {
            val h = started()
            val a = h.addJob()
            advanceTimeBy(1.seconds)

            h.queue.remove(a, RemoveFiles.TRASH) shouldBe CommandResult.TrashUnavailable

            h.job(a)!!.status shouldBe PAUSED
            h.job(a)!!.pauseReasons shouldBe setOf(PauseReason.USER)
            h.fs.paths().size shouldBe 1
        }

    @Test
    fun removeTrashMovesFilesToTheTrash() =
        runTest {
            val h = started()
            h.fs.trashAvailable = true
            val a = h.addJob()
            advanceTimeBy(1.seconds)

            h.queue.remove(a, RemoveFiles.TRASH) shouldBe CommandResult.Done(null)

            h.job(a) shouldBe null
            h.fs.paths() shouldBe emptySet()
            h.fs.trashed.map { it.name } shouldBe listOf("job-1.zip.${a.value}.anydm")
        }

    @Test
    fun removeTrashOfAFinishedJobTrashesItsOutput() =
        runTest {
            val h = started()
            h.fs.trashAvailable = true
            val a = h.addJob()
            settle()

            h.queue.remove(a, RemoveFiles.TRASH) shouldBe CommandResult.Done(null)

            h.fs.trashed.map { it.name } shouldBe listOf("job-1.zip")
        }

    @Test
    fun retryRequeuesAFailedJobWithAFreshCount() =
        runTest {
            val protocol = FakeProtocol(failure = FakeFailure.AtProbe(StepResult.Failed(null, "nope")))
            val h = started { QueueHarness(it, newStore(), protocol) }
            val a = h.addJob()
            settle()
            h.store.downloads.update(h.job(a)!!.copy(attemptCount = 2))
            protocol.failure = null

            val retried =
                h.queue
                    .retry(a)
                    .shouldBeInstanceOf<CommandResult.Done>()
                    .download!!
            retried.status shouldBe QUEUED
            retried.attemptCount shouldBe 0
            settle()

            h.status(a) shouldBe COMPLETED
        }

    @Test
    fun retryOfAJobThatDidntFailIsRejected() =
        runTest {
            val h = started()
            val a = h.addJob()
            settle()

            h.queue.retry(a) shouldBe CommandResult.Rejected(TransitionResult.Rejected(COMPLETED, QUEUED))
        }

    @Test
    fun reprioritizeNeverPreempts() =
        runTest {
            val h = started(limit = 1)
            val a = h.addJob()
            val c = h.addJob(priority = 5)
            val b = h.addJob()
            advanceTimeBy(1.seconds)

            h.queue
                .reprioritize(b, 9)
                .shouldBeInstanceOf<CommandResult.Done>()
                .download!!
                .priority shouldBe 9
            h.status(a) shouldBe DOWNLOADING
            settle()

            h.finished shouldBe listOf(a, b, c)
        }

    @Test
    fun aStoppingSlotStillCountsAgainstTheLimit() =
        runTest {
            val h = started(limit = 1) { QueueHarness(it, newStore(), FakeProtocol(stopDelay = 1.seconds)) }
            val a = h.addJob()
            val b = h.addJob()
            advanceTimeBy(1.seconds)

            val paused = async { h.queue.pause(a) }
            advanceTimeBy(500.milliseconds)
            h.queue.inspect().stopping shouldBe setOf(a)
            h.status(b) shouldBe PENDING

            advanceTimeBy(1.seconds)
            paused.isCompleted shouldBe true
            h.status(b) shouldBe DOWNLOADING
        }

    @Test
    fun aJobPausedBeforeItsSlotRanEndsPaused() =
        runTest {
            val h = started()
            val a = h.addJob()

            // The slot was launched but its coroutine hasn't run yet: cancelling it must still free the slot.
            h.queue
                .pause(a)
                .shouldBeInstanceOf<CommandResult.Done>()
                .download!!
                .status shouldBe PAUSED
            settle()

            h.status(a) shouldBe PAUSED
            h.queue.inspect().running shouldBe emptySet()
        }

    @Test
    fun aRunnerMoveKeepsFieldsTheQueueOwns() =
        runTest {
            val h = started()
            val a = h.addJob()
            advanceTimeBy(1.seconds)

            h.queue.reprioritize(a, 7)
            settle()

            h.job(a)!!.status shouldBe COMPLETED
            h.job(a)!!.priority shouldBe 7
        }

    @Test
    fun aJobThatFinishesWhileStoppingStaysFinished() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val waitingHook =
                object : FinishHook {
                    override suspend fun finished(
                        download: Download,
                        outputs: List<Path>,
                    ) = gate.await()
                }
            val h = started { QueueHarness(it, newStore(), finish = waitingHook) }
            val a = h.addJob()
            advanceTimeBy(10.seconds)
            h.status(a) shouldBe COMPLETED

            val paused = async { h.queue.pause(a) }
            runCurrent()
            gate.complete(Unit)
            runCurrent()

            paused.await() shouldBe CommandResult.Rejected(TransitionResult.Rejected(COMPLETED, PAUSED))
            h.status(a) shouldBe COMPLETED
            h.queue.inspect().running shouldBe emptySet()
        }

    @Test
    fun commandsOnAMissingJobReturnNotFound() =
        runTest {
            val h = started()
            val missing = DownloadId(999)

            h.queue.pause(missing) shouldBe CommandResult.NotFound
            h.queue.resume(missing) shouldBe CommandResult.NotFound
            h.queue.cancel(missing) shouldBe CommandResult.NotFound
            h.queue.remove(missing, RemoveFiles.KEEP) shouldBe CommandResult.NotFound
            h.queue.remove(missing, RemoveFiles.TRASH) shouldBe CommandResult.NotFound
            h.queue.retry(missing) shouldBe CommandResult.NotFound
            h.queue.reprioritize(missing, 1) shouldBe CommandResult.NotFound

            val a = h.addJob()
            h.queue.remove(a, RemoveFiles.KEEP) shouldBe CommandResult.Done(null)
            h.queue.remove(a, RemoveFiles.KEEP) shouldBe CommandResult.NotFound
        }
}
