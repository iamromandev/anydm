package dev.anydm.engine.torrent

import dev.anydm.engine.http.Clock
import dev.anydm.engine.http.DiskIo
import dev.anydm.engine.http.DownloadEngine
import dev.anydm.engine.http.JdkTransport
import dev.anydm.engine.http.JobSpec
import dev.anydm.engine.http.PartFile
import dev.anydm.engine.http.RateShares
import dev.anydm.engine.http.RealDiskIo
import dev.anydm.engine.http.TestServer
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/** One speed limit for an HTTP download and a torrent that run at the same time (AC14, the engines' part). */
class SharedRateTest {
    private val rig = Rig()
    private val server = TestServer()
    private val real = Clock { System.nanoTime() / 1_000_000 }

    @AfterTest
    fun tearDown() {
        server.close()
        rig.close()
    }

    /** Counts the bytes written to each file by the last part of its name. */
    private class CountingDisk(
        private val inner: DiskIo,
    ) : DiskIo by inner {
        private val counts = ConcurrentHashMap<String, AtomicLong>()

        fun of(name: String): Long = counts[name]?.get() ?: 0L

        override fun openPart(
            p: Path,
            append: Boolean,
        ): java.io.OutputStream {
            val out = inner.openPart(p, append)
            val c = counts.computeIfAbsent(p.fileName.toString()) { AtomicLong() }
            return object : java.io.OutputStream() {
                override fun write(b: Int) {
                    out.write(b)
                    c.incrementAndGet()
                }

                override fun write(
                    b: ByteArray,
                    off: Int,
                    len: Int,
                ) {
                    out.write(b, off, len)
                    c.addAndGet(len.toLong())
                }

                override fun flush() = out.flush()

                override fun close() = out.close()
            }
        }

        override fun openSpans(
            p: Path,
            size: Long?,
        ): PartFile {
            val file = inner.openSpans(p, size)
            val c = counts.computeIfAbsent(p.fileName.toString()) { AtomicLong() }
            return object : PartFile by file {
                override fun write(
                    offset: Long,
                    buf: ByteArray,
                    off: Int,
                    len: Int,
                ) {
                    file.write(offset, buf, off, len)
                    c.addAndGet(len.toLong())
                }
            }
        }
    }

    // One 3 s window after a warm-up. The combined rate is always asserted; the split between the two is logged only on CI, where a
    // shared machine makes the share of two competing downloads meaningless.
    @Test
    fun oneLimitCapsAnHttpDownloadAndATorrentTogetherAndSplitsItAboutEvenly() {
        val counting = CountingDisk(RealDiskIo())
        val shares = RateShares()
        val http = DownloadEngine(JdkTransport(), counting, real, rig.scope, shares = shares)
        val size = 20 * MB
        val t = rig.torrent(listOf("t.bin" to size), pieceLength = 16 * KB, single = true, name = "t.bin")
        val swarm = rig.swarm(t, PeerScript(), PeerScript())
        val torrents = rig.engine(disk = counting, clock = real, shares = shares)
        val cap = 1_000_000.0
        val tolerance = if (System.getenv("CI") == null) 0.10 else 0.30
        shares.setRate(1_000_000)
        http.start(JobSpec("h", "${server.base}/file/h?size=$size", rig.save.toString(), "h.bin", 0, null, null, size.toLong()))
        torrents.start(t.job(id = "t", peers = swarm.addrs, folder = rig.save))
        rig.await("both flowing", id = "t") { counting.of("h.bin.part") > 0 && counting.of("t.bin.part") > 0 }
        Thread.sleep(2_000) // warm-up: the buckets' burst and the connections' start are behind us
        val t0 = System.nanoTime()
        val h0 = counting.of("h.bin.part")
        val b0 = counting.of("t.bin.part")
        Thread.sleep(3_000)
        val h = counting.of("h.bin.part") - h0
        val b = counting.of("t.bin.part") - b0
        val combined = (h + b) * 1e9 / (System.nanoTime() - t0)
        assertTrue(
            combined in cap * (1 - tolerance)..cap * (1 + tolerance),
            "combined throughput over 3 s $combined B/s (http $h, torrent $b)",
        )
        val even = listOf(h, b).all { it in ((h + b) * 0.4).toLong()..((h + b) * 0.6).toLong() }
        if (System.getenv("CI") == null) {
            assertTrue(even, "uneven split: http $h / torrent $b")
        } else {
            println("share of http and torrent over 3 s: $h / $b (even: $even)")
        }
        http.stop("h")
        torrents.stop("t")
    }
}
