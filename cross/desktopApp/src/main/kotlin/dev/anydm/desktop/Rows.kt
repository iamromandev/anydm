package dev.anydm.desktop

import dev.anydm.engine.DownloadSnapshot
import dev.anydm.engine.core.DownloadId

enum class SortKey(
    val label: String,
) {
    NAME("Name"),
    SIZE("Size"),
    PROGRESS("Progress"),
    STATUS("Status"),
    PRIORITY("Priority"),
}

/** How the list is shown: [sort] null keeps the engine's order. */
data class ListOptions(
    val sort: SortKey? = null,
    val descending: Boolean = false,
    val query: String = "",
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

    fun matches(s: DownloadSnapshot): Boolean = filter.accepts(s) && (query.isEmpty() || matchesQuery(s, query))

    val top =
        snapshots.filter { s ->
            val parent = s.download.parentId
            (parent == null || parent !in byId) && (matches(s) || children[s.download.id].orEmpty().any(::matches))
        }
    val sorted = options.sort?.let { key -> top.sortedWith(comparator(key, options.descending)) } ?: top
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
): Comparator<DownloadSnapshot> {
    fun <T : Comparable<T>> by(value: (DownloadSnapshot) -> T?): Comparator<DownloadSnapshot> {
        val order: Comparator<T> = if (descending) reverseOrder() else naturalOrder()
        return compareBy(nullsLast(order), value)
    }
    return when (key) {
        SortKey.NAME -> by { displayName(it).lowercase() }
        SortKey.SIZE -> by { it.total ?: it.download.size }
        SortKey.PROGRESS -> by { progress(it) }
        SortKey.STATUS -> by { it.download.status.ordinal }
        SortKey.PRIORITY -> by { it.download.priority }
    }
}
