package dev.anydm.engine.http

/** How many redirect wrappers, one inside another, [cleanUrl] takes off. */
private const val MAX_UNWRAPS = 5

/**
 * [url] with known redirect wrappers taken off (Google, Facebook, Instagram, YouTube, Outlook SafeLinks, Steam, VK,
 * Slack, Disqus; up to [MAX_UNWRAPS] deep), then its tracking parameters removed (HTTP-04): `utm_*`, `fbclid` and
 * `gclid` everywhere, `si` and `pp` only where they track (YouTube; `si` on Spotify), since a file server may use them.
 * Everything else stays exactly as written. Anything that isn't an http(s) URL comes back unchanged.
 */
fun cleanUrl(url: String): String {
    if (!isHttp(url)) return url
    var current = url
    repeat(MAX_UNWRAPS) { current = unwrapped(current) ?: return stripped(current) }
    return stripped(current)
}

private fun isHttp(url: String) = url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)

/** An http(s) URL cut at its first `?` and `#`: [head] is scheme, authority and path; [fragment] keeps its `#`. */
private class Parts(
    val head: String,
    val query: String?,
    val fragment: String,
) {
    private val afterScheme = head.substringAfter("://")
    private val authority = afterScheme.takeWhile { it != '/' }

    /** Lowercased, without user info, port or a trailing dot; an IPv6 host keeps its brackets. */
    val host: String =
        authority
            .substringAfterLast('@')
            .let { hostPort ->
                if (hostPort.startsWith('[')) hostPort.substringBefore(']') + "]" else hostPort.substringBeforeLast(':')
            }.removeSuffix(".")
            .lowercase()

    val path: String = afterScheme.drop(authority.length)

    val pairs: List<String> = query?.split('&').orEmpty()

    /** The value of the first pair named [name], as written; null without one. */
    fun raw(name: String): String? = pairs.firstOrNull { nameOf(it) == name && '=' in it }?.substringAfter('=')
}

private fun split(url: String): Parts {
    val hash = url.indexOf('#')
    val beforeFragment = if (hash < 0) url else url.substring(0, hash)
    val fragment = if (hash < 0) "" else url.substring(hash)
    val question = beforeFragment.indexOf('?')
    return if (question < 0) {
        Parts(beforeFragment, null, fragment)
    } else {
        Parts(beforeFragment.substring(0, question), beforeFragment.substring(question + 1), fragment)
    }
}

/** A pair's name, percent-decoded where it can be, lowercased. */
private fun nameOf(pair: String): String {
    val raw = pair.substringBefore('=')
    return (percentDecodedBytes(raw)?.decodeToString() ?: raw).lowercase()
}

private fun stripped(url: String): String {
    val parts = split(url)
    if (parts.query == null) return url
    val kept = parts.pairs.filterNot { isTracking(nameOf(it), parts.host) }
    if (kept.size == parts.pairs.size) return url
    return if (kept.isEmpty()) parts.head + parts.fragment else parts.head + "?" + kept.joinToString("&") + parts.fragment
}

private fun isTracking(
    name: String,
    host: String,
): Boolean =
    name.startsWith("utm_") ||
        name == "fbclid" ||
        name == "gclid" ||
        ((name == "si" || name == "pp") && isYouTube(host)) ||
        (name == "si" && host == "open.spotify.com")

private fun isYouTube(host: String) = host == "youtube.com" || host.endsWith(".youtube.com") || host == "youtu.be"

private val google = Regex("""(www\.)?google(\.[a-z]{2,3}){1,2}""")
private val disqusSuffix = Regex(""":[A-Za-z0-9]+$""")

/** The URL [url] wraps, if it is a known redirect link with an http(s) target; null otherwise. */
private fun unwrapped(url: String): String? {
    val parts = split(url)
    val host = parts.host
    val path = parts.path
    val names =
        when {
            google.matches(host) && path == "/url" -> listOf("q", "url")
            (host == "l.facebook.com" || host == "lm.facebook.com") && path == "/l.php" -> listOf("u")
            host == "l.instagram.com" && (path == "/" || path.isEmpty()) -> listOf("u")
            host in setOf("youtube.com", "www.youtube.com", "m.youtube.com") && path == "/redirect" -> listOf("q")
            host.endsWith(".safelinks.protection.outlook.com") -> listOf("url")
            host == "steamcommunity.com" && (path == "/linkfilter/" || path == "/linkfilter") -> listOf("url", "u")
            (host == "vk.com" || host == "m.vk.com") && path == "/away.php" -> listOf("to")
            host == "slack-redir.net" && path == "/link" -> listOf("url")
            host == "disq.us" && path == "/url" -> listOf("url")
            else -> return null
        }
    val raw = names.firstNotNullOfOrNull { parts.raw(it) } ?: return null
    val decoded = percentDecodedBytes(raw)?.decodeToString() ?: return null
    val target = if (host == "disq.us") decoded.replace(disqusSuffix, "") else decoded
    return target.takeIf(::isHttp)
}
