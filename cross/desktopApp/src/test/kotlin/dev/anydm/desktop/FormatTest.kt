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
}
