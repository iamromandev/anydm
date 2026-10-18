package dev.anydm.engine

import dev.anydm.engine.core.Attempt
import dev.anydm.engine.core.Checksum
import dev.anydm.engine.core.Download
import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.DownloadStatus
import dev.anydm.engine.core.Mirror
import dev.anydm.engine.core.MirrorId
import dev.anydm.engine.core.RetryPolicy
import dev.anydm.engine.core.StepRunner
import dev.anydm.engine.files.FilesPlacement
import dev.anydm.engine.files.NameCleaner
import dev.anydm.engine.files.OsProfile
import dev.anydm.engine.log.Log
import dev.anydm.engine.log.LogLevel
import dev.anydm.engine.net.NetworkIdentity
import dev.anydm.engine.net.TrafficMeter
import dev.anydm.engine.net.epochMinute
import dev.anydm.engine.platform.FileSystem
import dev.anydm.engine.protocol.Protocol
import dev.anydm.engine.queue.CommandResult
import dev.anydm.engine.queue.Queue
import dev.anydm.engine.queue.QueueObserver
import dev.anydm.engine.queue.RemoveFiles
import dev.anydm.engine.store.NewDownload
import dev.anydm.engine.store.Store
import dev.anydm.engine.store.TrafficStore
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
    defaultFolder: Path,
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
    /** Whose file-name rules names follow (FIL-03); the desktop passes OsProfile.current(). */
    osProfile: OsProfile = OsProfile.LINUX,
) {
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

    private val runner =
        StepRunner(
            fileSystem,
            store.downloads,
            protocols,
            defaultFolder,
            placement =
                FilesPlacement(
                    NameCleaner(osProfile),
                    categories = { store.categories.list() },
                    policy = { store.settings.load().clashPolicy },
                    fileSystem = fileSystem,
                ),
            checkpointEvery = checkpointEvery,
        )

    /** What the queue reports to; internal so tests can play orders the queue only produces under load. */
    internal val observer: QueueObserver = Observer()

    private val queue =
        Queue(CoroutineScope(scope.coroutineContext + queueJob), store, runner, protocols, fileSystem, clock, retryPolicy, observer, log)

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

    suspend fun pause(id: DownloadId): CommandResult = command { queue.pause(id) }

    suspend fun resume(id: DownloadId): CommandResult = command { queue.resume(id) }

    suspend fun pauseAll(): CommandResult = command { queue.pauseAll() }

    suspend fun resumeAll(): CommandResult = command { queue.resumeAll() }

    suspend fun cancel(id: DownloadId): CommandResult = command { queue.cancel(id) }

    suspend fun remove(
        id: DownloadId,
        files: RemoveFiles,
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
    private suspend fun command(block: suspend () -> CommandResult): CommandResult {
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
            eventFlow.tryEmit(fact.event)
        }
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
    }
}
