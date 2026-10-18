package dev.anydm.engine.queue

import dev.anydm.engine.core.Checksum
import dev.anydm.engine.core.ChecksumAlgorithm
import dev.anydm.engine.core.Download
import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.DownloadStatus.CANCELLED
import dev.anydm.engine.core.DownloadStatus.COMPLETED
import dev.anydm.engine.core.DownloadStatus.DOWNLOADING
import dev.anydm.engine.core.DownloadStatus.FAILED
import dev.anydm.engine.core.DownloadStatus.MUXING
import dev.anydm.engine.core.DownloadStatus.PAUSED
import dev.anydm.engine.core.DownloadStatus.PENDING
import dev.anydm.engine.core.DownloadStatus.QUEUED
import dev.anydm.engine.core.ErrorClass
import dev.anydm.engine.core.FinishHook
import dev.anydm.engine.core.Mirror
import dev.anydm.engine.core.PauseReason
import dev.anydm.engine.core.RequestContext
import dev.anydm.engine.core.Step
import dev.anydm.engine.core.StepFailure
import dev.anydm.engine.core.TransitionResult
import dev.anydm.engine.core.hasherFor
import dev.anydm.engine.core.stepRoot
import dev.anydm.engine.platform.FakeFileSystem
import dev.anydm.engine.platform.virtualEpoch
import dev.anydm.engine.protocol.FakeFailure
import dev.anydm.engine.protocol.FakeProtocol
import dev.anydm.engine.protocol.FinalizeResult
import dev.anydm.engine.protocol.Protocol
import dev.anydm.engine.protocol.RelinkRequest
import dev.anydm.engine.protocol.Relinked
import dev.anydm.engine.protocol.StepResult
import dev.anydm.engine.protocol.Transfer
import dev.anydm.engine.protocol.TransferIo
import dev.anydm.engine.protocol.fakeContent
import dev.anydm.engine.store.NewDownload
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldNotBeInstanceOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** What the queue (#552) must do, on a new database from [newDriver] for each test. */
abstract class QueueContract : QueueTestBase() {
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

    // Global pause and the limit

    @Test
    fun pauseAllPausesEveryActiveJobAndFreesEverySlot() =
        runTest {
            val h = started(limit = 2)
            val ids = List(3) { h.addJob() }
            advanceTimeBy(1.seconds)

            h.queue.pauseAll() shouldBe CommandResult.Done(null)
            settle()

            ids.map { h.job(it)!!.status to h.job(it)!!.pauseReasons } shouldBe List(3) { PAUSED to setOf(PauseReason.GLOBAL) }
            h.queue.inspect().running shouldBe emptySet()
            h.queue.inspect().globalPause shouldBe true
            h.store.settings
                .load()
                .globalPause shouldBe true
        }

    @Test
    fun resumeAllResumesOnlyWhatItPaused() =
        runTest {
            val h = started()
            val a = h.addJob()
            val b = h.addJob()
            h.queue.pause(a)
            h.queue.pauseAll()

            h.queue.resumeAll() shouldBe CommandResult.Done(null)
            settle()

            h.job(a)!!.pauseReasons shouldBe setOf(PauseReason.USER)
            h.statusesShouldBe(a to PAUSED, b to COMPLETED)
            h.store.settings
                .load()
                .globalPause shouldBe false
        }

    @Test
    fun aUserResumeDuringGlobalPauseLeavesItPausedGlobally() =
        runTest {
            val h = started()
            val a = h.addJob()
            h.queue.pause(a)
            h.queue.pauseAll()
            h.job(a)!!.pauseReasons shouldBe setOf(PauseReason.USER, PauseReason.GLOBAL)

            val resumed =
                h.queue
                    .resume(a)
                    .shouldBeInstanceOf<CommandResult.Done>()
                    .download!!

            resumed.status shouldBe PAUSED
            resumed.pauseReasons shouldBe setOf(PauseReason.GLOBAL)
        }

    @Test
    fun aJobAddedDuringGlobalPauseStartsPaused() =
        runTest {
            val h = started()
            h.queue.pauseAll()

            val a = h.addJob()
            settle()

            h.job(a)!!.status shouldBe PAUSED
            h.job(a)!!.pauseReasons shouldBe setOf(PauseReason.GLOBAL)
        }

    @Test
    fun aRetryDuringGlobalPauseWaitsPaused() =
        runTest {
            val protocol = FakeProtocol(failure = FakeFailure.AtProbe(StepResult.Failed(null, "nope")))
            val h = started { QueueHarness(it, newStore(), protocol) }
            val a = h.addJob()
            settle()
            protocol.failure = null
            h.queue.pauseAll()

            h.queue
                .retry(a)
                .shouldBeInstanceOf<CommandResult.Done>()
                .download!!
                .pauseReasons shouldBe setOf(PauseReason.GLOBAL)
            h.queue.resumeAll()
            settle()

            h.status(a) shouldBe COMPLETED
        }

    @Test
    fun pauseAllThenResumeAllBeforeTheSlotsStopRequeuesThem() =
        runTest {
            val h = started { QueueHarness(it, newStore(), FakeProtocol(stopDelay = 1.seconds)) }
            val a = h.addJob()
            advanceTimeBy(1.seconds)

            val paused = async { h.queue.pauseAll() }
            runCurrent()
            h.queue.resumeAll()
            settle()

            paused.await() shouldBe CommandResult.Done(null)
            h.status(a) shouldBe COMPLETED
        }

    @Test
    fun loweringTheLimitPreemptsTheLowestPriority() =
        runTest {
            val h = started()
            val p1 = h.addJob(priority = 1)
            val p2 = h.addJob(priority = 2)
            val p3 = h.addJob(priority = 3)
            advanceTimeBy(1.seconds)

            h.queue.setLimit(1) shouldBe CommandResult.Done(null)
            runCurrent()

            h.statusesShouldBe(p1 to QUEUED, p2 to QUEUED, p3 to DOWNLOADING)
            settle()
            h.finished shouldBe listOf(p3, p2, p1)
        }

    @Test
    fun loweringTheLimitNeverPreemptsMuxing() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val quick = FakeProtocol()
            val gated =
                object : Protocol by quick {
                    override suspend fun finalize(transfer: Transfer): StepResult<FinalizeResult> {
                        gate.await()
                        return quick.finalize(transfer)
                    }
                }
            val slow = FakeProtocol(bytesPerSecond = 1024)
            val h = started { QueueHarness(it, newStore(), slow, mapOf(DownloadKind.SITE to gated, DownloadKind.DIRECT to slow)) }
            val muxing = h.addJob(kind = DownloadKind.SITE)
            val downloading = h.addJob()
            advanceTimeBy(5.seconds)
            h.status(muxing) shouldBe MUXING

            h.queue.setLimit(1)
            runCurrent()

            h.statusesShouldBe(muxing to MUXING, downloading to QUEUED)
            gate.complete(Unit)
        }

    @Test
    fun aPreemptedJobThatReachedMuxingWaitsAgain() =
        runTest {
            // Its transfer ignores the stop, so the job reports MUXING after the preemption was decided.
            val stubborn = FakeProtocol()
            val ignoresStop =
                object : Protocol by stubborn {
                    override suspend fun transfer(
                        transfer: Transfer,
                        io: TransferIo,
                    ): StepResult<Unit> = withContext(NonCancellable) { stubborn.transfer(transfer, io) }
                }
            val h = started { QueueHarness(it, newStore(), stubborn, DownloadKind.entries.associateWith { ignoresStop }) }
            val low = h.addJob(priority = 0)
            val high = h.addJob(priority = 1)
            advanceTimeBy(1.seconds)

            h.queue.setLimit(1)
            settle()

            h.statusesShouldBe(low to COMPLETED, high to COMPLETED)
        }

    @Test
    fun resumeAllAfterALimitChangeStartsOnlyTheLimit() =
        runTest {
            val h = started()
            repeat(5) { h.addJob() }
            h.queue.pauseAll()
            h.queue.setLimit(2)

            h.queue.resumeAll()
            runCurrent()

            h.queue
                .inspect()
                .running.size shouldBe 2
        }

    @Test
    fun aLimitBelowOneIsInvalid() =
        runTest {
            val h = started()

            h.queue.setLimit(0) shouldBe CommandResult.Invalid("limit must be >= 1: 0")
            h.queue.inspect().limit shouldBe 3
        }

    // Relink

    private suspend fun QueueHarness.mirrorOf(id: DownloadId) = store.downloads.mirrors(id).single()

    @Test
    fun relinkHandsTheProtocolTheWrittenBytesAndContext() =
        runTest {
            val protocol = FakeProtocol(relinkAnswer = StepResult.Ok(Relinked(null)))
            val h = started { QueueHarness(it, newStore(), protocol, checkpointEvery = 250.milliseconds) }
            val a = h.addJob()
            h.store.downloads.setContext(a, RequestContext(referrer = "https://example.com/page"))
            advanceTimeBy(1.seconds)

            h.queue.relink(a, h.mirrorOf(a).id, "https://example.com/new").shouldBeInstanceOf<CommandResult.Done>()

            val request = h.protocol.relinkRequests.single()
            request.url shouldBe "https://example.com/new"
            request.context shouldBe RequestContext(referrer = "https://example.com/page")
            request.stored.files
                .single()
                .written
                .single()
                .first shouldBe 0L
        }

    @Test
    fun aRelinkAdoptsTheValidatorWhenItsMirrorIsNext() =
        runTest {
            val protocol = FakeProtocol(validator = "etag:\"v1\"", relinkAnswer = StepResult.Ok(Relinked("etag:\"v2\"")))
            val h = started { QueueHarness(it, newStore(), protocol) }
            val a = h.addJob()
            advanceTimeBy(1.seconds)
            protocol.validator = "etag:\"v2\"" // the new server's token

            h.queue.relink(a, h.mirrorOf(a).id, "https://example.com/new").shouldBeInstanceOf<CommandResult.Done>()
            settle()

            h.job(a)!!.sourceValidator shouldBe "etag:\"v2\""
            h.status(a) shouldBe COMPLETED
            protocol.fetched.sumOf { it.end - it.start } shouldBe 64 * 1024L // nothing fetched twice: no start-over
        }

    @Test
    fun aRelinkOfAMirrorThatIsntNextKeepsTheValidator() =
        runTest {
            val protocol = FakeProtocol(validator = "etag:\"v1\"", relinkAnswer = StepResult.Ok(Relinked("etag:\"v2\"")))
            val h = started { QueueHarness(it, newStore(), protocol) }
            val a = h.addJob(mirrors = listOf("https://a.example/f", "https://b.example/f"))
            advanceTimeBy(1.seconds)
            val second = h.store.downloads.mirrors(a)[1]

            h.queue.relink(a, second.id, "https://c.example/f").shouldBeInstanceOf<CommandResult.Done>()

            h.job(a)!!.sourceValidator shouldBe "etag:\"v1\""
        }

    @Test
    fun aPausedJobKeepsTheAdoptedValidator() =
        runTest {
            val protocol = FakeProtocol(validator = "etag:\"v1\"", relinkAnswer = StepResult.Ok(Relinked("etag:\"v2\"")))
            val h = started { QueueHarness(it, newStore(), protocol) }
            val a = h.addJob()
            advanceTimeBy(1.seconds)
            h.queue.pause(a).shouldBeInstanceOf<CommandResult.Done>()
            runCurrent()

            h.queue.relink(a, h.mirrorOf(a).id, "https://example.com/new").shouldBeInstanceOf<CommandResult.Done>()

            h.status(a) shouldBe PAUSED
            h.job(a)!!.sourceValidator shouldBe "etag:\"v2\""
        }

    @Test
    fun aRelinkWithoutAValidatorKeepsTheOld() =
        runTest {
            val protocol = FakeProtocol(validator = "etag:\"v1\"", relinkAnswer = StepResult.Ok(Relinked(null)))
            val h = started { QueueHarness(it, newStore(), protocol) }
            val a = h.addJob()
            advanceTimeBy(1.seconds)

            h.queue.relink(a, h.mirrorOf(a).id, "https://example.com/new").shouldBeInstanceOf<CommandResult.Done>()
            settle()

            h.job(a)!!.sourceValidator shouldBe "etag:\"v1\""
        }

    @Test
    fun aRunGetsTheMirrorsNotGoneThisRound() =
        runTest {
            val h = started { QueueHarness(it, newStore(), FakeProtocol(gone = setOf("https://a.example/f"))) }
            val a = h.addJob(mirrors = listOf("https://a.example/f", "https://b.example/f", "https://c.example/f"))
            settle()
            val (_, b, c) = h.store.downloads.mirrors(a)
            h.protocol.transfers
                .single()
                .mirror shouldBe b
            h.protocol.transfers
                .single()
                .mirrors shouldBe listOf(c)
        }

    // Checksums (#573): one start-over from zero, then INTEGRITY with the file kept.

    private val rightChecksum =
        Checksum(
            ChecksumAlgorithm.SHA256,
            hasherFor(ChecksumAlgorithm.SHA256).also { it.update(fakeContent(0, 0, 64 * 1024)) }.hex(),
        )
    private val wrongChecksum = Checksum(ChecksumAlgorithm.SHA256, "0".repeat(64))

    private fun partialOf(id: DownloadId) = Path(stepRoot, "job-1.zip.${id.value}.anydm")

    private suspend fun TestScope.failedTwice(h: QueueHarness): DownloadId {
        val a = h.addJob(checksum = wrongChecksum)
        settle()
        h.status(a) shouldBe FAILED
        return a
    }

    @Test
    fun aMismatchStartsOverOnce() =
        runTest {
            val h = started()
            val a = failedTwice(h)

            h.store.downloads
                .attempts(a)
                .map { it.errorClass } shouldBe listOf(ErrorClass.INTEGRITY, ErrorClass.INTEGRITY)
            h.protocol.fetched.count { it.start == 0L } shouldBe 2 // the second run fetched from zero again
            h.protocol.fetched.sumOf { it.end - it.start } shouldBe 2 * 64 * 1024L
            h.fs.exists(partialOf(a)) shouldBe true
        }

    @Test
    fun aCorrectedChecksumPassesOnRetry() =
        runTest {
            val h = started()
            val a = failedTwice(h)
            h.protocol.fetched.clear()

            h.queue.setChecksum(a, rightChecksum).shouldBeInstanceOf<CommandResult.Done>()
            h.queue.retry(a).shouldBeInstanceOf<CommandResult.Done>()
            settle()

            h.status(a) shouldBe COMPLETED
            h.protocol.fetched shouldBe emptyList()
        }

    @Test
    fun clearingTheChecksumSkipsTheCheck() =
        runTest {
            val h = started()
            val a = failedTwice(h)

            h.queue.setChecksum(a, null).shouldBeInstanceOf<CommandResult.Done>()
            h.queue.retry(a).shouldBeInstanceOf<CommandResult.Done>()
            settle()

            h.status(a) shouldBe COMPLETED
        }

    @Test
    fun setChecksumSurvivesARunningJobsReports() =
        runTest {
            val h = started()
            val a = h.addJob()
            advanceTimeBy(1.seconds)
            h.status(a) shouldBe DOWNLOADING

            h.queue.setChecksum(a, rightChecksum).shouldBeInstanceOf<CommandResult.Done>()
            settle()

            h.status(a) shouldBe COMPLETED
            h.job(a)!!.checksum shouldBe rightChecksum
        }

    // Final review: after an INTEGRITY failure every byte is fetched, and the all-fetched rule kept that bad copy even
    // when the source changed, so a fixed source was never downloaded.
    @Test
    fun aChangedSourceIsFetchedAgainAfterAnIntegrityFailure() =
        runTest {
            val protocol = FakeProtocol(validator = "etag:\"v1\"", checksum = Checksum(ChecksumAlgorithm.MD5, "0".repeat(32)))
            val h = started { QueueHarness(it, newStore(), protocol) }
            val a = h.addJob()
            settle()
            h.status(a) shouldBe FAILED
            protocol.fetched.clear()
            protocol.validator = "etag:\"v2\"" // the server published a fixed file
            protocol.checksum = null

            h.queue.retry(a).shouldBeInstanceOf<CommandResult.Done>()
            settle()

            h.status(a) shouldBe COMPLETED
            protocol.fetched.sumOf { it.end - it.start } shouldBe 64 * 1024L
            h.job(a)!!.sourceValidator shouldBe "etag:\"v2\""
        }

    @Test
    fun setChecksumOfAnUnknownJobIsNotFound() =
        runTest {
            started().queue.setChecksum(DownloadId(999), null) shouldBe CommandResult.NotFound
        }

    // Speed limits (#574). FakeProtocol takes 4 KiB every 250 ms through the limiter: unlimited, a job takes 4 s.

    @Test
    fun aJobsSpeedLimitSlowsItsRun() =
        runTest {
            val h = started()
            val a = h.addJob(speedLimit = 8 * 1024)
            advanceTimeBy(6.seconds)
            h.status(a) shouldBe DOWNLOADING
            advanceTimeBy(4.seconds)
            h.status(a) shouldBe COMPLETED
        }

    @Test
    fun theGlobalLimitAppliesFromStart() =
        runTest {
            val h = QueueHarness(this, newStore())
            h.store.settings.update { it.copy(globalSpeedLimit = 8 * 1024) }
            h.queue.start()
            val a = h.addJob()
            advanceTimeBy(6.seconds)
            h.status(a) shouldBe DOWNLOADING
            advanceTimeBy(4.seconds)
            h.status(a) shouldBe COMPLETED
        }

    @Test
    fun aResumedJobKeepsItsLimit() =
        runTest {
            val h = started()
            val a = h.addJob(speedLimit = 4 * 1024)
            advanceTimeBy(2.seconds)
            h.queue.pause(a).shouldBeInstanceOf<CommandResult.Done>()
            runCurrent()
            h.status(a) shouldBe PAUSED
            h.queue.resume(a).shouldBeInstanceOf<CommandResult.Done>()
            advanceTimeBy(6.seconds)
            h.status(a) shouldBe DOWNLOADING
            advanceTimeBy(14.seconds)
            h.status(a) shouldBe COMPLETED
        }

    @Test
    fun theDefaultRelinkIsRefused() =
        runTest {
            val h = started()
            val a = h.addJob()
            val mirror = h.mirrorOf(a)

            h.queue.relink(a, mirror.id, "https://example.com/new") shouldBe
                CommandResult.RelinkFailed(StepFailure(Step.PROBE, null, "relink not supported"))
            h.mirrorOf(a).url shouldBe mirror.url
        }

    @Test
    fun relinkSavesTheUrlAndReprobesARunningJob() =
        runTest {
            val h = started { QueueHarness(it, newStore(), FakeProtocol(relinkAnswer = StepResult.Ok(Relinked(null)))) }
            val a = h.addJob()
            advanceTimeBy(1.seconds)
            h.status(a) shouldBe DOWNLOADING

            h.queue.relink(a, h.mirrorOf(a).id, "https://example.com/new").shouldBeInstanceOf<CommandResult.Done>()
            h.mirrorOf(a).url shouldBe "https://example.com/new"
            settle()

            h.status(a) shouldBe COMPLETED
            h.protocol.calls.count { it == "probe" } shouldBe 2
        }

    @Test
    fun aRefusedRelinkChangesNothing() =
        runTest {
            val refusal = StepResult.Failed(ErrorClass.AUTH, "not the same file")
            val h = started { QueueHarness(it, newStore(), FakeProtocol(relinkAnswer = refusal)) }
            val a = h.addJob()
            advanceTimeBy(1.seconds)
            val mirror = h.mirrorOf(a)

            h.queue.relink(a, mirror.id, "https://example.com/new") shouldBe
                CommandResult.RelinkFailed(StepFailure(Step.PROBE, ErrorClass.AUTH, "not the same file"))

            h.mirrorOf(a) shouldBe mirror
            h.status(a) shouldBe DOWNLOADING
        }

    @Test
    fun relinkOfAMuxingJobIsRejected() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val quick = FakeProtocol(relinkAnswer = StepResult.Ok(Relinked(null)))
            val gated =
                object : Protocol by quick {
                    override suspend fun finalize(transfer: Transfer): StepResult<FinalizeResult> {
                        gate.await()
                        return quick.finalize(transfer)
                    }
                }
            val h = started { QueueHarness(it, newStore(), quick, DownloadKind.entries.associateWith { gated }) }
            val a = h.addJob()
            advanceTimeBy(5.seconds)
            h.status(a) shouldBe MUXING

            h.queue.relink(a, h.mirrorOf(a).id, "https://example.com/new") shouldBe
                CommandResult.Rejected(TransitionResult.Rejected(MUXING, PENDING))
            gate.complete(Unit)
        }

    @Test
    fun relinkOfAnotherJobsMirrorIsNotFound() =
        runTest {
            val h = started()
            val a = h.addJob()
            val b = h.addJob()

            h.queue.relink(a, h.mirrorOf(b).id, "https://example.com/new") shouldBe CommandResult.NotFound
        }

    @Test
    fun relinkDoesNotBlockOtherCommands() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val protocol = FakeProtocol(relinkAnswer = StepResult.Ok(Relinked(null)), relinkGate = gate)
            val h = started { QueueHarness(it, newStore(), protocol) }
            val a = h.addJob()
            val b = h.addJob()
            advanceTimeBy(1.seconds)

            val relinked = async { h.queue.relink(a, h.mirrorOf(a).id, "https://example.com/new") }
            runCurrent()
            h.queue.pause(b).shouldBeInstanceOf<CommandResult.Done>()
            relinked.isCompleted shouldBe false

            gate.complete(Unit)
            relinked.await().shouldBeInstanceOf<CommandResult.Done>()
        }

    // Final review fixes

    @Test
    fun aStopRacingTheFinishStillRunsTheFinishHook() =
        runTest {
            val h = started()
            val a = h.addJob()
            var paused: CommandResult? = null
            // The pause is queued while the outputs are placed, so it reaches the queue before the COMPLETED move does.
            h.fs.faults[FakeFileSystem.Op.ATOMIC_MOVE] = {
                launch(start = CoroutineStart.UNDISPATCHED) { paused = h.queue.pause(a) }
                null
            }
            settle()

            h.status(a) shouldBe COMPLETED
            h.finished shouldBe listOf(a)
            paused shouldBe CommandResult.Rejected(TransitionResult.Rejected(COMPLETED, PAUSED))
        }

    @Test
    fun aCancellationNobodyAskedForFailsTheJob() =
        runTest {
            val quiet = FakeProtocol()
            val leaky =
                object : Protocol by quiet {
                    override suspend fun transfer(
                        transfer: Transfer,
                        io: TransferIo,
                    ): StepResult<Unit> = throw CancellationException("a timeout that escaped")
                }
            val h = started { QueueHarness(it, newStore(), quiet, DownloadKind.entries.associateWith { leaky }) }
            val a = h.addJob()
            settle()

            h.status(a) shouldBe FAILED
            h.queue.inspect().running shouldBe emptySet()
        }

    @Test
    fun aStoreFailureAnswersCommandsWaitingForASlotToStop() =
        runTest {
            val failures = mutableListOf<Throwable>()
            val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job() + CoroutineExceptionHandler { _, e -> failures += e })
            val h = started { QueueHarness(it, newStore(), FakeProtocol(stopDelay = 1.seconds), scope = scope) }
            val a = h.addJob()
            advanceTimeBy(1.seconds)
            val paused = async { runCatching { h.queue.pause(a) } }
            advanceTimeBy(100.milliseconds)
            h.store.close()

            shouldThrow<Exception> { h.addJob() }
            settle()

            paused.isCompleted shouldBe true
            paused.await().isFailure shouldBe true
        }

    @Test
    fun aStoppedQueueAnswersEveryCommand() =
        runTest {
            val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
            val h = started { QueueHarness(it, newStore(), FakeProtocol(stopDelay = 1.seconds), scope = scope) }
            val a = h.addJob()
            advanceTimeBy(1.seconds)
            val paused = async { runCatching { h.queue.pause(a) } }
            advanceTimeBy(100.milliseconds)

            scope.cancel()
            runCurrent()

            paused.isCompleted shouldBe true
            paused.await().exceptionOrNull().shouldBeInstanceOf<IllegalStateException>()
            shouldThrow<IllegalStateException> { h.queue.add(NewDownload(DownloadKind.DIRECT)) }
        }

    @Test
    fun relinkKeepsAWaitingJobsPlace() =
        runTest {
            val h = started(limit = 1) { QueueHarness(it, newStore(), FakeProtocol(relinkAnswer = StepResult.Ok(Relinked(null)))) }
            h.addJob()
            val a = h.addJob()
            runCurrent()
            val queuedAt = h.job(a)!!.queuedAt
            h.status(a) shouldBe PENDING
            advanceTimeBy(1.seconds)

            h.queue.relink(a, h.mirrorOf(a).id, "https://example.com/new").shouldBeInstanceOf<CommandResult.Done>()

            h.job(a)!!.queuedAt shouldBe queuedAt
        }

    @Test
    fun aRelinkThatThrowsIsAnInternalError() =
        runTest {
            val fake = FakeProtocol()
            val buggy =
                object : Protocol by fake {
                    override suspend fun relink(request: RelinkRequest): StepResult<Relinked> = throw IllegalStateException("bug")
                }
            val h = started { QueueHarness(it, newStore(), fake, DownloadKind.entries.associateWith { buggy }) }
            val a = h.addJob()
            val mirror = h.mirrorOf(a)

            h.queue.relink(a, mirror.id, "https://example.com/new") shouldBe
                CommandResult.RelinkFailed(StepFailure(Step.PROBE, null, "internal error: bug"))
            h.mirrorOf(a).url shouldBe mirror.url
        }

    @Test
    fun aStoreFailureDuringPauseAllReachesItsCaller() =
        runTest {
            val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job() + CoroutineExceptionHandler { _, _ -> })
            val h = started { QueueHarness(it, newStore(), FakeProtocol(stopDelay = 1.seconds), scope = scope) }
            h.addJob()
            advanceTimeBy(1.seconds)
            val paused = async { runCatching { h.queue.pauseAll() } }
            advanceTimeBy(100.milliseconds)
            h.store.close()

            val error = shouldThrow<Exception> { h.addJob() }
            settle()

            val failure = paused.await().exceptionOrNull().shouldNotBeNull()
            failure.shouldNotBeInstanceOf<CancellationException>()
            failure.message shouldBe error.message
        }

    @Test
    fun aStoppedQueueAnswersPauseAllWithWhy() =
        runTest {
            val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
            val h = started { QueueHarness(it, newStore(), FakeProtocol(stopDelay = 1.seconds), scope = scope) }
            h.addJob()
            advanceTimeBy(1.seconds)
            val paused = async { runCatching { h.queue.pauseAll() } }
            advanceTimeBy(100.milliseconds)

            scope.cancel()
            runCurrent()

            paused.isCompleted shouldBe true
            val failure = paused.await().exceptionOrNull().shouldNotBeNull()
            failure.shouldNotBeInstanceOf<CancellationException>() // a JVM CancellationException is an IllegalStateException
            failure.message shouldBe "the queue has stopped"
        }

    // Source validators (#554)

    private suspend fun TestScope.changeSourceWhilePaused(): Pair<QueueHarness, DownloadId> {
        val h = started()
        h.protocol.validator = "a"
        val a = h.addJob()
        advanceTimeBy(1.seconds)
        h.queue.pause(a)
        h.protocol.validator = "b"
        h.protocol.fetched.clear()
        h.queue.resume(a)
        settle()
        return h to a
    }

    @Test
    fun aChangedSourceIsRecordedOnTheAttempt() =
        runTest {
            val (h, a) = changeSourceWhilePaused()

            h.status(a) shouldBe COMPLETED
            h.fs.read(Path(stepRoot, "job-1.zip"))!!.toList() shouldBe fakeContent(0, 0, 64 * 1024).toList()
            h.store.downloads
                .attempts(a)
                .last()
                .message shouldBe "source changed (validator \"a\" → \"b\"): starting from zero"
        }

    @Test
    fun aSourceThatChangedWhilePausedStartsOver() =
        runTest {
            val (h, _) = changeSourceWhilePaused()

            h.protocol.fetched.minOf { it.start } shouldBe 0L
            h.protocol.fetched.sumOf { it.end - it.start } shouldBe 64 * 1024L
        }
}
