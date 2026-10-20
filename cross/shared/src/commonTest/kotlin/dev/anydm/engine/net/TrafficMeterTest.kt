package dev.anydm.engine.net

import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadKind.DIRECT
import dev.anydm.engine.core.DownloadKind.SITE
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** The traffic meter (NET-04), with UTC days so the tests don't depend on the machine's zone. */
class TrafficMeterTest {
    private val a = DownloadId(1)
    private val b = DownloadId(2)
    private val t0 = Instant.parse("2026-10-18T12:00:00Z")
    private val day = "2026-10-18"

    private fun meter() = TrafficMeter { it.toString().substring(0, 10) }

    @Test
    fun bytesAddUpByDayNetworkKindAndDownload() {
        val meter = meter()
        meter.second(t0, "en0", listOf(TrafficMeter.Sample(a, DIRECT, 100), TrafficMeter.Sample(b, SITE, 50)))
        meter.second(t0 + 1.seconds, "en0", listOf(TrafficMeter.Sample(a, DIRECT, 20)))

        val batch = meter.drain()
        batch.days shouldBe mapOf(Triple(day, "en0", DIRECT) to 120L, Triple(day, "en0", SITE) to 50L)
        batch.downloads shouldBe mapOf(a to 120L, b to 50L)
    }

    @Test
    fun uploadIsCountedByDayNetworkKindAndDownload() {
        val meter = meter()
        meter.second(t0, "en0", listOf(TrafficMeter.Sample(a, DIRECT, 100, bytesUp = 30), TrafficMeter.Sample(b, SITE, 0, bytesUp = 5)))
        meter.second(t0 + 1.seconds, "en0", listOf(TrafficMeter.Sample(a, DIRECT, 0, bytesUp = 2)))

        val batch = meter.drain()
        batch.days shouldBe mapOf(Triple(day, "en0", DIRECT) to 100L)
        batch.daysUp shouldBe mapOf(Triple(day, "en0", DIRECT) to 32L, Triple(day, "en0", SITE) to 5L)
        batch.downloads shouldBe mapOf(a to 100L)
        batch.downloadsUp shouldBe mapOf(a to 32L, b to 5L)
    }

    @Test
    fun uploadDoesNotChangeSpeedOrPeaks() {
        val meter = meter()
        meter.second(t0, "en0", listOf(TrafficMeter.Sample(a, DIRECT, 100, bytesUp = 900)))

        val batch = meter.drain()
        batch.speedDays shouldBe mapOf(day to SpeedDelta(100, 100, 1))
        batch.speedMinutes shouldBe mapOf(epochMinute(t0) to SpeedDelta(100, 100, 0))
    }

    @Test
    fun aSampleWithOnlyUploadAddsNoSpeedSecond() {
        val meter = meter()
        meter.second(t0, "en0", listOf(TrafficMeter.Sample(b, SITE, 0, bytesUp = 7)))

        val batch = meter.drain()
        batch.speedDays shouldBe emptyMap()
        batch.speedMinutes shouldBe emptyMap()
        batch.daysUp shouldBe mapOf(Triple(day, "en0", SITE) to 7L)
        batch.isEmpty shouldBe false
    }

    @Test
    fun leftoverUploadCountsWithoutBeingASecond() {
        val meter = meter()
        meter.bytes(t0, "en0", listOf(TrafficMeter.Sample(a, DIRECT, 3, bytesUp = 4)))

        val batch = meter.drain()
        batch.days shouldBe mapOf(Triple(day, "en0", DIRECT) to 3L)
        batch.daysUp shouldBe mapOf(Triple(day, "en0", DIRECT) to 4L)
        batch.downloadsUp shouldBe mapOf(a to 4L)
    }

    @Test
    fun drainAndRestoreKeepUpload() {
        val meter = meter()
        meter.second(t0, "en0", listOf(TrafficMeter.Sample(a, DIRECT, 10, bytesUp = 6)))
        val batch = meter.drain()
        meter.drain().isEmpty shouldBe true

        meter.restore(batch)
        meter.second(t0 + 1.seconds, "en0", listOf(TrafficMeter.Sample(a, DIRECT, 0, bytesUp = 1)))

        val again = meter.drain()
        again.daysUp shouldBe mapOf(Triple(day, "en0", DIRECT) to 7L)
        again.downloadsUp shouldBe mapOf(a to 7L)
    }

    @Test
    fun peakIsTheLargestSecondAndQuietSecondsArentActive() {
        val meter = meter()
        meter.second(t0, "en0", listOf(TrafficMeter.Sample(a, DIRECT, 100), TrafficMeter.Sample(b, SITE, 50)))
        meter.second(t0 + 1.seconds, "en0", listOf(TrafficMeter.Sample(a, DIRECT, 0)))
        meter.second(t0 + 2.seconds, "en0", listOf(TrafficMeter.Sample(a, DIRECT, 20)))

        val batch = meter.drain()
        batch.speedDays shouldBe mapOf(day to SpeedDelta(170, 150, 2))
        batch.speedMinutes shouldBe mapOf(epochMinute(t0) to SpeedDelta(170, 150, 0))
    }

    @Test
    fun aSecondAfterMidnightGoesToTheNewDay() {
        val meter = meter()
        meter.second(Instant.parse("2026-10-18T23:59:59Z"), "en0", listOf(TrafficMeter.Sample(a, DIRECT, 1)))
        meter.second(Instant.parse("2026-10-19T00:00:00Z"), "en0", listOf(TrafficMeter.Sample(a, DIRECT, 2)))

        meter.drain().days shouldBe
            mapOf(Triple("2026-10-18", "en0", DIRECT) to 1L, Triple("2026-10-19", "en0", DIRECT) to 2L)
    }

    @Test
    fun leftoverBytesCountWithoutBeingASecond() {
        val meter = meter()
        meter.second(t0, "en0", listOf(TrafficMeter.Sample(a, DIRECT, 100)))
        meter.bytes(t0 + 1.seconds, "en0", listOf(TrafficMeter.Sample(a, DIRECT, 7)))

        val batch = meter.drain()
        batch.days shouldBe mapOf(Triple(day, "en0", DIRECT) to 107L)
        batch.downloads shouldBe mapOf(a to 107L)
        batch.speedDays shouldBe mapOf(day to SpeedDelta(107, 100, 1))
        batch.speedMinutes shouldBe mapOf(epochMinute(t0) to SpeedDelta(107, 100, 0))
    }

    @Test
    fun drainEmptiesTheMeter() {
        val meter = meter()
        meter.second(t0, "en0", listOf(TrafficMeter.Sample(a, DIRECT, 5)))
        meter.drain().isEmpty shouldBe false
        meter.drain().isEmpty shouldBe true
    }

    @Test
    fun restoreMergesABatchBack() {
        val meter = meter()
        meter.second(t0, "en0", listOf(TrafficMeter.Sample(a, DIRECT, 100)))
        val failed = meter.drain()
        meter.restore(failed)
        meter.second(t0 + 1.seconds, "en0", listOf(TrafficMeter.Sample(a, DIRECT, 30)))

        val batch = meter.drain()
        batch.days shouldBe mapOf(Triple(day, "en0", DIRECT) to 130L)
        batch.downloads shouldBe mapOf(a to 130L)
        batch.speedDays shouldBe mapOf(day to SpeedDelta(130, 100, 2))
        batch.speedMinutes shouldBe mapOf(epochMinute(t0) to SpeedDelta(130, 100, 0))
    }
}
