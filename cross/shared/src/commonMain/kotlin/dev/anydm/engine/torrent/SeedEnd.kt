package dev.anydm.engine.torrent

import dev.anydm.engine.core.DownloadId

/** Why a seed ended without a failure: a seeding rule was reached (or turned off), or the user stopped it. */
enum class SeedEndReason { RULE, STOPPED }

/** A seed that is over. The torrent has left the session and its files stay. */
sealed interface SeedEnd {
    val id: DownloadId

    data class Ended(
        override val id: DownloadId,
        val reason: SeedEndReason,
    ) : SeedEnd

    /** The seed broke: a file error, or the torrent is no longer in the session. */
    data class Failed(
        override val id: DownloadId,
        val message: String,
    ) : SeedEnd
}

/** What a seed sent and received since the last [TorrentService.seedTraffic] (TOR-09). */
class SeedSample(
    val id: DownloadId,
    val up: Long,
    val down: Long,
)
