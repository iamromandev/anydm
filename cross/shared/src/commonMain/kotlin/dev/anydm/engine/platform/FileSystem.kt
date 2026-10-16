package dev.anydm.engine.platform

import kotlinx.io.files.Path

/**
 * The file operations the engine needs beyond reading and writing bytes. Paths must be absolute.
 * Expected failures come back as [FileResult], including a name the OS can't make
 * ([FileResult.InvalidName]); only bugs throw.
 */
interface FileSystem {
    /** Creates [folder] and missing parents, then checks files can be created in it (preflight, app folders). */
    suspend fun prepareFolder(folder: Path): FileResult<Unit>

    /**
     * Creates a new file at [path], [size] bytes long, sparse where the file system allows (HTTP-16).
     * Never touches an existing file. The folder must exist. Leaves nothing behind on failure.
     *
     * Cancelling the call can leave the file behind, possibly partly written: a caller that cancels
     * a preallocate should expect [FileResult.AlreadyExists] when it retries (deleting is not part
     * of this interface).
     *
     * On file systems without sparse files (FAT32, exFAT, HFS+) it writes the whole length, which
     * can block for minutes, and FAT32 rejects files of 4 GiB and larger ([FileResult.IoError]).
     * The caller's free-space check (FIL-08) is the guard.
     */
    suspend fun preallocate(
        path: Path,
        size: Long,
    ): FileResult<Unit>

    /** Bytes still writable on the volume holding [path], which need not exist yet (FIL-08). */
    suspend fun freeSpace(path: Path): FileResult<Long>

    /**
     * Renames [source] to [target] in one step, replacing a file there (FIL-07). Across volumes it
     * returns [FileResult.CrossVolume]; copying is the caller's job (#539). A cancelled caller does
     * not stop the rename once the call has been made, so the file is either moved or untouched.
     */
    suspend fun atomicMove(
        source: Path,
        target: Path,
    ): FileResult<Unit>

    /**
     * Moves a file or folder to the OS trash (FIL-09), or returns [FileResult.TrashUnavailable].
     * Like [atomicMove], it runs to the end even if the caller is cancelled.
     */
    suspend fun moveToTrash(path: Path): FileResult<Unit>
}

internal fun requireAbsolute(path: Path) = require(path.isAbsolute) { "path must be absolute: $path" }
