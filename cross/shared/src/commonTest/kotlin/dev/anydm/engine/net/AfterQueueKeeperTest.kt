package dev.anydm.engine.net

import dev.anydm.engine.EngineEvent
import dev.anydm.engine.log.Log
import dev.anydm.engine.platform.FakePowerActions
import dev.anydm.engine.platform.virtualClock
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** The one-shot after-queue countdown (NET-10), without the engine. */
class AfterQueueKeeperTest {
    private val t0 = Instant.parse("2026-10-21T12:00:00Z")

    private class Rig(
        test: TestScope,
    ) {
        val order = mutableListOf<String>()
        val events = mutableListOf<EngineEvent>()
        val saved = mutableListOf<AfterQueueAction>()
        var busy = false
        var flushGate: CompletableDeferred<Unit>? = null
        val power = FakePowerActions().also { it.before = { order += "power" } }
        val keeper =
            AfterQueueKeeper(
                scope = test.backgroundScope,
                clock = test.virtualClock(Instant.parse("2026-10-21T12:00:00Z")),
                log = Log.None,
                save = {
                    saved += it
                    order += "save $it"
                },
                busyNow = { busy },
                flush = {
                    order += "flush"
                    flushGate?.await()
                },
                power = power,
                emit = {
                    events += it
                    if (it is EngineEvent.AfterQueueDone) order += "Done"
                },
            )
    }

    private fun TestScope.rig() = Rig(this)

    /** Moves virtual time by [by] and lets what is due at its end run. */
    private fun TestScope.pass(by: Duration) {
        advanceTimeBy(by)
        runCurrent()
    }

    @Test
    fun armingAnIdleQueueWaitsForABusyThenIdleCycle() =
        runTest {
            val r = rig()
            r.keeper.set(AfterQueueAction.SLEEP, busy = false)
            r.keeper.update(false)
            pass(1.minutes)
            r.events shouldBe emptyList()

            r.keeper.update(true)
            r.keeper.update(false)

            r.events shouldBe listOf(EngineEvent.AfterQueueCountdown(AfterQueueAction.SLEEP, t0 + 1.minutes + 30.seconds))
            r.power.calls shouldBe emptyList()
        }

    @Test
    fun armedWhileBusyCountsDownWhenIdle() =
        runTest {
            val r = rig()
            r.keeper.set(AfterQueueAction.SLEEP, busy = true)
            r.keeper.update(true)
            r.events shouldBe emptyList()

            r.keeper.update(false)

            r.events shouldBe listOf(EngineEvent.AfterQueueCountdown(AfterQueueAction.SLEEP, t0 + 30.seconds))
            r.keeper.state.value shouldBe AfterQueueState(AfterQueueAction.SLEEP, t0 + 30.seconds)
        }

    @Test
    fun theActionRunsOnceAfterExactly30Seconds() =
        runTest {
            val r = rig()
            r.keeper.start(AfterQueueAction.SLEEP)
            r.keeper.update(true)
            r.keeper.update(false)

            pass(29999.milliseconds)
            r.power.calls shouldBe emptyList()
            r.order shouldBe emptyList()

            pass(1.milliseconds)
            r.order shouldBe listOf("save NONE", "flush", "Done", "power")
            r.power.calls shouldBe listOf("sleep")
            r.events.last() shouldBe EngineEvent.AfterQueueDone(AfterQueueAction.SLEEP)
            r.keeper.state.value shouldBe AfterQueueState(AfterQueueAction.NONE, null)

            r.keeper.update(true)
            r.keeper.update(false)
            pass(5.minutes)
            r.power.calls shouldBe listOf("sleep")
            r.events.size shouldBe 2
        }

    @Test
    fun busyDuringTheCountdownCancelsAndStaysArmed() =
        runTest {
            val r = rig()
            r.keeper.start(AfterQueueAction.SLEEP)
            r.keeper.update(true)
            r.keeper.update(false)
            pass(10.seconds)

            r.keeper.update(true)

            r.events.last() shouldBe EngineEvent.AfterQueueCancelled(AfterQueueAction.SLEEP, false)
            r.keeper.state.value shouldBe AfterQueueState(AfterQueueAction.SLEEP, null)
            pass(1.minutes)
            r.power.calls shouldBe emptyList()

            r.keeper.update(false)
            r.events.last() shouldBe EngineEvent.AfterQueueCountdown(AfterQueueAction.SLEEP, t0 + 70.seconds + 30.seconds)
            pass(30.seconds)
            r.power.calls shouldBe listOf("sleep")
        }

    @Test
    fun busyAtTheEndOfTheCountdownCancelsAndStaysArmed() =
        runTest {
            val r = rig()
            r.keeper.start(AfterQueueAction.SLEEP)
            r.keeper.update(true)
            r.keeper.update(false)
            pass(29.seconds)
            // A job started that the keeper was not told about yet.
            r.busy = true

            pass(1.seconds)

            r.events.last() shouldBe EngineEvent.AfterQueueCancelled(AfterQueueAction.SLEEP, false)
            r.keeper.state.value shouldBe AfterQueueState(AfterQueueAction.SLEEP, null)
            r.power.calls shouldBe emptyList()
            r.saved shouldBe emptyList()

            r.busy = false
            r.keeper.update(false)
            r.events.last() shouldBe EngineEvent.AfterQueueCountdown(AfterQueueAction.SLEEP, t0 + 60.seconds)
        }

    @Test
    fun cancelDisarms() =
        runTest {
            val r = rig()
            r.keeper.start(AfterQueueAction.SLEEP)
            r.keeper.update(true)
            r.keeper.update(false)
            pass(10.seconds)

            r.keeper.set(AfterQueueAction.NONE, busy = false)

            r.events.last() shouldBe EngineEvent.AfterQueueCancelled(AfterQueueAction.SLEEP, true)
            r.saved shouldBe listOf(AfterQueueAction.NONE)
            r.keeper.state.value shouldBe AfterQueueState(AfterQueueAction.NONE, null)
            pass(5.minutes)
            r.power.calls shouldBe emptyList()
        }

    @Test
    fun cancelWithNothingArmedTellsNothing() =
        runTest {
            val r = rig()
            r.keeper.set(AfterQueueAction.NONE, busy = false)
            r.events shouldBe emptyList()

            r.keeper.set(AfterQueueAction.SLEEP, busy = false)
            r.keeper.set(AfterQueueAction.NONE, busy = false)
            r.events shouldBe emptyList()
            r.keeper.state.value shouldBe AfterQueueState(AfterQueueAction.NONE, null)
        }

    @Test
    fun settingTheArmedActionAgainChangesNothing() =
        runTest {
            val r = rig()
            r.keeper.start(AfterQueueAction.SLEEP)
            r.keeper.update(true)
            r.keeper.update(false)
            pass(10.seconds)
            r.events.size shouldBe 1

            r.keeper.set(AfterQueueAction.SLEEP, busy = false)

            r.events.size shouldBe 1
            pass(20.seconds)
            r.power.calls shouldBe listOf("sleep")
        }

    @Test
    fun switchingActionDuringACountdownRestartsItForTheNewAction() =
        runTest {
            val r = rig()
            r.keeper.start(AfterQueueAction.SLEEP)
            r.keeper.update(true)
            r.keeper.update(false)
            pass(10.seconds)

            r.keeper.set(AfterQueueAction.SHUT_DOWN, busy = false)

            r.events.takeLast(2) shouldBe
                listOf(
                    EngineEvent.AfterQueueCancelled(AfterQueueAction.SLEEP, false),
                    EngineEvent.AfterQueueCountdown(AfterQueueAction.SHUT_DOWN, t0 + 40.seconds),
                )
            r.keeper.state.value shouldBe AfterQueueState(AfterQueueAction.SHUT_DOWN, t0 + 40.seconds)
            pass(29.seconds)
            r.power.calls shouldBe emptyList()
            pass(1.seconds)
            r.power.calls shouldBe listOf("shutDown")
        }

    @Test
    fun shutDownCallsShutDown() =
        runTest {
            val r = rig()
            r.keeper.start(AfterQueueAction.SHUT_DOWN)
            r.keeper.update(true)
            r.keeper.update(false)

            pass(30.seconds)

            r.power.calls shouldBe listOf("shutDown")
            r.events.last() shouldBe EngineEvent.AfterQueueDone(AfterQueueAction.SHUT_DOWN)
        }

    @Test
    fun aFailedPowerCallIsToldAndStaysDisarmed() =
        runTest {
            val r = rig()
            r.power.result = false
            r.keeper.start(AfterQueueAction.SLEEP)
            r.keeper.update(true)
            r.keeper.update(false)

            pass(30.seconds)

            r.events.last() shouldBe EngineEvent.AfterQueueFailed(AfterQueueAction.SLEEP, "the sleep command is missing or failed")
            r.saved shouldBe listOf(AfterQueueAction.NONE)
            r.keeper.state.value shouldBe AfterQueueState(AfterQueueAction.NONE, null)

            val shut = rig()
            shut.power.result = false
            shut.keeper.start(AfterQueueAction.SHUT_DOWN)
            shut.keeper.update(true)
            shut.keeper.update(false)
            pass(30.seconds)
            shut.events.last() shouldBe
                EngineEvent.AfterQueueFailed(AfterQueueAction.SHUT_DOWN, "the shut-down command is missing or failed")
        }

    @Test
    fun aThrowingPowerCallIsAFailure() =
        runTest {
            val r = rig()
            r.power.before = { throw IllegalStateException("boom") }
            r.keeper.start(AfterQueueAction.SLEEP)
            r.keeper.update(true)
            r.keeper.update(false)

            pass(30.seconds)

            r.events.last() shouldBe
                EngineEvent.AfterQueueFailed(AfterQueueAction.SLEEP, "the sleep command is missing or failed: boom")
            r.keeper.state.value shouldBe AfterQueueState(AfterQueueAction.NONE, null)
        }

    @Test
    fun anUpdateWhileFiringDoesNotStopIt() =
        runTest {
            val r = rig()
            val gate = CompletableDeferred<Unit>()
            r.flushGate = gate
            r.keeper.start(AfterQueueAction.SLEEP)
            r.keeper.update(true)
            r.keeper.update(false)
            pass(30.seconds)
            r.order shouldBe listOf("save NONE", "flush")

            r.keeper.update(true)
            gate.complete(Unit)
            runCurrent()

            r.power.calls shouldBe listOf("sleep")
            r.events.none { it is EngineEvent.AfterQueueCancelled } shouldBe true
        }

    @Test
    fun anActionArmedAgainWhileFiringStartsNoSecondCountdown() =
        runTest {
            val r = rig()
            val gate = CompletableDeferred<Unit>()
            r.flushGate = gate
            r.keeper.start(AfterQueueAction.SLEEP)
            r.keeper.update(true)
            r.keeper.update(false)
            pass(30.seconds)
            r.keeper.set(AfterQueueAction.SHUT_DOWN, busy = true)

            r.keeper.update(false)
            gate.complete(Unit)
            runCurrent()

            r.events.filterIsInstance<EngineEvent.AfterQueueCountdown>().size shouldBe 1
            r.power.calls shouldBe listOf("sleep")
        }

    @Test
    fun startNeverFires() =
        runTest {
            val r = rig()
            r.keeper.started shouldBe false
            r.keeper.start(AfterQueueAction.SLEEP)
            r.keeper.started shouldBe true
            r.keeper.state.value shouldBe AfterQueueState(AfterQueueAction.SLEEP, null)

            r.keeper.update(false)
            pass(5.minutes)

            r.events shouldBe emptyList()
            r.power.calls shouldBe emptyList()
        }
}
