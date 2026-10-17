package dev.anydm.engine.http

import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.File
import dev.anydm.engine.core.FileId
import dev.anydm.engine.core.Segment
import dev.anydm.engine.core.SegmentId
import dev.anydm.engine.core.aDownload
import dev.anydm.engine.protocol.PlanContext
import dev.anydm.engine.protocol.ProbeResult
import dev.anydm.engine.protocol.StepResult
import dev.anydm.engine.protocol.TransferFile
import dev.anydm.engine.protocol.TransferPlan
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.io.files.Path
import kotlin.test.Test

class HttpPlanTest {
    private val mib = 1L shl 20
    private val download = aDownload().copy(fileName = null)

    private fun probe(
        size: Long? = 10 * mib,
        ranges: Boolean = true,
    ) = HttpProbe("f.bin", size, null, ranges, "https://h/f.bin", null)

    private fun planned(
        probe: ProbeResult,
        context: PlanContext = PlanContext(download),
        connections: Int = 4,
    ) = HttpProtocol(FakeTransport { error("no requests") }, connections = { connections })
        .plan(probe, context)
        .shouldBeInstanceOf<StepResult.Ok<TransferPlan>>()
        .value.files
        .single()

    @Test
    fun aRangedFileIsSplitIntoBlocks() {
        val file = planned(probe())
        file.index shouldBe 0
        file.path shouldBe "f.bin"
        file.size shouldBe 10 * mib
        file.segments.map { it.start to it.end } shouldBe (0 until 10).map { it * mib to (it + 1) * mib }
    }

    @Test
    fun noSegmentsWithoutRangesOrSize() {
        planned(probe(ranges = false)).run {
            segments.shouldBeEmpty()
            size shouldBe 10 * mib
        }
        planned(probe(size = null)).segments.shouldBeEmpty()
        planned(probe(size = 0)).segments.shouldBeEmpty()
    }

    @Test
    fun theUsersFileNameWins() {
        planned(probe(), PlanContext(download.copy(fileName = "mine.bin"))).path shouldBe "mine.bin"
    }

    private fun stored(size: Long): TransferFile {
        val file = File(FileId(1), DownloadId(1), 0, "f.bin", size)
        val segments =
            planBlocks(size, 1).mapIndexed { i, s -> Segment(SegmentId(i + 1L), file.id, s.start, s.end, s.start + 7) }
        return TransferFile(file, segments, Path("/d/f.bin.1.anydm"), Path("/d/f.bin"))
    }

    @Test
    fun storedBlocksComeBackUnchanged() {
        val stored = stored(10 * mib)
        val file = planned(probe(), PlanContext(download, listOf(stored)), connections = 8)
        file.segments.map { it.start to it.end } shouldBe stored.segments.map { it.start to it.end }
    }

    @Test
    fun aNewSizeGetsAFreshPlan() {
        val file = planned(probe(size = 12 * mib), PlanContext(download, listOf(stored(10 * mib))))
        file.segments shouldBe planBlocks(12 * mib, 4)
    }

    @Test
    fun aProbeOfAnotherKindIsABug() {
        val other =
            object : ProbeResult {
                override val name = "x"
                override val size: Long? = 1
            }
        shouldThrow<IllegalStateException> { HttpProtocol(FakeTransport { error("none") }).plan(other, PlanContext(download)) }
    }
}
