package dev.anydm.engine.core

import dev.anydm.engine.platform.FileResult
import dev.anydm.engine.platform.FileWriter
import dev.anydm.engine.protocol.Limiter
import dev.anydm.engine.protocol.Meter
import dev.anydm.engine.protocol.StepResult
import dev.anydm.engine.protocol.Transfer
import dev.anydm.engine.protocol.TransferIo
import dev.anydm.engine.store.PlanStore

/** The [TransferIo] of one run: writes go to the open partials, checkpoints straight to the store (#554 will batch them). */
internal class RunIo(
    transfer: Transfer,
    private val writers: Map<FileId, FileWriter>,
    private val store: PlanStore,
    override val limiter: Limiter,
    override val meter: Meter,
    private val listener: RunListener,
    private val total: Long?,
) : TransferIo {
    private val known = transfer.files.flatMap { f -> f.segments.map { it.id } }.toSet()

    override suspend fun write(
        file: FileId,
        offset: Long,
        bytes: ByteArray,
        startIndex: Int,
        endIndex: Int,
    ): StepResult<Unit> {
        val writer = requireNotNull(writers[file]) { "file ${file.value} isn't part of this transfer" }
        return when (val written = writer.write(offset, bytes, startIndex, endIndex)) {
            is FileResult.Failed -> written.toStepFailed()
            is FileResult.Ok -> StepResult.Ok(Unit)
        }
    }

    override suspend fun checkpoint(watermarks: Map<SegmentId, Long>) {
        require(known.containsAll(watermarks.keys)) { "segments of another transfer: ${watermarks.keys - known}" }
        store.saveWatermarks(watermarks)
    }

    override fun progress(downloaded: Long) = listener.progress(downloaded, total)
}
