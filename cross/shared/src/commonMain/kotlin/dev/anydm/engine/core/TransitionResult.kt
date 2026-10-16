package dev.anydm.engine.core

/**
 * The outcome of [Download.transitionTo]. Only [Moved] carries a [Download], so a caller can't
 * continue past a rejection without handling it.
 */
sealed interface TransitionResult {
    /** The transition is allowed; [download] is the new snapshot. */
    data class Moved(
        val download: Download,
    ) : TransitionResult

    /** The table has no edge from [from] to [to]; nothing changed. */
    data class Rejected(
        val from: DownloadStatus,
        val to: DownloadStatus,
    ) : TransitionResult
}
