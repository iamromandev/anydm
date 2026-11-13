package dev.anydm.engine.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val MIB = 1L shl 20
private const val ALIGN = 64L * 1024

class SpanPlannerTest {
    @Test
    fun thirtyTwoMibOverFourConnectionsIsFourEqualSpans() {
        assertEquals(
            listOf(
                Span(0, 8 * MIB - 1, 0),
                Span(8 * MIB, 16 * MIB - 1, 0),
                Span(16 * MIB, 24 * MIB - 1, 0),
                Span(24 * MIB, 32 * MIB - 1, 0),
            ),
            initialSpans(32 * MIB, 4),
        )
    }

    @Test
    fun aFileTooSmallToSplitIsOneSpan() {
        assertEquals(listOf(Span(0, MIB + MIB / 2 - 1, 0)), initialSpans(MIB + MIB / 2, 8))
    }

    @Test
    fun theNumberOfSpansIsCappedSoEachHoldsAtLeastTheMinimumSplit() {
        val spans = initialSpans(5 * MIB, 8)
        assertEquals(5, spans.size)
        assertTrue(spans.all { it.end - it.start + 1 >= MIB })
    }

    @Test
    fun theLastSpanTakesTheRemainder() {
        val spans = initialSpans(8 * MIB + 7, 4)
        assertEquals(4, spans.size)
        assertEquals(List(3) { 2 * MIB + 1 }, spans.take(3).map { it.end - it.start + 1 })
        assertEquals(8 * MIB + 6, spans.last().end)
        // contiguous, in order, nothing lost
        assertEquals(0L, spans.first().start)
        spans.zipWithNext { a, b -> assertEquals(a.end + 1, b.start) }
    }

    @Test
    fun oneConnectionIsOneSpanOfTheWholeFile() {
        assertEquals(listOf(Span(0, 100 * MIB - 1, 0)), initialSpans(100 * MIB, 1))
    }

    @Test
    fun nothingToFetchIsNoSpans() {
        assertEquals(emptyList(), initialSpans(0, 4))
    }

    @Test
    fun stealTakesTheSecondHalfOfTheRemainingBytesOfTheSpanWithMostLeft() {
        val spans = listOf(Span(0, 8 * MIB - 1, 7 * MIB), Span(8 * MIB, 16 * MIB - 1, 2 * MIB), Span(16 * MIB, 24 * MIB - 1, 8 * MIB))
        val s = assertNotNull(steal(spans))
        assertEquals(1, s.index) // 6 MiB left, the most of the unfinished ones
        // remaining bytes are 10 MiB..16 MiB-1; the half starts 13 MiB in (already a 64 KiB multiple)
        assertEquals(13 * MIB, s.taken.start)
        assertEquals(16 * MIB - 1, s.taken.end)
        assertEquals(0L, s.taken.done)
        assertEquals(13 * MIB - 1, s.newEnd)
    }

    @Test
    fun theSplitPointIsRoundedToSixtyFourKib() {
        val s = assertNotNull(steal(listOf(Span(0, 5 * MIB + 12_345, 1_000))))
        assertEquals(0L, s.taken.start % ALIGN)
        assertEquals(s.taken.start - 1, s.newEnd)
        assertEquals(5 * MIB + 12_345, s.taken.end)
        assertTrue(s.taken.start > 1_000)
        assertTrue(s.taken.end - s.taken.start + 1 >= MIB)
    }

    @Test
    fun noStealWhenTheHalfIsUnderTheMinimumSplit() {
        assertNull(steal(listOf(Span(0, 2 * MIB - 3, 0)))) // half is 1 MiB - 2
        assertNull(steal(listOf(Span(0, 4 * MIB - 1, 2 * MIB + 5), Span(4 * MIB, 5 * MIB - 1, 0))))
    }

    @Test
    fun stealWorksAtExactlyTwiceTheMinimumSplit() {
        val s = assertNotNull(steal(listOf(Span(0, 2 * MIB - 1, 0))))
        assertEquals(MIB, s.taken.start)
        assertEquals(MIB - 1, s.newEnd)
    }

    @Test
    fun finishedSpansAreNeverStolenFrom() {
        assertNull(steal(listOf(Span(0, 9 * MIB - 1, 9 * MIB))))
        assertNull(steal(emptyList()))
    }

    @Test
    fun aSmallerMinimumSplitIsHonoured() {
        val s = assertNotNull(steal(listOf(Span(0, 999, 0)), minSplit = 100))
        assertEquals(500, s.taken.start)
        assertEquals(499, s.newEnd)
    }

    @Test
    fun doneOfSumsTheBytesOnDisk() {
        assertEquals(30L, doneOf(listOf(Span(0, 99, 10), Span(100, 199, 20), Span(200, 299, 0))))
        assertEquals(0L, doneOf(emptyList()))
    }

    @Test
    fun compactMergesTouchingFinishedSpansOnly() {
        val spans =
            listOf(
                Span(0, 9, 10),
                Span(10, 19, 10),
                Span(20, 29, 4),
                Span(30, 39, 10),
                Span(40, 49, 10),
                Span(50, 59, 0),
                Span(60, 69, 10),
            )
        assertEquals(
            listOf(Span(0, 19, 20), Span(20, 29, 4), Span(30, 49, 20), Span(50, 59, 0), Span(60, 69, 10)),
            compact(spans),
        )
    }

    @Test
    fun compactKeepsTheBytesAndHandlesAnEmptyList() {
        assertEquals(emptyList(), compact(emptyList()))
        val spans = listOf(Span(0, 9, 3), Span(10, 19, 0))
        assertEquals(spans, compact(spans))
    }

    @Test
    fun finishedOverIsTrueOnlyForFinishedRangesThatCoverTheWholeFile() {
        assertTrue(finishedOver(listOf(Span(0, 9, 10), Span(10, 19, 10)), 20))
        assertTrue(finishedOver(listOf(Span(10, 19, 10), Span(0, 9, 10)), 20), "in any order")
        assertTrue(finishedOver(listOf(Span(0, 19, 20)), 20))
        assertFalse(finishedOver(listOf(Span(0, 9, 10), Span(10, 19, 9)), 20), "one byte missing")
        assertFalse(finishedOver(listOf(Span(0, 9, 10)), 20), "stops short of the total")
        assertFalse(finishedOver(listOf(Span(0, 9, 10), Span(11, 19, 9)), 20), "a gap")
        assertFalse(finishedOver(listOf(Span(0, 9, 10), Span(5, 19, 15)), 20), "an overlap")
        assertFalse(finishedOver(listOf(Span(0, 19, 20)), 19), "past the total")
        assertFalse(finishedOver(emptyList(), 20))
        assertFalse(finishedOver(listOf(Span(0, 9, 10)), 0))
    }

    @Test
    fun contentRangeIsParsed() {
        assertEquals(ContentRange(10, 19, 100), parseContentRange("bytes 10-19/100"))
        assertEquals(ContentRange(0, 9, null), parseContentRange("bytes 0-9/*"))
        assertNull(parseContentRange("bytes */100"))
        assertNull(parseContentRange("nonsense"))
    }
}
