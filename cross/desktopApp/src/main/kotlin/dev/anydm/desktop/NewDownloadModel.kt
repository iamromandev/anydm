package dev.anydm.desktop

import dev.anydm.engine.http.ProbeResult
import dev.anydm.engine.torrent.MagnetLink
import dev.anydm.engine.torrent.MagnetResult
import dev.anydm.engine.torrent.TorrentMeta
import dev.anydm.engine.torrent.parseMagnet
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.file.Path

data class SampleFile(
    val name: String,
    val bytes: Long,
)

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
        /** The info hash (40 hex) of a `.torrent` that was read or of a magnet: such a torrent downloads for real. */
        val infoHash: String? = null,
        /** The `.torrent` file it was read from, read again when Download is pressed to make the app's own copy. Null for a magnet. */
        val source: Path? = null,
        /** The magnet link behind this preview: its metadata is not known yet, so it has no files and no size. */
        val magnet: MagnetLink? = null,
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
    if (isMagnetText(text)) return magnetPreview(text)
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

/** What the dialog says for a magnet with no usable info hash (R2). */
const val INVALID_MAGNET_TEXT = "Not a valid magnet link"

/** What the dialog says for a magnet with no tracker to announce to (R3). */
const val MAGNET_NO_TRACKERS_TEXT = "This magnet has no trackers"

/** The size and files line of a magnet before its metadata arrives (R5). */
const val MAGNET_UNKNOWN_TEXT = "Unknown until metadata arrives"

private fun isMagnetText(raw: String): Boolean = raw.trim().startsWith("magnet:", ignoreCase = true)

/** Why [raw] is a magnet the app cannot use, for the dialog; null when it is not a magnet or is a usable one. */
fun magnetProblem(raw: String): String? {
    if (!isMagnetText(raw)) return null
    return when (parseMagnet(raw)) {
        is MagnetResult.Ok -> null
        MagnetResult.Invalid -> INVALID_MAGNET_TEXT
        MagnetResult.NoTrackers -> MAGNET_NO_TRACKERS_TEXT
    }
}

/** The name from `dn` (cleaned by the parser) or, without one, the info hash in hex; the files and the size come with the metadata. */
private fun magnetPreview(text: String): Preview? {
    val link = (parseMagnet(text) as? MagnetResult.Ok)?.link ?: return null
    return Preview.Torrent(link.name ?: link.infoHashHex, emptyList(), infoHash = link.infoHashHex, magnet = link)
}

private fun encodeParam(s: String): String = URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")

/**
 * The link a magnet row keeps (and is resumed from): rebuilt from what the parser took, so the row never holds more than the
 * hash, the cleaned name and the usable trackers, however much the pasted text had.
 */
fun magnetUrl(link: MagnetLink): String =
    buildString {
        append("magnet:?xt=urn:btih:").append(link.infoHashHex)
        link.name?.let { append("&dn=").append(encodeParam(it)) }
        link.trackers.forEach { append("&tr=").append(encodeParam(it)) }
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
            if (preview.magnet != null) {
                MAGNET_UNKNOWN_TEXT
            } else {
                val (n, bytes) = torrentSummary(preview.files, ticked)
                "$n ${if (n == 1) "file" else "files"} · ${formatBytes(bytes)}"
            }
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
    // A torrent is downloaded whole, whatever [ticked] says (choosing files comes later).
    val taken = if (preview is Preview.Torrent) preview.files.mapTo(HashSet()) { it.name } else ticked
    val total =
        when (preview) {
            is Preview.Direct -> preview.totalBytes ?: UNKNOWN_SIZE

            // a magnet's size is not known until its metadata arrives
            is Preview.Torrent -> if (preview.magnet != null) UNKNOWN_SIZE else torrentSummary(preview.files, taken).second
        }
    val kind = if (preview is Preview.Torrent) DownloadKind.Torrent else DownloadKind.Direct
    // A magnet has no files yet: null until the metadata gives them (the row is waiting for it).
    val files = (preview as? Preview.Torrent)?.takeIf { it.magnet == null }?.files?.filter { it.name in taken }
    val url =
        when (preview) {
            is Preview.Direct -> preview.url
            is Preview.Torrent -> preview.magnet?.let(::magnetUrl)
        }
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
        0,
        DownloadStatus.Downloading,
        kind,
        folder,
        files,
        url,
        connections = count,
        infoHash = (preview as? Preview.Torrent)?.infoHash,
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
