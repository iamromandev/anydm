package dev.anydm.engine.protocol

import dev.anydm.engine.core.Download
import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.File
import dev.anydm.engine.core.FileId
import dev.anydm.engine.core.Mirror
import dev.anydm.engine.core.MirrorId
import dev.anydm.engine.core.Segment
import dev.anydm.engine.core.SegmentId
import dev.anydm.engine.store.NewSegment
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.Test

class TransferPlanTest {
    private fun segments(vararg edges: Long) = edges.toList().zipWithNext { a, b -> NewSegment(a, b) }

    @Test
    fun plannedSegmentsMustCoverTheFileInOrder() {
        PlannedFile(0, "f", 10, segments(0, 5, 10)) // fine
        shouldThrow<IllegalArgumentException> { PlannedFile(0, "f", 10, listOf(NewSegment(0, 4), NewSegment(5, 10))) } // gap
        shouldThrow<IllegalArgumentException> { PlannedFile(0, "f", 10, listOf(NewSegment(0, 6), NewSegment(5, 10))) } // overlap
        shouldThrow<IllegalArgumentException> { PlannedFile(0, "f", 10, segments(0, 5, 9)) } // short cover
        shouldThrow<IllegalArgumentException> { PlannedFile(0, "f", 10, segments(1, 10)) } // does not start at zero
        shouldThrow<IllegalArgumentException> { PlannedFile(0, "f", null, segments(0, 5, 10)) } // size unknown
        shouldThrow<IllegalArgumentException> { PlannedFile(0, "f", 10, listOf(NewSegment(5, 10), NewSegment(0, 5))) } // out of order
    }

    @Test
    fun aPlanNeedsDistinctFiles() {
        TransferPlan(listOf(PlannedFile(0, "a"), PlannedFile(1, "b"))) // fine
        shouldThrow<IllegalArgumentException> { TransferPlan(emptyList()) }
        shouldThrow<IllegalArgumentException> { TransferPlan(listOf(PlannedFile(0, "a"), PlannedFile(0, "b"))) }
        shouldThrow<IllegalArgumentException> { TransferPlan(listOf(PlannedFile(0, "a"), PlannedFile(1, "a"))) }
    }

    @Test
    fun aFailureNeedsAMessage() {
        shouldThrow<IllegalArgumentException> { StepResult.Failed(null, " ") }
    }

    @Test
    fun aPlanKeepsItsOwnCopyOfTheList() {
        val files = mutableListOf(PlannedFile(0, "a"))
        val plan = TransferPlan(files)
        files += PlannedFile(1, "b")
        files.clear()
        plan.files shouldBe listOf(PlannedFile(0, "a"))
        plan shouldBe TransferPlan(listOf(PlannedFile(0, "a")))
    }

    @Test
    fun aTransferAndItsFilesKeepTheirOwnCopyOfTheLists() {
        val download = Download(DownloadId(1), DownloadKind.DIRECT)
        val row = File(FileId(1), download.id, 0, "a")
        val segments = mutableListOf(Segment(SegmentId(1), row.id, 0, 10))
        val part = TransferFile(row, segments, Path("/x/a.1.anydm"), Path("/x/a"))
        val parts = mutableListOf(part)
        val transfer = Transfer(download, Mirror(MirrorId(1), download.id, "https://example.com/a", 0), FakeProbe("a", 10), parts)
        segments.clear()
        parts.clear()
        part.segments.size shouldBe 1
        transfer.files shouldBe listOf(part)
    }

    @Test
    fun aFreshFileIsAPlannedFileLikeAnyOther() {
        PlannedFile(0, "a", 10, splitEvenly(10, 2), fresh = true).fresh shouldBe true
        PlannedFile(0, "a").fresh shouldBe false
    }

    @Test
    fun fakePlansAreAlwaysValid() =
        runTest {
            checkAll(Arb.long(0L..100_000L), Arb.int(1..16)) { size, n -> PlannedFile(0, "f", size, splitEvenly(size, n)) }
        }
}
