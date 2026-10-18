package dev.anydm.engine.files

import dev.anydm.engine.platform.FileResult
import dev.anydm.engine.platform.FileSystem
import kotlinx.io.files.Path

/** What happens when a finished file's name is already taken in the folder (FIL-04). */
enum class ClashPolicy {
    /** Keep both: the new file becomes `name (1).ext`, `name (2).ext` and so on. */
    RENAME,

    /** Replace the file that is there. */
    OVERWRITE,

    /** Leave the file that is there and don't place this one. */
    SKIP,
}

/**
 * The first of `name`, `name (1).ext`, `name (2).ext`, ... that nothing in [folder] has taken, or the failure of the
 * check that couldn't tell. A folder in the way counts as taken. `.tar.*` is one extension; otherwise only the part
 * after the last dot is, and a name that starts with its only dot has none.
 */
suspend fun nextFreeName(
    fileSystem: FileSystem,
    folder: Path,
    name: String,
): FileResult<Path> {
    val extension = extensionOf(name)
    val stem = name.removeSuffix(extension)
    for (number in 0..MAX_TRIES) {
        val candidate = Path(folder, if (number == 0) name else "$stem ($number)$extension")
        when (val found = fileSystem.size(candidate)) {
            is FileResult.NotFound -> return FileResult.Ok(candidate)
            is FileResult.Ok, is FileResult.AlreadyExists -> Unit
            is FileResult.Failed -> return found
        }
    }
    return FileResult.IoError(Path(folder, name), "no free name after $MAX_TRIES tries")
}

private const val MAX_TRIES = 10_000
private val tarExtension = Regex("\\.tar\\.[A-Za-z0-9]+$", RegexOption.IGNORE_CASE)

/** The extension of [name] with its dot: `.tar.*` is one, otherwise what follows the last dot; none for a leading dot only. */
internal fun extensionOf(name: String): String {
    tarExtension.find(name)?.let { if (it.range.first > 0) return it.value }
    val dot = name.lastIndexOf('.')
    return if (dot > 0) name.substring(dot) else ""
}
