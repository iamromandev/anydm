package dev.anydm.engine

import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.DownloadStatus
import dev.anydm.engine.core.PauseReason
import dev.anydm.engine.net.Allowance
import dev.anydm.engine.net.AllowancePeriod
import dev.anydm.engine.net.NetworkPolicy
import dev.anydm.engine.net.NewScheduleRule
import dev.anydm.engine.net.RuleAction
import dev.anydm.engine.net.RuleSource
import dev.anydm.engine.net.TrafficBatch
import dev.anydm.engine.platform.ShiftedClock
import dev.anydm.engine.platform.deleteRecursively
import dev.anydm.engine.platform.newTempFolder
import dev.anydm.engine.platform.virtualClock
import dev.anydm.engine.protocol.FakeFile
import dev.anydm.engine.protocol.FakeProtocol
import dev.anydm.engine.store.DATABASE_FILE
import dev.anydm.engine.store.Store
import dev.anydm.engine.store.openDatabaseFile
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.io.files.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Network rules across an engine stop and start on a real database file (#543). A 1 MiB file at 16 KiB/s. */
class EngineRulesRestartTest {
    private val root = newTempFolder("anydm-engine-rules-test-")
    private val file = Path(root, DATABASE_FILE)
    private val stores = mutableListOf<Store>()
    private val noon = Instant.parse("2026-10-21T12:00:00Z")
    private val allDay = NewScheduleRule(DayOfWeek.entries.toSet(), 0, 0, RuleAction.PAUSE)
    private val allowance = Allowance(102_400, AllowancePeriod.Daily, warnPercent = 50)

    @AfterTest
    fun cleanUp() {
        stores.forEach { runCatching { it.close() } }
        deleteRecursively(root)
    }

    private fun TestScope.open(): Store = Store(openDatabaseFile(file), StandardTestDispatcher(testScheduler)).also { stores += it }

    // A job with a start time in the future holds no slot, and has not been probed yet if it was never started.
    private val waiting = setOf(DownloadStatus.PENDING, DownloadStatus.QUEUED)
    private val harnesses = mutableListOf<EngineHarness>()

    private fun tracked(h: EngineHarness): EngineHarness = h.also { harnesses += it }

    // A failed assertion must not leave an engine's endless timers for runTest to drain.
    private fun restartTest(body: suspend TestScope.() -> Unit) =
        runTest {
            try {
                body()
            } finally {
                harnesses.forEach { it.engine.stop() }
            }
        }

    private fun protocol() = FakeProtocol(files = listOf(FakeFile("file.zip", 1L shl 20)))

    private fun EngineHarness.reasons(id: DownloadId) = snapshot(id)!!.download.pauseReasons

    @Test
    fun aPauseRuleActiveAtRestartLetsNoJobStart() =
        restartTest {
            val first = tracked(EngineHarness(this, open(), protocol(), clock = virtualClock(noon)))
            first.engine.start()
            val a = first.addJob()
            val b = first.addJob()
            advanceTimeBy(3.seconds)
            runCurrent()
            first.status(a) shouldBe DownloadStatus.DOWNLOADING
            first.engine.stop()

            // The rule appears while the engine is off, so what the file holds are jobs that were running or waiting.
            val store = open()
            store.rules.addScheduleRule(allDay)
            val second = tracked(EngineHarness(this, store, protocol(), fs = first.fs, clock = first.clock))
            val events = mutableListOf<EngineEvent>()
            backgroundScope.launch { second.engine.events.collect { events += it } }
            runCurrent()
            try {
                second.engine.start()
                advanceTimeBy(30.seconds)
                runCurrent()

                events.filterIsInstance<EngineEvent.StatusChanged>().filter { it.to == DownloadStatus.DOWNLOADING }.shouldBeEmpty()
                second.protocol.fetched.shouldBeEmpty()
                for (id in listOf(a, b)) {
                    second.status(id) shouldBe DownloadStatus.PAUSED
                    second.reasons(id) shouldBe setOf(PauseReason.SCHEDULE)
                }
            } finally {
                second.engine.stop()
            }
        }

    @Test
    fun storedUsageOverTheAllowanceAtRestartPausesWithoutAnAlert() =
        restartTest {
            val clock = virtualClock(noon)
            val first = tracked(EngineHarness(this, open(), protocol(), clock = clock))
            first.engine.start()
            first.engine.setNetworkPolicy(null, NetworkPolicy(allowance = allowance))
            first.engine.stop()

            val store = open()
            val today =
                clock
                    .now()
                    .toLocalDateTime(TimeZone.UTC)
                    .date
                    .toString()
            store.traffic.add(TrafficBatch(days = mapOf(Triple(today, "unknown", DownloadKind.DIRECT) to 204_800L)), 0)
            val second = tracked(EngineHarness(this, store, protocol(), clock = clock))
            val events = mutableListOf<EngineEvent>()
            backgroundScope.launch { second.engine.events.collect { events += it } }
            runCurrent()
            try {
                second.engine.start()
                val id = second.addJob()
                // Long enough for the idle timer's checks, which are what would tell a level crossed before the restart.
                advanceTimeBy(3.minutes)
                runCurrent()

                second.status(id) shouldBe DownloadStatus.PAUSED
                second.reasons(id) shouldBe setOf(PauseReason.SCHEDULE)
                second.engine.rules.value.action shouldBe RuleAction.PAUSE
                events.filter { it is EngineEvent.AllowanceWarning || it is EngineEvent.AllowanceReached }.shouldBeEmpty()
            } finally {
                second.engine.stop()
            }
        }

    @Test
    fun aPeriodThatEndedWhileClosedIsReleasedAtStart() =
        restartTest {
            val clock = virtualClock(noon)
            val first = tracked(EngineHarness(this, open(), protocol(), clock = clock))
            first.engine.start()
            first.engine.setNetworkPolicy(null, NetworkPolicy(allowance = allowance))
            val id = first.addJob()
            advanceTimeBy(10.seconds)
            runCurrent()
            first.status(id) shouldBe DownloadStatus.PAUSED
            first.engine.stop()

            val second = tracked(EngineHarness(this, open(), protocol(), fs = first.fs, clock = ShiftedClock(clock, 1.days)))
            try {
                second.engine.start()
                second.engine.rules.value.allowance!!
                    .used shouldBe 0
                advanceTimeBy(1.seconds)
                runCurrent()

                second.status(id) shouldBe DownloadStatus.DOWNLOADING
                second.reasons(id) shouldBe emptySet()
                second.engine.rules.value.action shouldBe RuleAction.ALLOW
            } finally {
                second.engine.stop()
            }
        }

    @Test
    fun aStartTimeAndASlowModeSurviveARestart() =
        restartTest {
            val clock = virtualClock(noon)
            val first = tracked(EngineHarness(this, open(), protocol(), clock = clock))
            first.engine.start()
            first.engine.setSlowModeLimit(4096)
            first.engine.setSlowMode(true)
            val id = first.addJob()
            val startAt = noon + 1.hours
            first.engine.setStartTime(id, startAt)
            runCurrent()
            (first.status(id) in waiting) shouldBe true
            first.engine.stop()

            val second = tracked(EngineHarness(this, open(), protocol(), fs = first.fs, clock = clock))
            try {
                second.engine.start()
                runCurrent()

                second.engine.rules.value.action shouldBe RuleAction.SLOW
                second.engine.rules.value.because shouldBe setOf(RuleSource.SLOW_MODE)
                second.protocol.globalLimits.last() shouldBe (4096L to 4096L)
                second.snapshot(id)!!.download.startAt shouldBe startAt
                advanceTimeBy(59.minutes)
                runCurrent()
                (second.status(id) in waiting) shouldBe true
                advanceTimeBy(1.minutes + 1.seconds)
                runCurrent()
                second.status(id) shouldBe DownloadStatus.DOWNLOADING
            } finally {
                second.engine.stop()
            }
        }
}
