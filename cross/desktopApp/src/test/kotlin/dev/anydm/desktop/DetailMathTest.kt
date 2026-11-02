package dev.anydm.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class DetailMathTest {
    private val mib = 1024L * 1024

    // The chart's axis

    @Test
    fun `no speed gives an axis with no lines`() {
        val axis = speedAxis(0, SpeedUnit.BYTES)
        assertEquals(1.0, axis.top)
        assertTrue(axis.ticks.isEmpty())
    }

    @Test
    fun `the axis steps by round figures and its top just clears the fastest second`() {
        val axis = speedAxis((13.2 * mib).toLong(), SpeedUnit.BYTES)
        assertEquals(listOf("5 MB/s", "10 MB/s", "15 MB/s"), axis.ticks.map { it.label })
        assertEquals(15.0 * mib, axis.top)
        assertEquals(listOf(5.0 * mib, 10.0 * mib, 15.0 * mib), axis.ticks.map { it.value })
    }

    @Test
    fun `a slow second is read in the smaller unit`() {
        val axis = speedAxis(100 * 1024, SpeedUnit.BYTES)
        assertEquals(listOf("50 KB/s", "100 KB/s"), axis.ticks.map { it.label })
        assertEquals(100.0 * 1024, axis.top)
    }

    @Test
    fun `a step of two and a half is used when it is the first to fit`() {
        val axis = speedAxis(7 * mib, SpeedUnit.BYTES)
        assertEquals(listOf("2.5 MB/s", "5 MB/s", "7.5 MB/s"), axis.ticks.map { it.label })
    }

    @Test
    fun `bits are read in thousands`() {
        val axis = speedAxis((13.2 * mib).toLong(), SpeedUnit.BITS)
        assertEquals(listOf("50 Mbps", "100 Mbps", "150 Mbps"), axis.ticks.map { it.label })
        assertEquals(150.0 * 125_000, axis.top)
    }

    // The chunk map

    private fun cells(
        count: Int,
        done: Int,
        shares: Map<String, Long> = emptyMap(),
        cap: Int = 200,
    ) = chunkCells(
        sizes = List(count) { 10L },
        written = List(count) { if (it < done) 10L else 0L },
        shares = shares,
        cap = cap,
    )

    @Test
    fun `a few chunks are one cell each`() {
        val map = cells(150, done = 100)
        assertEquals(150, map.cells.size)
        assertEquals(150, map.chunks)
        assertEquals(100, map.cells.count { it.fill == 1f })
    }

    @Test
    fun `no more than two hundred cells are shown however many chunks there are`() {
        val map = cells(500, done = 250)
        assertEquals(200, map.cells.size)
        assertEquals(500, map.chunks)
        assertEquals(100, map.cells.count { it.fill == 1f })
        assertEquals(100, map.cells.count { it.fill == 0f })
    }

    @Test
    fun `a cell that groups chunks shows how much of them is done`() {
        val map = chunkCells(sizes = List(400) { 10L }, written = List(400) { if (it % 2 == 0) 10L else 0L }, shares = emptyMap())
        assertEquals(200, map.cells.size)
        assertTrue(map.cells.all { it.fill == 0.5f })
    }

    @Test
    fun `finished chunks are colored by network in proportion to what each carried`() {
        val map = cells(100, done = 100, shares = mapOf("wifi" to 51L, "usb" to 49L))
        assertEquals(51, map.cells.count { it.key == "wifi" })
        assertEquals(49, map.cells.count { it.key == "usb" })
        // Mixed through the map, not one block then the other.
        assertTrue(
            map.cells
                .take(4)
                .map { it.key }
                .toSet()
                .size == 2,
        )
    }

    @Test
    fun `a cell keeps its network as more cells fill in`() {
        val shares = mapOf("wifi" to 60L, "usb" to 40L)
        val few = cells(100, done = 30, shares = shares)
        val more = cells(100, done = 70, shares = shares)
        assertEquals(few.cells.take(30).map { it.key }, more.cells.take(30).map { it.key })
    }

    @Test
    fun `chunks not started have no network and without a share none is named`() {
        val started = cells(10, done = 4, shares = mapOf("wifi" to 100L))
        assertEquals(4, started.cells.count { it.key == "wifi" })
        assertEquals(6, started.cells.count { it.key == null })
        assertTrue(cells(10, done = 4).cells.all { it.key == null })
    }

    @Test
    fun `no chunks give an empty map`() {
        val map = chunkCells(emptyList(), emptyList(), emptyMap())
        assertTrue(map.cells.isEmpty())
        assertEquals(0, map.chunks)
    }

    // Share

    @Test
    fun `shares add up to a hundred`() {
        assertEquals(listOf(34, 33, 33), sharePercents(listOf(1, 1, 1)))
        assertEquals(listOf(51, 49), sharePercents(listOf(139_600_000, 135_400_000)))
        assertEquals(100, sharePercents(listOf(7, 11, 13, 17, 19)).sum())
        assertEquals(listOf(100), sharePercents(listOf(5)))
    }

    @Test
    fun `no bytes give no share`() {
        assertEquals(listOf(0, 0), sharePercents(listOf(0, 0)))
        assertEquals(emptyList(), sharePercents(emptyList()))
    }

    // "N× alone"

    @Test
    fun `two networks sharing the work are each told how much faster they are together`() {
        val badges = aloneBadges(13 * mib, listOf("Wi-Fi" to 6 * mib, "USB Tethering" to 6 * mib))
        assertEquals(listOf("2.2× WI-FI ALONE", "2.2× USB TETHERING ALONE"), badges.map { it.label })
    }

    @Test
    fun `the network that carries least is the biggest multiple and the fastest comes first`() {
        val badges = aloneBadges(10 * mib, listOf("Wi-Fi" to 8 * mib, "USB" to 2 * mib))
        assertEquals(listOf("1.3× WI-FI ALONE", "5.0× USB ALONE"), badges.map { it.label })
    }

    @Test
    fun `one network on its own has no badge`() {
        assertTrue(aloneBadges(5 * mib, listOf("Wi-Fi" to 5 * mib)).isEmpty())
    }

    @Test
    fun `a network that carried nearly everything makes no point worth a badge`() {
        val badges = aloneBadges(100, listOf("Wi-Fi" to 98L, "USB" to 2L))
        assertEquals(listOf("50.0× USB ALONE"), badges.map { it.label })
    }

    @Test
    fun `no badge while nothing moves or a network carries nothing`() {
        assertTrue(aloneBadges(0, listOf("A" to 0L, "B" to 0L)).isEmpty())
        assertTrue(aloneBadges(10, listOf("A" to 10L, "B" to 0L)).isEmpty())
    }
}
