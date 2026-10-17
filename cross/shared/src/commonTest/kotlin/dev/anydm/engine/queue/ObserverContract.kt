package dev.anydm.engine.queue

import dev.anydm.engine.EngineEvent
import dev.anydm.engine.core.Attempt
import dev.anydm.engine.core.Download
import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadStatus.COMPLETED
import dev.anydm.engine.core.DownloadStatus.DOWNLOADING
import dev.anydm.engine.core.DownloadStatus.MUXING
import dev.anydm.engine.core.DownloadStatus.PAUSED
import dev.anydm.engine.core.DownloadStatus.PENDING
import dev.anydm.engine.core.DownloadStatus.QUEUED
import dev.anydm.engine.core.ErrorClass
import dev.anydm.engine.core.Mirror
import dev.anydm.engine.core.stepRoot
import dev.anydm.engine.protocol.FakeFailure
import dev.anydm.engine.protocol.FakeProtocol
import dev.anydm.engine.protocol.StepResult
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/** What the queue reports to its observer (#555), on a new database from [newDriver] for each test. */
abstract class ObserverContract : QueueTestBase() {
    internal class RecordingObserver : QueueObserver {
        val events = mutableListOf<EngineEvent>()
        val changed = mutableListOf<Download>()
        val removed = mutableListOf<DownloadId>()
        val attempts = mutableListOf<Attempt>()
        val mirrors = mutableListOf<Pair<DownloadId, List<Mirror>>>()

        override fun event(event: EngineEvent) {
            events += event
        }

        override fun jobChanged(download: Download) {
            changed += download
        }

        override fun jobRemoved(id: DownloadId) {
            removed += id
        }

        override fun attemptChanged(attempt: Attempt) {
            attempts += attempt
        }

        override fun mirrorsChanged(
            id: DownloadId,
            mirrors: List<Mirror>,
        ) {
            this.mirrors += id to mirrors
        }

        val progress = mutableListOf<Long>()
        var received = 0L
        var checkpoints = 0
        val slots = mutableListOf<String>()

        override fun progress(
            id: DownloadId,
            downloaded: Long,
            total: Long?,
        ) {
            progress += downloaded
        }

        override fun received(
            id: DownloadId,
            bytes: Long,
        ) {
            received += bytes
        }

        override fun checkpointed(id: DownloadId) {
            checkpoints++
        }

        override fun slotStarted(id: DownloadId) {
            slots += "started ${id.value}"
        }

        override fun slotEnded(id: DownloadId) {
            slots += "ended ${id.value}"
        }
    }

    private suspend fun TestScope.observed(protocol: FakeProtocol = FakeProtocol()): Pair<QueueHarness, RecordingObserver> {
        val observer = RecordingObserver()
        return started { QueueHarness(it, newStore(), protocol, observer = observer) } to observer
    }

    @Test
    fun aRunReportsEveryStatusInOrder() =
        runTest {
            val (h, o) = observed()
            val a = h.addJob()
            settle()

            o.events shouldBe
                listOf(
                    EngineEvent.Added(a),
                    EngineEvent.StatusChanged(a, PENDING, QUEUED),
                    EngineEvent.StatusChanged(a, QUEUED, DOWNLOADING),
                    EngineEvent.StatusChanged(a, DOWNLOADING, MUXING),
                    EngineEvent.StatusChanged(a, MUXING, COMPLETED),
                    EngineEvent.Completed(a, listOf(Path(stepRoot, "job-1.zip"))),
                )
            o.changed.last().status shouldBe COMPLETED
        }

    @Test
    fun pauseAndRemoveAreReported() =
        runTest {
            val (h, o) = observed()
            val a = h.addJob()
            h.queue.pause(a)
            o.changed.last().status shouldBe PAUSED

            h.queue.remove(a, RemoveFiles.KEEP)

            o.removed shouldBe listOf(a)
            o.events.last() shouldBe EngineEvent.Removed(a)
        }

    @Test
    fun attemptsAreReported() =
        runTest {
            val (h, o) = observed()
            h.addJob()
            settle()

            o.attempts
                .map { it.id }
                .distinct()
                .size shouldBe 1
            o.attempts.first().endedAt shouldBe null
            (o.attempts.last().endedAt != null) shouldBe true
        }

    @Test
    fun aFailureWithNoClassIsReported() =
        runTest {
            val (h, o) = observed(FakeProtocol(failure = FakeFailure.AtProbe(StepResult.Failed(null, "nope"))))
            val a = h.addJob()
            settle()

            o.events shouldContain EngineEvent.Failed(a, null, "nope")
        }

    @Test
    fun aTransientFailureSchedulesARetry() =
        runTest {
            val (h, o) = observed(FakeProtocol(failure = FakeFailure.AtProbe(StepResult.Failed(ErrorClass.TRANSIENT, "reset"))))
            val a = h.addJob()
            advanceTimeBy(1.seconds)

            val retry = o.events.filterIsInstance<EngineEvent.RetryScheduled>().first()
            retry.id shouldBe a
            retry.attempt shouldBe 1
        }

    @Test
    fun aFullDiskIsReported() =
        runTest {
            val (h, o) = observed(FakeProtocol(failure = FakeFailure.AtProbe(StepResult.Failed(ErrorClass.DISK_FULL, "full"))))
            val a = h.addJob()
            settle()

            o.events shouldContain EngineEvent.PausedForDisk(a)
        }

    @Test
    fun aGoneMirrorReportsTheMove() =
        runTest {
            val (h, o) = observed(FakeProtocol(gone = setOf("https://one.example/f")))
            val a = h.addJob(mirrors = listOf("https://one.example/f", "https://two.example/f"))
            settle()

            val second = h.store.downloads.mirrors(a)[1]
            o.events shouldContain EngineEvent.MovedToMirror(a, second.id)
            h.status(a) shouldBe COMPLETED
        }

    @Test
    fun aChangedSourceIsReported() =
        runTest {
            val (h, o) = observed(FakeProtocol(validator = "a"))
            val a = h.addJob()
            advanceTimeBy(1.seconds)
            h.queue.pause(a)
            h.protocol.validator = "b"
            h.queue.resume(a)
            settle()

            o.events shouldContain EngineEvent.StartedOver(a, "source changed (validator \"a\" → \"b\"): starting from zero")
        }

    @Test
    fun aRelinkReportsTheMirrors() =
        runTest {
            val (h, o) = observed(FakeProtocol(relinkAnswer = StepResult.Ok(Unit)))
            val a = h.addJob()
            val mirror =
                h.store.downloads
                    .mirrors(a)
                    .single()

            h.queue.relink(a, mirror.id, "https://example.com/new")

            o.mirrors.last() shouldBe (a to listOf(mirror.copy(url = "https://example.com/new")))
        }

    @Test
    fun slotsReportProgressBytesAndCheckpoints() =
        runTest {
            val o = RecordingObserver()
            val h = started { QueueHarness(it, newStore(), observer = o, checkpointEvery = 1.seconds) }
            val a = h.addJob()
            settle()

            o.progress.last() shouldBe 64 * 1024L
            o.received shouldBe 64 * 1024L
            (o.checkpoints >= 1) shouldBe true
            o.slots shouldBe listOf("started ${a.value}", "ended ${a.value}")
        }

    @Test
    fun slotEndIsReportedAfterTheQueueStops() =
        runTest {
            val o = RecordingObserver()
            val scope = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
            val h = started { QueueHarness(it, newStore(), observer = o, scope = scope) }
            val a = h.addJob()
            advanceTimeBy(1.seconds)

            scope.cancel()
            runCurrent()

            o.slots shouldBe listOf("started ${a.value}", "ended ${a.value}")
        }
}
