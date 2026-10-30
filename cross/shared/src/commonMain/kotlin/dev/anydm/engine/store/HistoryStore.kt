package dev.anydm.engine.store

import dev.anydm.engine.core.DownloadId
import kotlin.time.Instant

/** A finished download, as the history keeps it (FIL-10). [sourceUrl] is the cleaned link of its first mirror. */
data class HistoryEntry(
    val downloadId: DownloadId,
    val name: String,
    val path: String,
    val size: Long?,
    val finishedAt: Instant,
    val sourceUrl: String,
)

/** The finished downloads, kept after the downloads themselves are removed. */
interface HistoryStore {
    suspend fun add(entry: HistoryEntry)

    /** The newest [limit] entries, newest first. */
    suspend fun list(limit: Int): List<HistoryEntry>

    /** Deletes all but the newest [keep] entries. */
    suspend fun prune(keep: Int)

    /** Forgets every entry; the downloads and their files are not touched. */
    suspend fun clear()

    /** The newest entry for this cleaned link. */
    suspend fun findByUrl(cleanedUrl: String): HistoryEntry?

    /** The newest entry with this name (any case) and size. */
    suspend fun findByFile(
        name: String,
        size: Long,
    ): HistoryEntry?
}
