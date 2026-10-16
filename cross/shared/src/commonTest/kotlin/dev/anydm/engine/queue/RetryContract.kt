package dev.anydm.engine.queue

import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.DownloadStatus.FAILED
import dev.anydm.engine.core.ErrorClass
import dev.anydm.engine.platform.virtualEpoch
import dev.anydm.engine.protocol.FakeFailure
import dev.anydm.engine.protocol.FakeProtocol
import dev.anydm.engine.protocol.Protocol
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
}
