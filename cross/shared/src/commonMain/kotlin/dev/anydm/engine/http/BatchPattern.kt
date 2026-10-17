package dev.anydm.engine.http

/** The most links one pattern may name. */
const val MAX_PATTERN_LINKS = 1000

/** What [expandPattern] made of a pattern: its links, or why it names none. */
sealed interface Expansion {
    data class Links(
        val urls: List<String>,
    ) : Expansion

    data class Invalid(
        val message: String,
    ) : Expansion
}

private val group = Regex("""\[(?:(\d+)-(\d+)|([a-z])-([a-z])|([A-Z])-([A-Z]))\]""")

/**
 * Every link [pattern] names, in order (HTTP-15). A group is two numbers (`[1-9]`, `[01-50]`) or two letters of one case
 * (`[a-z]`, `[A-F]`); a left number with a leading zero sets the padding (`[01-50]` gives `01`…`50`). Any other bracket
 * stays as written, so a link that really holds `[` or `]` isn't mangled. Several groups multiply, the last varying
 * fastest. A backwards range, or more than [limit] links, is [Expansion.Invalid]; the count is worked out before
 * anything is built.
 */
fun expandPattern(
    pattern: String,
    limit: Int = MAX_PATTERN_LINKS,
): Expansion {
    require(limit >= 1) { "limit must be >= 1: $limit" }
    val trimmed = pattern.trim()
    if (trimmed.isEmpty()) return Expansion.Invalid("the pattern is empty")
    val groups = group.findAll(trimmed).toList()
    // Backwards ranges first, so the message names the real fault rather than the size.
    for (match in groups) {
        val (left, right) = boundsOf(match)
        if (backwards(left, right)) return Expansion.Invalid("the range ${match.value} runs backwards")
    }
    var total = 1L
    for (match in groups) {
        val size = sizeOf(match) ?: return tooMany(limit)
        total = if (total > Long.MAX_VALUE / size) return tooMany(limit) else total * size
    }
    if (total > limit) return Expansion.Invalid("the pattern names $total links; the most is $limit")
    val literals = mutableListOf<String>()
    var cursor = 0
    for (match in groups) {
        literals += trimmed.substring(cursor, match.range.first)
        cursor = match.range.last + 1
    }
    literals += trimmed.substring(cursor)
    var links = listOf(literals.first())
    for ((i, match) in groups.withIndex()) {
        val values = valuesOf(match)
        links = links.flatMap { prefix -> values.map { prefix + it + literals[i + 1] } }
    }
    return Expansion.Links(links)
}

private fun tooMany(limit: Int) = Expansion.Invalid("the pattern names more than $limit links")

private fun boundsOf(match: MatchResult): Pair<String, String> {
    val g = match.groupValues
    return when {
        g[1].isNotEmpty() -> g[1] to g[2]
        g[3].isNotEmpty() -> g[3] to g[4]
        else -> g[5] to g[6]
    }
}

private fun isNumbers(match: MatchResult) = match.groupValues[1].isNotEmpty()

/** Numbers compare by value (a number too long for a Long is never smaller than its partner), letters by code. */
private fun backwards(
    left: String,
    right: String,
): Boolean {
    if (!left[0].isDigit()) return left > right
    val a = left.trimStart('0')
    val b = right.trimStart('0')
    return if (a.length != b.length) a.length > b.length else a > b
}

/** How many values a group has, without building them; null when it doesn't fit a Long. */
private fun sizeOf(match: MatchResult): Long? {
    val (left, right) = boundsOf(match)
    if (!isNumbers(match)) return (right[0] - left[0] + 1).toLong()
    val a = left.toLongOrNull() ?: return null
    val b = right.toLongOrNull() ?: return null
    return if (b - a == Long.MAX_VALUE) null else b - a + 1
}

private fun valuesOf(match: MatchResult): List<String> {
    val (left, right) = boundsOf(match)
    if (!isNumbers(match)) return (left[0]..right[0]).map { it.toString() }
    // A leading zero on the left number asks for padding to its width.
    val width = if (left.length > 1 && left.startsWith('0')) left.length else 0
    return (left.toLong()..right.toLong()).map { it.toString().padStart(width, '0') }
}
