package dev.anydm.engine.http

import java.io.Closeable
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes

/** A part file written at arbitrary offsets. [write] is safe to call from several threads for disjoint ranges. */
interface PartFile : Closeable {
    fun write(
        offset: Long,
        buf: ByteArray,
        off: Int,
        len: Int,
    )

    /** Makes what was written so far survive a crash of the app or the OS. */
    fun force()

    /** Cuts the file to [size] bytes. */
    fun truncate(size: Long)
}

interface DiskIo {
    fun size(p: Path): Long?

    fun exists(p: Path): Boolean

    fun openPart(
        p: Path,
        append: Boolean,
    ): OutputStream

    fun move(
        from: Path,
        to: Path,
    )

    fun delete(p: Path)

    /** Deletes [p] and, when it is a folder, everything in it; a link is removed, never followed. No error when it is not there. */
    fun deleteTree(p: Path) = delete(p)

    /** Whether [p] is itself a symbolic link (what it points to is not looked at). False when it is not there. */
    fun isLink(p: Path): Boolean = false

    /**
     * The entries of the folder [dir], links not followed; null when [dir] is not a folder (a file, a link, nothing there) or
     * cannot be listed. The default says nothing is known.
     */
    fun children(dir: Path): List<Path>? = null

    fun mkdirs(p: Path)

    /**
     * Creates or opens [p] for positional writes. With a [size], sets the length to it; a file that must grow fails with
     * `IOException("No space left")` when the free space is less than what is missing, before anything is created.
     */
    fun openSpans(
        p: Path,
        size: Long?,
    ): PartFile

    /** The bytes a new file in [dir] could still take. */
    fun freeSpace(dir: Path): Long

    /** The last [len] bytes of [p]; null when there is no such file or it is shorter than that. */
    fun readTail(
        p: Path,
        len: Int,
    ): ByteArray?

    /**
     * Reads up to [len] bytes at position [pos] of [p] into [buf] at [off] and returns how many it read: fewer than [len] only at the
     * end of the file, 0 at or past it. A file that is not there fails with an [IOException].
     */
    fun readAt(
        p: Path,
        pos: Long,
        buf: ByteArray,
        off: Int,
        len: Int,
    ): Int
}

open class RealDiskIo : DiskIo {
    override fun size(p: Path): Long? = if (Files.isRegularFile(p)) Files.size(p) else null

    override fun exists(p: Path) = Files.exists(p)

    override fun openPart(
        p: Path,
        append: Boolean,
    ): OutputStream =
        Files.newOutputStream(
            p,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            if (append) StandardOpenOption.APPEND else StandardOpenOption.TRUNCATE_EXISTING,
        )

    override fun move(
        from: Path,
        to: Path,
    ) {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    override fun delete(p: Path) {
        Files.deleteIfExists(p)
    }

    override fun deleteTree(p: Path) {
        if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) return
        // walkFileTree does not follow links by default: a link in the tree is deleted as the file it is.
        Files.walkFileTree(
            p,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(
                    file: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    Files.deleteIfExists(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(
                    dir: Path,
                    exc: IOException?,
                ): FileVisitResult {
                    if (exc != null) throw exc
                    Files.deleteIfExists(dir)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }

    override fun isLink(p: Path): Boolean = Files.isSymbolicLink(p)

    override fun children(dir: Path): List<Path>? =
        try {
            if (Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) Files.list(dir).use { it.toList() } else null
        } catch (_: IOException) {
            null
        }

    override fun mkdirs(p: Path) {
        Files.createDirectories(p)
    }

    override fun openSpans(
        p: Path,
        size: Long?,
    ): PartFile {
        val current = if (Files.isRegularFile(p)) Files.size(p) else 0L
        if (size != null && size > current) {
            val dir = p.toAbsolutePath().parent
            if (freeSpace(dir) < size - current) throw IOException("No space left on device")
        }
        val channel = FileChannel.open(p, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        try {
            if (size != null && size < current) channel.truncate(size)
            if (size != null && size > current) channel.write(ByteBuffer.wrap(byteArrayOf(0)), size - 1)
        } catch (e: IOException) {
            channel.close()
            throw e
        }
        return ChannelPartFile(channel)
    }

    override fun freeSpace(dir: Path): Long = Files.getFileStore(dir).usableSpace

    override fun readAt(
        p: Path,
        pos: Long,
        buf: ByteArray,
        off: Int,
        len: Int,
    ): Int {
        FileChannel.open(p, StandardOpenOption.READ).use { channel ->
            val bb = ByteBuffer.wrap(buf, off, len)
            var at = pos
            while (bb.hasRemaining()) {
                val n = channel.read(bb, at)
                if (n < 0) break
                at += n
            }
            return (at - pos).toInt()
        }
    }

    override fun readTail(
        p: Path,
        len: Int,
    ): ByteArray? {
        if (!Files.isRegularFile(p)) return null
        FileChannel.open(p, StandardOpenOption.READ).use { channel ->
            val size = channel.size()
            if (size < len) return null
            val bb = ByteBuffer.allocate(len)
            var pos = size - len
            while (bb.hasRemaining()) {
                val n = channel.read(bb, pos)
                if (n < 0) return null
                pos += n
            }
            return bb.array()
        }
    }
}

private class ChannelPartFile(
    private val channel: FileChannel,
) : PartFile {
    override fun write(
        offset: Long,
        buf: ByteArray,
        off: Int,
        len: Int,
    ) {
        val bb = ByteBuffer.wrap(buf, off, len)
        var pos = offset
        while (bb.hasRemaining()) pos += channel.write(bb, pos)
    }

    override fun force() = channel.force(false)

    override fun truncate(size: Long) {
        if (channel.size() > size) channel.truncate(size)
        channel.force(true) // the length is metadata
    }

    override fun close() = channel.close()
}
