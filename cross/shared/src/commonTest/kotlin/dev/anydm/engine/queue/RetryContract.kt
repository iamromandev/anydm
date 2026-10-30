package dev.anydm.engine.queue

import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.DownloadStatus.COMPLETED
import dev.anydm.engine.core.DownloadStatus.DOWNLOADING
import dev.anydm.engine.core.DownloadStatus.FAILED
import dev.anydm.engine.core.DownloadStatus.PAUSED
import dev.anydm.engine.core.DownloadStatus.PENDING
import dev.anydm.engine.core.DownloadStatus.QUEUED
import dev.anydm.engine.core.ErrorClass
import dev.anydm.engine.core.PauseReason
import dev.anydm.engine.core.SegmentId
import dev.anydm.engine.platform.ShiftedClock
import dev.anydm.engine.platform.virtualClock
import dev.anydm.engine.platform.virtualEpoch
import dev.anydm.engine.protocol.FakeFailure
import dev.anydm.engine.protocol.FakeProtocol
import dev.anydm.engine.protocol.Protocol
import dev.anydm.engine.protocol.Relinked
import dev.anydm.engine.protocol.StepResult
import dev.anydm.engine.protocol.Transfer
import dev.anydm.engine.protocol.TransferIo
import dev.anydm.engine.store.NewAttempt
import dev.anydm.engine.store.NewDownload
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** What the queue does with failures (#553): Attempt rows, retries, mirror failover. A new database for each test. */
abstract class RetryContract : QueueTestBase() {
    /** A harness whose SITE jobs run on [protocol]; DIRECT jobs on their own working fake. */
    private fun TestScope.failingOn(protocol: Protocol): (TestScope) -> QueueHarness {
        val store = newStore()
        return { QueueHarness(it, store, FakeProtocol(), mapOf(DownloadKind.DIRECT to FakeProtocol(), DownloadKind.SITE to protocol)) }
    }

    // Attempt rows

    @Test
    fun aFinishedAttemptHasNoClassOrMessage() =
        runTest {
            val h = started()
            val a = h.addJob()
            settle()

            val mirror =
                h.store.downloads
                    .mirrors(a)
                    .single()
            val attempt =
                h.store.downloads
                    .attempts(a)
                    .single()
            attempt.mirrorId shouldBe mirror.id
            attempt.endedAt.shouldNotBeNull()
            attempt.errorClass shouldBe null
            attempt.message shouldBe null
        }

    @Test
    fun aFailedAttemptKeepsItsClassAndMessage() =
        runTest {
            val h = started(harness = failingOn(FakeProtocol(failure = FakeFailure.AtProbe(StepResult.Failed(ErrorClass.AUTH, "denied")))))
            val a = h.addJob(kind = DownloadKind.SITE)
            settle()

            val attempt =
                h.store.downloads
                    .attempts(a)
                    .single()
            attempt.errorClass shouldBe ErrorClass.AUTH
            attempt.message shouldBe "denied"
            attempt.endedAt.shouldNotBeNull()
        }

    @Test
    fun aCrashIsRecordedAsAnInternalError() =
        runTest {
            val h = started(harness = failingOn(FakeProtocol(failure = FakeFailure.ThrowAtProbe(IllegalStateException("bug")))))
            val a = h.addJob(kind = DownloadKind.SITE)
            settle()

            val attempt =
                h.store.downloads
                    .attempts(a)
                    .single()
            attempt.errorClass shouldBe null
            attempt.message shouldBe "internal error: bug"
            attempt.endedAt.shouldNotBeNull()
        }

    @Test
    fun aCancellationNobodyAskedForIsRecordedAsAnInternalError() =
        runTest {
            val quiet = FakeProtocol()
            val leaky =
                object : Protocol by quiet {
                    override suspend fun transfer(
                        transfer: Transfer,
                        io: TransferIo,
                    ): StepResult<Unit> = throw CancellationException("a timeout that escaped")
                }
            val h = started(harness = failingOn(leaky))
            val a = h.addJob(kind = DownloadKind.SITE)
            settle()

            h.store.downloads
                .attempts(a)
                .single()
                .message shouldBe "internal error: cancelled unexpectedly"
        }

    @Test
    fun aStoppedAttemptSaysStopped() =
        runTest {
            val h = started()
            val a = h.addJob()
            advanceTimeBy(1.seconds)

            h.queue.pause(a)
            settle()

            val attempt =
                h.store.downloads
                    .attempts(a)
                    .single()
            attempt.message shouldBe "stopped"
            attempt.errorClass shouldBe null
            attempt.endedAt.shouldNotBeNull()
        }

    @Test
    fun aSlotStoppedBeforeItRanClosesItsAttempt() =
        runTest {
            val h = started()
            val a = h.addJob()

            h.queue.pause(a) // the slot was launched but its coroutine hasn't run yet
            settle()

            h.store.downloads
                .openAttempts()
                .shouldBeEmpty()
            h.store.downloads
                .attempts(a)
                .single()
                .message shouldBe "stopped"
        }

    @Test
    fun aJobWithoutAMirrorRecordsItsAttempt() =
        runTest {
            val h = started()
            val a =
                h.queue
                    .add(NewDownload(DownloadKind.DIRECT))
                    .shouldBeInstanceOf<CommandResult.Done>()
                    .download!!
                    .id
            settle()

            h.status(a) shouldBe FAILED
            val attempt =
                h.store.downloads
                    .attempts(a)
                    .single()
            attempt.mirrorId shouldBe null
            attempt.endedAt.shouldNotBeNull()
            attempt.message shouldBe "no source to download from"
        }

    @Test
    fun theSlotStartsOnTheFirstMirrorThatIsNotGone() =
        runTest {
            val h = QueueHarness(this, newStore())
            val a = h.store.downloads.add(NewDownload(DownloadKind.DIRECT, mirrors = listOf("https://a.example/f", "https://b.example/f")))
            val (first, second) = h.store.downloads.mirrors(a.id)
            val gone = h.store.downloads.addAttempt(NewAttempt(a.id, virtualEpoch, first.id))
            h.store.downloads.updateAttempt(gone.copy(endedAt = virtualEpoch, errorClass = ErrorClass.GONE, message = "404"))

            h.queue.start()
            runCurrent()

            h.protocol.probed shouldBe listOf("https://b.example/f")
            h.store.downloads
                .attempts(a.id)
                .last()
                .mirrorId shouldBe second.id
        }

    @Test
    fun aRestartClosesOpenAttempts() =
        runTest {
            val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
            val store = newStore()
            val first = QueueHarness(this, store, scope = scope).also { it.queue.start() }
            val a = first.addJob()
            advanceTimeBy(1.seconds)
            scope.cancel()
            runCurrent()

            val restartedAt = first.clock.now()
            QueueHarness(this, store, clock = first.clock).queue.start()

            val attempt = store.downloads.attempts(a).first()
            attempt.endedAt shouldBe restartedAt
            attempt.message shouldBe "the app stopped"
            settle()
            store.downloads.openAttempts().shouldBeEmpty()
        }

    // Retries, failover and the timer. The harness's policy has no jitter: the first retry waits 2.5 s.

    private val reset = FakeFailure.AtProbe(StepResult.Failed(ErrorClass.TRANSIENT, "reset"))

    private suspend fun TestScope.startedWith(maxRetries: Int): QueueHarness {
        val h = QueueHarness(this, newStore())
        h.store.settings.update { it.copy(maxRetries = maxRetries) }
        h.queue.start()
        return h
    }

    @Test
    fun aTransientFailureWaitsThenRetries() =
        runTest {
            val h = started()
            h.protocol.failure = reset
            val t0 = h.clock.now()
            val a = h.addJob()
            runCurrent()

            val waiting = h.job(a)!!
            waiting.status shouldBe PENDING
            waiting.attemptCount shouldBe 1
            waiting.nextAttemptAt shouldBe t0 + 2.5.seconds
            waiting.queuedAt shouldBe waiting.nextAttemptAt
            h.queue
                .inspect()
                .running
                .shouldBeEmpty()

            advanceTimeBy(2.4.seconds)
            runCurrent()
            h.protocol.probed.size shouldBe 1

            h.protocol.failure = null
            advanceTimeBy(0.2.seconds)
            runCurrent()
            h.protocol.probed.size shouldBe 2
            h.job(a)!!.nextAttemptAt shouldBe null

            settle()
            h.status(a) shouldBe COMPLETED
            h.job(a)!!.attemptCount shouldBe 0
        }

    @Test
    fun aUsedUpBudgetFails() =
        runTest {
            val h = startedWith(maxRetries = 2)
            h.protocol.failure = reset
            val a = h.addJob()
            settle()

            val job = h.job(a)!!
            job.status shouldBe FAILED
            job.attemptCount shouldBe 2
            job.nextAttemptAt shouldBe null
            h.store.downloads
                .attempts(a)
                .size shouldBe 3
        }

    @Test
    fun aFailureAfterProgressDoesNotUseUpTheBudget() =
        runTest {
            val h = startedWith(maxRetries = 1)
            h.protocol.failure = FakeFailure.AtTransfer(16 * 1024, StepResult.Failed(ErrorClass.TRANSIENT, "drop"))
            val a = h.addJob()
            settle()

            // Runs 1 to 4 each add 16 KiB of the 64, so the count stays at 1; run 5 has nothing left to write, makes no
            // progress and uses up the budget. Without the reset the job would fail after 2.
            h.status(a) shouldBe FAILED
            h.store.downloads
                .attempts(a)
                .size shouldBe 5
        }

    @Test
    fun aMuxingFailureRetriesThroughQueued() =
        runTest {
            val h = started()
            h.protocol.failure = FakeFailure.AtFinalize(StepResult.Failed(ErrorClass.TRANSIENT, "busy"))
            val a = h.addJob()
            advanceTimeBy(5.seconds)
            runCurrent()

            h.status(a) shouldBe QUEUED
            h.job(a)!!.nextAttemptAt.shouldNotBeNull()

            h.protocol.failure = null
            settle()
            h.status(a) shouldBe COMPLETED
        }

    @Test
    fun goneFailsOverInMirrorOrder() =
        runTest {
            val h = started()
            val urls = listOf("https://a.example/f", "https://b.example/f", "https://c.example/f")
            h.protocol.gone = urls.take(2).toSet()
            val a = h.addJob(mirrors = urls)
            settle()

            h.status(a) shouldBe COMPLETED
            h.protocol.probed shouldBe urls
            h.store.downloads
                .attempts(a)
                .map { it.mirrorId } shouldBe
                h.store.downloads
                    .mirrors(a)
                    .map { it.id }
            h.job(a)!!.attemptCount shouldBe 0
        }

    @Test
    fun goneOnEveryMirrorFailsAndARetryStartsAtTheFirst() =
        runTest {
            val h = started()
            val urls = listOf("https://a.example/f", "https://b.example/f")
            h.protocol.gone = urls.toSet()
            val a = h.addJob(mirrors = urls)
            settle()
            h.status(a) shouldBe FAILED

            h.protocol.gone = emptySet()
            h.queue.retry(a)
            settle()

            h.status(a) shouldBe COMPLETED
            h.protocol.probed shouldBe urls + urls.first()
        }

    @Test
    fun failoverKeepsTheJobsPlace() =
        runTest {
            val h = started(limit = 1)
            h.protocol.gone = setOf("https://a.example/f")
            val a = h.addJob(mirrors = listOf("https://a.example/f", "https://b.example/f"))
            val b = h.addJob()
            settle()

            h.finished shouldBe listOf(a, b)
        }

    @Test
    fun diskFullPausesForLowDisk() =
        runTest {
            val h = started()
            h.fs.free = 1_000 // the probe learns 64 KiB
            val a = h.addJob()
            runCurrent()

            val paused = h.job(a)!!
            paused.status shouldBe PAUSED
            paused.pauseReasons shouldBe setOf(PauseReason.LOW_DISK)
            paused.nextAttemptAt shouldBe null

            h.fs.free = 1L shl 40
            h.queue.resume(a)
            settle()
            h.status(a) shouldBe COMPLETED
        }

    @Test
    fun theRetryTimeSurvivesARestart() =
        runTest {
            val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
            val store = newStore()
            val slow = FakeProtocol(bytesPerSecond = 1024)
            val failing = FakeProtocol(failure = reset)
            val first = QueueHarness(this, store, slow, mapOf(DownloadKind.DIRECT to slow, DownloadKind.SITE to failing), scope = scope)
            first.queue.start()
            val t0 = first.clock.now()
            val a = first.addJob(kind = DownloadKind.SITE)
            val b = first.addJob()
            advanceTimeBy(1.seconds)
            runCurrent()
            first.job(a)!!.nextAttemptAt shouldBe t0 + 2.5.seconds
            first.status(b) shouldBe DOWNLOADING

            scope.cancel()
            runCurrent()
            val second = QueueHarness(this, store, clock = first.clock)
            second.queue.start()

            store.downloads
                .attempts(b)
                .first()
                .message shouldBe "the app stopped"
            second.job(b)!!.attemptCount shouldBe 0
            second.job(a)!!.nextAttemptAt shouldBe t0 + 2.5.seconds

            advanceTimeBy(1.4.seconds)
            runCurrent()
            store.downloads.attempts(a).size shouldBe 1

            advanceTimeBy(0.2.seconds)
            runCurrent()
            store.downloads.attempts(a).size shouldBe 2

            settle()
            second.statusesShouldBe(a to COMPLETED, b to COMPLETED)
        }

    @Test
    fun aWakeBeforeTheRetryTimeReArms() =
        runTest {
            val shifted = ShiftedClock(virtualClock())
            val h = started { QueueHarness(it, newStore(), clock = shifted) }
            h.protocol.failure = reset
            val t0 = shifted.now()
            val a = h.addJob()
            runCurrent()
            h.job(a)!!.nextAttemptAt shouldBe t0 + 2.5.seconds

            shifted.offset = (-1).minutes // the wall clock is set back
            advanceTimeBy(3.seconds)
            runCurrent()
            h.status(a) shouldBe PENDING
            h.store.downloads
                .attempts(a)
                .size shouldBe 1

            advanceTimeBy(1.minutes)
            runCurrent()
            h.store.downloads
                .attempts(a)
                .size shouldBe 2
        }

    @Test
    fun theTimerFollowsTheEarliestRetry() =
        runTest {
            val h = started()
            h.protocol.failure = reset
            val a = h.addJob() // retries at 2.5 s
            runCurrent()
            advanceTimeBy(1.seconds)
            val b = h.addJob() // retries at 3.5 s
            runCurrent()
            advanceTimeBy(1.seconds)

            h.queue.remove(a, RemoveFiles.KEEP)
            advanceTimeBy(1.6.seconds)
            runCurrent()

            h.store.downloads
                .attempts(b)
                .size shouldBe 2
        }

    @Test
    fun aZeroRetryAfterStillRunsOutOfBudget() =
        runTest {
            val h = started()
            h.protocol.failure = FakeFailure.AtProbe(StepResult.Failed(ErrorClass.RATE_LIMITED, "slow", 0.seconds))
            val t0 = h.clock.now()
            val a = h.addJob()
            runCurrent()

            h.status(a) shouldBe FAILED
            val attempts = h.store.downloads.attempts(a)
            attempts.size shouldBe 6
            attempts.map { it.startedAt }.toSet() shouldBe setOf(t0)
        }

    // Commands while a retry is pending

    @Test
    fun pauseKeepsAPendingRetryAndResumeRunsItNow() =
        runTest {
            val h = started()
            h.protocol.failure = reset
            val t0 = h.clock.now()
            val a = h.addJob()
            runCurrent()

            h.queue.pause(a)
            h.job(a)!!.status shouldBe PAUSED
            h.job(a)!!.nextAttemptAt shouldBe t0 + 2.5.seconds

            h.protocol.failure = null
            advanceTimeBy(1.seconds)
            h.queue.resume(a)
            runCurrent()

            h.job(a)!!.nextAttemptAt shouldBe null
            h.store.downloads
                .attempts(a)
                .size shouldBe 2
        }

    @Test
    fun aPendingRetryOutlastsAGlobalPause() =
        runTest {
            val h = started()
            h.protocol.failure = reset
            val t0 = h.clock.now()
            val a = h.addJob()
            runCurrent()

            advanceTimeBy(1.seconds)
            h.queue.pauseAll()
            advanceTimeBy(0.5.seconds)
            h.queue.resumeAll()
            runCurrent()
            h.job(a)!!.nextAttemptAt shouldBe t0 + 2.5.seconds

            advanceTimeBy(0.9.seconds)
            runCurrent()
            h.store.downloads
                .attempts(a)
                .size shouldBe 1

            advanceTimeBy(0.2.seconds)
            runCurrent()
            h.store.downloads
                .attempts(a)
                .size shouldBe 2
        }

    @Test
    fun relinkRunsAPendingRetryNowAndKeepsItsPlace() =
        runTest {
            val h = started()
            h.protocol.failure = reset
            h.protocol.relinkAnswer = StepResult.Ok(Relinked(null))
            val a = h.addJob()
            runCurrent()
            val waiting = h.job(a)!!
            waiting.attemptCount shouldBe 1

            h.protocol.failure = null
            val mirror =
                h.store.downloads
                    .mirrors(a)
                    .single()
            h.queue.relink(a, mirror.id, "https://example.com/new").shouldBeInstanceOf<CommandResult.Done>()
            runCurrent()

            val relinked = h.job(a)!!
            relinked.nextAttemptAt shouldBe null
            relinked.attemptCount shouldBe 0
            relinked.queuedAt shouldBe waiting.queuedAt
            h.store.downloads
                .attempts(a)
                .size shouldBe 2
        }

    // Final review fixes

    @Test
    fun aDropBeforeACheckpointStillUsesUpTheBudget() =
        runTest {
            // Bytes reach the partial, but the run drops before any watermark is stored: nothing durable was gained.
            val fake = FakeProtocol(failure = FakeFailure.AtTransfer(8 * 1024, StepResult.Failed(ErrorClass.TRANSIENT, "reset")))
            val noCheckpoints =
                object : Protocol by fake {
                    override suspend fun transfer(
                        transfer: Transfer,
                        io: TransferIo,
                    ): StepResult<Unit> =
                        fake.transfer(
                            transfer,
                            object : TransferIo by io {
                                override suspend fun checkpoint(watermarks: Map<SegmentId, Long>) {}
                            },
                        )
                }
            val h = QueueHarness(this, newStore(), fake, DownloadKind.entries.associateWith { noCheckpoints })
            h.store.settings.update { it.copy(maxRetries = 2) }
            h.queue.start()
            val a = h.addJob()
            settle()

            h.status(a) shouldBe FAILED
            h.store.downloads
                .attempts(a)
                .size shouldBe 3
        }

    @Test
    fun aWallClockJumpForwardIsNoticedWithinAMinute() =
        runTest {
            val shifted = ShiftedClock(virtualClock())
            val h = started { QueueHarness(it, newStore(), clock = shifted) }
            h.protocol.failure = FakeFailure.AtProbe(StepResult.Failed(ErrorClass.RATE_LIMITED, "slow", 1.hours))
            val a = h.addJob()
            runCurrent()
            h.protocol.failure = null

            shifted.offset = 1.hours // the laptop slept, or the clock was corrected
            advanceTimeBy(61.seconds)
            runCurrent()

            h.store.downloads
                .attempts(a)
                .size shouldBe 2
        }
}
