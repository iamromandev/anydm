package dev.anydm.engine.store

import dev.anydm.engine.core.DownloadId
import kotlin.time.Instant

/** What yt-dlp said about a media page, kept so a restart doesn't ask again (MED-01): its `-J` output and when it ran. */
data class Extraction(
    val downloadId: DownloadId,
    val url: String,
    val json: String,
    val extractedAt: Instant,
    /** The format ids chosen from this page; a later extraction keeps them while they are still offered. */
    val chosen: List<String> = emptyList(),
)

/** At most one [Extraction] per download, gone with it. */
interface ExtractionStore {
    suspend fun get(id: DownloadId): Extraction?

    /** Replaces the download's extraction. False if the download is gone. */
    suspend fun put(extraction: Extraction): Boolean

    suspend fun delete(id: DownloadId)
}
