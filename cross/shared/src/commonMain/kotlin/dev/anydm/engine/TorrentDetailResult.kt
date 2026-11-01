package dev.anydm.engine

import dev.anydm.engine.torrent.SeedRule
import dev.anydm.engine.torrent.TorrentDetail

/** What [Engine.torrentDetail] found: the peers, trackers and piece map of a torrent that is in the session (TOR-07). */
sealed interface TorrentDetailResult {
    /** The engine has no torrent service. */
    data object Unavailable : TorrentDetailResult

    /** The job's torrent is not in the session (paused, finished without a seed, not started): its summary and stored progress are all there is. */
    data object NotInSession : TorrentDetailResult

    /**
     * [priorities] are the stored file priorities in torrent order (empty: every file is selected, none was chosen);
     * [seedRatio] and [seedMinutes] are the torrent's own seeding limits.
     */
    data class Detail(
        val detail: TorrentDetail,
        val priorities: List<Int> = emptyList(),
        val seedRatio: SeedRule<Double> = SeedRule.Inherit,
        val seedMinutes: SeedRule<Int> = SeedRule.Inherit,
    ) : TorrentDetailResult
}

/** What [Engine.recheckTorrent] and [Engine.reannounceTorrent] did. */
sealed interface TorrentActionResult {
    /** The engine has no torrent service. */
    data object Unavailable : TorrentActionResult

    /** The job's torrent is not in the session, so there was nothing to act on. */
    data object NotInSession : TorrentActionResult

    data object Done : TorrentActionResult
}
