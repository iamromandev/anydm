package dev.anydm.engine.torrent

import dev.anydm.engine.core.pathProblem
import dev.anydm.engine.files.NameCleaner

/** The most files one torrent may have: each becomes a row, and the plan and the checks are linear in it. */
const val MAX_TORRENT_FILES = 20_000

/** The default priority of a selected file; 0 skips a file, 7 is the highest. */
const val DEFAULT_PRIORITY = 4

/** The highest priority of a file. */
const val MAX_FILE_PRIORITY = 7

/**
 * Why [info] can't be downloaded here, or null if it can: no files, too many, no name, a file of negative size, or a path
 * that can't be made in a save folder (outside it, a `.anydm` name, two names that are one file on some disks, a file
 * inside a file). Metadata comes from strangers: it is checked before anything is planned from it.
 */
fun checkTorrent(info: TorrentInfo): String? {
    if (info.files.isEmpty()) return "the torrent has no files"
    if (info.files.size > MAX_TORRENT_FILES) return "the torrent has ${info.files.size} files; the limit is $MAX_TORRENT_FILES"
    if (info.name.isBlank()) return "the torrent has no name"
    if (info.files.any { it.size < 0 }) return "the torrent has a file with a negative size"
    // A colon is cleaned out of a planned name (see plannedPaths), so it is no reason to refuse the torrent.
    return pathProblem(info.files.map { it.path.replace(':', COLON_REPLACEMENT) })
        ?.let { "the torrent can't be placed in the save folder: $it" }
}

/**
 * Where each file goes, relative to the save folder, in torrent order: a multi-file torrent's root folder takes the
 * job's [placedName] and every name is cleaned; a single file is named [placedName].
 */
fun plannedPaths(
    info: TorrentInfo,
    placedName: String,
    cleaner: NameCleaner,
): List<String> {
    val root = clean(cleaner, placedName)
    if (info.singleFile) return listOf(root)
    return info.files.map { file ->
        val segments = file.path.split('/')
        val below = segments.drop(1).ifEmpty { segments.takeLast(1) }
        (listOf(root) + below.map { clean(cleaner, it) }).joinToString("/")
    }
}

private const val COLON_REPLACEMENT = '_'

// The runner refuses a colon in any planned path, on every system, though most allow it in a name.
private fun clean(
    cleaner: NameCleaner,
    name: String,
): String = cleaner.clean(name).replace(':', COLON_REPLACEMENT)

/** A stored choice for a torrent of [files] files: empty means every file at the default; a list of another length is null. */
fun resolvePriorities(
    stored: List<Int>,
    files: Int,
): List<Int>? =
    when {
        stored.isEmpty() -> List(files) { DEFAULT_PRIORITY }
        stored.size == files -> stored
        else -> null
    }

/**
 * The priorities for taking only the files whose torrent paths are in [wanted] (null: all of them). Null when [wanted]
 * names no file of the torrent.
 */
fun prioritiesFor(
    info: TorrentInfo,
    wanted: Set<String>?,
): List<Int>? {
    if (wanted == null) return List(info.files.size) { DEFAULT_PRIORITY }
    val priorities = info.files.map { if (it.path in wanted) DEFAULT_PRIORITY else 0 }
    return priorities.takeIf { list -> list.any { it > 0 } }
}
