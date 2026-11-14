package dev.anydm.engine.torrent

import dev.anydm.engine.http.Clock
import dev.anydm.engine.http.ConnectionPermits
import dev.anydm.engine.http.DiskIo
import dev.anydm.engine.http.EngineEvent
import dev.anydm.engine.http.Fail
import dev.anydm.engine.http.RateShares
import dev.anydm.engine.http.Reasons
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.random.Random

interface TorrentEngine {
    val events: SharedFlow<EngineEvent>

    fun start(job: TorrentJob)

    fun stop(id: String)

    /** Returns once the job of [id], if one is running, has ended: after a [stop], its files are closed. */
    suspend fun join(id: String) = Unit

    /** The row of [id] is gone: drop what the engine remembers about it. */
    fun forget(id: String) = Unit
}

/** The numbers a torrent engine runs by; the defaults are for real use, tests pass small ones. */
data class TorrentLimits(
    val perTorrent: Int = MAX_PEERS_PER_TORRENT,
    val total: Int = MAX_PEERS_TOTAL,
    val noPeersMs: Long = NO_PEERS_MS,
    /** At least one `Progress` this often. */
    val reportMs: Long = 500,
    /** The most unverified piece data one torrent holds (or one piece, when that is more): see [bufferCap]. */
    val maxBuffered: Long = MAX_BUFFERED.toLong(),
    val connectTimeoutMs: Int = 10_000,
    val handshakeMs: Long = 30_000,
    /** Announces to one tracker are at least this far apart. */
    val minAnnounceMs: Long = MIN_ANNOUNCE_MS,
    /** The time the `completed` and `stopped` announces have, together. */
    val farewellMs: Long = FAREWELL_MS,
)

/** How often a run looks at the clock: four times in a report interval, so a late tick cannot push a report far past its time. */
internal fun TorrentLimits.tickMs(): Long = (reportMs / 4).coerceIn(10, 250)

/**
 * One coroutine per torrent (see [TorrentRun]); `stop` cancels it, which closes every peer connection at once and forces and
 * closes the files, and a `Stopped` event says which pieces were verified. Jobs run under their own supervisor: one that fails never
 * takes the others down. Peer connections of all torrents together are held to `limits.total`.
 *
 * A start with pieces (a resume) hashes those pieces again from the files before it trusts them (see [recheck]): `Progress` events with
 * the bytes verified so far come meanwhile (no `pieces`: the row keeps its own), then `Started` with the pieces that held, before any
 * peer is connected to. A pause during the check says the row's own pieces in `Stopped`. A job with no pieces (a row that never ran)
 * claims none in `Failed` or `Stopped` until its `Started` has been told: before that its files have no settled name. [trackers] announces each torrent to its
 * trackers (see [Announcer]); a job's own `peers` list is offered as well.
 *
 * A job with a `magnet` and no `.torrent` copy yet starts with the metadata: it is fetched from peers ([MetadataFetch]), saved as the
 * copy, told in a `Metadata` event, and then the job goes on as any other: a pause, a failure or a stop before that claims no pieces
 * (no `Started` has been told), and the row's own `doneBytes` is handed back. The fetch uses the same peers cap, permits and speed cap.
 *
 * The end of a job is told once its files are closed (`Failed`, `Stopped`) or renamed (`Finished`), and the events cannot be lost to
 * a cancel. From the moment it is told the job counts as over: a `start` for the same id (a resume made on seeing `Failed`) is
 * accepted at once and waits only for the old coroutine to finish its cleanup.
 */
class DefaultTorrentEngine(
    private val transport: PeerTransport,
    /** Where the peers come from besides a job's own list: the trackers of each torrent are announced to through this. */
    private val trackers: TrackerClient,
    private val disk: DiskIo,
    private val clock: Clock,
    scope: CoroutineScope,
    /** The speed cap and the buckets of the running downloads; the HTTP engine uses the same object to split the cap with this one. */
    val shares: RateShares = RateShares(),
    private val random: Random = Random.Default,
    private val limits: TorrentLimits = TorrentLimits(),
) : TorrentEngine {
    private val jobScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    private val bus = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 1024)
    override val events: SharedFlow<EngineEvent> = bus.asSharedFlow()
    private val jobs = HashMap<String, Slot>()
    private val permits = ConnectionPermits(limits.total)
    private val runs = ConcurrentHashMap<String, TorrentRun>()

    /** The last announces of the latest run of each job: a resume waits for them (see [TorrentEnv.farewells]). */
    private val farewells = ConcurrentHashMap<String, Job>()

    /** The root paths that running jobs write, by the id of the job: two jobs never write the same files. */
    private val claims = HashMap<String, String>()

    /** A job and whether it has told its end: from then on it no longer stands in the way of a start for the same id. */
    private class Slot {
        lateinit var job: Job

        @Volatile var ended = false

        /** Its row is gone: it must save no `.torrent` copy (guarded by the engine's monitor, as the save is). */
        var forgotten = false
    }

    private fun keyOf(path: Path): String = path.normalize().toString().lowercase(Locale.ROOT)

    @Synchronized
    override fun start(job: TorrentJob) {
        val old = jobs[job.id]
        if (old != null && old.job.isActive && !old.ended) return // already running; stop() first
        val slot = Slot()
        slot.job =
            jobScope.launch(Dispatchers.IO, CoroutineStart.LAZY) {
                old?.job?.join() // a stopped or failed predecessor may still be winding down on the same files
                run(job, slot)
            }
        jobs[job.id] = slot
        slot.job.invokeOnCompletion {
            synchronized(this) { if (jobs[job.id] === slot) jobs.remove(job.id) }
        }
        slot.job.start()
    }

    @Synchronized
    override fun stop(id: String) {
        jobs[id]?.job?.cancel()
    }

    /** A job still winding down may be in the middle of saving a copy for a row that is gone: after this it saves none. */
    @Synchronized
    override fun forget(id: String) {
        jobs[id]?.forgotten = true
    }

    override suspend fun join(id: String) {
        val slot = synchronized(this) { jobs[id] }
        slot?.job?.join()
    }

    /** Offers [addrs] to the running torrent [id] as peers to connect to; nothing happens when it is not running. */
    internal suspend fun offerPeers(
        id: String,
        addrs: List<PeerAddr>,
    ) {
        runs[id]?.offerPeers(addrs)
    }

    /** The run of [id] while it is going; tests read what it measured. */
    internal fun runOf(id: String): TorrentRun? = runs[id]

    private suspend fun run(
        start: TorrentJob,
        slot: Slot,
    ) {
        // the job as it goes on: a magnet job that fetched its metadata takes the torrent's own name (see below)
        var job = start
        var startedTrackers: Set<String> = emptySet()
        shares.register(job.id)
        var meta: TorrentMeta? = null
        var store: TorrentStore? = null
        var run: TorrentRun? = null
        var claimed: String? = null
        var pieces: PieceSet? = job.pieces

        // before the metainfo is read the figure is the row's own, handed back, never a 0 that would reset it
        fun done(): Long = meta?.let { m -> pieces?.doneBytes(m) } ?: job.doneBytes

        /** Tells how the job ended: the files are closed first, so whoever hears it may open them again; and not even a stop cuts it short. */
        suspend fun end(event: () -> EngineEvent) {
            withContext(NonCancellable) {
                withContext(Dispatchers.IO) { store?.close() }
                slot.ended = true
                bus.emit(event())
            }
        }
        val env =
            TorrentEnv(
                transport,
                clock,
                { bus.emit(it) },
                shares.bucket(job.id),
                permits,
                random,
                limits,
                trackers,
                jobScope,
                farewells = farewells,
            )
        try {
            // a magnet row that has no usable copy yet: the metadata comes first, and is saved as the copy the run below reads
            val magnet = start.magnet
            if (magnet != null) {
                val copy = usableCopy(start, magnet)
                val known: TorrentMeta
                if (copy == null) {
                    discardCopy(start)
                    val fetched = fetchMetadata(env, start, magnet, slot)
                    startedTrackers = fetched.startedTrackers
                    known = fetched.meta
                } else {
                    // The copy is there already (a stop after the fetch, a copy put there): a row that has no pieces has not been told it.
                    // A row that has pieces was told before its first Started, so it is not told again.
                    if (start.pieces == null) {
                        withContext(NonCancellable) { bus.emit(EngineEvent.Metadata(start.id, copy.name, copy.totalSize, copy.files)) }
                    }
                    known = copy
                }
                // The row's name is the link's `dn` or the hash, never what the files are called: the torrent's own name is. A row that
                // has pieces has settled its name already (its files are there), and keeps it.
                if (start.pieces == null) job = start.copy(name = known.name)
            }
            val loaded = load(job)
            meta = loaded
            job.pieces?.takeIf { it.size != loaded.pieceCount }?.let {
                log.warning(
                    "torrent job ${job.id}: the row claims pieces for ${it.size} pieces, the torrent has ${loaded.pieceCount}: ignored",
                )
            }
            val have = job.pieces?.takeIf { it.size == loaded.pieceCount } ?: PieceSet.empty(loaded.pieceCount)
            // A claim of the wrong size is handed back as an empty one of the right size; a row with no claim (it never ran) keeps
            // none until Started: its name is settled only then, and any end before that must not tell the row it is.
            if (job.pieces != null) pieces = have
            // not cancellable: what open() made (files, a claim) must reach the cleanup below, never be lost to a stop that came meanwhile
            val opened = withContext(Dispatchers.IO + NonCancellable) { open(job, loaded) }
            store = opened.first
            claimed = opened.second
            // what the row claims is trusted after its bytes on disk hash right, not before; the claims stay the row's own until then
            val checkEnv =
                RecheckEnv(clock, Dispatchers.IO, maxOf(limits.maxBuffered, loaded.pieceLength), limits.reportMs, limits.tickMs())
            val checked =
                recheck(checkEnv, store, loaded, have) { bus.emit(EngineEvent.Progress(job.id, it, loaded.totalSize, 0, connections = 0)) }
            // a stop that came during the open or the check ends the job here, before Started: no name was told, so no claim is made
            currentCoroutineContext().ensureActive()
            pieces = checked
            bus.emit(EngineEvent.Started(job.id, store.root, loaded.totalSize, null, null, checked.doneBytes(loaded), pieces = checked))
            val torrentRun = TorrentRun(env, job, loaded, store, checked, startedTrackers)
            run = torrentRun
            runs[job.id] = torrentRun
            try {
                torrentRun.run()
            } finally {
                pieces = torrentRun.verified
            }
            // Once every piece is in, the end is not interrupted: a stop now must not leave files renamed and the row paused.
            withContext(NonCancellable) {
                val name = withContext(Dispatchers.IO) { store.finish() }
                slot.ended = true
                bus.emit(EngineEvent.Finished(job.id, loaded.totalSize, name.takeIf { it != start.name }))
            }
        } catch (e: CancellationException) {
            end { EngineEvent.Stopped(job.id, done(), pieces = pieces) }
            throw e
        } catch (e: Fail) {
            end { EngineEvent.Failed(job.id, e.reason, done(), pieces = pieces) }
        } catch (e: Throwable) {
            // Anything unforeseen, an Error too, is this download's failure, not the engine's: the row says so, and the cause is logged.
            log.log(Level.WARNING, "torrent job ${job.id} failed unexpectedly", e)
            end { EngineEvent.Failed(job.id, Reasons.NETWORK, done(), pieces = pieces) }
        } finally {
            runs.remove(job.id, run)
            withContext(NonCancellable) { store?.close() }
            claimed?.let { release(it, job.id) }
            shares.unregister(job.id)
        }
    }

    /**
     * The `.torrent` copy of the magnet [job], when it can be used: a regular file that reads as a torrent with the magnet's info hash.
     * Anything else (a folder, an empty or damaged file, the copy of another torrent) gives null: it is replaced, not a reason to give up.
     */
    private suspend fun usableCopy(
        job: TorrentJob,
        magnet: MagnetLink,
    ): TorrentMeta? =
        withContext(Dispatchers.IO) {
            try {
                val path = Path.of(job.torrentPath)
                if (!Files.isRegularFile(path)) return@withContext null
                val size = Files.size(path)
                if (size <= 0 || size > MAX_TORRENT_BYTES) return@withContext null
                val meta = (readTorrent(Files.readAllBytes(path)) as? TorrentResult.Ok)?.meta ?: return@withContext null
                meta.takeIf { it.pieceHashes.size == it.pieceCount && it.infoHash.contentEquals(magnet.infoHash) }
            } catch (_: IOException) {
                null
            } catch (_: InvalidPathException) {
                null
            }
        }

    /** Removes what is at the copy's path (a copy that cannot be used), so the fetch can save a new one; what cannot be removed is [Reasons.NOT_WRITABLE]. */
    private suspend fun discardCopy(job: TorrentJob) {
        withContext(Dispatchers.IO) {
            try {
                Files.deleteIfExists(Path.of(job.torrentPath))
            } catch (e: IOException) {
                log.log(Level.WARNING, "torrent job ${job.id}: the unusable copy could not be removed", e)
                throw Fail(Reasons.NOT_WRITABLE)
            } catch (_: InvalidPathException) {
                throw Fail(Reasons.NOT_WRITABLE)
            }
        }
    }

    /** The metadata of a magnet, as saved, and the trackers that heard `started` while it was fetched. */
    private class Fetched(
        val meta: TorrentMeta,
        val startedTrackers: Set<String>,
    )

    /**
     * Fetches the metadata of [magnet] from peers, and saves it as the `.torrent` copy of [job]: written to a temp file next to it,
     * forced to disk, and moved over the real name in one step, so a crash never leaves half a copy under it. The write and the
     * `Metadata` event that follows it are not cancellable: a copy that is on disk is always told, and one is not saved for a row that
     * was forgotten (see [forget]). A copy that cannot be written is
     * [Reasons.NOT_WRITABLE]; metadata that matches the hash but is no torrent this client reads is [METADATA_INVALID].
     */
    private suspend fun fetchMetadata(
        env: TorrentEnv,
        job: TorrentJob,
        magnet: MagnetLink,
        slot: Slot,
    ): Fetched {
        val fetch = MetadataFetch(env, job.id, magnet, job.peerId, job.peers)
        val info =
            fetch.run { n ->
                bus.emit(EngineEvent.Progress(job.id, job.doneBytes, null, 0, connections = n, peers = n))
            }
        val bytes = torrentFileBytes(info, magnet.trackers)
        val meta = (readTorrent(bytes) as? TorrentResult.Ok)?.meta ?: throw Fail(METADATA_INVALID)
        if (meta.pieceHashes.size != meta.pieceCount) throw Fail(METADATA_INVALID)
        // the hash covers the bytes as received: bytes after the dictionary (which a torrent file reads as keys of its own) change it
        if (!meta.infoHash.contentEquals(magnet.infoHash)) throw Fail(METADATA_INVALID)
        withContext(NonCancellable) {
            withContext(Dispatchers.IO) {
                // checked and saved under the monitor `forget` takes: a forgotten job saves nothing, and a copy saved before it is
                // there for whoever forgot to delete
                synchronized(this@DefaultTorrentEngine) {
                    if (slot.forgotten) throw CancellationException("the row is gone")
                    saveCopy(job, bytes)
                }
            }
            bus.emit(EngineEvent.Metadata(job.id, meta.name, meta.totalSize, meta.files))
        }
        return Fetched(meta, fetch.startedTrackers())
    }

    private fun saveCopy(
        job: TorrentJob,
        bytes: ByteArray,
    ) {
        val target =
            try {
                Path.of(job.torrentPath)
            } catch (_: InvalidPathException) {
                throw Fail(Reasons.NOT_WRITABLE)
            }
        val tmp = target.resolveSibling("${target.fileName}.tmp")
        try {
            target.parent?.let { Files.createDirectories(it) }
            FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { ch ->
                val buf = ByteBuffer.wrap(bytes)
                while (buf.hasRemaining()) ch.write(buf)
                ch.force(true)
            }
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: IOException) {
            log.log(Level.WARNING, "torrent job ${job.id}: the metadata could not be saved", e)
            throw Fail(Reasons.NOT_WRITABLE)
        } finally {
            runCatching { Files.deleteIfExists(tmp) }
        }
    }

    /** The metainfo of the saved `.torrent` of [job]; a copy that is gone, too big or not a torrent any more is [CANT_RESUME]. */
    private companion object {
        val log: Logger = Logger.getLogger(DefaultTorrentEngine::class.java.name)
    }

    private suspend fun load(job: TorrentJob): TorrentMeta {
        val bytes =
            withContext(Dispatchers.IO) {
                try {
                    val path = Path.of(job.torrentPath)
                    if (Files.size(path) > MAX_TORRENT_BYTES) null else Files.readAllBytes(path)
                } catch (_: IOException) {
                    null
                } catch (_: InvalidPathException) {
                    null
                }
            } ?: throw Fail(CANT_RESUME)
        val meta = (readTorrent(bytes) as? TorrentResult.Ok)?.meta ?: throw Fail(CANT_RESUME)
        if (meta.pieceHashes.size != meta.pieceCount) throw Fail(CANT_RESUME)
        return meta
    }

    /** Opens the files and claims their root, in one step, so two jobs never pick the same free name. */
    private fun open(
        job: TorrentJob,
        meta: TorrentMeta,
    ): Pair<TorrentStore, String> {
        val folder =
            try {
                Path.of(job.folder).normalize()
            } catch (_: InvalidPathException) {
                throw Fail(Reasons.NOT_WRITABLE)
            }
        synchronized(claims) {
            val taken = { p: Path -> keyOf(p) in claims }
            if (job.pieces != null && claims[keyOf(folder.resolve(job.name))].let { it != null && it != job.id }) {
                throw Fail(Reasons.NOT_WRITABLE) // another running job writes these files
            }
            val store = TorrentStore.open(disk, meta, folder, job.name, resume = job.pieces != null, taken)
            val key = keyOf(folder.resolve(store.root))
            claims[key] = job.id
            return store to key
        }
    }

    private fun release(
        key: String,
        id: String,
    ) {
        synchronized(claims) { if (claims[key] == id) claims.remove(key) }
    }
}
