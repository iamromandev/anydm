package dev.anydm.engine.core

import dev.anydm.engine.core.DownloadStatus.PAUSED
import dev.anydm.engine.core.DownloadStatus.QUEUED
import kotlin.time.Instant

/**
 * One job, as an immutable snapshot ("download" in the old anydm server's terms). Because it never
 * changes in place, a rejected [transitionTo] has nothing to undo, and snapshots can be published
 * without copying.
 *
 * - Its sources are its [Mirror] rows, not a field here.
 * - A collection is a Download with [collection] set; its children point at it with [parentId].
 * - [priority]: higher runs first. The queue (#552) owns the ordering.
 * - Status changes go through [transitionTo], [pause] and [release]. [copy] can build any status,
 *   so only the store and the single command path should do that.
 * - [pauseReasons] say why a PAUSED job is paused; any other status has none.
 * - [queuedAt]: when the job last started waiting for a slot. The queue sets it (the model reads no
 *   clock) and breaks ties within a priority with it.
 * - Unknown values are `null`, never a sentinel such as -1.
 * - Open question: a collection Download shares this table, but its status will be derived from its
 *   children (decided in MED-16/HTTP-15), so it isn't driven through [transitionTo].
 */
data class Download(
    val id: DownloadId,
    val kind: DownloadKind,
    val status: DownloadStatus = DownloadStatus.PENDING,
    val parentId: DownloadId? = null,
    val collection: CollectionKind? = null,
    val categoryId: CategoryId? = null,
    val profileId: ProfileId? = null,
    /** Unknown until the probe or the category decides. */
    val saveFolder: String? = null,
    /** Unknown until the probe decides. */
    val fileName: String? = null,
    /** Total bytes, or null when unknown. */
    val size: Long? = null,
    val priority: Int = 0,
    /** Bytes per second; null means no limit. */
    val speedLimit: Long? = null,
    val attemptCount: Int = 0,
    val nextAttemptAt: Instant? = null,
    val pauseReasons: Set<PauseReason> = emptySet(),
    val queuedAt: Instant? = null,
    /** What the source said it is at the last probe (ProbeResult.validator); a different one starts it over (#554). */
    val sourceValidator: String? = null,
    /** What the finished file must hash to, as the user gave it (HTTP-14); it wins over the server's. */
    val checksum: Checksum? = null,
    /** Added anyway after a duplicate warning (FIL-06): the duplicate checks leave it alone. */
    val allowDuplicate: Boolean = false,
) {
    init {
        require(status == PAUSED || pauseReasons.isEmpty()) { "only a PAUSED download has pause reasons: $status" }
        require(parentId != id) { "A download can't be its own parent: $id" }
        require(size == null || size >= 0) { "size must be >= 0: $size" }
        require(speedLimit == null || speedLimit > 0) { "speedLimit must be > 0, or null for none: $speedLimit" }
        require(attemptCount >= 0) { "attemptCount must be >= 0: $attemptCount" }
    }

    val isCollection: Boolean get() = collection != null

    /**
     * This download moved to [target], or why it can't move. Never changes this instance. Leaving PAUSED clears every
     * pause reason, so this is also a user's resume.
     */
    fun transitionTo(target: DownloadStatus): TransitionResult =
        if (status.canMoveTo(target)) {
            TransitionResult.Moved(copy(status = target, pauseReasons = emptySet()))
        } else {
            TransitionResult.Rejected(from = status, to = target)
        }

    /** Paused for [reason] as well: moves into PAUSED, or adds the reason if it is already paused. */
    fun pause(reason: PauseReason): TransitionResult =
        when {
            status == PAUSED -> TransitionResult.Moved(copy(pauseReasons = pauseReasons + reason))
            status.canMoveTo(PAUSED) -> TransitionResult.Moved(copy(status = PAUSED, pauseReasons = setOf(reason)))
            else -> TransitionResult.Rejected(from = status, to = PAUSED)
        }

    /**
     * No longer paused for [reason]: QUEUED once no reason is left, still PAUSED otherwise. A reason the job doesn't
     * hold, or a job that isn't paused, changes nothing.
     */
    fun release(reason: PauseReason): TransitionResult {
        if (status != PAUSED || reason !in pauseReasons) return TransitionResult.Moved(this)
        val remaining = pauseReasons - reason
        return if (remaining.isEmpty()) transitionTo(QUEUED) else TransitionResult.Moved(copy(pauseReasons = remaining))
    }
}
