package dev.anydm.engine

import dev.anydm.engine.core.Attempt
import dev.anydm.engine.core.Download
import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.Mirror
import dev.anydm.engine.core.MirrorId
import dev.anydm.engine.core.RetryPolicy
import dev.anydm.engine.core.StepRunner
import dev.anydm.engine.platform.FileSystem
import dev.anydm.engine.protocol.Protocol
import dev.anydm.engine.queue.CommandResult
import dev.anydm.engine.queue.Queue
import dev.anydm.engine.queue.QueueObserver
import dev.anydm.engine.queue.RemoveFiles
import dev.anydm.engine.store.NewDownload
import dev.anydm.engine.store.Store
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.files.Path
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

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
    clock: Clock = Clock.System,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val stopTimeout: Duration = 10.seconds,
    checkpointEvery: Duration = StepRunner.DEFAULT_CHECKPOINT_EVERY,
    retryPolicy: RetryPolicy = RetryPolicy(),
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

    private val runner = StepRunner(fileSystem, store.downloads, protocols, defaultFolder, checkpointEvery = checkpointEvery)
    private val queue =
        Queue(CoroutineScope(scope.coroutineContext + queueJob), store, runner, protocols, fileSystem, clock, retryPolicy, Observer())

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

        override fun slotStarted(id: DownloadId) = inSlots.update { it + id }

        override fun slotEnded(id: DownloadId) {
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
        queue.start()
        val jobs = store.downloads.list()
        val attempts = jobs.associate { it.id to store.downloads.attempts(it.id) }
        val mirrors = jobs.associate { it.id to store.downloads.mirrors(it.id) }
        val files = jobs.associate { it.id to filesOf(it.id) }
        withContext(publisher) {
            cache.load(jobs, attempts, mirrors, files)
            publish()
        }
        scope.launch(publisher) { for (fact in facts) apply(fact) }
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
                if (ended == null) eventFlow.tryEmit(EngineEvent.StopTimedOut(inSlots.value.sortedBy { it.value }))
            }
        }
        engineJob.cancel()
        runCatching { store.close() }
        stateFlow.value = EngineState.Stopped
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

    suspend fun setLimit(limit: Int): CommandResult = command { queue.setLimit(limit) }

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

            is Fact.SlotEnded -> {
                if (cache.contains(
                        fact.id,
                    )
                ) {
                    cache.filesChanged(fact.id, filesOf(fact.id)).also { cache.stoppedRunning(fact.id) }
                }
            }

            is Fact.Event -> {
                Unit
            }
        }
        publish()
        if (fact is Fact.Event) eventFlow.tryEmit(fact.event)
    }

    private fun publish() {
        snapshots.value = cache.list()
    }

    private suspend fun filesOf(id: DownloadId): List<FileSnapshot> =
        store.downloads.files(id).map {
            FileSnapshot(it, store.downloads.segments(it.id))
        }

    // The queue's coroutine failed (the store): the engine stops with it.
    private fun failed(error: Throwable) {
        val now = stateFlow.value
        if (now is EngineState.Failed || now == EngineState.Stopped) return
        stateFlow.value = EngineState.Failed(error)
        eventFlow.tryEmit(EngineEvent.EngineFailed(error))
        scope.cancel()
        runCatching { store.close() }
    }
}
