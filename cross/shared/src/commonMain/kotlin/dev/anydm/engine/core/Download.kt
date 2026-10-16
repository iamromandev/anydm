package dev.anydm.engine.core

import kotlin.time.Instant

/**
 * One job, as an immutable snapshot ("download" in the old anydm server's terms). Because it never
 * changes in place, a rejected [transitionTo] has nothing to undo, and snapshots can be published
 * without copying.
 *
 * - Its sources are its [Mirror] rows, not a field here.
 * - A collection is a Download with [collection] set; its children point at it with [parentId].
 * - [priority]: higher runs first. The queue (#552) owns the ordering.
 * - Status changes go through [transitionTo]. [copy] can build any status, so only the store and
 *   the single command path should do that.
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
) {
    init {
        require(parentId != id) { "A download can't be its own parent: $id" }
        require(size == null || size >= 0) { "size must be >= 0: $size" }
        require(speedLimit == null || speedLimit > 0) { "speedLimit must be > 0, or null for none: $speedLimit" }
        require(attemptCount >= 0) { "attemptCount must be >= 0: $attemptCount" }
    }

    val isCollection: Boolean get() = collection != null

    /** This download moved to [target], or why it can't move. Never changes this instance. */
    fun transitionTo(target: DownloadStatus): TransitionResult =
        if (status.canMoveTo(target)) {
            TransitionResult.Moved(copy(status = target))
        } else {
            TransitionResult.Rejected(from = status, to = target)
        }
}
