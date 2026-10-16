package dev.anydm.engine.core

import kotlinx.io.files.Path

/** What every partial file ends in. A planned or output path that ends in it could be some download's partial. */
internal const val PARTIAL_SUFFIX = ".anydm"

/**
 * Stays inside the save folder: relative, `/` between names, no `.`, `..`, `\` or `:`. A guard until FIL-03 (#539)
 * cleans names: that name cleaner must run before a real protocol plans its paths, because the `:` rule here refuses
 * names that are legal on some systems, and case or Unicode lookalikes are not handled at all.
 */
internal fun isSafeRelativePath(path: String): Boolean =
    path.split('/').all {
        it.isNotEmpty() && it != "." && it != ".." && '\\' !in it &&
            ':' !in it
    }

internal fun targetPath(
    folder: Path,
    relative: String,
): Path = Path(folder, *relative.split('/').toTypedArray())

/**
 * The partial for [target] while download [downloadId] fetches it: `<name>.<downloadId>.anydm` beside it. The id makes
 * two downloads of the same name in one folder keep separate partials, and keeps the name the same on every run, so a
 * resume finds it again.
 */
internal fun partialPath(
    target: Path,
    downloadId: DownloadId,
): Path = Path(requireNotNull(target.parent) { "no folder: $target" }, "${target.name}.${downloadId.value}$PARTIAL_SUFFIX")

/**
 * Why [paths] (a plan's files, or a finalize result's outputs) can't be made in one save folder, or null if they can:
 * one is unsafe, ends in [PARTIAL_SUFFIX], repeats an earlier one (also when only the case differs, as the same file on
 * a case-insensitive disk), or lies inside another, which would have to be a folder and a file at once. The message
 * names the offending path in single quotes.
 */
internal fun pathProblem(paths: List<String>): String? {
    for (path in paths) {
        if (!isSafeRelativePath(path)) return "'$path' is not a path inside the save folder"
        if (path.lowercase().endsWith(PARTIAL_SUFFIX)) return "'$path' ends in $PARTIAL_SUFFIX, which is how partial files are named"
    }
    val seen = mutableSetOf<String>()
    for (path in paths) {
        val isNew = seen.add(path.lowercase())
        if (!isNew) return "'$path' is the same file as an earlier path (names that differ only in case are one file on some disks)"
    }
    for (path in paths) {
        val parts = path.lowercase().split('/')
        val folders = (1 until parts.size).map { length -> parts.take(length).joinToString("/") }
        if (folders.any { it in seen }) return "'$path' is inside another path, which would have to be a folder and a file at once"
    }
    return null
}

/** The sum, stopping at [Long.MAX_VALUE] instead of wrapping around, for sizes that came from outside. */
internal fun Iterable<Long>.saturatingSum(): Long {
    var sum = 0L
    for (value in this) sum = if (value > Long.MAX_VALUE - sum) Long.MAX_VALUE else sum + value
    return sum
}
