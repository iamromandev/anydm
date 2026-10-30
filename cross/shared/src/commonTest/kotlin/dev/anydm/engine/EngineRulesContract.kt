package dev.anydm.engine

import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.DownloadStatus
import dev.anydm.engine.core.PauseReason
import dev.anydm.engine.log.Log
import dev.anydm.engine.log.RecordingLogSink
import dev.anydm.engine.net.Allowance
import dev.anydm.engine.net.AllowanceAction
import dev.anydm.engine.net.AllowancePeriod
import dev.anydm.engine.net.NetworkPolicy
import dev.anydm.engine.net.NewScheduleRule
import dev.anydm.engine.net.RuleAction
import dev.anydm.engine.net.RuleSource
import dev.anydm.engine.net.RuleStatus
import dev.anydm.engine.net.SlowMode
import dev.anydm.engine.net.TrafficBatch
import dev.anydm.engine.platform.virtualClock
import dev.anydm.engine.protocol.FakeFile
import dev.anydm.engine.protocol.FakeProtocol
import dev.anydm.engine.queue.CommandResult
import dev.anydm.engine.store.Store
import dev.anydm.engine.store.StoreTestBase
import dev.anydm.engine.store.TrafficStore
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DayOfWeek
import kotlin.test.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The engine applies network rules (#543): schedule boundaries, allowances, slow mode and the commands that change them.
 * UTC, a 1 MiB file at 16 KiB/s unless a test says otherwise, on a new database from [newDriver] for each test.
 */
abstract class EngineRulesContract : StoreTestBase() {
    private val noon = Instant.parse("2026-10-21T12:00:00Z")
    private val today = "2026-10-21"
    private val midnight = Instant.parse("2026-10-22T00:00:00Z")

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

    private fun allDay(action: RuleAction) = NewScheduleRule(DayOfWeek.entries.toSet(), 0, 0, action)

    private fun TestScope.harness(
        origin: Instant = noon,
        network: () -> String = { "unknown" },
        sink: RecordingLogSink? = null,
        store: Store = newStore(),
        traffic: TrafficStore = store.traffic,
    ): EngineHarness {
        val clock = virtualClock(origin)
        return EngineHarness(
            this,
            store,
            FakeProtocol(files = listOf(FakeFile("file.zip", 1L shl 20))),
            clock = clock,
            network = { network() },
            traffic = traffic,
            log = if (sink == null) Log.None else Log(sink, clock),
        ).also { harnesses += it }
    }

    private suspend fun EngineHarness.storeUsage(
        bytes: Long,
        network: String = "unknown",
    ) = store.traffic.add(TrafficBatch(days = mapOf(Triple(today, network, DownloadKind.DIRECT) to bytes)), 0)

    private fun EngineHarness.reasons(id: DownloadId) = snapshot(id)!!.download.pauseReasons

    @Test
    fun rulesIsNoneBeforeStartAndACommandBeforeStartAppliesAtStart() =
        engineTest {
            val h = harness()
            h.engine.rules.value shouldBe RuleStatus.None

            h.engine.addScheduleRule(allDay(RuleAction.PAUSE))
            h.engine.rules.value shouldBe RuleStatus.None

            h.engine.start()
            h.engine.rules.value.action shouldBe RuleAction.PAUSE
            val id = h.addJob()
            runCurrent()

            h.status(id) shouldBe DownloadStatus.PAUSED
            h.reasons(id) shouldBe setOf(PauseReason.SCHEDULE)
            h.engine.stop()
        }

    @Test
    fun aScheduleRulePausesAtItsStartAndResumesAtItsEnd() =
        engineTest {
            val h = harness(Instant.parse("2026-10-21T09:59:50Z"))
            h.engine.start()
            h.engine.addScheduleRule(NewScheduleRule(setOf(DayOfWeek.WEDNESDAY), 600, 660, RuleAction.PAUSE))
            val id = h.addJob()

            advanceTimeBy(9.seconds)
            runCurrent()
            h.status(id) shouldBe DownloadStatus.DOWNLOADING

            advanceTimeBy(1.seconds)
            runCurrent()
            h.status(id) shouldBe DownloadStatus.PAUSED
            h.reasons(id) shouldBe setOf(PauseReason.SCHEDULE)

            advanceTimeBy(1.hours)
            runCurrent()
            h.status(id) shouldBe DownloadStatus.DOWNLOADING

            advanceTimeBy(5.minutes)
            runCurrent()
            h.status(id) shouldBe DownloadStatus.COMPLETED
            h.engine.stop()
        }

    @Test
    fun slowModeCapsAndRestoresWithoutTouchingTheStoredLimit() =
        engineTest {
            val h = harness()
            h.engine.start()
            h.engine.setGlobalSpeedLimit(8192)
            h.engine.setSlowModeLimit(4096)

            h.engine.setSlowMode(true) shouldBe CommandResult.Done(null)

            h.engine.rules.value.action shouldBe RuleAction.SLOW
            h.engine.rules.value.because shouldBe setOf(RuleSource.SLOW_MODE)
            h.protocol.globalLimits.last() shouldBe (4096L to 4096L)
            h.store.settings
                .load()
                .globalSpeedLimit shouldBe 8192

            h.engine.setSlowMode(false)

            h.engine.rules.value.action shouldBe RuleAction.ALLOW
            h.protocol.globalLimits.last() shouldBe (8192L to null)
            h.store.settings
                .load()
                .globalSpeedLimit shouldBe 8192
            h.engine.slowMode() shouldBe SlowMode(false, 4096)
            h.engine.stop()
        }

    @Test
    fun anAllowancePausesBeforeTheFirstTrafficSave() =
        engineTest {
            val store = newStore()
            val traffic = RecordingTraffic(store.traffic)
            val h = harness(Instant.parse("2026-10-21T23:58:00Z"), store = store, traffic = traffic)
            val events = mutableListOf<EngineEvent>()
            backgroundScope.launch { h.engine.events.collect { events += it } }
            runCurrent()
            h.engine.start()
            h.engine.setNetworkPolicy(null, NetworkPolicy(allowance = Allowance(102_400, AllowancePeriod.Daily, warnPercent = 50)))
            val id = h.addJob()

            advanceTimeBy(8500.milliseconds)
            runCurrent()

            h.status(id) shouldBe DownloadStatus.PAUSED
            h.reasons(id) shouldBe setOf(PauseReason.SCHEDULE)
            traffic.adds.shouldBeEmpty()
            events.filterIsInstance<EngineEvent.AllowanceWarning>().size shouldBe 1
            val reached = events.filterIsInstance<EngineEvent.AllowanceReached>().single()
            reached.action shouldBe AllowanceAction.PAUSE
            reached.resetsAt shouldBe midnight

            advanceTimeBy(121.seconds - 8500.milliseconds)
            runCurrent()
            h.status(id) shouldBe DownloadStatus.DOWNLOADING
            h.engine.stop()
        }

    @Test
    fun atLimitSlowKeepsRunningAtTheSlowCap() =
        engineTest {
            val h = harness()
            h.engine.start()
            h.engine.setNetworkPolicy(
                null,
                NetworkPolicy(allowance = Allowance(20_000, AllowancePeriod.Daily, atLimit = AllowanceAction.SLOW)),
            )
            val id = h.addJob()

            advanceTimeBy(5.seconds)
            runCurrent()

            h.status(id) shouldBe DownloadStatus.DOWNLOADING
            h.engine.rules.value.action shouldBe RuleAction.SLOW
            h.protocol.globalLimits.last() shouldBe (SlowMode.DEFAULT_LIMIT to SlowMode.DEFAULT_LIMIT)
            h.engine.stop()
        }

    @Test
    fun switchingOntoANetworkOverItsAllowancePausesWithinASecond() =
        engineTest {
            var net = "a"
            val h = harness(network = { net })
            h.storeUsage(600_000, "b")
            h.engine.start()
            h.engine.setNetworkPolicy(null, NetworkPolicy(allowance = Allowance(500_000, AllowancePeriod.Daily)))
            val id = h.addJob()
            advanceTimeBy(3.seconds)
            runCurrent()
            h.status(id) shouldBe DownloadStatus.DOWNLOADING

            net = "b"
            advanceTimeBy(1.seconds)
            runCurrent()

            h.status(id) shouldBe DownloadStatus.PAUSED
            h.reasons(id) shouldBe setOf(PauseReason.SCHEDULE)
            h.engine.stop()
        }

    @Test
    fun switchingToANetworkUnderItsAllowanceResumesWithinAMinute() =
        engineTest {
            var net = "b"
            val h = harness(network = { net })
            h.storeUsage(600_000, "b")
            h.store.rules.setNetworkPolicy(null, NetworkPolicy(allowance = Allowance(500_000, AllowancePeriod.Daily)))
            h.engine.start()
            val id = h.addJob()
            runCurrent()
            h.status(id) shouldBe DownloadStatus.PAUSED

            // The monitor sees the switch at its next poll (3 s) and the rules release the job then. A minute is the limit
            // (the idle check), but by a minute the job has used the whole 500 kB allowance of the new network by itself.
            net = "a"
            advanceTimeBy(10.seconds)
            runCurrent()

            h.status(id) shouldBe DownloadStatus.DOWNLOADING
            h.engine.stop()
        }

    @Test
    fun raisingTheAllowanceMidPauseHasResumedWhenTheCommandReturns() =
        engineTest {
            val h = harness()
            h.engine.start()
            h.engine.setNetworkPolicy(null, NetworkPolicy(allowance = Allowance(50_000, AllowancePeriod.Daily)))
            val id = h.addJob()
            advanceTimeBy(5.seconds)
            runCurrent()
            h.status(id) shouldBe DownloadStatus.PAUSED

            h.engine.setNetworkPolicy(null, NetworkPolicy(allowance = Allowance(50_000_000, AllowancePeriod.Daily)))

            (
                h.store.downloads
                    .get(id)!!
                    .status != DownloadStatus.PAUSED
            ) shouldBe true
            h.engine.stop()
        }

    @Test
    fun removingARuleMidRunResumesBeforeTheCommandReturns() =
        engineTest {
            val h = harness()
            h.engine.start()
            val id = h.addJob()
            advanceTimeBy(2.seconds)
            runCurrent()
            h.status(id) shouldBe DownloadStatus.DOWNLOADING

            val rule = h.engine.addScheduleRule(allDay(RuleAction.PAUSE))
            h.store.downloads
                .get(id)!!
                .status shouldBe DownloadStatus.PAUSED

            h.engine.removeScheduleRule(rule.id) shouldBe true
            (
                h.store.downloads
                    .get(id)!!
                    .status != DownloadStatus.PAUSED
            ) shouldBe true
            h.engine.scheduleRules() shouldBe emptyList()
            h.engine.stop()
        }

    @Test
    fun theStatusShowsNetworkActionBecauseUsedAndNextChange() =
        engineTest {
            val h = harness(network = { "wifi" })
            h.storeUsage(1000, "wifi")
            h.store.rules.setNetworkPolicy(null, NetworkPolicy(allowance = Allowance(1_000_000, AllowancePeriod.Daily)))
            h.store.rules.addScheduleRule(NewScheduleRule(setOf(DayOfWeek.WEDNESDAY), 780, 840, RuleAction.SLOW))
            h.engine.start()

            val before = h.engine.rules.value
            before.network shouldBe "wifi"
            before.action shouldBe RuleAction.ALLOW
            before.because shouldBe emptySet()
            before.allowance!!.used shouldBe 1000
            before.nextChange shouldBe Instant.parse("2026-10-21T13:00:00Z")

            advanceTimeBy(1.hours)
            runCurrent()

            val during = h.engine.rules.value
            during.action shouldBe RuleAction.SLOW
            during.because shouldBe setOf(RuleSource.SCHEDULE)
            during.nextChange shouldBe Instant.parse("2026-10-21T14:00:00Z")
            h.engine.stop()
        }

    @Test
    fun invalidSlowLimitAndNetworkKeysAreRefused() =
        engineTest {
            val h = harness()
            h.engine.start()

            h.engine.setSlowModeLimit(0) shouldBe CommandResult.Invalid("slow-mode limit must be >= 1: 0")
            for (key in listOf("", " ", "*")) {
                h.engine.setNetworkPolicy(key, NetworkPolicy()) shouldBe CommandResult.Invalid("network key must not be blank or *: $key")
            }

            h.engine.slowMode() shouldBe SlowMode(false, SlowMode.DEFAULT_LIMIT)
            h.engine.networkRules().networks shouldBe emptyMap()
            h.engine.setNetworkPolicy("wifi", NetworkPolicy(speedLimit = 1000)) shouldBe CommandResult.Done(null)
            h.engine
                .networkRules()
                .networks.keys shouldBe setOf("wifi")
            h.engine.stop()
        }

    @Test
    fun allowanceEventsAreLogged() =
        engineTest {
            val sink = RecordingLogSink()
            val h = harness(sink = sink)
            h.engine.start()
            h.engine.setNetworkPolicy(null, NetworkPolicy(allowance = Allowance(131_072, AllowancePeriod.Daily, warnPercent = 50)))
            // 64 KiB is the warning's 50% to the byte; the limit is reached at 8 s.
            h.addJob()

            advanceTimeBy(10.seconds)
            runCurrent()

            sink.lines.any { "allowance on unknown: 50% used" in it } shouldBe true
            sink.lines.any { "allowance on unknown reached: PAUSE until 2026-10-22T00:00:00Z" in it } shouldBe true
            h.engine.stop()
        }
}
