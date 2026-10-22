package dev.anydm.engine.queue

import dev.anydm.engine.EngineEvent
import dev.anydm.engine.core.Attempt
import dev.anydm.engine.core.Download
import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.Mirror

/**
 * What the queue tells the Engine (#555). The first group is called from the queue's coroutine, one at a time and in
 * the order things happened; the second from running slots, possibly at once. No call may suspend or throw into the
 * queue: hand the fact over and return.
 */
internal interface QueueObserver {
    /** A job row was stored (added, moved, changed). */
    fun jobChanged(download: Download) {}

    fun jobRemoved(id: DownloadId) {}

    /** An Attempt row was stored (opened, ended, given a note). */
    fun attemptChanged(attempt: Attempt) {}

    /** The job's mirrors changed (a relink). */
    fun mirrorsChanged(
        id: DownloadId,
        mirrors: List<Mirror>,
    ) {}

    fun event(event: EngineEvent) {}

    /** From a slot: bytes in the job's partials so far, of [total] when known. Called often. */
    fun progress(
        id: DownloadId,
        downloaded: Long,
        total: Long?,
    ) {}

    /** From a slot: [bytes] just came in. */
    fun received(
        id: DownloadId,
        bytes: Long,
    ) {}

    /** From a slot: [bytes] just came in on the bound network [network] (#544). */
    fun receivedOn(
        id: DownloadId,
        bytes: Long,
        network: String,
    ) = received(id, bytes)

    /** From a slot: [bytes] just went out (a torrent uploading, TOR-09). */
    fun sent(
        id: DownloadId,
        bytes: Long,
    ) {}

    /** From a slot: its checkpoints were saved, so its file and segment rows changed. */
    fun checkpointed(id: DownloadId) {}

    /** The job took a slot. */
    fun slotStarted(id: DownloadId) {}

    /** The job's slot ended; called even after the queue has stopped. */
    fun slotEnded(id: DownloadId) {}

    data object None : QueueObserver
}
