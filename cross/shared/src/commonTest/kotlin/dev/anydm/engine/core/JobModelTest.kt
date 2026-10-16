package dev.anydm.engine.core

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.property.Arb
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

class JobModelTest {
    @Test
    fun segmentTracksHowFarItIsWritten() {
        val segment = Segment(SegmentId(1), FileId(1), start = 100, end = 200)

        segment.watermark shouldBe 100
        segment.size shouldBe 100
        segment.copy(watermark = 150).written shouldBe 50
        segment.copy(watermark = 200).isComplete shouldBe true
        shouldThrow<IllegalArgumentException> { segment.copy(watermark = 99) }
        shouldThrow<IllegalArgumentException> { segment.copy(watermark = 201) }
        shouldThrow<IllegalArgumentException> { segment.copy(end = 100) }
        shouldThrow<IllegalArgumentException> { segment.copy(start = -1) }
    }

    @Test
    fun segmentAcceptsExactlyTheWatermarksInsideItsRange() =
        runTest {
            checkAll(Arb.long(0L..1_000L), Arb.long(1L..1_000L), Arb.long(-10L..2_010L)) { s, len, w ->
                val result = runCatching { Segment(SegmentId(1), FileId(1), s, s + len, w) }
                if (w in s..s + len) {
                    result.isSuccess shouldBe true
                } else {
                    result.exceptionOrNull().shouldBeInstanceOf<IllegalArgumentException>()
                }
            }
        }

    @Test
    fun fileRejectsImpossibleValues() {
        val file = File(FileId(1), DownloadId(1), index = 0, path = "a/b.txt")

        file.selected shouldBe true
        shouldThrow<IllegalArgumentException> { file.copy(index = -1) }
        shouldThrow<IllegalArgumentException> { file.copy(path = " ") }
        shouldThrow<IllegalArgumentException> { file.copy(size = -1) }
    }

    @Test
    fun mirrorRejectsImpossibleValues() {
        val mirror = Mirror(MirrorId(1), DownloadId(1), url = "https://example.com/a", position = 0)

        shouldThrow<IllegalArgumentException> { mirror.copy(url = "") }
        shouldThrow<IllegalArgumentException> { mirror.copy(position = -1) }
    }

    @Test
    fun attemptRecordsHowATryEnded() {
        val start = Instant.fromEpochMilliseconds(1_000)
        val attempt = Attempt(AttemptId(1), DownloadId(1), startedAt = start)

        val failed = attempt.copy(endedAt = start + 5.milliseconds, errorClass = ErrorClass.GONE, message = "404")
        failed.errorClass shouldBe ErrorClass.GONE
        failed.message shouldBe "404"
        // Times are wall-clock: a clock set back is an expected outcome, not a broken invariant.
        attempt.copy(endedAt = start - 1.milliseconds).endedAt shouldBe start - 1.milliseconds
    }

    @Test
    fun errorClassesAreTheSpecsSeven() {
        ErrorClass.entries.map { it.name } shouldBe
            listOf("TRANSIENT", "RATE_LIMITED", "AUTH", "GONE", "DISK_FULL", "FORMAT_WITHHELD", "INTEGRITY")
    }
}
