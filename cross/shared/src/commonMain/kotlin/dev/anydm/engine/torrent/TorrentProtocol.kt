package dev.anydm.engine.torrent

import dev.anydm.engine.core.Download
import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.ErrorClass
import dev.anydm.engine.files.ClashPolicy
import dev.anydm.engine.files.NameCleaner
import dev.anydm.engine.files.nextFreeName
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
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

private const val MAX_RENAMES = 10_000

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
    /** How often a running torrent is asked how far it is. */
    private val pollEvery: Duration = 500.milliseconds,
) : Protocol {
    /** The torrents in the session, by download: put there by a transfer, taken by its finalize or its end. */
    internal val handles = RunningTorrents()

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
    ): StepResult<Unit> {
        val probe = transfer.probe
        check(probe is TorrentProbe) { "TorrentProtocol can't transfer a ${probe::class.simpleName}" }
        val id = transfer.download.id
        // A handle an earlier run left (its transfer was cut short without a chance to remove it) is replaced.
        handles.take(id)?.remove()
        val request =
            AddTorrent(
                info = probe.info,
                saveTo = Path(jobFolder(transfer), stagingName(transfer)),
                priorities = probe.priorities,
                sequential = probe.sequential,
                fileNames = transfer.files.map { it.file.path },
            )
        val handle =
            when (val added = backend.add(request)) {
                is StepResult.Failed -> return added
                is StepResult.Ok -> added.value
            }
        handles.put(id, handle)
        var finished = false
        try {
            while (true) {
                val progress = handle.progress()
                io.progress(progress.wantedDone)
                progress.error?.let { return failureOf(it) }
                if (progress.finished) {
                    finished = true
                    return StepResult.Ok(Unit)
                }
                delay(pollEvery)
            }
        } finally {
            // Whatever ended the transfer but a finished download leaves the session; the files stay where they are.
            if (!finished) {
                withContext(NonCancellable) { handles.take(id)?.remove() }
            }
        }
    }

    private fun failureOf(error: TorrentError): StepResult.Failed =
        when (error.kind) {
            TorrentErrorKind.DISK_FULL -> StepResult.Failed(ErrorClass.DISK_FULL, error.message)
            TorrentErrorKind.LOST -> StepResult.Failed(ErrorClass.TRANSIENT, error.message)
            TorrentErrorKind.OTHER -> StepResult.Failed(null, error.message)
        }

    override suspend fun finalize(transfer: Transfer): StepResult<FinalizeResult> {
        val id = transfer.download.id
        val handle =
            handles.take(id) ?: return StepResult.Failed(ErrorClass.TRANSIENT, "the torrent is no longer in the session")
        // Gone from the session at the end whatever happens: a failed finalize is retried from the transfer.
        return try {
            place(transfer, handle)
        } finally {
            withContext(NonCancellable) { handle.remove() }
        }
    }

    /** Moves the torrent out of its staging folder (TOR-10) under the name-clash policy (FIL-04). */
    private suspend fun place(
        transfer: Transfer,
        handle: RunningTorrent,
    ): StepResult<FinalizeResult> {
        val folder = jobFolder(transfer)
        val planned = transfer.files.map { it.file.path }
        val folderRoot = planned.first().contains('/')
        val root = planned.first().substringBefore('/')
        var replace = false
        var names = planned
        when (val taken = takenAs(folder, root, folderRoot)) {
            is StepResult.Failed -> {
                return taken
            }

            is StepResult.Ok -> {
                if (taken.value) {
                    when (policy()) {
                        ClashPolicy.SKIP -> {
                            return StepResult.Failed(null, "$root already exists in the save folder")
                        }

                        ClashPolicy.OVERWRITE -> {
                            replace = true
                        }

                        ClashPolicy.RENAME -> {
                            val free =
                                when (val found = freeRoot(folder, root, folderRoot)) {
                                    is StepResult.Failed -> return found
                                    is StepResult.Ok -> found.value
                                }
                            names = planned.map { free + it.removePrefix(root) }
                            when (val renamed = handle.renameFiles(names)) {
                                is StepResult.Failed -> return renamed
                                is StepResult.Ok -> Unit
                            }
                        }
                    }
                }
            }
        }
        when (val moved = handle.moveStorage(folder, replace)) {
            is StepResult.Failed -> return moved
            is StepResult.Ok -> Unit
        }
        val selected = (transfer.probe as TorrentProbe).priorities.map { it > 0 }
        return StepResult.Ok(
            FinalizeResult(
                outputs = names.filterIndexed { i, _ -> selected[i] },
                // Renamed: the rows planned under the old names give way to the ones the files now have.
                consumed = if (names == planned) emptyList() else planned,
                cleanup = listOf(stagingName(transfer), ".${(transfer.probe as TorrentProbe).info.infoHash}.parts"),
            ),
        )
    }

    /** Whether [root] is taken in [folder]: a file, or for a torrent with a folder, one that isn't empty. */
    private suspend fun takenAs(
        folder: Path,
        root: String,
        folderRoot: Boolean,
    ): StepResult<Boolean> {
        val at = Path(folder, root)
        return when (val found = fileSystem.size(at)) {
            is FileResult.NotFound -> {
                StepResult.Ok(false)
            }

            is FileResult.Ok -> {
                StepResult.Ok(true)
            }

            is FileResult.AlreadyExists -> {
                if (!folderRoot) {
                    StepResult.Ok(true)
                } else {
                    when (val children = fileSystem.list(at)) {
                        is FileResult.Ok -> StepResult.Ok(children.value.isNotEmpty())
                        is FileResult.NotFound -> StepResult.Ok(false)
                        is FileResult.Failed -> StepResult.Failed(null, "can't look in $at: ${children.detail ?: "unknown reason"}")
                    }
                }
            }

            is FileResult.Failed -> {
                StepResult.Failed(null, "can't look at $at: ${found.detail ?: "unknown reason"}")
            }
        }
    }

    /** The first of `Name (1)`, `Name (2)`, ... nothing in [folder] has taken; a file keeps its extension. */
    private suspend fun freeRoot(
        folder: Path,
        root: String,
        folderRoot: Boolean,
    ): StepResult<String> {
        if (!folderRoot) {
            return when (val next = nextFreeName(fileSystem, folder, root)) {
                is FileResult.Ok -> StepResult.Ok(next.value.name)
                is FileResult.Failed -> StepResult.Failed(null, "can't find a free name for $root: ${next.detail ?: "unknown reason"}")
            }
        }
        for (number in 1..MAX_RENAMES) {
            val candidate = "$root ($number)"
            when (val found = fileSystem.size(Path(folder, candidate))) {
                is FileResult.NotFound -> return StepResult.Ok(candidate)
                is FileResult.Ok, is FileResult.AlreadyExists -> Unit
                is FileResult.Failed -> return StepResult.Failed(null, "can't look at $candidate: ${found.detail ?: "unknown reason"}")
            }
        }
        return StepResult.Failed(null, "no free name for $root after $MAX_RENAMES tries")
    }

    /** The folder the planned paths are relative to: the targets of the files, minus what the planned paths add. */
    private fun jobFolder(transfer: Transfer): Path {
        val first = transfer.files.first()
        var folder = first.target
        repeat(first.file.path.count { it == '/' } + 1) {
            folder =
                checkNotNull(folder.parent) { "target without a folder: ${first.target}" }
        }
        return folder
    }

    private fun stagingName(transfer: Transfer): String =
        hiddenFolderName(transfer.download.fileName ?: transfer.probe.name, transfer.download.id, "torrent")

    override fun workFolders(download: Download): List<String> {
        val name = download.fileName ?: return emptyList()
        return listOf(hiddenFolderName(name, download.id, "torrent"))
    }

    override suspend fun close() = backend.close()
}

/** The running torrents by download id. Calls come from several coroutines (a transfer, a command), so it is locked. */
internal class RunningTorrents {
    private val lock = Mutex()
    private val running = mutableMapOf<DownloadId, RunningTorrent>()

    suspend fun put(
        id: DownloadId,
        torrent: RunningTorrent,
    ) = lock.withLock { running[id] = torrent }

    suspend fun get(id: DownloadId): RunningTorrent? = lock.withLock { running[id] }

    suspend fun take(id: DownloadId): RunningTorrent? = lock.withLock { running.remove(id) }
}
