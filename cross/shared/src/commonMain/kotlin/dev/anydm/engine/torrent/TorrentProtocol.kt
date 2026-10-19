package dev.anydm.engine.torrent

import dev.anydm.engine.core.Download
import dev.anydm.engine.core.ErrorClass
import dev.anydm.engine.files.ClashPolicy
import dev.anydm.engine.files.NameCleaner
import dev.anydm.engine.http.HttpProtocol
import dev.anydm.engine.media.hiddenFolderName
import dev.anydm.engine.platform.FileResult
import dev.anydm.engine.platform.FileSystem
import dev.anydm.engine.protocol.FinalizeResult
import dev.anydm.engine.protocol.PlanContext
import dev.anydm.engine.protocol.PlannedFile
import dev.anydm.engine.protocol.ProbeRequest
import dev.anydm.engine.protocol.ProbeResult
import dev.anydm.engine.protocol.Protocol
import dev.anydm.engine.protocol.StepResult
import dev.anydm.engine.protocol.Transfer
import dev.anydm.engine.protocol.TransferIo
import dev.anydm.engine.protocol.TransferPlan
import dev.anydm.engine.store.TorrentStore
import kotlinx.io.files.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** What a torrent probe learns: the metadata and the file choice (a priority per file, 0 skips it). */
class TorrentProbe(
    val info: TorrentInfo,
    val priorities: List<Int>,
    val sequential: Boolean,
) : ProbeResult {
    override val name: String get() = info.name

    /** The selected files' bytes. */
    override val size: Long get() = info.files.filterIndexed { i, _ -> priorities[i] > 0 }.sumOf { it.size }

    /** The infohash: a torrent that changed under the same address starts over, and the same one added twice is found. */
    override val validator: String get() = info.infoHash
}

private const val SUPPORTED = "a torrent address must be a magnet link, an http(s) address of a .torrent file, or a file:/// path"

/**
 * The TORRENT protocol (TOR-01 to TOR-04, TOR-10): the library underneath is a [TorrentBackend], which keeps and moves its
 * files itself, so this protocol [ownsFiles]. [torrentLimit] caps a `.torrent` file read from an address or a path;
 * [magnetTimeout] is how long a magnet link waits for its metadata.
 */
class TorrentProtocol(
    private val http: HttpProtocol,
    private val backend: TorrentBackend,
    private val torrents: TorrentStore,
    private val cleaner: NameCleaner,
    private val fileSystem: FileSystem,
    private val policy: suspend () -> ClashPolicy = { ClashPolicy.RENAME },
    private val magnetTimeout: Duration = 2.minutes,
    private val torrentLimit: Int = 10 shl 20,
) : Protocol {
    override val ownsFiles: Boolean get() = true

    override suspend fun probe(request: ProbeRequest): StepResult<ProbeResult> {
        val stored = torrents.get(request.download.id)
        val info =
            when (val resolved = resolveInfo(request.mirror.url, stored?.metadata)) {
                is StepResult.Failed -> return resolved
                is StepResult.Ok -> resolved.value
            }
        checkTorrent(info)?.let { return StepResult.Failed(null, it) }
        // Fetched now: kept, so a restart or a retry doesn't ask the network again. A job with no torrent row has nowhere to keep it.
        if (stored != null && stored.metadata == null) torrents.setMetadata(request.download.id, info.metadata)
        val priorities =
            resolvePriorities(stored?.priorities.orEmpty(), info.files.size)
                ?: return StepResult.Failed(null, "the saved file choice doesn't fit this torrent")
        if (priorities.all { it == 0 }) return StepResult.Failed(null, "no file is selected")
        return StepResult.Ok(TorrentProbe(info, priorities, stored?.sequential ?: false))
    }

    /** The torrent's metadata from what is stored, else from [source]: a magnet link, an address or a file. */
    internal suspend fun resolveInfo(
        source: String,
        stored: ByteArray?,
    ): StepResult<TorrentInfo> {
        if (stored != null) return backend.metadataOfBytes(stored)
        return when (val parsed = TorrentSource.parse(source)) {
            null -> StepResult.Failed(null, SUPPORTED)
            is TorrentSource.Magnet -> backend.metadataOfMagnet(parsed.uri, magnetTimeout)
            is TorrentSource.Url -> bytesOf(http.fetchBytes(parsed.url, null, torrentLimit))
            is TorrentSource.LocalFile -> bytesOf(readLocal(parsed.path))
        }
    }

    private fun bytesOf(fetched: StepResult<ByteArray>): StepResult<TorrentInfo> =
        when (fetched) {
            is StepResult.Failed -> fetched
            is StepResult.Ok -> backend.metadataOfBytes(fetched.value)
        }

    private suspend fun readLocal(path: String): StepResult<ByteArray> {
        val target = Path(path)
        val size =
            when (val sized = fileSystem.size(target)) {
                is FileResult.Ok -> sized.value
                is FileResult.NotFound -> return StepResult.Failed(ErrorClass.GONE, "there is no torrent file at $path")
                is FileResult.Failed -> return StepResult.Failed(null, "can't read $path: ${sized.detail ?: "unknown reason"}")
            }
        if (size > torrentLimit) return StepResult.Failed(null, "the file at $path is bigger than $torrentLimit bytes")
        return when (val read = fileSystem.read(target, 0, size.toInt())) {
            is FileResult.Ok -> StepResult.Ok(read.value)
            is FileResult.NotFound -> StepResult.Failed(ErrorClass.GONE, "there is no torrent file at $path")
            is FileResult.Failed -> StepResult.Failed(null, "can't read $path: ${read.detail ?: "unknown reason"}")
        }
    }

    override fun plan(
        probe: ProbeResult,
        context: PlanContext,
    ): StepResult<TransferPlan> {
        check(probe is TorrentProbe) { "TorrentProtocol can't plan a ${probe::class.simpleName}" }
        val info = probe.info
        val paths = plannedPaths(info, context.download.fileName ?: info.name, cleaner)
        return StepResult.Ok(
            TransferPlan(
                info.files.mapIndexed {
                    i,
                    file,
                    ->
                    PlannedFile(file.index, paths[i], file.size, selected = probe.priorities[i] > 0)
                },
            ),
        )
    }

    override suspend fun transfer(
        transfer: Transfer,
        io: TransferIo,
    ): StepResult<Unit> = StepResult.Failed(null, "torrent downloads aren't wired yet")

    override suspend fun finalize(transfer: Transfer): StepResult<FinalizeResult> =
        StepResult.Failed(null, "torrent downloads aren't wired yet")

    override fun workFolders(download: Download): List<String> {
        val name = download.fileName ?: return emptyList()
        return listOf(hiddenFolderName(name, download.id, "torrent"))
    }

    override suspend fun close() = backend.close()
}
