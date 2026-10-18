package dev.anydm.engine.store

import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.store.db.AnydmDb
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal class SqlExtractionStore(
    private val database: AnydmDb,
    private val io: CoroutineDispatcher,
) : ExtractionStore {
    private val queries = database.extractionQueries

    // A write that was asked for is made, even if the caller is cancelled meanwhile (as for the other stores).
    private val writeContext = io + NonCancellable

    override suspend fun get(id: DownloadId): Extraction? =
        withContext(io) {
            queries.selectByDownload(id).executeAsOneOrNull()?.let { Extraction(it.download_id, it.url, it.json, it.extracted_at) }
        }

    override suspend fun put(extraction: Extraction): Boolean =
        withContext(writeContext) {
            database.transactionWithResult {
                if (database.downloadQueries.selectById(extraction.downloadId).executeAsOneOrNull() == null) {
                    false
                } else {
                    queries.put(extraction.downloadId, extraction.url, extraction.json, extraction.extractedAt)
                    true
                }
            }
        }

    override suspend fun delete(id: DownloadId) {
        withContext(writeContext) { queries.delete(id) }
    }
}
