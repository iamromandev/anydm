package dev.anydm.desktop

import dev.anydm.engine.http.Span
import dev.anydm.engine.torrent.PieceSet

enum class DownloadStatus { Downloading, Queued, Paused, Finished, Failed }

/** A total size that the server did not tell: no percentage and no ETA. */
const val UNKNOWN_SIZE = -1L

enum class DownloadKind { Direct, Torrent }

data class DownloadItem(
    val id: String,
    val name: String,
    val totalBytes: Long,
    val doneBytes: Long,
    val bytesPerSec: Long,
    val status: DownloadStatus,
    val kind: DownloadKind = DownloadKind.Direct,
    /** Absolute save folder; null means the default (`<home>/Downloads`). */
    val folder: String? = null,
    /** A torrent's files; null for a magnet row until its metadata arrives (and for demo rows, which show the Sintel sample). */
    val files: List<SampleFile>? = null,
    /** The http(s) link of a real download, or a magnet row's magnet link; null for a `.torrent` row and demo rows. */
    val url: String? = null,
    /** Why a Failed row failed. */
    val error: String? = null,
    val etag: String? = null,
    val lastModified: String? = null,
    /** How many connections may fetch the file at once, fixed when the row is added. */
    val connections: Int = 1,
    /** The ranges of a segmented download with what is written; null: not segmented (yet), or a version 1 row. */
    val spans: List<Span>? = null,
    /** The identity of the `.part` file the [spans] were written for; ranges without it are not trusted on a resume. */
    val partId: String? = null,
    /** Connections open right now; not saved. */
    val liveConnections: Int = 0,
    /** A real torrent's info hash (40 lowercase hex), also a magnet's; null for a link and a row from before torrents were real. */
    val infoHash: String? = null,
    /**
     * A real torrent's verified pieces: what the engine last said, written to disk and checked. Null until the torrent first
     * starts, and again once it is finished. A row that has it is a resume: the engine keeps the names its files have.
     */
    val pieces: PieceSet? = null,
    /** A real torrent's connected peers; not saved. */
    val peers: Int = 0,
    /** A real torrent's pieces with a block asked for that are not verified yet; not saved. */
    val inFlight: Set<Int> = emptySet(),
    /**
     * A magnet row made by an earlier build, which simulated magnets. Nothing makes one now: it is only read from `downloads.json`,
     * and then turned into a real magnet row or into one that cannot resume (see [restoredRows]).
     */
    val simulated: Boolean = false,
    /**
     * A torrent row from an earlier run with no info hash: it was made before torrents were real (or is a magnet saved before magnet rows were marked), has no `.torrent` copy
     * to run from, and says so ("Can't resume: add the torrent again") when resumed. Not saved: set when the rows are loaded.
     */
    val noCopy: Boolean = false,
)

data class HomeState(
    val downloads: List<DownloadItem>,
    val networks: List<Network>,
    val freeBytes: Long,
)

enum class Filter(
    val label: String,
) {
    All("All downloads"),
    Downloading("Downloading"),
    Finished("Finished"),
}

enum class SpeedUnit { MBps, Mbps }

fun filtered(
    state: HomeState,
    filter: Filter,
): List<DownloadItem> =
    when (filter) {
        Filter.All -> state.downloads
        Filter.Downloading -> state.downloads.filter { it.status != DownloadStatus.Finished }
        Filter.Finished -> state.downloads.filter { it.status == DownloadStatus.Finished }
    }

/** The files a torrent row shows: its own (none yet for a magnet row waiting for its metadata), or the Sintel sample for demo rows. */
fun DownloadItem.fileList(): List<SampleFile> = files ?: if (infoHash != null) emptyList() else sintelFiles

/**
 * A magnet row whose metadata has not arrived: a real torrent row made from a magnet link, with no files yet. The metadata gives it
 * its files (and a restart that finds the `.torrent` copy already written takes them from it), so "no files" is the same as "no copy".
 * A row that has pieces or is Finished is not waiting: its files are on disk, whatever its file list says.
 */
val DownloadItem.awaitingMetadata: Boolean
    get() = lacksFiles && pieces == null && status != DownloadStatus.Finished

/** A magnet row that was never told its files; [awaitingMetadata] adds that it has not run either. */
internal val DownloadItem.lacksFiles: Boolean
    get() = kind == DownloadKind.Torrent && infoHash != null && files == null && url?.startsWith("magnet:", ignoreCase = true) == true

fun fileCount(n: Int): String = "$n ${if (n == 1) "file" else "files"}"

fun badgeFor(name: String): String {
    val ext = name.substringAfterLast('.', "")
    val dot = name.lastIndexOf('.')
    if (dot <= 0 || ext.isEmpty()) return "FILE"
    return ext.uppercase().take(4)
}

fun badgeFor(item: DownloadItem): String = if (item.kind == DownloadKind.Torrent) "TOR" else badgeFor(item.name)

fun progress(item: DownloadItem): Float = if (item.totalBytes <= 0) 0f else (item.doneBytes.toFloat() / item.totalBytes).coerceIn(0f, 1f)

fun percent(item: DownloadItem): Int = if (item.totalBytes <= 0) 0 else (item.doneBytes * 100 / item.totalBytes).toInt().coerceIn(0, 100)
