package dev.anydm.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

class FormatTest {
    private val mb = 1024L * 1024

    @Test
    fun bytesUseOneDecimalAndTheRightUnit() {
        assertEquals("512 B", formatBytes(512))
        assertEquals("1.5 KB", formatBytes(1536))
        assertEquals("5.0 MB", formatBytes(5 * mb))
        assertEquals("2.0 GB", formatBytes(2048 * mb))
    }

    @Test
    fun speedInMegabytesPerSecond() {
        assertEquals("4.0 MB/s", formatSpeed(4 * mb, SpeedUnit.MBps))
    }

    @Test
    fun speedInMegabitsIsEightTimesBigger() {
        assertEquals("32.0 Mbps", formatSpeed(4 * mb, SpeedUnit.Mbps))
    }

    @Test
    fun zeroSpeedIsShownInBytes() {
        assertEquals("0 B/s", formatSpeed(0, SpeedUnit.MBps))
        assertEquals("0 B/s", formatSpeed(0, SpeedUnit.Mbps))
    }

    @Test
    fun etaIsCompact() {
        assertEquals("6s", formatEta(6))
        assertEquals("1m 5s", formatEta(65))
        assertEquals("1h 2m", formatEta(3720))
    }
}
