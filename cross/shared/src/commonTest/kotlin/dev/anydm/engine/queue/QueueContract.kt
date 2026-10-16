package dev.anydm.engine.queue

import dev.anydm.engine.core.Download
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.DownloadStatus.COMPLETED
import dev.anydm.engine.core.DownloadStatus.DOWNLOADING
import dev.anydm.engine.core.DownloadStatus.FAILED
import dev.anydm.engine.core.DownloadStatus.PENDING
import dev.anydm.engine.core.FinishHook
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
}
