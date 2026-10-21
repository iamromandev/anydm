package dev.anydm.engine

import dev.anydm.engine.core.Attempt
import dev.anydm.engine.core.Checksum
import dev.anydm.engine.core.Download
import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.DownloadStatus
import dev.anydm.engine.core.DuplicateCheck
import dev.anydm.engine.core.FinishHook
import dev.anydm.engine.core.ItemSelection
import dev.anydm.engine.core.Mirror
import dev.anydm.engine.core.MirrorId
import dev.anydm.engine.core.PauseReason
import dev.anydm.engine.core.ProfileId
import dev.anydm.engine.core.RetryPolicy
import dev.anydm.engine.core.ScheduleRuleId
import dev.anydm.engine.core.StepRunner
import dev.anydm.engine.core.TransitionResult
import dev.anydm.engine.files.CollectionFinishHook
import dev.anydm.engine.files.DiskGuard
import dev.anydm.engine.files.FilesDuplicateCheck
import dev.anydm.engine.files.FilesPlacement
import dev.anydm.engine.files.HistoryRecorder
import dev.anydm.engine.files.NameCleaner
import dev.anydm.engine.files.NewProfile
import dev.anydm.engine.files.OsProfile
import dev.anydm.engine.files.Profile
import dev.anydm.engine.files.ProfileResult
import dev.anydm.engine.ftp.CredentialWriter
import dev.anydm.engine.ftp.FtpUrl
import dev.anydm.engine.ftp.SshKeyLogin
import dev.anydm.engine.ftp.stripUserInfo
import dev.anydm.engine.http.originOf
import dev.anydm.engine.http.userInfoOf
import dev.anydm.engine.log.Log
import dev.anydm.engine.log.LogLevel
import dev.anydm.engine.media.LISTING_CAP
import dev.anydm.engine.media.PlaylistEntry
import dev.anydm.engine.media.PlaylistListing
import dev.anydm.engine.media.PlaylistParse
import dev.anydm.engine.media.PlaylistSource
import dev.anydm.engine.net.AfterQueueAction
import dev.anydm.engine.net.AfterQueueKeeper
import dev.anydm.engine.net.AfterQueueState
import dev.anydm.engine.net.AwakeKeeper
import dev.anydm.engine.net.MeteredRefresh
import dev.anydm.engine.net.NetworkIdentity
import dev.anydm.engine.net.NetworkMonitor
import dev.anydm.engine.net.NetworkPolicy
import dev.anydm.engine.net.NetworkRules
import dev.anydm.engine.net.NetworkState
import dev.anydm.engine.net.NetworkStep
import dev.anydm.engine.net.NetworkWatch
import dev.anydm.engine.net.NewScheduleRule
import dev.anydm.engine.net.OnlineProbe
import dev.anydm.engine.net.QueueActivity
import dev.anydm.engine.net.RuleKeeper
import dev.anydm.engine.net.RuleStatus
import dev.anydm.engine.net.ScheduleRule
import dev.anydm.engine.net.SlowMode
import dev.anydm.engine.net.TrafficMeter
import dev.anydm.engine.net.WakeDetector
import dev.anydm.engine.net.epochMinute
import dev.anydm.engine.platform.FileSystem
import dev.anydm.engine.platform.KeepAwake
import dev.anydm.engine.platform.MeteredDetector
import dev.anydm.engine.platform.PowerActions
import dev.anydm.engine.protocol.Protocol
import dev.anydm.engine.protocol.StepResult
import dev.anydm.engine.queue.CommandResult
import dev.anydm.engine.queue.Queue
import dev.anydm.engine.queue.QueueObserver
import dev.anydm.engine.queue.RemoveFiles
import dev.anydm.engine.store.NewDownload
import dev.anydm.engine.store.NewItem
import dev.anydm.engine.store.NewTorrent
import dev.anydm.engine.store.Store
import dev.anydm.engine.store.TrafficStore
import dev.anydm.engine.tools.Tool
import dev.anydm.engine.tools.ToolManager
import dev.anydm.engine.torrent.MAX_FILE_PRIORITY
import dev.anydm.engine.torrent.SeedEnd
import dev.anydm.engine.torrent.SeedRule
import dev.anydm.engine.torrent.TorrentInfo
import dev.anydm.engine.torrent.TorrentService
import dev.anydm.engine.torrent.TorrentSource
import dev.anydm.engine.torrent.prioritiesFor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.io.files.Path
import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.TimeSource

/**
 * anydm's engine (FND-11, FND-15): one object a UI, or nothing at all, creates with a [Store], starts, commands, observes
 * and stops. It owns its coroutines, the queue and the step runner, and closes the store in [stop]. No window is
 * involved: the desktop app (and later Android's foreground service) only holds and observes it.
 *
 * Snapshots ([downloads]) are the truth about every job; [events] are moments to react to, not replayed. The queue
 * reports what changes through a [QueueObserver]; the engine applies those facts on one coroutine at a time, so a
 * snapshot list is always one consistent picture.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class Engine(
    private val store: Store,
    fileSystem: FileSystem,
    protocols: Map<DownloadKind, Protocol>,
    private val defaultFolder: Path,
    private val clock: Clock = Clock.System,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val stopTimeout: Duration = 10.seconds,
    checkpointEvery: Duration = StepRunner.DEFAULT_CHECKPOINT_EVERY,
    retryPolicy: RetryPolicy = RetryPolicy(),
    private val log: Log = Log.None,
    /** Which network bytes arrive on (NET-04); the desktop passes DefaultRouteNetwork. */
    private val identity: NetworkIdentity = NetworkIdentity.Unknown,
    /** Where traffic is saved; the store's, replaceable in tests. */
    private val traffic: TrafficStore = store.traffic,
    /** Whose file-name rules names follow (FIL-03); the desktop passes OsProfile.current(). Windows' are the strictest. */
    osProfile: OsProfile = OsProfile.WINDOWS,
    /** Asked on every add and after every probe (FIL-05); replaceable in tests. */
    duplicates: DuplicateCheck = FilesDuplicateCheck(store.downloads, store.history, fileSystem),
    /** Told when a job finishes; by default the history (FIL-10) and, for a collection's item, its archive and playlist file (MED-17). */
    finish: FinishHook? = null,
    /** Fetches the runtime tools jobs wait for (BIN-01); without one, a job that needs a missing tool stays paused. */
    private val tools: ToolManager? = null,
    /** Lists a playlist or channel for [addPlaylist] (MED-16); without one, a playlist can't be added. */
    private val playlists: PlaylistSource? = null,
    /** Reads a torrent before it is added and changes one that is downloading (TOR-02); without one, only a whole torrent can be added. */
    private val torrents: TorrentService? = null,
    /** Takes in the logins found in FTP and HTTP URLs when a job is added (FTP-06); without one, URLs keep their logins. */
    private val credentials: CredentialWriter? = null,
    /** The zone days, weeks and schedule rules are read in (NET-05..08), asked at every use; the desktop passes the system's. */
    private val timeZone: () -> TimeZone = TimeZone.Companion::currentSystemDefault,
    /** Whether this machine has a route to the internet (NET-13), polled every few seconds; the desktop passes DefaultRouteProbe. */
    onlineProbe: OnlineProbe = OnlineProbe.Always,
    /** Whether the current network is metered (NET-14); without one only the user's own flag counts. */
    meteredDetector: MeteredDetector = MeteredDetector.None,
    /** Keeps the machine from sleeping while a job downloads (NET-11); the desktop passes keepAwakeForCurrentOs. */
    keepAwake: KeepAwake = KeepAwake.None,
    /** Sleeps or shuts the machine down when the queue is finished (NET-10); the desktop passes powerActionsForCurrentOs. */
    powerActions: PowerActions = PowerActions.None,
    /** Where elapsed time is read to notice that the machine slept (NET-12); tests pass the test scheduler's. */
    monotonic: TimeSource = TimeSource.Monotonic,
    /** How much longer than its 5 s tick the machine must have been away to count as woken (NET-12). */
    wakeGap: Duration = 30.seconds,
) {
    private val nameCleaner = NameCleaner(osProfile)

    // Each protocol once, however many kinds share it.
    private val protocolList = protocols.values.distinct()

    private val stateFlow = MutableStateFlow<EngineState>(EngineState.NotStarted)
    private val snapshots = MutableStateFlow<List<DownloadSnapshot>>(emptyList())
    private val speedFlow = MutableStateFlow(SpeedWindow.Empty)
    private val eventFlow = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    val state: StateFlow<EngineState> = stateFlow.asStateFlow()
    val downloads: StateFlow<List<DownloadSnapshot>> = snapshots.asStateFlow()
    val totalSpeed: StateFlow<SpeedWindow> = speedFlow.asStateFlow()
    val events: SharedFlow<EngineEvent> = eventFlow.asSharedFlow()

    private val engineJob = SupervisorJob()
    private val scope = CoroutineScope(dispatcher + engineJob + CoroutineExceptionHandler { _, e -> failed(e) })

    // The queue's own job: stop() cancels it and waits for it, and with it for every slot's last checkpoint save.
    private val queueJob = SupervisorJob(engineJob)

    // Facts are applied here, one at a time.
    private val publisher = dispatcher.limitedParallelism(1)
    private val facts = Channel<Fact>(Channel.UNLIMITED)
    private val cache = SnapshotCache()

    // Jobs holding a slot, written from slots and the queue at once.
    private val inSlots = MutableStateFlow<Set<DownloadId>>(emptySet())

    // What each running job's slot reports, written from slots and read by the publisher: no lock needed.
    private class Live {
        val progress = MutableStateFlow<Pair<Long, Long?>?>(null)
        val bytes = MutableStateFlow(0L)

        /** Bytes the run sent since the last sampled second (TOR-09). */
        val bytesUp = MutableStateFlow(0L)

        /** Its kind once known, kept so a job removed mid-run still counts under it (NET-04). */
        @Volatile var kind: DownloadKind? = null
    }

    // Traffic (NET-04): counted once a second on the publisher; bytes a run got after its last second wait in leftovers.
    private val meter = TrafficMeter { it.toLocalDateTime(timeZone()).date.toString() }
    private val leftovers = MutableStateFlow<List<TrafficMeter.Sample>>(emptyList())

    private val live = MutableStateFlow<Map<DownloadId, Live>>(emptyMap())

    // The publisher's own: what runs, what was last published, and the loops that run while anything does.
    private val running = mutableSetOf<DownloadId>()
    private val published = mutableMapOf<DownloadId, Pair<Long, Long?>>()
    private val totalSamples = ArrayDeque<Long>()
    private var loops: Job? = null
    private val diskGuard = DiskGuard(fileSystem) { store.settings.load().freeSpaceReserve }
    private var lowFolders = emptySet<Path>()

    private val runner =
        StepRunner(
            fileSystem,
            store.downloads,
            protocols,
            defaultFolder,
            reserve = { store.settings.load().freeSpaceReserve },
            duplicates = duplicates,
            finish =
                toolAware(
                    finish
                        ?: CollectionFinishHook(
                            store.collectionItems,
                            store.downloads,
                            fileSystem,
                            { store.settings.load().playlistFile },
                            HistoryRecorder(store.history, store.downloads, clock) { store.settings.load().historyLimit },
                        ),
                ),
            placement =
                FilesPlacement(
                    NameCleaner(osProfile),
                    categories = { store.categories.list() },
                    policy = { store.settings.load().clashPolicy },
                    fileSystem = fileSystem,
                    profiles = { store.profiles.get(it) },
                ),
            checkpointEvery = checkpointEvery,
            profiles = { store.profiles.get(it) },
        )

    /** What the queue reports to; internal so tests can play orders the queue only produces under load. */
    internal val observer: QueueObserver = Observer()

    // The network (NET-13): polled on the publisher; the queue asks it for an immediate answer after a connection error.
    private val monitor = NetworkMonitor(onlineProbe, identity, clock, log)

    // On the publisher: the keepers and refreshes that are not thread-safe on their own.
    private val publisherScope = CoroutineScope(scope.coroutineContext + publisher)
    private val meteredRefresh = MeteredRefresh(meteredDetector, monitor, clock, log, publisherScope)

    // Wake (NET-12), keep awake (NET-11) and the after-queue action (NET-10): all on the publisher.
    private val wake = WakeDetector(WAKE_TICK, wakeGap)
    private val monoStart = monotonic.markNow()
    private val hold = keepAwake
    private val awake = AwakeKeeper(hold, publisherScope)
    private val afterQueueKeeper =
        AfterQueueKeeper(
            publisherScope,
            clock,
            log,
            save = { action -> store.settings.update { it.copy(afterQueue = action) } },
            busyNow = { QueueActivity.busy(store.downloads.list()) },
            flush = { flushTraffic() },
            power = powerActions,
            emit = ::tell,
        )

    // Set on the publisher once the keepers have their first reading; a command before then is stored for start().
    private var keepersStarted = false

    private val queue =
        Queue(
            CoroutineScope(scope.coroutineContext + queueJob),
            store,
            runner,
            protocols,
            fileSystem,
            clock,
            retryPolicy,
            observer,
            log,
            duplicates = duplicates,
            profiles = { store.profiles.get(it) },
            networkCheck = { monitor.pollNow().online },
        )

    // Network rules (#543): evaluated on the publisher, pushed to the queue.
    private val keeper =
        RuleKeeper(
            store.rules,
            store.settings,
            traffic,
            meter,
            identity,
            clock,
            timeZone,
            log,
            leftovers = { leftovers.value.sumOf { it.bytes + it.bytesUp } },
            emit = ::tell,
            apply = { queue.applyRules(it) },
            detectedMetered = { monitor.state.value.metered == true },
        )
    private var ruleTimer: Job? = null
    private var ruleTimerAt: Instant? = null

    // A return whose rules could not be read: its release waits for a check that can (on the publisher).
    private var releasePending = false

    // The last rule check failed: a check the bytes trigger waits for the timer, and the recovery is logged once (on the publisher).
    private var rulesFailing = false

    /** What the network rules decide now: the action, its causes, caps, allowance and next change; [RuleStatus.None] until [start]. */
    val rules: StateFlow<RuleStatus> = keeper.status

    /** The network now (NET-13, NET-14): online, its key, what the detector says of it; [NetworkState.Unknown] until [start]. */
    val network: StateFlow<NetworkState> = monitor.state

    /** The armed after-queue action and, while its 30 s countdown runs, when it ends (NET-10); idle until [start]. */
    val afterQueue: StateFlow<AfterQueueState> = afterQueueKeeper.state

    // Logs an event and tells it at once.
    private fun tell(event: EngineEvent) {
        logEvent(event)
        eventFlow.tryEmit(event)
    }

    private sealed interface Fact {
        data class Changed(
            val download: Download,
        ) : Fact

        data class Removed(
            val id: DownloadId,
        ) : Fact

        data class AttemptChanged(
            val attempt: Attempt,
        ) : Fact

        data class MirrorsChanged(
            val id: DownloadId,
            val mirrors: List<Mirror>,
        ) : Fact

        data class Event(
            val event: EngineEvent,
        ) : Fact

        data class Checkpointed(
            val id: DownloadId,
        ) : Fact

        data class SlotStarted(
            val id: DownloadId,
        ) : Fact

        data class SlotEnded(
            val id: DownloadId,
        ) : Fact
    }

    private inner class Observer : QueueObserver {
        override fun jobChanged(download: Download) = send(Fact.Changed(download))

        override fun jobRemoved(id: DownloadId) = send(Fact.Removed(id))

        override fun attemptChanged(attempt: Attempt) = send(Fact.AttemptChanged(attempt))

        override fun mirrorsChanged(
            id: DownloadId,
            mirrors: List<Mirror>,
        ) = send(Fact.MirrorsChanged(id, mirrors))

        override fun event(event: EngineEvent) = send(Fact.Event(event))

        override fun checkpointed(id: DownloadId) = send(Fact.Checkpointed(id))

        override fun progress(
            id: DownloadId,
            downloaded: Long,
            total: Long?,
        ) {
            live.value[id]?.progress?.value = downloaded to total
        }

        override fun received(
            id: DownloadId,
            bytes: Long,
        ) {
            live.value[id]?.bytes?.update { it + bytes }
        }

        override fun sent(
            id: DownloadId,
            bytes: Long,
        ) {
            live.value[id]?.bytesUp?.update { it + bytes }
        }

        override fun slotStarted(id: DownloadId) {
            live.update { it + (id to Live()) }
            inSlots.update { it + id }
            send(Fact.SlotStarted(id))
        }

        // The holder goes now, not when the fact is applied: a new slot for the job may start before then.
        override fun slotEnded(id: DownloadId) {
            live.value[id]?.let(::keepLeftover)
            live.update { it - id }
            inSlots.update { it - id }
            send(Fact.SlotEnded(id))
        }

        private fun send(fact: Fact) {
            facts.trySend(fact)
        }
    }

    /** Loads the store, starts the queue and publishes every job. Once only. */
    suspend fun start() {
        check(stateFlow.compareAndSet(EngineState.NotStarted, EngineState.Running)) { "the engine was already started" }
        // On the engine's scope, not the caller's: a caller cancelled half way must not leave the queue running with no
        // snapshots. A failure here fails the engine.
        val startup =
            scope.async {
                if (store.settings.load().debugLogging) log.minLevel = LogLevel.DEBUG
                // The network and the rules are known before any job can start: the queue starts with their effect, and
                // offline it starts with every job held (NET-13). The watch starts from the same reading as the queue.
                val (watch, offline) =
                    withContext(publisher) {
                        meteredRefresh.first(monitor.first())
                        val seen = monitor.observed.value
                        NetworkWatch(seen) to !seen.state.online
                    }
                val initial = withContext(publisher) { keeper.start() }
                torrents?.let { service ->
                    // Subscribed before the queue starts: a seed taken up again may end at once.
                    scope.launch(start = CoroutineStart.UNDISPATCHED) { service.seedEnds.collect { finishSeed(it) } }
                    scope.launch(publisher) {
                        while (true) {
                            delay(SPEED_EVERY)
                            sampleSeeds(service)
                            applyTorrentLive(service)
                        }
                    }
                }
                queue.start(initial, offline)
                val jobs = store.downloads.list()
                log.info("engine") { "engine started (${jobs.size} jobs)" }
                val attempts = jobs.associate { it.id to store.downloads.attempts(it.id) }
                val mirrors = jobs.associate { it.id to store.downloads.mirrors(it.id) }
                val files = jobs.associate { it.id to filesOf(it.id) }
                withContext(publisher) {
                    cache.load(jobs, attempts, mirrors, files)
                    publish()
                    // The keepers read what is there now: an armed action waits for work to start and end, so a start
                    // never fires it.
                    val settings = store.settings.load()
                    afterQueueKeeper.start(settings.afterQueue)
                    awake.setEnabled(settings.keepAwake)
                    onActivity()
                    keepersStarted = true
                }
                scope.launch(publisher) {
                    for (fact in facts) {
                        // Every fact waiting is applied, then one snapshot list is built: a collection added at once sends
                        // thousands, and each used to rebuild every snapshot.
                        val events = mutableListOf<Fact.Event>()
                        var next: Fact? = fact
                        var batch = 0
                        var moved = false
                        while (next != null) {
                            if (!moved) moved = changesActivity(next)
                            apply(next)?.let { events += it }
                            next = if (++batch < MAX_BATCH) facts.tryReceive().getOrNull() else null
                        }
                        publish()
                        if (moved) onActivity()
                        events.forEach { afterPublished(it) }
                    }
                }
                scope.launch(publisher) {
                    while (true) {
                        delay(TRAFFIC_SAVE_EVERY)
                        flushTraffic()
                    }
                }
                withContext(publisher) { armRuleTimer() }
                scope.launch(publisher) { monitor.observed.collect { seen -> watch.next(seen).forEach { onNetworkStepQuietly(it) } } }
                scope.launch(publisher) { every(NET_POLL) { networkTick() } }
                scope.launch(publisher) { every(WAKE_TICK) { wakeTick() } }
                scope.launch {
                    while (true) {
                        delay(DISK_CHECK_EVERY)
                        checkDisk()
                    }
                }
                recoverTools()
            }
        try {
            startup.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            failed(e)
            throw e
        }
    }

    /**
     * Stops every transfer, waits up to stopTimeout for each job to save its last checkpoint, and closes the store. A
     * job that didn't finish saving resumes from its previous checkpoint (StopTimedOut names it). Does nothing if the
     * engine already stopped or failed; an engine is never started again.
     */
    suspend fun stop() {
        when (val now = stateFlow.value) {
            EngineState.Stopped, EngineState.Stopping, is EngineState.Failed -> {
                return
            }

            EngineState.NotStarted -> {
                if (!stateFlow.compareAndSet(now, EngineState.Stopping)) return stop()
                queue.abandon(IllegalStateException("the engine was stopped before it started"))
            }

            EngineState.Running -> {
                if (!stateFlow.compareAndSet(now, EngineState.Stopping)) return stop()
                // The hold goes first: a stop that times out below still lets the machine sleep again.
                withContext(NonCancellable) {
                    withTimeoutOrNull(stopTimeout) {
                        withContext(publisher) {
                            releasePending = false
                            awake.close()
                        }
                    }
                }
                queueJob.cancel()
                val ended = withTimeoutOrNull(stopTimeout) { queueJob.join() }
                if (ended == null) {
                    val still = inSlots.value.sortedBy { it.value }
                    log.error("engine") { "stop timed out, still saving: ${still.joinToString { it.value.toString() }}" }
                    eventFlow.tryEmit(EngineEvent.StopTimedOut(still))
                }
            }
        }
        // The queue has stopped: what the protocols hold (a torrent session) can go. One that fails to close does not stop this.
        for (protocol in protocolList) {
            try {
                protocol.close()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("engine") { "a protocol didn't close: ${e.message}" }
            }
        }
        engineJob.cancel()
        // The publisher may be reading the store: let it finish before the store closes.
        withTimeoutOrNull(stopTimeout) { engineJob.join() }
        saveLastTraffic()
        runCatching { store.close() }
        stateFlow.value = EngineState.Stopped
        log.info("engine") { "engine stopped" }
    }

    suspend fun add(new: NewDownload): CommandResult = command { queue.add(withoutLogins(new)) }

    /** Saves an SFTP key login under [url]'s origin (FTP-04); false when it only reached memory, or the engine has no keychain. */
    suspend fun saveKeyLogin(
        url: String,
        login: SshKeyLogin,
    ): Boolean {
        val writer = credentials ?: return false
        return command { writer.saveKey(originFor(url), login) }
    }

    /** Stops trusting the SSH key of [host]:[port] (FTP-04), after the user has confirmed it changed; true if there was one. */
    suspend fun forgetHostKey(
        host: String,
        port: Int,
    ): Boolean = command { store.hostKeys.forget(host, port) }

    // Where a login for [url] is kept: FTP's origin spells the port out for every scheme, HTTP's only for http and https.
    private fun originFor(url: String): String = FtpUrl.parse(url)?.origin ?: originOf(url)

    // A job's FTP and HTTP URLs without the user:password in them: the login goes to the keychain, and the rows never hold it.
    private suspend fun withoutLogins(new: NewDownload): NewDownload {
        val writer = credentials ?: return new
        if (new.kind != DownloadKind.FTP && new.kind != DownloadKind.DIRECT) return new
        val mirrors = new.mirrors.map { moveLogin(writer, it) }
        val source = new.sourceUrl?.let { moveLogin(writer, it) }
        return if (mirrors == new.mirrors && source == new.sourceUrl) new else new.copy(mirrors = mirrors, sourceUrl = source)
    }

    private suspend fun moveLogin(
        writer: CredentialWriter,
        url: String,
    ): String {
        val bare = stripUserInfo(url)
        if (bare == url) return url
        val login = userInfoOf(url) ?: return bare
        val origin = originFor(url)
        if (!writer.save(origin, login)) eventFlow.tryEmit(EngineEvent.CredentialsNotSaved(origin))
        return bare
    }

    /** The peers, trackers and piece map of torrent job [id] (TOR-07), for the one being looked at. */
    suspend fun torrentDetail(id: DownloadId): TorrentDetailResult {
        val service = torrents ?: return TorrentDetailResult.Unavailable
        val detail = command { service.detail(id) } ?: return TorrentDetailResult.NotInSession
        return TorrentDetailResult.Detail(detail)
    }

    /** Reads a torrent without adding it (TOR-02): its name and files, for a choice of which to download. */
    suspend fun inspectTorrent(source: String): InspectResult {
        val service = torrents ?: return InspectResult.Unavailable
        return when (val inspected = command { service.inspect(source) }) {
            is StepResult.Failed -> InspectResult.Failed(inspected)
            is StepResult.Ok -> InspectResult.Inspected(inspected.value)
        }
    }

    /**
     * Adds a torrent (TOR-01) from a magnet link, the address of a `.torrent` file or a path to one. [files] are the
     * torrent paths to download, null for all of them; a choice needs the file list, which is [info] from an earlier
     * [inspectTorrent] or else read now. The job is [template] with the torrent's own options, [sequential] asking for
     * the pieces in order (TOR-03).
     */
    suspend fun addTorrent(
        source: String,
        files: Set<String>? = null,
        sequential: Boolean = false,
        template: NewDownload = NewDownload(DownloadKind.TORRENT),
        info: TorrentInfo? = null,
        /** This torrent's own seeding limits (TOR-05); Inherit takes the settings'. */
        seedRatio: SeedRule<Double> = SeedRule.Inherit,
        seedMinutes: SeedRule<Int> = SeedRule.Inherit,
    ): AddTorrentResult {
        if (TorrentSource.parse(source) == null) return AddTorrentResult.BadSource
        seedRuleProblem(seedRatio, seedMinutes)?.let { return AddTorrentResult.Refused(CommandResult.Invalid(it)) }
        var known = info
        if (files != null && known == null) {
            val service = torrents ?: return AddTorrentResult.Unavailable
            known =
                when (val inspected = command { service.inspect(source) }) {
                    is StepResult.Failed -> return AddTorrentResult.Failed(inspected)
                    is StepResult.Ok -> inspected.value
                }
        }
        val priorities = if (known == null) emptyList() else prioritiesFor(known, files) ?: return AddTorrentResult.EmptySelection
        val added =
            command {
                queue.add(
                    template.copy(
                        kind = DownloadKind.TORRENT,
                        mirrors = listOf(source),
                        torrent = NewTorrent(known?.metadata, priorities, sequential, seedRatio, seedMinutes),
                    ),
                )
            }
        return when (added) {
            is CommandResult.Done -> AddTorrentResult.Added(checkNotNull(added.download) { "the queue added no job" })
            else -> AddTorrentResult.Refused(added)
        }
    }

    /**
     * Chooses the files of torrent job [id] (TOR-02): a priority from 0 (skip) to 7 for each file in torrent order, not
     * all 0. A torrent that is downloading follows at once when the files it takes stay the same; a different set takes
     * effect the next time the job runs, since its files were made for the old one.
     */
    suspend fun setTorrentFiles(
        id: DownloadId,
        priorities: List<Int>,
    ): CommandResult =
        command {
            val row = store.torrents.get(id) ?: return@command CommandResult.NotFound
            if (priorities.any { it !in 0..MAX_FILE_PRIORITY }) {
                return@command CommandResult.Invalid("file priorities are 0 to $MAX_FILE_PRIORITY: $priorities")
            }
            if (priorities.all { it == 0 }) return@command CommandResult.Invalid("no file is selected")
            // The torrent's files are known from its rows once it has run, else from the choice it was added with.
            val count =
                store.downloads
                    .files(id)
                    .size
                    .takeIf { it > 0 } ?: row.priorities.size.takeIf { it > 0 }
            if (count != null && priorities.size != count) {
                return@command CommandResult.Invalid("the torrent has $count files, not ${priorities.size}")
            }
            // No stored choice means every file is selected.
            val sameFiles =
                if (row.priorities.isEmpty()) priorities.all { it > 0 } else row.priorities.map { it > 0 } == priorities.map { it > 0 }
            store.torrents.setPriorities(id, priorities)
            // Another set of files has other pieces: the saved resume data would describe the wrong ones (TOR-06).
            if (!sameFiles) {
                store.torrents.setResume(id, null)
                store.torrents.setFileDone(id, null)
            }
            if (sameFiles) torrents?.applyPriorities(id, priorities)
            CommandResult.Done(store.downloads.get(id))
        }

    /** Sets whether torrent job [id] takes its pieces in order (TOR-03); a torrent that is downloading follows at once. */
    suspend fun setSequential(
        id: DownloadId,
        on: Boolean,
    ): CommandResult =
        command {
            if (!store.torrents.setSequential(id, on)) return@command CommandResult.NotFound
            torrents?.applySequential(id, on)
            CommandResult.Done(store.downloads.get(id))
        }

    /**
     * Adds a playlist or channel as a collection (MED-16): one flat listing of [url], then a parent job and one child job
     * for each item [selection] picks, all saved in a folder named after the playlist under [template]'s folder, its
     * category's, or the default. Every child is [template] with its own address, so its options apply to each video.
     */
    suspend fun addPlaylist(
        url: String,
        selection: ItemSelection = ItemSelection(),
        template: NewDownload = NewDownload(DownloadKind.SITE),
    ): AddPlaylistResult {
        val source = playlists ?: return AddPlaylistResult.Unavailable
        val listing =
            when (val listed = source.list(url, selection.listingEnd(LISTING_CAP + 1))) {
                is StepResult.Failed -> {
                    return AddPlaylistResult.Failed(listed)
                }

                is StepResult.Ok -> {
                    when (val parsed = listed.value) {
                        PlaylistParse.NotAPlaylist -> return AddPlaylistResult.NotAPlaylist
                        is PlaylistParse.Rejected -> return AddPlaylistResult.Failed(StepResult.Failed(null, parsed.reason))
                        is PlaylistParse.Ok -> parsed.listing
                    }
                }
            }
        val picked =
            when (val chosen = chosenEntries(listing, selection)) {
                is Chosen.Some -> chosen.entries
                Chosen.None -> return AddPlaylistResult.EmptySelection
                is Chosen.TooMany -> return AddPlaylistResult.Failed(StepResult.Failed(null, chosen.reason))
            }
        // Where the items go: the template's folder, else its profile's (or the default profile's), else the folder of the
        // template's or profile's category, else the default. The playlist gets a folder of its own under it.
        val profile = (template.profileId ?: store.settings.load().defaultProfileId)?.let { store.profiles.get(it) }
        val categoryId = template.categoryId ?: profile?.categoryId
        val base =
            template.saveFolder?.let(::Path)
                ?: profile?.saveFolder?.let(::Path)
                ?: store.categories
                    .list()
                    .firstOrNull { it.id == categoryId }
                    ?.folder
                    ?.let(::Path)
                ?: defaultFolder
        val folder = freshFolder(base, nameCleaner.clean(listing.title))
        val parent =
            template.copy(
                mirrors = emptyList(),
                parentId = null,
                collection = listing.kind,
                saveFolder = folder,
                fileName = null,
                size = null,
                checksum = null,
                playlistIndex = null,
                sourceUrl = url,
                selection = selection.takeIf { it != ItemSelection() },
            )
        val made = picked.map { itemJob(template, folder, it, it.position) }
        val added =
            command { queue.addCollection(parent, made.map { it.first }, made.map { it.second }) }
                ?: return AddPlaylistResult.AllDuplicates(made.size)
        return AddPlaylistResult.Added(added.collection, added.children, added.skipped, listing.dropped)
    }

    /** One playlist entry as a job (the [template] with its own address and place in the playlist) and its item row. */
    private fun itemJob(
        template: NewDownload,
        folder: String,
        entry: PlaylistEntry,
        index: Int,
    ): Pair<NewDownload, NewItem> =
        template.copy(
            mirrors = listOf(entry.url),
            parentId = null,
            collection = null,
            saveFolder = folder,
            fileName = null,
            size = null,
            checksum = null,
            playlistIndex = index,
            sourceUrl = null,
            selection = null,
        ) to NewItem(entry.extractor, entry.id, index, entry.title, entry.duration)

    private sealed interface Chosen {
        data class Some(
            val entries: List<PlaylistEntry>,
        ) : Chosen

        data object None : Chosen

        data class TooMany(
            val reason: String,
        ) : Chosen
    }

    // The listing's entries that [selection] picks, by their place in the playlist as listed; at most the cap.
    private fun chosenEntries(
        listing: PlaylistListing,
        selection: ItemSelection,
    ): Chosen {
        val positions = selection.indices(listing.entries.size + listing.dropped).toSet()
        val entries = listing.entries.filter { it.position in positions }
        return when {
            entries.isEmpty() -> Chosen.None
            entries.size > LISTING_CAP -> Chosen.TooMany("the selection has ${entries.size} items; the limit is $LISTING_CAP")
            else -> Chosen.Some(entries)
        }
    }

    // A folder no other collection uses: two playlists with one title would share a folder and one playlist file.
    private suspend fun freshFolder(
        base: Path,
        name: String,
    ): String {
        val used =
            store.downloads
                .list()
                .filter { it.isCollection }
                .mapNotNull { it.saveFolder }
                .toSet()
        var folder = Path(base, name).toString()
        var n = 2
        while (folder in used) folder = Path(base, "$name (${n++})").toString()
        return folder
    }

    // One sync at a time: two at once would both see an item as new.
    private val syncLock = Mutex()

    /**
     * Lists a collection's source again and adds a job for each item that is new (MED-17): one with no item row, or whose
     * job was removed before it finished. An item that is done, or whose job still exists (running, waiting, failed),
     * is left alone. The collection's own range and limit apply again; its folder, category, profile and options are the
     * new jobs'.
     */
    suspend fun syncCollection(id: DownloadId): SyncOutcome {
        val source = playlists ?: return SyncOutcome.Unavailable
        return syncLock.withLock {
            val collection = store.downloads.get(id)?.takeIf { it.isCollection && it.sourceUrl != null }
            if (collection == null) return@withLock SyncOutcome.NotACollection
            val selection = collection.selection ?: ItemSelection()
            val listing =
                when (val listed = source.list(collection.sourceUrl!!, selection.listingEnd(LISTING_CAP + 1))) {
                    is StepResult.Failed -> {
                        return@withLock SyncOutcome.Failed(listed)
                    }

                    is StepResult.Ok -> {
                        when (val parsed = listed.value) {
                            PlaylistParse.NotAPlaylist -> return@withLock SyncOutcome.Failed(
                                StepResult.Failed(null, "the address is not a playlist any more"),
                            )

                            is PlaylistParse.Rejected -> return@withLock SyncOutcome.Failed(StepResult.Failed(null, parsed.reason))

                            is PlaylistParse.Ok -> parsed.listing
                        }
                    }
                }
            val chosen =
                when (val picked = chosenEntries(listing, selection)) {
                    is Chosen.Some -> picked.entries
                    Chosen.None -> emptyList()
                    is Chosen.TooMany -> return@withLock SyncOutcome.Failed(StepResult.Failed(null, picked.reason))
                }
            val known = store.collectionItems.items(id).associateBy { it.extractor to it.videoId }
            val template = templateOf(collection)
            val folder = collection.saveFolder ?: ""
            // An item with a row that has no job is one the user removed or told us to forget: it is wanted again, so the
            // old job for the same address (still in the list or the history) must not stop it.
            val again = template.copy(allowDuplicate = true)
            val made =
                chosen
                    .filter { entry ->
                        val item = known[entry.extractor to entry.id]
                        item == null || (!item.done && (item.childId == null || store.downloads.get(item.childId!!) == null))
                    }.map { entry ->
                        itemJob(
                            if (known[entry.extractor to entry.id] !=
                                null
                            ) {
                                again
                            } else {
                                template
                            },
                            folder,
                            entry,
                            entry.position,
                        )
                    }
            val added = if (made.isEmpty()) null else command { queue.addChildren(id, made.map { it.first }, made.map { it.second }) }
            SyncOutcome.Done(SyncResult(added?.children?.size ?: 0, added?.skipped ?: 0, listing.dropped))
        }
    }

    /** What a collection's new items start from: its own options. */
    private suspend fun templateOf(collection: Download): NewDownload =
        NewDownload(
            kind = collection.kind,
            categoryId = collection.categoryId,
            profileId = collection.profileId,
            saveFolder = collection.saveFolder,
            priority = collection.priority,
            speedLimit = collection.speedLimit,
            context = store.downloads.context(collection.id),
            allowDuplicate = collection.allowDuplicate,
            format = store.downloads.format(collection.id),
        )

    /** The saved download profiles, by name (SET-02). */
    suspend fun profiles(): List<Profile> = command { store.profiles.list() }

    suspend fun addProfile(new: NewProfile): ProfileResult = command { store.profiles.add(new) }

    /** Jobs that name the profile read the new values from their next probe, placement or start. */
    suspend fun updateProfile(profile: Profile): ProfileResult = command { store.profiles.update(profile) }

    /** Removes the profile; its jobs go on with their own values and the defaults. Returns how many jobs named it. */
    suspend fun deleteProfile(id: ProfileId): Int = command { store.profiles.delete(id) }

    /** The profile a new download takes when it names none; null for none. False if there is no such profile. */
    suspend fun setDefaultProfile(id: ProfileId?): Boolean =
        command {
            if (id != null && store.profiles.get(id) == null) {
                false
            } else {
                store.settings.update { it.copy(defaultProfileId = id) }
                true
            }
        }

    /**
     * Forgets that [videoId] was fetched for the collection (MED-17): its old job leaves the collection and the next sync
     * fetches it again, whatever the duplicate check thinks of the old one.
     */
    suspend fun forgetItem(
        collection: DownloadId,
        extractor: String,
        videoId: String,
    ): Boolean =
        command {
            val item = store.collectionItems.items(collection).firstOrNull { it.extractor == extractor && it.videoId == videoId }
            if (item == null) {
                false
            } else {
                // The old job stays as it is, outside the collection: its file and its place in the history are the user's.
                item.childId?.let { queue.detach(it) }
                store.collectionItems.forget(collection, extractor, videoId)
            }
        }

    suspend fun pause(id: DownloadId): CommandResult = command { queue.pause(id) }

    suspend fun resume(id: DownloadId): CommandResult = command { queue.resume(id) }

    suspend fun pauseAll(): CommandResult = command { queue.pauseAll() }

    suspend fun resumeAll(): CommandResult = command { queue.resumeAll() }

    suspend fun cancel(id: DownloadId): CommandResult = command { queue.cancel(id) }

    /** Takes the job off the list; [files] says what happens to its files, which go to the OS trash unless told otherwise. */
    suspend fun remove(
        id: DownloadId,
        files: RemoveFiles = RemoveFiles.TRASH,
    ): CommandResult =
        command {
            val service = torrents
            val seeding = service != null && store.downloads.get(id)?.status == DownloadStatus.SEEDING
            // A seed leaves the session before its files go (TOR-05); its end is not news for a job that is being removed.
            if (seeding) {
                removing.update { it + id }
                service?.stopSeeding(id)
            }
            try {
                val removed = queue.remove(id, files)
                // A removal the trash refused leaves the job: its seed is over all the same.
                if (seeding && removed !is CommandResult.Done) queue.finishSeeding(id, null)
                removed
            } finally {
                if (seeding) removing.update { it - id }
            }
        }

    suspend fun retry(id: DownloadId): CommandResult = command { queue.retry(id) }

    suspend fun reprioritize(
        id: DownloadId,
        priority: Int,
    ): CommandResult = command { queue.reprioritize(id, priority) }

    suspend fun setChecksum(
        id: DownloadId,
        checksum: Checksum?,
    ): CommandResult = command { queue.setChecksum(id, checksum) }

    suspend fun setLimit(limit: Int): CommandResult = command { queue.setLimit(limit) }

    suspend fun setGlobalSpeedLimit(bytesPerSecond: Long?): CommandResult = command { queue.setGlobalSpeedLimit(bytesPerSecond) }

    /** The most all torrents together may send, in bytes per second, null for no limit (TOR-08). */
    suspend fun setGlobalUploadLimit(bytesPerSecond: Long?): CommandResult = command { queue.setGlobalUploadLimit(bytesPerSecond) }

    /**
     * Ends the seed of torrent job [id] now (TOR-05); the job completes. Only a SEEDING job takes it. A seed the protocol
     * no longer holds (its session never came up) completes all the same, so the user is never stuck with it.
     */
    suspend fun stopSeeding(id: DownloadId): CommandResult =
        command {
            val job = store.downloads.get(id) ?: return@command CommandResult.NotFound
            if (job.status != DownloadStatus.SEEDING) {
                return@command CommandResult.Rejected(TransitionResult.Rejected(job.status, DownloadStatus.COMPLETED))
            }
            torrents?.stopSeeding(id)
            val finished = queue.finishSeeding(id, null)
            // The protocol's own end may have completed the job first.
            val now = store.downloads.get(id)
            if (finished is CommandResult.Rejected && now?.status == DownloadStatus.COMPLETED) CommandResult.Done(now) else finished
        }

    /** Sets when the job may start (NET-09), or clears it with null; a running job given a future time is stopped and waits. */
    suspend fun setStartTime(
        id: DownloadId,
        at: Instant?,
    ): CommandResult = command { queue.setStartTime(id, at) }

    /** Slow mode (NET-06): whether it is on and the limit it caps at, both directions. */
    suspend fun slowMode(): SlowMode = command { store.settings.load().let { SlowMode(it.slowMode, it.slowModeLimit) } }

    /** Switches slow mode on or off; running jobs are slowed or sped up before this returns. The user's own limits stay. */
    suspend fun setSlowMode(on: Boolean): CommandResult =
        command {
            store.settings.update { it.copy(slowMode = on) }
            rulesChanged()
            CommandResult.Done(null)
        }

    /** The limit slow mode caps at, in bytes per second for each direction. */
    suspend fun setSlowModeLimit(bytesPerSecond: Long): CommandResult =
        command {
            if (bytesPerSecond < 1) return@command CommandResult.Invalid("slow-mode limit must be >= 1: $bytesPerSecond")
            store.settings.update { it.copy(slowModeLimit = bytesPerSecond) }
            rulesChanged()
            CommandResult.Done(null)
        }

    /** The limits and data allowances of the default policy and of each network (NET-05, NET-07). */
    suspend fun networkRules(): NetworkRules = command { store.rules.networkRules() }

    /** Sets the policy of [network], null for the default; a null [policy] removes the entry. */
    suspend fun setNetworkPolicy(
        network: String?,
        policy: NetworkPolicy?,
    ): CommandResult =
        command {
            if (network != null && (network.isBlank() || network == "*")) {
                return@command CommandResult.Invalid("network key must not be blank or *: $network")
            }
            store.rules.setNetworkPolicy(network, policy)
            rulesChanged()
            CommandResult.Done(null)
        }

    /** The weekly schedule rules (NET-08), by id. */
    suspend fun scheduleRules(): List<ScheduleRule> = command { store.rules.scheduleRules() }

    suspend fun addScheduleRule(new: NewScheduleRule): ScheduleRule =
        command {
            val rule = store.rules.addScheduleRule(new)
            rulesChanged()
            rule
        }

    /** False if the rule is gone. */
    suspend fun updateScheduleRule(rule: ScheduleRule): Boolean =
        command {
            val updated = store.rules.updateScheduleRule(rule)
            rulesChanged()
            updated
        }

    /** False if the rule is gone. */
    suspend fun removeScheduleRule(id: ScheduleRuleId): Boolean =
        command {
            val removed = store.rules.removeScheduleRule(id)
            rulesChanged()
            removed
        }

    /** Whether the machine is kept awake while a job downloads (NET-11); on by default. */
    suspend fun keepAwake(): Boolean = command { store.settings.load().keepAwake }

    /** Turns keep awake on or off; off lets the machine sleep again at once. */
    suspend fun setKeepAwake(on: Boolean): CommandResult =
        command {
            store.settings.update { it.copy(keepAwake = on) }
            withContext(publisher) { if (keepersStarted) awake.setEnabled(on) }
            CommandResult.Done(null)
        }

    /**
     * Arms what the machine does once the queue is finished (NET-10), or takes it away with NONE. It is one-shot: after
     * the queue has been busy and then finished, a 30 s countdown ([EngineEvent.AfterQueueCountdown]) runs and the action
     * follows, unless work starts first. Arming an idle queue waits for the next busy-then-idle cycle.
     */
    suspend fun setAfterQueue(action: AfterQueueAction): CommandResult =
        command {
            withContext(publisher) {
                if (keepersStarted) {
                    afterQueueKeeper.set(action, QueueActivity.busy(snapshots.value.map { it.download }))
                } else {
                    store.settings.update { it.copy(afterQueue = action) }
                }
            }
            CommandResult.Done(null)
        }

    /** Stops the countdown and takes the action away; done also when nothing is armed. */
    suspend fun cancelAfterQueue(): CommandResult = setAfterQueue(AfterQueueAction.NONE)

    /** Torrent job [id]'s own seeding limits (TOR-05); a rule applies at the next check of the seed. */
    suspend fun setSeedRules(
        id: DownloadId,
        ratio: SeedRule<Double>,
        minutes: SeedRule<Int>,
    ): CommandResult =
        command {
            if (store.torrents.get(id) == null) return@command CommandResult.NotFound
            seedRuleProblem(ratio, minutes)?.let { return@command CommandResult.Invalid(it) }
            store.torrents.setSeedRules(id, ratio, minutes)
            CommandResult.Done(store.downloads.get(id))
        }

    private fun seedRuleProblem(
        ratio: SeedRule<Double>,
        minutes: SeedRule<Int>,
    ): String? =
        when {
            ratio is SeedRule.At && ratio.value <= 0 -> "a seed ratio must be > 0: ${ratio.value}"
            minutes is SeedRule.At && minutes.value <= 0 -> "seed minutes must be > 0: ${minutes.value}"
            else -> null
        }

    suspend fun setSpeedLimit(
        id: DownloadId,
        bytesPerSecond: Long?,
    ): CommandResult = command { queue.setSpeedLimit(id, bytesPerSecond) }

    suspend fun relink(
        id: DownloadId,
        mirrorId: MirrorId,
        url: String,
    ): CommandResult = command { queue.relink(id, mirrorId, url) }

    // A stopped or failed engine takes no command; one not started yet lets the queue hold it until start().
    private suspend fun <T> command(block: suspend () -> T): T {
        when (stateFlow.value) {
            EngineState.Stopping, EngineState.Stopped, is EngineState.Failed -> throw IllegalStateException("the engine has stopped")
            else -> Unit
        }
        return block()
    }

    // After a rule or slow-mode command: the rules are read again and the queue has the result before the command returns.
    // Before start() the store alone holds them, and start() reads it.
    private suspend fun rulesChanged() =
        withContext(publisher) {
            if (keeper.refresh() != null) {
                armRuleTimer()
                keeper.push()
            }
        }

    // On the publisher: THE rule check (the timer, the bytes, a drop, a change, a return, a wake): decides again, re-arms the
    // timer and sends a changed effect to the queue. A check that fails (a store that cannot be read) must not end the engine:
    // it is tried again with the idle period, and only the timer, a drop, a change, a wake or a command try it sooner. A check
    // the bytes trigger cannot succeed where the timer's read fails, so while one failed it is skipped (one warning, not
    // one a second). [returning]: the network just came back, so its rules reach the queue before the release, or a job would
    // start on a forbidden network; if they cannot be read the release waits for the retry. [after]: what starts again once
    // the rules are in place; it does not depend on them, so a failed check still does it.
    private suspend fun checkRules(
        byBytes: Boolean = false,
        returning: Boolean = false,
        after: After = After.NOTHING,
    ) {
        if (byBytes && rulesFailing) return
        try {
            keeper.check() ?: return
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (rulesFailing) {
                log.debug("net") { "rules not checked: ${e.message}" }
            } else {
                log.warn("net") { "rules not checked: ${e.message}" }
            }
            rulesFailing = true
            // The rules cannot be read now, and the new network may forbid the jobs: they stay paused for the network, and the
            // retry's success pushes the rules and then releases them.
            if (returning) releasePending = true
            armRuleRetry()
            if (after != After.NOTHING) launchQuietly { restart(after) }
            return
        }
        if (rulesFailing) {
            rulesFailing = false
            log.info("net") { "rules checked again" }
        }
        armRuleTimer()
        val release = returning || takeRelease()
        if (returning) releasePending = false
        launchQuietly {
            pushQuietly()
            // The push may have waited at the queue while the network dropped again: the Lost's offline is already there, and
            // a release behind it would undo it. Nothing suspends between this check and the send.
            if (release && monitor.state.value.online) queue.applyNetwork(false)
            restart(after)
        }
    }

    // What starts again after the rules: the connections that still run (a change of network, a wake); after a wake the network
    // is asked once more first: offline, the drop pauses everything and there is nothing to restart.
    private suspend fun restart(after: After) {
        when (after) {
            After.NOTHING -> Unit
            After.RESTART -> queue.restartConnections()
            After.RESTART_IF_ONLINE -> if (monitor.pollNow().online) queue.restartConnections()
        }
    }

    // On the publisher: whether a return still waits for its release (its rules could not be read); asked once.
    private fun takeRelease(): Boolean = releasePending.also { releasePending = false }

    // On the publisher, every WAKE_TICK: a jump of the clock means the machine slept or froze. The rules are decided again at
    // once, then the connections start again, if the network is still there.
    private suspend fun wakeTick() {
        val slept = wake.observe(clock.now(), monoStart.elapsedNow()) ?: return
        tell(EngineEvent.SystemWoke(slept))
        meteredRefresh.invalidate()
        checkRules(after = After.RESTART_IF_ONLINE)
    }

    // Whether this fact can change what the keepers watch: a job added or removed, or one whose status or pause reasons
    // changed (a PAUSED job that gets or loses NETWORK changes "busy" with no status change). Before it is applied.
    private fun changesActivity(fact: Fact): Boolean =
        when (fact) {
            is Fact.Removed -> {
                true
            }

            is Fact.Changed -> {
                val before = cache.downloadOf(fact.download.id)
                before == null || before.status != fact.download.status || before.pauseReasons != fact.download.pauseReasons
            }

            else -> {
                false
            }
        }

    // On the publisher, after a publish that moved a job: tells the keepers what the queue is doing.
    private suspend fun onActivity() {
        val jobs = snapshots.value.map { it.download }
        try {
            awake.update(QueueActivity.awake(jobs))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("engine") { "keep awake failed: ${e.message}" }
        }
        afterQueueKeeper.update(QueueActivity.busy(jobs))
    }

    // On the publisher: one poll of the network, then the metered answer for it when one is due.
    private suspend fun networkTick() {
        meteredRefresh.refresh(monitor.tick())
    }

    // One step failing (a store that cannot be read) must never end the collection, or the jobs would stay as they are for good.
    private suspend fun onNetworkStepQuietly(step: NetworkStep) {
        try {
            onNetworkStep(step)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("net") { "network step $step failed: ${e.message}" }
        }
    }

    // What the engine does when the network drops, returns, changes, or learns it is metered (NET-12, NET-13, NET-14).
    private suspend fun onNetworkStep(step: NetworkStep) {
        when (step) {
            NetworkStep.Lost -> {
                // The queue is offline now: a return that waited for its rules has nothing left to release.
                releasePending = false
                tell(EngineEvent.NetworkLost)
                launchQuietly { queue.applyNetwork(true) }
                checkRules()
            }

            NetworkStep.Restored -> {
                tell(EngineEvent.NetworkRestored)
                meteredRefresh.invalidate()
                checkRules(returning = true)
            }

            is NetworkStep.Changed -> {
                tell(EngineEvent.NetworkChanged(step.from, step.to))
                checkRules(after = After.RESTART)
            }

            NetworkStep.MeteredChanged -> {
                checkRules()
            }
        }
    }

    // On the publisher; a queue that has stopped has nothing to tell.
    private fun launchQuietly(block: suspend () -> Unit) {
        scope.launch(publisher) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalStateException) {
                log.debug("net") { "not applied: ${e.message}" }
            }
        }
    }

    // A queue that has stopped has nothing to apply the rules to.
    private suspend fun pushQuietly() {
        try {
            keeper.push()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalStateException) {
            log.debug("net") { "rules not applied: ${e.message}" }
        } catch (e: Exception) {
            // A store that fails inside the queue must not skip what comes after the push (the release on a return).
            log.warn("net") { "rules not applied: ${e.message}" }
        }
    }

    // On the publisher: one timer for the next schedule boundary or allowance reset, at most RULES_IDLE_CHECK away, so a
    // wall clock that jumped or a network that changed is seen while nothing transfers. Re-armed when the instant changes.
    private fun armRuleTimer() {
        val target = keeper.status.value.nextChange
        if (ruleTimer?.isActive == true && target == ruleTimerAt) return
        ruleTimer?.cancel()
        ruleTimerAt = target
        ruleTimer =
            scope.launch(publisher) {
                val wait = if (target == null) RULES_IDLE_CHECK else minOf(target - clock.now(), RULES_IDLE_CHECK)
                delay(wait.coerceAtLeast(Duration.ZERO))
                // This timer is done: the check below may arm the next one.
                ruleTimer = null
                ruleTimerAt = null
                checkRules()
            }
    }

    // On the publisher: after a failed check, a timer one idle period away unless one is running, so a store that stays broken
    // is asked once a minute, however often the bytes ask for a check.
    private fun armRuleRetry() {
        if (ruleTimer?.isActive == true) return
        ruleTimerAt = null
        ruleTimer =
            scope.launch(publisher) {
                delay(RULES_IDLE_CHECK)
                ruleTimer = null
                ruleTimerAt = null
                checkRules()
            }
    }

    // Applies one fact to the cache; the event it carries, if any, is returned to be told once the snapshots are out.
    private suspend fun apply(fact: Fact): Fact.Event? {
        when (fact) {
            is Fact.Changed -> {
                cache.jobChanged(fact.download)
            }

            is Fact.Removed -> {
                cache.jobRemoved(fact.id)
            }

            is Fact.AttemptChanged -> {
                cache.attemptChanged(fact.attempt)
            }

            is Fact.MirrorsChanged -> {
                cache.mirrorsChanged(fact.id, fact.mirrors)
            }

            is Fact.Checkpointed -> {
                if (cache.contains(fact.id)) cache.filesChanged(fact.id, filesOf(fact.id))
            }

            is Fact.SlotStarted -> {
                started(fact.id)
            }

            is Fact.SlotEnded -> {
                ended(fact.id)
            }

            is Fact.Event -> {
                Unit
            }
        }
        return fact as? Fact.Event
    }

    private fun afterPublished(fact: Fact.Event) {
        logEvent(fact.event)
        (fact.event as? EngineEvent.PausedForTool)?.let(::fetchTool)
        eventFlow.tryEmit(fact.event)
    }

    // A finished TOOL job tells the manager, which makes the tool current once it is whole; then the jobs waiting for
    // tools get another go.
    private fun toolAware(inner: FinishHook): FinishHook =
        object : FinishHook {
            override suspend fun finished(
                download: Download,
                outputs: List<Path>,
            ) {
                inner.finished(download, outputs)
                if (download.kind == DownloadKind.TOOL && tools?.installed(download, outputs) == true) queue.applyToolState()
            }
        }

    // A job paused for a missing tool starts the install; the manager adds one TOOL job however many jobs wait. A tool
    // that is already there (it finished between the job's probe and this event) just lets the waiting jobs go, after a
    // pause so a job whose own lookup disagrees with the manager can't spin. Nothing here may fail the engine.
    private fun fetchTool(event: EngineEvent.PausedForTool) {
        val manager = tools ?: return
        val tool = Tool.byId(event.tool)
        if (tool == null) {
            toolUnavailable(event)
            return
        }
        scope.launch {
            try {
                if (manager.resolve(tool) != null) {
                    delay(TOOL_RELEASE_DELAY)
                    queue.applyToolState()
                } else if (!manager.ensure(tool) { queue.add(it) }) {
                    toolUnavailable(event)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("tools") { "can't fetch ${tool.id}: ${e.message}" }
                toolUnavailable(event)
            }
        }
    }

    private fun toolUnavailable(event: EngineEvent.PausedForTool) {
        log.warn("job") { "job ${event.id.value} waits for ${event.tool}, which can't be fetched" }
        eventFlow.tryEmit(EngineEvent.ToolUnavailable(event.id, event.tool))
    }

    // After a restart: a TOOL job that completed but whose finish never ran gets recorded, and the jobs paused for tools
    // get another go (those that still lack theirs pause again and ask for it).
    private suspend fun recoverTools() {
        val manager = tools ?: return
        for (job in store.downloads
            .list(
                DownloadStatus.COMPLETED,
            ).filter { it.kind == DownloadKind.TOOL }) {
            manager.installed(job, emptyList())
        }
        queue.applyToolState()
    }

    private fun logEvent(event: EngineEvent) {
        when (event) {
            is EngineEvent.Added -> {
                log.info("job") { "job ${event.id.value} added" }
            }

            is EngineEvent.Removed -> {
                log.info("job") { "job ${event.id.value} removed" }
            }

            is EngineEvent.StatusChanged -> {
                log.debug("job") { "job ${event.id.value} ${event.from} -> ${event.to}" }
                if (event.to == DownloadStatus.DOWNLOADING) log.info("job") { "job ${event.id.value} started" }
            }

            is EngineEvent.Completed -> {
                log.info("job") { "job ${event.id.value} completed (${event.outputs.joinToString()})" }
            }

            is EngineEvent.Failed -> {
                log.warn("job") { "job ${event.id.value} failed (${event.errorClass ?: "no class"}): ${event.message}" }
            }

            is EngineEvent.RetryScheduled -> {
                log.info("job") { "job ${event.id.value} retry ${event.attempt} at ${event.at}" }
            }

            is EngineEvent.MovedToMirror -> {
                log.info("job") { "job ${event.id.value} moved to mirror ${event.mirror.value}" }
            }

            is EngineEvent.StartedOver -> {
                log.info("job") { "job ${event.id.value} started over: ${event.reason}" }
            }

            is EngineEvent.PausedForDisk -> {
                log.info("job") { "job ${event.id.value} paused: disk full" }
            }

            is EngineEvent.ToolUnavailable -> {
                Unit
            }

            is EngineEvent.PausedForTool -> {
                log.info("job") { "job ${event.id.value} paused: needs ${event.tool}" }
            }

            is EngineEvent.CredentialsNotSaved -> {
                log.warn("job") { "login for ${event.origin} not saved in the keychain; kept for this run" }
            }

            is EngineEvent.AllowanceWarning -> {
                val pct = if (event.limit == 0L) 100 else event.used * 100 / event.limit
                log.info("net") { "allowance on ${event.network}: $pct% used (${event.used}/${event.limit} bytes)" }
            }

            is EngineEvent.AllowanceReached -> {
                log.info("net") { "allowance on ${event.network} reached: ${event.action} until ${event.resetsAt}" }
            }

            // The monitor logs these with the interface it knows.
            is EngineEvent.NetworkLost, is EngineEvent.NetworkRestored, is EngineEvent.NetworkChanged -> {
                Unit
            }

            is EngineEvent.SystemWoke -> {
                log.info("net") { "woke after ${event.slept.inWholeSeconds.seconds}" }
            }

            is EngineEvent.AfterQueueCountdown -> {
                log.info("net") { "after queue: ${event.action} in ${AfterQueueKeeper.AFTER_QUEUE_COUNTDOWN.inWholeSeconds} s" }
            }

            is EngineEvent.AfterQueueCancelled -> {
                log.info("net") { "after queue: ${event.action} cancelled" }
            }

            is EngineEvent.AfterQueueDone -> {
                log.info("net") { "after queue: ${event.action}" }
            }

            is EngineEvent.AfterQueueFailed -> {
                log.info("net") { "after queue: ${event.action} failed: ${event.message}" }
            }

            is EngineEvent.StopTimedOut, is EngineEvent.EngineFailed -> {
                Unit
            }
        }
    }

    private fun started(id: DownloadId) {
        running += id
        if (loops?.isActive != true) {
            loops =
                scope.launch(publisher) {
                    launch { every(PROGRESS_EVERY) { publishProgress() } }
                    launch { every(SPEED_EVERY) { sampleSpeed() } }
                }
        }
    }

    // Its last progress counts; then it shows what its checkpoints hold, and no speed.
    private suspend fun ended(id: DownloadId) {
        running -= id
        published -= id
        if (cache.contains(id)) {
            // A torrent's live numbers go with its run: what it saved is what the files show now, not a second-old reading.
            cache.torrentLive(id, null)
            cache.filesChanged(id, filesOf(id))
            cache.stoppedRunning(id)
        }
        if (running.isEmpty()) {
            loops?.cancel()
            loops = null
            speedFlow.value = SpeedWindow(0, totalSamples.toList())
        }
    }

    private suspend fun every(
        period: Duration,
        block: suspend () -> Unit,
    ) {
        while (true) {
            delay(period)
            block()
        }
    }

    // At most every PROGRESS_EVERY per job (FND-11): only what changed since the last publish.
    private fun publishProgress() {
        var changed = false
        for (id in running) {
            val now = live.value[id]?.progress?.value ?: continue
            if (published[id] == now) continue
            published[id] = now
            cache.progress(id, now.first, now.second)
            changed = true
        }
        if (changed) publish()
    }

    private suspend fun sampleSpeed() {
        var total = 0L
        val samples = mutableListOf<TrafficMeter.Sample>()
        for (id in running) {
            val holder = live.value[id]
            val sample = holder?.bytes?.getAndUpdate { 0L } ?: 0L
            val sentSample = holder?.bytesUp?.getAndUpdate { 0L } ?: 0L
            cache.speed(id, sample)
            total += sample
            val kind = holder?.kind ?: cache.kindOf(id)?.also { holder?.kind = it }
            if (kind != null && (sample > 0 || sentSample > 0)) samples += TrafficMeter.Sample(id, kind, sample, sentSample)
        }
        val now = clock.now()
        meter.second(now, identity.current(), samples)
        takeLeftovers(now)
        totalSamples.addLast(total)
        while (totalSamples.size > SpeedWindow.SIZE) totalSamples.removeFirst()
        speedFlow.value = SpeedWindow(total, totalSamples.toList())
        publish()
        checkRules(byBytes = true)
    }

    /** Jobs being removed while their seed is stopped: that end finishes nothing. */
    private val removing = MutableStateFlow<Set<DownloadId>>(emptySet())

    // A seed ended in the protocol: its job completes, or fails with the reason. The protocol's end can land before the run
    // that finalized the seed has stored SEEDING, so a job still on its way there is asked again for a moment.
    private suspend fun finishSeed(end: SeedEnd) {
        if (end.id in removing.value) return
        val failure = (end as? SeedEnd.Failed)?.message
        repeat(SEED_FINISH_TRIES) {
            val result =
                try {
                    command { queue.finishSeeding(end.id, failure) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IllegalStateException) {
                    return
                }
            val from = (result as? CommandResult.Rejected)?.rejected?.from
            if (from != DownloadStatus.DOWNLOADING && from != DownloadStatus.MUXING) return
            delay(SEED_FINISH_WAIT)
        }
    }

    // The torrents in the session as the protocol last saw them (TOR-07): a summary for each, and none left for the ones that
    // went. Published only when something changed.
    private suspend fun applyTorrentLive(service: TorrentService) {
        val live =
            try {
                service.live()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("engine") { "torrent state unavailable: ${e.message}" }
                return
            }
        var changed = false
        for (id in cache.liveTorrentIds() + live.keys) changed = cache.torrentLive(id, live[id]) || changed
        if (changed) publish()
    }

    // What the seeds sent and received since the last second: counted for the day and the job (TOR-09), with no slot to carry it.
    private suspend fun sampleSeeds(service: TorrentService) {
        val moved =
            try {
                service.seedTraffic()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("engine") { "seed traffic unavailable: ${e.message}" }
                return
            }
        if (moved.isEmpty()) return
        meter.bytes(clock.now(), identity.current(), moved.map { TrafficMeter.Sample(it.id, DownloadKind.TORRENT, it.down, it.up) })
        checkRules(byBytes = true)
    }

    /** How many snapshot lists were built, for tests of the batching. */
    internal var publishCount = 0
        private set

    private fun publish() {
        publishCount++
        snapshots.value = cache.list()
    }

    private suspend fun filesOf(id: DownloadId): List<FileSnapshot> {
        val rows = store.downloads.files(id)
        // A torrent's progress is kept per file in its row; it counts only when it names every file the job has.
        val done =
            store.torrents
                .get(id)
                ?.fileDone
                ?.takeIf { it.size == rows.size }
        return rows.mapIndexed { i, file -> FileSnapshot(file, store.downloads.segments(file.id), done?.get(i)) }
    }

    // Jobs in a folder that has run low on space wait for it (FIL-08); the whole state goes to the queue every time. A
    // job paused for a low disk comes back once its folder holds what is left of its file plus the reserve, the same
    // rule the start check applies, so it can't be released only to fail again.
    private suspend fun checkDisk() {
        val jobs = snapshots.value.filter { it.download.status in DISK_WATCHED || PauseReason.LOW_DISK in it.download.pauseReasons }
        val folders = jobs.mapNotNull { job -> job.download.saveFolder?.let { Path(it) } }.toSet()
        lowFolders = diskGuard.lowFolders(folders, lowFolders)
        val release =
            jobs
                .filter { it.download.status == DownloadStatus.PAUSED && PauseReason.LOW_DISK in it.download.pauseReasons }
                .filter { job ->
                    val folder = job.download.saveFolder?.let { Path(it) }
                    folder != null && folder !in lowFolders &&
                        diskGuard.hasRoom(folder, maxOf(0L, (job.download.size ?: 0L) - job.downloaded))
                }.map { it.download.id }
                .toSet()
        queue.applyDiskState(lowFolders, release)
    }

    // The queue's coroutine failed (the store): the engine stops with it.
    internal fun failed(error: Throwable) {
        // Only a running (or starting) engine fails; one that is stopping or stopped keeps its course.
        val now = stateFlow.value
        if (now != EngineState.Running && now != EngineState.NotStarted) return
        if (!stateFlow.compareAndSet(now, EngineState.Failed(error))) return failed(error)
        log.error("engine", error) { "engine failed" }
        eventFlow.tryEmit(EngineEvent.EngineFailed(error))
        scope.cancel()
        runCatching { store.close() }
        // The hold must not outlive the engine; the scope is gone, so this runs on its own.
        CoroutineScope(dispatcher).launch(NonCancellable) { runCatching { hold.release() } }
    }

    /** A run that ended: the bytes it got since its last sampled second, to count with the next save. */
    private fun keepLeftover(holder: Live) {
        val left = holder.bytes.getAndUpdate { 0L }
        val leftUp = holder.bytesUp.getAndUpdate { 0L }
        if (left <= 0 && leftUp <= 0) return
        val id =
            live.value.entries
                .firstOrNull { it.value === holder }
                ?.key ?: return
        val kind =
            holder.kind ?: snapshots.value
                .find { it.download.id == id }
                ?.download
                ?.kind ?: return
        leftovers.update { it + TrafficMeter.Sample(id, kind, left, leftUp) }
    }

    private fun takeLeftovers(now: Instant) {
        val left = leftovers.getAndUpdate { emptyList() }
        if (left.isNotEmpty()) meter.bytes(now, identity.current(), left)
    }

    /**
     * Saves what the meter counted (every TRAFFIC_SAVE_EVERY, and at stop). A failed save is put back for the next one.
     * A save cut short by cancellation isn't: the store finishes a write that was asked for, so it would count twice.
     */
    private suspend fun flushTraffic() {
        // Under the keeper's lock, so a rule check never finds the drained bytes in neither the meter nor its count.
        keeper.saveTraffic {
            takeLeftovers(clock.now())
            val batch = meter.drain()
            if (batch.isEmpty) return@saveTraffic null
            try {
                traffic.add(batch, epochMinute(clock.now() - 24.hours))
                batch
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                log.error("engine", e) { "traffic not saved; it is kept for the next save" }
                meter.restore(batch)
                null
            }
        }
    }

    /** At stop, with the queue stopped: whatever runs still hold, and every unsaved byte, saved once more. Never throws. */
    private suspend fun saveLastTraffic() {
        try {
            withContext(NonCancellable) {
                withTimeoutOrNull(stopTimeout) {
                    withContext(publisher) {
                        live.value.values.forEach(::keepLeftover)
                        flushTraffic()
                    }
                }
            }
        } catch (e: Throwable) {
            log.error("engine", e) { "traffic not saved at stop" }
        }
    }

    private companion object {
        /** The most facts one snapshot list covers, so a flood still lets the list update now and then. */
        const val MAX_BATCH = 500

        val TRAFFIC_SAVE_EVERY = 10.seconds
        val PROGRESS_EVERY = 200.milliseconds
        val SPEED_EVERY = 1.seconds
        val DISK_CHECK_EVERY = 30.seconds
        val TOOL_RELEASE_DELAY = 2.seconds

        /** How often the network is polled (NET-13). */
        val NET_POLL = 3.seconds

        /** How often the clock is compared with itself to notice a sleep (NET-12). */
        val WAKE_TICK = 5.seconds

        /** The longest the rules go unchecked while nothing transfers (NET-08): a wall clock jump or a network change shows by then. */
        val RULES_IDLE_CHECK = 1.minutes

        /** A seed that ends before its job has stored SEEDING is asked again, this often, this many times. */
        val SEED_FINISH_WAIT = 100.milliseconds
        const val SEED_FINISH_TRIES = 50
        val DISK_WATCHED = setOf(DownloadStatus.PENDING, DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING, DownloadStatus.MUXING)
    }
}

/** What starts again once the rules of a changed network are in place. */
private enum class After { NOTHING, RESTART, RESTART_IF_ONLINE }
