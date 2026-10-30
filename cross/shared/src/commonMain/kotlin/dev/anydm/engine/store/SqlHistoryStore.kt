package dev.anydm.engine.store

import dev.anydm.engine.store.db.AnydmDb
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import dev.anydm.engine.store.db.History as HistoryRow

internal class SqlHistoryStore(
    database: AnydmDb,
    private val io: CoroutineDispatcher,
) : HistoryStore {
    private val queries = database.historyQueries

    // A write that was asked for is made, even if the caller is cancelled meanwhile (as for the other stores).
    private val writeContext = io + NonCancellable

    override suspend fun add(entry: HistoryEntry) {
        withContext(writeContext) {
            queries.insert(entry.downloadId, entry.name, entry.path, entry.size, entry.finishedAt, entry.sourceUrl)
        }
    }

    override suspend fun list(limit: Int): List<HistoryEntry> =
        withContext(io) { queries.selectNewest(limit.toLong()).executeAsList().map { it.toEntry() } }

    override suspend fun prune(keep: Int) {
        withContext(writeContext) { queries.prune(keep.toLong()) }
    }

    override suspend fun clear() {
        withContext(writeContext) { queries.clear() }
    }

    override suspend fun findByUrl(cleanedUrl: String): HistoryEntry? =
        withContext(io) { queries.selectByUrl(cleanedUrl).executeAsOneOrNull()?.toEntry() }

    override suspend fun findByFile(
        name: String,
        size: Long,
    ): HistoryEntry? = withContext(io) { queries.selectByFile(name, size).executeAsOneOrNull()?.toEntry() }

    private fun HistoryRow.toEntry() = HistoryEntry(download_id, name, path, size, finished_at, source_url)
}
