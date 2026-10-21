package dev.anydm.engine.net

import dev.anydm.engine.core.Download
import dev.anydm.engine.core.DownloadStatus
import dev.anydm.engine.core.PauseReason

/** What the queue is doing, for the after-queue action (NET-10) and for keep awake (NET-11). Collection rows are ignored. */
internal object QueueActivity {
    private val selfResuming = setOf(PauseReason.NETWORK, PauseReason.SCHEDULE)

    /**
     * Some job is waiting, running, or paused only for a reason that lifts by itself (network, schedule). Finished is
     * the opposite.
     */
    fun busy(downloads: Collection<Download>): Boolean =
        downloads.any {
            !it.isCollection &&
                when (it.status) {
                    DownloadStatus.PENDING, DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING, DownloadStatus.MUXING -> true
                    DownloadStatus.PAUSED -> it.pauseReasons.isNotEmpty() && selfResuming.containsAll(it.pauseReasons)
                    else -> false
                }
        }

    /** Some job is transferring or finishing its file: the machine should stay awake. */
    fun awake(downloads: Collection<Download>): Boolean =
        downloads.any { !it.isCollection && (it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.MUXING) }
}
