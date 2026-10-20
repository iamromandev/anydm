package dev.anydm.engine.store

import dev.anydm.engine.core.DownloadId

private const val MAX_PRIORITY = 7

/** What a torrent job starts with: its metadata if known, a priority per file (empty: all at the default) and the order. */
class NewTorrent(
    val metadata: ByteArray? = null,
    val priorities: List<Int> = emptyList(),
    val sequential: Boolean = false,
) {
    init {
        require(priorities.all { it in 0..MAX_PRIORITY }) { "file priorities are 0 to $MAX_PRIORITY: $priorities" }
    }
}

/** A torrent job's stored options (TOR-02, TOR-03). [priorities]: one per file in torrent order, 0 skips it; empty means all. */
class TorrentRow(
    val downloadId: DownloadId,
    val metadata: ByteArray?,
    val priorities: List<Int>,
    val sequential: Boolean,
)

interface TorrentStore {
    suspend fun get(id: DownloadId): TorrentRow?

    /** False if the job has no torrent row. */
    suspend fun setMetadata(
        id: DownloadId,
        bytes: ByteArray,
    ): Boolean

    /** Each priority 0 to 7, or [IllegalArgumentException]. False if the job has no torrent row. */
    suspend fun setPriorities(
        id: DownloadId,
        priorities: List<Int>,
    ): Boolean

    suspend fun setSequential(
        id: DownloadId,
        on: Boolean,
    ): Boolean
}
