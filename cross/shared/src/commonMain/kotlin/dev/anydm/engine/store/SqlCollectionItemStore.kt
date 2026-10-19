package dev.anydm.engine.store

import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.store.db.AnydmDb
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import dev.anydm.engine.store.db.Collection_item as ItemRow

internal class SqlCollectionItemStore(
    private val database: AnydmDb,
    private val io: CoroutineDispatcher,
) : CollectionItemStore {
    private val queries = database.collectionItemQueries

    // A write that was asked for is made, even if the caller is cancelled meanwhile (as for the other stores).
    private val writeContext = io + NonCancellable

    override suspend fun items(collection: DownloadId): List<CollectionItem> =
        withContext(io) { queries.selectByCollection(collection).executeAsList().map { it.toItem() } }

    override suspend fun byChild(child: DownloadId): CollectionItem? =
        withContext(io) { queries.selectByChild(child).executeAsOneOrNull()?.toItem() }

    override suspend fun markDone(child: DownloadId): CollectionItem? =
        withContext(writeContext) {
            database.transactionWithResult {
                queries.markDone(child)
                queries.selectByChild(child).executeAsOneOrNull()?.toItem()
            }
        }

    override suspend fun relink(
        collection: DownloadId,
        extractor: String,
        videoId: String,
        child: DownloadId,
    ): Boolean = withContext(writeContext) { queries.relink(child, collection, extractor, videoId).value > 0 }

    override suspend fun forget(
        collection: DownloadId,
        extractor: String,
        videoId: String,
    ): Boolean = withContext(writeContext) { queries.forget(collection, extractor, videoId).value > 0 }

    private fun ItemRow.toItem() = CollectionItem(collection_id, extractor, video_id, child_id, playlist_index, title, duration, done)
}
