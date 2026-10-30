package dev.anydm.engine.http

import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class BlockPlannerTest {
    private val sizes = Arb.long(1L..100L * (1L shl 30))
    private val connections = Arb.int(1..16)

    @Test
    fun blocksCoverTheFileExactly() =
        runTest {
            checkAll(sizes, connections) { size, c ->
                val blocks = planBlocks(size, c)
                blocks.first().start shouldBe 0
                blocks.last().end shouldBe size
                blocks.zipWithNext().all { (a, b) -> a.end == b.start } shouldBe true
            }
        }

    @Test
    fun blockSizesStayInBounds() =
        runTest {
            checkAll(sizes, connections) { size, c ->
                val b = blockSize(size, c)
                (b in MIN_BLOCK..MAX_BLOCK && b % BLOCK_ALIGN == 0L) shouldBe true
                val blocks = planBlocks(size, c)
                blocks.dropLast(1).all { it.end - it.start == b } shouldBe true
                val last = blocks.last().end - blocks.last().start
                (last in 1..b) shouldBe true
            }
        }

    @Test
    fun aTableOfSizes() {
        val mib = 1L shl 20
        blockSize(10 * mib, 4) shouldBe mib
        blockSize(100 * mib, 4) shouldBe 6_553_600
        blockSize(10L * 1024 * mib, 4) shouldBe 8 * mib
        blockSize(500L * 1024, 1) shouldBe mib
        planBlocks(500L * 1024, 1).map { it.start to it.end } shouldBe listOf(0L to 500L * 1024)
    }
}
