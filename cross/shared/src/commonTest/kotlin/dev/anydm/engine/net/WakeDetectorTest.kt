package dev.anydm.engine.net

import io.kotest.matchers.shouldBe
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Wake detection (NET-12): a gap bigger than tick plus gap on either clock. */
class WakeDetectorTest {
    private val t0 = Instant.parse("2026-10-21T12:00:00Z")

    @Test
    fun theFirstCallIsNotAWake() {
        WakeDetector().observe(t0, Duration.ZERO) shouldBe null
    }

    @Test
    fun regularTicksAreNotAWake() {
        val detector = WakeDetector()
        (0..9).map { detector.observe(t0 + (it * 5).seconds, (it * 5).seconds) } shouldBe List(10) { null }
    }

    @Test
    fun aWallJumpWithAStoppedMonotonicClockIsAWake() {
        val detector = WakeDetector()
        detector.observe(t0, Duration.ZERO)
        detector.observe(t0 + 60.seconds, 5.seconds) shouldBe 60.seconds
    }

    @Test
    fun bothClocksJumpingIsAWake() {
        val detector = WakeDetector()
        detector.observe(t0, Duration.ZERO)
        detector.observe(t0 + 60.seconds, 60.seconds) shouldBe 60.seconds
    }

    @Test
    fun theLimitIsTickPlusGap() {
        val detector = WakeDetector()
        detector.observe(t0, Duration.ZERO)
        detector.observe(t0 + 34.seconds, Duration.ZERO) shouldBe null
        val second = WakeDetector()
        second.observe(t0, Duration.ZERO)
        second.observe(t0 + 35.seconds, Duration.ZERO) shouldBe null
        val third = WakeDetector()
        third.observe(t0, Duration.ZERO)
        third.observe(t0 + 36.seconds, Duration.ZERO) shouldBe 36.seconds
    }

    @Test
    fun aWallClockSetBackIsIgnored() {
        val detector = WakeDetector()
        detector.observe(t0, Duration.ZERO)
        detector.observe(t0 - 1.hours, 5.seconds) shouldBe null
        detector.observe(t0 - 1.hours + 5.seconds, 10.seconds) shouldBe null
    }

    @Test
    fun aDstChangeoverIsNotAJump() {
        val before = Instant.parse("2026-10-25T00:59:58Z")
        val after = Instant.parse("2026-10-25T01:00:03Z")
        val berlin = TimeZone.of("Europe/Berlin")
        // The local clock steps back an hour right there; the instant does not.
        (after.toLocalDateTime(berlin) < before.toLocalDateTime(berlin)) shouldBe true
        val detector = WakeDetector()
        detector.observe(before, Duration.ZERO)
        detector.observe(after, 5.seconds) shouldBe null
    }

    @Test
    fun anHourOnTheWallClockIsAWakeWhateverTheZone() {
        val detector = WakeDetector()
        detector.observe(Instant.parse("2026-10-25T00:30:00Z"), Duration.ZERO)
        detector.observe(Instant.parse("2026-10-25T01:30:00Z"), 5.seconds) shouldBe 1.hours
    }
}
