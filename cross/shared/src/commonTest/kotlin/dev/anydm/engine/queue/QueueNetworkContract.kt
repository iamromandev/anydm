package dev.anydm.engine.queue

import dev.anydm.engine.EngineEvent
import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.DownloadStatus.COMPLETED
import dev.anydm.engine.core.DownloadStatus.DOWNLOADING
import dev.anydm.engine.core.DownloadStatus.FAILED
import dev.anydm.engine.core.DownloadStatus.MUXING
import dev.anydm.engine.core.DownloadStatus.PAUSED
import dev.anydm.engine.core.DownloadStatus.PENDING
import dev.anydm.engine.core.DownloadStatus.QUEUED
import dev.anydm.engine.core.DownloadStatus.SEEDING
import dev.anydm.engine.core.ErrorClass
import dev.anydm.engine.core.PauseReason
import dev.anydm.engine.core.stepRoot
import dev.anydm.engine.net.RuleEffect
import dev.anydm.engine.platform.FakeFileSystem
import dev.anydm.engine.protocol.FakeFailure
import dev.anydm.engine.protocol.FakeProtocol
import dev.anydm.engine.protocol.FinalizeResult
import dev.anydm.engine.protocol.Protocol
import dev.anydm.engine.protocol.StepResult
import dev.anydm.engine.protocol.Transfer
import dev.anydm.engine.store.NewDownload
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/** What the queue does when the network drops and returns, when connections restart, and when a failure is a drop (#543). */
abstract class QueueNetworkContract : QueueTestBase() {
    private val network = setOf(PauseReason.NETWORK)
    private val schedule = RuleEffect(true, null, null)
    private val low = Path("/dl/low")

    @Test
    fun aDropStopsARunningJobWithoutCountingIt() =
        runTest {
            val observer = ObserverContract.RecordingObserver()
            val h = started { QueueHarness(it, newStore(), observer = observer) }
            val id = h.addJob()
            advanceTimeBy(1.seconds)
            h.status(id) shouldBe DOWNLOADING

            h.queue.applyNetwork(true) shouldBe CommandResult.Done(null)

            h.status(id) shouldBe PAUSED
            h.reasons(id) shouldBe network
            h.job(id)!!.attemptCount shouldBe 0
            observer.events.filterIsInstance<EngineEvent.RetryScheduled>().shouldBeEmpty()
            h.queue.inspect().running shouldBe emptySet()
        }

    @Test
    fun aDropHoldsWaitingJobs() =
        runTest {
            val h = started(limit = 1)
            val ids = List(3) { h.addJob() }
            advanceTimeBy(1.seconds)

            h.queue.applyNetwork(true)
            settle()

            for (id in ids) {
                h.status(id) shouldBe PAUSED
                h.reasons(id) shouldBe network
            }
        }

    @Test
    fun aDropAddsNetworkToAUserPausedJob() =
        runTest {
            val h = started()
            val id = h.addJob()
            advanceTimeBy(1.seconds)
            h.queue.pause(id)
            runCurrent()

            h.queue.applyNetwork(true)

            h.status(id) shouldBe PAUSED
            h.reasons(id) shouldBe setOf(PauseReason.USER, PauseReason.NETWORK)
        }

    @Test
    fun aMuxingJobFinishesThroughADrop() =
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

            h.queue.applyNetwork(true)
            runCurrent()

            h.status(id) shouldBe MUXING
            gate.complete(Unit)
            runCurrent()
            h.status(id) shouldBe COMPLETED
        }

    @Test
    fun aSeedingJobIsLeftAlone() =
        runTest {
            val h = started()
            h.protocol.seeding = true
            val id = h.addJob()
            settle()
            h.status(id) shouldBe SEEDING

            h.queue.applyNetwork(true)
            runCurrent()

            h.status(id) shouldBe SEEDING
            h.queue.applyNetwork(false)
            runCurrent()
            h.status(id) shouldBe SEEDING
        }

    @Test
    fun theReturnReleasesOnlyNetwork() =
        runTest {
            val missingTool = FakeProtocol(failure = FakeFailure.AtProbe(StepResult.Failed(ErrorClass.TOOL_MISSING, "yt-dlp")))
            // Each reason on a queue of its own: pauseAll and a rule pause would otherwise touch every job at once.
            releasesOnlyNetwork(setOf(PauseReason.USER)) { h, id ->
                h.queue.pause(id)
            }
            releasesOnlyNetwork(setOf(PauseReason.GLOBAL)) { h, _ ->
                h.queue.pauseAll()
            }
            releasesOnlyNetwork(setOf(PauseReason.LOW_DISK), folder = low) { h, _ ->
                h.queue.applyDiskState(setOf(low))
            }
            releasesOnlyNetwork(setOf(PauseReason.TOOLS), protocol = missingTool) { _, _ -> }
            releasesOnlyNetwork(setOf(PauseReason.SCHEDULE)) { h, _ ->
                h.queue.applyRules(schedule)
            }
        }

    private suspend fun TestScope.releasesOnlyNetwork(
        own: Set<PauseReason>,
        folder: Path = stepRoot,
        protocol: FakeProtocol = FakeProtocol(),
        pauseOwn: suspend (QueueHarness, DownloadId) -> Unit,
    ) {
        val h = started { QueueHarness(it, newStore(), protocol) }
        val id = h.addJob(folder = folder)
        advanceTimeBy(1.seconds)
        pauseOwn(h, id)
        runCurrent()
        h.status(id) shouldBe PAUSED
        h.reasons(id) shouldBe own

        h.queue.applyNetwork(true)
        runCurrent()
        h.reasons(id) shouldBe own + PauseReason.NETWORK

        h.queue.applyNetwork(false)
        runCurrent()
        h.status(id) shouldBe PAUSED
        h.reasons(id) shouldBe own
    }

    @Test
    fun aReleasedJobRunsToCompletionWithNoAttemptCounted() =
        runTest {
            val h = started()
            val id = h.addJob()
            advanceTimeBy(1.seconds)
            h.queue.applyNetwork(true)
            runCurrent()

            h.queue.applyNetwork(false)
            settle()

            h.status(id) shouldBe COMPLETED
            h.job(id)!!.attemptCount shouldBe 0
            h.store.downloads
                .attempts(id)
                .map { it.errorClass }
                .all { it == null } shouldBe true
        }

    @Test
    fun applyingTheSameStateTwiceChangesNothing() =
        runTest {
            val h = started(limit = 1)
            h.addJob()
            h.addJob()
            advanceTimeBy(1.seconds)
            h.queue.applyNetwork(true)
            runCurrent()
            val before = h.store.downloads.list()

            h.queue.applyNetwork(true)
            runCurrent()

            h.store.downloads.list() shouldBe before
        }

    @Test
    fun addResumeAndRetryWhileOfflineArePausedForNetwork() =
        runTest {
            val protocol = FakeProtocol(failure = FakeFailure.AtProbe(StepResult.Failed(ErrorClass.GONE, "gone")))
            val h = started { QueueHarness(it, newStore(), protocol) }
            val failing = h.addJob()
            settle()
            h.status(failing) shouldBe FAILED
            protocol.failure = null
            h.queue.applyNetwork(true)

            val added =
                h.queue
                    .add(newDownload())
                    .shouldBeInstanceOf<CommandResult.Done>()
                    .download!!
            added.status shouldBe PAUSED
            added.pauseReasons shouldBe network

            val resumed =
                h.queue
                    .resume(added.id)
                    .shouldBeInstanceOf<CommandResult.Done>()
                    .download!!
            resumed.status shouldBe PAUSED
            resumed.pauseReasons shouldBe network

            val retried =
                h.queue
                    .retry(failing)
                    .shouldBeInstanceOf<CommandResult.Done>()
                    .download!!
            retried.status shouldBe PAUSED
            retried.pauseReasons shouldBe network
            settle()
            h.status(added.id) shouldBe PAUSED
            h.status(failing) shouldBe PAUSED
        }

    @Test
    fun startOfflineLetsNothingRun() =
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
            before.status(running) shouldBe DOWNLOADING
            first.cancel()
            runCurrent()
            val observer = ObserverContract.RecordingObserver()

            val after = QueueHarness(this, store, protocol, clock = before.clock, fs = fs, observer = observer)
            after.queue.start(RuleEffect.None, offline = true)
            advanceTimeBy(30.seconds)

            observer.events
                .filterIsInstance<EngineEvent.StatusChanged>()
                .filter { it.to == DOWNLOADING }
                .shouldBeEmpty()
            for (id in listOf(running, waiting)) {
                after.status(id) shouldBe PAUSED
                after.reasons(id) shouldBe network
            }
        }

    @Test
    fun startOnlineReleasesANetworkPauseLeftFromTheLastRun() =
        runTest {
            val store = newStore()
            val fs = FakeFileSystem()
            val protocol = FakeProtocol()
            val first = childScope()
            val before = QueueHarness(this, store, protocol, scope = first, fs = fs)
            before.queue.start(RuleEffect.None, offline = true)
            val id = before.addJob()
            runCurrent()
            before.reasons(id) shouldBe network
            first.cancel()
            runCurrent()

            val after = QueueHarness(this, store, protocol, clock = before.clock, fs = fs)
            after.queue.start()
            settle()

            after.status(id) shouldBe COMPLETED
        }

    @Test
    fun rulesAndNetworkPausesNeedBothGoneInBothOrders() =
        runTest {
            val h = started()
            val first = h.addJob()
            advanceTimeBy(1.seconds)
            h.queue.applyNetwork(true)
            h.queue.applyRules(schedule)
            runCurrent()
            h.reasons(first) shouldBe setOf(PauseReason.NETWORK, PauseReason.SCHEDULE)

            h.queue.applyNetwork(false)
            runCurrent()
            h.status(first) shouldBe PAUSED
            h.reasons(first) shouldBe setOf(PauseReason.SCHEDULE)

            h.queue.applyRules(RuleEffect.None)
            settle()
            h.status(first) shouldBe COMPLETED

            val second = h.addJob()
            advanceTimeBy(1.seconds)
            h.queue.applyRules(schedule)
            h.queue.applyNetwork(true)
            runCurrent()
            h.reasons(second) shouldBe setOf(PauseReason.NETWORK, PauseReason.SCHEDULE)

            h.queue.applyRules(RuleEffect.None)
            runCurrent()
            h.status(second) shouldBe PAUSED
            h.reasons(second) shouldBe network

            h.queue.applyNetwork(false)
            settle()
            h.status(second) shouldBe COMPLETED
        }

    @Test
    fun aPendingRetryKeepsItsTimeThroughADrop() =
        runTest {
            val protocol =
                FakeProtocol(
                    failure = FakeFailure.AtTransfer(16_384, StepResult.Failed(ErrorClass.RATE_LIMITED, "slow down", retryAfter = 1.hours)),
                )
            val h = started { QueueHarness(it, newStore(), protocol) }
            val id = h.addJob()
            advanceTimeBy(10.seconds)
            val retrying = h.job(id)!!
            retrying.attemptCount shouldBe 1
            val at = retrying.nextAttemptAt!!
            protocol.failure = null

            h.queue.applyNetwork(true)
            runCurrent()
            h.status(id) shouldBe PAUSED
            h.job(id)!!.nextAttemptAt shouldBe at
            h.job(id)!!.attemptCount shouldBe 1

            h.queue.applyNetwork(false)
            runCurrent()
            h.job(id)!!.nextAttemptAt shouldBe at
            h.job(id)!!.attemptCount shouldBe 1

            advanceTimeBy(at - h.clock.now() - 1.seconds)
            h.status(id) shouldBe QUEUED
            advanceTimeBy(2.seconds)
            h.status(id) shouldBe DOWNLOADING
        }

    @Test
    fun aProbeInFlightIsStoppedAndProbedAgain() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val protocol = FakeProtocol(probeGate = gate)
            val h = started { QueueHarness(it, newStore(), protocol) }
            val id = h.addJob()
            runCurrent()
            h.status(id) shouldBe PENDING
            protocol.probed shouldHaveSize 1

            h.queue.applyNetwork(true)
            runCurrent()
            h.status(id) shouldBe PAUSED
            h.reasons(id) shouldBe network

            h.queue.applyNetwork(false)
            runCurrent()
            protocol.probed shouldHaveSize 2

            gate.complete(Unit)
            settle()
            h.status(id) shouldBe COMPLETED
        }

    @Test
    fun restartConnectionsRestartsWithoutCounting() =
        runTest {
            val h = started()
            val id = h.addJob()
            advanceTimeBy(1.seconds)
            h.protocol.transfers shouldHaveSize 1

            h.queue.restartConnections() shouldBe CommandResult.Done(null)
            runCurrent()
            h.protocol.transfers shouldHaveSize 2
            settle()

            h.status(id) shouldBe COMPLETED
            h.job(id)!!.attemptCount shouldBe 0
            val first =
                h.store.downloads
                    .attempts(id)
                    .first()
            first.message shouldBe "stopped"
            first.errorClass shouldBe null
        }

    @Test
    fun restartKeepsTheSameJobsRunning() =
        runTest {
            val h = started(limit = 1)
            val a = h.addJob()
            val b = h.addJob()
            advanceTimeBy(1.seconds)
            h.status(a) shouldBe DOWNLOADING

            h.queue.restartConnections()
            runCurrent()

            h.status(a) shouldBe DOWNLOADING
            h.status(b) shouldBe PENDING
            h.queue.inspect().running shouldBe setOf(a)
        }

    @Test
    fun restartSkipsASlotAlreadyStopping() =
        runTest {
            val protocol = FakeProtocol(stopDelay = 2.seconds)
            val h = started { QueueHarness(it, newStore(), protocol) }
            val id = h.addJob()
            advanceTimeBy(1.seconds)
            val paused = async { h.queue.pause(id) }
            runCurrent()

            // Nothing to restart: it answers at once, not when the stopping slot has ended.
            val restarted = async { h.queue.restartConnections() }
            runCurrent()
            restarted.isCompleted shouldBe true
            paused.isCompleted shouldBe false
            advanceTimeBy(3.seconds)
            paused.await()

            h.status(id) shouldBe PAUSED
            h.reasons(id) shouldBe setOf(PauseReason.USER)
            protocol.transfers shouldHaveSize 1
        }

    @Test
    fun restartLeavesMuxing() =
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

            h.queue.restartConnections()
            runCurrent()

            h.status(id) shouldBe MUXING
            quick.transfers shouldHaveSize 1
            gate.complete(Unit)
            runCurrent()
            h.status(id) shouldBe COMPLETED
        }

    @Test
    fun aTransientFailureWithTheNetworkGoneIsANetworkPause() =
        runTest {
            val observer = ObserverContract.RecordingObserver()
            var checks = 0
            val protocol = FakeProtocol(failure = FakeFailure.AtTransfer(16_384, StepResult.Failed(ErrorClass.TRANSIENT, "reset")))
            val h =
                started {
                    QueueHarness(it, newStore(), protocol, observer = observer, networkCheck = { false.also { checks++ } })
                }
            val id = h.addJob()
            advanceTimeBy(10.seconds)

            h.status(id) shouldBe PAUSED
            h.reasons(id) shouldBe network
            h.job(id)!!.attemptCount shouldBe 0
            observer.events.filterIsInstance<EngineEvent.RetryScheduled>().shouldBeEmpty()
            checks shouldBe 1
        }

    @Test
    fun aTransientFailureWithTheNetworkUpCountsAsToday() =
        runTest {
            val observer = ObserverContract.RecordingObserver()
            var checks = 0
            val protocol = FakeProtocol(failure = FakeFailure.AtTransfer(16_384, StepResult.Failed(ErrorClass.TRANSIENT, "reset")))
            val h =
                started {
                    QueueHarness(it, newStore(), protocol, observer = observer, networkCheck = { true.also { checks++ } })
                }
            val id = h.addJob()
            advanceTimeBy(2.seconds)

            h.job(id)!!.attemptCount shouldBe 1
            observer.events.filterIsInstance<EngineEvent.RetryScheduled>() shouldHaveSize 1
            checks shouldBe 1
        }

    @Test
    fun aThrowingCheckCountsAsOnline() =
        runTest {
            val protocol = FakeProtocol(failure = FakeFailure.AtTransfer(16_384, StepResult.Failed(ErrorClass.TRANSIENT, "reset")))
            val h = started { QueueHarness(it, newStore(), protocol, networkCheck = { error("no route table") }) }
            val id = h.addJob()
            advanceTimeBy(10.seconds)

            h.job(id)!!.attemptCount shouldBe 1
            h.status(id).let { it == QUEUED || it == PENDING } shouldBe true
        }

    @Test
    fun aFailedCheckHoldsLaterAdds() =
        runTest {
            val protocol = FakeProtocol(failure = FakeFailure.AtTransfer(16_384, StepResult.Failed(ErrorClass.TRANSIENT, "reset")))
            val h = started { QueueHarness(it, newStore(), protocol, networkCheck = { false }) }
            val id = h.addJob()
            advanceTimeBy(10.seconds)
            h.status(id) shouldBe PAUSED

            val added =
                h.queue
                    .add(newDownload())
                    .shouldBeInstanceOf<CommandResult.Done>()
                    .download!!

            added.status shouldBe PAUSED
            added.pauseReasons shouldBe network
        }

    @Test
    fun aFailureWhileOfflineNeedsNoCheck() =
        runTest {
            var checks = 0
            val gate = CompletableDeferred<Unit>()
            val quick = FakeProtocol()
            val gated =
                object : Protocol by quick {
                    override suspend fun finalize(transfer: Transfer): StepResult<FinalizeResult> {
                        gate.await()
                        return StepResult.Failed(ErrorClass.TRANSIENT, "reset")
                    }
                }
            val h =
                started {
                    QueueHarness(
                        it,
                        newStore(),
                        quick,
                        mapOf(DownloadKind.SITE to gated, DownloadKind.DIRECT to quick),
                        networkCheck = { true.also { checks++ } },
                    )
                }
            val id = h.addJob(kind = DownloadKind.SITE)
            advanceTimeBy(5.seconds)
            h.status(id) shouldBe MUXING
            h.queue.applyNetwork(true)

            gate.complete(Unit)
            runCurrent()

            h.status(id) shouldBe PAUSED
            h.reasons(id) shouldBe network
            h.job(id)!!.attemptCount shouldBe 0
            checks shouldBe 0
        }

    @Test
    fun aJobReleasedFromAnotherReasonWhileOfflineIsHeld() =
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
            h.queue.applyNetwork(true)
            gate.complete(Unit)
            runCurrent()
            h.status(id) shouldBe PAUSED
            h.reasons(id) shouldBe setOf(PauseReason.TOOLS)
            observer.events.clear()

            h.queue.applyToolState()
            settle()

            h.status(id) shouldBe PAUSED
            h.reasons(id) shouldBe network
            observer.events.filterIsInstance<EngineEvent.StatusChanged>().none { it.to == DOWNLOADING } shouldBe true
        }

    @Test
    fun aDropFailureUnderARulePauseIsPausedForBoth() =
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
            h.queue.applyNetwork(true)
            h.queue.applyRules(schedule)

            gate.complete(Unit)
            runCurrent()

            h.status(id) shouldBe PAUSED
            h.reasons(id) shouldBe setOf(PauseReason.NETWORK, PauseReason.SCHEDULE)
            h.job(id)!!.attemptCount shouldBe 0
        }

    private fun newDownload() =
        NewDownload(
            DownloadKind.DIRECT,
            mirrors = listOf("https://example.com/added.zip"),
            saveFolder = stepRoot.toString(),
            fileName = "added.zip",
        )
}
