package dev.anydm.engine.core

/**
 * Where a [Download] is in its life. The transition table in [next] is the only source of truth
 * for which moves are legal (FND-02); the `when` there is exhaustive, so a new status won't compile
 * until the table covers it.
 *
 * A job only goes back to a waiting status, and to the one whose step it must redo: [PENDING] means
 * preflight and probe must run (again), [QUEUED] means it is ready to transfer.
 *
 * Why the non-obvious edges are what they are:
 * - [DOWNLOADING] to [PENDING]: the source changed, so it must be probed again (next mirror, or
 *   extracting again for media).
 * - [DOWNLOADING] or [MUXING] to [QUEUED]: a backoff or rate-limit wait, a restart after a crash,
 *   or one full retry from zero after an integrity failure.
 * - Pause is allowed from [MUXING] because a full disk can show up while merging. It is not allowed
 *   from [SEEDING]: pause-all affects downloads, not seeds, and a paused seed could then be
 *   cancelled, which throws away the finished files.
 * - [QUEUED] to [PENDING]: a relink or changed preflight inputs on a queued job mean it must be
 *   probed again.
 * - Resume and retry go to [QUEUED], or to [PENDING] if the probe never finished; going to
 *   [PENDING] is always safe because the probe just runs again.
 * - [FAILED] is only reachable from a status where a step is running ([PENDING], [DOWNLOADING],
 *   [MUXING], [SEEDING]); nothing runs in [QUEUED] or [PAUSED].
 * - Cancel is allowed from every non-terminal status except [SEEDING]: a seeding torrent has
 *   finished, and its exits are stop seeding ([COMPLETED], TOR-05), [FAILED], or remove.
 * - [DOWNLOADING] to [COMPLETED] is rejected: every finish runs post-processing, at least
 *   renaming the partial file into place, so it goes through [MUXING] or [SEEDING].
 * - There are no self-transitions. A repeat command (pause a paused job) is rejected, and the
 *   caller can treat it as a no-op.
 * - Removing a download deletes its row; it is not a transition.
 */
enum class DownloadStatus {
    /** Added; preflight and probe have to run before anything is fetched. */
    PENDING,

    /** Probed and ready to transfer, waiting for a free slot. */
    QUEUED,

    /** Bytes are being transferred. */
    DOWNLOADING,

    /** Transferred; post-processing (merging, renaming into place) is running. */
    MUXING,

    /** Stopped by the user, or by a condition such as a full disk, until resumed. */
    PAUSED,

    /** A torrent whose files are complete and which is still uploading. */
    SEEDING,

    /** Finished successfully. Terminal. */
    COMPLETED,

    /** Stopped by an error. Not terminal: a retry can move it on. */
    FAILED,

    /** Cancelled by the user; partial files are thrown away. Terminal. */
    CANCELLED,
    ;

    /** The statuses this one may move to (FND-02); empty for a terminal one. */
    val next: Set<DownloadStatus> by lazy {
        when (this) {
            PENDING -> setOf(QUEUED, PAUSED, FAILED, CANCELLED)
            QUEUED -> setOf(PENDING, DOWNLOADING, PAUSED, CANCELLED)
            DOWNLOADING -> setOf(PENDING, QUEUED, MUXING, PAUSED, SEEDING, FAILED, CANCELLED)
            MUXING -> setOf(QUEUED, PAUSED, SEEDING, COMPLETED, FAILED, CANCELLED)
            PAUSED -> setOf(PENDING, QUEUED, CANCELLED)
            SEEDING -> setOf(COMPLETED, FAILED)
            FAILED -> setOf(PENDING, QUEUED, CANCELLED)
            COMPLETED, CANCELLED -> emptySet()
        }
    }

    /** True for [COMPLETED] and [CANCELLED]: nothing can move on from them. */
    val isTerminal: Boolean get() = next.isEmpty()

    fun canMoveTo(status: DownloadStatus): Boolean = status in next
}
