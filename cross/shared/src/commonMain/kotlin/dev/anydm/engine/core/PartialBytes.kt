package dev.anydm.engine.core

import dev.anydm.engine.platform.FileResult
import dev.anydm.engine.platform.FileSystem
import dev.anydm.engine.protocol.StepResult
import dev.anydm.engine.protocol.StoredBytes
import dev.anydm.engine.protocol.StoredFile
import kotlinx.io.files.Path

/** [StoredBytes] read from the partials at [partials] (by file index). Bytes below a saved watermark were synced first. */
internal class PartialBytes(
    private val fileSystem: FileSystem,
    override val files: List<StoredFile>,
    private val partials: Map<Int, Path>,
) : StoredBytes {
    override suspend fun read(
        index: Int,
        offset: Long,
        length: Int,
    ): StepResult<ByteArray> {
        val partial = requireNotNull(partials[index]) { "no stored file $index" }
        return when (val read = fileSystem.read(partial, offset, length)) {
            is FileResult.Ok -> {
                if (read.value.size < length) {
                    StepResult.Failed(null, "partial is shorter than its watermarks")
                } else {
                    StepResult.Ok(read.value)
                }
            }

            is FileResult.Failed -> {
                StepResult.Failed(null, "can't read the partial: ${read.toStepFailed().message}")
            }
        }
    }
}
