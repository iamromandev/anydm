package dev.anydm.engine.http

import dev.anydm.engine.protocol.StepResult
import dev.anydm.engine.protocol.StoredBytes
import dev.anydm.engine.protocol.StoredFile

/** File 0 of [content], with [written] ranges stored; [failRead] makes every read fail with it. */
class FakeStoredBytes(
    private val content: ByteArray,
    written: List<LongRange>,
    size: Long? = content.size.toLong(),
) : StoredBytes {
    var failRead: StepResult.Failed? = null

    override val files = if (written.isEmpty()) emptyList() else listOf(StoredFile(0, size, written))

    override suspend fun read(
        index: Int,
        offset: Long,
        length: Int,
    ): StepResult<ByteArray> {
        require(index == 0)
        failRead?.let { return it }
        return StepResult.Ok(content.copyOfRange(offset.toInt(), offset.toInt() + length))
    }
}
