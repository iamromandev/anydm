package dev.anydm.engine.platform

import kotlinx.io.files.Path

/** A small file made whole in one call, replacing any old one at [path]; its folder must exist. */
internal suspend fun FileSystem.writeWhole(
    path: Path,
    bytes: ByteArray,
): FileResult<Unit> {
    delete(path)
    val made = preallocate(path, bytes.size.toLong())
    if (made is FileResult.Failed) return made
    val writer =
        when (val opened = openWriter(path)) {
            is FileResult.Failed -> return opened
            is FileResult.Ok -> opened.value
        }
    return writer.use { it.write(0, bytes) }
}
