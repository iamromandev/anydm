package dev.anydm.engine.torrent

import dev.anydm.engine.http.DiskIo
import dev.anydm.engine.http.Fail
import dev.anydm.engine.http.PartFile
import dev.anydm.engine.http.Reasons
import dev.anydm.engine.http.diskReason
import dev.anydm.engine.http.uniqueName
import java.io.IOException
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.Locale

/**
 * A torrent's files on disk: every file as `<name>.part`, full size from the start, under `<folder>/<root>` (a folder for a
 * multi-file torrent, the file itself for a single-file one). Pieces are written at the places [Layout] gives, so nothing can land
 * outside the torrent's own files; [finish] renames every `.part` and makes the zero-length files.
 *
 * A part file gets another suffix when `.part` would make it the name of another file of the torrent (files `a` and `a.part`).
 * A resume after a [finish] that stopped half way finds some files already at their final names: those are used where they are
 * (never replaced by a new, zero-filled part file) and [finish] moves only the rest.
 *
 * [writePiece] may be called from several threads (pieces never overlap); [finish] and [close] come after the last write.
 * Failures with a reason for the row are [Fail]s.
 */
internal class TorrentStore private constructor(
    private val disk: DiskIo,
    private val meta: TorrentMeta,
    private val layout: Layout,
    private val folder: Path,
    /** The name of the file or folder under [folder] that holds the torrent. */
    val root: String,
    /** Where each file is now: its part file, or its final path when a finish that stopped half way had already renamed it. */
    private val current: List<Path>,
    /** The final paths (under [root]) that are in place already: files a resume found renamed. */
    private val inPlace: Set<Path>,
    private val handles: Array<PartFile?>,
) {
    private var closed = false

    /** Writes the verified piece [piece] (all of its bytes) across the files it spans. */
    fun writePiece(
        piece: Int,
        data: ByteArray,
    ) {
        require(data.size == layout.pieceSize(piece)) { "piece $piece is ${layout.pieceSize(piece)} bytes, not ${data.size}" }
        val segments = requireNotNull(layout.segments(piece)) { "no such piece $piece" }
        var at = 0
        try {
            for (s in segments) {
                checkNotNull(handles[s.file]).write(s.offset, data, at, s.length)
                at += s.length
            }
        } catch (e: IOException) {
            throw Fail(diskReason(e, Reasons.NOT_WRITABLE))
        }
    }

    /**
     * Writes the verified piece [piece] from the [blocks] it arrived in, in order, which together are all of its bytes. Each block is
     * written where it belongs, split at file borders, so the piece is never copied into one array first.
     */
    fun writePiece(
        piece: Int,
        blocks: List<ByteArray>,
    ) {
        require(blocks.sumOf { it.size } == layout.pieceSize(piece)) { "piece $piece is ${layout.pieceSize(piece)} bytes" }
        requireNotNull(layout.segments(piece)) { "no such piece $piece" }
        var at = 0
        try {
            for (block in blocks) {
                var from = 0
                for (s in checkNotNull(layout.segments(piece, at, block.size))) {
                    checkNotNull(handles[s.file]).write(s.offset, block, from, s.length)
                    from += s.length
                }
                at += block.size
            }
        } catch (e: IOException) {
            throw Fail(diskReason(e, Reasons.NOT_WRITABLE))
        }
    }

    /**
     * The bytes of piece [piece] as they are on disk now, or null when the piece does not exist or a file of it is missing or too
     * short. Reads through the disk, not through the open files, so it shows what a restart would find.
     */
    fun readPiece(piece: Int): ByteArray? {
        val segments = layout.segments(piece) ?: return null
        val out = ByteArray(layout.pieceSize(piece))
        var at = 0
        try {
            for (s in segments) {
                if (disk.readAt(current[s.file], s.offset, out, at, s.length) != s.length) return null
                at += s.length
            }
        } catch (_: IOException) {
            return null
        }
        return out
    }

    /** Makes what was written survive a crash. Ignores a disk that refuses: the next resume re-checks the pieces anyway. */
    fun force() {
        if (!closed) for (h in handles) runCatching { h?.force() }
    }

    /** Forces and closes every file. Safe to call again. */
    fun close() {
        if (closed) return
        force()
        closed = true
        for (h in handles) runCatching { h?.close() }
    }

    /**
     * Closes the files and renames every `.part` to its final name, then makes the zero-length files. Nothing that exists is
     * overwritten: when a final path has been taken meanwhile, the whole torrent goes to the next free name. Returns the root name.
     */
    fun finish(): String {
        close()
        try {
            val target =
                if (conflicts(root) { false }) {
                    uniqueName(root) { conflicts(it) { false } }
                } else {
                    root
                }
            for ((i, f) in meta.files.withIndex()) {
                val final = finalPath(target, f)
                disk.mkdirs(final.parent)
                if (f.size == 0L) {
                    disk.openPart(final, append = false).close()
                } else if (current[i] != final) {
                    disk.move(current[i], final)
                }
            }
            if (target != root && !meta.isSingle) removeEmptyFolders()
            return target
        } catch (e: IOException) {
            throw Fail(diskReason(e, Reasons.NOT_WRITABLE))
        }
    }

    private fun finalPath(
        root: String,
        file: TorrentFile,
    ): Path = filePath(folder, root, meta, file)

    /** Whether [name] is unusable as the root: it is a file where a folder is needed, or a path of the torrent exists under it. */
    private fun conflicts(
        name: String,
        extra: (Path) -> Boolean,
    ): Boolean = rootConflicts(disk, folder, meta, name, withParts = false, own = inPlace, extra = extra)

    /** Removes the folders of the old root that the move left empty, deepest first; one that still holds something stays. */
    private fun removeEmptyFolders() {
        val base = folder.resolve(root)
        val dirs = HashSet<Path>()
        for (p in current) {
            var d = p.parent
            while (d != null && d.startsWith(base)) {
                dirs.add(d)
                d = d.parent
            }
        }
        for (d in dirs.sortedByDescending { it.nameCount }) runCatching { disk.delete(d) }
    }

    companion object {
        /**
         * Opens the torrent's files under [folder]. A fresh start ([resume] false) picks the first free root name at or after [name]
         * (see [rootConflicts]; [taken] says which root paths other running torrents hold); a resume keeps [name] and the `.part` files
         * as they are. Space for all the files is reserved first, counting what the `.part` files already hold: when it is not
         * there the open fails with [Reasons.DISK_FULL] and leaves no file behind.
         */
        fun open(
            disk: DiskIo,
            meta: TorrentMeta,
            folder: Path,
            name: String,
            resume: Boolean,
            taken: (Path) -> Boolean = { false },
        ): TorrentStore {
            try {
                if (folder.resolve(name).normalize().parent != folder) throw Fail(Reasons.NOT_WRITABLE)
                disk.mkdirs(folder)
                val root =
                    if (resume) {
                        name
                    } else {
                        uniqueName(name) { rootConflicts(disk, folder, meta, it, withParts = true, anyAtRoot = true) { p -> taken(p) } }
                    }
                val finals = meta.files.map { filePath(folder, root, meta, it) }
                // two names that are one file on a disk that ignores case: one would overwrite the other
                if (finals.map { keyOf(it) }.toSet().size != finals.size) throw Fail(Reasons.NOT_WRITABLE)
                val parts = partPaths(finals)
                // a resume may find a file already renamed by a finish that stopped half way: it stays where it is
                val renamed = { i: Int ->
                    resume && meta.files[i].size > 0 && disk.size(parts[i]) == null && disk.size(finals[i]) == meta.files[i].size
                }
                val current = meta.files.indices.map { if (renamed(it)) finals[it] else parts[it] }
                val inPlace =
                    if (!resume) {
                        emptySet()
                    } else {
                        meta.files.indices
                            .filter {
                                renamed(
                                    it,
                                ) || (meta.files[it].size == 0L && disk.size(finals[it]) == 0L)
                            }.map { finals[it] }
                            .toSet()
                    }
                val missing = meta.files.indices.sumOf { i -> maxOf(0L, meta.files[i].size - (disk.size(current[i]) ?: 0L)) }
                if (missing > 0 && disk.freeSpace(folder) < missing) throw Fail(Reasons.DISK_FULL)
                val created = ArrayList<Path>()
                val handles = arrayOfNulls<PartFile>(meta.files.size)
                try {
                    for ((i, f) in meta.files.withIndex()) {
                        if (f.size == 0L) continue
                        disk.mkdirs(current[i].parent)
                        if (disk.size(current[i]) == null) created.add(current[i])
                        handles[i] = disk.openSpans(current[i], f.size)
                    }
                } catch (e: Throwable) {
                    handles.forEach { runCatching { it?.close() } }
                    created.forEach { runCatching { disk.delete(it) } }
                    throw e
                }
                return TorrentStore(disk, meta, Layout(meta), folder, root, current, inPlace, handles)
            } catch (e: IOException) {
                throw Fail(diskReason(e, Reasons.NOT_WRITABLE))
            } catch (_: InvalidPathException) {
                throw Fail(Reasons.NOT_WRITABLE)
            }
        }

        internal fun partOf(final: Path): Path = final.resolveSibling("${final.fileName}.part")

        private fun keyOf(p: Path): String = p.normalize().toString().lowercase(Locale.ROOT)

        /**
         * The part file of each of [finals]: `<name>.part`, or `<name>.part1`, `<name>.part2`... when that is the name of a final path
         * or of another part file (compared without regard to case), so no part file is ever another file or another file's part.
         */
        internal fun partPaths(finals: List<Path>): List<Path> {
            val used = finals.mapTo(HashSet()) { keyOf(it) }
            return finals.map { final ->
                var n = 0
                var part: Path
                do {
                    part = final.resolveSibling("${final.fileName}.part" + if (n == 0) "" else n.toString())
                    n++
                } while (!used.add(keyOf(part)))
                part
            }
        }

        /**
         * The final path of [file] under the root [root] of [folder]: the root itself for a single-file torrent, else the file's path
         * below it. A path that would leave the root is refused (the reader has already checked every name, this is the last guard).
         */
        fun filePath(
            folder: Path,
            root: String,
            meta: TorrentMeta,
            file: TorrentFile,
        ): Path {
            if (meta.isSingle) return folder.resolve(root).also { check(it.parent == folder) }
            var p = folder.resolve(root)
            val base = p
            for (part in file.path.split('/')) p = p.resolve(part)
            p = p.normalize()
            if (!p.startsWith(base) || p == base) throw InvalidPathException(file.path, "outside the torrent")
            return p
        }

        /**
         * Whether the root name [name] cannot be used for [meta] under [folder]: for a single-file torrent the file (or its `.part`
         * with [withParts]) exists; for a multi-file one [name] is a plain file, or any of the torrent's file paths (or their `.part`
         * files with [withParts]) exists under it. With [anyAtRoot] (a fresh start) a multi-file torrent's root also counts as taken
         * when anything is there, a folder too: the user's own folder is never merged into, and it is never a folder this torrent
         * made, so a later delete of the torrent's files cannot meet things that are not its own. A root path that [extra] claims
         * counts as taken as well. A final path in [own] is the torrent's own file, put there by an earlier run: it is no conflict.
         */
        fun rootConflicts(
            disk: DiskIo,
            folder: Path,
            meta: TorrentMeta,
            name: String,
            withParts: Boolean,
            anyAtRoot: Boolean = false,
            own: Set<Path> = emptySet(),
            extra: (Path) -> Boolean,
        ): Boolean {
            val root =
                try {
                    folder.resolve(name).normalize()
                } catch (_: InvalidPathException) {
                    return true
                }
            if (root.parent != folder || extra(root)) return true
            if (meta.isSingle) return (root !in own && disk.exists(root)) || (withParts && disk.exists(partOf(root)))
            if (disk.size(root) != null || (anyAtRoot && disk.exists(root))) return true
            return meta.files.any {
                val final = filePath(folder, name, meta, it)
                (final !in own && disk.exists(final)) || (withParts && disk.exists(partOf(final)))
            }
        }
    }
}

/** Where the files of a torrent are: the final path of each file of the torrent, in file order, and the part file it has until the end. */
class TorrentFilePaths(
    val finals: List<Path>,
    val parts: List<Path>,
)

/**
 * The paths a torrent's files have under [folder] when its root is named [root]: the same ones [TorrentStore] writes, so a delete of
 * the torrent's files can name each of them and touch nothing else. Throws [IllegalArgumentException] when [root] or a file path of
 * [meta] would leave [folder]/[root].
 */
fun torrentFilePaths(
    folder: Path,
    root: String,
    meta: TorrentMeta,
): TorrentFilePaths {
    val finals =
        try {
            meta.files.map { TorrentStore.filePath(folder, root, meta, it) }
        } catch (e: IllegalStateException) {
            throw IllegalArgumentException("outside the folder", e)
        }
    return TorrentFilePaths(finals, TorrentStore.partPaths(finals))
}
