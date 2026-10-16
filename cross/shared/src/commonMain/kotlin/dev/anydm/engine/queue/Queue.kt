package dev.anydm.engine.queue

import dev.anydm.engine.core.Download
import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.StepFailure
import dev.anydm.engine.core.StepRunner
import dev.anydm.engine.core.TransitionResult
import dev.anydm.engine.platform.FileResult
import dev.anydm.engine.platform.FileSystem
import dev.anydm.engine.protocol.Protocol
import dev.anydm.engine.store.NewDownload
import dev.anydm.engine.store.Store
import kotlinx.coroutines.CoroutineScope
import kotlin.time.Clock

/**
 * Runs jobs at most `Settings.concurrencyLimit` at a time, highest priority first, then longest waiting (FND-04), and
 * takes every command that changes a job (FND-12). Commands and the runs' reports go through one coroutine that alone
 * writes download rows, so no two changes to a job can race: that coroutine is the "single command path" the model and
 * the store rely on.
 *
 * A job holds its slot from probe to finish (StepRunner.prepare then run). Commands wait until [start] has loaded the
 * store. A store failure stops the queue: the coroutine fails into [scope], and later commands throw
 * IllegalStateException. The Engine (#555) owns [scope] and decides what to do then.
 */
class Queue(
    private val scope: CoroutineScope,
    store: Store,
    runner: StepRunner,
    protocols: Map<DownloadKind, Protocol>,
    fileSystem: FileSystem,
    clock: Clock = Clock.System,
) {
    private val actor = QueueActor(store, runner, protocols, fileSystem, clock)

    /** Loads the stored jobs and starts running them. Call once. */
    suspend fun start() = actor.start(scope)

    /** Stores a new PENDING job; it runs when a slot is free. */
    suspend fun add(new: NewDownload): CommandResult = actor.call { add(new) }

    /** Pauses the job for the user (USER); a running job is stopped first and its slot freed. */
    suspend fun pause(id: DownloadId): CommandResult = actor.callLater { pause(id, it) }

    /** Queues a paused job again, behind the jobs of its priority that already wait; clears every pause reason. */
    suspend fun resume(id: DownloadId): CommandResult = actor.call { resume(id) }

    /** Stops the job for good. Its partial files stay. */
    suspend fun cancel(id: DownloadId): CommandResult = actor.callLater { cancel(id, it) }

    /**
     * Removes the job and, with [RemoveFiles.TRASH], moves its outputs and partials to the trash first. If that fails
     * the job stays (paused for the user if it was active) and the result says why.
     */
    suspend fun remove(
        id: DownloadId,
        files: RemoveFiles,
    ): CommandResult = actor.callLater { remove(id, files, it) }

    /** Queues a FAILED job again with a fresh attempt count. */
    suspend fun retry(id: DownloadId): CommandResult = actor.call { retry(id) }

    /** Sets the job's priority. It never stops a running job: the new order applies to the next free slot. */
    suspend fun reprioritize(
        id: DownloadId,
        priority: Int,
    ): CommandResult = actor.call { reprioritize(id, priority) }

    /** For tests: what holds a slot right now. */
    internal suspend fun inspect(): QueueState = actor.inspect()
}

/** What `remove` does with a job's files. */
enum class RemoveFiles {
    /** Leave them where they are. */
    KEEP,

    /** Move the outputs and partials to the OS trash (FIL-09). */
    TRASH,
}

/** How a command ended. Expected outcomes are values; only a stopped queue or a store failure throws. */
sealed interface CommandResult {
    /** Done; [download] is the job as stored now, or null for a command on the whole queue or a removed job. */
    data class Done(
        val download: Download?,
    ) : CommandResult

    /** No job with that id (or that mirror). */
    data object NotFound : CommandResult

    /** The job can't make that move from where it is (FND-02). Nothing changed. */
    data class Rejected(
        val rejected: TransitionResult.Rejected,
    ) : CommandResult

    /** Remove with [RemoveFiles.TRASH] found no trash on this system; the job and its files stay. */
    data object TrashUnavailable : CommandResult

    /** Remove with [RemoveFiles.TRASH] couldn't move a file to the trash; the job stays. */
    data class TrashFailed(
        val failure: FileResult.Failed,
    ) : CommandResult

    /** The protocol refused the new link; nothing changed. */
    data class RelinkFailed(
        val failure: StepFailure,
    ) : CommandResult
}

internal data class QueueState(
    val running: Set<DownloadId>,
    val stopping: Set<DownloadId>,
    val limit: Int,
    val globalPause: Boolean,
)
