package dev.anydm.engine.os

/** The longest label a paired browser gets. */
internal const val MAX_LABEL = 60

/**
 * What the app calls a browser it paired, from the `Origin` and `User-Agent` of its pairing request: "Firefox 131 (macOS)".
 * Both are the browser's to write, so the label is made of known words and a version number when the agent is one we know,
 * and otherwise of the agent's own text cleaned up by [cleanLabel].
 */
internal fun browserLabel(
    origin: String?,
    userAgent: String?,
): String {
    val agent = userAgent?.let(::tidy).orEmpty()
    if (agent.isNotEmpty()) {
        val browser = BROWSERS.firstNotNullOfOrNull { (name, pattern) -> pattern.find(agent)?.let { "$name ${it.groupValues[1]}" } }
        if (browser != null) {
            val system = SYSTEMS.firstOrNull { (_, pattern) -> pattern.containsMatchIn(agent) }?.first
            return if (system == null) browser else "$browser ($system)"
        }
        return agent.take(MAX_LABEL)
    }
    val scheme = origin?.substringBefore("://", "")?.lowercase()
    return when (scheme) {
        "chrome-extension" -> "Chrome extension"
        "moz-extension" -> "Firefox extension"
        else -> origin?.let(::cleanLabel)?.takeIf { it.isNotEmpty() } ?: "Browser"
    }
}

/**
 * [text] fit to show a person and to store: no control, format, separator or unassigned characters, one space between words,
 * at most [MAX_LABEL] characters.
 */
internal fun cleanLabel(text: String): String = tidy(text).take(MAX_LABEL)

// The same without the length limit, so a version number far into a long agent can still be found.
private fun tidy(text: String): String =
    text
        .map { if (it.isWhitespace()) ' ' else it }
        .filter { it.category !in DROPPED }
        .joinToString("")
        .split(' ')
        .filter { it.isNotEmpty() }
        .joinToString(" ")

private val DROPPED =
    setOf(
        CharCategory.CONTROL,
        CharCategory.FORMAT,
        CharCategory.SURROGATE,
        CharCategory.PRIVATE_USE,
        CharCategory.UNASSIGNED,
        CharCategory.LINE_SEPARATOR,
        CharCategory.PARAGRAPH_SEPARATOR,
    )

// The first that matches wins: Edge and Opera say "Chrome" too, and Chrome says "Safari".
private val BROWSERS =
    listOf(
        "Edge" to Regex("""\bEdg(?:e|A|iOS)?/(\d+)"""),
        "Opera" to Regex("""\bOPR/(\d+)"""),
        "Firefox" to Regex("""\bFirefox/(\d+)"""),
        "Chrome" to Regex("""\bChrome/(\d+)"""),
        "Safari" to Regex("""\bVersion/(\d+)[\d.]* (?:Mobile/\S+ )?Safari/"""),
    )

private val SYSTEMS =
    listOf(
        "Windows" to Regex("""\bWindows\b"""),
        "Android" to Regex("""\bAndroid\b"""),
        "iOS" to Regex("""\b(?:iPhone|iPad)\b"""),
        "ChromeOS" to Regex("""\bCrOS\b"""),
        "macOS" to Regex("""\b(?:Macintosh|Mac OS X)\b"""),
        "Linux" to Regex("""\b(?:Linux|X11)\b"""),
    )
