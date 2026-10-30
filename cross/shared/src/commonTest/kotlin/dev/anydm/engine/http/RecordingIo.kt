package dev.anydm.engine.http

import dev.anydm.engine.core.ErrorClass
import dev.anydm.engine.core.FileId
import dev.anydm.engine.core.RecordingLimiter
import dev.anydm.engine.core.RecordingMeter
import dev.anydm.engine.core.Segment
import dev.anydm.engine.core.SegmentId
import dev.anydm.engine.protocol.StepResult
import dev.anydm.engine.protocol.TransferIo

/**
 * A partial in memory: records every write, checkpoint and progress report, and fails a checkpoint whose watermark is
 * ahead of the bytes written since the run started (FND-09).
 */
internal class RecordingIo(
    size: Long,
    private val segments: List<Segment>,
) : TransferIo {
    val bytes = ByteArray(size.toInt())
    val written = mutableListOf<LongRange>()
    val checkpoints = mutableListOf<Map<SegmentId, Long>>()
    val progress = mutableListOf<Long>()

    /** A write that would reach past this offset fails as a full disk. */
    var failWriteAt: Long? = null
    val diskFull = StepResult.Failed(ErrorClass.DISK_FULL, "disk full")

    override val limiter = RecordingLimiter()
    override val meter = RecordingMeter()

    /** Per segment: the end of the bytes written contiguously from its watermark when the run started. */
    private val covered = segments.associate { it.id to it.watermark }.toMutableMap()

    override suspend fun write(
        file: FileId,
        offset: Long,
        bytes: ByteArray,
        startIndex: Int,
        endIndex: Int,
    ): StepResult<Unit> {
        val n = endIndex - startIndex
        failWriteAt?.let { if (offset + n > it) return diskFull }
        bytes.copyInto(this.bytes, offset.toInt(), startIndex, endIndex)
        written += offset until offset + n
        segments.firstOrNull { offset in it.start until it.end }?.let { if (covered[it.id] == offset) covered[it.id] = offset + n }
        return StepResult.Ok(Unit)
    }

    override suspend fun checkpoint(watermarks: Map<SegmentId, Long>) {
        for ((id, at) in watermarks) check(at <= covered.getValue(id)) { "watermark $at of ${id.value} is ahead of ${covered[id]}" }
        checkpoints += watermarks
    }

    override fun progress(downloaded: Long) {
        progress += downloaded
    }

    fun lastWatermark(id: SegmentId): Long? = checkpoints.lastOrNull { id in it }?.get(id)
}
