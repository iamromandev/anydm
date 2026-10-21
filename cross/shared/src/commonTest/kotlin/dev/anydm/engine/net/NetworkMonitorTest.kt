package dev.anydm.engine.net

import dev.anydm.engine.log.Log
import dev.anydm.engine.log.RecordingLogSink
import dev.anydm.engine.platform.FakeOnlineProbe
import dev.anydm.engine.platform.virtualClock
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** The monitor's polls and debounce (NET-13), on virtual time. */
class NetworkMonitorTest {
    private val t0 = Instant.parse("2026-10-21T12:00:00Z")

    private class Fixture(
        test: TestScope,
        t0: Instant,
    ) {
        val probe = FakeOnlineProbe()
        var key = "en0"
        var freshCalls = 0
        var freshFailure: Throwable? = null
        val identity =
            object : NetworkIdentity {
                override fun current() = key

                override fun fresh(): String {
                    freshCalls++
                    freshFailure?.let { throw it }
                    return key
                }
            }
        val clock = test.virtualClock(t0)
        val sink = RecordingLogSink()
        val monitor = NetworkMonitor(probe, identity, clock, Log(sink, clock))
    }

    @Test
    fun firstTakesOnePollAsTrue() =
        runTest {
            val f = Fixture(this, t0)
            f.probe.online = false

            val state = f.monitor.first()

            state shouldBe NetworkState(online = false, key = "unknown", metered = null, since = t0)
            f.monitor.state.value shouldBe state
            f.monitor.observed.value shouldBe Observed(state, 0)
            f.probe.calls shouldBe 1
        }

    @Test
    fun oneMissKeepsOnlineAndTheSecondGoesOffline() =
        runTest {
            val f = Fixture(this, t0)
            f.monitor.first().online shouldBe true
            f.probe.online = false

            advanceTimeBy(3.seconds)
            f.monitor.tick().online shouldBe true

            advanceTimeBy(3.seconds)
            val state = f.monitor.tick()
            state.online shouldBe false
            state.since shouldBe t0 + 6.seconds
            f.monitor.observed.value.drops shouldBe 1
        }

    @Test
    fun oneGoodPollReturns() =
        runTest {
            val f = Fixture(this, t0)
            f.monitor.first()
            f.probe.online = false
            f.monitor.tick()
            f.monitor.tick().online shouldBe false

            f.probe.online = true
            advanceTimeBy(3.seconds)
            val state = f.monitor.tick()

            state.online shouldBe true
            state.key shouldBe "en0"
            state.since shouldBe t0 + 3.seconds
        }

    @Test
    fun pollNowConfirmsOfflineAtOnce() =
        runTest {
            val f = Fixture(this, t0)
            f.monitor.first()
            f.probe.online = false

            f.monitor.pollNow().online shouldBe false
            f.monitor.observed.value.drops shouldBe 1

            f.monitor.pollNow().online shouldBe false
            f.monitor.observed.value.drops shouldBe 1
        }

    @Test
    fun theKeyFollowsFreshOnEveryOnlineTick() =
        runTest {
            val f = Fixture(this, t0)
            f.monitor.first()
            f.freshCalls shouldBe 1

            f.monitor.tick()
            f.freshCalls shouldBe 2
            f.key = "en7"
            advanceTimeBy(3.seconds)
            f.monitor.tick().key shouldBe "en7"
            f.freshCalls shouldBe 3
        }

    @Test
    fun sinceMovesOnlyOnARealChange() =
        runTest {
            val f = Fixture(this, t0)
            f.monitor.first()

            advanceTimeBy(3.seconds)
            f.monitor.tick().since shouldBe t0

            f.key = "en7"
            advanceTimeBy(3.seconds)
            f.monitor.tick().since shouldBe t0 + 6.seconds

            advanceTimeBy(3.seconds)
            f.monitor.tick().since shouldBe t0 + 6.seconds
        }

    @Test
    fun offlineHasUnknownKeyAndNoMetered() =
        runTest {
            val f = Fixture(this, t0)
            f.monitor.first()
            f.monitor.setMetered("en0", true)
            f.monitor.state.value.metered shouldBe true

            f.probe.online = false
            val state = f.monitor.pollNow()

            state.key shouldBe "unknown"
            state.metered shouldBe null
        }

    @Test
    fun aKeyChangeClearsMetered() =
        runTest {
            val f = Fixture(this, t0)
            f.monitor.first()
            f.monitor.setMetered("en0", true)

            f.key = "en7"
            advanceTimeBy(3.seconds)

            f.monitor.tick().metered shouldBe null
        }

    @Test
    fun setMeteredForAnotherKeyIsIgnored() =
        runTest {
            val f = Fixture(this, t0)
            f.monitor.first()

            f.monitor.setMetered("en9", true)
            f.monitor.state.value.metered shouldBe null

            f.monitor.setMetered("en0", true)
            f.monitor.state.value.metered shouldBe true
            f.monitor.observed.value.state.metered shouldBe true
        }

    @Test
    fun aThrowingProbeIsAMiss() =
        runTest {
            val f = Fixture(this, t0)
            f.monitor.first()
            f.probe.failure = IllegalStateException("no route")

            f.monitor.tick().online shouldBe true
            f.monitor.tick().online shouldBe false
        }

    @Test
    fun aThrowingIdentityGivesUnknown() =
        runTest {
            val f = Fixture(this, t0)
            f.freshFailure = IllegalStateException("no interface")

            f.monitor.first().key shouldBe "unknown"
        }

    @Test
    fun theChangesAreLogged() =
        runTest {
            val f = Fixture(this, t0)
            f.monitor.first()
            f.key = "en7"
            advanceTimeBy(3.seconds)
            f.monitor.tick()
            f.probe.online = false
            f.monitor.pollNow()

            f.sink.lines.size shouldBe 3
            f.sink.lines[0].endsWith("net network: online (en0)") shouldBe true
            f.sink.lines[1].endsWith("net network: en0 -> en7") shouldBe true
            f.sink.lines[2].endsWith("net network: offline") shouldBe true
        }
}
