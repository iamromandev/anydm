package dev.anydm.engine.torrent

const val MAX_TORRENT_BYTES = 10 * 1024 * 1024
const val MAX_FILES = 100_000

/** The longest name or path component, in UTF-8 bytes: the usual file system limit. */
const val MAX_NAME_BYTES = 255

/** The longest joined file path, in UTF-8 bytes. */
const val MAX_PATH_BYTES = 4096

data class TorrentFile(
    val path: String,
    val size: Long,
)

data class TorrentMeta(
    val name: String,
    val files: List<TorrentFile>,
    /** True for a single-file torrent (`info.length`), false for a multi-file one even with one entry. */
    val isSingle: Boolean = false,
) {
    val totalSize: Long get() = files.sumOf { it.size }
}

sealed interface TorrentResult {
    data class Ok(
        val meta: TorrentMeta,
    ) : TorrentResult

    data class Err(
        val reason: String,
    ) : TorrentResult
}

/** Reads the name and file list of a `.torrent`. Untrusted input: never throws, all sizes and counts are bounded. */
fun readTorrent(bytes: ByteArray): TorrentResult {
    if (bytes.size > MAX_TORRENT_BYTES) return TorrentResult.Err("Too large")
    val root =
        when (val r = decodeBencode(bytes)) {
            is DecodeResult.Err -> return TorrentResult.Err(r.reason)
            is DecodeResult.Ok -> r.value
        }
    val info = (root as? BValue.BDict)?.entries?.get("info") as? BValue.BDict ?: return TorrentResult.Err("Missing info")
    val name = text(info.entries, "name")?.takeIf { it.isNotEmpty() } ?: return TorrentResult.Err("Missing name")
    when (check(name)) {
        Check.TooLong -> return TorrentResult.Err(NAME_TOO_LONG)
        Check.Bad -> return TorrentResult.Err("Bad name")
        Check.Ok -> Unit
    }
    val filesValue = info.entries["files"]
    val files =
        if (filesValue != null) {
            multiFile(filesValue)
        } else {
            singleFile(name, info.entries["length"])
        }
    return when (files) {
        is Files.Bad -> TorrentResult.Err(files.reason)
        is Files.Good -> TorrentResult.Ok(TorrentMeta(name, files.list, isSingle = filesValue == null))
    }
}

private sealed interface Files {
    class Good(
        val list: List<TorrentFile>,
    ) : Files

    class Bad(
        val reason: String,
    ) : Files
}

/** The `<key>.utf-8` bytes when present, else `<key>`, decoded as UTF-8 with replacement. */
private fun text(
    dict: Map<String, BValue>,
    key: String,
): String? = ((dict["$key.utf-8"] as? BValue.BBytes) ?: (dict[key] as? BValue.BBytes))?.b?.decodeToString()

private fun singleFile(
    name: String,
    length: BValue?,
): Files =
    when (length) {
        null -> Files.Bad("Missing files")
        is BValue.BInt -> if (length.v < 0) Files.Bad("Bad file size") else Files.Good(listOf(TorrentFile(name, length.v)))
        else -> Files.Bad("Bad file size")
    }

private const val NAME_TOO_LONG = "Name too long"
private const val BAD_PATH = "Bad file path"

private fun multiFile(value: BValue): Files {
    val entries = (value as? BValue.BList)?.items ?: return Files.Bad("Missing files")
    if (entries.isEmpty()) return Files.Bad("Missing files")
    if (entries.size > MAX_FILES) return Files.Bad("Too many files")
    val seen = HashSet<String>(entries.size * 2)
    var total = 0L
    val out = ArrayList<TorrentFile>(entries.size)
    val parts = ArrayList<List<String>>(entries.size)
    for (entry in entries) {
        val dict = (entry as? BValue.BDict)?.entries ?: return Files.Bad(BAD_PATH)
        val size = (dict["length"] as? BValue.BInt)?.v?.takeIf { it >= 0 } ?: return Files.Bad("Bad file size")
        if (size > Long.MAX_VALUE - total) return Files.Bad("Bad file size")
        total += size
        val names =
            when (val r = components(dict)) {
                is Parts.Bad -> return Files.Bad(r.reason)
                is Parts.Good -> r.names
            }
        val path = names.joinToString("/")
        if (path.encodeToByteArray().size > MAX_PATH_BYTES) return Files.Bad("Path too long")
        if (!seen.add(path)) return Files.Bad("Duplicate file path")
        out += TorrentFile(path, size)
        parts += names
    }
    if (hasPrefixConflict(parts)) return Files.Bad(BAD_PATH)
    return Files.Good(out)
}

private sealed interface Parts {
    class Good(
        val names: List<String>,
    ) : Parts

    class Bad(
        val reason: String,
    ) : Parts
}

private enum class Check { Ok, Bad, TooLong }

private fun isBidiControl(c: Char) = c in '\u202A'..'\u202E' || c in '\u2066'..'\u2069'

/**
 * Whether [component] can be one file or folder name on every platform: at most [MAX_NAME_BYTES] bytes, not empty, "." or "..",
 * and free of separators, ':' (a Windows drive), control characters (including NUL) and bidirectional controls.
 */
private fun check(component: String): Check =
    when {
        component.encodeToByteArray().size > MAX_NAME_BYTES -> Check.TooLong
        component.isEmpty() || component == "." || component == ".." -> Check.Bad
        component.any { it == '/' || it == '\\' || it == ':' || it < ' ' || it == '\u007F' || isBidiControl(it) } -> Check.Bad
        else -> Check.Ok
    }

/** The path components, or why they are unusable: none, not text, or one fails [check]. */
private fun components(file: Map<String, BValue>): Parts {
    val parts = ((file["path.utf-8"] ?: file["path"]) as? BValue.BList)?.items?.takeIf { it.isNotEmpty() } ?: return Parts.Bad(BAD_PATH)
    val names = ArrayList<String>(parts.size)
    for (part in parts) {
        val component = (part as? BValue.BBytes)?.b?.decodeToString() ?: return Parts.Bad(BAD_PATH)
        when (check(component)) {
            Check.TooLong -> return Parts.Bad(NAME_TOO_LONG)
            Check.Bad -> return Parts.Bad(BAD_PATH)
            Check.Ok -> names += component
        }
    }
    return Parts.Good(names)
}

private fun compareParts(
    a: List<String>,
    b: List<String>,
): Int {
    for (i in 0 until minOf(a.size, b.size)) {
        val c = a[i].compareTo(b[i])
        if (c != 0) return c
    }
    return a.size.compareTo(b.size)
}

/**
 * True when one file's path is a folder of another's ("a" and "a/b"), which no file system can hold. Sorted component by
 * component, a path sits right before everything under it, so comparing neighbours is enough.
 */
private fun hasPrefixConflict(paths: List<List<String>>): Boolean {
    val sorted = paths.sortedWith(::compareParts)
    for (i in 0 until sorted.size - 1) {
        val a = sorted[i]
        val b = sorted[i + 1]
        if (a.size < b.size && a.indices.all { a[it] == b[it] }) return true
    }
    return false
}
