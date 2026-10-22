package dev.anydm.engine.torrent

import dev.anydm.engine.core.ErrorClass
import dev.anydm.engine.net.ProxyEndpoint
import dev.anydm.engine.net.ProxyRoute
import dev.anydm.engine.net.ProxyScope
import dev.anydm.engine.net.ProxyType
import dev.anydm.engine.net.ProxyUse
import dev.anydm.engine.net.checkedFor
import dev.anydm.engine.net.currentProxyRoute
import dev.anydm.engine.protocol.StepResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.files.Path
import org.libtorrent4j.AddTorrentParams
import org.libtorrent4j.AlertListener
import org.libtorrent4j.AnnounceEntry
import org.libtorrent4j.Entry
import org.libtorrent4j.MoveFlags
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.TcpEndpoint
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentStatus
import org.libtorrent4j.Vectors
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AlertType
import org.libtorrent4j.alerts.FileRenameFailedAlert
import org.libtorrent4j.alerts.FileRenamedAlert
import org.libtorrent4j.alerts.SaveResumeDataAlert
import org.libtorrent4j.alerts.SaveResumeDataFailedAlert
import org.libtorrent4j.alerts.StorageMovedAlert
import org.libtorrent4j.alerts.StorageMovedFailedAlert
import org.libtorrent4j.alerts.TorrentAlert
import org.libtorrent4j.swig.error_code
import org.libtorrent4j.swig.libtorrent
import org.libtorrent4j.swig.settings_pack
import java.io.File
import java.lang.ref.Reference
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

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
    private val proxyLock = Mutex()

    @Volatile private var sessionRoute: ProxyRoute = ProxyRoute.Direct

    // Everyone using the session (a magnet fetch, an added torrent, a seed), each with the route its job asks for as of now. The
    // session has one proxy, so it changes only when all of them ask for the same.
    private val users = ConcurrentHashMap<String, suspend () -> ProxyRoute>()
    private val magnets = AtomicInteger()
    private var manager: SessionManager? = null
    private var closed = false
    private val moves = ConcurrentHashMap<String, CompletableDeferred<StepResult<Unit>>>()
    private val renames = ConcurrentHashMap<String, Renames>()
    private val resumes = ConcurrentHashMap<String, CompletableDeferred<ByteArray?>>()
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
            // The session starts with the proxy its first caller's job asks for, so nothing is sent before it is set (NET-15).
            val route = currentProxyRoute(PROXY_URL, ProxyUse.TORRENT)
            if (route is ProxyRoute.Blocked) return StepResult.Failed(ErrorClass.PROXY, route.reason)
            val port = config.port()
            val started =
                try {
                    withContext(Dispatchers.IO) {
                        SessionManager(false).also {
                            it.addListener(alerts)
                            // Plain reads and writes in place of libtorrent's default memory-mapped files: with the JVM's signal
                            // handlers kept (see SignalGuard), a full disk or a file cut short under a mapping would be a
                            // SIGBUS that ends the process, where a write that fails is an error the engine reports.
                            it.start(SessionParams(settings(port, route)).also { params -> params.swig().set_posix_disk_io_constructor() })
                        }
                    }
                } catch (e: RuntimeException) {
                    return StepResult.Failed(null, "the torrent engine can't start: ${e.message}")
                }
            manager = started
            sessionRoute = route
            // libtorrent takes the JVM's signal handlers over when it first moves data; put them back as soon as it has.
            watch.launch {
                while (isActive) {
                    natives.signals.repair()
                    delay(SIGNAL_CHECK_MS)
                }
            }
            StepResult.Ok(started)
        }

    private fun settings(
        port: Int,
        route: ProxyRoute,
    ): SettingsPack {
        val interfaces = if (config.loopbackOnly) "127.0.0.1:$port" else "0.0.0.0:$port,[::]:$port"
        val pack =
            SettingsPack()
                .listenInterfaces(interfaces)
                .downloadRateLimit(sessionLimit(limitDown))
                .uploadRateLimit(sessionLimit(limitUp))
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
            // A peer dropped on purpose (a proxy change) is tried again in a second, not after libtorrent's minute.
            pack.setInteger(settings_pack.int_types.min_reconnect_time.swigValue(), 1)
        }
        pack.setProxy(route)
        return pack
    }

    /**
     * What the session needs to go through [route] (NET-15), or to stop: a SOCKS5 proxy for peers, trackers and host names,
     * in anonymous mode so nothing is sent around it, and no DHT, local discovery, UPnP or NAT-PMP, which can't be proxied.
     * Going direct again turns them back on, unless the session is loopback only.
     */
    private fun SettingsPack.setProxy(route: ProxyRoute) {
        val pack = this
        // A blocked route sets a SOCKS5 proxy nothing answers at: the session then connects to nothing, which is the point.
        val endpoint = (route as? ProxyRoute.Via)?.endpoint ?: if (route is ProxyRoute.Blocked) DEAD_PROXY else null
        val type =
            when {
                endpoint == null -> settings_pack.proxy_type_t.none
                endpoint.username != null -> settings_pack.proxy_type_t.socks5_pw
                else -> settings_pack.proxy_type_t.socks5
            }
        pack.setInteger(settings_pack.int_types.proxy_type.swigValue(), type.swigValue())
        pack.setString(settings_pack.string_types.proxy_hostname.swigValue(), endpoint?.host.orEmpty())
        pack.setInteger(settings_pack.int_types.proxy_port.swigValue(), endpoint?.port ?: 0)
        pack.setString(settings_pack.string_types.proxy_username.swigValue(), endpoint?.username.orEmpty())
        pack.setString(settings_pack.string_types.proxy_password.swigValue(), endpoint?.password.orEmpty())
        val proxied = endpoint != null
        for (flag in listOf(
            settings_pack.bool_types.proxy_peer_connections,
            settings_pack.bool_types.proxy_tracker_connections,
            settings_pack.bool_types.proxy_hostnames,
            settings_pack.bool_types.anonymous_mode,
        )) {
            pack.setBoolean(flag.swigValue(), proxied)
        }
        for (flag in listOf(
            settings_pack.bool_types.enable_dht,
            settings_pack.bool_types.enable_lsd,
            settings_pack.bool_types.enable_upnp,
            settings_pack.bool_types.enable_natpmp,
        )) {
            pack.setBoolean(flag.swigValue(), !proxied && !config.loopbackOnly)
        }
    }

    /**
     * Registers the caller as a user of the session under [key] and points the session at the proxy its job asks for (NET-15).
     * A session has one proxy for all its torrents, so a caller whose proxy differs from what another user asks for is
     * refused, and nothing connects around it. Users are asked again, so a seed follows a proxy that changed.
     */
    private suspend fun routeSession(
        manager: SessionManager,
        key: String,
    ): StepResult.Failed? {
        val scope = currentCoroutineContext()[ProxyScope]
        val wanted: suspend () -> ProxyRoute = { scope?.freshRoute(PROXY_URL)?.checkedFor(ProxyUse.TORRENT) ?: ProxyRoute.Direct }
        val route = wanted()
        if (route is ProxyRoute.Blocked) return StepResult.Failed(ErrorClass.PROXY, route.reason)
        return proxyLock.withLock {
            users[key] = wanted
            try {
                when {
                    route == sessionRoute -> {
                        null
                    }

                    users.filterKeys { it != key }.values.all { asked(it) == route } -> {
                        applyRoute(manager, route)
                        null
                    }

                    else -> {
                        users.remove(key)
                        StepResult.Failed(ErrorClass.PROXY, "torrents share one proxy, and another torrent is using a different one")
                    }
                }
            } catch (e: Throwable) {
                // A cancel here (a proxy change restarts the transfer that is routing) must not leave the caller registered.
                users.remove(key)
                throw e
            }
        }
    }

    // What a user asks for; a user whose settings can't be read asks for nothing usable, not for an error to escape.
    private suspend fun asked(user: suspend () -> ProxyRoute): ProxyRoute =
        try {
            user()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ProxyRoute.Blocked("the proxy settings can't be read")
        }

    /**
     * A user is gone. If the session was closed for want of a usable proxy and the one who needed it was this user, the others
     * are asked again so the session opens for them.
     */
    private suspend fun release(key: String) {
        users.remove(key)
        if (sessionRoute is ProxyRoute.Blocked) withContext(NonCancellable) { reroute() }
    }

    private suspend fun applyRoute(
        manager: SessionManager,
        route: ProxyRoute,
    ) {
        // libtorrent drops the peers it has when the proxy settings change (the test for it is in LibTorrentBackendTest), so
        // they come back through the new route.
        withContext(Dispatchers.IO) { live({}) { manager.applySettings(SettingsPack().also { it.setProxy(route) }) } }
        sessionRoute = route
    }

    override suspend fun reroute() {
        val session = manager ?: return
        proxyLock.withLock {
            val wants = users.values.map { asked(it) }
            // One user whose proxy can't be used closes the session for all: a torrent can't be kept off it any other way.
            val route = wants.firstOrNull { it is ProxyRoute.Blocked } ?: wants.distinct().singleOrNull() ?: return@withLock
            if (route != sessionRoute) applyRoute(session, route)
        }
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
                    AlertType.SAVE_RESUME_DATA.swig(),
                    AlertType.SAVE_RESUME_DATA_FAILED.swig(),
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

                    is SaveResumeDataAlert -> {
                        resumes[hash]?.complete(AddTorrentParams.writeResumeDataBuf(alert.params()))
                    }

                    is SaveResumeDataFailedAlert -> {
                        resumes[hash]?.complete(null)
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
        val key = "magnet:${magnets.incrementAndGet()}"
        val withHints = config.peerHints.fold(uri) { link, peer -> "$link&x.pe=$peer" }
        val seconds = timeout.inWholeSeconds.coerceAtLeast(1).toInt()
        val scratch = Files.createTempDirectory("anydm-magnet-").toFile()
        try {
            routeSession(manager, key)?.let { return it }
            val bytes =
                try {
                    runInterruptible(Dispatchers.IO) { manager.fetchMagnet(withHints, seconds, scratch) }
                } catch (e: RuntimeException) {
                    return StepResult.Failed(null, "not a usable magnet link: ${e.message}")
                } ?: return StepResult.Failed(ErrorClass.TRANSIENT, "no metadata for the magnet link in $seconds s")
            return metadataOfBytes(bytes)
        } finally {
            release(key)
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
        val key = info.infoHash().toHex()
        var handedOver = false
        try {
            routeSession(manager, key)?.let { return it }
            return addRouted(request, manager, info, key).also { handedOver = it is StepResult.Ok }
        } finally {
            // Leaving without a running torrent (a failure, or a cancel that came before the torrent did) makes the caller no user.
            if (!handedOver) release(key)
        }
    }

    private suspend fun addRouted(
        request: AddTorrent,
        manager: SessionManager,
        info: org.libtorrent4j.TorrentInfo,
        key: String,
    ): StepResult<RunningTorrent> {
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
                    val resumed = request.resume?.let { readResume(it, hash.toHex()) }
                    if (resumed == null) {
                        manager.download(info, saveTo, null, priorities, peers, flags)
                    } else {
                        // The blob knows which pieces are good; the job's current choices replace what it saved of those.
                        resumed.torrentInfo = info
                        resumed.savePath = saveTo.absolutePath
                        if (priorities != null) resumed.filePriorities(priorities)
                        resumed.flags = flags
                        resumed.peers(peers)
                        manager.swig().async_add_torrent(resumed.swig())
                    }
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
            // libtorrent4j's session doesn't read the trackers out of the .torrent: give it the ones it doesn't have (a resume
            // blob may already carry them).
            val known = readTrackers(handle).map { it.url }.toSet()
            for ((url, tier) in trackersOf(request.info.metadata)) {
                if (url !in known) handle.addTracker(AnnounceEntry(url).also { it.tier(tier.toShort()) })
            }
            request.fileNames.forEachIndexed { i, name -> if (name != torrentFiles[i].path) handle.renameFile(i, name) }
            handle.resume()
        }
        return StepResult.Ok(Running(manager, handle, hash.toHex()))
    }

    /** The parameters saved in [blob], or null when it can't be read or is for another torrent than [hash]. */
    private fun readResume(
        blob: ByteArray,
        hash: String,
    ): AddTorrentParams? =
        try {
            val code = error_code()
            val params = AddTorrentParams(libtorrent.read_resume_data_ex(Vectors.bytes2byte_vector(blob), code))
            if (code.value() == 0 && params.infoHashes.best.toHex() == hash) params else null
        } catch (e: RuntimeException) {
            null
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
                release(hash.toHex())
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

    /** For tests: how many magnet fetches, torrents and seeds are registered as users of the session. */
    internal fun userCount(): Int = users.size

    /** For tests: the proxy route the session is set to. */
    internal fun sessionRoute(): ProxyRoute = sessionRoute

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

        /** What it had uploaded before this add (from resume data): libtorrent's own all-time counter only catches up when a peer leaves. */
        private val uploadedBefore: Long =
            live({ 0L }) {
                if (!handle.isValid) return@live 0L
                val status = handle.status()
                try {
                    status.allTimeUpload()
                } finally {
                    Reference.reachabilityFence(status)
                }
            }

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
            release(hash)
            withContext(Dispatchers.IO) { live({}) { if (handle.isValid) manager.remove(handle) } }
        }

        override fun stats(): TorrentStats {
            val none = TorrentStats(0, 0, 0, 0, 0, false)
            return live({ none }) {
                if (!handle.isValid) return@live none
                val status = handle.status()
                try {
                    TorrentStats(
                        payloadDown = status.totalPayloadDownload(),
                        payloadUp = status.totalPayloadUpload(),
                        allTimeUp = uploadedBefore + status.totalPayloadUpload(),
                        wanted = status.totalWanted(),
                        // Since it finished, not libtorrent's seeding time: that one only runs for a torrent with every file.
                        seedingSeconds = status.finishedDuration(),
                        seeding = status.isSeeding,
                    )
                } finally {
                    Reference.reachabilityFence(status)
                }
            }
        }

        override suspend fun saveResume(): ByteArray? = requestResume(handle, hash)

        // Removing is asynchronous: the handle can still look valid for a moment and then refuse a call.
        override fun live(): TorrentLiveStats =
            try {
                readLive()
            } catch (e: RuntimeException) {
                TorrentLiveStats.None
            }

        override fun detail(): TorrentDetail =
            try {
                readDetail()
            } catch (e: RuntimeException) {
                TorrentDetail.Empty
            }

        private fun readLive(): TorrentLiveStats =
            live({ TorrentLiveStats.None }) {
                if (!handle.isValid) return@live TorrentLiveStats.None
                val status = handle.status(TorrentHandle.QUERY_DISTRIBUTED_COPIES)
                try {
                    TorrentLiveStats(
                        peers = status.numPeers(),
                        seeds = status.numSeeds(),
                        availability = status.distributedCopies().toDouble().coerceAtLeast(0.0),
                        downloadRate = status.downloadPayloadRate().toLong(),
                        uploadRate = status.uploadPayloadRate().toLong(),
                        // By whole pieces: bytes of a piece still being fetched, or of a skipped file's share of one, are not done.
                        fileDone = handle.fileProgress(TorrentHandle.PIECE_GRANULARITY).toList(),
                        checking =
                            status.state().let {
                                it == TorrentStatus.State.CHECKING_FILES ||
                                    it == TorrentStatus.State.CHECKING_RESUME_DATA
                            },
                    )
                } finally {
                    Reference.reachabilityFence(status)
                }
            }

        private fun readDetail(): TorrentDetail =
            live({ TorrentDetail.Empty }) {
                if (!handle.isValid) return@live TorrentDetail.Empty
                val status = handle.status(TorrentHandle.QUERY_PIECES)
                try {
                    val bits = status.pieces()
                    val count = bits.size()
                    val have = ByteArray((count + 7) / 8)
                    for (i in 0 until count) if (bits.getBit(i)) have[i / 8] = (have[i / 8].toInt() or (1 shl (i % 8))).toByte()
                    val current = status.currentTracker()
                    val peers =
                        handle
                            .peerInfo()
                            .map {
                                TorrentPeer(
                                    address = it.ip(),
                                    client = it.client(),
                                    progress = it.progressPpm() / PPM,
                                    downloadRate = it.downSpeed().toLong(),
                                    uploadRate = it.upSpeed().toLong(),
                                    downloaded = it.totalDownload(),
                                    uploaded = it.totalUpload(),
                                    seed = it.progressPpm() >= PPM_FULL,
                                )
                            }.sortedByDescending { it.downloadRate + it.uploadRate }
                            .take(MAX_DETAIL_PEERS)
                    val trackers = readTrackers(handle).map { TorrentTracker(it.url, it.tier, it.verified, it.enabled, it.url == current) }
                    TorrentDetail(peers, trackers, PieceMap(count, have))
                } finally {
                    Reference.reachabilityFence(status)
                }
            }

        override fun setDownloadLimit(bytesPerSecond: Long?) = live({}) { handle.setDownloadLimit(perTorrentLimit(bytesPerSecond)) }
    }

    /** Asks libtorrent for [handle]'s resume data and waits for the alert that carries it. */
    private suspend fun requestResume(
        handle: TorrentHandle,
        hash: String,
    ): ByteArray? {
        val done = CompletableDeferred<ByteArray?>()
        resumes[hash] = done
        try {
            live(
                { done.complete(null) },
            ) { if (handle.isValid) handle.saveResumeData(TorrentHandle.FLUSH_DISK_CACHE) else done.complete(null) }
            return withTimeoutOrNull(RESUME_TIMEOUT) { done.await() }
        } finally {
            resumes.remove(hash, done)
        }
    }

    @Volatile
    private var limitDown: Long? = null

    @Volatile
    private var limitUp: Long? = null

    override fun setSessionLimits(
        download: Long?,
        upload: Long?,
    ) {
        limitDown = download
        limitUp = upload
        val session = manager ?: return
        live({}) { session.applySettings(SettingsPack().downloadRateLimit(sessionLimit(download)).uploadRateLimit(sessionLimit(upload))) }
    }

    /**
     * The download and upload limits the running session holds (0: none), or null before it starts. libtorrent does not
     * apply a session limit to peers on the local network, so a test cannot see the limit in a transfer's speed.
     */
    internal fun appliedSessionLimits(): Pair<Int, Int>? {
        val session = manager ?: return null
        return live({ null }) {
            val pack = session.settings()
            try {
                pack.downloadRateLimit() to pack.uploadRateLimit()
            } finally {
                Reference.reachabilityFence(pack)
            }
        }
    }

    override var resumeSink: ((infoHash: String, bytes: ByteArray) -> Unit)? = null

    /** The resume data of every torrent in the session, handed to [resumeSink]; waits for them all for at most [RESUME_TIMEOUT]. */
    private suspend fun saveAllResume(session: SessionManager) {
        val sink = resumeSink ?: return
        val handles =
            live({ emptyList() }) {
                val all = session.swig().get_torrents()
                (0 until all.size).map { i -> TorrentHandle(all[i]).let { it to it.infoHash().toHex() } }
            }
        withTimeoutOrNull(RESUME_TIMEOUT) {
            handles.map { (handle, hash) -> async { requestResume(handle, hash)?.let { sink(hash, it) } } }.awaitAll()
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
        // Everything still in the session is saved while the library can still answer.
        saveAllResume(stopping)
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

// libtorrent: 0 is no limit for the session, -1 for a torrent; a limit is an Int of bytes per second.
private fun sessionLimit(bytesPerSecond: Long?): Int = bytesPerSecond?.coerceIn(1, Int.MAX_VALUE.toLong())?.toInt() ?: 0

private fun perTorrentLimit(bytesPerSecond: Long?): Int = bytesPerSecond?.coerceIn(1, Int.MAX_VALUE.toLong())?.toInt() ?: -1

private val RESUME_TIMEOUT = 10.seconds

private class TrackerRead(
    val url: String,
    val tier: Int,
    val verified: Boolean,
    val enabled: Boolean,
)

/**
 * The trackers [handle] has. libtorrent4j's own `trackers()` wraps entries that live in a vector nothing keeps alive; the
 * vector is read here, inside a fence (the rule for every native object we read).
 */
private fun readTrackers(handle: TorrentHandle): List<TrackerRead> {
    val all = handle.swig().trackers()
    try {
        return (0 until all.size).map { i ->
            val entry = all[i]
            val endpoints = entry.endpoints
            TrackerRead(entry.url, entry.tier.toInt(), entry.verified, (0 until endpoints.size).any { endpoints[it].enabled })
        }
    } finally {
        Reference.reachabilityFence(all)
    }
}

/** The trackers a `.torrent` names with their tiers: its `announce-list`, else its `announce`; none for a file that has neither. */
private fun trackersOf(torrent: ByteArray): List<Pair<String, Int>> =
    try {
        val root = Entry.bdecode(torrent).dictionary()
        val tiers = root["announce-list"]?.list().orEmpty()
        if (tiers.isNotEmpty()) {
            tiers.flatMapIndexed { tier, urls -> urls.list().map { it.string() to tier } }
        } else {
            listOfNotNull(root["announce"]?.string()?.let { it to 0 })
        }
    } catch (e: RuntimeException) {
        emptyList()
    }

// libtorrent reports a peer's progress in parts per million.
private const val PPM_FULL = 1_000_000
private const val PPM = 1_000_000.0
private const val ENOSPC = 28
private const val ERROR_DISK_FULL = 112
private const val ADD_WAIT_TRIES = 500
private const val ADD_WAIT_EVERY_MS = 10L
private const val DROP_TRIES = 100
private const val ALERT_QUEUE_SIZE = 2 * MAX_TORRENT_FILES + 10_000
private val RENAME_TIMEOUT = 2.minutes
private const val SIGNAL_CHECK_MS = 25L

// What the router is asked about for a torrent: it has no host of its own, so no bypass applies.
private const val PROXY_URL = "magnet:"

// Where a blocked session points its proxy: nothing answers there.
private val DEAD_PROXY = ProxyEndpoint(ProxyType.SOCKS5, "127.0.0.1", 1)
