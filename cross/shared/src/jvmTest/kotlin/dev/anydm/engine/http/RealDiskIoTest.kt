package dev.anydm.engine.http

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RealDiskIoTest {
    private val disk = RealDiskIo()
    private val dir = Files.createTempDirectory("diskio")

    @Test
    fun appendAndTruncate() {
        val p = dir.resolve("a/b/f.part")
        disk.mkdirs(p.parent)
        assertNull(disk.size(p))
        disk.openPart(p, append = false).use { it.write(byteArrayOf(1, 2, 3)) }
        disk.openPart(p, append = true).use { it.write(byteArrayOf(4)) }
        assertEquals(4L, disk.size(p))
        assertContentEquals(byteArrayOf(1, 2, 3, 4), Files.readAllBytes(p))
        disk.openPart(p, append = false).use { it.write(byteArrayOf(9)) }
        assertEquals(1L, disk.size(p))
    }

    @Test
    fun moveReplacesAndDeleteIsIdempotent() {
        val from = dir.resolve("x.part")
        val to = dir.resolve("x")
        Files.writeString(from, "new")
        Files.writeString(to, "old")
        disk.move(from, to)
        assertEquals("new", Files.readString(to))
        assertFalse(disk.exists(from))
        assertTrue(disk.exists(to))
        disk.delete(to)
        disk.delete(to)
        assertFalse(disk.exists(to))
    }

    @Test
    fun openSpansMakesTheFileTheGivenSize() {
        val p = dir.resolve("s.part")
        disk.openSpans(p, 100_000).use { }
        assertEquals(100_000L, disk.size(p))
        disk.openSpans(p, null).use { it.write(5, byteArrayOf(1, 2), 0, 2) }
        assertEquals(100_000L, disk.size(p)) // size null leaves the length alone
        disk.openSpans(p, 100_000).use { }
        assertEquals(1.toByte(), Files.readAllBytes(p)[5]) // reopening at the same size keeps the bytes
    }

    @Test
    fun writesFromFourThreadsAtDisjointOffsetsLandWhereTheyBelong() {
        val p = dir.resolve("c.part")
        val size = 4 * 1024 * 1024
        val data = TestServer.expected(size)
        disk.openSpans(p, size.toLong()).use { file ->
            val start = CountDownLatch(1)
            val threads =
                List(4) { t ->
                    thread {
                        start.await()
                        val from = t * size / 4
                        var pos = from
                        while (pos < from + size / 4) {
                            file.write(pos.toLong(), data, pos, 8_192)
                            pos += 8_192
                        }
                    }
                }
            start.countDown()
            threads.forEach { it.join() }
            file.force()
        }
        assertContentEquals(data, Files.readAllBytes(p))
    }

    @Test
    fun writeWithAnOffsetAndLengthTakesOnlyThatSlice() {
        val p = dir.resolve("w.part")
        disk.openSpans(p, 10).use { it.write(3, byteArrayOf(9, 1, 2, 3, 9), 1, 3) }
        assertContentEquals(byteArrayOf(0, 0, 0, 1, 2, 3, 0, 0, 0, 0), Files.readAllBytes(p))
    }

    private class LowSpace(
        private val free: Long,
    ) : RealDiskIo() {
        override fun freeSpace(dir: Path) = free
    }

    @Test
    fun aReservationLargerThanTheFreeSpaceIsRefusedAsNoSpaceAndLeavesNoFile() {
        val p = dir.resolve("big.part")
        val e = assertFailsWith<IOException> { LowSpace(1_000).openSpans(p, 5_000).close() }
        assertTrue(e.message.orEmpty().contains("No space left"), e.message)
        assertFalse(disk.exists(p))
    }

    @Test
    fun onlyTheMissingBytesNeedFreeSpace() {
        val p = dir.resolve("grow.part")
        Files.write(p, ByteArray(4_000))
        LowSpace(1_000).openSpans(p, 5_000).use { } // 1000 more needed, 1000 free
        assertEquals(5_000L, disk.size(p))
        assertFailsWith<IOException> { LowSpace(999).openSpans(p, 6_000).close() }
        assertEquals(5_000L, disk.size(p))
    }

    @Test
    fun freeSpaceOfARealFolderIsPositive() {
        assertTrue(disk.freeSpace(dir) > 0)
    }
}
