package dev.anydm.desktop

import dev.anydm.engine.net.SpeedLimit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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

    // Time saved by bonding

    @Test
    fun `two networks sharing a run save the time the biggest alone would have taken extra`() {
        // 100 s, Wi-Fi carried 75%: alone it would take 100 * 4 / 3 = 133 s, so 33 s sooner.
        val saved = assertNotNull(timeSaved(400 * mib, 100, listOf("Wi-Fi" to 300 * mib, "iPhone" to 100 * mib)))
        assertEquals(33L, saved.seconds)
        assertEquals("About 33 sec sooner than Wi-Fi alone", saved.text)
    }

    @Test
    fun `a saving of a minute or more reads in minutes`() {
        // 600 s, a 50/50 split: alone 1200 s, so 10 min sooner.
        val saved = assertNotNull(timeSaved(100 * mib, 600, listOf("Wi-Fi" to 50 * mib, "iPhone" to 50 * mib)))
        assertEquals("About 10 min sooner than Wi-Fi alone", saved.text)
    }

    @Test
    fun `a single network has nothing saved`() {
        assertNull(timeSaved(100 * mib, 600, listOf("Wi-Fi" to 100 * mib)))
    }

    @Test
    fun `a network under a tenth of the bytes does not count as sharing`() {
        assertNull(timeSaved(100 * mib, 600, listOf("Wi-Fi" to 95 * mib, "iPhone" to 5 * mib)))
    }

    @Test
    fun `a network at exactly a tenth counts`() {
        assertNotNull(timeSaved(100 * mib, 600, listOf("Wi-Fi" to 90 * mib, "iPhone" to 10 * mib)))
    }

    @Test
    fun `a run under thirty seconds has no estimate`() {
        assertNull(timeSaved(100 * mib, 29, listOf("Wi-Fi" to 50 * mib, "iPhone" to 50 * mib)))
        assertNotNull(timeSaved(100 * mib, 30, listOf("Wi-Fi" to 50 * mib, "iPhone" to 50 * mib)))
    }

    @Test
    fun `a saving under ten seconds has no line`() {
        // 30 s at 10%/90%: alone 33.3 s, only 3 s saved.
        assertNull(timeSaved(100 * mib, 30, listOf("Wi-Fi" to 90 * mib, "iPhone" to 10 * mib)))
    }

    @Test
    fun `no bytes or no networks has no estimate`() {
        assertNull(timeSaved(0, 600, listOf("A" to 0L, "B" to 0L)))
        assertNull(timeSaved(0, 600, emptyList()))
    }

    private fun even(seconds: Long) = timeSaved(100 * mib, seconds, listOf("Wi-Fi" to 50 * mib, "iPhone" to 50 * mib))

    @Test
    fun `a saving rounds to the nearest minute from a minute up, never reading 60 sec`() {
        // A 50/50 split saves as many seconds as the run took.
        assertEquals("About 59 sec sooner than Wi-Fi alone", even(59)?.text)
        assertEquals("About 1 min sooner than Wi-Fi alone", even(60)?.text)
        assertEquals("About 2 min sooner than Wi-Fi alone", even(119)?.text)
        assertEquals("About 60 min sooner than Wi-Fi alone", even(3599)?.text)
        // 119 s at a 2/3 share: alone 178.5 s, so 59.5 s saved, which reads 1 min.
        val half = timeSaved(300 * mib, 119, listOf("Wi-Fi" to 200 * mib, "iPhone" to 100 * mib))
        assertEquals("About 1 min sooner than Wi-Fi alone", half?.text)
    }

    @Test
    fun `a saving of two hours or more reads in hours and minutes`() {
        assertEquals("About 2 h 15 min sooner than Wi-Fi alone", even(8100)?.text)
        assertEquals("About 2 h sooner than Wi-Fi alone", even(7200)?.text)
    }

    @Test
    fun `a run of zero seconds has no estimate`() {
        assertNull(even(0))
    }

    @Test
    fun `an even split names the first network`() {
        assertEquals("Wi-Fi", even(100)?.network)
        assertEquals("iPhone", timeSaved(100 * mib, 100, listOf("iPhone" to 50 * mib, "Wi-Fi" to 50 * mib))?.network)
    }

    @Test
    fun `three networks count the ones with a tenth or more, and the biggest sets the saving`() {
        // 45 / 50 / 5: two networks share, the 5% one is ignored for that but its bytes stay in the total. 100 s * 100/50 - 100 = 100 s.
        val saved = assertNotNull(timeSaved(100 * mib, 100, listOf("A" to 45 * mib, "B" to 50 * mib, "C" to 5 * mib)))
        assertEquals("B", saved.network)
        assertEquals(100L, saved.seconds)
        // 90 / 5 / 5: only one network carries a tenth.
        assertNull(timeSaved(100 * mib, 100, listOf("A" to 90 * mib, "B" to 5 * mib, "C" to 5 * mib)))
    }

    @Test
    fun `each limit has a short line`() {
        assertEquals("Limited by slow mode", limitText(SpeedLimit.SlowMode))
        assertEquals("Limited by your speed limit", limitText(SpeedLimit.SpeedCap))
        assertEquals("Wi-Fi reached its data limit", limitText(SpeedLimit.DataLimit("en0", "Wi-Fi")))
        assertEquals("iPhone can't reach the server", limitText(SpeedLimit.Unreachable("iPhone")))
        val looks = NetworkLooks(names = mapOf("usb0" to "Pixel"), defaults = mapOf("en0" to "Wi-Fi", "usb0" to "USB"))
        assertEquals("Wi-Fi reached its data limit", limitText(SpeedLimit.DataLimit("en0", "en0"), looks))
        assertEquals("Pixel reached its data limit", limitText(SpeedLimit.DataLimit("usb0", "USB"), looks))
    }
}
