package dev.anydm.engine

import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadStatus
import dev.anydm.engine.core.ErrorClass
import dev.anydm.engine.core.PauseReason
import dev.anydm.engine.net.Allowance
import dev.anydm.engine.net.AllowancePeriod
import dev.anydm.engine.net.NetworkPolicy
import dev.anydm.engine.net.NewScheduleRule
import dev.anydm.engine.net.RuleAction
import dev.anydm.engine.platform.FakeOnlineProbe
import dev.anydm.engine.platform.ShiftedClock
import dev.anydm.engine.platform.virtualClock
import dev.anydm.engine.protocol.FakeFailure
import dev.anydm.engine.protocol.FakeFile
import dev.anydm.engine.protocol.FakeProtocol
import dev.anydm.engine.protocol.StepResult
import dev.anydm.engine.store.Store
import dev.anydm.engine.store.StoreTestBase
import dev.anydm.engine.store.TrafficStore
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DayOfWeek
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The engine notices that the machine slept (#543, NET-12): a jump of the wall clock restarts the connections, uncounted,
 * and shows the rules and retries that came due. A 1 MiB file at 16 KiB/s, `wakeGap` 30 s unless a test says otherwise, on
 * a new database from [newDriver] for each test.
 */
abstract class EngineWakeContract : StoreTestBase() {
    private val noon = Instant.parse("2026-10-21T12:00:00Z")

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
        val clock: ShiftedClock,
        val probe: FakeOnlineProbe,
        val events: MutableList<EngineEvent>,
    )

    private fun TestScope.fixture(
        origin: Instant = noon,
        wakeGap: Duration = 30.seconds,
        traffic: (Store) -> TrafficStore = { it.traffic },
    ): Fixture {
        val clock = ShiftedClock(virtualClock(origin))
        val probe = FakeOnlineProbe()
        val store = newStore()
        val h =
            EngineHarness(
                this,
                store,
                FakeProtocol(files = listOf(FakeFile("file.zip", 1L shl 20))),
                traffic = traffic(store),
                clock = clock,
                onlineProbe = probe,
                wakeGap = wakeGap,
            ).also { harnesses += it }
        val events = mutableListOf<EngineEvent>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { h.engine.events.collect { events += it } }
        return Fixture(h, clock, probe, events)
    }

    private suspend fun TestScope.advance(by: Duration) {
        advanceTimeBy(by)
        runCurrent()
    }

    private fun EngineHarness.attempts(id: DownloadId) = snapshot(id)!!.download.attemptCount

    @Test
    fun aJumpWhileAJobRunsRestartsItsConnection() =
        engineTest {
            val f = fixture()
            f.h.engine.start()
            val id = f.h.addJob()
            advance(6.seconds)
            f.h.protocol.transfers.size shouldBe 1

            f.clock.offset += 10.minutes
            advance(5.seconds)

            val woke = f.events.filterIsInstance<EngineEvent.SystemWoke>().single()
            woke.slept shouldBeGreaterThanOrEqualTo 10.minutes
            f.h.protocol.transfers.size shouldBe 2
            f.h.status(id) shouldBe DownloadStatus.DOWNLOADING
            f.h.attempts(id) shouldBe 0
        }

    @Test
    fun aSmallStepIsNotAWake() =
        engineTest {
            val f = fixture()
            f.h.engine.start()
            f.h.addJob()
            advance(6.seconds)

            f.clock.offset += 20.seconds
            advance(10.seconds)

            f.events.filterIsInstance<EngineEvent.SystemWoke>().shouldBeEmpty()
            f.h.protocol.transfers.size shouldBe 1
        }

    @Test
    fun wokenOfflinePausesWithoutARestart() =
        engineTest {
            val f = fixture()
            f.h.engine.start()
            val id = f.h.addJob()
            advance(6.seconds)

            f.probe.online = false
            f.clock.offset += 10.minutes
            advance(5.seconds)

            f.events.filterIsInstance<EngineEvent.SystemWoke>().size shouldBe 1
            f.h.status(id) shouldBe DownloadStatus.PAUSED
            f.h
                .snapshot(id)!!
                .download.pauseReasons shouldBe setOf(PauseReason.NETWORK)
            f.h.protocol.transfers.size shouldBe 1

            f.probe.online = true
            advance(3.seconds)

            f.h.status(id) shouldBe DownloadStatus.DOWNLOADING
            f.h.attempts(id) shouldBe 0
        }

    @Test
    fun aRetryDueDuringTheJumpStartsWithinFiveSeconds() =
        engineTest {
            val f = fixture()
            f.h.protocol.failure =
                FakeFailure.AtTransfer(16_384, StepResult.Failed(ErrorClass.RATE_LIMITED, "slow down", retryAfter = 1.hours))
            f.h.engine.start()
            val id = f.h.addJob()
            // Past the wake ticker's first reading (5 s): it has nothing to compare with before that.
            advance(6.seconds)
            f.events.filterIsInstance<EngineEvent.RetryScheduled>().size shouldBe 1
            f.h.status(id) shouldBe DownloadStatus.PENDING
            f.h
                .snapshot(id)!!
                .download.nextAttemptAt shouldNotBe null
            f.h.protocol.failure = null

            f.clock.offset += 2.hours
            advance(5.seconds)

            f.h.status(id) shouldBe DownloadStatus.DOWNLOADING
        }

    @Test
    fun anIdleRuleBoundaryPassedDuringTheJumpIsSeenWithinFiveSeconds() =
        engineTest {
            val f = fixture(Instant.parse("2026-10-21T09:00:00Z"))
            f.h.engine.start()
            f.h.engine.addScheduleRule(NewScheduleRule(setOf(DayOfWeek.WEDNESDAY), 600, 660, RuleAction.PAUSE))
            advance(7.seconds)
            f.h.engine.rules.value.action shouldBe RuleAction.ALLOW

            f.clock.offset += 90.minutes
            advance(5.seconds)

            f.h.engine.rules.value.action shouldBe RuleAction.PAUSE
        }

    @Test
    fun theHarnessDefaultNeverWakes() =
        engineTest {
            val f = fixture(wakeGap = Duration.INFINITE)
            f.h.engine.start()
            f.h.addJob()
            advance(6.seconds)

            f.clock.offset += 10.minutes
            advance(10.seconds)

            f.events.filterIsInstance<EngineEvent.SystemWoke>().shouldBeEmpty()
        }

    // The store cannot be read when the day turns over under the jump: the wake's rule check fails.
    @Test
    fun aWakeWhoseRuleCheckFailsKeepsTheEngineAndTheWakeLoopAndRestartsTheConnections() =
        engineTest {
            var recording: RecordingTraffic? = null
            val f =
                fixture(
                    origin = Instant.parse("2026-10-21T23:55:00Z"),
                    traffic = { RecordingTraffic(it.traffic).also { r -> recording = r } },
                )
            val traffic = recording!!
            f.h.engine.start()
            f.h.engine.setNetworkPolicy("unknown", NetworkPolicy(allowance = Allowance(1L shl 40, AllowancePeriod.Daily)))
            val id = f.h.addJob()
            advance(6.seconds)
            f.h.protocol.transfers.size shouldBe 1

            traffic.failNetworkReads = 1_000
            f.clock.offset += 10.minutes
            advance(5.seconds)

            f.h.engine.state.value shouldBe EngineState.Running
            f.events.filterIsInstance<EngineEvent.EngineFailed>().shouldBeEmpty()
            f.events.filterIsInstance<EngineEvent.SystemWoke>().size shouldBe 1
            f.h.protocol.transfers.size shouldBe 2
            f.h.status(id) shouldBe DownloadStatus.DOWNLOADING

            // The loop still ticks: a second wake is seen and restarts the connection again.
            traffic.failNetworkReads = 0
            f.clock.offset += 10.minutes
            advance(5.seconds)

            f.events.filterIsInstance<EngineEvent.SystemWoke>().size shouldBe 2
            f.h.protocol.transfers.size shouldBe 3
            f.h.engine.state.value shouldBe EngineState.Running
        }
}
