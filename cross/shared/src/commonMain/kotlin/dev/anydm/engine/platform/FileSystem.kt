package dev.anydm.engine.platform

import kotlinx.io.files.Path

/**
 * The file operations the engine needs. Paths must be absolute.
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
     * a preallocate should expect [FileResult.AlreadyExists] when it retries (clear it with [delete]).
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
     * Renames [source] to [target] in one step, replacing a file there when [replace] is true (FIL-07). With
     * `replace = false` a file already at [target] is [FileResult.AlreadyExists] and both files stay as they are; the
     * check comes just before the rename, so a file that appears in between can still be replaced (best effort, as the
     * OS has no portable rename-without-replace). Across volumes it returns [FileResult.CrossVolume]; copying is the
     * caller's job (#539). A cancelled caller does not stop the rename once the call has been made, so the file is
     * either moved or untouched.
     */
    suspend fun atomicMove(
        source: Path,
        target: Path,
        replace: Boolean = true,
    ): FileResult<Unit>

    /**
     * Moves a file or folder to the OS trash (FIL-09), or returns [FileResult.TrashUnavailable].
     * Like [atomicMove], it runs to the end even if the caller is cancelled.
     */
    suspend fun moveToTrash(path: Path): FileResult<Unit>

    /**
     * Opens the existing file at [path] for writes at any offset; never creates, truncates or resizes it (create it
     * with [preallocate]). A folder there is [FileResult.AlreadyExists]. A caller cancelled while the file is being
     * opened gets no writer, and the file is closed again.
     */
    suspend fun openWriter(path: Path): FileResult<FileWriter>

    /**
     * Deletes the file at [path] for good (a stale partial; cancel, #552); a folder is [FileResult.AlreadyExists].
     * Like [atomicMove], it finishes even if the caller is cancelled.
     */
    suspend fun delete(path: Path): FileResult<Unit>

    /**
     * The direct children of the folder at [path] (files and folders), by name; [FileResult.NotFound] if there is no
     * such folder, [FileResult.AlreadyExists] for a file. Used to find what an unpacked archive holds.
     */
    suspend fun list(path: Path): FileResult<List<Path>>

    /**
     * The length of the file at [path] (#554 finds outputs placed before a stop): [FileResult.NotFound] if there is
     * none, [FileResult.AlreadyExists] for a folder.
     */
    suspend fun size(path: Path): FileResult<Long>

    /**
     * Up to [length] bytes of the file at [path] from [offset] (a relink compares them with a new link, #572): fewer at
     * its end, none at or past it. [FileResult.NotFound] if there is no file, [FileResult.AlreadyExists] for a folder.
     */
    suspend fun read(
        path: Path,
        offset: Long,
        length: Int,
    ): FileResult<ByteArray>
}

internal fun requireAbsolute(path: Path) = require(path.isAbsolute) { "path must be absolute: $path" }
