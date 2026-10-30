package dev.anydm.engine.platform

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.FileChannel

/**
 * [FileWriter] on a [FileChannel]: positional writes, safe from several threads at once.
 *
 * A FileChannel is closed for everyone when a thread blocked in it is interrupted (ClosedByInterruptException), and
 * the other writers then see a closed channel too. Coroutine cancellation does not interrupt threads, so a cancelled
 * transfer is safe; keep it that way and never wrap these calls in `runInterruptible`.
 */
internal class DesktopFileWriter(
    private val path: Path,
    private val channel: FileChannel,
    private val io: CoroutineDispatcher,
) : FileWriter {
    val isOpen: Boolean get() = channel.isOpen

    override suspend fun write(
        offset: Long,
        bytes: ByteArray,
        startIndex: Int,
        endIndex: Int,
    ): FileResult<Unit> {
        require(offset >= 0) { "offset must be >= 0: $offset" }
        require(startIndex in 0..endIndex && endIndex <= bytes.size) { "bad range [$startIndex, $endIndex) of ${bytes.size}" }
        return onChannel {
            val buffer = ByteBuffer.wrap(bytes, startIndex, endIndex - startIndex)
            var at = offset
            while (buffer.hasRemaining()) at += channel.write(buffer, at) // may take fewer bytes than offered
        }
    }

    override suspend fun size(): FileResult<Long> = onChannel { channel.size() }

    override suspend fun sync(): FileResult<Unit> = onChannel { channel.force(false) }

    override fun close() = channel.close()

    private suspend fun <T> onChannel(block: () -> T): FileResult<T> =
        withContext(io) {
            try {
                FileResult.Ok(block())
            } catch (e: ClosedChannelException) {
                throw IllegalStateException("the writer for $path is closed", e) // before IOException: a bug, not a failure
            } catch (e: IOException) {
                e.toFailure(path)
            }
        }
}
