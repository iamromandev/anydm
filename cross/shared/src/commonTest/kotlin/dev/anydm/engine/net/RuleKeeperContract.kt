package dev.anydm.engine.net

import dev.anydm.engine.EngineEvent
import dev.anydm.engine.RecordingTraffic
import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.log.Log
import dev.anydm.engine.log.RecordingLogSink
import dev.anydm.engine.platform.virtualClock
import dev.anydm.engine.store.StoreTestBase
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** How the keeper counts usage and pushes effects (#543), on a new database from [newDriver] for each test. */
abstract class RuleKeeperContract : StoreTestBase() {
    private val origin = Instant.parse("2026-10-21T12:00:00Z")
    private val today = "2026-10-21"
    private val allowance = Allowance(2000, AllowancePeriod.Daily, warnPercent = 50)

    // A pause still caps at the slow-mode limit, for the seeds it cannot pause.
    private val paused = RuleEffect(true, SlowMode.DEFAULT_LIMIT, SlowMode.DEFAULT_LIMIT)

    private inner class Fixture(
        test: TestScope,
    ) {
        val store = test.newStore()
        val traffic = RecordingTraffic(store.traffic)
        val meter = TrafficMeter { it.toLocalDateTime(TimeZone.UTC).date.toString() }
        val clock = test.virtualClock(origin)
        val sink = RecordingLogSink()
        val events = mutableListOf<EngineEvent>()
        val applied = mutableListOf<RuleEffect>()
        var network = "unknown"
        var leftovers = 0L
        var gate: CompletableDeferred<Unit>? = null
        val keeper =
            RuleKeeper(
                rules = store.rules,
                settings = store.settings,
                traffic = traffic,
                meter = meter,
                network = { network },
                clock = clock,
                timeZone = { TimeZone.UTC },
                log = Log(sink, clock),
                leftovers = { leftovers },
                emit = { events += it },
                apply = {
                    applied += it
                    gate?.await()
                },
            )

        suspend fun withAllowance() = store.rules.setNetworkPolicy(null, NetworkPolicy(allowance = allowance))

        suspend fun stored(
            bytes: Long,
            network: String = "unknown",
            day: String = today,
        ) = traffic.add(
            TrafficBatch(days = mapOf(Triple(day, network, DownloadKind.DIRECT) to bytes)),
            0,
        )

        fun unsaved(
            bytes: Long,
            network: String = "unknown",
        ) = meter.second(clock.now(), network, listOf(TrafficMeter.Sample(DownloadId(1), DownloadKind.DIRECT, bytes)))

        val used: Long get() =
            keeper.status.value.allowance!!
                .used
    }

    @Test
    fun checkBeforeStartDoesNothing() =
        runTest {
            val f = Fixture(this)

            f.keeper.check() shouldBe null
            f.keeper.refresh() shouldBe null

            f.keeper.status.value shouldBe RuleStatus.None
        }

    @Test
    fun usedIsStoredPlusUnsavedPlusLeftovers() =
        runTest {
            val f = Fixture(this)
            f.withAllowance()
            f.stored(1000)
            f.unsaved(200)
            f.leftovers = 30

            f.keeper.start()

            f.used shouldBe 1230
        }

    @Test
    fun aSaveMovesBytesFromTheMeterToTheCacheWithoutCountingTwice() =
        runTest {
            val f = Fixture(this)
            f.withAllowance()
            f.stored(1000)
            f.unsaved(200)
            f.keeper.start()
            f.used shouldBe 1200
            val reads = f.traffic.networkReads

            f.keeper.saveTraffic {
                val batch = f.meter.drain()
                f.traffic.add(batch, 0)
                batch
            }
            f.keeper.check()

            f.used shouldBe 1200
            f.traffic.networkReads shouldBe reads
            f.unsaved(50)
            f.keeper.check()
            f.used shouldBe 1250
        }

    @Test
    fun aFailedSaveCountsTheBytesOnceFromTheMeter() =
        runTest {
            val f = Fixture(this)
            f.withAllowance()
            f.stored(1000)
            f.unsaved(200)
            f.keeper.start()

            f.keeper.saveTraffic {
                f.meter.restore(f.meter.drain())
                null
            }
            f.keeper.check()
            f.used shouldBe 1200

            shouldThrow<IllegalStateException> {
                f.keeper.saveTraffic {
                    f.meter.restore(f.meter.drain())
                    error("disk gone")
                }
            }
            f.keeper.check()
            f.used shouldBe 1200
        }

    @Test
    fun aNetworkSwitchReadsThatNetworksStoredBytes() =
        runTest {
            val f = Fixture(this)
            f.withAllowance()
            f.stored(100)
            f.stored(500, network = "wifi")
            f.keeper.start()
            f.used shouldBe 100
            val reads = f.traffic.networkReads

            f.network = "wifi"
            f.keeper.check()

            f.used shouldBe 500
            f.keeper.status.value.allowance!!
                .network shouldBe "wifi"
            f.traffic.networkReads shouldBe reads + 1
        }

    @Test
    fun aNewPeriodReadsAgain() =
        runTest {
            val f = Fixture(this)
            f.withAllowance()
            f.stored(1000)
            f.stored(300, day = "2026-10-22")
            f.keeper.start()
            f.used shouldBe 1000
            val reads = f.traffic.networkReads

            advanceTimeBy(1.days)
            f.keeper.check()

            f.used shouldBe 300
            f.traffic.networkReads shouldBe reads + 1
        }

    @Test
    fun startPrimesSoLevelsAlreadyCrossedAreNotTold() =
        runTest {
            val f = Fixture(this)
            f.withAllowance()
            f.stored(2500)

            f.keeper.start()
            f.keeper.check()

            f.events.shouldBeEmpty()
            f.keeper.status.value.allowance!!
                .over shouldBe true
        }

    @Test
    fun aCrossingAfterStartTellsWarningThenReachedOnce() =
        runTest {
            val f = Fixture(this)
            f.withAllowance()
            f.keeper.start()
            f.events.shouldBeEmpty()

            f.unsaved(2500)
            f.keeper.check()
            f.keeper.check()

            f.events.map { it::class } shouldBe listOf(EngineEvent.AllowanceWarning::class, EngineEvent.AllowanceReached::class)
        }

    @Test
    fun pushSendsOnlyAChangedEffect() =
        runTest {
            val f = Fixture(this)
            f.withAllowance()
            f.keeper.start()
            f.keeper.push()
            f.applied.shouldBeEmpty()

            f.unsaved(2500)
            f.keeper.check()
            f.keeper.push()
            f.keeper.check()
            f.keeper.push()

            f.applied shouldBe listOf(paused)
        }

    @Test
    fun aPushSendsTheNewestEffect() =
        runTest {
            val f = Fixture(this)
            f.withAllowance()
            f.keeper.start()
            f.gate = CompletableDeferred()
            val pause = paused

            f.leftovers = 3000
            f.keeper.check()
            backgroundScope.launch { f.keeper.push() }
            runCurrent()
            f.applied shouldBe listOf(pause)

            f.leftovers = 0
            f.keeper.check()
            backgroundScope.launch { f.keeper.push() }
            f.leftovers = 3000
            f.keeper.check()
            backgroundScope.launch { f.keeper.push() }
            f.leftovers = 0
            f.keeper.check()
            backgroundScope.launch { f.keeper.push() }
            runCurrent()
            f.gate!!.complete(Unit)
            runCurrent()

            f.applied.last() shouldBe RuleEffect.None
            f.applied shouldBe listOf(pause, RuleEffect.None)
        }

    @Test
    fun aChangeOfActionIsLoggedOnce() =
        runTest {
            val f = Fixture(this)
            f.keeper.start()
            f.store.rules.addScheduleRule(NewScheduleRule(setOf(DayOfWeek.WEDNESDAY), 0, 0, RuleAction.PAUSE))

            f.keeper.refresh()
            f.keeper.check()
            f.keeper.check()

            f.sink.lines.count { it.endsWith("net rules: PAUSE (SCHEDULE)") } shouldBe 1
        }

    @Test
    fun refreshReadsEditedRules() =
        runTest {
            val f = Fixture(this)
            f.keeper.start()
            f.store.rules.addScheduleRule(NewScheduleRule(setOf(DayOfWeek.WEDNESDAY), 0, 0, RuleAction.PAUSE))

            f.keeper
                .check()!!
                .status.action shouldBe RuleAction.ALLOW
            val decision = f.keeper.refresh()!!

            decision.status.action shouldBe RuleAction.PAUSE
            decision.status.because shouldBe setOf(RuleSource.SCHEDULE)
            f.keeper.status.value shouldBe decision.status
        }
}
