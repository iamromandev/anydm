package dev.anydm.engine.torrent

import java.lang.management.ManagementFactory
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/** What it costs, in memory churn, to ask the picker the cheap questions a flood of peer messages asks over and over. */
class PickerCostTest {
    private fun allocatedBytes(block: () -> Unit): Long {
        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val before = bean.currentThreadAllocatedBytes
        block()
        return bean.currentThreadAllocatedBytes - before
    }

    @Test
    fun askingWhetherAPeerHasAnythingWeLackCopiesNoBitfield() {
        val n = 100_000
        val picker = Picker(Layout(listOf(n.toLong() * BLOCK), BLOCK.toLong()), PieceSet.of(n, 0 until n step 2), Random(1))
        picker.peerHas(1, PieceSet.of(n, 0 until n))
        repeat(50) { picker.wants(1) } // warm up
        val used = allocatedBytes { repeat(2_000) { picker.wants(1) } }
        // a copy of the 12.5 kB bitfield per question would be 25 MB
        assertTrue(used < 1_000_000, "2000 questions allocated $used bytes")
    }
}
