package dev.anydm.engine.files

import dev.anydm.engine.platform.FileResult
import dev.anydm.engine.platform.FileSystem
import kotlinx.io.files.Path

/**
 * Tells which folders have run low on space (FIL-08): free space under the reserve. A folder that was low stays low
 * until it has twice the reserve, so one hovering around the line doesn't pause and resume its jobs over and over.
 */
class DiskGuard(
    private val fileSystem: FileSystem,
    private val reserve: suspend () -> Long,
) {
    /** Whether [folder] can take [bytes] more and still keep the reserve; a folder whose space can't be read has no room. */
    suspend fun hasRoom(
        folder: Path,
        bytes: Long,
    ): Boolean {
        val free = fileSystem.freeSpace(folder) as? FileResult.Ok ?: return false
        val needed = if (bytes > Long.MAX_VALUE - reserve()) Long.MAX_VALUE else bytes + reserve()
        return free.value >= needed
    }

    /**
     * The low ones among [folders]. [alreadyLow] is what this returned last time; a folder whose space can't be read
     * keeps its last state, and one no longer in [folders] is dropped.
     */
    suspend fun lowFolders(
        folders: Set<Path>,
        alreadyLow: Set<Path>,
    ): Set<Path> {
        val floor = reserve()
        val doubled = if (floor > Long.MAX_VALUE / 2) Long.MAX_VALUE else floor * 2
        val low = mutableSetOf<Path>()
        for (folder in folders) {
            val wasLow = folder in alreadyLow
            when (val free = fileSystem.freeSpace(folder)) {
                is FileResult.Ok -> if (free.value < (if (wasLow) doubled else floor)) low += folder
                is FileResult.Failed -> if (wasLow) low += folder
            }
        }
        return low
    }
}
