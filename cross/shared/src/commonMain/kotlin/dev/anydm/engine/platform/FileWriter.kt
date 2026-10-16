package dev.anydm.engine.platform

/**
 * One open file written at any offset: the `.anydm` partial a transfer fills (HTTP-16). Several coroutines may write
 * at once, each to its own range. Close it when the transfer stops, also on failure or cancellation. After close,
 * every call throws.
 */
interface FileWriter : AutoCloseable {
    /**
     * Writes bytes [startIndex, endIndex) of [bytes] at [offset], all of them, growing the file past its end if needed.
     * A cancelled caller can't know whether they were written, so it must not count them (FND-09).
     */
    suspend fun write(
        offset: Long,
        bytes: ByteArray,
        startIndex: Int = 0,
        endIndex: Int = bytes.size,
    ): FileResult<Unit>

    /** The file's length now: what a resume compares with the planned size before it trusts the partial. */
    suspend fun size(): FileResult<Long>

    /**
     * Forces what was written to the disk (fsync). A watermark vouches for bytes that must survive a crash, so the
     * checkpointing of #554 calls this before it saves watermarks; the step runner calls it once the transfer is done.
     */
    suspend fun sync(): FileResult<Unit>
}
