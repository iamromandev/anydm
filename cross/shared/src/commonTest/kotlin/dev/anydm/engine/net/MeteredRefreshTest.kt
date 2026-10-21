package dev.anydm.engine.net

import dev.anydm.engine.log.Log
import dev.anydm.engine.log.RecordingLogSink
import dev.anydm.engine.platform.FakeMeteredDetector
import dev.anydm.engine.platform.FakeOnlineProbe
import dev.anydm.engine.platform.MeteredDetector
import dev.anydm.engine.platform.virtualClock
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** The metered refresh (NET-14): when it asks, what it keeps when the detector fails, one at a time. */
class MeteredRefreshTest {
    private val t0 = Instant.parse("2026-10-21T12:00:00Z")

    private class Fixture(
        test: TestScope,
        t0: Instant,
        detector: MeteredDetector? = null,
    ) {
        val probe = FakeOnlineProbe()
        var key = "en0"
        val clock = test.virtualClock(t0)
        val fake = FakeMeteredDetector(mutableMapOf("en0" to true, "en7" to false))
        val monitor = NetworkMonitor(probe, { key }, clock, Log(RecordingLogSink(), clock))
        val refresh = MeteredRefresh(detector ?: fake, monitor, clock, Log(RecordingLogSink(), clock), test.backgroundScope)
    }

    @Test
    fun firstDetectsAndWaits() =
        runTest {
            val f = Fixture(this, t0)
            f.fake.delay = 2.seconds
            val state = f.monitor.first()

            f.refresh.first(state)

            f.fake.calls shouldBe listOf("en0")
            f.monitor.state.value.metered shouldBe true
        }

    @Test
    fun refreshesAfterFiveMinutes() =
        runTest {
            val f = Fixture(this, t0)
            f.refresh.first(f.monitor.first())

            advanceTimeBy(4.minutes + 59.seconds)
            f.refresh.refresh(f.monitor.state.value)
            runCurrent()
            f.fake.calls.size shouldBe 1

            advanceTimeBy(1.seconds)
            f.refresh.refresh(f.monitor.state.value)
            runCurrent()
            f.fake.calls.size shouldBe 2
        }

    @Test
    fun refreshesOnAKeyChangeAndOnComingBackOnline() =
        runTest {
            val f = Fixture(this, t0)
            f.refresh.first(f.monitor.first())

            f.key = "en7"
            advanceTimeBy(3.seconds)
            f.refresh.refresh(f.monitor.tick())
            runCurrent()
            f.fake.calls shouldBe listOf("en0", "en7")

            f.probe.online = false
            f.refresh.refresh(f.monitor.pollNow())
            runCurrent()
            f.fake.calls.size shouldBe 2

            f.probe.online = true
            f.refresh.refresh(f.monitor.pollNow())
            runCurrent()
            f.fake.calls shouldBe listOf("en0", "en7", "en7")
        }

    @Test
    fun aSlowDetectorKeepsThePreviousValue() =
        runTest {
            val f = Fixture(this, t0)
            f.refresh.first(f.monitor.first())
            f.monitor.state.value.metered shouldBe true
            f.fake.delay = 10.seconds

            advanceTimeBy(5.minutes)
            f.refresh.refresh(f.monitor.state.value)
            advanceTimeBy(6.seconds)
            runCurrent()

            f.fake.calls.size shouldBe 2
            f.monitor.state.value.metered shouldBe true
        }

    @Test
    fun aNewKeyWithNoAnswerIsNull() =
        runTest {
            val f = Fixture(this, t0)
            f.fake.answers.remove("en7")
            f.refresh.first(f.monitor.first())

            f.key = "en7"
            advanceTimeBy(3.seconds)
            f.refresh.refresh(f.monitor.tick())
            runCurrent()

            f.monitor.state.value.key shouldBe "en7"
            f.monitor.state.value.metered shouldBe null
        }

    @Test
    fun offlineNeverDetects() =
        runTest {
            val f = Fixture(this, t0)
            f.probe.online = false
            val state = f.monitor.first()

            f.refresh.first(state)
            f.refresh.refresh(state)
            runCurrent()

            f.fake.calls shouldBe emptyList()
        }

    @Test
    fun oneDetectionAtATime() =
        runTest {
            val f = Fixture(this, t0)
            f.fake.delay = 4.seconds
            val state = f.monitor.first()

            f.refresh.refresh(state)
            f.refresh.refresh(state)
            runCurrent()
            advanceTimeBy(4.seconds)

            f.fake.calls.size shouldBe 1
        }

    @Test
    fun aKnownKeyGetsItsCachedValueAtOnce() =
        runTest {
            val f = Fixture(this, t0)
            f.refresh.first(f.monitor.first())
            f.fake.delay = 4.seconds

            f.key = "en7"
            advanceTimeBy(3.seconds)
            f.refresh.refresh(f.monitor.tick())
            runCurrent()
            f.monitor.state.value.metered shouldBe null

            f.key = "en0"
            advanceTimeBy(3.seconds)
            f.refresh.refresh(f.monitor.tick())
            runCurrent()

            f.monitor.state.value.key shouldBe "en0"
            f.monitor.state.value.metered shouldBe true
        }

    @Test
    fun aThrowingDetectorIsNoAnswer() =
        runTest {
            val f = Fixture(this, t0, MeteredDetector { error("no nmcli") })

            f.refresh.first(f.monitor.first())

            f.monitor.state.value.metered shouldBe null
        }
}
