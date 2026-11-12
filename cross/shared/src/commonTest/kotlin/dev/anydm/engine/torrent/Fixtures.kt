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

internal fun singleTorrent(
    name: String = "a.iso",
    length: Long = 5,
): ByteArray = bdict("info" to bdict("name" to bstr(name), "length" to bint(length), "piece length" to bint(16384)))

internal fun multiTorrent(
    name: String,
    files: List<ByteArray>,
    extra: List<Pair<String, ByteArray>> = emptyList(),
): ByteArray = bdict("info" to bdict(listOf("name" to bstr(name), "files" to blist(files)) + extra))

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
