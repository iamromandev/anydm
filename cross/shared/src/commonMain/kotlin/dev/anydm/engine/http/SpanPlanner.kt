package dev.anydm.engine.http

/** The work a free connection takes: shrink span [index] to end at [newEnd] and fetch [taken]. */
data class Steal(
    val index: Int,
    val newEnd: Long,
    val taken: Span,
)

private const val SPLIT_ALIGN = 64L * 1024

/** At most [n] equal spans of at least [minSplit] bytes each, in file order; the last one takes the remainder. */
fun initialSpans(
    total: Long,
    n: Int,
    minSplit: Long = MIN_SPLIT,
): List<Span> {
    if (total <= 0) return emptyList()
    val count = minOf(n.toLong(), total / minSplit.coerceAtLeast(1)).coerceAtLeast(1)
    val size = total / count
    return List(count.toInt()) { i ->
        val start = i * size
        Span(start, if (i == count.toInt() - 1) total - 1 else start + size - 1, 0)
    }
}

/**
 * The work a free connection should take: the second half of the remaining bytes of the unfinished span with the most left,
 * if that half is at least [minSplit]; null when nothing can be split. The split point is rounded down to 64 KiB.
 */
fun steal(
    spans: List<Span>,
    minSplit: Long = MIN_SPLIT,
): Steal? {
    val index = spans.indices.filter { !spans[it].finished }.maxByOrNull { spans[it].end - spans[it].next } ?: return null
    val span = spans[index]
    val left = span.end - span.next + 1
    if (left / 2 < minSplit) return null
    val exact = span.next + left / 2
    val rounded = exact / SPLIT_ALIGN * SPLIT_ALIGN
    val at = if (rounded > span.next) rounded else exact
    return Steal(index, at - 1, Span(at, span.end, 0))
}

fun doneOf(spans: List<Span>): Long = spans.sumOf { it.done }

/** Whether [spans] are all finished and, side by side, are exactly bytes `0..total-1`: everything of a file of [total] bytes is written. */
fun finishedOver(
    spans: List<Span>,
    total: Long,
): Boolean {
    if (total <= 0 || spans.isEmpty()) return false
    var next = 0L
    for (s in spans.sortedBy { it.start }) {
        if (s.start != next || s.done != s.end - s.start + 1) return false
        next = s.end + 1
    }
    return next == total
}

/** Merges finished neighbours that touch, so a stored list stays short. Unfinished spans are never merged. */
fun compact(spans: List<Span>): List<Span> {
    val out = ArrayList<Span>(spans.size)
    for (s in spans) {
        val last = out.lastOrNull()
        if (last != null && last.finished && s.finished && last.end + 1 == s.start) {
            val merged = Span(last.start, s.end, s.end - last.start + 1)
            out[out.lastIndex] = merged
        } else {
            out.add(s)
        }
    }
    return out
}

/** A `Content-Range` of a 206: [total] is null for `*`. */
data class ContentRange(
    val start: Long,
    val end: Long,
    val total: Long?,
)

private val CONTENT_RANGE = Regex("""bytes\s+(\d+)-(\d+)/(\d+|\*)""")

fun parseContentRange(value: String): ContentRange? {
    val m = CONTENT_RANGE.find(value) ?: return null
    val (start, end, total) = m.destructured
    return ContentRange(start.toLong(), end.toLong(), total.toLongOrNull())
}
