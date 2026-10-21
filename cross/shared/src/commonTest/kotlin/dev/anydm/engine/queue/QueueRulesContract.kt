package dev.anydm.engine.queue

import dev.anydm.engine.EngineEvent
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.DownloadStatus.COMPLETED
import dev.anydm.engine.core.DownloadStatus.DOWNLOADING
import dev.anydm.engine.core.DownloadStatus.FAILED
import dev.anydm.engine.core.DownloadStatus.MUXING
import dev.anydm.engine.core.DownloadStatus.PAUSED
import dev.anydm.engine.core.DownloadStatus.SEEDING
import dev.anydm.engine.core.ErrorClass
import dev.anydm.engine.core.PauseReason
import dev.anydm.engine.net.RuleEffect
import dev.anydm.engine.platform.FakeFileSystem
import dev.anydm.engine.protocol.FakeFailure
import dev.anydm.engine.protocol.FakeFile
import dev.anydm.engine.protocol.FakeProtocol
import dev.anydm.engine.protocol.FinalizeResult
import dev.anydm.engine.protocol.Protocol
import dev.anydm.engine.protocol.StepResult
import dev.anydm.engine.protocol.Transfer
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/** What the queue does when the network rules pause, release and cap it (#543). */
abstract class QueueRulesContract : QueueTestBase() {
    private val pause = RuleEffect(true, null, null)
    private val low = Path("/dl/low")

    private val scheduled = setOf(PauseReason.SCHEDULE)

    @Test
    fun aRulePauseStopsARunningJob() =
        runTest {
            val h = started()
            val id = h.addJob()
            advanceTimeBy(1.seconds)
            h.status(id) shouldBe DOWNLOADING

            h.queue.applyRules(pause) shouldBe CommandResult.Done(null)

            h.status(id) shouldBe PAUSED
            h.reasons(id) shouldBe scheduled
            h.queue.inspect().running shouldBe emptySet()
        }

    @Test
    fun aRulePauseHoldsWaitingJobs() =
        runTest {
            val h = started(limit = 1)
            val ids = List(3) { h.addJob() }
            advanceTimeBy(1.seconds)

            h.queue.applyRules(pause)
            settle()

            for (id in ids) {
                h.status(id) shouldBe PAUSED
                h.reasons(id) shouldBe scheduled
            }
        }

    @Test
    fun aRulePauseAddsScheduleToAUserPausedJob() =
        runTest {
            val h = started()
            val id = h.addJob()
            advanceTimeBy(1.seconds)
            h.queue.pause(id)
            runCurrent()

            h.queue.applyRules(pause)

            h.status(id) shouldBe PAUSED
            h.reasons(id) shouldBe setOf(PauseReason.USER, PauseReason.SCHEDULE)
        }

    @Test
    fun aMuxingJobIsLeftToFinish() =
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
            val h = started { QueueHarness(it, newStore(), quick, mapOf(DownloadKind.SITE to gated, DownloadKind.DIRECT to quick)) }
            val id = h.addJob(kind = DownloadKind.SITE)
            advanceTimeBy(5.seconds)
            h.status(id) shouldBe MUXING

            h.queue.applyRules(pause)
            runCurrent()

            h.status(id) shouldBe MUXING
            gate.complete(Unit)
            runCurrent()
            h.status(id) shouldBe COMPLETED
        }

    @Test
    fun releaseKeepsOtherReasons() =
        runTest {
            val h = started()
            val user = h.addJob()
            val disk = h.addJob(folder = low)
            val plain = h.addJob()
            advanceTimeBy(1.seconds)
            h.queue.pause(user)
            h.queue.applyDiskState(setOf(low))
            runCurrent()

            h.queue.applyRules(pause)
            h.queue.applyRules(RuleEffect.None)
            runCurrent()

            h.reasons(user) shouldBe setOf(PauseReason.USER)
            h.reasons(disk) shouldBe setOf(PauseReason.LOW_DISK)
            h.status(plain) shouldBe DOWNLOADING

            h.queue.pauseAll()
            runCurrent()
            h.queue.applyRules(pause)
            h.queue.applyRules(RuleEffect.None)
            runCurrent()

            h.reasons(user) shouldBe setOf(PauseReason.USER, PauseReason.GLOBAL)
            h.reasons(disk) shouldBe setOf(PauseReason.LOW_DISK, PauseReason.GLOBAL)
            h.reasons(plain) shouldBe setOf(PauseReason.GLOBAL)
        }

    @Test
    fun aReleasedJobRunsToCompletion() =
        runTest {
            val h = started()
            val id = h.addJob()
            advanceTimeBy(1.seconds)
            h.queue.applyRules(pause)
            runCurrent()

            h.queue.applyRules(RuleEffect.None)
            settle()

            h.status(id) shouldBe COMPLETED
        }

    @Test
    fun addResumeAndRetryWhileRulesPauseArePausedForSchedule() =
        runTest {
            val protocol = FakeProtocol(failure = FakeFailure.AtProbe(StepResult.Failed(ErrorClass.GONE, "gone")))
            val h = started { QueueHarness(it, newStore(), protocol) }
            val failing = h.addJob()
            settle()
            h.status(failing) shouldBe FAILED
            protocol.failure = null
            h.queue.applyRules(pause)

            val added =
                h.queue
                    .add(newDownload())
                    .shouldBeInstanceOf<CommandResult.Done>()
                    .download!!
            added.status shouldBe PAUSED
            added.reasons() shouldBe scheduled

            val resumed =
                h.queue
                    .resume(added.id)
                    .shouldBeInstanceOf<CommandResult.Done>()
                    .download!!
            resumed.status shouldBe PAUSED
            resumed.reasons() shouldBe scheduled

            val retried =
                h.queue
                    .retry(failing)
                    .shouldBeInstanceOf<CommandResult.Done>()
                    .download!!
            retried.status shouldBe PAUSED
            retried.reasons() shouldBe scheduled
            settle()
            h.status(added.id) shouldBe PAUSED
            h.status(failing) shouldBe PAUSED
        }

    @Test
    fun aFailureWhileRulesPauseWaitsPaused() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val quick = FakeProtocol()
            val gated =
                object : Protocol by quick {
                    override suspend fun finalize(transfer: Transfer): StepResult<FinalizeResult> {
                        gate.await()
                        return StepResult.Failed(ErrorClass.TRANSIENT, "reset")
                    }
                }
            val h = started { QueueHarness(it, newStore(), quick, mapOf(DownloadKind.SITE to gated, DownloadKind.DIRECT to quick)) }
            val id = h.addJob(kind = DownloadKind.SITE)
            advanceTimeBy(5.seconds)
            h.status(id) shouldBe MUXING
            h.queue.applyRules(pause)

            gate.complete(Unit)
            runCurrent()

            h.status(id) shouldBe PAUSED
            h.reasons(id) shouldBe scheduled
        }

    @Test
    fun aJobReleasedFromAnotherReasonWhileRulesPauseIsHeld() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val quick = FakeProtocol()
            val gated =
                object : Protocol by quick {
                    override suspend fun finalize(transfer: Transfer): StepResult<FinalizeResult> {
                        gate.await()
                        return StepResult.Failed(ErrorClass.TOOL_MISSING, "ffmpeg")
                    }
                }
            val observer = ObserverContract.RecordingObserver()
            val h =
                started {
                    QueueHarness(
                        it,
                        newStore(),
                        quick,
                        mapOf(DownloadKind.SITE to gated, DownloadKind.DIRECT to quick),
                        observer = observer,
                    )
                }
            val id = h.addJob(kind = DownloadKind.SITE)
            advanceTimeBy(5.seconds)
            h.status(id) shouldBe MUXING
            h.queue.applyRules(pause)
            gate.complete(Unit)
            runCurrent()
            h.status(id) shouldBe PAUSED
            h.reasons(id) shouldBe setOf(PauseReason.TOOLS)
            observer.events.clear()

            h.queue.applyToolState()
            settle()

            h.status(id) shouldBe PAUSED
            h.reasons(id) shouldBe scheduled
            observer.events.filterIsInstance<EngineEvent.StatusChanged>().none { it.to == DOWNLOADING } shouldBe true
        }

    @Test
    fun applyingTheSameEffectTwiceChangesNothing() =
        runTest {
            val h = started(limit = 1)
            h.addJob()
            h.addJob()
            advanceTimeBy(1.seconds)
            h.queue.applyRules(pause)
            runCurrent()
            val before = h.store.downloads.list()

            h.queue.applyRules(pause)
            runCurrent()

            h.store.downloads.list() shouldBe before
        }

    @Test
    fun startWithAPauseLetsNothingRun() =
        runTest {
            val store = newStore()
            val fs = FakeFileSystem()
            val protocol = FakeProtocol()
            val first = childScope()
            val before = QueueHarness(this, store, protocol, scope = first, fs = fs)
            before.queue.start()
            val running = before.addJob()
            val waiting = before.addJob()
            // One slot at a time would hide a waiting job: two jobs, one limit.
            store.settings.update { it.copy(concurrencyLimit = 1) }
            advanceTimeBy(1.seconds)
            first.cancel()
            runCurrent()
            val observer = ObserverContract.RecordingObserver()

            val after = QueueHarness(this, store, protocol, clock = before.clock, fs = fs, observer = observer)
            after.queue.start(pause)
            advanceTimeBy(30.seconds)

            observer.events
                .filterIsInstance<EngineEvent.StatusChanged>()
                .filter { it.to == DOWNLOADING }
                .shouldBeEmpty()
            for (id in listOf(running, waiting)) {
                after.status(id) shouldBe PAUSED
                after.reasons(id) shouldBe scheduled
            }
        }

    @Test
    fun startWithoutAPauseReleasesAScheduleLeftFromTheLastRun() =
        runTest {
            val store = newStore()
            val fs = FakeFileSystem()
            val protocol = FakeProtocol()
            val first = childScope()
            val before = QueueHarness(this, store, protocol, scope = first, fs = fs)
            before.queue.start(pause)
            val id = before.addJob()
            runCurrent()
            before.reasons(id) shouldBe scheduled
            first.cancel()
            runCurrent()

            val after = QueueHarness(this, store, protocol, clock = before.clock, fs = fs)
            after.queue.start()
            settle()

            after.status(id) shouldBe COMPLETED
        }

    @Test
    fun theCapIsTheMinimumOfTheUsersLimitAndTheRules() =
        runTest {
            val h = started()
            h.queue.setGlobalSpeedLimit(8000)

            h.queue.applyRules(RuleEffect(false, 4000, 3000))

            h.protocol.globalLimits.last() shouldBe (4000L to 3000L)
            val settings = h.store.settings.load()
            settings.globalSpeedLimit shouldBe 8000L
            settings.globalUploadLimit shouldBe null
        }

    @Test
    fun setGlobalSpeedLimitUnderACapStillAppliesTheMin() =
        runTest {
            val h = started()
            h.queue.applyRules(RuleEffect(false, 4000, null))

            h.queue.setGlobalSpeedLimit(2000)
            h.protocol.globalLimits.last() shouldBe (2000L to null)

            h.queue.setGlobalSpeedLimit(null)
            h.protocol.globalLimits.last() shouldBe (4000L to null)
            h.store.settings
                .load()
                .globalSpeedLimit shouldBe null
        }

    @Test
    fun liftingTheCapRestoresTheUsersLimit() =
        runTest {
            val h = started()
            h.queue.setGlobalSpeedLimit(8000)
            h.queue.applyRules(RuleEffect(false, 4000, 3000))

            h.queue.applyRules(RuleEffect.None)

            h.protocol.globalLimits.last() shouldBe (8000L to null)
        }

    @Test
    fun aCapSlowsRunningJobsThroughTheSharedLimiter() =
        runTest {
            val observer = ObserverContract.RecordingObserver()
            val protocol = FakeProtocol(files = listOf(FakeFile("big.bin", 1L shl 20)), bytesPerSecond = 1L shl 20)
            val h = started(rules = RuleEffect(false, 4096, null)) { QueueHarness(it, newStore(), protocol, observer = observer) }
            h.addJob()

            advanceTimeBy(10.seconds)

            observer.received shouldBeLessThanOrEqual 49_152L
            (observer.received > 0) shouldBe true
        }

    @Test
    fun seedsAreNotPausedByARule() =
        runTest {
            val h = started()
            h.protocol.seeding = true
            val id = h.addJob()
            settle()
            h.status(id) shouldBe SEEDING

            h.queue.applyRules(pause)
            runCurrent()

            h.status(id) shouldBe SEEDING
        }

    private fun dev.anydm.engine.core.Download.reasons() = pauseReasons

    private fun newDownload() =
        dev.anydm.engine.store.NewDownload(
            DownloadKind.DIRECT,
            mirrors = listOf("https://example.com/added.zip"),
            saveFolder =
                dev.anydm.engine.core.stepRoot
                    .toString(),
            fileName = "added.zip",
        )
}
