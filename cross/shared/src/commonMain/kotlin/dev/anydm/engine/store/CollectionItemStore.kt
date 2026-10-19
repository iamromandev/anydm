package dev.anydm.engine.store

import dev.anydm.engine.core.DownloadId

/**
 * One video of a collection (MED-16, MED-17). [extractor] and [videoId] say which video it is, [childId] is the job that
 * downloads it (null once that job is removed), [playlistIndex] its place in the playlist, [title] and [duration] (seconds)
 * what the listing said, and [done] that its job finished. The rows are the archive a sync reads.
 */
data class CollectionItem(
    val collection: DownloadId,
    val extractor: String,
    val videoId: String,
    val childId: DownloadId?,
    val playlistIndex: Int,
    val title: String?,
    val duration: Int?,
    val done: Boolean,
)

interface CollectionItemStore {
    /** The collection's items by playlist index. */
    suspend fun items(collection: DownloadId): List<CollectionItem>

    /** The item [child] downloads, if it is one. */
    suspend fun byChild(child: DownloadId): CollectionItem?

    /** Marks the item [child] downloads as done and returns it; null if [child] is not an item's job. */
    suspend fun markDone(child: DownloadId): CollectionItem?

    /** Points the item at a new job (after its old one was removed). False if there is no such item. */
    suspend fun relink(
        collection: DownloadId,
        extractor: String,
        videoId: String,
        child: DownloadId,
    ): Boolean

    /** Removes the item, so the next sync fetches the video again. False if there is no such item. */
    suspend fun forget(
        collection: DownloadId,
        extractor: String,
        videoId: String,
    ): Boolean
}
