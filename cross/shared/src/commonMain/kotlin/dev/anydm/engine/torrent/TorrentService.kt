package dev.anydm.engine.torrent

import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.protocol.StepResult
import kotlinx.coroutines.flow.Flow

/**
 * What the engine asks of the torrent protocol outside a transfer: a look inside a torrent before it is added (TOR-02),
 * and changes to one that is downloading now (TOR-02, TOR-03).
 */
interface TorrentService {
    /** A proxy setting changed (NET-15): the torrents in the session go through the proxy their jobs now ask for. */
    suspend fun reroute() = Unit

    /** The networks bonding uses changed (BND-04): the session sends from these interfaces only; none means its usual route. */
    suspend fun rebind(interfaces: List<String>) = Unit

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

    /** The seeds that ended, each once: by a rule, by [stopSeeding], or broken. Buffered, so none is lost before a collector comes. */
    val seedEnds: Flow<SeedEnd>

    /** Ends the seed of job [id] as [SeedEndReason.STOPPED]; false if it isn't seeding. */
    suspend fun stopSeeding(id: DownloadId): Boolean

    /** The latest summary and file progress of every torrent in the session, running or seeding, by job (TOR-07). A copy. */
    suspend fun live(): Map<DownloadId, TorrentLive>

    /** The peer list, trackers and piece map of job [id]'s torrent; null when it is not in the session (TOR-07). */
    suspend fun detail(id: DownloadId): TorrentDetail?

    /** What each seed moved since the last call; the first call after a (re)add counts from the add (TOR-09). */
    suspend fun seedTraffic(): List<SeedSample>
}
