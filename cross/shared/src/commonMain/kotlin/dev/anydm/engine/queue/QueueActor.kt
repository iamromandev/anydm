package dev.anydm.engine.queue

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
import dev.anydm.engine.core.FailureAction
import dev.anydm.engine.core.Mirror
import dev.anydm.engine.core.MirrorId
import dev.anydm.engine.core.PauseReason
import dev.anydm.engine.core.PrepareOutcome
import dev.anydm.engine.core.RetryPolicy
import dev.anydm.engine.core.RunListener
import dev.anydm.engine.core.RunOutcome
import dev.anydm.engine.core.Step
import dev.anydm.engine.core.StepFailure
import dev.anydm.engine.core.StepRunner
import dev.anydm.engine.core.TransitionResult
import dev.anydm.engine.core.mirrorToStart
import dev.anydm.engine.core.nextMirror
import dev.anydm.engine.core.partialPath
import dev.anydm.engine.core.targetPath
import dev.anydm.engine.platform.FileResult
import dev.anydm.engine.platform.FileSystem
import dev.anydm.engine.protocol.Protocol
import dev.anydm.engine.protocol.StepResult
import dev.anydm.engine.store.NewAttempt
import dev.anydm.engine.store.NewDownload
import dev.anydm.engine.store.Settings
import dev.anydm.engine.store.Store
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlin.time.Clock
import kotlin.time.Instant

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
) {
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
    private lateinit var settings: Settings
    private lateinit var slotScope: CoroutineScope
    private var launched = false

    // The one timer for the earliest pending retry (nextAttemptAt), and the time it is armed for.
    private var wake: Job? = null
    private var wakeAt: Instant? = null

    suspend fun start(scope: CoroutineScope) {
        check(!launched) { "the queue is already started" }
        launched = true
        val started = CompletableDeferred<Unit>()
        scope.launch {
            coroutineScope {
                slotScope = this
                // Inside the scope, before it waits for the slots: a slot may be waiting for an ack only stopped() gives.
                try {
                    settings = store.settings.load()
                    reconcile()
                    reschedule()
                    started.complete(Unit)
                    for (message in inbox) handle(message)
                } catch (e: CancellationException) {
                    stopped(IllegalStateException("the queue has stopped", e), started)
                    throw e
                } catch (e: Throwable) {
                    stopped(e, started)
                    throw e
                }
            }
        }
        started.await()
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

    suspend fun add(new: NewDownload): CommandResult {
        val added = downloads.add(new, queuedAt = clock.now())
        val held = holdIfGlobal(added)
        return CommandResult.Done(if (held == added) added else save(held))
    }

    suspend fun pause(
        id: DownloadId,
        reply: CompletableDeferred<CommandResult>,
    ) = stopThen(id, reply) { change(it.pause(PauseReason.USER)) }

    suspend fun resume(id: DownloadId): CommandResult =
        withJob(id) { job ->
            if (job.status != PAUSED) {
                CommandResult.Rejected(TransitionResult.Rejected(job.status, QUEUED))
            } else {
                change(job.transitionTo(QUEUED)) { holdIfGlobal(it.copy(queuedAt = clock.now())) }
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

    suspend fun retry(id: DownloadId): CommandResult =
        withJob(id) { job ->
            if (job.status != FAILED) {
                CommandResult.Rejected(TransitionResult.Rejected(job.status, QUEUED))
            } else {
                change(job.transitionTo(QUEUED)) {
                    holdIfGlobal(it.copy(attemptCount = 0, nextAttemptAt = null, queuedAt = clock.now()))
                }
            }
        }

    suspend fun reprioritize(
        id: DownloadId,
        priority: Int,
    ): CommandResult {
        val slot = slots[id] ?: return withJob(id) { CommandResult.Done(save(it.copy(priority = priority))) }
        slot.download = save(slot.download.copy(priority = priority))
        return CommandResult.Done(slot.download)
    }

    /**
     * Global pause (FND-05): the setting first, so a crash halfway leaves it on and [reconcile] finishes the job. A
     * running job is stopped; the reply comes once every stopped slot has ended.
     */
    suspend fun pauseAll(reply: CompletableDeferred<CommandResult>) {
        settings = store.settings.update { it.copy(globalPause = true) }
        val stopping = mutableListOf<CompletableDeferred<CommandResult>>()
        for (job in downloads.list()) {
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
            slotScope.launch {
                try {
                    stopping.awaitAll()
                    reply.complete(CommandResult.Done(null))
                } catch (e: Throwable) {
                    reply.completeExceptionally(e)
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

    private suspend fun release(job: Download) {
        val released = (job.release(PauseReason.GLOBAL) as TransitionResult.Moved).download
        if (released != job) save(if (released.status == QUEUED) released.copy(queuedAt = clock.now()) else released)
    }

    /** While global pause is on, a job that starts waiting waits paused instead. */
    private fun holdIfGlobal(job: Download): Download =
        if (settings.globalPause && job.status in WAITING) (job.pause(PauseReason.GLOBAL) as TransitionResult.Moved).download else job

    /**
     * After a restart nothing runs: every attempt left open ends ("the app stopped", not counted), a job left mid-run
     * waits again (DOWNLOADING to PENDING for a fresh probe, MUXING to QUEUED), every waiting job has a queued time,
     * and every job agrees with the global pause setting. Checking the partial files is #554's.
     */
    private suspend fun reconcile() {
        val now = clock.now()
        for (open in downloads.openAttempts()) downloads.updateAttempt(open.copy(endedAt = now, message = "the app stopped"))
        for (job in downloads.list()) {
            var fixed =
                when (job.status) {
                    DOWNLOADING -> job.moved(PENDING).copy(queuedAt = now)
                    MUXING -> job.moved(QUEUED).copy(queuedAt = now)
                    else -> job
                }
            if (fixed.status in WAITING && fixed.queuedAt == null) fixed = fixed.copy(queuedAt = now)
            if (settings.globalPause) {
                if (fixed.status in WAITING ||
                    fixed.status == PAUSED
                ) {
                    fixed = (fixed.pause(PauseReason.GLOBAL) as TransitionResult.Moved).download
                }
                if (fixed != job) save(fixed)
            } else {
                if (fixed != job) save(fixed)
                release(fixed)
            }
        }
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
        when (val checked = protocol.relink(job, mirror, url)) {
            is StepResult.Failed -> return CommandResult.RelinkFailed(StepFailure(Step.PROBE, checked.errorClass, checked.message))
            is StepResult.Ok -> Unit
        }
        return callLater { reply ->
            when (val found = relinkTarget(id, mirrorId)) {
                is RelinkTarget.Refused -> {
                    reply.complete(found.result)
                }

                is RelinkTarget.Found -> {
                    downloads.updateMirror(found.mirror.copy(url = url))
                    val slot = slots[id]
                    if (slot == null) reply.complete(reprobe(found.job)) else stop(slot, ::reprobe, reply)
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

    // After a relink: a job that was running or waiting probes again (the new link) before it transfers. A job at rest
    // (paused, failed, finished) keeps its status; it probes when it next runs.
    private suspend fun reprobe(job: Download): CommandResult {
        val again =
            when (job.status) {
                DOWNLOADING, QUEUED -> job.moved(PENDING)

                MUXING -> job.moved(QUEUED)

                // reached MUXING before the stop landed
                else -> job
            }
        if (again.status !in WAITING) return CommandResult.Done(again)
        return CommandResult.Done(save(holdIfGlobal(again.copy(queuedAt = clock.now()))))
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
        downloads.remove(job.id)
        return CommandResult.Done(null)
    }

    // Each planned file's output and partial; one that isn't there is fine. The first failure stops it.
    private suspend fun trash(job: Download): FileResult.Failed? {
        val folder = job.saveFolder?.let(::Path) ?: return null
        for (file in downloads.files(job.id)) {
            val target = targetPath(folder, file.path)
            for (path in listOf(target, partialPath(target, job.id))) {
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

    // A job waiting for a retry (nextAttemptAt in the future) isn't ready yet; the timer wakes the queue when it is.
    private suspend fun reschedule() {
        val now = clock.now()
        val waiting = (downloads.list(PENDING) + downloads.list(QUEUED)).filter { it.id !in slots }
        val ready = waiting.filter { job -> job.nextAttemptAt.let { it == null || it <= now } }
        val decision = schedule(ready, slots.values.map { SlotView(it.download, it.stopping) }, settings.concurrencyLimit)
        val byId = ready.associateBy { it.id }
        decision.start.forEach { launchSlot(byId.getValue(it)) }
        decision.preempt.forEach { stop(slots.getValue(it), ::requeue, null) }
        armWake(waiting.mapNotNull { it.nextAttemptAt }.filter { it > now }.minOrNull(), now)
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
                    delay(at - now)
                    inbox.trySend(Message.Wake)
                }
            }
    }

    private suspend fun launchSlot(waiting: Download) {
        // The retry is happening now.
        val download = if (waiting.nextAttemptAt != null) save(waiting.copy(nextAttemptAt = null)) else waiting
        val mirror = mirrorToStart(downloads.mirrors(download.id), downloads.attempts(download.id))
        val attempt = downloads.addAttempt(NewAttempt(download.id, clock.now(), mirror?.id))
        val slot = Slot(download, mirror, attempt, writtenBytes(download.id))
        slots[slot.id] = slot
        var end: SlotEnd = SlotEnd.Cancelled
        slot.job =
            slotScope.launch {
                try {
                    end = runSlot(slot, download)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    end = SlotEnd.Crashed(e)
                }
            }
        // Not a finally: a slot cancelled before its coroutine ran never runs one, and must still report.
        slot.job.invokeOnCompletion { inbox.trySend(Message.Ended(slot, end)) }
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
        // The most bytes the run reported: did this attempt make progress (RetryPolicy's budget)?
        var maxDownloaded: Long? = null
        val listener =
            object : RunListener {
                override suspend fun moved(download: Download) {
                    report(slot, download)
                }

                override fun progress(
                    downloaded: Long,
                    total: Long?,
                ) {
                    maxDownloaded = maxOf(maxDownloaded ?: downloaded, downloaded)
                }
            }
        val ready =
            when (val prepared = runner.prepare(pending, mirror, listener)) {
                is PrepareOutcome.Failed -> return SlotEnd.Failed(prepared.download, prepared.failure, maxDownloaded)
                is PrepareOutcome.Ready -> prepared
            }
        return when (val ran = runner.run(ready, listener)) {
            is RunOutcome.Failed -> SlotEnd.Failed(ran.download, ran.failure, maxDownloaded)
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
        endAttempt(slot, message.end)
        val after =
            when (val end = message.end) {
                is SlotEnd.Finished -> {
                    slot.download.takeRun(end.outcome.download).copy(attemptCount = 0)
                }

                is SlotEnd.Failed -> {
                    afterFailure(slot, end)
                }

                is SlotEnd.Crashed -> {
                    afterCrash(slot)
                }

                // Nobody stopped it: a cancellation that escaped the protocol (a stray timeout) is a crash too.
                SlotEnd.Cancelled -> {
                    if (slot.stopping) null else afterCrash(slot)
                }
            }
        if (after != null && after != slot.download) save(after)
        for (next in slot.afterStop) {
            val result = withJob(slot.id, next.op)
            next.reply?.complete(result)
        }
    }

    // Every end closes the run's Attempt (DIA-03); a stop doesn't count as a failure, so it has no class.
    private suspend fun endAttempt(
        slot: Slot,
        end: SlotEnd,
    ) {
        val (errorClass, message) =
            when (end) {
                is SlotEnd.Finished -> null to null
                is SlotEnd.Failed -> end.failure.errorClass to end.failure.message
                is SlotEnd.Crashed -> null to "internal error: ${end.error.message ?: end.error::class.simpleName}"
                SlotEnd.Cancelled -> null to if (slot.stopping) "stopped" else "internal error: cancelled unexpectedly"
            }
        downloads.updateAttempt(slot.attempt.copy(endedAt = clock.now(), errorClass = errorClass, message = message))
    }

    /**
     * Where a failed job goes (FND-06..08), as the [RetryPolicy] decides. The attempt is already closed, so a GONE
     * counts when choosing the next mirror. A retry starts waiting for a slot when its delay is over; a failover runs
     * again at once and keeps its place in line.
     */
    private suspend fun afterFailure(
        slot: Slot,
        end: SlotEnd.Failed,
    ): Download {
        val job = slot.download.takeRun(end.download)
        val madeProgress = end.maxDownloaded != null && end.maxDownloaded > slot.writtenAtStart
        val next = nextMirror(downloads.mirrors(slot.id), downloads.attempts(slot.id))
        return when (val action = retryPolicy.decide(job, end.failure, madeProgress, next, settings.maxRetries, clock.now())) {
            is FailureAction.Retry -> {
                holdIfGlobal(
                    job.backToWaiting().copy(attemptCount = action.attemptCount, nextAttemptAt = action.at, queuedAt = action.at),
                )
            }

            is FailureAction.NextMirror -> {
                holdIfGlobal(job.backToWaiting().copy(attemptCount = 0, nextAttemptAt = null))
            }

            is FailureAction.Pause -> {
                (job.pause(action.reason) as TransitionResult.Moved).download.copy(nextAttemptAt = null)
            }

            FailureAction.Fail -> {
                job.toFailed().copy(nextAttemptAt = null)
            }
        }
    }

    // A throw after COMPLETED or SEEDING was reported is a finished job (FinishHook); anything earlier fails it.
    private fun afterCrash(slot: Slot): Download {
        val reported = slot.lastReported
        return if (reported != null && reported.status in FINISHED) slot.download.takeRun(reported) else slot.download.toFailed()
    }

    private suspend fun save(download: Download): Download {
        downloads.update(download)
        return download
    }

    private companion object {
        val FINISHED = setOf(COMPLETED, SEEDING)
        val ACTIVE = setOf(PENDING, QUEUED, DOWNLOADING, MUXING)
        val WAITING = setOf(PENDING, QUEUED)
    }
}

/**
 * The fields a run decides (RunListener): status, and what the probe learned. Everything else, such as a priority
 * changed while the job ran, stays as the queue has it.
 */
private fun Download.takeRun(run: Download): Download =
    copy(status = run.status, saveFolder = run.saveFolder, fileName = run.fileName, size = run.size)

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
