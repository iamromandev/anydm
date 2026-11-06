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

    /** Makes the torrent of job [id] check its files again; false if it isn't in the session. */
    suspend fun recheck(id: DownloadId): Boolean

    /** Asks the tracker at [url] of job [id]'s torrent for peers now, or every tracker when [url] is null; false if it isn't in the session. */
    suspend fun reannounce(
        id: DownloadId,
        url: String?,
    ): Boolean

    /**
     * Adds the announce trackers [urls] to job [id]'s torrent and keeps them for it, so it has them again after a restart.
     * The list is cleaned first (see [TorrentProtocol.addTrackers]) and the same list goes to the store and the torrent.
     * A tracker it already has is not added again. A private torrent is refused ([AddTrackersResult.PRIVATE]) and nothing is
     * kept: a running one by its own flag, a stopped one by the metadata stored with its row.
     *
     * The stored list holds at most 200 addresses. Past that a new address is still given to the running torrent (it applies
     * live) but is not kept, so it is gone after the next start.
     *
     * [AddTrackersResult.NOT_IN_SESSION] means the torrent isn't running or seeding right now. If the job has a torrent row
     * the URLs are still kept and applied at the next start, so a caller treats that as "saved", not as a failure to retry;
     * only a job with no torrent row keeps nothing. If the stored metadata cannot be read, a private torrent may get URLs
     * stored, but they are never applied: the backend skips them at start, and the start also clears them.
     */
    suspend fun addTrackers(
        id: DownloadId,
        urls: List<String>,
    ): AddTrackersResult

    /** The latest summary and file progress of every torrent in the session, running or seeding, by job (TOR-07). A copy. */
    suspend fun live(): Map<DownloadId, TorrentLive>

    /** The peer list, trackers and piece map of job [id]'s torrent; null when it is not in the session (TOR-07). */
    suspend fun detail(id: DownloadId): TorrentDetail?

    /**
     * Whether job [id]'s torrent is private: from the running torrent, or when it is not in the session from the metadata
     * stored for it. False when that is not known (no torrent row, unreadable metadata).
     */
    suspend fun isPrivate(id: DownloadId): Boolean

    /** What each seed moved since the last call; the first call after a (re)add counts from the add (TOR-09). */
    suspend fun seedTraffic(): List<SeedSample>
}

/** What [TorrentService.addTrackers] did. */
enum class AddTrackersResult {
    /** The torrent is in the session: the trackers were kept and given to it. */
    ADDED,

    /**
     * The torrent is not in the session: the trackers were kept for the next start if the job has a torrent row, else dropped.
     */
    NOT_IN_SESSION,

    /** The torrent is private (running, or by its stored metadata): its trackers are its own, so nothing was kept or added. */
    PRIVATE,
}
