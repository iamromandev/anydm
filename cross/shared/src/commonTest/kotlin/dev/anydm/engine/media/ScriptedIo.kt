package dev.anydm.engine.media

import dev.anydm.engine.core.FileId
import dev.anydm.engine.core.RecordingLimiter
import dev.anydm.engine.core.RecordingMeter
import dev.anydm.engine.core.SegmentId
import dev.anydm.engine.protocol.StepResult
import dev.anydm.engine.protocol.TransferIo

/** A [TransferIo] for tests of code that doesn't write: it counts the meter, lists progress, and answers [share]. */
internal class ScriptedIo(
    private val share: () -> Long? = { null },
) : TransferIo {
    override val limiter = RecordingLimiter()
    override val meter = RecordingMeter()
    val progress = mutableListOf<Long>()

    override suspend fun write(
        file: FileId,
        offset: Long,
        bytes: ByteArray,
        startIndex: Int,
        endIndex: Int,
    ): StepResult<Unit> = StepResult.Ok(Unit)

    override suspend fun checkpoint(watermarks: Map<SegmentId, Long>) = Unit

    override fun progress(downloaded: Long) {
        progress += downloaded
    }

    override suspend fun speedShare(): Long? = share()
}
