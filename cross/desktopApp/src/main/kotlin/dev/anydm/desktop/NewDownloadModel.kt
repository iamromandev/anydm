package dev.anydm.desktop

import dev.anydm.engine.http.ProbeResult
import dev.anydm.engine.torrent.TorrentMeta
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
        /** The link as typed (trimmed): what the engine fetches. */
        val url: String? = null,
        /** True while the link is still being checked: the name shown is the link's, and the server may name the file differently. */
        val awaitingProbe: Boolean = false,
    ) : Preview {
        override val parallel: Boolean get() = resumable
    }

    data class Torrent(
        override val name: String,
        val files: List<SampleFile>,
        /** True for a single-file torrent; a folder torrent with one entry is still a folder. */
        val single: Boolean = false,
    ) : Preview {
        /** A single-file torrent shows its file's badge; anything else, even a folder with one entry, is a folder. */
        override val badge: String
            get() = if (single) files.singleOrNull()?.name?.let(::badgeFor) ?: "DIR" else "DIR"
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

/** Derives a preview from the field text: the name from the link, nothing else is known until the probe answers. */
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
    return Preview.Direct(
        name = name,
        badge = if (segment == null) "FILE" else badgeFor(name),
        totalBytes = null,
        resumable = false,
        url = text,
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

/** What the link probe of the New download dialog knows. */
sealed interface ProbeState {
    /** Nothing to probe (a magnet link, a torrent file or no link). */
    data object Idle : ProbeState

    data object Checking : ProbeState

    data class Ready(
        val result: ProbeResult,
    ) : ProbeState

    data class Failed(
        val reason: String,
    ) : ProbeState
}

/** The preview with what the probe found: the server's name, size and range support. */
fun probedPreview(
    base: Preview.Direct,
    state: ProbeState,
): Preview.Direct =
    when (state) {
        is ProbeState.Ready -> {
            base.copy(
                name = state.result.name,
                badge = badgeFor(state.result.name),
                totalBytes = state.result.total,
                resumable = state.result.resumable,
            )
        }

        ProbeState.Checking -> {
            base.copy(awaitingProbe = true)
        }

        else -> {
            base
        }
    }

fun previewDetail(
    preview: Preview,
    ticked: Set<String>,
    probe: ProbeState = ProbeState.Idle,
): String =
    when (preview) {
        is Preview.Direct -> {
            val size = preview.totalBytes?.let(::formatBytes) ?: "size unknown"
            when {
                probe is ProbeState.Checking -> "Checking link…"
                probe is ProbeState.Failed -> probe.reason
                preview.resumable -> "$size · Resumable"
                else -> "$size · Uses one connection. This server doesn't support parallel downloads."
            }
        }

        is Preview.Torrent -> {
            val (n, bytes) = torrentSummary(preview.files, ticked)
            "$n ${if (n == 1) "file" else "files"} · ${formatBytes(bytes)}"
        }
    }

/** A `.torrent` that was read: its file name (shown in the dialog) and what it holds. */
data class LoadedTorrent(
    val fileName: String,
    val meta: TorrentMeta,
)

fun previewOf(t: LoadedTorrent): Preview.Torrent =
    Preview.Torrent(t.meta.name, t.meta.files.map { SampleFile(it.path, it.size) }, t.meta.isSingle)

/** The files ticked when [preview] first shows: all of its own files, none for a direct link. */
fun initialTicked(preview: Preview?): Set<String> = (preview as? Preview.Torrent)?.files?.mapTo(HashSet()) { it.name } ?: emptySet()

fun newDownloadItem(
    preview: Preview,
    ticked: Set<String>,
    id: String,
    folder: String? = null,
): DownloadItem {
    val total =
        when (preview) {
            is Preview.Direct -> preview.totalBytes ?: UNKNOWN_SIZE
            is Preview.Torrent -> torrentSummary(preview.files, ticked).second
        }
    val kind = if (preview is Preview.Torrent) DownloadKind.Torrent else DownloadKind.Direct
    val files = (preview as? Preview.Torrent)?.files?.filter { it.name in ticked }
    // A direct link is a real download: it starts at 0 B/s and the engine's reports move it. A torrent row stays simulated.
    val speed = if (preview is Preview.Torrent) SAMPLE_SPEED else 0L
    val url = (preview as? Preview.Direct)?.url
    return DownloadItem(id, preview.name, total, 0, speed, DownloadStatus.Downloading, kind, folder, files, url)
}

fun nextId(items: List<DownloadItem>): String = ((items.maxOfOrNull { it.id.toLongOrNull() ?: 0L } ?: 0L) + 1).toString()
