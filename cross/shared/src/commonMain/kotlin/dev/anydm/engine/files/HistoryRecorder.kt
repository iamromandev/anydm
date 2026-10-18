package dev.anydm.engine.files

import dev.anydm.engine.core.Download
import dev.anydm.engine.core.FinishHook
import dev.anydm.engine.http.cleanUrl
import dev.anydm.engine.store.DownloadStore
import dev.anydm.engine.store.HistoryEntry
import dev.anydm.engine.store.HistoryStore
import kotlinx.io.files.Path
import kotlin.time.Clock

/**
 * Writes a row to the history when a download finishes (FIL-10) and keeps the newest [limit] rows. A download with no
 * output file (a torrent that only seeded) is recorded at its save folder.
 */
class HistoryRecorder(
    private val history: HistoryStore,
    private val downloads: DownloadStore,
    private val clock: Clock,
    private val limit: suspend () -> Int,
) : FinishHook {
    override suspend fun finished(
        download: Download,
        outputs: List<Path>,
    ) {
        val first = outputs.firstOrNull()
        val link =
            downloads
                .mirrors(download.id)
                .firstOrNull()
                ?.url
                ?.let(::cleanUrl)
                .orEmpty()
        history.add(
            HistoryEntry(
                downloadId = download.id,
                name = download.fileName ?: first?.name ?: "download",
                path = first?.toString() ?: download.saveFolder.orEmpty(),
                size = download.size,
                finishedAt = clock.now(),
                sourceUrl = link,
            ),
        )
        history.prune(limit())
    }
}
