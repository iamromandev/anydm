package dev.anydm.engine.torrent

import dev.anydm.engine.core.ErrorClass
import dev.anydm.engine.protocol.StepResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.files.Path
import org.libtorrent4j.AlertListener
import org.libtorrent4j.MoveFlags
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.TcpEndpoint
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AlertType
import org.libtorrent4j.alerts.FileRenameFailedAlert
import org.libtorrent4j.alerts.FileRenamedAlert
import org.libtorrent4j.alerts.StorageMovedAlert
import org.libtorrent4j.alerts.StorageMovedFailedAlert
import org.libtorrent4j.alerts.TorrentAlert
import org.libtorrent4j.swig.settings_pack
import java.io.File
import java.lang.ref.Reference
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * [TorrentBackend] over libtorrent (libtorrent4j). The session starts the first time something needs it and once, and
 * listens on [TorrentConfig.port]. Every call that waits for libtorrent does it off the caller's thread; a cancelled
 * caller stops waiting.
 */
class LibTorrentBackend(
    private val natives: TorrentNatives,
    private val config: TorrentConfig,
) : TorrentBackend {
    private val lock = Mutex()
    private var manager: SessionManager? = null
    private var closed = false
    private val moves = ConcurrentHashMap<String, CompletableDeferred<StepResult<Unit>>>()
    private val renames = ConcurrentHashMap<String, Renames>()
    private val watch = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // A handle used after its session was stopped is a crash, not an error: every call on one holds this for reading,
    // and stopping the session holds it for writing, so no call is half way into libtorrent while the session goes.
    private val native = ReentrantReadWriteLock()

    @Volatile
    private var stopped = false

    private inline fun <T> live(
        whenStopped: () -> T,
        block: () -> T,
    ): T {
        val read = native.readLock()
        read.lock()
        try {
            return if (stopped) whenStopped() else block()
        } finally {
            read.unlock()
        }
    }

    private class Renames(
        var left: Int,
        val done: CompletableDeferred<StepResult<Unit>>,
    )

    private suspend fun session(): StepResult<SessionManager> =
        lock.withLock {
            manager?.let { return StepResult.Ok(it) }
            if (closed) return StepResult.Failed(null, "the torrent engine is closed")
            when (val loaded = natives.load()) {
                is StepResult.Failed -> return loaded
                is StepResult.Ok -> Unit
            }
            val port = config.port()
            val started =
                try {
                    withContext(Dispatchers.IO) {
                        SessionManager(false).also {
                            it.addListener(alerts)
                            // Plain reads and writes in place of libtorrent's default memory-mapped files: with the JVM's signal
                            // handlers kept (see SignalGuard), a full disk or a file cut short under a mapping would be a
                            // SIGBUS that ends the process, where a write that fails is an error the engine reports.
                            it.start(SessionParams(settings(port)).also { params -> params.swig().set_posix_disk_io_constructor() })
                        }
                    }
                } catch (e: RuntimeException) {
                    return StepResult.Failed(null, "the torrent engine can't start: ${e.message}")
                }
            manager = started
            // libtorrent takes the JVM's signal handlers over when it first moves data; put them back as soon as it has.
            watch.launch {
                while (isActive) {
                    natives.signals.repair()
                    delay(SIGNAL_CHECK_MS)
                }
            }
            StepResult.Ok(started)
        }

    private fun settings(port: Int): SettingsPack {
        val interfaces = if (config.loopbackOnly) "127.0.0.1:$port" else "0.0.0.0:$port,[::]:$port"
        val pack = SettingsPack().listenInterfaces(interfaces)
        // Each file of a torrent that is renamed is an alert, and the default queue of 2000 drops the rest of a big torrent's.
        pack.setInteger(settings_pack.int_types.alert_queue_size.swigValue(), ALERT_QUEUE_SIZE)
        if (config.loopbackOnly) {
            for (flag in listOf(
                settings_pack.bool_types.enable_dht,
                settings_pack.bool_types.enable_lsd,
                settings_pack.bool_types.enable_upnp,
                settings_pack.bool_types.enable_natpmp,
            )) {
                pack.setBoolean(flag.swigValue(), false)
            }
            // Two sessions of one test share an address.
            pack.setBoolean(settings_pack.bool_types.allow_multiple_connections_per_ip.swigValue(), true)
        }
        return pack
    }

    /** Answers the waits for a move or a rename: libtorrent says when they are done through alerts. */
    private val alerts =
        object : AlertListener {
            override fun types() =
                intArrayOf(
                    AlertType.STORAGE_MOVED.swig(),
                    AlertType.STORAGE_MOVED_FAILED.swig(),
                    AlertType.FILE_RENAMED.swig(),
                    AlertType.FILE_RENAME_FAILED.swig(),
                )

            override fun alert(alert: Alert<*>) {
                if (alert !is TorrentAlert<*>) return
                val hash = alert.handle().infoHash().toHex()
                when (alert) {
                    is StorageMovedAlert -> {
                        moves.remove(hash)?.complete(StepResult.Ok(Unit))
                    }

                    is StorageMovedFailedAlert -> {
                        moves
                            .remove(
                                hash,
                            )?.complete(
                                StepResult.Failed(null, "can't move the files: ${alert.error().message} ${alert.filePath()}".trim()),
                            )
                    }

                    is FileRenamedAlert -> {
                        renames[hash]?.let { if (--it.left == 0) it.done.complete(StepResult.Ok(Unit)) }
                    }

                    is FileRenameFailedAlert -> {
                        renames[hash]?.done?.complete(StepResult.Failed(null, "can't rename a file: ${alert.error.message}"))
                    }

                    else -> {
                        Unit
                    }
                }
            }
        }

    override fun metadataOfBytes(bytes: ByteArray): StepResult<TorrentInfo> {
        when (val loaded = natives.ensure()) {
            is StepResult.Failed -> return loaded
            is StepResult.Ok -> Unit
        }
        return try {
            StepResult.Ok(infoOf(org.libtorrent4j.TorrentInfo(bytes), bytes))
        } catch (e: RuntimeException) {
            StepResult.Failed(null, "not a valid .torrent: ${e.message}")
        }
    }

    private fun infoOf(
        info: org.libtorrent4j.TorrentInfo,
        bytes: ByteArray,
    ): TorrentInfo {
        val files = info.files()
        return TorrentInfo(
            name = info.name(),
            infoHash = info.infoHash().toHex(),
            files =
                (0 until files.numFiles()).map {
                    TorrentFile(
                        it,
                        files.filePath(it).replace(File.separatorChar, '/'),
                        files.fileSize(it),
                    )
                },
            metadata = bytes,
        )
    }

    override suspend fun metadataOfMagnet(
        uri: String,
        timeout: Duration,
    ): StepResult<TorrentInfo> {
        val manager =
            when (val started = session()) {
                is StepResult.Failed -> return started
                is StepResult.Ok -> started.value
            }
        val withHints = config.peerHints.fold(uri) { link, peer -> "$link&x.pe=$peer" }
        val seconds = timeout.inWholeSeconds.coerceAtLeast(1).toInt()
        val scratch = Files.createTempDirectory("anydm-magnet-").toFile()
        try {
            val bytes =
                try {
                    runInterruptible(Dispatchers.IO) { manager.fetchMagnet(withHints, seconds, scratch) }
                } catch (e: RuntimeException) {
                    return StepResult.Failed(null, "not a usable magnet link: ${e.message}")
                } ?: return StepResult.Failed(ErrorClass.TRANSIENT, "no metadata for the magnet link in $seconds s")
            return metadataOfBytes(bytes)
        } finally {
            scratch.deleteRecursively()
        }
    }

    override suspend fun add(request: AddTorrent): StepResult<RunningTorrent> {
        val manager =
            when (val started = session()) {
                is StepResult.Failed -> return started
                is StepResult.Ok -> started.value
            }
        val info =
            try {
                org.libtorrent4j.TorrentInfo(request.info.metadata)
            } catch (e: RuntimeException) {
                return StepResult.Failed(null, "not a valid .torrent: ${e.message}")
            }
        val priorities =
            if (request.priorities.isEmpty()) {
                null
            } else {
                Array(request.priorities.size) { Priority.fromSwig(request.priorities[it]) }
            }
        // Paused until the files have their names: nothing is written, or even looked for, under a name that is about to change.
        var flags = TorrentFlags.PAUSED
        if (request.sequential) flags = flags.or_(TorrentFlags.SEQUENTIAL_DOWNLOAD)
        val peers =
            config.peerHints.map {
                it.substringBeforeLast(':').let { host ->
                    TcpEndpoint(host, it.substringAfterLast(':').toInt())
                }
            }
        val hash = info.infoHash()
        val handle =
            try {
                val saveTo = File(request.saveTo.toString())
                withContext(Dispatchers.IO) {
                    saveTo.mkdirs()
                    manager.download(info, saveTo, null, priorities, peers, flags)
                }
                // Adding is asynchronous: the handle shows up shortly.
                var found: TorrentHandle? = null
                var tries = 0
                while (found == null && tries++ < ADD_WAIT_TRIES) {
                    found = live({ null }) { manager.find(hash) }
                    if (found == null) delay(ADD_WAIT_EVERY_MS)
                }
                found
            } catch (e: CancellationException) {
                // Cancelled with the torrent added or about to be: nobody holds it, so nobody else would remove it.
                withContext(NonCancellable) { dropAfterCancel(manager, hash) }
                throw e
            } catch (e: RuntimeException) {
                return StepResult.Failed(null, "can't start the torrent: ${e.message}")
            } ?: return StepResult.Failed(ErrorClass.TRANSIENT, "the torrent didn't start")
        val torrentFiles = request.info.files
        live({}) {
            // Not auto-managed: libtorrent's own queue would hold all but 3 torrents back, whatever the engine's limit says.
            handle.unsetFlags(TorrentFlags.AUTO_MANAGED)
            request.fileNames.forEachIndexed { i, name -> if (name != torrentFiles[i].path) handle.renameFile(i, name) }
            handle.resume()
        }
        return StepResult.Ok(Running(manager, handle, hash.toHex()))
    }

    /** The torrent may reach the session a moment after its add was cancelled: look until it has, then remove it. */
    private suspend fun dropAfterCancel(
        manager: SessionManager,
        hash: org.libtorrent4j.Sha1Hash,
    ) {
        repeat(DROP_TRIES) {
            val found = live({ null }) { manager.find(hash) }
            if (found != null) {
                live({}) { manager.remove(found) }
                return
            }
            delay(ADD_WAIT_EVERY_MS)
        }
    }

    /** A torrent in the session, as the tests see it. */
    internal class SessionTorrent(
        val infoHash: String,
        val autoManaged: Boolean,
    )

    /** For tests: what is in the session now. */
    internal fun sessionTorrents(): List<SessionTorrent> {
        val session = manager ?: return emptyList()
        return live({ emptyList() }) {
            val handles = session.swig().get_torrents()
            (0 until handles.size).map { i ->
                val handle = TorrentHandle(handles[i])
                SessionTorrent(handle.infoHash().toHex(), handle.flags.and_(TorrentFlags.AUTO_MANAGED).non_zero())
            }
        }
    }

    private inner class Running(
        private val manager: SessionManager,
        private val handle: TorrentHandle,
        private val hash: String,
    ) : RunningTorrent {
        private val gone = TorrentProgress(0, 0, false, TorrentError(TorrentErrorKind.LOST, "the torrent left the session"))

        override fun progress(): TorrentProgress =
            live({ gone }) {
                if (!handle.isValid) return@live gone
                val status = handle.status()
                try {
                    // The error code points into the status, which is freed with its Java object: read it while that is alive.
                    val code = status.errorCode()
                    val failed = code.isError
                    val value = code.value
                    val message = code.message
                    TorrentProgress(
                        wantedDone = status.totalWantedDone(),
                        wanted = status.totalWanted(),
                        finished = status.isFinished,
                        error = if (failed) TorrentError(kindOf(value, message), message) else null,
                    )
                } finally {
                    Reference.reachabilityFence(status)
                }
            }

        override fun setPriorities(priorities: List<Int>) =
            live({}) { handle.prioritizeFiles(Array(priorities.size) { Priority.fromSwig(priorities[it]) }) }

        override fun setSequential(on: Boolean) =
            live({}) {
                if (on) handle.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD) else handle.unsetFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)
            }

        override suspend fun renameFiles(names: List<String>): StepResult<Unit> {
            val done = CompletableDeferred<StepResult<Unit>>()
            renames[hash] = Renames(names.size, done)
            try {
                live({ done.complete(STOPPED) }) { names.forEachIndexed { i, name -> handle.renameFile(i, name) } }
                // A rename that libtorrent drops an alert about would otherwise be waited for for ever.
                return withTimeoutOrNull(RENAME_TIMEOUT) { done.await() }
                    ?: StepResult.Failed(ErrorClass.TRANSIENT, "libtorrent didn't finish renaming the files in $RENAME_TIMEOUT")
            } finally {
                renames.remove(hash)
            }
        }

        override suspend fun moveStorage(
            to: Path,
            replaceExisting: Boolean,
        ): StepResult<Unit> {
            val done = CompletableDeferred<StepResult<Unit>>()
            moves[hash] = done
            try {
                withContext(Dispatchers.IO) { File(to.toString()).mkdirs() }
                val flags = if (replaceExisting) MoveFlags.ALWAYS_REPLACE_FILES else MoveFlags.FAIL_IF_EXIST
                live({ done.complete(STOPPED) }) { handle.moveStorage(to.toString(), flags) }
                return done.await()
            } finally {
                moves.remove(hash, done)
            }
        }

        override suspend fun remove() {
            // The files stay: the session is only told to forget the torrent.
            withContext(Dispatchers.IO) { live({}) { if (handle.isValid) manager.remove(handle) } }
        }
    }

    private fun kindOf(
        code: Int,
        message: String,
    ): TorrentErrorKind {
        val text = message.lowercase()
        val full = code == ENOSPC || code == ERROR_DISK_FULL || "no space" in text || "not enough space" in text || "disk full" in text
        return if (full) TorrentErrorKind.DISK_FULL else TorrentErrorKind.OTHER
    }

    override suspend fun close() {
        val stopping =
            lock.withLock {
                closed = true
                manager.also { manager = null }
            } ?: return
        withContext(Dispatchers.IO) {
            val write = native.writeLock()
            write.lock()
            try {
                stopped = true
                stopping.stop()
            } finally {
                write.unlock()
            }
        }
        watch.cancel()
        natives.signals.repair()
        // A finalize still waiting for a move or a rename will never hear of it.
        moves.values.forEach { it.complete(STOPPED) }
        renames.values.forEach { it.done.complete(STOPPED) }
    }
}

private val STOPPED = StepResult.Failed(null, "the torrent engine stopped")

private const val ENOSPC = 28
private const val ERROR_DISK_FULL = 112
private const val ADD_WAIT_TRIES = 500
private const val ADD_WAIT_EVERY_MS = 10L
private const val DROP_TRIES = 100
private const val ALERT_QUEUE_SIZE = 2 * MAX_TORRENT_FILES + 10_000
private val RENAME_TIMEOUT = 2.minutes
private const val SIGNAL_CHECK_MS = 25L
