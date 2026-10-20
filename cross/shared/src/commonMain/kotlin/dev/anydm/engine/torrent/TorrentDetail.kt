package dev.anydm.engine.torrent

/**
 * What a front end shows of a torrent that is in the session (TOR-07): connected [peers] and [seeds], libtorrent's
 * [availability] (distributed copies), payload bytes per second each way, all-time payload bytes [uploaded], [ratio]
 * (uploaded over the bytes wanted; null when nothing is wanted) and the seconds since it [finishedSeconds] (0 while it
 * downloads).
 */
data class TorrentSummary(
    val peers: Int,
    val seeds: Int,
    val availability: Double,
    val downloadRate: Long,
    val uploadRate: Long,
    val uploaded: Long,
    val ratio: Double?,
    val finishedSeconds: Long,
)

/** What a backend reads from a torrent for a [TorrentSummary]: [fileDone] is the bytes done of each file, in torrent order. */
class TorrentLiveStats(
    val peers: Int,
    val seeds: Int,
    val availability: Double,
    val downloadRate: Long,
    val uploadRate: Long,
    val fileDone: List<Long>,
) {
    companion object {
        /** A torrent that is not in the session. */
        val None = TorrentLiveStats(0, 0, 0.0, 0, 0, emptyList())
    }
}

/** One connected peer. A peer is a seed when it has every piece. */
class TorrentPeer(
    val address: String,
    val client: String,
    /** 0.0 to 1.0. */
    val progress: Double,
    val downloadRate: Long,
    val uploadRate: Long,
    val downloaded: Long,
    val uploaded: Long,
    val seed: Boolean,
)

/** One tracker of the torrent; [current] is the one it last talked to. libtorrent4j reports no per-tracker status or message. */
class TorrentTracker(
    val url: String,
    val tier: Int,
    val verified: Boolean,
    /** Some endpoint of the tracker is enabled. */
    val enabled: Boolean,
    val current: Boolean,
)

/** Which pieces the torrent has: bit `i % 8` of byte `i / 8` of [have] is piece `i`. */
class PieceMap(
    val count: Int,
    val have: ByteArray,
) {
    fun has(piece: Int): Boolean = piece in 0 until count && (have[piece / 8].toInt() shr (piece % 8)) and 1 == 1

    val haveCount: Int get() = (0 until count).count { has(it) }

    companion object {
        val Empty = PieceMap(0, ByteArray(0))
    }
}

/** The heavy lists, read on demand for the torrent being looked at: the fastest [peers] (at most [MAX_DETAIL_PEERS]), its [trackers] and its [pieces]. */
class TorrentDetail(
    val peers: List<TorrentPeer>,
    val trackers: List<TorrentTracker>,
    val pieces: PieceMap,
) {
    companion object {
        /** A torrent that is not in the session. */
        val Empty = TorrentDetail(emptyList(), emptyList(), PieceMap.Empty)
    }
}

/** The most peers a [TorrentDetail] lists. */
const val MAX_DETAIL_PEERS = 500
