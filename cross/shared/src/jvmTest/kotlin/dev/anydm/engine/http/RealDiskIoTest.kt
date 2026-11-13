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
import kotlin.test.assertNotNull
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

    @Test
    fun readAtReturnsTheBytesAtAPositionAndFewerAtTheEnd() {
        val p = dir.resolve("r.part")
        Files.write(p, byteArrayOf(1, 2, 3, 4, 5, 6))
        val buf = ByteArray(10)
        assertEquals(3, disk.readAt(p, 2, buf, 1, 3))
        assertContentEquals(byteArrayOf(0, 3, 4, 5, 0), buf.copyOf(5))
        assertEquals(2, disk.readAt(p, 4, buf, 0, 5), "only what the file has")
        assertEquals(0, disk.readAt(p, 6, buf, 0, 5), "at the end")
        assertEquals(0, disk.readAt(p, 60, buf, 0, 5), "past the end")
    }

    @Test
    fun readAtOfAMissingFileFails() {
        assertFailsWith<IOException> { disk.readAt(dir.resolve("nope"), 0, ByteArray(1), 0, 1) }
    }

    @Test
    fun readAtSeesWhatAPartFileWrote() {
        val p = dir.resolve("rw.part")
        disk.openSpans(p, 100).use { it.write(40, byteArrayOf(7, 8, 9), 0, 3) }
        val buf = ByteArray(3)
        assertEquals(3, disk.readAt(p, 40, buf, 0, 3))
        assertContentEquals(byteArrayOf(7, 8, 9), buf)
    }

    @Test
    fun deleteTreeRemovesAFolderWithEverythingInItAndAMissingPathIsNoError() {
        val root = dir.resolve("tree")
        disk.mkdirs(root.resolve("a/b"))
        Files.writeString(root.resolve("a/b/f.part"), "x")
        Files.writeString(root.resolve("g"), "y")
        disk.deleteTree(root)
        assertFalse(Files.exists(root))
        disk.deleteTree(root)
    }

    @Test
    fun deleteTreeRemovesALinkButNeverWhatItPointsTo() {
        val outside = dir.resolve("outside")
        disk.mkdirs(outside)
        Files.writeString(outside.resolve("keep"), "k")
        val root = dir.resolve("with-link")
        disk.mkdirs(root)
        try {
            Files.createSymbolicLink(root.resolve("link"), outside)
        } catch (_: UnsupportedOperationException) {
            return // no links on this file system
        }
        disk.deleteTree(root)
        assertFalse(Files.exists(root))
        assertEquals("k", Files.readString(outside.resolve("keep")))
    }

    @Test
    fun deleteTreeOfAFileIsDelete() {
        val f = dir.resolve("one")
        Files.writeString(f, "1")
        disk.deleteTree(f)
        assertFalse(Files.exists(f))
    }

    @Test
    fun isLinkIsTrueForALinkItselfAndFalseForAFileAFolderAndAMissingPath() {
        val outside = dir.resolve("outside-dir")
        disk.mkdirs(outside)
        val file = dir.resolve("plain")
        Files.writeString(file, "x")
        val link = dir.resolve("a-link")
        try {
            Files.createSymbolicLink(link, outside)
        } catch (_: UnsupportedOperationException) {
            return // no links on this file system
        }
        assertTrue(disk.isLink(link), "a link to a folder")
        assertFalse(disk.isLink(outside))
        assertFalse(disk.isLink(file))
        assertFalse(disk.isLink(dir.resolve("missing")))
    }

    @Test
    fun childrenListsAFoldersEntriesWithoutFollowingLinksAndIsNullForAFileALinkOrAMissingPath() {
        val folder = dir.resolve("kids")
        disk.mkdirs(folder.resolve("sub"))
        Files.writeString(folder.resolve("f"), "x")
        val outside = dir.resolve("outside-kids")
        disk.mkdirs(outside)
        Files.writeString(outside.resolve("o"), "o")
        val link = folder.resolve("link")
        val linked =
            try {
                Files.createSymbolicLink(link, outside)
                true
            } catch (_: UnsupportedOperationException) {
                false
            }
        val kids = assertNotNull(disk.children(folder)).toSet()
        assertEquals(setOf(folder.resolve("sub"), folder.resolve("f")) + if (linked) setOf(link) else emptySet(), kids)
        assertEquals(emptyList(), disk.children(folder.resolve("sub")))
        assertNull(disk.children(folder.resolve("f")), "a file")
        assertNull(disk.children(dir.resolve("missing")))
        if (linked) assertNull(disk.children(link), "a link is not followed")
    }
}
