package dev.anydm.engine.platform

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.DELETE_ON_CLOSE
import java.nio.file.StandardOpenOption.SPARSE
import java.nio.file.StandardOpenOption.WRITE
import java.util.UUID
import java.nio.file.Path as NioPath

/** [FileSystem] for macOS, Windows and Linux on java.nio. Blocking calls run on [io]. */
class DesktopFileSystem internal constructor(
    private val io: CoroutineDispatcher,
    private val trashBin: TrashBin,
    // A seam for tests: told of every writer the moment its file is open.
    private val onWriterOpened: (DesktopFileWriter) -> Unit = {},
) : FileSystem {
    constructor() : this(Dispatchers.IO, DesktopTrashBin)

    override suspend fun prepareFolder(folder: Path): FileResult<Unit> {
        requireAbsolute(folder)
        return blocking {
            val dir = folder.nio()
            if (Files.exists(dir) && !Files.isDirectory(dir)) {
                FileResult.AlreadyExists(folder, "not a folder")
            } else {
                attempt(folder) {
                    Files.createDirectories(dir)
                    // Permission bits miss read-only volumes and network shares, so really create a file.
                    // DELETE_ON_CLOSE leaves nothing behind even if we are killed right after.
                    val probe = dir.resolve(".anydm-check-${UUID.randomUUID()}.tmp")
                    Files.newByteChannel(probe, CREATE_NEW, WRITE, DELETE_ON_CLOSE).close()
                }
            }
        }
    }

    override suspend fun preallocate(
        path: Path,
        size: Long,
    ): FileResult<Unit> {
        require(size >= 0) { "size must be >= 0: $size" }
        requireAbsolute(path)
        return blocking {
            val file = path.nio()
            var created = false
            try {
                // CREATE_NEW never touches existing bytes, and is what lets SPARSE mark the file sparse on NTFS.
                FileChannel.open(file, CREATE_NEW, WRITE, SPARSE).use { channel ->
                    created = true
                    // One byte at the end sets the length; the gap stays a hole where the file system has them.
                    if (size > 0) channel.write(ByteBuffer.wrap(byteArrayOf(0)), size - 1)
                }
                FileResult.Ok(Unit)
            } catch (e: IOException) {
                val outOfRoom = created && usableSpace(file) < size
                if (created) deleteQuietly(file)
                e.toFailure(path, outOfRoom = outOfRoom)
            }
        }
    }

    override suspend fun freeSpace(path: Path): FileResult<Long> {
        requireAbsolute(path)
        return blocking {
            val existing = generateSequence(path.nio()) { it.parent }.firstOrNull { Files.exists(it) }
            if (existing == null) {
                FileResult.NotFound(path, "no part of it exists")
            } else {
                attempt(path) { Files.getFileStore(existing).usableSpace }
            }
        }
    }

    override suspend fun atomicMove(
        source: Path,
        target: Path,
        replace: Boolean,
    ): FileResult<Unit> {
        requireAbsolute(source)
        requireAbsolute(target)
        return blocking(cancellable = false) {
            val from = source.nio()
            val to = target.nio()
            val targetFolder = target.parent
            when {
                !Files.exists(from, NOFOLLOW_LINKS) -> {
                    FileResult.NotFound(source)
                }

                targetFolder != null && !Files.isDirectory(targetFolder.nio()) -> {
                    FileResult.NotFound(targetFolder)
                }

                // rename(2) and MoveFileEx refuse a folder target differently; refuse it one way everywhere.
                Files.isDirectory(to, NOFOLLOW_LINKS) -> {
                    FileResult.AlreadyExists(target, "a folder is in the way")
                }

                // Best effort: rename(2) replaces silently, so a file that appears after this check is replaced.
                !replace && Files.exists(to, NOFOLLOW_LINKS) -> {
                    FileResult.AlreadyExists(target, "a file is already there")
                }

                else -> {
                    attempt(source, target) {
                        // ATOMIC_MOVE is rename(2) on macOS and Linux and MoveFileEx(MOVEFILE_REPLACE_EXISTING) on
                        // Windows; both replace a file at the target. REPLACE_EXISTING only states the intent.
                        Files.move(from, to, ATOMIC_MOVE, REPLACE_EXISTING)
                        Unit
                    }
                }
            }
        }
    }

    override suspend fun moveToTrash(path: Path): FileResult<Unit> {
        requireAbsolute(path)
        return blocking(cancellable = false) {
            val item = path.nio()
            when {
                !Files.exists(item, NOFOLLOW_LINKS) -> FileResult.NotFound(path)
                !trashBin.isAvailable -> FileResult.TrashUnavailable(path)
                else -> attempt(path) { if (!trashBin.moveToTrash(item)) throw IOException("the trash refused it") }
            }
        }
    }

    override suspend fun openWriter(path: Path): FileResult<FileWriter> {
        requireAbsolute(path)
        // withContext throws away the result of a call whose caller was cancelled meanwhile, which would leave the
        // channel it opened unclosed for good. So the channel is kept here, and closed if the call ends cancelled.
        var opened: DesktopFileWriter? = null
        try {
            return blocking {
                val file = path.nio()
                if (Files.isDirectory(file)) {
                    FileResult.AlreadyExists(path, "a folder is in the way")
                } else {
                    // No CREATE: a missing file is NotFound.
                    attempt<FileWriter>(path) {
                        DesktopFileWriter(path, FileChannel.open(file, WRITE), io).also {
                            opened = it
                            onWriterOpened(it)
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            opened?.close()
            throw e
        }
    }

    override suspend fun delete(path: Path): FileResult<Unit> {
        requireAbsolute(path)
        return blocking(cancellable = false) {
            val file = path.nio()
            when {
                !Files.exists(file, NOFOLLOW_LINKS) -> FileResult.NotFound(path)
                Files.isDirectory(file, NOFOLLOW_LINKS) -> FileResult.AlreadyExists(path, "a folder is in the way")
                else -> attempt(path) { Files.delete(file) }
            }
        }
    }

    /**
     * Runs [block] on [io]. A path that can't be a java.nio path ends the call as
     * [FileResult.InvalidName]. Renames and trash moves pass `cancellable = false`: they are quick and
     * can't be undone, so a cancelled caller must not leave the caller guessing whether they happened.
     */
    private suspend fun <T> blocking(
        cancellable: Boolean = true,
        block: () -> FileResult<T>,
    ): FileResult<T> =
        withContext(if (cancellable) io else io + NonCancellable) {
            try {
                block()
            } catch (e: InvalidNameException) {
                e.failure
            }
        }

    private inline fun <T> attempt(
        path: Path,
        target: Path? = null,
        block: () -> T,
    ): FileResult<T> =
        try {
            FileResult.Ok(block())
        } catch (e: IOException) {
            e.toFailure(path, target)
        }
}

private fun usableSpace(file: NioPath): Long =
    try {
        Files.getFileStore(file.parent ?: file).usableSpace
    } catch (e: IOException) {
        Long.MAX_VALUE
    }

private fun deleteQuietly(file: NioPath) {
    try {
        Files.deleteIfExists(file)
    } catch (e: IOException) {
        // Best effort: the caller already gets the original failure.
    }
}

/** Thrown inside a call when a path can't be a java.nio path; [DesktopFileSystem.blocking] turns it into the result. */
private class InvalidNameException(
    val failure: FileResult.InvalidName,
) : RuntimeException(failure.detail)

private fun Path.nio(): NioPath =
    try {
        toNioPath()
    } catch (e: InvalidPathException) {
        throw InvalidNameException(FileResult.InvalidName(this, e.message))
    }
