package dev.anydm.engine.protocol

import dev.anydm.engine.store.NewSegment

/**
 * One file to fetch; [path] is relative to the save folder with `/` between names. Segments need a known size and
 * cover [0, size) in order; without segments a file is fetched from zero every run.
 *
 * [fresh]: the protocol saw the source change since the bytes on disk were fetched (a different ETag, size or
 * modification time), so nothing already written may be kept: the runner deletes the partial and the stored ranges
 * and starts the file over, even when the ranges look the same. The runner does not store validators; a protocol
 * keeps its own and compares them in [Protocol.plan] (#538).
 *
 * [selected] is false for a file the user skipped (a torrent's): it keeps its row but is not fetched and is not counted.
 */
data class PlannedFile(
    val index: Int,
    val path: String,
    val size: Long? = null,
    val segments: List<NewSegment> = emptyList(),
    val fresh: Boolean = false,
    val selected: Boolean = true,
) {
    init {
        require(index >= 0) { "index must be >= 0: $index" }
        require(path.isNotBlank()) { "path must not be blank" }
        require(size == null || size >= 0) { "size must be >= 0: $size" }
        if (segments.isNotEmpty()) {
            require(size != null) { "segments need a known size: $path" }
            require(
                segments.first().start == 0L &&
                    segments.last().end == size &&
                    segments.zipWithNext().all { (a, b) -> a.end == b.start },
            ) { "segments must cover [0, $size) in order: $path" }
        }
    }
}
