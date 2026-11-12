package dev.anydm.engine.http

import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

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
}

class RealDiskIo : DiskIo {
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
}
