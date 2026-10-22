package dev.anydm.engine.torrent

import dev.anydm.engine.core.Download
import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.ErrorClass
import dev.anydm.engine.core.File
import dev.anydm.engine.files.ClashPolicy
import dev.anydm.engine.files.NameCleaner
import dev.anydm.engine.files.nextFreeName
import dev.anydm.engine.http.HttpProtocol
import dev.anydm.engine.media.hiddenFolderName
import dev.anydm.engine.platform.FileResult
import dev.anydm.engine.platform.FileSystem
import dev.anydm.engine.protocol.Direction
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
import dev.anydm.engine.store.Settings
import dev.anydm.engine.store.TorrentStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

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
    /** The settings as they are now: the seeding rules and what a job without its own takes. */
    private val settings: suspend () -> Settings = { Settings() },
    /** How often a running or seeding torrent's resume data is saved. */
    private val resumeEvery: Duration = 30.seconds,
    /** How often the seeds are checked against their rules. */
    private val seedPollEvery: Duration = 5.seconds,
    /** Seconds in a minute of seeding time; a test sets 1 to make a minute short. */
    private val secondsPerMinute: Int = 60,
    /** The file rows of a job, for the names its files have now when a seed is taken up again after a restart. */
    private val files: suspend (DownloadId) -> List<File> = { emptyList() },
    /** Where the seed loop runs; by default a scope of its own that [close] cancels. */
    scope: CoroutineScope? = null,
) : Protocol,
    TorrentService {
    /** The torrents in the session, by download: put there by a transfer, taken by its finalize or its end. */
    internal val handles = RunningTorrents()

    private val seedScope = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ownsScope = scope == null

    private val seeds =
        Seeds(
            torrents = torrents::get,
            settings = settings,
            resumeEvery = resumeEvery,
            seedPollEvery = seedPollEvery,
            secondsPerMinute = secondsPerMinute,
            scope = seedScope,
            save = ::saveResume,
            observe = { id, torrent -> record(id, torrent.stats(), torrent.live()) },
            onLeave = ::forget,
        )

    override val seedEnds: Flow<SeedEnd> get() = seeds.ends

    override suspend fun stopSeeding(id: DownloadId): Boolean = seeds.end(id, SeedEndReason.STOPPED)

    override suspend fun seedTraffic(): List<SeedSample> = seeds.traffic()

    override suspend fun reroute() = backend.reroute()

    /** The latest live values of each torrent the protocol holds, by job (TOR-07): written by the polls, read by [live]. */
    private val latest = mutableMapOf<DownloadId, TorrentLive>()
    private val latestLock = Mutex()

    // Only the torrents the protocol still holds: a poll that was cut short by an end can write a reading after the torrent left.
    override suspend fun live(): Map<DownloadId, TorrentLive> {
        val all = latestLock.withLock { latest.toMap() }
        return all.filterKeys { handles.get(it) != null || seeds.has(it) }
    }

    // On a thread of its own: the read is a native call and a JNI call per piece, which no caller's UI thread should wait for.
    override suspend fun detail(id: DownloadId): TorrentDetail? =
        (handles.get(id) ?: seeds.handle(id))?.let { withContext(Dispatchers.Default) { it.detail() } }

    private suspend fun record(
        id: DownloadId,
        stats: TorrentStats,
        live: TorrentLiveStats,
    ) {
        val summary =
            TorrentSummary(
                peers = live.peers,
                seeds = live.seeds,
                availability = live.availability,
                downloadRate = live.downloadRate,
                uploadRate = live.uploadRate,
                uploaded = stats.allTimeUp,
                ratio = if (stats.wanted > 0) stats.allTimeUp.toDouble() / stats.wanted else null,
                finishedSeconds = stats.seedingSeconds,
            )
        latestLock.withLock { latest[id] = TorrentLive(summary, live.fileDone) }
    }

    private suspend fun forget(id: DownloadId) {
        latestLock.withLock { latest.remove(id) }
    }

    /** Which download each infohash in the session belongs to, for resume data the backend hands over when it closes. */
    private val owners = mutableMapOf<String, DownloadId>()
    private val ownersLock = Mutex()
    private val closing = Channel<Pair<String, ByteArray>>(Channel.UNLIMITED)

    init {
        backend.resumeSink = { hash, bytes -> closing.trySend(hash to bytes) }
    }

    private suspend fun own(
        hash: String,
        id: DownloadId,
    ) = ownersLock.withLock { owners[hash] = id }

    /** Stores the torrent's resume data (TOR-06). A torrent that can't give any is skipped: resume data only saves a re-check. */
    private suspend fun saveResume(
        id: DownloadId,
        torrent: RunningTorrent,
    ) {
        // The file progress first: it needs no wait, and a torrent that can't give a blob still has it.
        // Not while it is checking its files: the count starts from zero again and would replace the saved progress.
        val done = torrent.live().let { if (it.checking) emptyList() else it.fileDone }
        val bytes = torrent.saveResume()
        quietly { if (bytes != null) torrents.setResume(id, bytes) }
        quietly { if (done.isNotEmpty()) torrents.setFileDone(id, done) }
    }

    // A full disk or a busy database costs a re-check later; it must not stop a torrent from leaving the session.
    private suspend fun quietly(write: suspend () -> Unit) {
        try {
            write()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // not worth failing the job
        }
    }

    override fun setGlobalLimits(
        download: Long?,
        upload: Long?,
    ) = backend.setSessionLimits(download, upload)

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

    override suspend fun inspect(source: String): StepResult<TorrentInfo> {
        val info =
            when (val resolved = resolveInfo(source, null)) {
                is StepResult.Failed -> return resolved
                is StepResult.Ok -> resolved.value
            }
        checkTorrent(info)?.let { return StepResult.Failed(null, it) }
        return StepResult.Ok(info)
    }

    override suspend fun applyPriorities(
        id: DownloadId,
        priorities: List<Int>,
    ): Boolean {
        val handle = handles.get(id) ?: return false
        handle.setPriorities(priorities)
        return true
    }

    override suspend fun applySequential(
        id: DownloadId,
        on: Boolean,
    ): Boolean {
        val handle = handles.get(id) ?: return false
        handle.setSequential(on)
        return true
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
        // A handle an earlier run left (its transfer was cut short without a chance to remove it) is replaced, and so is
        // a seed this job still has (its run failed after finalize kept the torrent).
        handles.take(id)?.remove()
        seeds.drop(id)
        val request =
            AddTorrent(
                info = probe.info,
                saveTo = Path(jobFolder(transfer), stagingName(transfer)),
                priorities = probe.priorities,
                sequential = probe.sequential,
                // The torrent's own names: libtorrent finds what a paused or restarted download already has by them.
                fileNames = probe.info.files.map { it.path },
                // A changed file choice has dropped it (Task 6): what is stored matches what is asked for.
                resume = torrents.get(id)?.resume,
            )
        val handle =
            when (val added = backend.add(request)) {
                is StepResult.Failed -> return added
                is StepResult.Ok -> added.value
            }
        handles.put(id, handle)
        own(probe.info.infoHash, id)
        var finished = false
        var down = 0L
        var up = 0L
        var savedAt = TimeSource.Monotonic.markNow()
        try {
            while (true) {
                val progress = handle.progress()
                io.progress(progress.wantedDone)
                val stats = handle.stats()
                if (stats.payloadDown > down) io.meter.record(stats.payloadDown - down, Direction.DOWN)
                if (stats.payloadUp > up) io.meter.record(stats.payloadUp - up, Direction.UP)
                down = maxOf(down, stats.payloadDown)
                up = maxOf(up, stats.payloadUp)
                record(id, stats, handle.live())
                progress.error?.let { return failureOf(it) }
                if (progress.finished) {
                    finished = true
                    return StepResult.Ok(Unit)
                }
                handle.setDownloadLimit(io.speedShare())
                if (savedAt.elapsedNow() >= resumeEvery) {
                    saveResume(id, handle)
                    savedAt = TimeSource.Monotonic.markNow()
                }
                delay(pollEvery)
            }
        } finally {
            // Whatever ended the transfer but a finished download leaves the session, with its resume data saved first;
            // the files stay where they are.
            if (!finished) {
                withContext(NonCancellable) {
                    handles.take(id)?.let {
                        saveResume(id, it)
                        it.remove()
                    }
                    forget(id)
                }
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
        // Gone from the session at the end whatever happens, but a seed: a failed finalize is retried from the transfer.
        var kept = false
        try {
            val placed = place(transfer, handle)
            if (placed !is StepResult.Ok) return placed
            // The blob carries the final names and place, so a restart finds the files without a check.
            saveResume(id, handle)
            val (ratio, minutes) = seedLimits(torrents.get(id), settings())
            if (ratio == null && minutes == null) return placed
            val stats = handle.stats()
            withContext(NonCancellable) { seeds.add(id, handle, stats.payloadUp, stats.payloadDown) }
            kept = true
            return StepResult.Ok(placed.value.copy(seeding = true))
        } finally {
            if (!kept) {
                withContext(NonCancellable) {
                    handle.remove()
                    forget(id)
                }
            }
        }
    }

    override suspend fun resumeSeeding(download: Download): StepResult<Unit> {
        val id = download.id
        if (seeds.has(id)) return StepResult.Ok(Unit)
        val row = torrents.get(id) ?: return StepResult.Failed(null, "the torrent's record is gone")
        val metadata = row.metadata ?: return StepResult.Failed(null, "the torrent's metadata is gone")
        val info =
            when (val read = backend.metadataOfBytes(metadata)) {
                is StepResult.Failed -> return read
                is StepResult.Ok -> read.value
            }
        val folder = download.saveFolder ?: return StepResult.Failed(null, "the download has no save folder")
        val priorities =
            resolvePriorities(row.priorities, info.files.size)
                ?: return StepResult.Failed(null, "the saved file choice doesn't fit this torrent")
        val names = finalNames(info, files(id))
        for ((i, name) in names.withIndex()) {
            if (priorities[i] == 0) continue
            if (fileSystem.size(Path(folder, name)) is FileResult.NotFound) {
                return StepResult.Failed(null, "the seeded file is gone: $name")
            }
        }
        val request = AddTorrent(info, Path(folder), priorities, sequential = false, fileNames = names, resume = row.resume)
        val handle =
            when (val added = backend.add(request)) {
                // The session didn't come up (no library on this machine), or the job's proxy can't be used (NET-15) right now:
                // not the seed's fault, so the job stays SEEDING until a start or a proxy change tries again.
                is StepResult.Failed -> {
                    val unusable = added.errorClass == ErrorClass.PROXY
                    return StepResult.Failed(if (unusable) ErrorClass.PROXY else ErrorClass.TRANSIENT, added.message)
                }

                is StepResult.Ok -> {
                    added.value
                }
            }
        own(info.infoHash, id)
        seeds.add(id, handle, up = 0, down = 0)
        return StepResult.Ok(Unit)
    }

    /**
     * The names a finished torrent's files have now, in torrent order: from the file rows (where a clash may have moved
     * them), and for a file with no row of its own (one that was skipped) the torrent's name under the same folder.
     */
    private fun finalNames(
        info: TorrentInfo,
        rows: List<File>,
    ): List<String> {
        val byIndex = rows.associateBy { it.index }
        val placedRoot = rows.minByOrNull { it.index }?.let { (it.placedPath ?: it.path).substringBefore('/') }
        return info.files.mapIndexed { i, file ->
            byIndex[i]?.let { it.placedPath ?: it.path }
                ?: if (placedRoot != null && '/' in file.path) placedRoot + "/" + file.path.substringAfter('/') else file.path
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
                        }
                    }
                }
            }
        }
        // The files were kept under the torrent's own names; they get the planned ones (or the free ones) now.
        if (names != (transfer.probe as TorrentProbe).info.files.map { it.path }) {
            when (val renamed = handle.renameFiles(names)) {
                is StepResult.Failed -> return renamed
                is StepResult.Ok -> Unit
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

    override suspend fun close() {
        seeds.stop()
        backend.close()
        latestLock.withLock { latest.clear() }
        // The backend handed over the resume data of every torrent it still held (the transfers were stopped before, the seeds are kept).
        while (true) {
            val (hash, bytes) = closing.tryReceive().getOrNull() ?: break
            val id = ownersLock.withLock { owners[hash] } ?: continue
            torrents.setResume(id, bytes)
        }
        if (ownsScope) seedScope.cancel()
    }
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
