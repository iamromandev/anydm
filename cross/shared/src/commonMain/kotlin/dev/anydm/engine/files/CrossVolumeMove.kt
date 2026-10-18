package dev.anydm.engine.files

import dev.anydm.engine.core.ChecksumAlgorithm
import dev.anydm.engine.core.hasherFor
import dev.anydm.engine.platform.FileResult
import dev.anydm.engine.platform.FileSystem
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path

private const val CHUNK = 1 shl 20

/**
 * Moves [source] to [target] on another volume (FIL-07): copies it next to the target as `<name>.copy`, checks the
 * copy's size and SHA-256 against what was read from the source, renames it into place on the target's volume, and
 * only then deletes the source. Any failure before that leaves the source alone and removes the copy; a copy left by
 * a power cut or a cancel is replaced by the next try. Without [replace] a taken [target] is
 * [FileResult.AlreadyExists]. A source that can't be deleted after the move is left behind: the file is in place.
 */
suspend fun moveAcrossVolumes(
    fileSystem: FileSystem,
    source: Path,
    target: Path,
    replace: Boolean,
): FileResult<Unit> {
    val folder = checkNotNull(target.parent) { "target has no folder: $target" }
    val copy = Path(folder, target.name + ".copy")
    val size =
        when (val sized = fileSystem.size(source)) {
            is FileResult.Ok -> sized.value
            is FileResult.Failed -> return sized
        }
    if (!replace && fileSystem.size(target) !is FileResult.NotFound) return FileResult.AlreadyExists(target, "a file is already there")
    val stale = fileSystem.delete(copy)
    if (stale is FileResult.Failed && stale !is FileResult.NotFound) return stale

    suspend fun abandon(failure: FileResult.Failed): FileResult.Failed {
        withContext(NonCancellable) { fileSystem.delete(copy) }
        return failure
    }

    val preallocated = fileSystem.preallocate(copy, size)
    if (preallocated is FileResult.Failed) return preallocated
    val sourceHash =
        when (val written = writeCopy(fileSystem, source, copy, size)) {
            is FileResult.Ok -> written.value
            is FileResult.Failed -> return abandon(written)
        }
    val copied = fileSystem.size(copy)
    if (copied !is FileResult.Ok || copied.value != size) {
        return abandon(FileResult.IoError(copy, "the copy is ${(copied as? FileResult.Ok)?.value} bytes, not $size"))
    }
    val copyHash =
        when (val hashed = hashOf(fileSystem, copy)) {
            is FileResult.Ok -> hashed.value
            is FileResult.Failed -> return abandon(hashed)
        }
    if (copyHash != sourceHash) return abandon(FileResult.IoError(copy, "the copy doesn't match the source"))
    val placed = fileSystem.atomicMove(copy, target, replace)
    if (placed is FileResult.Failed) return abandon(placed)
    fileSystem.delete(source)
    return FileResult.Ok(Unit)
}

/** Copies [source] into the preallocated [copy], and returns the SHA-256 of what it read. */
private suspend fun writeCopy(
    fileSystem: FileSystem,
    source: Path,
    copy: Path,
    size: Long,
): FileResult<String> {
    val writer =
        when (val opened = fileSystem.openWriter(copy)) {
            is FileResult.Ok -> opened.value
            is FileResult.Failed -> return opened
        }
    writer.use {
        val hasher = hasherFor(ChecksumAlgorithm.SHA256)
        var offset = 0L
        while (offset < size) {
            val chunk =
                when (val read = fileSystem.read(source, offset, CHUNK)) {
                    is FileResult.Ok -> read.value
                    is FileResult.Failed -> return read
                }
            if (chunk.isEmpty()) return FileResult.IoError(source, "the source ended at $offset of $size bytes")
            val wrote = writer.write(offset, chunk)
            if (wrote is FileResult.Failed) return wrote
            hasher.update(chunk)
            offset += chunk.size
        }
        val synced = writer.sync()
        if (synced is FileResult.Failed) return synced
        return FileResult.Ok(hasher.hex())
    }
}

private suspend fun hashOf(
    fileSystem: FileSystem,
    path: Path,
): FileResult<String> {
    val hasher = hasherFor(ChecksumAlgorithm.SHA256)
    var offset = 0L
    while (true) {
        val chunk =
            when (val read = fileSystem.read(path, offset, CHUNK)) {
                is FileResult.Ok -> read.value
                is FileResult.Failed -> return read
            }
        if (chunk.isEmpty()) return FileResult.Ok(hasher.hex())
        hasher.update(chunk)
        offset += chunk.size
    }
}
