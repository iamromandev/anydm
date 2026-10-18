package dev.anydm.engine.store

import dev.anydm.engine.core.CategoryId
import dev.anydm.engine.core.Checksum
import dev.anydm.engine.core.CollectionKind
import dev.anydm.engine.core.Download
import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.File
import dev.anydm.engine.core.Format
import dev.anydm.engine.core.Mirror
import dev.anydm.engine.core.MirrorId
import dev.anydm.engine.core.ProfileId
import dev.anydm.engine.core.RequestContext
import dev.anydm.engine.core.Segment
import kotlin.time.Instant

/**
 * A download to add: the creation fields of [Download]. It starts PENDING with no attempts; the store assigns its id,
 * and the queue its queued time (DownloadStore.add).
 */
data class NewDownload(
    val kind: DownloadKind,
    /** Source URLs in the order to try them; each becomes a [Mirror] at its index. */
    val mirrors: List<String> = emptyList(),
    val parentId: DownloadId? = null,
    val collection: CollectionKind? = null,
    val categoryId: CategoryId? = null,
    val profileId: ProfileId? = null,
    val saveFolder: String? = null,
    val fileName: String? = null,
    val size: Long? = null,
    val priority: Int = 0,
    val speedLimit: Long? = null,
    /** What its requests carry besides the URL (HTTP-10); stored with it. */
    val context: RequestContext? = null,
    /** What the finished file must hash to (HTTP-14). */
    val checksum: Checksum? = null,
    /** Skip the duplicate checks for this one (FIL-06). */
    val allowDuplicate: Boolean = false,
    /** What the user wants from a media page (MED-09); stored with it. */
    val format: Format? = null,
) {
    init { // the same rules as Download
        require(size == null || size >= 0) { "size must be >= 0: $size" }
        require(speedLimit == null || speedLimit > 0) { "speedLimit must be > 0, or null for none: $speedLimit" }
        require(mirrors.none { it.isBlank() }) { "mirror urls must not be blank" }
    }
}

/** A [File] to add to a download; the store assigns its id. */
data class NewFile(
    val index: Int,
    val path: String,
    val size: Long? = null,
    val selected: Boolean = true,
) {
    init { // the same rules as File
        require(index >= 0) { "index must be >= 0: $index" }
        require(path.isNotBlank()) { "path must not be blank" }
        require(size == null || size >= 0) { "size must be >= 0: $size" }
    }
}

/** A byte range `[start, end)` with nothing written yet; the store assigns the [Segment]'s id. */
data class NewSegment(
    val start: Long,
    val end: Long,
) {
    init { // the same rules as Segment
        require(start >= 0) { "start must be >= 0: $start" }
        require(start < end) { "start must be < end: $start, $end" }
    }
}

/** A try that has just started, optionally against one [Mirror]. */
data class NewAttempt(
    val downloadId: DownloadId,
    val startedAt: Instant,
    val mirrorId: MirrorId? = null,
)
