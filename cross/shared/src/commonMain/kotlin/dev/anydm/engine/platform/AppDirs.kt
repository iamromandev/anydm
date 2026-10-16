package dev.anydm.engine.platform

import kotlinx.io.files.Path

/**
 * Where anydm keeps its own files: [data] (database, settings), [logs] (DIA-01) and [cache]
 * (anything rebuildable). Only names the folders; whoever writes first calls
 * [FileSystem.prepareFolder]. Tests point all three into a temporary folder.
 */
data class AppDirs(
    val data: Path,
    val logs: Path,
    val cache: Path,
) {
    init {
        require(data.isAbsolute && logs.isAbsolute && cache.isAbsolute) { "app folders must be absolute: $this" }
    }
}
