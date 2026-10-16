package dev.anydm.engine.queue

import dev.anydm.engine.core.Download
import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.DownloadStatus
import dev.anydm.engine.core.DownloadStatus.COMPLETED
import dev.anydm.engine.core.DownloadStatus.FAILED
import dev.anydm.engine.core.DownloadStatus.PENDING
import dev.anydm.engine.core.DownloadStatus.QUEUED
import dev.anydm.engine.core.DownloadStatus.SEEDING
import dev.anydm.engine.core.PrepareOutcome
import dev.anydm.engine.core.RunListener
import dev.anydm.engine.core.RunOutcome
import dev.anydm.engine.core.Step
import dev.anydm.engine.core.StepFailure
import dev.anydm.engine.core.StepRunner
import dev.anydm.engine.core.TransitionResult
import dev.anydm.engine.platform.FileSystem
import dev.anydm.engine.protocol.Protocol
import dev.anydm.engine.store.NewDownload
import dev.anydm.engine.store.Settings
import dev.anydm.engine.store.Store
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.time.Clock

/**
 * The queue's single coroutine and everything only it touches: the slots, the cached settings and every write of a
 * download row. Commands and slot reports arrive as [Message]s on one channel and are handled one at a time; after
 * each, [reschedule] starts what fits. The queue never waits for a slot's coroutine to end (that slot may be waiting
 * for the queue to store a move): a slot reports its end as a message instead.
 */
internal class QueueActor(
    private val store: Store,
    private val runner: StepRunner,
    @Suppress("unused") private val protocols: Map<DownloadKind, Protocol>,
    @Suppress("unused") private val fileSystem: FileSystem,
    private val clock: Clock,
) {
    private sealed interface Message {
        class Command(
            val block: suspend QueueActor.(CompletableDeferred<CommandResult>) -> Unit,
            val reply: CompletableDeferred<CommandResult>,
        ) : Message

        class Inspect(
            val reply: CompletableDeferred<QueueState>,
        ) : Message

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
    }

    private val inbox = Channel<Message>(Channel.UNLIMITED)
    private val downloads = store.downloads
    private val slots = mutableMapOf<DownloadId, Slot>()
    private lateinit var settings: Settings
    private lateinit var slotScope: CoroutineScope
    private var launched = false

    suspend fun start(scope: CoroutineScope) {
        check(!launched) { "the queue is already started" }
        launched = true
        val started = CompletableDeferred<Unit>()
        scope.launch {
            coroutineScope {
                slotScope = this
                try {
                    settings = store.settings.load()
                    reschedule()
                    started.complete(Unit)
                    for (message in inbox) handle(message)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    fail(e)
                    started.completeExceptionally(e)
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

    suspend fun inspect(): QueueState {
        val reply = CompletableDeferred<QueueState>()
        send(Message.Inspect(reply))
        return reply.await()
    }

    private fun send(message: Message) {
        if (inbox.trySend(message).isClosed) throw IllegalStateException("the queue has stopped")
    }

    private suspend fun handle(message: Message) {
        when (message) {
            is Message.Command -> {
                try {
                    message.block(this, message.reply)
                } catch (e: Throwable) {
                    message.reply.completeExceptionally(e)
                    throw e
                }
            }

            is Message.Inspect -> {
                message.reply.complete(state())
            }

            is Message.Moved -> {
                moved(message)
            }

            is Message.Ended -> {
                ended(message)
            }
        }
        reschedule()
    }

    // A store failure: nothing more is accepted, and everyone still waiting learns why.
    private fun fail(error: Throwable) {
        inbox.close()
        while (true) {
            when (val message = inbox.tryReceive().getOrNull() ?: break) {
                is Message.Command -> message.reply.completeExceptionally(error)
                is Message.Inspect -> message.reply.completeExceptionally(error)
                is Message.Moved -> message.ack.completeExceptionally(error)
                is Message.Ended -> Unit
            }
        }
    }

    private fun state() =
        QueueState(
            running = slots.keys.toSet(),
            stopping = emptySet(),
            limit = settings.concurrencyLimit,
            globalPause = settings.globalPause,
        )

    suspend fun add(new: NewDownload): CommandResult = CommandResult.Done(downloads.add(new.copy(queuedAt = clock.now())))

    // Scheduling

    private suspend fun reschedule() {
        val waiting = (downloads.list(PENDING) + downloads.list(QUEUED)).filter { it.id !in slots }
        val decision = schedule(waiting, slots.values.map { SlotView(it.download, stopping = false) }, settings.concurrencyLimit)
        val byId = waiting.associateBy { it.id }
        decision.start.forEach { launchSlot(byId.getValue(it)) }
    }

    private suspend fun launchSlot(download: Download) {
        val slot = Slot(download, downloads.mirrors(download.id).firstOrNull())
        slots[slot.id] = slot
        slot.job =
            slotScope.launch {
                var end: SlotEnd = SlotEnd.Cancelled
                try {
                    end = runSlot(slot, download)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    end = SlotEnd.Crashed(e)
                } finally {
                    inbox.trySend(Message.Ended(slot, end))
                }
            }
    }

    // On the slot's coroutine: the job's whole run, from probe to finish.
    private suspend fun runSlot(
        slot: Slot,
        start: Download,
    ): SlotEnd {
        // A QUEUED job has no probe result in memory (it waited, was paused or the app restarted): probe it again.
        val pending = if (start.status == QUEUED) report(slot, start.moved(PENDING)) else start
        val mirror =
            slot.mirror
                ?: return SlotEnd.Failed(pending.moved(FAILED), StepFailure(Step.PREFLIGHT, null, "no source to download from"))
        val listener =
            object : RunListener {
                override suspend fun moved(download: Download) {
                    report(slot, download)
                }
            }
        val ready =
            when (val prepared = runner.prepare(pending, mirror, listener)) {
                is PrepareOutcome.Failed -> return SlotEnd.Failed(prepared.download, prepared.failure)
                is PrepareOutcome.Ready -> prepared
            }
        return when (val ran = runner.run(ready, listener)) {
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
        ack.await()
        return download
    }

    private suspend fun moved(message: Message.Moved) {
        val slot = message.slot
        if (slots[slot.id] === slot) {
            slot.lastReported = message.download
            slot.download = save(slot.download.takeRun(message.download))
        }
        message.ack.complete(Unit)
    }

    private suspend fun ended(message: Message.Ended) {
        val slot = message.slot
        if (slots[slot.id] !== slot) return
        slots.remove(slot.id)
        val after =
            when (val end = message.end) {
                is SlotEnd.Finished -> {
                    slot.download.takeRun(end.outcome.download)
                }

                is SlotEnd.Failed -> {
                    slot.download.takeRun(end.download)
                }

                // The message has nowhere to be kept until #553 records Attempts (DIA-03), nor to be logged until #556.
                is SlotEnd.Crashed -> {
                    val reported = slot.lastReported
                    if (reported != null && reported.status in FINISHED) slot.download.takeRun(reported) else slot.download.crashed()
                }

                SlotEnd.Cancelled -> {
                    null
                }
            }
        if (after != null && after != slot.download) save(after)
    }

    private suspend fun save(download: Download): Download {
        downloads.update(download)
        return download
    }

    private companion object {
        val FINISHED = setOf(COMPLETED, SEEDING)
    }
}

/**
 * The fields a run decides (RunListener): status, and what the probe learned. Everything else, such as a priority
 * changed while the job ran, stays as the queue has it.
 */
private fun Download.takeRun(run: Download): Download =
    copy(status = run.status, saveFolder = run.saveFolder, fileName = run.fileName, size = run.size)

/** Where a job goes when its run threw: FAILED, through PENDING if it was between probe and transfer. */
private fun Download.crashed(): Download {
    val from = if (status == QUEUED) moved(PENDING) else this
    return if (from.status.canMoveTo(FAILED)) from.moved(FAILED) else from
}

internal fun Download.moved(status: DownloadStatus): Download =
    when (val result = transitionTo(status)) {
        is TransitionResult.Moved -> result.download
        is TransitionResult.Rejected -> error("the queue moved a job ${result.from} to ${result.to}")
    }
