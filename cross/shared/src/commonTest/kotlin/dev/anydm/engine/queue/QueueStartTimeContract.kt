package dev.anydm.engine.queue

import dev.anydm.engine.core.CollectionKind
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.DownloadStatus.CANCELLED
import dev.anydm.engine.core.DownloadStatus.COMPLETED
import dev.anydm.engine.core.DownloadStatus.DOWNLOADING
import dev.anydm.engine.core.DownloadStatus.FAILED
import dev.anydm.engine.core.DownloadStatus.PAUSED
import dev.anydm.engine.core.DownloadStatus.PENDING
import dev.anydm.engine.core.DownloadStatus.QUEUED
import dev.anydm.engine.core.DownloadStatus.SEEDING
import dev.anydm.engine.core.ErrorClass
import dev.anydm.engine.core.PauseReason
import dev.anydm.engine.core.TransitionResult
import dev.anydm.engine.core.stepRoot
import dev.anydm.engine.net.RuleEffect
import dev.anydm.engine.platform.FakeFileSystem
import dev.anydm.engine.platform.ShiftedClock
import dev.anydm.engine.platform.virtualClock
import dev.anydm.engine.protocol.FakeFailure
import dev.anydm.engine.protocol.FakeProtocol
import dev.anydm.engine.protocol.StepResult
import dev.anydm.engine.store.NewDownload
import dev.anydm.engine.store.NewItem
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** What the queue does with a download's start time (NET-09), on a new database from [newDriver] for each test. */
abstract class QueueStartTimeContract : QueueTestBase() {
    @Test
    fun aJobWithAStartTimeTakesNoSlotUntilThen() =
        runTest {
            val h = started(limit = 1)
            val at = h.clock.now() + 90.seconds
            val a = h.addJob(startAt = at)
            val b = h.addJob()

            advanceTimeBy(1.seconds)
            h.status(b) shouldBe DOWNLOADING
            advanceTimeBy(88.seconds)
            h.status(a) shouldBe PENDING
            advanceTimeBy(1.seconds)
            runCurrent()

            h.status(a) shouldBe DOWNLOADING
        }

    @Test
    fun clearingTheStartTimeStartsItAtOnce() =
        runTest {
            val h = started()
            val a = h.addJob(startAt = h.clock.now() + 1.hours)
            advanceTimeBy(1.seconds)
            h.status(a) shouldBe PENDING

            h.queue.setStartTime(a, null).shouldBeInstanceOf<CommandResult.Done>()
            advanceTimeBy(1.seconds)

            h.status(a) shouldBe DOWNLOADING
            h.job(a)!!.startAt shouldBe null
        }

    @Test
    fun aPastStartTimeIsReadyAtOnce() =
        runTest {
            val h = started()
            val a = h.addJob(startAt = h.clock.now() + 1.hours)
            advanceTimeBy(1.seconds)

            h.queue.setStartTime(a, h.clock.now() - 1.hours).shouldBeInstanceOf<CommandResult.Done>()
            advanceTimeBy(1.seconds)

            h.status(a) shouldBe DOWNLOADING
        }

    @Test
    fun aFutureStartTimeStopsARunningJobAndItWaits() =
        runTest {
            val h = started()
            val a = h.addJob()
            advanceTimeBy(1.seconds)
            h.status(a) shouldBe DOWNLOADING
            val at = h.clock.now() + 60.seconds

            val result = h.queue.setStartTime(a, at).shouldBeInstanceOf<CommandResult.Done>()

            result.download!!.startAt shouldBe at
            h.status(a) shouldBe QUEUED
            h.job(a)!!.startAt shouldBe at
            h.queue.inspect().running shouldBe emptySet()
            advanceTimeBy(59.seconds)
            h.status(a) shouldBe QUEUED
            advanceTimeBy(2.seconds)
            h.status(a) shouldBe DOWNLOADING
        }

    @Test
    fun aPastStartTimeOnARunningJobOnlyStoresIt() =
        runTest {
            val h = started()
            val a = h.addJob()
            advanceTimeBy(1.seconds)
            val at = h.clock.now() - 1.seconds

            h.queue.setStartTime(a, at).shouldBeInstanceOf<CommandResult.Done>()

            h.status(a) shouldBe DOWNLOADING
            h.job(a)!!.startAt shouldBe at
            settle()
            h.status(a) shouldBe COMPLETED
            h.job(a)!!.startAt shouldBe at
        }

    @Test
    fun finishedJobsRejectItAndUnknownIsNotFound() =
        runTest {
            val gone = FakeProtocol(failure = FakeFailure.AtProbe(StepResult.Failed(ErrorClass.GONE, "gone")))
            val h = started { QueueHarness(it, newStore(), gone) }
            val failed = h.addJob()
            settle()
            h.status(failed) shouldBe FAILED
            gone.failure = null
            val done = h.addJob()
            settle()
            h.status(done) shouldBe COMPLETED
            val cancelled = h.addJob(startAt = h.clock.now() + 1.hours)
            h.queue.cancel(cancelled)
            runCurrent()
            h.status(cancelled) shouldBe CANCELLED
            val at = h.clock.now() + 1.hours

            h.queue.setStartTime(done, at) shouldBe CommandResult.Rejected(TransitionResult.Rejected(COMPLETED, QUEUED))
            h.queue.setStartTime(cancelled, at) shouldBe CommandResult.Rejected(TransitionResult.Rejected(CANCELLED, QUEUED))
            h.queue.setStartTime(
                dev.anydm.engine.core
                    .DownloadId(999),
                at,
            ) shouldBe CommandResult.NotFound
            h.job(done)!!.startAt shouldBe null
            // A failed job isn't finished: the time is kept for its retry.
            h.queue.setStartTime(failed, at).shouldBeInstanceOf<CommandResult.Done>()
            h.job(failed)!!.startAt shouldBe at
        }

    @Test
    fun aSeedRejectsIt() =
        runTest {
            val h = started()
            h.protocol.seeding = true
            val id = h.addJob()
            settle()
            h.status(id) shouldBe SEEDING

            h.queue.setStartTime(id, h.clock.now() + 1.hours) shouldBe CommandResult.Rejected(TransitionResult.Rejected(SEEDING, QUEUED))
        }

    @Test
    fun aCollectionGivesItToEachItemAndItsRow() =
        runTest {
            val h = started()
            val parent = NewDownload(DownloadKind.DIRECT, collection = CollectionKind.PLAYLIST)
            val children =
                (1..2).map {
                    NewDownload(
                        DownloadKind.DIRECT,
                        mirrors = listOf("https://example.com/v$it"),
                        saveFolder = stepRoot.toString(),
                        fileName = "v$it.zip",
                        playlistIndex = it,
                    )
                }
            val added = h.queue.addCollection(parent, children, (1..2).map { NewItem("x", "v$it", it, "V$it", 1) })!!
            advanceTimeBy(1.seconds)
            val at = h.clock.now() + 1.hours

            h.queue.setStartTime(added.collection.id, at).shouldBeInstanceOf<CommandResult.Done>()
            advanceTimeBy(1.seconds)

            for (id in added.children.map { it.id } + added.collection.id) h.job(id)!!.startAt shouldBe at
            for (child in added.children) h.status(child.id) shouldBe QUEUED
            h.queue.inspect().running shouldBe emptySet()
            advanceTimeBy(1.hours)
            runCurrent()
            for (child in added.children) h.status(child.id) shouldBe DOWNLOADING
        }

    @Test
    fun aWallClockMovedPastItStartsItWithinAMinute() =
        runTest {
            val shifted = ShiftedClock(virtualClock())
            val h = started { QueueHarness(it, newStore(), clock = shifted) }
            val a = h.addJob(startAt = shifted.now() + 2.hours)
            advanceTimeBy(1.seconds)
            h.status(a) shouldBe PENDING

            shifted.offset += 2.hours
            advanceTimeBy(59.seconds)
            runCurrent()

            h.status(a) shouldBe DOWNLOADING
        }

    @Test
    fun itSurvivesARestart() =
        runTest {
            val store = newStore()
            val fs = FakeFileSystem()
            val protocol = FakeProtocol()
            val first = childScope()
            val before = QueueHarness(this, store, protocol, scope = first, fs = fs)
            before.queue.start()
            val at = before.clock.now() + 1.hours
            val a = before.addJob(startAt = at)
            advanceTimeBy(1.seconds)
            first.cancel()
            runCurrent()

            val after = QueueHarness(this, store, protocol, clock = before.clock, fs = fs)
            after.queue.start()
            advanceTimeBy(30.minutes)

            after.status(a) shouldBe PENDING
            after.job(a)!!.startAt shouldBe at
            advanceTimeBy(30.minutes)
            runCurrent()
            after.status(a) shouldBe DOWNLOADING
        }

    @Test
    fun resumeAndRetryKeepIt() =
        runTest {
            val gone = FakeProtocol(failure = FakeFailure.AtProbe(StepResult.Failed(ErrorClass.GONE, "gone")))
            val h = started { QueueHarness(it, newStore(), gone) }
            val at = h.clock.now() + 1.hours
            val paused = h.addJob(startAt = at)
            h.queue.pause(paused)
            runCurrent()
            h.status(paused) shouldBe PAUSED

            h.queue.resume(paused).shouldBeInstanceOf<CommandResult.Done>()
            runCurrent()
            h.job(paused)!!.startAt shouldBe at

            val failed = h.addJob()
            settle()
            h.status(failed) shouldBe FAILED
            h.queue.setStartTime(failed, at)
            gone.failure = null

            h.queue.retry(failed).shouldBeInstanceOf<CommandResult.Done>()
            runCurrent()
            h.job(failed)!!.startAt shouldBe at
            h.status(failed) shouldBe QUEUED
        }

    @Test
    fun aRunKeepsIt() =
        runTest {
            val h = started()
            val at = h.clock.now() + 10.seconds
            val a = h.addJob(startAt = at)

            settle()

            h.status(a) shouldBe COMPLETED
            h.job(a)!!.startAt shouldBe at
        }

    @Test
    fun aStartTimeDoesNotOverrideARulePause() =
        runTest {
            val h = started()
            h.queue.applyRules(RuleEffect(true, null, null))
            val a = h.addJob(startAt = h.clock.now() + 10.seconds)
            h.status(a) shouldBe PAUSED

            advanceTimeBy(30.seconds)
            h.status(a) shouldBe PAUSED
            h.reasons(a) shouldBe setOf(PauseReason.SCHEDULE)

            h.queue.applyRules(RuleEffect.None)
            settle()
            h.status(a) shouldBe COMPLETED
        }
}
