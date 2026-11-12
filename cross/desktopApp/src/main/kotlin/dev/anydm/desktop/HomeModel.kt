package dev.anydm.desktop

enum class DownloadStatus { Downloading, Paused, Finished }

enum class DownloadKind { Direct, Torrent }

data class DownloadItem(
    val id: String,
    val name: String,
    val totalBytes: Long,
    val doneBytes: Long,
    val bytesPerSec: Long,
    val status: DownloadStatus,
    val kind: DownloadKind = DownloadKind.Direct,
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

fun badgeFor(name: String): String {
    val ext = name.substringAfterLast('.', "")
    val dot = name.lastIndexOf('.')
    if (dot <= 0 || ext.isEmpty()) return "FILE"
    return ext.uppercase().take(4)
}

fun badgeFor(item: DownloadItem): String = if (item.kind == DownloadKind.Torrent) "TOR" else badgeFor(item.name)

fun progress(item: DownloadItem): Float = if (item.totalBytes <= 0) 0f else (item.doneBytes.toFloat() / item.totalBytes).coerceIn(0f, 1f)

fun percent(item: DownloadItem): Int = if (item.totalBytes <= 0) 0 else (item.doneBytes * 100 / item.totalBytes).toInt().coerceIn(0, 100)
