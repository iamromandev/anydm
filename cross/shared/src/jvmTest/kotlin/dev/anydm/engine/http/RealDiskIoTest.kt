package dev.anydm.engine.http

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
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
}
