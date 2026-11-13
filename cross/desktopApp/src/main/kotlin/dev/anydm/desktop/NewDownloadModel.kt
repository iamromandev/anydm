package dev.anydm.desktop

import dev.anydm.engine.http.ProbeResult
import dev.anydm.engine.torrent.TorrentMeta
import java.net.URLDecoder
import java.nio.file.Path

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
        /** The info hash (40 hex) of a `.torrent` that was read: such a torrent downloads for real. Null for a magnet, which is simulated. */
        val infoHash: String? = null,
        /** The `.torrent` file it was read from, read again when Download is pressed to make the app's own copy. */
        val source: Path? = null,
    ) : Preview {
        /** Every file of a real torrent is downloaded: its tree is shown ticked and cannot be changed (R3). */
        val locked: Boolean get() = infoHash != null

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

/** Whether a link can be fetched over several connections: the server takes ranges and the size is known. */
fun canSplit(preview: Preview.Direct): Boolean = preview.resumable && preview.totalBytes != null

fun connectionsText(n: Int): String = if (n == 1) "1 connection" else "$n connections"

fun previewDetail(
    preview: Preview,
    ticked: Set<String>,
    probe: ProbeState = ProbeState.Idle,
    /** The count chosen in the dialog, shown for a link that can be split. */
    connections: Int = 1,
): String =
    when (preview) {
        is Preview.Direct -> {
            val size = preview.totalBytes?.let(::formatBytes) ?: "size unknown"
            when {
                probe is ProbeState.Checking -> "Checking link…"
                probe is ProbeState.Failed -> probe.reason
                canSplit(preview) -> "$size · Resumable · ${connectionsText(connections)}"
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
    /** Where it was read from. */
    val path: Path? = null,
)

fun previewOf(t: LoadedTorrent): Preview.Torrent =
    Preview.Torrent(t.meta.name, t.meta.files.map { SampleFile(it.path, it.size) }, t.meta.isSingle, t.meta.infoHashHex, t.path)

/** Shown under the file tree of a real torrent, whose files cannot be picked yet. */
const val LOCKED_TREE_HINT = "Choosing files comes in a later version"

/** The refusal for a torrent that names no tracker: with no DHT yet, nothing would find it a peer. */
const val NO_TRACKERS_TEXT = "This torrent has no trackers. Trackerless torrents come in a later version."

/** Whether [meta] names at least one usable tracker (`readTorrent` keeps only http, https and udp ones). */
fun hasTrackers(meta: TorrentMeta): Boolean = meta.trackers.any { it.isNotEmpty() }

/** The files ticked when [preview] first shows: all of its own files, none for a direct link. */
fun initialTicked(preview: Preview?): Set<String> = (preview as? Preview.Torrent)?.files?.mapTo(HashSet()) { it.name } ?: emptySet()

fun newDownloadItem(
    preview: Preview,
    ticked: Set<String>,
    id: String,
    folder: String? = null,
    /** The count asked for; a row that cannot be split (no range support, unknown size, a torrent) keeps one. */
    connections: Int = 1,
): DownloadItem {
    // A real torrent is downloaded whole, whatever [ticked] says; a magnet's simulated tree still takes the ticks.
    val taken = if (preview is Preview.Torrent && preview.locked) preview.files.mapTo(HashSet()) { it.name } else ticked
    val total =
        when (preview) {
            is Preview.Direct -> preview.totalBytes ?: UNKNOWN_SIZE
            is Preview.Torrent -> torrentSummary(preview.files, taken).second
        }
    val kind = if (preview is Preview.Torrent) DownloadKind.Torrent else DownloadKind.Direct
    val files = (preview as? Preview.Torrent)?.files?.filter { it.name in taken }
    // A link and a `.torrent` are real downloads: they start at 0 B/s and the engines' reports move them. A magnet row stays simulated.
    val speed = if (preview is Preview.Torrent && !preview.locked) SAMPLE_SPEED else 0L
    val url = (preview as? Preview.Direct)?.url
    val count =
        if (preview is Preview.Direct &&
            canSplit(preview)
        ) {
            connections.coerceIn(MIN_CONNECTIONS, MAX_CONNECTIONS_PER_DOWNLOAD)
        } else {
            1
        }
    return DownloadItem(
        id,
        preview.name,
        total,
        0,
        speed,
        DownloadStatus.Downloading,
        kind,
        folder,
        files,
        url,
        connections = count,
        infoHash = (preview as? Preview.Torrent)?.infoHash,
        simulated = preview is Preview.Torrent && !preview.locked,
    )
}

fun nextId(items: List<DownloadItem>): String = ((items.maxOfOrNull { it.id.toLongOrNull() ?: 0L } ?: 0L) + 1).toString()

/**
 * Hands out row ids that never come round again within one run: one more than the largest id in the list, and more than any id given
 * before. A row that was removed while its job still winds down keeps its id to itself, so the job's late events cannot reach a new row.
 */
class IdIssuer {
    private var last = 0L

    fun next(items: List<DownloadItem>): String {
        val n = maxOf(items.maxOfOrNull { it.id.toLongOrNull() ?: 0L } ?: 0L, last) + 1
        last = n
        return n.toString()
    }
}
