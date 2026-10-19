package dev.anydm.engine.torrent

import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.protocol.StepResult

/** A [TorrentService] with the torrents a test gives it, and a record of what it was asked to change on the running ones. */
internal class FakeTorrentService : TorrentService {
    /** What [inspect] finds for each source. */
    val known = mutableMapOf<String, TorrentInfo>()
    val inspected = mutableListOf<String>()

    /** The jobs whose torrent is in the session. */
    val running = mutableSetOf<DownloadId>()
    val appliedPriorities = mutableMapOf<DownloadId, List<Int>>()
    val appliedSequential = mutableMapOf<DownloadId, Boolean>()

    override suspend fun inspect(source: String): StepResult<TorrentInfo> {
        inspected += source
        return known[source]?.let { StepResult.Ok(it) } ?: StepResult.Failed(null, "no torrent at $source")
    }

    override suspend fun applyPriorities(
        id: DownloadId,
        priorities: List<Int>,
    ): Boolean {
        if (id !in running) return false
        appliedPriorities[id] = priorities
        return true
    }

    override suspend fun applySequential(
        id: DownloadId,
        on: Boolean,
    ): Boolean {
        if (id !in running) return false
        appliedSequential[id] = on
        return true
    }
}
