package dev.anydm.engine.core

private val RANGE_SHAPE = Regex("^[0-9:,]{1,200}$")
private const val MAX_LIMIT = 5000

/**
 * Which items of a playlist to take (MED-16): [range] in yt-dlp's item syntax (`1:5,8,10:`, 1-based), then at most [limit]
 * of them from the start. Null means everything.
 */
data class ItemSelection(
    val range: String? = null,
    val limit: Int? = null,
) {
    init {
        require(range == null || RANGE_SHAPE.matches(range)) { "range must be item numbers and ranges like 1:5,8,10:: $range" }
        require(limit == null || limit in 1..MAX_LIMIT) { "limit must be 1 to $MAX_LIMIT: $limit" }
    }
}
