package dev.anydm.engine

import dev.anydm.engine.torrent.TorrentDetail

/** What [Engine.torrentDetail] found: the peers, trackers and piece map of a torrent that is in the session (TOR-07). */
sealed interface TorrentDetailResult {
    /** The engine has no torrent service. */
    data object Unavailable : TorrentDetailResult

    /** The job's torrent is not in the session (paused, finished without a seed, not started): its summary and stored progress are all there is. */
    data object NotInSession : TorrentDetailResult

    data class Detail(
        val detail: TorrentDetail,
    ) : TorrentDetailResult
}
