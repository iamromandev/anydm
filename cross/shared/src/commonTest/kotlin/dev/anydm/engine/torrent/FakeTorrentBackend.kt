package dev.anydm.engine.torrent

import dev.anydm.engine.core.ErrorClass
import dev.anydm.engine.protocol.StepResult
import kotlinx.coroutines.delay
import kotlinx.io.files.Path
import kotlin.time.Duration

/**
 * A [TorrentBackend] with no library: torrents are made with [torrent], whose "metadata" is its own hash as bytes;
 * magnets answer from [magnets]; [magnetNever] makes one wait out its timeout. Calls are recorded in [calls].
 */
internal class FakeTorrentBackend : TorrentBackend {
    val calls = mutableListOf<String>()
    val magnets = mutableMapOf<String, TorrentInfo>()
    var magnetNever = false
    var closed = 0
    private val known = mutableMapOf<String, TorrentInfo>()

    /** A torrent named [name] with these files (path to size, paths as the torrent has them), known to this backend. */
    fun torrent(
        name: String,
        vararg files: Pair<String, Long>,
        hash: String =
            name
                .padEnd(40, 'a')
                .take(40)
                .lowercase()
                .map { if (it in '0'..'9' || it in 'a'..'f') it else 'b' }
                .joinToString(""),
    ): TorrentInfo =
        TorrentInfo(name, hash, files.mapIndexed { i, (path, size) -> TorrentFile(i, path, size) }, hash.encodeToByteArray()).also {
            known[hash] =
                it
        }

    override suspend fun metadataOfMagnet(
        uri: String,
        timeout: Duration,
    ): StepResult<TorrentInfo> {
        calls += "magnet $uri"
        if (magnetNever) {
            delay(timeout)
            return StepResult.Failed(ErrorClass.TRANSIENT, "no metadata arrived")
        }
        return magnets[uri]?.let { StepResult.Ok(it) } ?: StepResult.Failed(ErrorClass.TRANSIENT, "no metadata arrived")
    }

    override fun metadataOfBytes(bytes: ByteArray): StepResult<TorrentInfo> {
        calls += "bytes"
        return known[bytes.decodeToString()]?.let { StepResult.Ok(it) } ?: StepResult.Failed(null, "not a valid .torrent: unreadable")
    }

    /** What every torrent added from now on reports from [RunningTorrent.progress], one answer per call, the last one repeating. */
    var script: List<TorrentProgress> = listOf(TorrentProgress(0, 0, finished = true))
    var addFailure: StepResult.Failed? = null
    val added = mutableListOf<FakeRunning>()

    override suspend fun add(request: AddTorrent): StepResult<RunningTorrent> {
        calls += "add"
        addFailure?.let { return it }
        return StepResult.Ok(FakeRunning(request, script).also { added += it })
    }

    /** The limits last given to [setSessionLimits]: download, then upload. */
    var sessionLimits: Pair<Long?, Long?>? = null

    override fun setSessionLimits(
        download: Long?,
        upload: Long?,
    ) {
        sessionLimits = download to upload
    }

    override var resumeSink: ((infoHash: String, bytes: ByteArray) -> Unit)? = null

    /** What [close] hands the sink: the resume data of each torrent still added (not removed), by hash. */
    val resumeAtClose = mutableMapOf<String, ByteArray>()

    override suspend fun close() {
        closed++
        for (running in added.filter { it.removed == 0 }) {
            val bytes = running.resume ?: continue
            resumeSink?.invoke(running.request.info.infoHash, bytes)
            resumeAtClose[running.request.info.infoHash] = bytes
        }
    }
}

/** A torrent in the fake session: scripted progress, and a record of what was done to it. */
internal class FakeRunning(
    val request: AddTorrent,
    private val script: List<TorrentProgress>,
) : RunningTorrent {
    private var at = 0
    var chosen: List<Int> = request.priorities
    var inOrder: Boolean = request.sequential
    var renamed: List<String>? = null
    var moved: Pair<Path, Boolean>? = null
    var removed = 0
    var renameFailure: StepResult.Failed? = null
    var moveFailure: StepResult.Failed? = null

    /** What [stats] answers; a test changes it between polls. */
    var stats: TorrentStats = TorrentStats(0, 0, 0, 0, 0, false)

    /** What [saveResume] answers (null: libtorrent could not make it), and how often it was asked. */
    var resume: ByteArray? = "resume-of-${request.info.infoHash}".encodeToByteArray()
    var resumeSaves = 0

    /** The last limit given to [setDownloadLimit]. */
    var limitedTo: Long? = null

    override fun progress(): TorrentProgress = script[minOf(at++, script.lastIndex)]

    override fun stats(): TorrentStats = stats

    override suspend fun saveResume(): ByteArray? {
        resumeSaves++
        return resume
    }

    override fun setDownloadLimit(bytesPerSecond: Long?) {
        limitedTo = bytesPerSecond
    }

    override fun setPriorities(priorities: List<Int>) {
        chosen = priorities
    }

    override fun setSequential(on: Boolean) {
        inOrder = on
    }

    override suspend fun renameFiles(names: List<String>): StepResult<Unit> {
        renameFailure?.let { return it }
        renamed = names
        return StepResult.Ok(Unit)
    }

    override suspend fun moveStorage(
        to: Path,
        replaceExisting: Boolean,
    ): StepResult<Unit> {
        moveFailure?.let { return it }
        moved = to to replaceExisting
        return StepResult.Ok(Unit)
    }

    override suspend fun remove() {
        removed++
    }
}
