package dev.anydm.engine.protocol

import dev.anydm.engine.core.FileId
import dev.anydm.engine.core.SegmentId

/**
 * What transfer writes through. A protocol whose library writes files itself writes to TransferFile.partial and still
 * reports checkpoints and progress.
 *
 * The runner only hands [limiter] and [meter] over; nothing in the runner calls them. A real protocol must call
 * `io.limiter.acquire` and `io.meter.record` itself, on the path its bytes take (#538), or the speed limit and the
 * speed readout silently do nothing.
 */
interface TransferIo {
    /** Call before taking in each chunk (NET-01); no limit until #538. */
    val limiter: Limiter

    /** Count every byte received (NET-04); counts nothing until #538. */
    val meter: Meter

    /** Writes into [file]'s partial at [offset]. A Failed (a full disk) ends the transfer: return it. */
    suspend fun write(
        file: FileId,
        offset: Long,
        bytes: ByteArray,
        startIndex: Int = 0,
        endIndex: Int = bytes.size,
    ): StepResult<Unit>

    /**
     * These segments are written up to these offsets: bytes [write] accepted, never bytes merely received (FND-09).
     * Call it as often as you like: the runner saves them at least every StepRunner.checkpointEvery and when the
     * transfer ends, each time after syncing the files they cover.
     */
    suspend fun checkpoint(watermarks: Map<SegmentId, Long>)

    /** All bytes in this job's partials so far, resumed ones included. */
    fun progress(downloaded: Long)

    /**
     * What this job may take now, in bytes per second, or null for no limit (MED-05). For a protocol that hands the
     * transfer to a program of its own, which the [limiter] can't pace: it sets the program's limit from this, and asks
     * again from time to time because the share changes with the other jobs and the settings.
     */
    suspend fun speedShare(): Long? = null
}
