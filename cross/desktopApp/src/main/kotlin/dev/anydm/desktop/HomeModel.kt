package dev.anydm.desktop

import dev.anydm.engine.http.Span

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
    /** A torrent's files (the ticked ones); null shows the Sintel sample. */
    val files: List<SampleFile>? = null,
    /** The http(s) link of a real download; null for torrent rows and samples, which never move. */
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

/** The files a torrent row shows: its own, or the Sintel sample for sample rows. */
fun DownloadItem.fileList(): List<SampleFile> = files ?: sintelFiles

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
