package dev.anydm.engine.queue

import dev.anydm.engine.EngineEvent
import dev.anydm.engine.core.Attempt
import dev.anydm.engine.core.Checksum
import dev.anydm.engine.core.Download
import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.DownloadStatus
import dev.anydm.engine.core.DownloadStatus.CANCELLED
import dev.anydm.engine.core.DownloadStatus.COMPLETED
import dev.anydm.engine.core.DownloadStatus.DOWNLOADING
import dev.anydm.engine.core.DownloadStatus.FAILED
import dev.anydm.engine.core.DownloadStatus.MUXING
import dev.anydm.engine.core.DownloadStatus.PAUSED
import dev.anydm.engine.core.DownloadStatus.PENDING
import dev.anydm.engine.core.DownloadStatus.QUEUED
import dev.anydm.engine.core.DownloadStatus.SEEDING
import dev.anydm.engine.core.DuplicateCheck
import dev.anydm.engine.core.ErrorClass
import dev.anydm.engine.core.FailureAction
import dev.anydm.engine.core.Mirror
import dev.anydm.engine.core.MirrorId
import dev.anydm.engine.core.PauseReason
import dev.anydm.engine.core.PrepareOutcome
import dev.anydm.engine.core.ProfileId
import dev.anydm.engine.core.RetryPolicy
import dev.anydm.engine.core.RunListener
import dev.anydm.engine.core.RunOutcome
import dev.anydm.engine.core.Step
import dev.anydm.engine.core.StepFailure
import dev.anydm.engine.core.StepRunner
import dev.anydm.engine.core.TransitionResult
import dev.anydm.engine.core.effectiveSpeedLimit
import dev.anydm.engine.core.goneMirrors
import dev.anydm.engine.core.isSafeRelativePath
import dev.anydm.engine.core.mirrorToStart
import dev.anydm.engine.core.nextMirror
import dev.anydm.engine.core.partialPath
import dev.anydm.engine.core.targetPath
import dev.anydm.engine.files.Profile
import dev.anydm.engine.log.Log
import dev.anydm.engine.net.Bandwidth
import dev.anydm.engine.net.ProxyChoice
import dev.anydm.engine.net.RuleEffect
import dev.anydm.engine.net.minOfNotNull
import dev.anydm.engine.platform.FileResult
import dev.anydm.engine.platform.FileSystem
import dev.anydm.engine.protocol.Direction
import dev.anydm.engine.protocol.Meter
import dev.anydm.engine.protocol.Protocol
import dev.anydm.engine.protocol.RelinkRequest
import dev.anydm.engine.protocol.StepResult
import dev.anydm.engine.store.NewAttempt
import dev.anydm.engine.store.NewDownload
import dev.anydm.engine.store.NewItem
import dev.anydm.engine.store.Settings
import dev.anydm.engine.store.Store
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.time.TimeSource

/**
 * The queue's single coroutine and everything only it touches: the slots, the cached settings and every write of a
 * download row. Commands and slot reports arrive as [Message]s on one channel and are handled one at a time; after
 * each, [reschedule] starts what fits. The queue never waits for a slot's coroutine to end (that slot may be waiting
 * for the queue to store a move): a slot reports its end as a message instead.
 */
internal class QueueActor(
    private val store: Store,
    private val runner: StepRunner,
    private val protocols: Map<DownloadKind, Protocol>,
    private val fileSystem: FileSystem,
    private val clock: Clock,
    private val retryPolicy: RetryPolicy,
    private val observer: QueueObserver,
    private val log: Log,
    timeSource: TimeSource.WithComparableMarks,
    private val duplicates: DuplicateCheck,
    /** A job's profile (SET-02), read when it starts and when its limit changes; null if it is gone. */
    private val profiles: suspend (ProfileId) -> Profile? = { null },
    /** Whether the network is up right now (#543): asked once when a run fails TRANSIENT while the queue thinks it is online. */
    private val networkCheck: suspend () -> Boolean = { true },
) {
    /** Every running job's speed limits (NET-01..03), shared by all of them. */
    private val bandwidth = Bandwidth(timeSource)

    private sealed interface Message {
        class Command(
            val block: suspend QueueActor.(CompletableDeferred<CommandResult>) -> Unit,
            val reply: CompletableDeferred<CommandResult>,
        ) : Message

        class Ask<T>(
            val block: suspend QueueActor.() -> T,
            val reply: CompletableDeferred<T>,
        ) : Message {
            suspend fun answer(actor: QueueActor) {
                try {
                    reply.complete(actor.block())
                } catch (e: Throwable) {
                    reply.completeExceptionally(e)
                    throw e
                }
            }
        }

        /** The runner moved [slot]'s job to [download]; [ack] once it is stored. */
        class Moved(
            val slot: Slot,
            val download: Download,
            val ack: CompletableDeferred<Unit>,
        ) : Message

        class Ended(
            val slot: Slot,
            val end: SlotEnd,
        ) : Message

        /** The earliest pending retry may be due; handling any message reschedules. */
        data object Wake : Message
    }

    private val inbox = Channel<Message>(Channel.UNLIMITED)
    private val downloads = store.downloads
    private val slots = mutableMapOf<DownloadId, Slot>()

    // Each job's status as last stored, to report StatusChanged (#555).
    private val lastStatus = mutableMapOf<DownloadId, DownloadStatus>()
    private lateinit var settings: Settings

    // What the network rules say now (NET-05..08); set before the first job can start.
    private var rules: RuleEffect = RuleEffect.None

    // Whether the network is gone (NET-13); like the rules, set before the first job can start. Nothing runs while it is on.
    private var offline = false
    private lateinit var slotScope: CoroutineScope
    private var launched = false

    // The one timer for the earliest pending retry (nextAttemptAt), and the time it is armed for.
    private var wake: Job? = null
    private var wakeAt: Instant? = null

    suspend fun start(
        scope: CoroutineScope,
        initialRules: RuleEffect,
        initialOffline: Boolean,
    ) {
        check(!launched) { "the queue is already started" }
        launched = true
        val started = CompletableDeferred<Unit>()
        // ATOMIC: even if the scope is cancelled at once, the body runs to its first suspension, so stopped() answers
        // start() and every waiting command (#555).
        scope.launch(start = CoroutineStart.ATOMIC) {
            coroutineScope {
                slotScope = this
                // Inside the scope, before it waits for the slots: a slot may be waiting for an ack only stopped() gives.
                try {
                    settings = store.settings.load()
                    rules = initialRules
                    offline = initialOffline
                    for (job in downloads.list()) lastStatus[job.id] = job.status
                    reconcile()
                    applyLimits()
                    resumeSeeds()
                    reschedule()
                    started.complete(Unit)
                    for (message in inbox) handle(message)
                } catch (e: CancellationException) {
                    stopped(IllegalStateException("the queue has stopped", e), started)
                    throw e
                } catch (e: Throwable) {
                    log.error("queue", e) { "queue stopped" }
                    stopped(e, started)
                    throw e
                }
            }
        }
        started.await()
    }

    /** A queue that will never start: every command sent so far, and every later one, fails with [error]. */
    fun abandon(error: Throwable) {
        if (launched) return
        inbox.close()
        while (true) abandon(inbox.tryReceive().getOrNull() ?: break, error)
    }

    /** Runs [block] on the queue's coroutine and returns what it returned. */
    suspend fun call(block: suspend QueueActor.() -> CommandResult): CommandResult = callLater { it.complete(block()) }

    /** Runs [block] on the queue's coroutine; the command ends when [block], or something later, completes the reply. */
    suspend fun callLater(block: suspend QueueActor.(CompletableDeferred<CommandResult>) -> Unit): CommandResult {
        val reply = CompletableDeferred<CommandResult>()
        send(Message.Command(block, reply))
        return reply.await()
    }

    suspend fun inspect(): QueueState = ask { state() }

    /** Runs [block] on the queue's coroutine and returns its value, for a command that answers with more than a [CommandResult]. */
    suspend fun <T> inActor(block: suspend QueueActor.() -> T): T = ask(block)

    /** Runs [block] on the queue's coroutine and returns its value. */
    private suspend fun <T> ask(block: suspend QueueActor.() -> T): T {
        val reply = CompletableDeferred<T>()
        send(Message.Ask(block, reply))
        return reply.await()
    }

    private fun send(message: Message) {
        if (inbox.trySend(message).isClosed) throw IllegalStateException("the queue has stopped")
    }

    private suspend fun handle(message: Message) {
        try {
            dispatch(message)
        } catch (e: Throwable) {
            abandon(message, e)
            throw e
        }
        reschedule()
    }

    private suspend fun dispatch(message: Message) {
        when (message) {
            is Message.Command -> {
                message.block(this, message.reply)
            }

            is Message.Ask<*> -> {
                message.answer(this)
            }

            is Message.Moved -> {
                moved(message)
            }

            is Message.Ended -> {
                ended(message)
            }

            // Fired: the reschedule after it arms the next one, even for the same time if the clock was set back.
            Message.Wake -> {
                wake = null
                wakeAt = null
            }
        }
    }

    // The queue stopped (a store failure, or its scope was cancelled): nothing more is accepted, and everyone still
    // waiting, for a reply or an ack, learns why. Completing what is already complete changes nothing.
    private fun stopped(
        error: Throwable,
        started: CompletableDeferred<Unit>,
    ) {
        inbox.close()
        while (true) abandon(inbox.tryReceive().getOrNull() ?: break, error)
        for (slot in slots.values) slot.afterStop.forEach { it.reply?.completeExceptionally(error) }
        started.completeExceptionally(error)
    }

    private fun abandon(
        message: Message,
        error: Throwable,
    ) {
        when (message) {
            is Message.Command -> message.reply.completeExceptionally(error)
            is Message.Ask<*> -> message.reply.completeExceptionally(error)
            is Message.Moved -> message.ack.completeExceptionally(error)
            is Message.Ended -> message.slot.afterStop.forEach { it.reply?.completeExceptionally(error) }
            Message.Wake -> Unit
        }
    }

    private fun state() =
        QueueState(
            running = slots.keys.toSet(),
            stopping = slots.filterValues { it.stopping }.keys,
            limit = settings.concurrencyLimit,
            globalPause = settings.globalPause,
        )

    // Commands

    suspend fun add(requested: NewDownload): CommandResult {
        val new = withProfile(requested)
        duplicates.atAdd(new)?.let { return CommandResult.Duplicate(it) }
        val added = downloads.add(new, queuedAt = clock.now())
        lastStatus[added.id] = added.status
        observer.jobChanged(added)
        observer.mirrorsChanged(added.id, downloads.mirrors(added.id))
        observer.event(EngineEvent.Added(added.id))
        val held = hold(added)
        return CommandResult.Done(if (held == added) added else save(held))
    }

    /**
     * Adds a collection (MED-16): the parent and its children in one transaction. A child the duplicate check refuses is
     * left out and counted. The parent is only a label for its children and never takes a slot.
     */
    suspend fun addCollection(
        requestedParent: NewDownload,
        requestedChildren: List<NewDownload>,
        items: List<NewItem>,
    ): CollectionAdded? {
        val parent = withProfile(requestedParent)
        val children = requestedChildren.map { withProfile(it) }
        val found = duplicates.atAddAll(children)
        val kept = children.indices.filter { found[it] == null }
        // Every item refused: the same playlist added again. No empty collection is made for it.
        if (children.isNotEmpty() && kept.isEmpty()) return null
        val (collection, added) = downloads.addCollection(parent, kept.map { children[it] }, kept.map { items[it] }, clock.now())
        announce(listOf(collection) + added)
        return CollectionAdded(collection, hold(added), children.size - kept.size)
    }

    /** More items for a collection (a sync, MED-17), the same way; null if the collection is gone. */
    suspend fun addChildren(
        collection: DownloadId,
        children: List<NewDownload>,
        items: List<NewItem>,
    ): CollectionAdded? {
        val parent = downloads.get(collection) ?: return null
        val found = duplicates.atAddAll(children)
        val kept = children.indices.filter { found[it] == null }
        val added = downloads.addChildren(collection, kept.map { children[it] }, kept.map { items[it] }, clock.now())
        announce(added)
        return CollectionAdded(parent, hold(added), children.size - kept.size)
    }

    // A download that names no profile takes the default one; one that names a profile that doesn't exist, or is the
    // engine's own tool download, gets none (SET-02): an id nobody holds must not attach to a profile made later.
    private suspend fun withProfile(new: NewDownload): NewDownload {
        if (new.kind == DownloadKind.TOOL) return if (new.profileId == null) new else new.copy(profileId = null)
        val wanted = new.profileId ?: store.settings.load().defaultProfileId
        val valid = wanted?.takeIf { profiles(it) != null }
        return if (valid == new.profileId) new else new.copy(profileId = valid)
    }

    private suspend fun announce(jobs: List<Download>) {
        // Read first, then tell everyone without a pause in between: the engine can then take the facts of a whole
        // collection as one batch and build its snapshots once.
        val mirrors = jobs.map { downloads.mirrors(it.id) }
        jobs.forEachIndexed { i, job ->
            lastStatus[job.id] = job.status
            observer.jobChanged(job)
            observer.mirrorsChanged(job.id, mirrors[i])
            observer.event(EngineEvent.Added(job.id))
        }
    }

    // While global pause or a rule pause is on, jobs that start waiting wait paused instead.
    private suspend fun hold(jobs: List<Download>): List<Download> =
        jobs.map { job ->
            hold(job).let {
                if (it ==
                    job
                ) {
                    job
                } else {
                    save(it)
                }
            }
        }

    /** Takes a collection's child out of its collection, keeping the job (a forgotten item, MED-17). */
    suspend fun detach(child: DownloadId) {
        downloads.get(child)?.takeIf { it.parentId != null }?.let { save(it.copy(parentId = null)) }
    }

    /**
     * A command on a collection's items (MED-16), in this one step: each waiting item is changed at once, so none starts
     * before it is, and each running one is stopped and then changed. The reply comes when every stopped item has ended.
     */
    private suspend fun forItems(
        collection: DownloadId,
        reply: CompletableDeferred<CommandResult>,
        op: suspend (Download) -> CommandResult,
    ) {
        val stopping = mutableListOf<CompletableDeferred<CommandResult>>()
        for (child in downloads.children(collection)) {
            val slot = slots[child.id]
            if (slot != null) {
                stopping += CompletableDeferred<CommandResult>().also { stop(slot, op, it) }
            } else {
                op(child)
            }
        }
        if (stopping.isEmpty()) reply.complete(CommandResult.Done(null)) else replyWhenStopped(stopping, reply)
    }

    suspend fun pauseItems(
        collection: DownloadId,
        reply: CompletableDeferred<CommandResult>,
    ) = forItems(collection, reply) { change(it.pause(PauseReason.USER)) }

    suspend fun cancelItems(
        collection: DownloadId,
        reply: CompletableDeferred<CommandResult>,
    ) = forItems(collection, reply) { change(it.transitionTo(CANCELLED)) }

    suspend fun pause(
        id: DownloadId,
        reply: CompletableDeferred<CommandResult>,
    ) = stopThen(id, reply) { change(it.pause(PauseReason.USER)) }

    suspend fun resume(id: DownloadId): CommandResult =
        withJob(id) { job ->
            if (job.status != PAUSED) {
                CommandResult.Rejected(TransitionResult.Rejected(job.status, QUEUED))
            } else {
                // The user wants it now: a pending retry doesn't wait out its delay.
                change(job.transitionTo(QUEUED)) { hold(it.copy(queuedAt = clock.now(), nextAttemptAt = null)) }
            }
        }

    suspend fun cancel(
        id: DownloadId,
        reply: CompletableDeferred<CommandResult>,
    ) = stopThen(id, reply) { change(it.transitionTo(CANCELLED)) }

    suspend fun remove(
        id: DownloadId,
        files: RemoveFiles,
        reply: CompletableDeferred<CommandResult>,
    ) = stopThen(id, reply) { removeJob(it, files) }

    suspend fun retry(
        id: DownloadId,
        allowDuplicate: Boolean = false,
    ): CommandResult =
        withJob(id) { job ->
            if (job.status != FAILED) {
                CommandResult.Rejected(TransitionResult.Rejected(job.status, QUEUED))
            } else {
                change(job.transitionTo(QUEUED)) {
                    hold(
                        it.copy(
                            attemptCount = 0,
                            nextAttemptAt = null,
                            queuedAt = clock.now(),
                            allowDuplicate =
                                job.allowDuplicate || allowDuplicate,
                        ),
                    )
                }
            }
        }

    /** The user's checksum (HTTP-14), kept like the priority: a running job's reports never overwrite it. */
    suspend fun setChecksum(
        id: DownloadId,
        checksum: Checksum?,
    ): CommandResult {
        val slot = slots[id] ?: return withJob(id) { CommandResult.Done(save(it.copy(checksum = checksum))) }
        slot.download = save(slot.download.copy(checksum = checksum))
        return CommandResult.Done(slot.download)
    }

    suspend fun reprioritize(
        id: DownloadId,
        priority: Int,
    ): CommandResult {
        val slot = slots[id] ?: return withJob(id) { CommandResult.Done(save(it.copy(priority = priority))) }
        slot.download = save(slot.download.copy(priority = priority))
        bandwidth.setPriority(id, priority)
        return CommandResult.Done(slot.download)
    }

    /**
     * Global pause (FND-05): the setting first, so a crash halfway leaves it on and [reconcile] finishes the job. A
     * running job is stopped; the reply comes once every stopped slot has ended.
     */
    suspend fun pauseAll(reply: CompletableDeferred<CommandResult>) {
        settings = store.settings.update { it.copy(globalPause = true) }
        val stopping = mutableListOf<CompletableDeferred<CommandResult>>()
        for (job in downloads.list().filterNot { it.isCollection }) {
            val slot = slots[job.id]
            if (slot != null) {
                stopping += CompletableDeferred<CommandResult>().also { stop(slot, ::afterGlobalStop, it) }
            } else if (job.status in ACTIVE || job.status == PAUSED) {
                save((job.pause(PauseReason.GLOBAL) as TransitionResult.Moved).download)
            }
        }
        if (stopping.isEmpty()) {
            reply.complete(CommandResult.Done(null))
        } else {
            replyWhenStopped(stopping, reply)
        }
    }

    // Not a coroutine in slotScope: one cancelled along with a stopping queue would answer with its own
    // CancellationException instead of why the queue stopped. The handlers run on the queue's coroutine, which
    // completes every stopped slot's reply, so the count needs no lock.
    private fun replyWhenStopped(
        stopping: List<CompletableDeferred<CommandResult>>,
        reply: CompletableDeferred<CommandResult>,
    ) {
        var left = stopping.size
        for (stopped in stopping) {
            stopped.invokeOnCompletion { cause ->
                if (cause != null) {
                    reply.completeExceptionally(cause)
                } else if (--left == 0) {
                    reply.complete(CommandResult.Done(null))
                }
            }
        }
    }

    /** Ends global pause: the jobs first, then the setting (the reverse of [pauseAll], for the same reason). */
    suspend fun resumeAll(): CommandResult {
        for (job in downloads.list(PAUSED)) release(job)
        settings = store.settings.update { it.copy(globalPause = false) }
        return CommandResult.Done(null)
    }

    /**
     * Free space ran low in the [low] folders (FIL-08): their running jobs stop and their waiting ones wait, paused for
     * LOW_DISK; a job paused for it that is named in [release] is released. Idempotent, so the engine can send the whole
     * state on every check. A job that is also paused for another reason stays paused for that one.
     */
    suspend fun applyDiskState(
        low: Set<Path>,
        release: Set<DownloadId>,
        reply: CompletableDeferred<CommandResult>,
    ) {
        val stopping = mutableListOf<CompletableDeferred<CommandResult>>()
        for (job in downloads.list().filterNot { it.isCollection }) {
            val inLow = job.saveFolder?.let { Path(it) in low } == true
            val slot = slots[job.id]
            when {
                inLow && slot != null -> {
                    stopping += CompletableDeferred<CommandResult>().also { stop(slot, ::afterDiskStop, it) }
                }

                inLow && (job.status in WAITING || job.status == PAUSED) -> {
                    pauseForDisk(job)
                }

                !inLow && job.status == PAUSED && PauseReason.LOW_DISK in job.pauseReasons && job.id in release -> {
                    release(job, PauseReason.LOW_DISK)
                }
            }
        }
        if (stopping.isEmpty()) reply.complete(CommandResult.Done(null)) else replyWhenStopped(stopping, reply)
    }

    /**
     * A runtime tool was installed (BIN-01): every job paused for TOOLS lets go of that reason and waits for a slot
     * again. One that still lacks its tool pauses again on its next probe, so nothing here needs to know which tool each
     * job wants. A job also paused for another reason stays paused for that one.
     */
    suspend fun applyToolState(): CommandResult {
        for (job in downloads.list(PAUSED)) if (PauseReason.TOOLS in job.pauseReasons) release(job, PauseReason.TOOLS)
        return CommandResult.Done(null)
    }

    private suspend fun pauseForDisk(job: Download) {
        val paused = (job.pause(PauseReason.LOW_DISK) as TransitionResult.Moved).download
        if (paused != job) save(paused)
    }

    // A slot stopped for a low disk: paused for it, unless the next check finds the folder recovered.
    private suspend fun afterDiskStop(job: Download): CommandResult = change(job.pause(PauseReason.LOW_DISK))

    /** The limit every job shares by priority (NET-02, NET-03); applies to running jobs at once. */
    suspend fun setGlobalSpeedLimit(bytesPerSecond: Long?): CommandResult {
        settings = store.settings.update { it.copy(globalSpeedLimit = bytesPerSecond) }
        applyLimits()
        return CommandResult.Done(null)
    }

    /** The limit all uploads share (TOR-08): kept, and told to the protocols that pace their own sockets. */
    suspend fun setGlobalUploadLimit(bytesPerSecond: Long?): CommandResult {
        settings = store.settings.update { it.copy(globalUploadLimit = bytesPerSecond) }
        applyLimits()
        return CommandResult.Done(null)
    }

    // The only place a limit reaches the bandwidth and the protocols: the user's limit and the rules' cap, whichever is
    // lower, per direction. Each protocol once, however many kinds share it.
    private suspend fun applyLimits() {
        val down = minOfNotNull(settings.globalSpeedLimit, rules.downloadCap)
        val up = minOfNotNull(settings.globalUploadLimit, rules.uploadCap)
        bandwidth.setGlobal(down)
        for (protocol in protocols.values.distinct()) protocol.setGlobalLimits(down, up)
    }

    /**
     * The network rules changed (NET-05..08). A rule pause stops every running job that isn't MUXING and holds the
     * waiting ones, paused for SCHEDULE; lifting it releases only that reason. The caps apply at once. Idempotent, so the
     * engine can send the whole effect on every change. The reply comes once every stopped slot has ended.
     */
    suspend fun applyRules(
        effect: RuleEffect,
        reply: CompletableDeferred<CommandResult>,
    ) {
        rules = effect
        applyLimits()
        val stopping = mutableListOf<CompletableDeferred<CommandResult>>()
        for (job in downloads.list().filterNot { it.isCollection }) {
            val slot = slots[job.id]
            if (effect.pause) {
                when {
                    slot != null -> {
                        if (slot.download.status in RUNNABLE) {
                            stopping += CompletableDeferred<CommandResult>().also { stop(slot, ::afterHoldStop, it) }
                        }
                    }

                    job.status in WAITING || job.status == PAUSED -> {
                        val paused = (job.pause(PauseReason.SCHEDULE) as TransitionResult.Moved).download
                        if (paused != job) save(paused)
                    }
                }
            } else if (slot == null && job.status == PAUSED && PauseReason.SCHEDULE in job.pauseReasons) {
                release(job, PauseReason.SCHEDULE)
            }
        }
        if (stopping.isEmpty()) reply.complete(CommandResult.Done(null)) else replyWhenStopped(stopping, reply)
    }

    // A slot stopped by a rule pause or a network drop: paused for each of them that still applies when it has stopped
    // (so a stop that both asked for ends paused for both), and waiting again if neither does any more.
    private suspend fun afterHoldStop(job: Download): CommandResult {
        if (!rules.pause && !offline) return requeue(job)
        var held = job
        for (reason in listOfNotNull(PauseReason.SCHEDULE.takeIf { rules.pause }, PauseReason.NETWORK.takeIf { offline })) {
            when (val paused = held.pause(reason)) {
                is TransitionResult.Moved -> held = paused.download
                is TransitionResult.Rejected -> return CommandResult.Rejected(paused)
            }
        }
        return CommandResult.Done(save(held))
    }

    /**
     * The network dropped or came back (NET-13). Offline stops every running job that isn't MUXING and holds the waiting
     * ones, paused for NETWORK; SEEDING is left alone. Online releases only that reason. A pending retry keeps its time
     * and its attempt count: nothing is counted for the time offline. Idempotent, so the engine can send the state on
     * every change. The reply comes once every stopped slot has ended.
     */
    suspend fun applyNetwork(
        offline: Boolean,
        reply: CompletableDeferred<CommandResult>,
    ) {
        this.offline = offline
        val stopping = mutableListOf<CompletableDeferred<CommandResult>>()
        for (job in downloads.list().filterNot { it.isCollection }) {
            val slot = slots[job.id]
            if (offline) {
                when {
                    slot != null -> {
                        if (slot.download.status in RUNNABLE) {
                            stopping += CompletableDeferred<CommandResult>().also { stop(slot, ::afterHoldStop, it) }
                        }
                    }

                    job.status in WAITING || job.status == PAUSED -> {
                        val paused = (job.pause(PauseReason.NETWORK) as TransitionResult.Moved).download
                        if (paused != job) save(paused)
                    }
                }
            } else if (slot == null && job.status == PAUSED && PauseReason.NETWORK in job.pauseReasons) {
                release(job, PauseReason.NETWORK)
            }
        }
        if (stopping.isEmpty()) reply.complete(CommandResult.Done(null)) else replyWhenStopped(stopping, reply)
    }

    /**
     * Every running connection starts again (NET-12): the network changed or the machine woke, and a socket on the old
     * route would only time out. Each slot that isn't MUXING and isn't already stopping is stopped and queued again in
     * its place (the same `queuedAt`), so the jobs that ran start again and the ones that waited keep waiting. Nothing
     * is counted: a stop has no error class. The reply comes once every stopped slot has ended.
     */
    suspend fun restartConnections(
        reply: CompletableDeferred<CommandResult>,
        kinds: Set<DownloadKind>? = null,
    ) {
        val stopping = mutableListOf<CompletableDeferred<CommandResult>>()
        for (slot in slots.values.toList()) {
            if (slot.download.status in RUNNABLE && !slot.stopping && (kinds == null || slot.download.kind in kinds)) {
                stopping += CompletableDeferred<CommandResult>().also { stop(slot, ::restartOp, it) }
            }
        }
        if (stopping.isEmpty()) reply.complete(CommandResult.Done(null)) else replyWhenStopped(stopping, reply)
    }

    // Like requeue, but the job keeps its place in line: a waiting job of the same priority must not take its slot.
    private suspend fun restartOp(job: Download): CommandResult {
        val waiting = if (job.status == DOWNLOADING || job.status == MUXING) job.moved(QUEUED) else job
        return CommandResult.Done(if (waiting.status in WAITING) save(waiting) else waiting)
    }

    /**
     * Every job the last run left SEEDING is taken up again by its protocol, each in a coroutine of its own so the start
     * doesn't wait for a session. A protocol that says it can't (TRANSIENT: no library yet; PROXY: the keychain is locked
     * or the seeds disagree on a proxy) leaves the job SEEDING for the next start or the next proxy change, which asks again
     * ([retrySeeds]); any other failure ends the seed as FAILED with its message (TOR-05).
     */
    private suspend fun resumeSeeds() {
        for (job in downloads.list(SEEDING)) {
            val protocol = protocols[job.kind] ?: continue
            slotScope.launch {
                val result =
                    try {
                        protocol.resumeSeeding(job)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        StepResult.Failed(null, internalError(e))
                    }
                if (result !is StepResult.Failed) return@launch
                if (result.errorClass == ErrorClass.TRANSIENT || result.errorClass == ErrorClass.PROXY) {
                    log.warn("queue") { "seed ${job.id.value} can't be taken up yet: ${result.message}" }
                    return@launch
                }
                try {
                    call { finishSeeding(job.id, result.message) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IllegalStateException) {
                    // The queue is stopping; the job stays SEEDING and tries again at the next start.
                }
            }
        }
    }

    /** A proxy setting changed (NET-15): the seeds that couldn't be taken up for want of a usable proxy try again. A seed already up is left alone. */
    suspend fun retrySeeds() = resumeSeeds()

    /** A seed is over: SEEDING to COMPLETED, or to FAILED with [failure] on its last Attempt. Any other status is Rejected. */
    suspend fun finishSeeding(
        id: DownloadId,
        failure: String?,
    ): CommandResult =
        withJob(id) { job ->
            val target = if (failure == null) COMPLETED else FAILED
            if (job.status != SEEDING) return@withJob CommandResult.Rejected(TransitionResult.Rejected(job.status, target))
            if (failure != null) noteSeedFailure(id, failure)
            val result = change(job.transitionTo(target))
            if (result is CommandResult.Done) {
                observer.event(if (failure == null) EngineEvent.Completed(id, emptyList()) else EngineEvent.Failed(id, null, failure))
            }
            result
        }

    private suspend fun noteSeedFailure(
        id: DownloadId,
        message: String,
    ) {
        val now = clock.now()
        val last = downloads.attempts(id).lastOrNull()
        if (last != null) {
            updateAttempt(last.copy(endedAt = last.endedAt ?: now, message = message))
        } else {
            val made = downloads.addAttempt(NewAttempt(id, now, null))
            updateAttempt(made.copy(endedAt = now, message = message))
        }
    }

    /** The job's own limit (NET-02), kept like the priority; a running job slows or speeds up at once. */
    suspend fun setSpeedLimit(
        id: DownloadId,
        bytesPerSecond: Long?,
    ): CommandResult {
        val slot = slots[id] ?: return withJob(id) { CommandResult.Done(save(it.copy(speedLimit = bytesPerSecond))) }
        slot.download = save(slot.download.copy(speedLimit = bytesPerSecond))
        bandwidth.setLimit(id, effectiveSpeedLimit(slot.download, slot.download.profileId?.let { profiles(it) }))
        return CommandResult.Done(slot.download)
    }

    /** The proxy the job asks for (NET-15), kept like the priority; the engine restarts connections so a running job follows it. */
    suspend fun setProxy(
        id: DownloadId,
        choice: ProxyChoice,
    ): CommandResult {
        val slot = slots[id] ?: return withJob(id) { CommandResult.Done(save(it.copy(proxy = choice))) }
        slot.download = save(slot.download.copy(proxy = choice))
        return CommandResult.Done(slot.download)
    }

    /**
     * The job's start time (NET-09): it takes no slot before then. A running job that isn't MUXING and gets a future time
     * is stopped and waits again; any other job just keeps the time. A collection's own row only stores it.
     */
    suspend fun setStartTime(
        id: DownloadId,
        at: Instant?,
        reply: CompletableDeferred<CommandResult>,
    ) {
        val slot = slots[id]
        val job = slot?.download ?: downloads.get(id)
        when {
            job == null -> {
                reply.complete(CommandResult.NotFound)
            }

            job.isCollection -> {
                reply.complete(CommandResult.Done(save(job.copy(startAt = at))))
            }

            job.status == COMPLETED || job.status == CANCELLED || job.status == SEEDING -> {
                reply.complete(CommandResult.Rejected(TransitionResult.Rejected(job.status, QUEUED)))
            }

            slot != null && job.status != MUXING && at != null && at > clock.now() -> {
                // Stored first: whatever else was asked of the stopping slot runs on a job that already has it.
                slot.download = save(slot.download.copy(startAt = at))
                stop(slot, ::requeue, reply)
            }

            slot != null -> {
                slot.download = save(slot.download.copy(startAt = at))
                reply.complete(CommandResult.Done(slot.download))
            }

            else -> {
                reply.complete(CommandResult.Done(save(job.copy(startAt = at))))
            }
        }
    }

    /** The limit applies at once: the next reschedule preempts what is over it. */
    suspend fun setLimit(limit: Int): CommandResult {
        settings = store.settings.update { it.copy(concurrencyLimit = limit) }
        return CommandResult.Done(null)
    }

    // A slot stopped by pauseAll: paused GLOBAL, unless resumeAll came first while it stopped.
    private suspend fun afterGlobalStop(job: Download): CommandResult =
        if (settings.globalPause) change(job.pause(PauseReason.GLOBAL)) else requeue(job)

    // A slot stopped to give its place back: waiting again, at the back of its priority. It may have reached MUXING
    // before the stop landed; with no slot it can't stay there.
    private suspend fun requeue(job: Download): CommandResult {
        val waiting = if (job.status == DOWNLOADING || job.status == MUXING) job.moved(QUEUED) else job
        return CommandResult.Done(if (waiting.status in WAITING) save(waiting.copy(queuedAt = clock.now())) else waiting)
    }

    private suspend fun release(
        job: Download,
        reason: PauseReason = PauseReason.GLOBAL,
    ) {
        val released = (job.release(reason) as TransitionResult.Moved).download
        if (released != job) save(if (released.status == QUEUED) released.copy(queuedAt = clock.now()) else released)
    }

    /** While global pause, a rule pause or the network drop is on, a job that starts waiting waits paused instead (GLOBAL, SCHEDULE, NETWORK). */
    private fun hold(job: Download): Download {
        if (job.status !in WAITING || job.isCollection) return job
        var held = job
        if (settings.globalPause) held = (held.pause(PauseReason.GLOBAL) as TransitionResult.Moved).download
        if (rules.pause) held = (held.pause(PauseReason.SCHEDULE) as TransitionResult.Moved).download
        if (offline) held = (held.pause(PauseReason.NETWORK) as TransitionResult.Moved).download
        return held
    }

    /**
     * After a restart nothing runs: every attempt left open ends ("the app stopped", not counted), a job left mid-run
     * waits again (DOWNLOADING to PENDING for a fresh probe, MUXING to QUEUED), every waiting job has a queued time,
     * and every job agrees with the global pause setting. The partial files are checked when each job runs: it probes
     * again, and StepRunner.prepare starts it over if the source's validator changed (#554).
     */
    private suspend fun reconcile() {
        val now = clock.now()
        for (open in downloads.openAttempts()) updateAttempt(open.copy(endedAt = now, message = "the app stopped"))
        for (job in downloads.list().filterNot { it.isCollection }) {
            var fixed =
                when (job.status) {
                    DOWNLOADING -> job.moved(PENDING).copy(queuedAt = now)
                    MUXING -> job.moved(QUEUED).copy(queuedAt = now)
                    else -> job
                }
            if (fixed.status in WAITING && fixed.queuedAt == null) fixed = fixed.copy(queuedAt = now)
            fixed = reconciled(fixed, PauseReason.GLOBAL, settings.globalPause, now)
            fixed = reconciled(fixed, PauseReason.SCHEDULE, rules.pause, now)
            fixed = reconciled(fixed, PauseReason.NETWORK, offline, now)
            if (fixed != job) save(fixed)
        }
    }

    // A waiting or paused job takes [reason] while it applies, and lets go of it once it doesn't; a job that is then paused
    // for nothing waits again.
    private fun reconciled(
        job: Download,
        reason: PauseReason,
        applies: Boolean,
        now: Instant,
    ): Download {
        if (applies) {
            return if (job.status in WAITING || job.status == PAUSED) (job.pause(reason) as TransitionResult.Moved).download else job
        }
        val released = (job.release(reason) as TransitionResult.Moved).download
        return if (released.status == QUEUED && job.status != QUEUED) released.copy(queuedAt = now) else released
    }

    /**
     * Relink in three steps, so that the protocol's check, which may go to the network, runs on the caller's coroutine
     * and not the queue's: find the job and mirror, ask the protocol, then store the url and send the job back to probe.
     */
    suspend fun relink(
        id: DownloadId,
        mirrorId: MirrorId,
        url: String,
    ): CommandResult {
        val (job, mirror) =
            when (val found = ask { relinkTarget(id, mirrorId) }) {
                is RelinkTarget.Refused -> return found.result
                is RelinkTarget.Found -> found
            }
        val protocol =
            protocols[job.kind]
                ?: return CommandResult.RelinkFailed(StepFailure(Step.PROBE, null, "no protocol handles ${job.kind} downloads yet"))
        val checked =
            try {
                protocol.relink(RelinkRequest(job, mirror, url, downloads.context(id), runner.storedBytes(job)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                return CommandResult.RelinkFailed(StepFailure(Step.PROBE, null, internalError(e)))
            }
        val relinked =
            when (checked) {
                is StepResult.Failed -> return CommandResult.RelinkFailed(StepFailure(Step.PROBE, checked.errorClass, checked.message))
                is StepResult.Ok -> checked.value
            }
        return callLater { reply ->
            when (val found = relinkTarget(id, mirrorId)) {
                is RelinkTarget.Refused -> {
                    reply.complete(found.result)
                }

                is RelinkTarget.Found -> {
                    downloads.updateMirror(found.mirror.copy(url = url))
                    val mirrors = downloads.mirrors(id)
                    observer.mirrorsChanged(id, mirrors)
                    // The new link's validator becomes the job's only if the reprobe asks that mirror; another mirror's
                    // own token must not make the next probe see a changed source (FND-10).
                    val validator = relinked.validator?.takeIf { mirrorToStart(mirrors, downloads.attempts(id))?.id == mirrorId }
                    val op: suspend (Download) -> CommandResult = { job -> reprobe(job, validator) }
                    val slot = slots[id]
                    if (slot == null) reply.complete(op(found.job)) else stop(slot, op, reply)
                }
            }
        }
    }

    private sealed interface RelinkTarget {
        data class Found(
            val job: Download,
            val mirror: Mirror,
        ) : RelinkTarget

        data class Refused(
            val result: CommandResult,
        ) : RelinkTarget
    }

    // A MUXING job has its bytes already; a new link can't change them.
    private suspend fun relinkTarget(
        id: DownloadId,
        mirrorId: MirrorId,
    ): RelinkTarget {
        val job = slots[id]?.download ?: downloads.get(id) ?: return RelinkTarget.Refused(CommandResult.NotFound)
        val mirror = downloads.mirrors(id).find { it.id == mirrorId } ?: return RelinkTarget.Refused(CommandResult.NotFound)
        if (job.status == MUXING) return RelinkTarget.Refused(CommandResult.Rejected(TransitionResult.Rejected(MUXING, PENDING)))
        return RelinkTarget.Found(job, mirror)
    }

    // After a relink: a job that was running or waiting probes again (the new link) before it transfers, at once and
    // with a fresh retry budget. A job that was waiting keeps its place; one that was running waits at the back of its
    // priority. A job at rest (paused, failed, finished) keeps its status; it probes when it next runs. A [validator] the
    // relink adopted replaces the job's.
    private suspend fun reprobe(
        job: Download,
        validator: String?,
    ): CommandResult {
        val again =
            when (job.status) {
                DOWNLOADING, QUEUED -> job.moved(PENDING)

                MUXING -> job.moved(QUEUED)

                // reached MUXING before the stop landed
                else -> job
            }.copy(nextAttemptAt = null, attemptCount = 0, sourceValidator = validator ?: job.sourceValidator)
        if (again.status !in WAITING) return CommandResult.Done(if (again != job) save(again) else again)
        val queuedAt = if (job.status in WAITING) job.queuedAt else clock.now()
        return CommandResult.Done(save(hold(again.copy(queuedAt = queuedAt))))
    }

    private suspend fun removeJob(
        job: Download,
        files: RemoveFiles,
    ): CommandResult {
        if (files == RemoveFiles.TRASH) {
            // Held paused while its files go, so a refused trash leaves it waiting for the user, not running.
            val held = if (job.status in ACTIVE) save((job.pause(PauseReason.USER) as TransitionResult.Moved).download) else job
            when (val trashed = trash(held)) {
                null -> Unit
                is FileResult.TrashUnavailable -> return CommandResult.TrashUnavailable
                else -> return CommandResult.TrashFailed(trashed)
            }
        }
        // The protocol's own work folders are the engine's, never the user's: they go whatever happens to the files.
        job.saveFolder?.let(::Path)?.let { folder ->
            for (name in protocols[job.kind]?.workFolders(job).orEmpty()) {
                if (isSafeRelativePath(name)) fileSystem.deleteTree(targetPath(folder, name))
            }
        }
        downloads.remove(job.id)
        lastStatus.remove(job.id)
        observer.jobRemoved(job.id)
        observer.event(EngineEvent.Removed(job.id))
        return CommandResult.Done(null)
    }

    // Each planned file's output and partial; one that isn't there is fine. The first failure stops it.
    private suspend fun trash(job: Download): FileResult.Failed? {
        val folder = job.saveFolder?.let(::Path) ?: return null
        val finished = job.status == COMPLETED || job.status == SEEDING
        for (file in downloads.files(job.id)) {
            val target = targetPath(folder, file.path)
            // Only a file this job placed is its own: at its row's path another job's file can be sitting.
            val output = file.placedPath?.let { targetPath(folder, it) } ?: target.takeIf { finished }
            for (path in listOfNotNull(output, partialPath(target, job.id))) {
                when (val trashed = fileSystem.moveToTrash(path)) {
                    is FileResult.Ok, is FileResult.NotFound -> Unit
                    is FileResult.Failed -> return trashed
                }
            }
        }
        return null
    }

    private suspend fun withJob(
        id: DownloadId,
        op: suspend (Download) -> CommandResult,
    ): CommandResult = downloads.get(id)?.let { op(it) } ?: CommandResult.NotFound

    /** Runs [op] on job [id] now, or, if the job holds a slot, once that slot has stopped. */
    private suspend fun stopThen(
        id: DownloadId,
        reply: CompletableDeferred<CommandResult>,
        op: suspend (Download) -> CommandResult,
    ) {
        val slot = slots[id]
        if (slot == null) {
            reply.complete(withJob(id, op))
        } else {
            stop(slot, op, reply)
        }
    }

    // Never waits for the slot: its end arrives as a message, and ended() runs what was asked.
    private fun stop(
        slot: Slot,
        op: suspend (Download) -> CommandResult,
        reply: CompletableDeferred<CommandResult>?,
    ) {
        if (!slot.stopping) slot.job.cancel()
        slot.afterStop += AfterStop(op, reply)
    }

    private suspend fun change(
        result: TransitionResult,
        edit: (Download) -> Download = { it },
    ): CommandResult =
        when (result) {
            is TransitionResult.Moved -> CommandResult.Done(save(edit(result.download)))
            is TransitionResult.Rejected -> CommandResult.Rejected(result)
        }

    // Scheduling

    // A job waiting for a retry (nextAttemptAt in the future) or for its start time (startAt) isn't ready yet; the timer
    // wakes the queue when it is.
    private suspend fun reschedule() {
        val now = clock.now()
        val waiting = (downloads.list(PENDING) + downloads.list(QUEUED)).filter { it.id !in slots && !it.isCollection }
        if (rules.pause || offline) {
            // Nothing starts under a rule pause or while offline. Whatever got back to waiting without passing through hold
            // (a requeue after a stop, a release of LOW_DISK or TOOLS from a job paused after the pause took hold) is held
            // again here. This is the one place that guarantees it.
            for (job in waiting) hold(job).let { if (it != job) save(it) }
            armWake(null, now)
            return
        }
        val ready =
            waiting.filter { job ->
                job.nextAttemptAt.let { it == null || it <= now } && job.startAt.let { it == null || it <= now }
            }
        val decision = schedule(ready, slots.values.map { SlotView(it.download, it.stopping) }, settings.concurrencyLimit)
        val byId = ready.associateBy { it.id }
        decision.start.forEach { launchSlot(byId.getValue(it)) }
        decision.preempt.forEach { stop(slots.getValue(it), ::requeue, null) }
        armWake(waiting.flatMap { listOfNotNull(it.nextAttemptAt, it.startAt) }.filter { it > now }.minOrNull(), now)
    }

    private fun armWake(
        earliest: Instant?,
        now: Instant,
    ) {
        if (earliest == wakeAt) return
        wake?.cancel()
        wakeAt = earliest
        wake =
            earliest?.let { at ->
                slotScope.launch {
                    // At most a minute: delay runs on the monotonic clock, so a wall clock that moved (sleep, a
                    // correction) is noticed at the next check. A wake that comes early only re-arms.
                    delay(minOf(at - now, WAKE_CHECK))
                    inbox.trySend(Message.Wake)
                }
            }
    }

    private suspend fun launchSlot(waiting: Download) {
        // The retry is happening now.
        val download = if (waiting.nextAttemptAt != null) save(waiting.copy(nextAttemptAt = null)) else waiting
        val mirrors = downloads.mirrors(download.id)
        val attempts = downloads.attempts(download.id)
        val mirror = mirrorToStart(mirrors, attempts)
        val gone = goneMirrors(mirrors, attempts)
        val others = mirrors.filter { it.id != mirror?.id && it.id !in gone }
        val attempt = downloads.addAttempt(NewAttempt(download.id, clock.now(), mirror?.id)).also { observer.attemptChanged(it) }
        val failedCheck = attempts.lastOrNull()?.errorClass == ErrorClass.INTEGRITY
        val limiter =
            bandwidth.register(
                download.id,
                effectiveSpeedLimit(download, download.profileId?.let { profiles(it) }),
                download.priority,
            )
        val slot = Slot(download, mirror, attempt, writtenBytes(download.id), others, failedCheck, limiter)
        slots[slot.id] = slot
        observer.slotStarted(slot.id)
        var end: SlotEnd = SlotEnd.Cancelled
        slot.job =
            slotScope.launch {
                try {
                    end = runSlot(slot, download)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    log.error("queue", e) { "job ${slot.id.value} crashed" }
                    end = SlotEnd.Crashed(e)
                }
            }
        // Not a finally: a slot cancelled before its coroutine ran never runs one, and must still report.
        // slotEnded first: the queue starts a new slot for this job only after it handles Ended, so the engine always
        // hears of the old slot's end before the new one's start.
        slot.job.invokeOnCompletion {
            observer.slotEnded(slot.id)
            inbox.trySend(Message.Ended(slot, end))
        }
    }

    private suspend fun writtenBytes(id: DownloadId): Long =
        downloads.files(id).sumOf { file -> downloads.segments(file.id).sumOf { it.written } }

    // On the slot's coroutine: the job's whole run, from probe to finish.
    private suspend fun runSlot(
        slot: Slot,
        start: Download,
    ): SlotEnd {
        // A QUEUED job has no probe result in memory (it waited, was paused or the app restarted): probe it again.
        val pending = if (start.status == QUEUED) report(slot, start.moved(PENDING)) else start
        val mirror =
            slot.mirror
                ?: return SlotEnd.Failed(pending, StepFailure(Step.PREFLIGHT, null, "no source to download from"))
        val listener =
            object : RunListener {
                override suspend fun moved(download: Download) {
                    report(slot, download)
                }

                override suspend fun startedOver(reason: String) {
                    ask { noteStartOver(slot, reason) }
                }

                override fun progress(
                    downloaded: Long,
                    total: Long?,
                ) = observer.progress(slot.id, downloaded, total)

                override suspend fun checkpointed() = observer.checkpointed(slot.id)
            }
        val ready =
            when (val prepared = runner.prepare(pending, mirror, listener, slot.others, slot.failedCheck)) {
                is PrepareOutcome.Failed -> return SlotEnd.Failed(prepared.download, prepared.failure)
                is PrepareOutcome.Ready -> prepared
            }
        val meter =
            object : Meter {
                override fun record(
                    bytes: Long,
                    direction: Direction,
                    network: String?,
                ) = when {
                    direction == Direction.UP -> observer.sent(slot.id, bytes)
                    network != null -> observer.receivedOn(slot.id, bytes, network)
                    else -> observer.received(slot.id, bytes)
                }
            }
        return when (val ran = runner.run(ready, listener, slot.limiter, meter) { bandwidth.shareOf(slot.id) }) {
            is RunOutcome.Failed -> SlotEnd.Failed(ran.download, ran.failure)
            is RunOutcome.Finished -> SlotEnd.Finished(ran)
        }
    }

    // On the slot's coroutine: waits until the queue has stored the move.
    private suspend fun report(
        slot: Slot,
        download: Download,
    ): Download {
        val ack = CompletableDeferred<Unit>()
        send(Message.Moved(slot, download, ack))
        // Not cancellable: a stop that lands while COMPLETED is being stored must not skip the finish hook after it
        // (StepRunner runs it shielded only once this returns). The ack always comes, or stopped() fails it.
        withContext(NonCancellable) { ack.await() }
        return download
    }

    private suspend fun moved(message: Message.Moved) {
        val slot = message.slot
        if (slots[slot.id] === slot) {
            // Stored even while the slot stops: the command that stopped it runs after the slot's last move.
            slot.lastReported = message.download
            slot.download = save(slot.download.takeRun(message.download))
        }
        message.ack.complete(Unit)
    }

    private suspend fun ended(message: Message.Ended) {
        val slot = message.slot
        if (slots[slot.id] !== slot) return
        slots.remove(slot.id)
        bandwidth.unregister(slot.id)
        val (errorClass, problem) = endAttempt(slot, message.end)
        // Events about where the job went are told after the row is saved, so a listener sees the matching snapshot.
        val later = mutableListOf<EngineEvent>()
        val after =
            when (val end = message.end) {
                is SlotEnd.Finished -> {
                    slot.download.takeRun(end.outcome.download).copy(attemptCount = 0)
                }

                is SlotEnd.Failed -> {
                    afterFailure(slot, end, later)
                }

                is SlotEnd.Crashed -> {
                    afterCrash(slot)
                }

                // Nobody stopped it: a cancellation that escaped the protocol (a stray timeout) is a crash too.
                SlotEnd.Cancelled -> {
                    if (!slot.stopping) log.warn("queue") { "job ${slot.id.value} was cancelled unexpectedly" }
                    if (slot.stopping) null else afterCrash(slot)
                }
            }
        // A seed can end while the run that made it is still finishing: its job is over, and the slot's old view of it
        // (SEEDING) must not put it back.
        val over = after?.status == SEEDING && downloads.get(slot.id)?.status.let { it == COMPLETED || it == FAILED }
        if (after != null && after != slot.download && !over) save(after)
        later.forEach(observer::event)
        val end = message.end
        when {
            after?.status == COMPLETED && end is SlotEnd.Finished -> observer.event(EngineEvent.Completed(slot.id, end.outcome.outputs))
            after?.status == COMPLETED -> observer.event(EngineEvent.Completed(slot.id, emptyList()))
            after?.status == FAILED -> observer.event(EngineEvent.Failed(slot.id, errorClass, problem ?: "failed"))
        }
        for (next in slot.afterStop) {
            val result = withJob(slot.id, next.op)
            next.reply?.complete(result)
        }
    }

    // Every end closes the run's Attempt (DIA-03); a stop doesn't count as a failure, so it has no class.
    // The reason goes on the run's Attempt now, so it is there even if the app stops before the run ends.
    private suspend fun noteStartOver(
        slot: Slot,
        reason: String,
    ) {
        if (slots[slot.id] !== slot) return
        slot.note = reason
        updateAttempt(slot.attempt.copy(message = reason))
        observer.event(EngineEvent.StartedOver(slot.id, reason))
    }

    private suspend fun updateAttempt(attempt: Attempt) {
        downloads.updateAttempt(attempt)
        observer.attemptChanged(attempt)
    }

    // The class and message it stored, which a FAILED end reports as its event.
    private suspend fun endAttempt(
        slot: Slot,
        end: SlotEnd,
    ): Pair<ErrorClass?, String?> {
        val (errorClass, message) =
            when (end) {
                is SlotEnd.Finished -> null to slot.note
                is SlotEnd.Failed -> end.failure.errorClass to end.failure.message
                is SlotEnd.Crashed -> null to internalError(end.error)
                SlotEnd.Cancelled -> null to if (slot.stopping) "stopped" else "internal error: cancelled unexpectedly"
            }
        updateAttempt(slot.attempt.copy(endedAt = clock.now(), errorClass = errorClass, message = message))
        return errorClass to message
    }

    /**
     * Where a failed job goes (FND-06..08), as the [RetryPolicy] decides. The attempt is already closed, so a GONE
     * counts when choosing the next mirror. A retry starts waiting for a slot when its delay is over; a failover runs
     * again at once and keeps its place in line.
     */
    private suspend fun afterFailure(
        slot: Slot,
        end: SlotEnd.Failed,
        later: MutableList<EngineEvent>,
    ): Download {
        val job = slot.download.takeRun(end.download)
        // Progress is what the stored watermarks gained: bytes a run fetched but never checkpointed are fetched again.
        val madeProgress = writtenBytes(slot.id) > slot.writtenAtStart
        val attempts = downloads.attempts(slot.id)
        val next = nextMirror(downloads.mirrors(slot.id), attempts)
        val integrityBefore = attempts.any { it.id != slot.attempt.id && it.errorClass == ErrorClass.INTEGRITY }
        val offlineNow = if (end.failure.errorClass == ErrorClass.TRANSIENT) offlineAfterTransient() else offline
        val action = retryPolicy.decide(job, end.failure, madeProgress, next, settings.maxRetries, clock.now(), integrityBefore, offlineNow)
        return when (action) {
            is FailureAction.Retry -> {
                later += EngineEvent.RetryScheduled(slot.id, action.at, action.attemptCount)
                hold(
                    job.backToWaiting().copy(attemptCount = action.attemptCount, nextAttemptAt = action.at, queuedAt = action.at),
                )
            }

            is FailureAction.NextMirror -> {
                next?.let { later += EngineEvent.MovedToMirror(slot.id, it.id) }
                hold(job.backToWaiting().copy(attemptCount = 0, nextAttemptAt = null))
            }

            is FailureAction.Pause -> {
                if (action.reason == PauseReason.LOW_DISK) later += EngineEvent.PausedForDisk(slot.id)
                if (action.reason == PauseReason.TOOLS) later += EngineEvent.PausedForTool(slot.id, end.failure.message)
                var paused = (job.pause(action.reason) as TransitionResult.Moved).download
                // A drop that a rule pause overlaps ends paused for both, as a stop that both asked for would.
                if (action.reason == PauseReason.NETWORK &&
                    rules.pause
                ) {
                    paused = (paused.pause(PauseReason.SCHEDULE) as TransitionResult.Moved).download
                }
                paused.copy(nextAttemptAt = null)
            }

            FailureAction.StartOver -> {
                when (val dropped = runner.startOver(job)) {
                    is StepResult.Failed -> {
                        log.warn("queue") { "job ${slot.id.value} can't start over: ${dropped.message}" }
                        job.toFailed().copy(nextAttemptAt = null)
                    }

                    is StepResult.Ok -> {
                        later += EngineEvent.StartedOver(slot.id, "checksum mismatch: starting from zero")
                        hold(job.backToWaiting().copy(attemptCount = 0, nextAttemptAt = null))
                    }
                }
            }

            FailureAction.Fail -> {
                job.toFailed().copy(nextAttemptAt = null)
            }
        }
    }

    // A TRANSIENT failure may be the network dropping before the monitor noticed: ask once. A failed check also makes the
    // queue offline, so hold() agrees until the engine's own applyNetwork arrives, which then changes nothing for this
    // job. A check that throws is no answer: the failure counts as today.
    private suspend fun offlineAfterTransient(): Boolean {
        if (offline) return true
        val online =
            try {
                networkCheck()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                log.warn("queue") { "network check failed: ${e.message}" }
                true
            }
        if (!online) offline = true
        return !online
    }

    // A throw after COMPLETED or SEEDING was reported is a finished job (FinishHook); anything earlier fails it.
    private fun afterCrash(slot: Slot): Download {
        val reported = slot.lastReported
        return if (reported != null && reported.status in FINISHED) slot.download.takeRun(reported) else slot.download.toFailed()
    }

    /** Stores the row and reports it, with a StatusChanged when the status moved since the last save. */
    private suspend fun save(download: Download): Download {
        downloads.update(download)
        val before = lastStatus.put(download.id, download.status)
        observer.jobChanged(download)
        if (before != null && before != download.status) observer.event(EngineEvent.StatusChanged(download.id, before, download.status))
        return download
    }

    private companion object {
        val FINISHED = setOf(COMPLETED, SEEDING)
        val ACTIVE = setOf(PENDING, QUEUED, DOWNLOADING, MUXING)
        val RUNNABLE = setOf(PENDING, QUEUED, DOWNLOADING)
        val WAITING = setOf(PENDING, QUEUED)
        val WAKE_CHECK = 1.minutes
    }
}

/**
 * The fields a run decides (RunListener): status, its category and folder, and what the probe learned. Everything else, such as a priority
 * changed while the job ran, stays as the queue has it.
 */
private fun Download.takeRun(run: Download): Download =
    copy(
        status = run.status,
        categoryId = run.categoryId,
        saveFolder = run.saveFolder,
        fileName = run.fileName,
        size = run.size,
        sourceValidator = run.sourceValidator,
    )

/** How a bug (a throw that isn't an expected failure) is reported in an Attempt or a command's result. */
private fun internalError(error: Throwable) = "internal error: ${error.message ?: error::class.simpleName}"

/** Waiting again after a failed run: a new probe from PENDING, or QUEUED (which probes again) after post-processing. */
private fun Download.backToWaiting(): Download =
    when (status) {
        DOWNLOADING -> moved(PENDING)
        MUXING -> moved(QUEUED)
        else -> this
    }

/** FAILED, through PENDING if it was between probe and transfer: where a job goes when its run threw or failed. */
private fun Download.toFailed(): Download {
    val from = if (status == QUEUED) moved(PENDING) else this
    return if (from.status.canMoveTo(FAILED)) from.moved(FAILED) else from
}

internal fun Download.moved(status: DownloadStatus): Download =
    when (val result = transitionTo(status)) {
        is TransitionResult.Moved -> result.download
        is TransitionResult.Rejected -> error("the queue moved a job ${result.from} to ${result.to}")
    }
