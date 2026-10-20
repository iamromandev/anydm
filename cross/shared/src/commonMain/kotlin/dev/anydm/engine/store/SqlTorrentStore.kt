package dev.anydm.engine.store

import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.store.db.AnydmDb
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal class SqlTorrentStore(
    private val database: AnydmDb,
    private val io: CoroutineDispatcher,
) : TorrentStore {
    private val queries = database.torrentQueries

    // A write that was asked for is made, even if the caller is cancelled meanwhile (as for the other stores).
    private val writeContext = io + NonCancellable

    override suspend fun get(id: DownloadId): TorrentRow? =
        withContext(io) {
            queries.selectByDownload(id).executeAsOneOrNull()?.let {
                TorrentRow(it.download_id, it.metadata, decodePriorities(it.priorities), it.sequential)
            }
        }

    override suspend fun setMetadata(
        id: DownloadId,
        bytes: ByteArray,
    ): Boolean = withContext(writeContext) { queries.setMetadata(bytes, id).value > 0 }

    override suspend fun setPriorities(
        id: DownloadId,
        priorities: List<Int>,
    ): Boolean {
        require(priorities.all { it in 0..7 }) { "file priorities are 0 to 7: $priorities" }
        return withContext(writeContext) { queries.setPriorities(priorities.joinToString(","), id).value > 0 }
    }

    override suspend fun setSequential(
        id: DownloadId,
        on: Boolean,
    ): Boolean = withContext(writeContext) { queries.setSequential(on, id).value > 0 }
}

// '' is no choice; a value this version can't read counts as no choice too: a stored row must not stop the engine.
private fun decodePriorities(text: String): List<Int> =
    if (text.isEmpty()) emptyList() else text.split(',').map { it.toIntOrNull()?.takeIf { n -> n in 0..7 } ?: return emptyList() }
