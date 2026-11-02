package dev.anydm.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class NetworkSamplerTest {
    @Test
    fun `the first look only sets the baseline`() {
        val sampler = NetworkSampler()
        sampler.tick(mapOf("en0" to 5_000L))
        assertEquals(0, sampler.length)
        assertTrue(sampler.series.isEmpty())
    }

    @Test
    fun `each second is the bytes a network gained since the one before`() {
        val sampler = NetworkSampler()
        sampler.tick(mapOf("en0" to 1_000L, "en7" to 0L))
        sampler.tick(mapOf("en0" to 3_000L, "en7" to 500L))
        sampler.tick(mapOf("en0" to 3_000L, "en7" to 1_500L))
        assertEquals(listOf(2_000L, 0L), sampler.series.getValue("en0"))
        assertEquals(listOf(500L, 1_000L), sampler.series.getValue("en7"))
        assertEquals(2, sampler.length)
    }

    @Test
    fun `a tick that took longer than a second is scaled to bytes per second`() {
        val sampler = NetworkSampler()
        sampler.tick(mapOf("en0" to 0L))
        sampler.tick(mapOf("en0" to 3_000L), elapsedMillis = 2_000)
        assertEquals(listOf(1_500L), sampler.series.getValue("en0"))
    }

    @Test
    fun `a counter that went back is a restart and counts what it holds now`() {
        val sampler = NetworkSampler()
        sampler.tick(mapOf("en0" to 9_000L))
        sampler.tick(mapOf("en0" to 10_000L))
        sampler.tick(mapOf("en0" to 400L))
        sampler.tick(mapOf("en0" to 900L))
        assertEquals(listOf(1_000L, 400L, 500L), sampler.series.getValue("en0"))
    }

    @Test
    fun `a network that vanishes gets zeros and keeps its past until it scrolls out`() {
        val sampler = NetworkSampler(size = 3)
        sampler.tick(mapOf("en0" to 0L, "en7" to 0L))
        sampler.tick(mapOf("en0" to 100L, "en7" to 200L))
        sampler.tick(mapOf("en0" to 300L))
        assertEquals(listOf(100L, 200L), sampler.series.getValue("en0"))
        assertEquals(listOf(200L, 0L), sampler.series.getValue("en7"))
        sampler.tick(mapOf("en0" to 400L))
        sampler.tick(mapOf("en0" to 500L))
        // en7's whole window is zeros now: it is dropped.
        assertEquals(setOf("en0"), sampler.series.keys)
    }

    @Test
    fun `a network that appears later counts what it carried and is padded with zeros before`() {
        val sampler = NetworkSampler()
        sampler.tick(mapOf("en0" to 0L))
        sampler.tick(mapOf("en0" to 100L))
        sampler.tick(mapOf("en0" to 200L, "en7" to 50L))
        sampler.tick(mapOf("en0" to 300L, "en7" to 80L))
        assertEquals(listOf(100L, 100L, 100L), sampler.series.getValue("en0"))
        assertEquals(listOf(0L, 50L, 30L), sampler.series.getValue("en7"))
    }

    @Test
    fun `only the last sixty seconds are kept`() {
        val sampler = NetworkSampler()
        for (second in 0..100) sampler.tick(mapOf("en0" to second * 10L))
        assertEquals(60, sampler.length)
        assertEquals(60, sampler.series.getValue("en0").size)
    }

    @Test
    fun `reset forgets the baseline and the samples`() {
        val sampler = NetworkSampler()
        sampler.tick(mapOf("en0" to 0L))
        sampler.tick(mapOf("en0" to 10L))
        sampler.reset()
        assertEquals(0, sampler.length)
        sampler.tick(mapOf("en0" to 10_000L))
        assertEquals(0, sampler.length)
    }

    @Test
    fun `the speed of a network now is its latest sample`() {
        val sampler = NetworkSampler()
        assertEquals(0L, sampler.latest("en0"))
        sampler.tick(mapOf("en0" to 0L))
        sampler.tick(mapOf("en0" to 70L))
        assertEquals(70L, sampler.latest("en0"))
        assertEquals(0L, sampler.latest("nope"))
    }
}
