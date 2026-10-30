package dev.anydm.engine.tools

import dev.anydm.engine.platform.FileResult
import dev.anydm.engine.platform.FileSystem
import kotlinx.io.files.Path

private const val MAX_DEPTH = 3

/** The first file of each wanted name in [folder], looking at most [MAX_DEPTH] folders down (`pkg/bin/ffmpeg`). */
internal suspend fun findExecutables(
    fileSystem: FileSystem,
    folder: Path,
    names: Set<String>,
): Map<String, Path> {
    val found = mutableMapOf<String, Path>()

    suspend fun walk(
        directory: Path,
        level: Int,
    ) {
        val children = (fileSystem.list(directory) as? FileResult.Ok)?.value ?: return
        for (child in children) {
            when (fileSystem.size(child)) {
                is FileResult.Ok -> if (child.name in names && child.name !in found) found[child.name] = child
                is FileResult.AlreadyExists -> if (level < MAX_DEPTH) walk(child, level + 1)
                else -> Unit
            }
        }
    }
    walk(folder, 1)
    return found
}
