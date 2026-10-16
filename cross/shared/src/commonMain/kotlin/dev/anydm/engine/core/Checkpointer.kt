package dev.anydm.engine.core

import dev.anydm.engine.platform.FileResult
import dev.anydm.engine.platform.FileWriter
import dev.anydm.engine.protocol.StepResult
import dev.anydm.engine.store.PlanStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The checkpoints of one transfer (FND-09): the protocol's watermarks wait here until [flush] saves them, and a save
 * only follows a sync of every file written since the last one, so a stored watermark never points past bytes that a
 * power cut could take away. The step runner flushes on a timer and once more when the transfer ends, however it ends.
 * The protocol records while the timer flushes, so everything here is behind one lock.
 */
internal class Checkpointer(
    private val writers: Map<FileId, FileWriter>,
    private val store: PlanStore,
) {
    private val lock = Mutex()
    private val pending = mutableMapOf<SegmentId, Long>()
    private val dirty = mutableSetOf<FileId>()

    /** [file] took in bytes; the next flush syncs it. */
    suspend fun wrote(file: FileId) = lock.withLock { dirty += file }

    /** The segments are written up to these offsets; a lower offset than one already recorded is ignored. */
    suspend fun record(watermarks: Map<SegmentId, Long>) =
        lock.withLock {
            for ((segment, at) in watermarks) pending[segment] = maxOf(at, pending[segment] ?: at)
        }

    /** Syncs every written file, then saves the watermarks. If a sync fails nothing is saved, and the next flush tries again. */
    suspend fun flush(): StepResult<Unit> =
        lock.withLock {
            for (file in dirty) {
                val synced = writers.getValue(file).sync()
                if (synced is FileResult.Failed) return@withLock synced.toStepFailed()
            }
            if (pending.isNotEmpty()) store.saveWatermarks(pending.toMap())
            pending.clear()
            dirty.clear()
            StepResult.Ok(Unit)
        }
}
