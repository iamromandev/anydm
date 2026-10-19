package dev.anydm.engine.media

import dev.anydm.engine.core.FileId
import dev.anydm.engine.core.RecordingLimiter
import dev.anydm.engine.core.RecordingMeter
import dev.anydm.engine.core.SegmentId
import dev.anydm.engine.protocol.StepResult
import dev.anydm.engine.protocol.TransferIo

/** A [TransferIo] that keeps what is written (by file, at any offset), what is reported, and answers [share]. */
internal class CollectingIo(
    var share: suspend () -> Long? = { null },
) : TransferIo {
    override val limiter = RecordingLimiter()
    override val meter = RecordingMeter()
    val progress = mutableListOf<Long>()
    private val files = mutableMapOf<FileId, ByteArray>()

    fun bytes(file: FileId): ByteArray = files[file] ?: ByteArray(0)

    override suspend fun write(
        file: FileId,
        offset: Long,
        bytes: ByteArray,
        startIndex: Int,
        endIndex: Int,
    ): StepResult<Unit> {
        val end = offset.toInt() + (endIndex - startIndex)
        val held = files[file] ?: ByteArray(0)
        val grown = if (held.size >= end) held else held.copyOf(end)
        bytes.copyInto(grown, offset.toInt(), startIndex, endIndex)
        files[file] = grown
        return StepResult.Ok(Unit)
    }

    override suspend fun checkpoint(watermarks: Map<SegmentId, Long>) = Unit

    override fun progress(downloaded: Long) {
        progress += downloaded
    }
}
