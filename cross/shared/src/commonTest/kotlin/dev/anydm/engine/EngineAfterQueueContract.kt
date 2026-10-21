package dev.anydm.engine

import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.DownloadStatus
import dev.anydm.engine.core.ErrorClass
import dev.anydm.engine.core.PauseReason
import dev.anydm.engine.core.stepRoot
import dev.anydm.engine.net.AfterQueueAction
import dev.anydm.engine.net.AfterQueueState
import dev.anydm.engine.net.NewScheduleRule
import dev.anydm.engine.net.RuleAction
import dev.anydm.engine.platform.FakeOnlineProbe
import dev.anydm.engine.platform.FakePowerActions
import dev.anydm.engine.platform.virtualClock
import dev.anydm.engine.protocol.FakeFailure
import dev.anydm.engine.protocol.FakeFile
import dev.anydm.engine.protocol.FakeProtocol
import dev.anydm.engine.protocol.StepResult
import dev.anydm.engine.queue.CommandResult
import dev.anydm.engine.store.NewDownload
import dev.anydm.engine.store.StoreTestBase
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DayOfWeek
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The one-shot after-queue action in the engine (#543, NET-10): armed while work runs it counts down 30 s after the queue
 * is finished and then sleeps or shuts down, once. A 64 KiB file at 16 KiB/s (4 s a job) unless a test says otherwise, on a
 * new database from [newDriver] for each test. [FakePowerActions] stands in for the machine: nothing here can sleep it.
 */
abstract class EngineAfterQueueContract : StoreTestBase() {
    private val harnesses = mutableListOf<EngineHarness>()

    // A failed assertion must not leave an engine's endless timers for runTest to drain.
    private fun engineTest(body: suspend TestScope.() -> Unit) =
        runTest {
            try {
                body()
            } finally {
                harnesses.forEach { it.engine.stop() }
                harnesses.clear()
            }
        }

    private class Fixture(
        val h: EngineHarness,
        val power: FakePowerActions,
        val probe: FakeOnlineProbe,
        /** Every event with the instant it was told. */
        val events: MutableList<Pair<Instant, EngineEvent>>,
    ) {
        inline fun <reified T : EngineEvent> told(): List<T> = events.map { it.second }.filterIsInstance<T>()
    }

    private fun TestScope.fixture(
        power: FakePowerActions = FakePowerActions(),
        protocol: FakeProtocol = FakeProtocol(),
    ): Fixture {
        val clock = virtualClock()
        val probe = FakeOnlineProbe()
        val h =
            EngineHarness(this, newStore(), protocol, clock = clock, powerActions = power, onlineProbe = probe)
                .also { harnesses += it }
        val events = mutableListOf<Pair<Instant, EngineEvent>>()
        // Unconfined: an event is noted at the moment it is told, so a test can see what came before a power call.
        backgroundScope.launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            h.engine.events.collect { events += clock.now() to it }
        }
        return Fixture(h, power, probe, events)
    }

    private suspend fun TestScope.advance(by: Duration) {
        advanceTimeBy(by)
        runCurrent()
    }

    // Moves time in small steps until the job has [status]; false if it never does within [limit].
    private suspend fun TestScope.until(
        h: EngineHarness,
        id: DownloadId,
        status: DownloadStatus,
        limit: Duration = 2.minutes,
    ): Boolean {
        var spent = Duration.ZERO
        while (h.status(id) != status) {
            if (spent >= limit) return false
            advance(100.milliseconds)
            spent += 100.milliseconds
        }
        return true
    }

    private suspend fun EngineHarness.addLater(at: Instant): DownloadId =
        engine
            .add(
                NewDownload(
                    DownloadKind.DIRECT,
                    mirrors = listOf("https://example.com/later-${at.toEpochMilliseconds()}.zip"),
                    saveFolder = stepRoot.toString(),
                    fileName = "later-${at.toEpochMilliseconds()}.zip",
                    startAt = at,
                ),
            ).shouldBeInstanceOf<CommandResult.Done>()
            .download!!
            .id

    @Test
    fun armedWhileAJobRunsFiresThirtySecondsAfterItCompletes() =
        engineTest {
            val f = fixture()
            f.h.engine.start()
            val id = f.h.addJob()
            f.h.engine.setAfterQueue(AfterQueueAction.SLEEP) shouldBe CommandResult.Done(null)
            runCurrent()
            f.h.engine.afterQueue.value shouldBe AfterQueueState(AfterQueueAction.SLEEP, null)
            f.power.before = { _ ->
                f.h.store.settings
                    .load()
                    .afterQueue shouldBe AfterQueueAction.NONE
                f.told<EngineEvent.AfterQueueDone>() shouldBe listOf(EngineEvent.AfterQueueDone(AfterQueueAction.SLEEP))
            }

            until(f.h, id, DownloadStatus.COMPLETED) shouldBe true
            val completedAt = f.h.clock.now()

            val countdown = f.told<EngineEvent.AfterQueueCountdown>().single()
            countdown shouldBe EngineEvent.AfterQueueCountdown(AfterQueueAction.SLEEP, completedAt + 30.seconds)
            f.h.engine.afterQueue.value.countdownEndsAt shouldBe completedAt + 30.seconds

            advanceTimeBy(29_999.milliseconds)
            runCurrent()
            f.power.calls.shouldBeEmpty()
            f.told<EngineEvent.AfterQueueDone>().shouldBeEmpty()

            advance(1.milliseconds)

            f.power.calls shouldBe listOf("sleep")
            f.told<EngineEvent.AfterQueueDone>().single().action shouldBe AfterQueueAction.SLEEP
            f.h.store.settings
                .load()
                .afterQueue shouldBe AfterQueueAction.NONE
            f.h.engine.afterQueue.value shouldBe AfterQueueState.Idle

            // Once only: another busy-then-idle cycle starts nothing.
            val again = f.h.addJob()
            until(f.h, again, DownloadStatus.COMPLETED) shouldBe true
            advance(2.minutes)
            f.power.calls shouldBe listOf("sleep")
            f.told<EngineEvent.AfterQueueCountdown>().size shouldBe 1
        }

    @Test
    fun armingAnIdleQueueWaitsForWork() =
        engineTest {
            val f = fixture()
            f.h.engine.start()
            f.h.engine.setAfterQueue(AfterQueueAction.SLEEP)

            advance(2.minutes)

            f.told<EngineEvent.AfterQueueCountdown>().shouldBeEmpty()
            f.power.calls.shouldBeEmpty()
            f.h.engine.afterQueue.value shouldBe AfterQueueState(AfterQueueAction.SLEEP, null)

            val id = f.h.addJob()
            until(f.h, id, DownloadStatus.COMPLETED) shouldBe true
            advance(31.seconds)

            f.told<EngineEvent.AfterQueueCountdown>().size shouldBe 1
            f.power.calls shouldBe listOf("sleep")
        }

    @Test
    fun aJobAddedDuringTheCountdownCancelsAndStaysArmed() =
        engineTest {
            val f = fixture()
            f.h.engine.start()
            val first = f.h.addJob()
            f.h.engine.setAfterQueue(AfterQueueAction.SLEEP)
            until(f.h, first, DownloadStatus.COMPLETED) shouldBe true
            val countdownAt = f.h.clock.now()

            advance(10.seconds)
            val second = f.h.addJob()
            runCurrent()

            f.told<EngineEvent.AfterQueueCancelled>() shouldBe listOf(EngineEvent.AfterQueueCancelled(AfterQueueAction.SLEEP, false))
            f.h.engine.afterQueue.value shouldBe AfterQueueState(AfterQueueAction.SLEEP, null)
            f.power.calls.shouldBeEmpty()

            // The first countdown would have ended at +30 s: it must not.
            advanceTimeBy(countdownAt + 31.seconds - f.h.clock.now())
            runCurrent()
            f.power.calls.shouldBeEmpty()

            // The second job ended at about +14 s and started the second countdown, which fires once.
            val countdowns = f.told<EngineEvent.AfterQueueCountdown>()
            countdowns.size shouldBe 2
            val secondDoneAt = f.events.first { (it.second as? EngineEvent.Completed)?.id == second }.first
            countdowns[1] shouldBe EngineEvent.AfterQueueCountdown(AfterQueueAction.SLEEP, secondDoneAt + 30.seconds)

            advanceTimeBy(countdowns[1].endsAt - f.h.clock.now())
            runCurrent()
            f.power.calls shouldBe listOf("sleep")
        }

    @Test
    fun cancelDuringTheCountdownDisarms() =
        engineTest {
            val f = fixture()
            f.h.engine.start()
            val id = f.h.addJob()
            f.h.engine.setAfterQueue(AfterQueueAction.SLEEP)
            until(f.h, id, DownloadStatus.COMPLETED) shouldBe true
            advance(10.seconds)

            f.h.engine.cancelAfterQueue() shouldBe CommandResult.Done(null)
            runCurrent()

            f.told<EngineEvent.AfterQueueCancelled>() shouldBe listOf(EngineEvent.AfterQueueCancelled(AfterQueueAction.SLEEP, true))
            f.h.engine.afterQueue.value shouldBe AfterQueueState.Idle
            f.h.store.settings
                .load()
                .afterQueue shouldBe AfterQueueAction.NONE

            advance(2.minutes)
            f.power.calls.shouldBeEmpty()
        }

    @Test
    fun cancelWithNothingArmedIsDone() =
        engineTest {
            val f = fixture()
            f.h.engine.start()

            f.h.engine.cancelAfterQueue() shouldBe CommandResult.Done(null)
            f.h.engine.setAfterQueue(AfterQueueAction.NONE) shouldBe CommandResult.Done(null)
            runCurrent()

            f.told<EngineEvent.AfterQueueCancelled>().shouldBeEmpty()
            f.h.engine.afterQueue.value shouldBe AfterQueueState.Idle
        }

    @Test
    fun aJobWaitingForARetryOrAStartTimeHoldsItOff() =
        engineTest {
            val f = fixture()
            f.h.protocol.failure =
                FakeFailure.AtTransfer(16_384, StepResult.Failed(ErrorClass.RATE_LIMITED, "slow down", retryAfter = 1.hours))
            f.h.engine.start()
            val retrying = f.h.addJob()
            f.h.engine.setAfterQueue(AfterQueueAction.SLEEP)
            advance(10.seconds)
            f.told<EngineEvent.RetryScheduled>().size shouldBe 1

            // The failed job waits for its retry in an hour: the queue is not finished.
            advance(2.minutes)
            f.told<EngineEvent.AfterQueueCountdown>().shouldBeEmpty()

            f.h.engine.cancel(retrying)
            runCurrent()
            f.told<EngineEvent.AfterQueueCountdown>().size shouldBe 1
            f.h.engine.cancelAfterQueue()
            f.h.protocol.failure = null

            // A job that waits for its start time holds it off too, while another one runs and ends.
            val later = f.h.addLater(f.h.clock.now() + 1.hours)
            val quick = f.h.addJob()
            f.h.engine.setAfterQueue(AfterQueueAction.SLEEP)
            until(f.h, quick, DownloadStatus.COMPLETED) shouldBe true
            advance(2.minutes)
            f.told<EngineEvent.AfterQueueCountdown>().size shouldBe 1

            f.h.engine.cancel(later)
            runCurrent()
            f.told<EngineEvent.AfterQueueCountdown>().size shouldBe 2
        }

    @Test
    fun userPausedAndSeedingDoNotHoldItOff() =
        engineTest {
            val f = fixture(protocol = FakeProtocol(seeding = true))
            f.h.engine.start()
            val parked = f.h.addJob()
            f.h.engine.pause(parked)
            val seed = f.h.addJob()
            f.h.engine.setAfterQueue(AfterQueueAction.SLEEP)

            until(f.h, seed, DownloadStatus.SEEDING) shouldBe true

            f.h
                .snapshot(parked)!!
                .download.pauseReasons shouldBe setOf(PauseReason.USER)
            f.told<EngineEvent.AfterQueueCountdown>().size shouldBe 1
            advance(30.seconds)
            f.power.calls shouldBe listOf("sleep")
        }

    @Test
    fun pauseAllStartsTheCountdown() =
        engineTest {
            val f = fixture()
            f.h.engine.start()
            f.h.addJob()
            f.h.engine.setAfterQueue(AfterQueueAction.SLEEP)
            advance(1.seconds)

            f.h.engine.pauseAll()
            runCurrent()

            f.told<EngineEvent.AfterQueueCountdown>().size shouldBe 1
            advance(30.seconds)
            f.power.calls shouldBe listOf("sleep")
        }

    @Test
    fun aNetworkPauseHoldsItOff() =
        engineTest {
            val f = fixture(protocol = FakeProtocol(files = listOf(FakeFile("file.zip", 1L shl 20))))
            f.h.engine.start()
            val id = f.h.addJob()
            f.h.engine.setAfterQueue(AfterQueueAction.SLEEP)
            advance(1.seconds)

            f.probe.online = false
            advance(10.seconds)
            f.h
                .snapshot(id)!!
                .download.pauseReasons shouldBe setOf(PauseReason.NETWORK)

            advance(60.seconds)

            f.told<EngineEvent.AfterQueueCountdown>().shouldBeEmpty()
            f.power.calls.shouldBeEmpty()
        }

    @Test
    fun aUserPauseOnANetworkPausedJobEndsTheBusyStateWithoutAStatusChange() =
        engineTest {
            val f = fixture(protocol = FakeProtocol(files = listOf(FakeFile("file.zip", 1L shl 20))))
            f.h.engine.start()
            val id = f.h.addJob()
            f.h.engine.setAfterQueue(AfterQueueAction.SLEEP)
            advance(1.seconds)
            f.probe.online = false
            advance(10.seconds)
            f.h
                .snapshot(id)!!
                .download.pauseReasons shouldBe setOf(PauseReason.NETWORK)
            f.told<EngineEvent.AfterQueueCountdown>().shouldBeEmpty()

            // PAUSED stays PAUSED; only the reasons change, and with USER among them the job no longer counts as busy.
            f.h.engine.pause(id)
            runCurrent()

            f.h
                .snapshot(id)!!
                .download.pauseReasons shouldBe setOf(PauseReason.USER, PauseReason.NETWORK)
            f.told<EngineEvent.AfterQueueCountdown>().size shouldBe 1
        }

    @Test
    fun aSchedulePauseHoldsItOff() =
        engineTest {
            val f = fixture(protocol = FakeProtocol(files = listOf(FakeFile("file.zip", 1L shl 20))))
            f.h.engine.start()
            val id = f.h.addJob()
            f.h.engine.setAfterQueue(AfterQueueAction.SLEEP)
            advance(1.seconds)

            f.h.engine.addScheduleRule(NewScheduleRule(DayOfWeek.entries.toSet(), 0, 0, RuleAction.PAUSE))
            advance(1.seconds)
            f.h
                .snapshot(id)!!
                .download.pauseReasons shouldBe setOf(PauseReason.SCHEDULE)

            advance(60.seconds)

            f.told<EngineEvent.AfterQueueCountdown>().shouldBeEmpty()
            f.power.calls.shouldBeEmpty()
        }

    @Test
    fun shutDownCallsShutDown() =
        engineTest {
            val f = fixture()
            f.h.engine.start()
            val id = f.h.addJob()
            f.h.engine.setAfterQueue(AfterQueueAction.SHUT_DOWN)
            until(f.h, id, DownloadStatus.COMPLETED) shouldBe true

            advance(30.seconds)

            f.power.calls shouldBe listOf("shutDown")
            f.told<EngineEvent.AfterQueueDone>().single().action shouldBe AfterQueueAction.SHUT_DOWN
        }

    @Test
    fun aRefusedSleepIsToldFailedAndDisarmed() =
        engineTest {
            val f = fixture(FakePowerActions(result = false))
            f.h.engine.start()
            val id = f.h.addJob()
            f.h.engine.setAfterQueue(AfterQueueAction.SLEEP)
            until(f.h, id, DownloadStatus.COMPLETED) shouldBe true

            advance(30.seconds)

            f.told<EngineEvent.AfterQueueFailed>() shouldBe
                listOf(EngineEvent.AfterQueueFailed(AfterQueueAction.SLEEP, "the sleep command is missing or failed"))
            f.h.store.settings
                .load()
                .afterQueue shouldBe AfterQueueAction.NONE
            f.h.engine.afterQueue.value shouldBe AfterQueueState.Idle
        }

    @Test
    fun stopDuringTheCountdownDoesNothing() =
        engineTest {
            val f = fixture()
            f.h.engine.start()
            val id = f.h.addJob()
            f.h.engine.setAfterQueue(AfterQueueAction.SLEEP)
            until(f.h, id, DownloadStatus.COMPLETED) shouldBe true
            advance(10.seconds)
            f.told<EngineEvent.AfterQueueCountdown>().size shouldBe 1

            f.h.engine.stop()
            advance(2.minutes)

            f.power.calls.shouldBeEmpty()
            f.told<EngineEvent.AfterQueueDone>().shouldBeEmpty()
        }

    @Test
    fun afterQueueIsIdleBeforeStartAndACommandBeforeStartIsStored() =
        engineTest {
            val f = fixture()
            f.h.engine.afterQueue.value shouldBe AfterQueueState.Idle

            f.h.engine.setAfterQueue(AfterQueueAction.SLEEP) shouldBe CommandResult.Done(null)

            f.h.engine.afterQueue.value shouldBe AfterQueueState.Idle
            f.h.store.settings
                .load()
                .afterQueue shouldBe AfterQueueAction.SLEEP

            f.h.engine.start()

            f.h.engine.afterQueue.value shouldBe AfterQueueState(AfterQueueAction.SLEEP, null)
        }
}
