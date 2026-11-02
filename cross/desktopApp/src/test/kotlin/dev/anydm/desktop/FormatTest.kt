package dev.anydm.desktop

import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadStatus.COMPLETED
import dev.anydm.engine.core.DownloadStatus.DOWNLOADING
import dev.anydm.engine.core.DownloadStatus.PAUSED
import dev.anydm.engine.core.Mirror
import dev.anydm.engine.core.MirrorId
import dev.anydm.engine.core.PauseReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FormatTest {
    private val mib = 1024L * 1024

    @Test
    fun `bytes and speeds use 1024-based units`() {
        assertEquals("512 B", formatBytes(512))
        assertEquals("1.5 KB", formatBytes(1536))
        assertEquals("100.0 MB", formatBytes(100 * mib))
        assertEquals("0 B/s", formatSpeed(0))
        assertEquals("1.0 MB/s", formatSpeed(mib))
    }

    @Test
    fun `eta reads as hours, minutes or seconds`() {
        assertEquals("12s", formatEta(12, 1))
        assertEquals("3m 05s", formatEta(185, 1))
        assertEquals("1h 02m", formatEta(3720, 1))
        assertEquals("1s", formatEta(1, 1000))
        assertNull(formatEta(100, 0))
    }

    @Test
    fun `a rate reads in megabytes or megabits per second`() {
        assertEquals("4.5 MB/s", formatRate(4 * mib + mib / 2, SpeedUnit.BYTES))
        assertEquals("0 B/s", formatRate(0, SpeedUnit.BYTES))
        assertEquals("8.0 Mbps", formatRate(1_000_000, SpeedUnit.BITS))
        assertEquals("800.0 kbps", formatRate(100_000, SpeedUnit.BITS))
        assertEquals("80 bps", formatRate(10, SpeedUnit.BITS))
        assertEquals("1.6 Gbps", formatRate(200_000_000, SpeedUnit.BITS))
    }

    @Test
    fun `a downloading row shows its progress and eta`() {
        val s = snapshot(1, DOWNLOADING, total = 100 * mib, downloaded = 40 * mib, speed = mib)
        assertEquals(0.4f, progress(s))
        assertEquals("1m 00s", eta(s))
    }

    @Test
    fun `progress is unknown without a total and never past done`() {
        assertNull(progress(snapshot(1, DOWNLOADING, downloaded = 10)))
        assertEquals(1f, progress(snapshot(1, DOWNLOADING, total = 10, downloaded = 20)))
    }

    @Test
    fun `eta only while downloading at a speed with a total`() {
        assertNull(eta(snapshot(1, PAUSED, total = 100, downloaded = 10, speed = 5)))
        assertNull(eta(snapshot(1, DOWNLOADING, total = null, downloaded = 10, speed = 5)))
        assertNull(eta(snapshot(1, DOWNLOADING, total = 100, downloaded = 10, speed = 0)))
    }

    @Test
    fun `eta seconds is the number eta formats, null when there is none`() {
        assertEquals(60L, etaSeconds(snapshot(1, DOWNLOADING, total = 100 * mib, downloaded = 40 * mib, speed = mib)))
        assertNull(etaSeconds(snapshot(1, PAUSED, total = 100, downloaded = 10, speed = 5)))
        assertNull(etaSeconds(snapshot(1, DOWNLOADING, total = 100, downloaded = 10, speed = 0)))
        assertNull(etaSeconds(snapshot(1, DOWNLOADING, total = null, downloaded = 10, speed = 5)))
    }

    @Test
    fun `a row is named by its file, else its address, else its number`() {
        assertEquals("a.iso", displayName(snapshot(1, COMPLETED, name = "a.iso")))
        val mirror = Mirror(MirrorId(1), DownloadId(2), "https://x.example/b", 0)
        assertEquals("https://x.example/b", displayName(snapshot(2, DOWNLOADING, mirrors = listOf(mirror))))
        assertEquals("Download #3", displayName(snapshot(3, DOWNLOADING)))
    }

    @Test
    fun `status says why a job is paused`() {
        assertEquals("Downloading", statusLabel(snapshot(1, DOWNLOADING)))
        assertEquals("Paused (all)", statusLabel(snapshot(1, PAUSED, pauseReasons = setOf(PauseReason.GLOBAL))))
        assertEquals("Paused (disk full)", statusLabel(snapshot(1, PAUSED, pauseReasons = setOf(PauseReason.LOW_DISK))))
    }

    @Test
    fun `a time ago is said in the biggest whole unit`() {
        val now = kotlin.time.Instant.fromEpochSeconds(1_000_000)
        assertEquals("just now", formatAgo(now, now - kotlin.time.Duration.parse("20s")))
        assertEquals("just now", formatAgo(now, now + kotlin.time.Duration.parse("5s")))
        assertEquals("5 min ago", formatAgo(now, now - kotlin.time.Duration.parse("5m")))
        assertEquals("3 h ago", formatAgo(now, now - kotlin.time.Duration.parse("3h 20m")))
        assertEquals("2 d ago", formatAgo(now, now - kotlin.time.Duration.parse("49h")))
    }

    @Test
    fun `a countdown is minutes and two-digit seconds`() {
        assertEquals("4:32", formatCountdown(272))
        assertEquals("0:05", formatCountdown(5))
        assertEquals("0:00", formatCountdown(-3))
    }
}
