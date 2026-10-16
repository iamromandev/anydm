package dev.anydm.engine.store

import dev.anydm.engine.core.Attempt
import dev.anydm.engine.core.Download
import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadStatus
import dev.anydm.engine.core.File
import dev.anydm.engine.core.FileId
import dev.anydm.engine.core.Mirror
import dev.anydm.engine.core.Segment
import dev.anydm.engine.core.SegmentId

/**
 * Downloads and the rows that belong to them. Ids are assigned here and never reused, so a stale id finds nothing.
 * Times are kept to the millisecond. Every call is one transaction. A row whose parent is gone (a file of a removed
 * download) throws: that is a bug in the caller.
 *
 * Cancellation: a write that has been asked for is made, even if the caller is cancelled while it waits its turn, so
 * a pause or quit can save watermarks from a `finally` block (FND-09). The cancelled caller may lose the return
 * value, so a retry after a cancelled [add] creates a second download: add is not idempotent.
 */
interface DownloadStore {
    /** Adds a PENDING download with no attempts, and its mirrors, together. Not idempotent. */
    suspend fun add(new: NewDownload): Download

    suspend fun get(id: DownloadId): Download?

    /** Every download, in the order added. */
    suspend fun list(): List<Download>

    /** Downloads in [status], highest priority first, then in the order added (FND-04). */
    suspend fun list(status: DownloadStatus): List<Download>

    /**
     * Saves every field but the id: a status from [Download.transitionTo], priority, limit or retry state. False if
     * removed. Last writer wins: the store does not check transitions, so the queue (#552) must serialize all status
     * writes.
     */
    suspend fun update(download: Download): Boolean

    /** Deletes the download with its mirrors, files, segments, attempts and child downloads. False if absent. */
    suspend fun remove(id: DownloadId): Boolean

    /** Position 0 first. */
    suspend fun mirrors(id: DownloadId): List<Mirror>

    /**
     * Replaces the download's files, and so their segments; indexes must differ. Returns them by index. Throws if the
     * download doesn't exist, even when [files] is empty.
     */
    suspend fun replaceFiles(
        id: DownloadId,
        files: List<NewFile>,
    ): List<File>

    suspend fun files(id: DownloadId): List<File>

    /**
     * Replaces the file's segments, each written up to its start. Returns them by start. Throws if the file doesn't
     * exist, even when [segments] is empty.
     */
    suspend fun replaceSegments(
        fileId: FileId,
        segments: List<NewSegment>,
    ): List<Segment>

    suspend fun segments(fileId: FileId): List<Segment>

    /** Saves many watermarks at once (FND-09), all or none. Ids of removed segments are skipped. */
    suspend fun saveWatermarks(watermarks: Map<SegmentId, Long>)

    /** Records a try that has started. */
    suspend fun addAttempt(new: NewAttempt): Attempt

    /** Saves every field but the id and download, usually how it ended. False if removed. */
    suspend fun updateAttempt(attempt: Attempt): Boolean

    /** Oldest first. */
    suspend fun attempts(id: DownloadId): List<Attempt>
}
