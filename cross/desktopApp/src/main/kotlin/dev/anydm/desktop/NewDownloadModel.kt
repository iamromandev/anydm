package dev.anydm.desktop

import java.net.URLDecoder

private const val MIB = 1024L * 1024
private const val SAMPLE_SPEED = 2 * MIB
private const val SINTEL = "Sintel"

data class SampleFile(
    val name: String,
    val bytes: Long,
)

/** Fixed sample tree shown for every magnet link; no torrent metadata is fetched. */
val sintelFiles: List<SampleFile> =
    listOf(SampleFile("Sintel.mp4", 129_280_000L)) +
        listOf("en", "de", "es", "fr", "it", "nl", "pl", "pt", "ru", "tr")
            .mapIndexed { i, lang -> SampleFile("Sintel.$lang.srt", 1536L + i * 2) }

sealed interface Preview {
    val name: String
    val badge: String
    val parallel: Boolean

    data class Direct(
        override val name: String,
        override val badge: String,
        val totalBytes: Long?,
        val resumable: Boolean,
    ) : Preview {
        override val parallel: Boolean get() = resumable
    }

    data class Torrent(
        override val name: String,
        val files: List<SampleFile>,
    ) : Preview {
        override val badge: String get() = "DIR"
        override val parallel: Boolean get() = false
    }
}

private val linkPattern =
    Regex("^https?://([^/?#\\s@]*@)?([^/?#\\s:]+)(:\\d+)?(/[^?#\\s]*)?(\\?[^#\\s]*)?(#\\S*)?$", RegexOption.IGNORE_CASE)
private val hostPattern = Regex("^[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?$")

/** Percent-decodes; falls back to [raw] on bad escapes, invalid UTF-8 or a blank result. */
private fun decode(
    raw: String,
    plusIsSpace: Boolean = false,
): String =
    try {
        val out = URLDecoder.decode(if (plusIsSpace) raw else raw.replace("+", "%2B"), Charsets.UTF_8)
        if (out.isBlank() || ('\uFFFD' in out && '\uFFFD' !in raw)) raw else out
    } catch (_: IllegalArgumentException) {
        raw
    }

/** The link to prefill when Home should handle the paste shortcut, or null when it must not. */
fun pasteLink(
    clipboard: String?,
    dialogOpen: Boolean,
): String? {
    if (dialogOpen) return null
    val t = clipboard?.trim().orEmpty()
    return t.takeIf { listOf("http://", "https://", "magnet:").any { p -> t.startsWith(p, ignoreCase = true) } }
}

/** Derives a preview from the field text. Stand-in rules: localhost links count as range-capable. */
fun parsePreview(raw: String): Preview? {
    val text = raw.trim()
    if (text.startsWith("magnet:?", ignoreCase = true)) return magnetPreview(text)
    val m = linkPattern.matchEntire(text) ?: return null
    val host = m.groupValues[2]
    if (!hostPattern.matches(host)) return null
    val port = m.groupValues[3].drop(1)
    if (port.isNotEmpty() && (port.toLongOrNull() ?: Long.MAX_VALUE) > 65535) return null
    val segment = m.groupValues[4].split('/').lastOrNull { it.isNotEmpty() }
    val name = segment?.let(::decode) ?: host
    val resumable = host == "127.0.0.1" || host.equals("localhost", ignoreCase = true)
    return Preview.Direct(
        name = name,
        badge = if (segment == null) "FILE" else badgeFor(name),
        totalBytes = if (resumable) 400 * MIB else null,
        resumable = resumable,
    )
}

private fun magnetPreview(text: String): Preview? {
    val params = text.substringAfter('?').split('&').map { it.substringBefore('=') to it.substringAfter('=', "") }
    if (params.none { (k, v) -> k.equals("xt", true) && v.startsWith("urn:btih:", true) }) return null
    val dn =
        params
            .firstOrNull { it.first.equals("dn", true) }
            ?.second
            ?.let { decode(it, plusIsSpace = true) }
            ?.takeIf { it.isNotBlank() }
    return Preview.Torrent(dn ?: SINTEL, sintelFiles)
}

fun torrentSummary(
    files: List<SampleFile>,
    ticked: Set<String>,
): Pair<Int, Long> {
    val on = files.filter { it.name in ticked }
    return on.size to on.sumOf { it.bytes }
}

fun previewDetail(
    preview: Preview,
    ticked: Set<String>,
): String =
    when (preview) {
        is Preview.Direct -> {
            if (preview.resumable) {
                "${formatBytes(preview.totalBytes ?: 0)} · resumable"
            } else {
                "size unknown · Uses one connection. This server doesn't support parallel downloads."
            }
        }

        is Preview.Torrent -> {
            val (n, bytes) = torrentSummary(preview.files, ticked)
            "$n ${if (n == 1) "file" else "files"} · ${formatBytes(bytes)}"
        }
    }

fun newDownloadItem(
    preview: Preview,
    ticked: Set<String>,
    id: String,
): DownloadItem {
    val total =
        when (preview) {
            is Preview.Direct -> preview.totalBytes ?: 0L
            is Preview.Torrent -> torrentSummary(preview.files, ticked).second
        }
    return DownloadItem(id, preview.name, total, 0, SAMPLE_SPEED, DownloadStatus.Downloading)
}

fun nextId(items: List<DownloadItem>): String = ((items.maxOfOrNull { it.id.toLongOrNull() ?: 0L } ?: 0L) + 1).toString()
