package dev.anydm.engine.store

import dev.anydm.engine.core.Attempt
import dev.anydm.engine.core.Download
import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadStatus
import dev.anydm.engine.core.Mirror
import dev.anydm.engine.core.RequestContext
import kotlin.time.Instant

/**
 * Downloads and the rows that belong to them, including the plan rows of [PlanStore], whose rules apply to every call
 * here: ids are assigned here and never reused, every call is one transaction, a row whose parent is gone throws, and
 * a write that has been asked for is made even if the caller is cancelled (so watermarks can be saved from a `finally`
 * block, FND-09). Times are kept to the millisecond. The cancelled caller may lose the return value, so a retry after a
 * cancelled [add] creates a second download: add is not idempotent.
 */
interface DownloadStore : PlanStore {
    /**
     * Adds a PENDING download with no attempts, and its mirrors, together. [queuedAt] is when it starts waiting for a
     * slot; only the queue sets it. Not idempotent.
     */
    suspend fun add(
        new: NewDownload,
        queuedAt: Instant? = null,
    ): Download

    suspend fun get(id: DownloadId): Download?

    /** Every download, in the order added. */
    suspend fun list(): List<Download>

    /** Downloads in [status], highest priority first, then longest waiting (queuedAt, unknown first), then in the order added (FND-04). */
    suspend fun list(status: DownloadStatus): List<Download>

    /**
     * Saves every field but the id: a status from [Download.transitionTo], priority, limit or retry state. False if
     * removed. Last writer wins: the store does not check transitions, so the queue (#552) must serialize all status
     * writes.
     */
    suspend fun update(download: Download): Boolean

    /** The direct children of a collection, by their place in the playlist, then id (MED-16). */
    suspend fun children(parent: DownloadId): List<Download>

    /** Deletes the download with its mirrors, files, segments, attempts and child downloads. False if absent. */
    suspend fun remove(id: DownloadId): Boolean

    /** Replaces the download's request context; null removes it. False if the download is gone. */
    suspend fun setContext(
        id: DownloadId,
        context: RequestContext?,
    ): Boolean

    /** Position 0 first. */
    suspend fun mirrors(id: DownloadId): List<Mirror>

    /** Every mirror of every download, by download and position (the duplicate check, FIL-05). */
    suspend fun allMirrors(): List<Mirror>

    /** Saves the mirror's url and position (a relink, #552). False if removed. */
    suspend fun updateMirror(mirror: Mirror): Boolean

    /** Records a try that has started. */
    suspend fun addAttempt(new: NewAttempt): Attempt

    /** Saves every field but the id and download, usually how it ended. False if removed. */
    suspend fun updateAttempt(attempt: Attempt): Boolean

    /** Oldest first. */
    suspend fun attempts(id: DownloadId): List<Attempt>

    /** Every attempt of every download that has no end yet, oldest first: the tries a stopped app left behind. */
    suspend fun openAttempts(): List<Attempt>
}
