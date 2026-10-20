package dev.anydm.engine.torrent

import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.protocol.StepResult
import kotlinx.coroutines.flow.MutableSharedFlow

/** A [TorrentService] with the torrents a test gives it, and a record of what it was asked to change on the running ones. */
internal class FakeTorrentService : TorrentService {
    /** What [inspect] finds for each source. */
    val known = mutableMapOf<String, TorrentInfo>()
    val inspected = mutableListOf<String>()

    /** The jobs whose torrent is in the session. */
    val running = mutableSetOf<DownloadId>()
    val appliedPriorities = mutableMapOf<DownloadId, List<Int>>()
    val appliedSequential = mutableMapOf<DownloadId, Boolean>()

    /** The jobs [stopSeeding] finds seeding, and the ones it stopped. */
    val seeding = mutableSetOf<DownloadId>()
    val stopped = mutableListOf<DownloadId>()

    /** A test ends a seed by emitting here; the engine under test collects it. */
    override val seedEnds = MutableSharedFlow<SeedEnd>(extraBufferCapacity = 16)

    /** What [seedTraffic] answers once, then nothing until a test sets more. */
    var traffic: List<SeedSample> = emptyList()

    override suspend fun stopSeeding(id: DownloadId): Boolean {
        if (!seeding.remove(id)) return false
        stopped += id
        seedEnds.emit(SeedEnd.Ended(id, SeedEndReason.STOPPED))
        return true
    }

    override suspend fun seedTraffic(): List<SeedSample> = traffic.also { traffic = emptyList() }

    /** What [live] answers; a test changes it between the engine's polls. */
    var liveMap: Map<DownloadId, TorrentLive> = emptyMap()

    /** What [detail] answers per job; a job without an entry is not in the session. */
    val details = mutableMapOf<DownloadId, TorrentDetail>()

    override suspend fun live(): Map<DownloadId, TorrentLive> = liveMap

    override suspend fun detail(id: DownloadId): TorrentDetail? = details[id]

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
