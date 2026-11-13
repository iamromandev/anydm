package dev.anydm.desktop

import dev.anydm.engine.http.DiskIo
import dev.anydm.engine.torrent.TorrentFile
import dev.anydm.engine.torrent.TorrentMeta
import dev.anydm.engine.torrent.TorrentResult
import dev.anydm.engine.torrent.readTorrent
import dev.anydm.engine.torrent.torrentFilePaths
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * What "also delete the files" of a torrent row is about: the row's root [name] under [folder] (the name its files got at the
 * start), and whether the torrent is [finished] (its files have their final names) or still has `.part` files.
 */
data class OwnTorrent(
    /** The info hash in hex: the `.torrent` copy of the row says which files the torrent has. */
    val hex: String,
    val folder: Path,
    val name: String,
    val finished: Boolean,
)

/**
 * The files a torrent made, found one by one from its metainfo, and the folders that held only them. Never a folder as a whole
 * without looking inside: the root may be a folder the user already had, and may hold things that are not the torrent's.
 */
internal class TorrentRemoval(
    /** The root folder of a multi-file torrent; null for a single-file one. */
    val root: Path?,
    /** The torrent's own files that are on disk, part files and renamed ones. */
    val files: List<Path>,
    /** Folders under [root] (it too) that held those files, deepest first. They go only if the removal leaves them empty. */
    val folders: List<Path>,
)

/**
 * Works out what [own] made on [disk], from the torrent's metainfo [meta]. A part file is the torrent's; a file at a final path is
 * only when the torrent is finished, or when it has no part file and has the size the torrent says (a finish that stopped half
 * way renamed it). A file below a link (the root or one of its folders) is not touched: it is not known to be the torrent's.
 * Null when the names do not make paths under the folder.
 */
internal fun planTorrentRemoval(
    disk: DiskIo,
    meta: TorrentMeta,
    own: OwnTorrent,
): TorrentRemoval? {
    val folder = own.folder.normalize()
    return try {
        val rootPath = folder.resolve(own.name).normalize()
        if (rootPath.parent != folder) return null
        val paths = torrentFilePaths(folder, own.name, meta)
        val files = ArrayList<Path>()
        val folders = LinkedHashSet<Path>()
        for ((i, f) in meta.files.withIndex()) {
            val final = paths.finals[i]
            val part = paths.parts[i]
            // single-file: the file is the root itself, there is no folder to look at
            val dirs = if (meta.isSingle) emptyList() else dirsBetween(rootPath, final)
            if (dirs.any { disk.isLink(it) }) continue
            val found = ArrayList<Path>()
            if (disk.exists(part) && !disk.isLink(part)) found.add(part)
            if (final.isOwn(disk, f, partThere = disk.exists(part), finished = own.finished)) found.add(final)
            if (found.isEmpty()) continue
            files.addAll(found)
            folders.addAll(dirs)
        }
        val root = if (meta.isSingle) null else rootPath
        TorrentRemoval(root, files, folders.sortedByDescending { it.nameCount })
    } catch (_: InvalidPathException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}

/** The root and every folder between it and [file], root first. */
private fun dirsBetween(
    root: Path,
    file: Path,
): List<Path> {
    val out = ArrayList<Path>()
    var d = file.parent
    while (d != null && d.startsWith(root)) {
        out.add(0, d)
        if (d == root) break
        d = d.parent
    }
    return out
}

private fun Path.isOwn(
    disk: DiskIo,
    f: TorrentFile,
    partThere: Boolean,
    finished: Boolean,
): Boolean {
    if (disk.isLink(this)) return false
    if (finished) return disk.size(this) != null // a regular file
    // not finished: its part file is the torrent's; what is at the final path is the torrent's only if it was renamed there already
    if (partThere) return false
    return disk.size(this) == f.size
}

/** Reads the metainfo of [bytes]; null when it is not a torrent. */
internal fun metaOf(bytes: ByteArray): TorrentMeta? = (readTorrent(bytes) as? TorrentResult.Ok)?.meta

/**
 * Removes what [plan] found: the whole root to the Trash when it holds nothing else (so the user finds the folder as it was),
 * else each file one by one (to the Trash, or for good when there is none), then the folders that are empty now, deepest
 * first, with a delete that fails on a folder that still holds something. The folder the user chose is never in the plan.
 */
internal fun removeTorrentFiles(
    plan: TorrentRemoval,
    disk: DiskIo,
    opener: OsOpener,
) {
    val root = plan.root
    if (root != null && holdsOnly(disk, root, plan.files.toSet()) && runCatching { opener.trash(root) }.getOrDefault(false)) return
    for (file in plan.files) {
        runCatching {
            // a link is removed as the link it is: the Trash would be asked to move what it points to on some systems
            if (disk.isLink(file) || !opener.trash(file)) disk.delete(file)
        }
    }
    for (dir in plan.folders) runCatching { disk.delete(dir) }
}

/** Whether [dir] holds nothing but [own] files and folders that hold nothing else: no foreign file, no link. */
private fun holdsOnly(
    disk: DiskIo,
    dir: Path,
    own: Set<Path>,
): Boolean {
    if (disk.isLink(dir)) return false
    val kids = disk.children(dir) ?: return false
    return kids.all { k ->
        when {
            k in own -> !disk.isLink(k)
            disk.isLink(k) -> false
            else -> holdsOnly(disk, k, own)
        }
    }
}
