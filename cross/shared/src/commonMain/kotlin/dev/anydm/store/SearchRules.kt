package dev.anydm.store

import dev.anydm.model.FoundTorrent
import dev.anydm.model.IndexerError
import kotlin.math.max
import kotlin.math.round
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** What the Search view shows, decided without a UI (web: `present.ts`). */
enum class SearchMode { BROWSE, SEARCH }

/** Nothing typed browses the latest; 2 or more characters search; exactly 1 does nothing (`null`). */
fun modeFor(q: String): SearchMode? =
    when (q.trim().length) {
        0 -> SearchMode.BROWSE
        1 -> null
        else -> SearchMode.SEARCH
    }

fun canRun(
    q: String,
    busy: Boolean,
): Boolean = !busy && modeFor(q) != null

enum class SortKey { TITLE, SIZE, SEEDERS, LEECHERS, PUBLISHED, INDEXER }

data class SortState(
    val key: SortKey,
    val descending: Boolean,
)

fun defaultSort(mode: SearchMode): SortState =
    if (mode == SearchMode.BROWSE) SortState(SortKey.PUBLISHED, true) else SortState(SortKey.SEEDERS, true)

/** Numbers and dates read best biggest-or-newest first; words, A to Z. */
private fun startsDescending(key: SortKey) = key != SortKey.TITLE && key != SortKey.INDEXER

fun nextSort(
    current: SortState,
    key: SortKey,
): SortState = if (current.key == key) current.copy(descending = !current.descending) else SortState(key, startsDescending(key))

@OptIn(ExperimentalTime::class)
private fun millisOf(published: String?): Long? = published?.let { runCatching { Instant.parse(it).toEpochMilliseconds() }.getOrNull() }

private fun numberOf(
    result: FoundTorrent,
    key: SortKey,
): Long? =
    when (key) {
        SortKey.SIZE -> result.sizeBytes
        SortKey.SEEDERS -> result.seeders?.toLong()
        SortKey.LEECHERS -> result.leechers?.toLong()
        SortKey.PUBLISHED -> millisOf(result.published)
        else -> null
    }

private fun textOf(
    result: FoundTorrent,
    key: SortKey,
): String = if (key == SortKey.TITLE) result.title.lowercase() else (result.indexers.firstOrNull() ?: "").lowercase()

/** A sorted copy; an unknown value sorts last whichever way the column runs, and ties keep their order. */
fun sortFound(
    results: List<FoundTorrent>,
    sort: SortState,
): List<FoundTorrent> {
    val text = sort.key == SortKey.TITLE || sort.key == SortKey.INDEXER
    val (known, unknown) = results.partition { text || numberOf(it, sort.key) != null }
    val ordered =
        when {
            text && sort.descending -> known.sortedByDescending { textOf(it, sort.key) }
            text -> known.sortedBy { textOf(it, sort.key) }
            sort.descending -> known.sortedByDescending { numberOf(it, sort.key) }
            else -> known.sortedBy { numberOf(it, sort.key) }
        }
    return ordered + unknown
}

private val SIZE_UNITS = listOf("B", "KB", "MB", "GB", "TB")

fun formatSize(bytes: Long?): String {
    if (bytes == null) return "—"
    if (bytes == 0L) return "0 B"
    var size = bytes.toDouble()
    var unit = 0
    while (size >= 1024 && unit < SIZE_UNITS.lastIndex) {
        size /= 1024
        unit++
    }
    if (unit == 0) return "${size.toLong()} B"
    val tenths = round(size * 10).toLong()
    return "${tenths / 10}.${tenths % 10} ${SIZE_UNITS[unit]}"
}

private val AGE_STEPS =
    listOf(
        365L * 24 * 3600 to "year",
        30L * 24 * 3600 to "month",
        24L * 3600 to "day",
        3600L to "hour",
        60L to "minute",
    )

fun formatAge(
    published: String?,
    nowMillis: Long,
): String {
    val at = millisOf(published) ?: return "—"
    val seconds = max(0L, (nowMillis - at) / 1000)
    for ((size, name) in AGE_STEPS) {
        val n = seconds / size
        if (n >= 1) return "$n $name${if (n == 1L) "" else "s"}"
    }
    return "just now"
}

enum class SeederTone { GOOD, SOME, NONE }

fun seederTone(seeders: Int?): SeederTone =
    when {
        seeders != null && seeders >= 10 -> SeederTone.GOOD
        seeders != null && seeders >= 1 -> SeederTone.SOME
        else -> SeederTone.NONE
    }

fun indexerLabel(indexers: List<String>): String =
    when (indexers.size) {
        0 -> ""
        1 -> indexers[0]
        else -> "${indexers[0]} +${indexers.size - 1}"
    }

/** "N results from M indexers · 0.9 s", or "Latest · N releases …" when browsing. [askedOk] is those asked minus those that failed. */
fun statusLine(
    count: Int,
    askedOk: Int,
    tookMs: Long,
    mode: SearchMode,
): String {
    val noun = if (mode == SearchMode.BROWSE) "release" else "result"
    val results = "$count $noun${if (count == 1) "" else "s"}"
    val indexers = "$askedOk indexer${if (askedOk == 1) "" else "s"}"
    val tenths = (tookMs + 50) / 100
    val line = "$results from $indexers · ${tenths / 10}.${tenths % 10} s"
    return if (mode == SearchMode.BROWSE) "Latest · $line" else line
}

fun emptyText(
    mode: SearchMode,
    searched: String,
): String = if (mode == SearchMode.BROWSE) "Nothing recent from these indexers" else "No results for “$searched”"

fun errorChip(error: IndexerError): String = "${error.indexer}: ${error.message}"

data class Category(
    val id: String,
    val label: String,
)

val CATEGORIES =
    listOf(
        Category("all", "All"),
        Category("movies", "Movies"),
        Category("tv", "TV"),
        Category("music", "Music"),
        Category("software", "Software"),
        Category("books", "Books"),
        Category("other", "Other"),
    )
