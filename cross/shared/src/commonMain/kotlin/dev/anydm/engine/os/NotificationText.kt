package dev.anydm.engine.os

/**
 * What may go on a notification's line (OSI-09): one line, no control characters, no web addresses (a login or a query
 * string in a link never reaches the screen), and not long.
 */
internal object NotificationText {
    fun clean(
        text: String,
        max: Int,
    ): String {
        val flat = text.map { if (unsafe(it)) ' ' else it }.joinToString("")
        val noLinks = flat.replace(ADDRESS, "a link").replace(Regex("\\s+"), " ").trim()
        return if (noLinks.length <= max) noLinks else noLinks.take(max - 1) + "…"
    }

    // Control characters, and the ones that reorder what is shown (a name read right to left).
    private fun unsafe(c: Char): Boolean =
        c < ' ' || c in '\u007f'..'\u009f' || c in '\u200e'..'\u200f' || c in '\u202a'..'\u202e' || c in '\u2066'..'\u2069'

    private val ADDRESS = Regex("[A-Za-z][A-Za-z0-9+.-]*://\\S+")
}
