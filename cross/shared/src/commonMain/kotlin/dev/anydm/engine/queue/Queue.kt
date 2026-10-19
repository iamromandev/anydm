package dev.anydm.engine.queue

import dev.anydm.engine.core.Checksum
import dev.anydm.engine.core.Download
import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.DuplicateCheck
import dev.anydm.engine.core.DuplicateFound
import dev.anydm.engine.core.MirrorId
import dev.anydm.engine.core.ProfileId
import dev.anydm.engine.core.RetryPolicy
import dev.anydm.engine.core.StepFailure
import dev.anydm.engine.core.StepRunner
import dev.anydm.engine.core.TransitionResult
import dev.anydm.engine.files.Profile
import dev.anydm.engine.log.Log
import dev.anydm.engine.platform.FileResult
import dev.anydm.engine.platform.FileSystem
import dev.anydm.engine.protocol.Protocol
import dev.anydm.engine.store.NewDownload
import dev.anydm.engine.store.NewItem
import dev.anydm.engine.store.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.io.files.Path
import kotlin.time.Clock
import kotlin.time.TimeSource

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
class Queue internal constructor(
    private val scope: CoroutineScope,
    private val store: Store,
    runner: StepRunner,
    protocols: Map<DownloadKind, Protocol>,
    fileSystem: FileSystem,
    clock: Clock = Clock.System,
    retryPolicy: RetryPolicy = RetryPolicy(),
    observer: QueueObserver = QueueObserver.None,
    log: Log = Log.None,
    /** What speed limits are measured on (NET-01): monotonic, or a test's virtual time. */
    timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
    /** Asked on every add (FIL-05). */
    duplicates: DuplicateCheck = DuplicateCheck.None,
    /** A job's profile (SET-02); null if it is gone. */
    profiles: suspend (ProfileId) -> Profile? = { null },
) {
    private val actor =
        QueueActor(store, runner, protocols, fileSystem, clock, retryPolicy, observer, log, timeSource, duplicates, profiles)

    /** Loads the stored jobs and starts running them. Call once. */
    suspend fun start() = actor.start(scope)

    /** Stores a new PENDING job; it runs when a slot is free. */
    suspend fun add(new: NewDownload): CommandResult = actor.call { add(new) }

    /** A collection and its children, added together (MED-16). */
    suspend fun addCollection(
        parent: NewDownload,
        children: List<NewDownload>,
        items: List<NewItem>,
    ): CollectionAdded = actor.inActor { addCollection(parent, children, items) }

    /** More items for an existing collection (a sync, MED-17); null if the collection is gone. */
    suspend fun addChildren(
        collection: DownloadId,
        children: List<NewDownload>,
        items: List<NewItem>,
    ): CollectionAdded? = actor.inActor { addChildren(collection, children, items) }

    /**
     * Pauses what runs or waits in the [low] folders and releases the jobs named in [release], which were paused for a
     * low disk and now have room (FIL-08). Any other job paused for it, say because its file didn't fit at the start,
     * waits until the engine finds it room.
     */
    suspend fun applyDiskState(
        low: Set<Path>,
        release: Set<DownloadId> = emptySet(),
    ): CommandResult = actor.callLater { applyDiskState(low, release, it) }

    /** A runtime tool was installed: jobs paused for TOOLS wait for a slot again (BIN-01). */
    suspend fun applyToolState(): CommandResult = actor.call { applyToolState() }

    /** Pauses the job for the user (USER); a running job is stopped first and its slot freed. */
    suspend fun pause(id: DownloadId): CommandResult = forItems(id, { pause(it) }) { actor.callLater { pause(id, it) } }

    /** Queues a paused job again, behind the jobs of its priority that already wait; clears every pause reason. */
    suspend fun resume(id: DownloadId): CommandResult = forItems(id, { resume(it) }) { actor.call { resume(id) } }

    /** Stops the job for good. Its partial files stay. */
    suspend fun cancel(id: DownloadId): CommandResult = forItems(id, { cancel(it) }) { actor.callLater { cancel(id, it) } }

    /**
     * Removes the job and, with [RemoveFiles.TRASH], moves its outputs and partials to the trash first. If that fails
     * the job stays (paused for the user if it was active) and the result says why.
     */
    suspend fun remove(
        id: DownloadId,
        files: RemoveFiles,
    ): CommandResult =
        forItems(id, { remove(it, files) }, alsoCollection = true, failing = {
            it is CommandResult.TrashUnavailable ||
                it is CommandResult.TrashFailed
        }) {
            actor.callLater { remove(id, files, it) }
        }

    /** Queues a FAILED job again with a fresh attempt count. */
    suspend fun retry(id: DownloadId): CommandResult = forItems(id, { retry(it) }) { actor.call { retry(id) } }

    /** Every job's combined speed limit in bytes per second, shared by priority, or null for none; below 1 is Invalid. */
    suspend fun setGlobalSpeedLimit(bytesPerSecond: Long?): CommandResult {
        if (bytesPerSecond != null && bytesPerSecond < 1) return CommandResult.Invalid("speed limit must be >= 1: $bytesPerSecond")
        return actor.call { setGlobalSpeedLimit(bytesPerSecond) }
    }

    /** The job's own speed limit in bytes per second, or null for none; below 1 is Invalid. A running job follows it at once. */
    suspend fun setSpeedLimit(
        id: DownloadId,
        bytesPerSecond: Long?,
    ): CommandResult {
        if (bytesPerSecond != null && bytesPerSecond < 1) return CommandResult.Invalid("speed limit must be >= 1: $bytesPerSecond")
        return forItems(
            id,
            { setSpeedLimit(it, bytesPerSecond) },
            alsoCollection = true,
        ) { actor.call { setSpeedLimit(id, bytesPerSecond) } }
    }

    /** Sets the job's checksum (HTTP-14), or clears it with null. A running job is checked with it from its next run. */
    suspend fun setChecksum(
        id: DownloadId,
        checksum: Checksum?,
    ): CommandResult = actor.call { setChecksum(id, checksum) }

    /** Sets the job's priority. It never stops a running job: the new order applies to the next free slot. */
    suspend fun reprioritize(
        id: DownloadId,
        priority: Int,
    ): CommandResult = forItems(id, { reprioritize(it, priority) }, alsoCollection = true) { actor.call { reprioritize(id, priority) } }

    /**
     * A command on a collection (MED-16) goes to each of its items in turn, not at once: an item may wait for its slot to
     * stop. The first result that [failing] calls a failure ends it. [alsoCollection] says the collection's own row takes
     * the command too (its priority, its limit, its removal). Any other job gets [single]. A command an item can't take
     * (pausing a finished one) is that item's business: the others still get it.
     */
    private suspend fun forItems(
        id: DownloadId,
        each: suspend (DownloadId) -> CommandResult,
        alsoCollection: Boolean = false,
        failing: (CommandResult) -> Boolean = { false },
        single: suspend () -> CommandResult,
    ): CommandResult {
        val collection = store.downloads.get(id)?.takeIf { it.isCollection } ?: return single()
        for (child in store.downloads.children(collection.id)) {
            val result = each(child.id)
            if (failing(result)) return result
        }
        return if (alsoCollection) single() else CommandResult.Done(store.downloads.get(id))
    }

    /** Pauses everything (GLOBAL), running jobs included, and keeps it paused across restarts (FND-05). */
    suspend fun pauseAll(): CommandResult = actor.callLater { pauseAll(it) }

    /** Ends global pause; jobs also paused for another reason stay paused. */
    suspend fun resumeAll(): CommandResult = actor.call { resumeAll() }

    /**
     * Sets how many jobs may run at once. A lower limit stops the lowest-priority running jobs (never MUXING ones).
     * Below 1 it is [CommandResult.Invalid].
     */
    suspend fun setLimit(limit: Int): CommandResult {
        if (limit < 1) return CommandResult.Invalid("limit must be >= 1: $limit")
        return actor.call { setLimit(limit) }
    }

    /**
     * Gives [mirrorId] of job [id] a new [url], once the job's protocol agrees it is the same content. That check runs
     * outside the queue, so other commands go on meanwhile. A running job is stopped and probes the new link.
     */
    suspend fun relink(
        id: DownloadId,
        mirrorId: MirrorId,
        url: String,
    ): CommandResult = actor.relink(id, mirrorId, url)

    /** For an engine stopped before it started: the commands waiting for start() fail with [error] (#555). */
    internal fun abandon(error: Throwable) = actor.abandon(error)

    /** For tests: what holds a slot right now. */
    internal suspend fun inspect(): QueueState = actor.inspect()
}

/** What adding a collection made: the parent, the children that went in, and how many the duplicate check kept out. */
data class CollectionAdded(
    val collection: Download,
    val children: List<Download>,
    val skipped: Int,
)

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

    /** An argument the command can't take, and why; nothing changed. */
    data class Invalid(
        val reason: String,
    ) : CommandResult

    /** [DuplicateCheck.atAdd] found the link already in the list or the file already there; nothing was added (FIL-05). */
    data class Duplicate(
        val found: DuplicateFound,
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
