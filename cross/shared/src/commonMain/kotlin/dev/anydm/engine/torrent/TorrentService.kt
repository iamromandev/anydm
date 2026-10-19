package dev.anydm.engine.torrent

import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.protocol.StepResult

/**
 * What the engine asks of the torrent protocol outside a transfer: a look inside a torrent before it is added (TOR-02),
 * and changes to one that is downloading now (TOR-02, TOR-03).
 */
interface TorrentService {
    /** The torrent's name, files and metadata, read from a magnet link, an address or a file, as a probe would. No job is made. */
    suspend fun inspect(source: String): StepResult<TorrentInfo>

    /** Gives the running torrent of job [id] new file priorities; false if it isn't running. */
    suspend fun applyPriorities(
        id: DownloadId,
        priorities: List<Int>,
    ): Boolean

    /** Switches the running torrent of job [id] to or from downloading in order; false if it isn't running. */
    suspend fun applySequential(
        id: DownloadId,
        on: Boolean,
    ): Boolean
}
