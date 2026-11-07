package dev.anydm.desktop

import dev.anydm.engine.net.SpeedLimit
import java.util.Locale

/** Most cells the chunk map shows; more chunks are grouped into this many. */
const val MAX_CHUNK_CELLS = 200

/** One square of the chunk map: how much of the chunks it stands for is done, and the network that did it (null: none yet or unknown). */
data class ChunkCell(
    val fill: Float,
    val key: String?,
)

/** The squares to draw and how many chunks (or pieces) there are in all. */
data class ChunkMap(
    val cells: List<ChunkCell>,
    val chunks: Int,
) {
    companion object {
        val Empty = ChunkMap(emptyList(), 0)
    }
}

/**
 * The map of a file as at most [cap] squares: each chunk its own square, or, past [cap], neighbours grouped so a square
 * stands for several. [sizes] and [written] are bytes of each chunk in file order.
 *
 * The engine knows how many bytes each network carried in all ([shares]) but not which chunk each carried, so the squares
 * take the networks in turn by their position in the map, each network getting as many as its share of the bytes: a map
 * that reads right in total, an approximation per square. A square's network depends on its position only (and the shares).
 */
fun chunkCells(
    sizes: List<Long>,
    written: List<Long>,
    shares: Map<String, Long>,
    cap: Int = MAX_CHUNK_CELLS,
): ChunkMap {
    val n = sizes.size
    if (n == 0) return ChunkMap.Empty
    val groups = if (n <= cap) n else cap
    val fills =
        (0 until groups).map { g ->
            val from = (g.toLong() * n / groups).toInt()
            val to = ((g + 1).toLong() * n / groups).toInt()
            val size = (from until to).sumOf { sizes[it] }
            val done = (from until to).sumOf { written[it].coerceAtMost(sizes[it]) }
            if (size <= 0) 0f else (done.toDouble() / size).toFloat().coerceIn(0f, 1f)
        }
    // The turns go to every cell, not only the filled ones: a cell keeps its network as the ones before it fill in.
    val keys = attribute(groups, shares)
    return ChunkMap(fills.mapIndexed { i, fill -> ChunkCell(fill, if (fill > 0f) keys[i] else null) }, n)
}

// [count] turns handed to the networks by share: each turn goes to the network furthest behind its due.
private fun attribute(
    count: Int,
    shares: Map<String, Long>,
): List<String?> {
    val live = shares.filterValues { it > 0 }.toSortedMap()
    val total = live.values.sum()
    if (count == 0 || total <= 0) return List(count) { null }
    val given = live.keys.associateWith { 0 }.toMutableMap()
    return (1..count).map { turn ->
        val key = live.keys.maxByOrNull { live.getValue(it).toDouble() / total * turn - given.getValue(it) }!!
        given[key] = given.getValue(key) + 1
        key
    }
}

/** Whole percents of [bytes] that add up to 100 (the largest remainders round up); all zero when no bytes. */
fun sharePercents(bytes: List<Long>): List<Int> {
    val total = bytes.sum()
    if (bytes.isEmpty() || total <= 0) return List(bytes.size) { 0 }
    val exact = bytes.map { it.toDouble() * 100 / total }
    val result = exact.map { it.toInt() }.toMutableList()
    val left = 100 - result.sum()
    exact.indices
        .sortedWith(compareByDescending<Int> { exact[it] - result[it] }.thenBy { it })
        .take(left)
        .forEach { result[it]++ }
    return result
}

/** "2.0x Wi-Fi alone": how many times the whole is [ratio] of what [name] gives on its own. */
data class AloneBadge(
    val name: String,
    val ratio: Double,
    val label: String,
)

private const val MIN_ALONE_RATIO = 1.05

/**
 * What bonding gains: [total] against what each network gives alone ([networks]: name and its speed, or its bytes when
 * nothing is moving). A network that gave almost all of it (under 1.05x) makes no point worth a badge; fastest first.
 */
fun aloneBadges(
    total: Long,
    networks: List<Pair<String, Long>>,
): List<AloneBadge> {
    if (networks.size < 2 || total <= 0) return emptyList()
    return networks
        .filter { it.second > 0 }
        .map { (name, metric) -> name to total.toDouble() / metric }
        .filter { it.second.isFinite() && it.second >= MIN_ALONE_RATIO }
        .sortedBy { it.second }
        .map { (name, ratio) -> AloneBadge(name, ratio, String.format(Locale.ROOT, "%.1f× %s ALONE", ratio, name.uppercase())) }
}

/** What bonding saved on a finished run: [seconds] sooner than the biggest [network] would have managed alone, as [text]. */
data class TimeSaved(
    val seconds: Long,
    val network: String,
    val text: String,
)

private const val MIN_SHARE_DIVISOR = 10L // a network counts from a tenth of the bytes: bytes * 10 >= total
private const val MIN_RUN_SECONDS = 30L
private const val MIN_SAVED_SECONDS = 10L

/**
 * An estimate of the time bonding saved: the run took [seconds] for [total] bytes, and the biggest network ([networks]:
 * name and the bytes it carried) alone would have needed `seconds * total / its bytes`. Null unless two or more networks
 * each carried at least a tenth, the run lasted 30 s or more and at least 10 s were saved. Shared speed is not alone
 * speed, so the wording says "about".
 */
fun timeSaved(
    total: Long,
    seconds: Long,
    networks: List<Pair<String, Long>>,
): TimeSaved? {
    if (total <= 0 || seconds < MIN_RUN_SECONDS) return null
    if (networks.count { it.second * MIN_SHARE_DIVISOR >= total } < 2) return null
    val (name, biggest) = networks.maxByOrNull { it.second } ?: return null
    if (biggest <= 0) return null
    val exact = seconds.toDouble() * total / biggest - seconds
    val saved = exact.toLong()
    if (saved < MIN_SAVED_SECONDS) return null
    // Round first, then split into units, so 59.5 s reads "1 min", never "60 sec".
    val rounded = Math.round(exact)
    val minutes = (rounded + 30) / 60
    val span =
        when {
            rounded < 60 -> "$rounded sec"
            minutes < 120 -> "$minutes min"
            minutes % 60 == 0L -> "${minutes / 60} h"
            else -> "${minutes / 60} h ${minutes % 60} min"
        }
    return TimeSaved(saved, name, "About $span sooner than $name alone")
}

/** The short line that names what limits a download's speed (#634). */
fun limitText(
    limit: SpeedLimit,
    looks: NetworkLooks = NetworkLooks(),
): String =
    when (limit) {
        SpeedLimit.SlowMode -> "Limited by slow mode"
        SpeedLimit.SpeedCap -> "Limited by your speed limit"
        is SpeedLimit.DataLimit -> "${looks.nameOf(limit.key, limit.network)} reached its data limit"
        is SpeedLimit.Unreachable -> "${limit.network} can't reach the server"
    }
