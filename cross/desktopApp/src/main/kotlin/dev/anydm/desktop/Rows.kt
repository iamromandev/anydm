package dev.anydm.desktop

import dev.anydm.engine.DownloadSnapshot
import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadKind

enum class SortKey(
    val label: String,
) {
    NAME("Name"),
    SIZE("Size"),
    PROGRESS("Progress"),
    STATUS("Status"),
    PRIORITY("Priority"),
    SPEED("Speed"),
    ETA("ETA"),
    PROTOCOL("Protocol"),
}

/** What the sidebar filters by and a card's badge says; several kinds can share one. */
enum class Protocol(
    val label: String,
    val kinds: Set<DownloadKind>,
) {
    HTTP("HTTP", setOf(DownloadKind.DIRECT)),
    SITE("Site", setOf(DownloadKind.SITE)),
    TORRENT("Torrent", setOf(DownloadKind.TORRENT)),
    FTP("FTP", setOf(DownloadKind.FTP)),
    TOOL("Tool", setOf(DownloadKind.TOOL)),
}

val DownloadKind.protocol: Protocol get() =
    Protocol.entries.firstOrNull { this in it.kinds }
        ?: error("No protocol lists the download kind $this")

/** How the list is shown: [sort] null keeps the engine's order. */
data class ListOptions(
    val sort: SortKey? = null,
    val descending: Boolean = false,
    val query: String = "",
    val protocol: Protocol? = null,
    val expanded: Set<DownloadId> = emptySet(),
)

/** One line of the list: a job, or a collection's item under it ([depth] 1). */
data class Row(
    val snapshot: DownloadSnapshot,
    val depth: Int = 0,
    val expandable: Boolean = false,
    val expanded: Boolean = false,
) {
    val id: DownloadId get() = snapshot.download.id
}

/**
 * The rows the list shows for [filter] and [options]. A collection's items are never rows of their own: they follow
 * their collection, in playlist order, when it is expanded. A collection is shown when it or any of its items matches;
 * sorting orders the top-level rows, and items stay with their collection.
 */
internal fun buildRows(
    snapshots: List<DownloadSnapshot>,
    filter: Filter,
    options: ListOptions,
): List<Row> {
    val byId = snapshots.associateBy { it.download.id }
    val children =
        snapshots
            .filter { it.download.parentId != null && it.download.parentId in byId }
            .groupBy { it.download.parentId!! }
            .mapValues { (_, items) -> items.sortedWith(compareBy(nullsLast()) { it.download.playlistIndex }) }
    val query = options.query.trim()

    fun matches(s: DownloadSnapshot): Boolean =
        filter.accepts(s) && (options.protocol == null || s.download.kind.protocol == options.protocol) &&
            (query.isEmpty() || matchesQuery(s, query))

    val top =
        snapshots.filter { s ->
            val parent = s.download.parentId
            (parent == null || parent !in byId) && (matches(s) || children[s.download.id].orEmpty().any(::matches))
        }
    val sorted = options.sort?.let { key -> top.sortedWith(comparator(key, options.descending, top)) } ?: top
    return buildList {
        for (s in sorted) {
            val items = children[s.download.id].orEmpty()
            val open = s.download.id in options.expanded
            add(Row(s, expandable = s.download.isCollection, expanded = open))
            if (open) items.filter(::matches).forEach { add(Row(it, depth = 1)) }
        }
    }
}

internal fun Filter.accepts(s: DownloadSnapshot): Boolean =
    when (this) {
        Filter.All -> true
        is Filter.Status -> s.download.status in group.statuses
        is Filter.InCategory -> s.download.categoryId == id
    }

private fun matchesQuery(
    s: DownloadSnapshot,
    query: String,
): Boolean =
    displayName(s).contains(query, ignoreCase = true) ||
        s.mirrors.any { it.url.contains(query, ignoreCase = true) } ||
        s.download.sourceUrl?.contains(query, ignoreCase = true) == true

// Unknown values sort last whichever way the list runs.
private fun comparator(
    key: SortKey,
    descending: Boolean,
    rows: List<DownloadSnapshot>,
): Comparator<DownloadSnapshot> {
    // Each name is lowercased once, not on every comparison.
    val names = rows.associate { it.download.id to displayName(it).lowercase() }

    fun name(s: DownloadSnapshot): String = names.getValue(s.download.id)

    fun <T : Comparable<T>> by(value: (DownloadSnapshot) -> T?): Comparator<DownloadSnapshot> {
        val order: Comparator<T> = if (descending) reverseOrder() else naturalOrder()
        return compareBy(nullsLast(order), value)
    }
    val primary: Comparator<DownloadSnapshot> =
        when (key) {
            SortKey.NAME -> by { name(it) }
            SortKey.SIZE -> by { it.total ?: it.download.size }
            SortKey.PROGRESS -> by { progress(it) }
            SortKey.STATUS -> by { it.download.status.ordinal }
            SortKey.PRIORITY -> by { it.download.priority }
            SortKey.SPEED -> by { it.speed }
            SortKey.ETA -> by { etaSeconds(it) }
            SortKey.PROTOCOL -> by { it.download.kind.protocol.label }
        }
    // Equal values keep a fixed order, whichever way the list runs: by name, then by number.
    return primary.thenComparing(compareBy<DownloadSnapshot> { name(it) }.thenBy { it.download.id.value })
}

/**
 * One network's part of a download. [percent] is a whole number and the parts of a [ShareView] sum to 100; a part that is
 * really under 1% ([tiny]) may still carry a point of that sum, but is shown as `<1%` ([percentText]).
 */
data class SharePart(
    val key: String,
    val name: String,
    val bytes: Long,
    val percent: Int,
    val tiny: Boolean = false,
) {
    val percentText: String get() = if (tiny) "<1%" else "$percent%"
}

/** The bytes a download received on each network, largest first. Only networks that carried bytes are listed. */
data class ShareView(
    val parts: List<SharePart> = emptyList(),
) {
    /** The card shows a share only when more than one network carried bytes. */
    val multiple: Boolean get() = parts.size >= 2

    /** `Wi-Fi 70% · USB 30%`. */
    val label: String get() = parts.joinToString(" · ") { "${it.name} ${it.percentText}" }
}

/** The key the engine gives traffic it could not place on any network. */
private const val UNKNOWN_NETWORK_KEY = "unknown"

/**
 * [share] (network key to bytes) as a [ShareView]; [names] says what each key is called, and an unknown key is shown as it is
 * (the key `unknown` as "Unknown network"). Two networks with one name get their keys appended. Percentages use the largest
 * remainder, so they sum to 100 and none is rounded past it.
 */
internal fun shareView(
    share: Map<String, Long>,
    names: Map<String, String>,
): ShareView {
    val counted = share.filterValues { it > 0 }
    val total = counted.values.sum()
    if (total <= 0) return ShareView()
    val sorted = counted.entries.sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
    // Each part's exact share is bytes * 100 / total; the leftover points go to the largest fractions.
    val floors = sorted.map { (it.value * 100 / total).toInt() }
    val leftover = 100 - floors.sum()
    val extra =
        sorted.indices
            .sortedWith(compareByDescending<Int> { sorted[it].value * 100 % total }.thenBy { it })
            .take(leftover)
            .toSet()
    val shown =
        sorted.map { e ->
            names[e.key] ?: if (e.key == UNKNOWN_NETWORK_KEY) "Unknown network" else e.key
        }
    val repeated =
        shown
            .groupingBy { it }
            .eachCount()
            .filterValues { it > 1 }
            .keys
    return ShareView(
        sorted.mapIndexed { i, e ->
            val name = if (shown[i] in repeated) "${shown[i]} (${e.key})" else shown[i]
            SharePart(e.key, name, e.value, floors[i] + if (i in extra) 1 else 0, tiny = e.value * 100 < total)
        },
    )
}
