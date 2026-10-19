package dev.anydm.engine.core

private val RANGE_SHAPE = Regex("^[0-9:,]{1,200}$")
private const val MAX_LIMIT = 5000
private const val MAX_DIGITS = 9

/**
 * Which items of a playlist to take (MED-16): [range] in yt-dlp's item syntax (`1:5,8,10:`, 1-based, inclusive), then at
 * most [limit] of them from the start. Null means everything. A range that is not valid text is refused when the value is
 * made; [parse] answers null instead.
 */
data class ItemSelection(
    val range: String? = null,
    val limit: Int? = null,
) {
    init {
        require(range == null || (RANGE_SHAPE.matches(range) && parts(range) != null)) {
            "range must be item numbers and ranges like 1:5,8,10:: $range"
        }
        require(limit == null || limit in 1..MAX_LIMIT) { "limit must be 1 to $MAX_LIMIT: $limit" }
    }

    /** The 1-based positions to take out of [total] items: ascending, unique, each within `1..total`. */
    fun indices(total: Int): List<Int> {
        if (total <= 0) return emptyList()
        val wanted = range?.let { parts(it) } ?: listOf(1..total)
        val picked = sortedSetOf<Int>()
        for (part in wanted) {
            for (n in part.first.coerceAtLeast(1)..part.last.coerceAtMost(total)) picked += n
        }
        return limit?.let { picked.take(it) } ?: picked.toList()
    }

    /**
     * How many entries of the listing this selection can need: the highest item a range names, or the limit when there
     * is no range; [cap] when either is open ended. Passed to yt-dlp so it stops listing there.
     */
    fun listingEnd(cap: Int): Int {
        val parts = range?.let { parts(it) }
        val byRange = parts?.maxOf { it.last }?.takeIf { it != Int.MAX_VALUE }
        val byLimit = if (range == null) limit else null
        return minOf(byRange ?: byLimit ?: cap, cap)
    }

    companion object {
        /** The selection, or null when [range] or [limit] are not valid. */
        fun parse(
            range: String?,
            limit: Int?,
        ): ItemSelection? = runCatching { ItemSelection(range, limit) }.getOrNull()
    }
}

/** The parts of a range text as inclusive ranges (an open end is [Int.MAX_VALUE]), or null if any part is not valid. */
private fun parts(range: String): List<IntRange>? =
    range.split(',').map { part ->
        val pieces = part.split(':')
        when (pieces.size) {
            1 -> {
                number(pieces[0])?.let { it..it }
            }

            2 -> {
                val low = if (pieces[0].isEmpty()) 1 else number(pieces[0])
                val high = if (pieces[1].isEmpty()) Int.MAX_VALUE else number(pieces[1])
                if (low == null || high == null || low > high) null else low..high
            }

            else -> {
                null
            }
        } ?: return null
    }

private fun number(text: String): Int? =
    if (text.isNotEmpty() &&
        text.length <= MAX_DIGITS
    ) {
        text.toIntOrNull()?.takeIf { it >= 1 }
    } else {
        null
    }
