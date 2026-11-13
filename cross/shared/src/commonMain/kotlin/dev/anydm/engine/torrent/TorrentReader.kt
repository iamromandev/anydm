package dev.anydm.engine.torrent

const val MAX_TORRENT_BYTES = 10 * 1024 * 1024
const val MAX_FILES = 100_000

/** The longest name or path component, in UTF-8 bytes: the usual file system limit. */
const val MAX_NAME_BYTES = 255

/** The longest joined file path, in UTF-8 bytes. */
const val MAX_PATH_BYTES = 4096

/** The longest tracker URL kept, in bytes; longer ones are dropped. */
const val MAX_TRACKER_URL_BYTES = 2048

/** The most tracker URLs kept over all tiers; the rest are dropped. */
const val MAX_TRACKERS = 100

data class TorrentFile(
    val path: String,
    val size: Long,
)

/**
 * What a `.torrent` says. Everything after [isSingle] has a default so callers that only care about names and sizes need not set it.
 * Equality compares the hashes by content, not by array identity.
 */
data class TorrentMeta(
    val name: String,
    val files: List<TorrentFile>,
    /** True for a single-file torrent (`info.length`), false for a multi-file one even with one entry. */
    val isSingle: Boolean = false,
    /** SHA-1 of the `info` dictionary exactly as written in the file (20 bytes). */
    val infoHash: ByteArray = ByteArray(20),
    /** Bytes per piece: a power of two from 16 KiB to 64 MiB. 0 only in a hand-built meta. */
    val pieceLength: Long = 0,
    /** The SHA-1 of each piece (20 bytes each); `pieceCount` entries. */
    val pieceHashes: List<ByteArray> = emptyList(),
    /** Tracker URLs in tiers (BEP 12), in file order; only http, https and udp ones. */
    val trackers: List<List<String>> = emptyList(),
    val private: Boolean = false,
) {
    val totalSize: Long get() = files.sumOf { it.size }

    /** How many pieces cover [totalSize]; the last one may be short. */
    val pieceCount: Int
        get() =
            if (pieceLength <=
                0
            ) {
                0
            } else {
                ((totalSize / pieceLength) + if (totalSize % pieceLength > 0) 1 else 0).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            }

    /** The size of piece [i]: [pieceLength] except for a short last piece; 0 for an index outside the torrent. */
    fun pieceSize(i: Int): Long =
        when {
            i < 0 || i >= pieceCount -> 0
            i == pieceCount - 1 -> totalSize - pieceLength * i
            else -> pieceLength
        }

    /** The info hash as 40 lowercase hex digits. */
    val infoHashHex: String get() = infoHash.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    override fun equals(other: Any?): Boolean =
        other is TorrentMeta &&
            name == other.name &&
            files == other.files &&
            isSingle == other.isSingle &&
            infoHash.contentEquals(other.infoHash) &&
            pieceLength == other.pieceLength &&
            pieceHashes.size == other.pieceHashes.size &&
            pieceHashes.indices.all { pieceHashes[it].contentEquals(other.pieceHashes[it]) } &&
            trackers == other.trackers &&
            private == other.private

    override fun hashCode(): Int = 31 * (31 * name.hashCode() + files.hashCode()) + infoHash.contentHashCode()
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
    val decoded =
        when (val r = decodeBencode(bytes)) {
            is DecodeResult.Err -> return TorrentResult.Err(r.reason)
            is DecodeResult.Ok -> r
        }
    val root = (decoded.value as? BValue.BDict)?.entries
    val info = root?.get("info") as? BValue.BDict ?: return TorrentResult.Err("Missing info")
    val infoRange = decoded.infoRange ?: return TorrentResult.Err("Missing info")
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
    if (files is Files.Bad) return TorrentResult.Err(files.reason)
    val list = (files as Files.Good).list
    val pieces = pieces(info.entries, list.sumOf { it.size }) ?: return TorrentResult.Err(BAD_PIECES)
    val meta =
        TorrentMeta(
            name = name,
            files = list,
            isSingle = filesValue == null,
            infoHash = sha1(bytes, infoRange.first, infoRange.last - infoRange.first + 1),
            pieceLength = pieces.length,
            pieceHashes = pieces.hashes,
            trackers = trackers(root),
            private = (info.entries["private"] as? BValue.BInt)?.v == 1L,
        )
    return TorrentResult.Ok(meta)
}

private const val BAD_PIECES = "Bad pieces"
private const val MIN_PIECE_LENGTH = 16L * 1024
private const val MAX_PIECE_LENGTH = 64L * 1024 * 1024
private const val HASH_BYTES = 20

private class Pieces(
    val length: Long,
    val hashes: List<ByteArray>,
)

/**
 * The piece length and hashes of [info] for [total] bytes, or null when the length is not a power of two from 16 KiB to 64 MiB,
 * or `pieces` is not exactly 20 bytes per piece. The count is worked out without adding to [total], so no size can wrap.
 */
private fun pieces(
    info: Map<String, BValue>,
    total: Long,
): Pieces? {
    val length = (info["piece length"] as? BValue.BInt)?.v ?: return null
    if (length < MIN_PIECE_LENGTH || length > MAX_PIECE_LENGTH || length and (length - 1) != 0L) return null
    val blob = (info["pieces"] as? BValue.BBytes)?.b ?: return null
    val count = total / length + if (total % length > 0) 1 else 0
    if (blob.size.toLong() != count * HASH_BYTES) return null
    return Pieces(length, List(count.toInt()) { blob.copyOfRange(it * HASH_BYTES, (it + 1) * HASH_BYTES) })
}

/**
 * The trackers in tiers: `announce-list` as written (skipping anything that is not a usable URL, and tiers left empty), else
 * `announce`. At most [MAX_TRACKERS] URLs are kept.
 */
private fun trackers(root: Map<String, BValue>?): List<List<String>> {
    if (root == null) return emptyList()
    var left = MAX_TRACKERS
    val tiers = ArrayList<List<String>>()
    for (tier in (root["announce-list"] as? BValue.BList)?.items.orEmpty()) {
        val urls = ArrayList<String>()
        for (item in (tier as? BValue.BList)?.items.orEmpty()) {
            if (left == 0) break
            val url = trackerUrl(item) ?: continue
            urls += url
            left--
        }
        if (urls.isNotEmpty()) tiers += urls
    }
    if (tiers.isNotEmpty()) return tiers
    return trackerUrl(root["announce"])?.let { listOf(listOf(it)) } ?: emptyList()
}

private val TRACKER_SCHEMES = listOf("http://", "https://", "udp://")

/**
 * [value] as a tracker URL: text of at most [MAX_TRACKER_URL_BYTES] printable ASCII bytes, an http, https or udp URL with a host,
 * stored with its scheme in lower case and the rest as written.
 */
private fun trackerUrl(value: BValue?): String? {
    val raw = (value as? BValue.BBytes)?.b ?: return null
    if (raw.isEmpty() || raw.size > MAX_TRACKER_URL_BYTES || raw.any { it < '!'.code.toByte() || it > '~'.code.toByte() }) return null
    val url = raw.decodeToString()
    val scheme = TRACKER_SCHEMES.firstOrNull { url.startsWith(it, ignoreCase = true) } ?: return null
    val host = url.getOrNull(scheme.length)
    return if (host == null || host == '/' || host == '?' || host == '#') null else scheme + url.substring(scheme.length)
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
