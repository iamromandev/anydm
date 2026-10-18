package dev.anydm.engine.store

import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.File
import dev.anydm.engine.core.FileId
import dev.anydm.engine.core.Format
import dev.anydm.engine.core.RequestContext
import dev.anydm.engine.core.Segment
import dev.anydm.engine.core.SegmentId

/**
 * The rows a run of the step runner keeps: its plan's files and segments and how far each is written (FND-03, FND-09).
 * [DownloadStore] is one.
 *
 * Every call is one transaction. A row whose parent is gone (a file of a removed download) throws: that is a bug in
 * the caller. Ids are assigned here and never reused, so a stale id finds nothing.
 *
 * Cancellation: a write that has been asked for is made, even if the caller is cancelled while it waits its turn, so
 * a pause or quit can save watermarks from a `finally` block (FND-09), which the step runner and the checkpointing of
 * #554 rely on. The cancelled caller may lose the return value, so a retried replace call makes new rows with new ids.
 */
interface PlanStore {
    /**
     * Replaces the download's files, and so their segments; indexes must differ. Returns them by index. Throws if the
     * download doesn't exist, even when [files] is empty.
     */
    suspend fun replaceFiles(
        id: DownloadId,
        files: List<NewFile>,
    ): List<File>

    suspend fun files(id: DownloadId): List<File>

    /** Records where the file's output was placed ([File.placedPath]); a file that isn't there is left alone. */
    suspend fun setPlacedPath(
        id: FileId,
        path: String,
    )

    /** What the download's requests carry besides the URL (HTTP-10), or null. */
    suspend fun context(id: DownloadId): RequestContext?

    /** What the user wants from a media page (MED-09), or null. */
    suspend fun format(id: DownloadId): Format?

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
}
