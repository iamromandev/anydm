package dev.anydm.engine.core

/**
 * A byte range `[start, end)` of a [File], fetched independently. [watermark] is the offset up to
 * which bytes are safely written; it is what gets checkpointed (FND-09) and where a resume starts.
 *
 * Segments exist only for transfers of known length. A transfer of unknown length has no Segment
 * and restarts from zero after a stop.
 */
data class Segment(
    val id: SegmentId,
    val fileId: FileId,
    val start: Long,
    val end: Long,
    val watermark: Long = start,
) {
    init {
        require(start >= 0) { "start must be >= 0: $start" }
        require(start < end) { "start must be < end: $start, $end" }
        require(watermark in start..end) { "watermark must be in [$start, $end]: $watermark" }
    }

    val size: Long get() = end - start
    val written: Long get() = watermark - start
    val isComplete: Boolean get() = watermark == end
}
