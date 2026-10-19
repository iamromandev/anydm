package dev.anydm.engine

import dev.anydm.engine.core.Download
import dev.anydm.engine.protocol.StepResult
import dev.anydm.engine.queue.CommandResult
import dev.anydm.engine.torrent.TorrentInfo

/** What [Engine.inspectTorrent] found (TOR-02). */
sealed interface InspectResult {
    data class Inspected(
        val info: TorrentInfo,
    ) : InspectResult

    /** The address isn't a torrent this engine reads, or the torrent couldn't be fetched or read. */
    data class Failed(
        val failure: StepResult.Failed,
    ) : InspectResult

    /** This engine has no torrent support. */
    data object Unavailable : InspectResult
}

/** What [Engine.addTorrent] made of a torrent (TOR-01, TOR-02). */
sealed interface AddTorrentResult {
    data class Added(
        val download: Download,
    ) : AddTorrentResult

    /** The address is not a magnet link, an http(s) address of a `.torrent` file, or a file:/// path. */
    data object BadSource : AddTorrentResult

    /** The choice names no file of the torrent. */
    data object EmptySelection : AddTorrentResult

    /** The torrent had to be read to choose files in it, and couldn't be. */
    data class Failed(
        val failure: StepResult.Failed,
    ) : AddTorrentResult

    /** The queue refused the job: the torrent is already in the list or the history. */
    data class Refused(
        val result: CommandResult,
    ) : AddTorrentResult

    /** A choice of files needs the torrent read, and this engine has no torrent support. */
    data object Unavailable : AddTorrentResult
}
