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
import dev.anydm.engine.net.TrafficBatch
import dev.anydm.engine.platform.ShiftedClock
import dev.anydm.engine.platform.virtualClock
import dev.anydm.engine.protocol.FakeFile
import dev.anydm.engine.protocol.FakeProtocol
import dev.anydm.engine.store.StoreTestBase
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Rules and the clock (#543): wall-clock jumps, edits that move a boundary, daylight-saving gaps and overlaps in
 * Europe/Berlin, and a zone that changes. A 1 MiB file at 16 KiB/s, on a new database from [newDriver] for each test.
 */
abstract class EngineRulesTimeContract : StoreTestBase() {
    private val berlin = TimeZone.of("Europe/Berlin")
    private val harnesses = mutableListOf<EngineHarness>()

    // A failed assertion must not leave an engine's endless timers for runTest to drain.
    private fun timeTest(body: suspend TestScope.() -> Unit) =
        runTest {
            try {
                body()
            } finally {
                harnesses.forEach { it.engine.stop() }
                harnesses.clear()
            }
        }

    private fun TestScope.harness(
        origin: Instant,
        zone: () -> TimeZone = { TimeZone.UTC },
    ): Pair<EngineHarness, ShiftedClock> {
        val clock = ShiftedClock(virtualClock(origin))
        val h =
            EngineHarness(
                this,
                newStore(),
                FakeProtocol(files = listOf(FakeFile("file.zip", 1L shl 20))),
                clock = clock,
                timeZone = zone,
            )
        harnesses += h
        return h to clock
    }

    private fun EngineHarness.reasons(id: DownloadId) = snapshot(id)!!.download.pauseReasons

    private suspend fun TestScope.advanceTo(seconds: Long) {
        advanceTimeBy(seconds.seconds)
        runCurrent()
    }

    @Test
    fun aWallClockJumpIntoAPauseRangeIsSeenWithinAMinuteWhenIdle() =
        timeTest {
            val (h, clock) = harness(Instant.parse("2026-10-21T08:00:00Z"))
            h.engine.start()
            h.engine.addScheduleRule(NewScheduleRule(DayOfWeek.entries.toSet(), 600, 660, RuleAction.PAUSE))
            h.engine.rules.value.action shouldBe RuleAction.ALLOW

            clock.offset += 2.hours
            advanceTimeBy(60.seconds)
            runCurrent()

            h.engine.rules.value.action shouldBe RuleAction.PAUSE
            val id = h.addJob()
            runCurrent()
            h.status(id) shouldBe DownloadStatus.PAUSED
            h.engine.stop()
        }

    @Test
    fun aJumpWhileTransferringIsSeenWithinASecond() =
        timeTest {
            val (h, clock) = harness(Instant.parse("2026-10-21T08:00:00Z"))
            h.engine.start()
            h.engine.addScheduleRule(NewScheduleRule(DayOfWeek.entries.toSet(), 600, 660, RuleAction.PAUSE))
            val id = h.addJob()
            advanceTo(3)
            h.status(id) shouldBe DownloadStatus.DOWNLOADING

            clock.offset += 2.hours
            advanceTo(4)

            h.status(id) shouldBe DownloadStatus.PAUSED
            h.reasons(id) shouldBe setOf(PauseReason.SCHEDULE)
            h.engine.stop()
        }

    @Test
    fun aJumpBackOutOfARangeReleases() =
        timeTest {
            val (h, clock) = harness(Instant.parse("2026-10-21T10:30:00Z"))
            h.engine.start()
            h.engine.addScheduleRule(NewScheduleRule(DayOfWeek.entries.toSet(), 600, 660, RuleAction.PAUSE))
            val id = h.addJob()
            runCurrent()
            h.status(id) shouldBe DownloadStatus.PAUSED

            clock.offset -= 2.hours
            advanceTimeBy(60.seconds)
            runCurrent()

            h.status(id) shouldBe DownloadStatus.DOWNLOADING
            h.engine.stop()
        }

    @Test
    fun editingARuleToStartEarlierFiresAtTheNewBoundary() =
        timeTest {
            // :20 past, so the timer's one-minute steps don't happen to land on the boundary.
            val (h, _) = harness(Instant.parse("2026-10-21T09:00:20Z"))
            h.engine.start()
            val rule = h.engine.addScheduleRule(NewScheduleRule(setOf(DayOfWeek.WEDNESDAY), 600, 660, RuleAction.PAUSE))
            advanceTimeBy(10.minutes)
            runCurrent()
            h.engine.rules.value.action shouldBe RuleAction.ALLOW

            h.engine.updateScheduleRule(rule.copy(startMinute = 570)) shouldBe true
            h.engine.rules.value.nextChange shouldBe Instant.parse("2026-10-21T09:30:00Z")

            advanceTimeBy(19.minutes + 39.seconds)
            runCurrent()
            h.engine.rules.value.action shouldBe RuleAction.ALLOW
            advanceTo(1)

            h.engine.rules.value.action shouldBe RuleAction.PAUSE
            h.engine.stop()
        }

    @Test
    fun editingARuleToStartWithinTheTimersStepFiresAtTheNewBoundary() =
        timeTest {
            // The timer is due at 09:01:20; the edit puts the boundary at 09:01:00, so the edit has to re-arm it.
            val (h, _) = harness(Instant.parse("2026-10-21T09:00:20Z"))
            h.engine.start()
            val rule = h.engine.addScheduleRule(NewScheduleRule(setOf(DayOfWeek.WEDNESDAY), 600, 660, RuleAction.PAUSE))
            advanceTimeBy(10.seconds)
            runCurrent()

            h.engine.updateScheduleRule(rule.copy(startMinute = 541)) shouldBe true
            advanceTimeBy(29.seconds)
            runCurrent()
            h.engine.rules.value.action shouldBe RuleAction.ALLOW
            advanceTo(1)

            h.engine.rules.value.action shouldBe RuleAction.PAUSE
            h.engine.stop()
        }

    @Test
    fun aSpringGapBoundaryFiresAtTheGapsEnd() =
        timeTest {
            // Berlin skips 02:00-03:00 local on 2026-03-29, so 02:30 happens at 03:00 local = 01:00Z.
            val (h, _) = harness(Instant.parse("2026-03-29T00:58:30Z"), zone = { berlin })
            h.engine.start()
            h.engine.addScheduleRule(NewScheduleRule(setOf(DayOfWeek.SUNDAY), 150, 240, RuleAction.PAUSE))
            h.engine.rules.value.action shouldBe RuleAction.ALLOW
            h.engine.rules.value.nextChange shouldBe Instant.parse("2026-03-29T01:00:00Z")

            advanceTimeBy(89.seconds)
            runCurrent()
            h.engine.rules.value.action shouldBe RuleAction.ALLOW
            advanceTo(1)

            h.engine.rules.value.action shouldBe RuleAction.PAUSE
            h.engine.rules.value.nextChange shouldBe Instant.parse("2026-03-29T02:00:00Z")
            h.engine.stop()
        }

    @Test
    fun anAutumnOverlapBoundaryFiresAtItsFirstOccurrence() =
        timeTest {
            // Berlin repeats 02:00-03:00 local on 2026-10-25; the first 02:30 is 00:30Z.
            val (h, _) = harness(Instant.parse("2026-10-25T00:00:10Z"), zone = { berlin })
            h.engine.start()
            h.engine.addScheduleRule(NewScheduleRule(setOf(DayOfWeek.SUNDAY), 150, 300, RuleAction.PAUSE))
            h.engine.rules.value.action shouldBe RuleAction.ALLOW
            h.engine.rules.value.nextChange shouldBe Instant.parse("2026-10-25T00:30:00Z")

            advanceTimeBy(29.minutes + 49.seconds)
            runCurrent()
            h.engine.rules.value.action shouldBe RuleAction.ALLOW
            advanceTo(1)

            h.engine.rules.value.action shouldBe RuleAction.PAUSE
            h.engine.stop()
        }

    @Test
    fun aBerlinDailyAllowanceResetsAtLocalMidnight() =
        timeTest {
            val (h, _) = harness(Instant.parse("2026-03-29T12:00:00Z"), zone = { berlin })
            h.store.rules.setNetworkPolicy(null, NetworkPolicy(allowance = Allowance(1000, AllowancePeriod.Daily)))
            // The local day is Berlin's: 2026-03-29, and 2026-03-30 from 22:00Z (the gap made it a 23-hour day).
            h.store.traffic.add(TrafficBatch(days = mapOf(Triple("2026-03-29", "unknown", DownloadKind.DIRECT) to 5000L)), 0)
            h.engine.start()
            h.engine.rules.value.allowance!!
                .resetsAt shouldBe Instant.parse("2026-03-29T22:00:00Z")
            val id = h.addJob()
            runCurrent()
            h.status(id) shouldBe DownloadStatus.PAUSED

            advanceTimeBy(10.hours - 1.seconds)
            runCurrent()
            h.status(id) shouldBe DownloadStatus.PAUSED
            advanceTo(1)

            h.status(id) shouldBe DownloadStatus.DOWNLOADING
            h.engine.rules.value.allowance!!
                .resetsAt shouldBe Instant.parse("2026-03-30T22:00:00Z")
            h.engine.stop()
        }

    @Test
    fun aZoneChangeIsReadAtTheNextCheck() =
        timeTest {
            var zone: TimeZone = TimeZone.UTC
            val (h, _) = harness(Instant.parse("2026-10-21T09:30:00Z"), zone = { zone })
            h.engine.start()
            h.engine.addScheduleRule(NewScheduleRule(DayOfWeek.entries.toSet(), 660, 720, RuleAction.PAUSE))
            h.engine.rules.value.action shouldBe RuleAction.ALLOW

            zone = berlin // 11:30 local
            advanceTimeBy(60.seconds)
            runCurrent()

            h.engine.rules.value.action shouldBe RuleAction.PAUSE
            h.engine.stop()
        }
}
