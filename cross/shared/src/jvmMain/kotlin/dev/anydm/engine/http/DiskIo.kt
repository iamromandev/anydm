package dev.anydm.engine.http

import java.io.Closeable
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

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
