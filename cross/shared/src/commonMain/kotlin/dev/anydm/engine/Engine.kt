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
import dev.anydm.engine.core.RetryPolicy
import dev.anydm.engine.core.StepRunner
import dev.anydm.engine.files.CollectionFinishHook
import dev.anydm.engine.files.DiskGuard
import dev.anydm.engine.files.FilesDuplicateCheck
import dev.anydm.engine.files.FilesPlacement
import dev.anydm.engine.files.HistoryRecorder
import dev.anydm.engine.files.NameCleaner
import dev.anydm.engine.files.OsProfile
import dev.anydm.engine.log.Log
import dev.anydm.engine.log.LogLevel
import dev.anydm.engine.media.PlaylistEntry
import dev.anydm.engine.media.PlaylistParse
import dev.anydm.engine.media.PlaylistSource
import dev.anydm.engine.net.NetworkIdentity
import dev.anydm.engine.net.TrafficMeter
import dev.anydm.engine.net.epochMinute
import dev.anydm.engine.platform.FileSystem
import dev.anydm.engine.protocol.Protocol
import dev.anydm.engine.protocol.StepResult
import dev.anydm.engine.queue.CommandResult
import dev.anydm.engine.queue.Queue
import dev.anydm.engine.queue.QueueObserver
import dev.anydm.engine.queue.RemoveFiles
import dev.anydm.engine.store.NewDownload
import dev.anydm.engine.store.NewItem
import dev.anydm.engine.store.Store
import dev.anydm.engine.store.TrafficStore
import dev.anydm.engine.tools.Tool
import dev.anydm.engine.tools.ToolManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
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
import kotlinx.io.files.Path
import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

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
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val stopTimeout: Duration = 10.seconds,
    checkpointEvery: Duration = StepRunner.DEFAULT_CHECKPOINT_EVERY,
    retryPolicy: RetryPolicy = RetryPolicy(),
    private val log: Log = Log.None,
    /** Which network bytes arrive on (NET-04); the desktop passes DefaultRouteNetwork. */
    private val network: NetworkIdentity = NetworkIdentity.Unknown,
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
) {
    private val nameCleaner = NameCleaner(osProfile)

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

        /** Its kind once known, kept so a job removed mid-run still counts under it (NET-04). */
        @Volatile var kind: DownloadKind? = null
    }

    // Traffic (NET-04): counted once a second on the publisher; bytes a run got after its last second wait in leftovers.
    private val meter = TrafficMeter()
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
        )

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
                queue.start()
                val jobs = store.downloads.list()
                log.info("engine") { "engine started (${jobs.size} jobs)" }
                val attempts = jobs.associate { it.id to store.downloads.attempts(it.id) }
                val mirrors = jobs.associate { it.id to store.downloads.mirrors(it.id) }
                val files = jobs.associate { it.id to filesOf(it.id) }
                withContext(publisher) {
                    cache.load(jobs, attempts, mirrors, files)
                    publish()
                }
                scope.launch(publisher) { for (fact in facts) apply(fact) }
                scope.launch(publisher) {
                    while (true) {
                        delay(TRAFFIC_SAVE_EVERY)
                        flushTraffic()
                    }
                }
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
                queueJob.cancel()
                val ended = withTimeoutOrNull(stopTimeout) { queueJob.join() }
                if (ended == null) {
                    val still = inSlots.value.sortedBy { it.value }
                    log.error("engine") { "stop timed out, still saving: ${still.joinToString { it.value.toString() }}" }
                    eventFlow.tryEmit(EngineEvent.StopTimedOut(still))
                }
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

    suspend fun add(new: NewDownload): CommandResult = command { queue.add(new) }

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
            when (val listed = source.list(url)) {
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
        val picked = selection.indices(listing.entries.size)
        if (picked.isEmpty()) return AddPlaylistResult.EmptySelection
        val base =
            template.saveFolder?.let(::Path)
                ?: store.categories
                    .list()
                    .firstOrNull { it.id == template.categoryId }
                    ?.folder
                    ?.let(::Path)
                ?: defaultFolder
        val folder = Path(base, nameCleaner.clean(listing.title)).toString()
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
        val made = picked.map { itemJob(template, folder, listing.entries[it - 1], it) }
        val added = command { queue.addCollection(parent, made.map { it.first }, made.map { it.second }) }
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
            val listing =
                when (val listed = source.list(collection.sourceUrl!!)) {
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
            val known = store.collectionItems.items(id).associateBy { it.extractor to it.videoId }
            val template = templateOf(collection)
            val folder = collection.saveFolder ?: ""
            val made =
                (collection.selection ?: ItemSelection())
                    .indices(listing.entries.size)
                    .filter { index ->
                        val entry = listing.entries[index - 1]
                        val item = known[entry.extractor to entry.id]
                        item == null || (!item.done && (item.childId == null || store.downloads.get(item.childId!!) == null))
                    }.map { itemJob(template, folder, listing.entries[it - 1], it) }
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

    /** Forgets that [videoId] was ever an item of the collection, so the next sync fetches it again (MED-17). */
    suspend fun forgetItem(
        collection: DownloadId,
        extractor: String,
        videoId: String,
    ): Boolean = command { store.collectionItems.forget(collection, extractor, videoId) }

    suspend fun pause(id: DownloadId): CommandResult = command { queue.pause(id) }

    suspend fun resume(id: DownloadId): CommandResult = command { queue.resume(id) }

    suspend fun pauseAll(): CommandResult = command { queue.pauseAll() }

    suspend fun resumeAll(): CommandResult = command { queue.resumeAll() }

    suspend fun cancel(id: DownloadId): CommandResult = command { queue.cancel(id) }

    /** Takes the job off the list; [files] says what happens to its files, which go to the OS trash unless told otherwise. */
    suspend fun remove(
        id: DownloadId,
        files: RemoveFiles = RemoveFiles.TRASH,
    ): CommandResult = command { queue.remove(id, files) }

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

    private suspend fun apply(fact: Fact) {
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
        publish()
        if (fact is Fact.Event) {
            logEvent(fact.event)
            (fact.event as? EngineEvent.PausedForTool)?.let(::fetchTool)
            eventFlow.tryEmit(fact.event)
        }
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
        block: () -> Unit,
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

    private fun sampleSpeed() {
        var total = 0L
        val samples = mutableListOf<TrafficMeter.Sample>()
        for (id in running) {
            val holder = live.value[id]
            val sample = holder?.bytes?.getAndUpdate { 0L } ?: 0L
            cache.speed(id, sample)
            total += sample
            val kind = holder?.kind ?: cache.kindOf(id)?.also { holder?.kind = it }
            if (kind != null && sample > 0) samples += TrafficMeter.Sample(id, kind, sample)
        }
        val now = clock.now()
        meter.second(now, network.current(), samples)
        takeLeftovers(now)
        totalSamples.addLast(total)
        while (totalSamples.size > SpeedWindow.SIZE) totalSamples.removeFirst()
        speedFlow.value = SpeedWindow(total, totalSamples.toList())
        publish()
    }

    private fun publish() {
        snapshots.value = cache.list()
    }

    private suspend fun filesOf(id: DownloadId): List<FileSnapshot> =
        store.downloads.files(id).map {
            FileSnapshot(it, store.downloads.segments(it.id))
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
    }

    /** A run that ended: the bytes it got since its last sampled second, to count with the next save. */
    private fun keepLeftover(holder: Live) {
        val left = holder.bytes.getAndUpdate { 0L }
        if (left <= 0) return
        val id =
            live.value.entries
                .firstOrNull { it.value === holder }
                ?.key ?: return
        val kind =
            holder.kind ?: snapshots.value
                .find { it.download.id == id }
                ?.download
                ?.kind ?: return
        leftovers.update { it + TrafficMeter.Sample(id, kind, left) }
    }

    private fun takeLeftovers(now: Instant) {
        val left = leftovers.getAndUpdate { emptyList() }
        if (left.isNotEmpty()) meter.bytes(now, network.current(), left)
    }

    /**
     * Saves what the meter counted (every TRAFFIC_SAVE_EVERY, and at stop). A failed save is put back for the next one.
     * A save cut short by cancellation isn't: the store finishes a write that was asked for, so it would count twice.
     */
    private suspend fun flushTraffic() {
        takeLeftovers(clock.now())
        val batch = meter.drain()
        if (batch.isEmpty) return
        try {
            traffic.add(batch, epochMinute(clock.now() - 24.hours))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            log.error("engine", e) { "traffic not saved; it is kept for the next save" }
            meter.restore(batch)
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
        val TRAFFIC_SAVE_EVERY = 10.seconds
        val PROGRESS_EVERY = 200.milliseconds
        val SPEED_EVERY = 1.seconds
        val DISK_CHECK_EVERY = 30.seconds
        val TOOL_RELEASE_DELAY = 2.seconds
        val DISK_WATCHED = setOf(DownloadStatus.PENDING, DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING, DownloadStatus.MUXING)
    }
}
