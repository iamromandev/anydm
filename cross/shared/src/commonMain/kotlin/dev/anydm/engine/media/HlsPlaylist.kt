package dev.anydm.engine.media

/** What reading an HLS playlist gave (MED-03). */
sealed interface HlsParse {
    /** A finished media playlist: its fragments in order, the init segment (`EXT-X-MAP`) first when there is one. */
    data class Media(
        val fragments: List<FragmentRef>,
    ) : HlsParse

    /** A master playlist: it lists other playlists and holds no media. */
    data object Master : HlsParse

    /** Its fragments are encrypted with [method] (AES-128 and the like), which we don't decrypt. */
    data class Encrypted(
        val method: String,
    ) : HlsParse

    /** No `EXT-X-ENDLIST`: a live stream that is still growing. */
    data object Live : HlsParse

    data class Invalid(
        val reason: String,
    ) : HlsParse
}

private val ATTRIBUTE = Regex("""([A-Z0-9-]+)=("[^"]*"|[^,]*)""")

/** Reads HLS playlists; unknown tags and stray lines are ignored. */
object HlsPlaylist {
    fun parse(
        text: String,
        baseUrl: String,
    ): HlsParse {
        val lines = text.removePrefix(Char(0xFEFF).toString()).lines().map { it.trim() }
        if (lines.firstOrNull { it.isNotEmpty() } != "#EXTM3U") return HlsParse.Invalid("this isn't an HLS playlist")
        var master = false
        var endList = false
        var encrypted: String? = null
        var init: FragmentRef? = null
        var duration: Double? = null
        var range: Pair<Long, Long?>? = null
        val lastEnd = mutableMapOf<String, Long>()
        val fragments = mutableListOf<FragmentRef>()
        for (line in lines) {
            when {
                line.isEmpty() -> {
                    Unit
                }

                line.startsWith("#EXT-X-STREAM-INF") -> {
                    master = true
                }

                line.startsWith("#EXT-X-KEY:") -> {
                    val method = attributes(line)["METHOD"]
                    if (method != null && method != "NONE" && encrypted == null) encrypted = method
                }

                line.startsWith("#EXT-X-MAP:") -> {
                    val attrs = attributes(line)
                    val uri = attrs["URI"]
                    if (uri != null && init == null) {
                        val bytes = attrs["BYTERANGE"]?.let(::byteRange)
                        val url = resolveUrl(baseUrl, uri)
                        val start = bytes?.second ?: 0L.takeIf { bytes != null }
                        init = FragmentRef(url, null, start, bytes?.let { (start ?: 0L) + it.first })
                    }
                }

                line.startsWith("#EXTINF:") -> {
                    duration =
                        line
                            .substringAfter(':')
                            .substringBefore(',')
                            .trim()
                            .toDoubleOrNull()
                }

                line.startsWith("#EXT-X-BYTERANGE:") -> {
                    range = byteRange(line.substringAfter(':')) ?: return HlsParse.Invalid("a byte range of the playlist can't be read")
                }

                line == "#EXT-X-ENDLIST" -> {
                    endList = true
                }

                line.startsWith("#") -> {
                    Unit
                }

                else -> {
                    val url = resolveUrl(baseUrl, line)
                    val bytes = range
                    if (bytes != null) {
                        val start = bytes.second ?: lastEnd[url] ?: 0L
                        val end = start + bytes.first
                        lastEnd[url] = end
                        fragments += FragmentRef(url, duration, start, end)
                    } else {
                        fragments += FragmentRef(url, duration)
                    }
                    duration = null
                    range = null
                }
            }
        }
        return when {
            master -> HlsParse.Master
            encrypted != null -> HlsParse.Encrypted(encrypted)
            !endList -> HlsParse.Live
            fragments.isEmpty() -> HlsParse.Invalid("no fragments")
            else -> HlsParse.Media(listOfNotNull(init) + fragments)
        }
    }

    /** `KEY=value` and `KEY="value"` pairs after the tag's colon. */
    private fun attributes(line: String): Map<String, String> =
        ATTRIBUTE.findAll(line.substringAfter(':')).associate { it.groupValues[1] to it.groupValues[2].removeSurrounding("\"") }

    /** `n` or `n@o`: the length, and the offset when there is one. */
    private fun byteRange(text: String): Pair<Long, Long?>? {
        val length =
            text
                .substringBefore('@')
                .trim()
                .toLongOrNull()
                ?.takeIf { it > 0 } ?: return null
        val offset = text.substringAfter('@', "").trim().toLongOrNull()
        return if (offset != null && offset < 0) null else length to offset
    }
}
