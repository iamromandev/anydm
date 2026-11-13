package dev.anydm.engine.torrent

/** A tiny bencode encoder for building fixtures in tests. */
internal fun concat(parts: List<ByteArray>): ByteArray {
    val out = ByteArray(parts.sumOf { it.size })
    var at = 0
    for (p in parts) {
        p.copyInto(out, at)
        at += p.size
    }
    return out
}

internal fun bint(v: Long): ByteArray = "i${v}e".encodeToByteArray()

internal fun bstr(b: ByteArray): ByteArray = concat(listOf("${b.size}:".encodeToByteArray(), b))

internal fun bstr(s: String): ByteArray = bstr(s.encodeToByteArray())

internal fun blist(items: List<ByteArray>): ByteArray = concat(listOf("l".encodeToByteArray()) + items + listOf("e".encodeToByteArray()))

internal fun blist(vararg items: ByteArray): ByteArray = blist(items.toList())

internal fun bdict(entries: List<Pair<String, ByteArray>>): ByteArray =
    concat(
        listOf("d".encodeToByteArray()) +
            entries.sortedBy { it.first }.flatMap { (k, v) -> listOf(bstr(k), v) } +
            listOf("e".encodeToByteArray()),
    )

internal fun bdict(vararg entries: Pair<String, ByteArray>): ByteArray = bdict(entries.toList())

internal fun fileEntry(
    size: Long,
    vararg path: String,
): ByteArray = bdict("length" to bint(size), "path" to blist(path.map { bstr(it) }))

/** The smallest power-of-two piece length (16 KiB to 64 MiB) that keeps [total] bytes under about a thousand pieces. */
internal fun pieceLengthFor(total: Long): Int {
    var length = 16 * 1024
    while (length < 64 * 1024 * 1024 && (total + length - 1) / length > 1_000) length *= 2
    return length
}

internal fun pieceCountOf(
    total: Long,
    pieceLength: Int,
): Int = if (total <= 0) 0 else ((total + pieceLength - 1) / pieceLength).toInt()

/** The `length` of one encoded file entry, 0 when it has none (the malformed entries some tests feed on purpose). */
internal fun lengthOf(entry: ByteArray): Long {
    val ok = decodeBencode(entry) as? DecodeResult.Ok ?: return 0
    return ((ok.value as? BValue.BDict)?.entries?.get("length") as? BValue.BInt)?.v?.coerceAtLeast(0) ?: 0
}

/** [total] bytes' worth of piece hashes that match nothing, for tests that only need a well-formed torrent. */
internal fun junkPieces(
    total: Long,
    pieceLength: Int,
): ByteArray = ByteArray(20 * pieceCountOf(total, pieceLength))

internal fun singleTorrent(
    name: String = "a.iso",
    length: Long = 5,
): ByteArray {
    val pieceLength = pieceLengthFor(length)
    return bdict(
        "info" to
            bdict(
                "name" to bstr(name),
                "length" to bint(length),
                "piece length" to bint(pieceLength.toLong()),
                "pieces" to bstr(junkPieces(length, pieceLength)),
            ),
    )
}

internal fun multiTorrent(
    name: String,
    files: List<ByteArray>,
    extra: List<Pair<String, ByteArray>> = emptyList(),
): ByteArray {
    val total = files.sumOf { lengthOf(it) }
    val pieceLength = pieceLengthFor(total)
    val defaults =
        listOf("piece length" to bint(pieceLength.toLong()), "pieces" to bstr(junkPieces(total, pieceLength)))
            .filter { (k, _) -> extra.none { it.first == k } }
    return bdict("info" to bdict(listOf("name" to bstr(name), "files" to blist(files)) + defaults + extra))
}

/** A torrent built from real content, with what a test needs to check the reader against. */
internal class BuiltTorrent(
    /** The whole `.torrent` file. */
    val bytes: ByteArray,
    /** The bencoded `info` dictionary exactly as it sits in [bytes]. */
    val info: ByteArray,
    val content: ByteArray,
    val pieceLength: Int,
)

/** Deterministic pseudo-random content of [size] bytes. */
internal fun contentOf(
    size: Int,
    seed: Int = 1,
): ByteArray = kotlin.random.Random(seed).nextBytes(size)

/**
 * A torrent over [data], the files' bytes one after another, with real SHA-1 piece hashes. [files] are (path, size) pairs whose sizes
 * add up to `data.size`; one file with [single] set is a single-file torrent named [name]. A path with `/` is split into components.
 */
internal fun torrentOf(
    files: List<Pair<String, Int>>,
    pieceLength: Int,
    data: ByteArray,
    name: String = "t",
    single: Boolean = false,
    announce: String? = null,
    announceList: List<List<String>>? = null,
    private: Boolean? = null,
): BuiltTorrent {
    check(files.sumOf { it.second } == data.size) { "files do not add up to the data" }
    val pieces =
        concat(
            (0 until pieceCountOf(data.size.toLong(), pieceLength)).map { i ->
                val from = i * pieceLength
                sha1(data, from, minOf(pieceLength, data.size - from))
            },
        )
    val shape =
        if (single) {
            listOf("length" to bint(data.size.toLong()))
        } else {
            listOf("files" to blist(files.map { (path, size) -> fileEntry(size.toLong(), *path.split('/').toTypedArray()) }))
        }
    val infoEntries =
        listOf("name" to bstr(name), "piece length" to bint(pieceLength.toLong()), "pieces" to bstr(pieces)) + shape +
            listOfNotNull(private?.let { "private" to bint(if (it) 1 else 0) })
    val info = bdict(infoEntries)
    val root =
        listOf("info" to info) +
            listOfNotNull(
                announce?.let { "announce" to bstr(it) },
                announceList?.let { tiers -> "announce-list" to blist(tiers.map { tier -> blist(tier.map { bstr(it) }) }) },
            )
    return BuiltTorrent(bdict(root), info, data, pieceLength)
}

internal fun nestedLists(depth: Int): ByteArray =
    concat(List(depth) { "l".encodeToByteArray() } + listOf(bint(1)) + List(depth) { "e".encodeToByteArray() })

internal fun nestedDicts(depth: Int): ByteArray =
    concat(List(depth) { "d1:a".encodeToByteArray() } + listOf(bint(1)) + List(depth) { "e".encodeToByteArray() })

/** [unit] written [times] times in a row, built without a list of small arrays. */
internal fun repeated(
    unit: String,
    times: Int,
): ByteArray {
    val u = unit.encodeToByteArray()
    val out = ByteArray(u.size * times)
    for (i in 0 until times) u.copyInto(out, i * u.size)
    return out
}
