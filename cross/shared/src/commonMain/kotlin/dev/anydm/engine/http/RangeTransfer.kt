package dev.anydm.engine.http

import dev.anydm.engine.core.ErrorClass
import dev.anydm.engine.core.Segment
import dev.anydm.engine.protocol.StepResult
import dev.anydm.engine.protocol.TransferFile
import dev.anydm.engine.protocol.TransferIo
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Tries per block (or per single stream) in one run before the run fails. */
internal const val ATTEMPTS = 3

/** The most bytes read, written and checkpointed at once. */
internal const val CHUNK = 64 * 1024

/**
 * Fetches one file's incomplete blocks over [connections] workers (HTTP-05), each block from its watermark with a
 * `Range` request whose answer is checked (HTTP-08): 206, `Content-Range` exactly as asked, never more bytes than the
 * range. A failed attempt puts the block back from its new watermark; [ATTEMPTS] per block, then the run fails. An HTTP
 * error that retrying can't fix ends the run at once.
 */
internal class RangeTransfer(
    private val transport: HttpTransport,
    private val url: String,
    private val headers: List<Pair<String, String>>,
    private val io: TransferIo,
    private val connections: Int,
) {
    private class Block(
        val segment: Segment,
        var watermark: Long,
        var attempts: Int = 0,
    )

    private sealed interface Attempt {
        data object Done : Attempt

        /** Worth another try (a network error, a short body, a wrong Content-Range, 408, 5xx). */
        data class Retry(
            val failed: StepResult.Failed,
        ) : Attempt

        /** Ends the run: an HTTP error retrying can't fix, or a failed write. */
        data class End(
            val failed: StepResult.Failed,
        ) : Attempt
    }

    /** Thrown by the worker that ends the run, so the scope cancels the others; the reason is in [ended]. */
    private class RunEnded : Exception()

    private val progressLock = Mutex()
    private var downloaded = 0L
    private var ended: StepResult.Failed? = null

    suspend fun run(file: TransferFile): StepResult<Unit> {
        val size = requireNotNull(file.file.size) { "a file in blocks has a size" }
        downloaded = file.segments.sumOf { it.written }
        val incomplete = file.segments.filterNot { it.isComplete }.map { Block(it, it.watermark) }
        if (incomplete.isEmpty()) return StepResult.Ok(Unit)
        val queue = Channel<Block>(Channel.UNLIMITED)
        incomplete.forEach { queue.trySend(it) }
        var left = incomplete.size
        val leftLock = Mutex()
        try {
            coroutineScope {
                repeat(minOf(connections, incomplete.size)) {
                    launch {
                        for (block in queue) {
                            when (val attempt = fetch(file, size, block)) {
                                Attempt.Done -> {
                                    leftLock.withLock { if (--left == 0) queue.close() }
                                }

                                is Attempt.Retry -> {
                                    if (++block.attempts >= ATTEMPTS) end(attempt.failed) else queue.send(block)
                                }

                                is Attempt.End -> {
                                    end(attempt.failed)
                                }
                            }
                        }
                    }
                }
            }
        } catch (_: RunEnded) {
            return checkNotNull(ended)
        }
        return StepResult.Ok(Unit)
    }

    private fun end(failed: StepResult.Failed): Nothing {
        if (ended == null) ended = failed
        throw RunEnded()
    }

    private suspend fun fetch(
        file: TransferFile,
        size: Long,
        block: Block,
    ): Attempt {
        val first = block.watermark
        val last = block.segment.end - 1
        val response =
            try {
                transport.open(HttpRequest(url, headers = listOf("Range" to "bytes=$first-$last") + headers))
            } catch (e: HttpIoException) {
                return Attempt.Retry(ioFailed(e))
            }
        try {
            val host = hostOf(response.finalUrl)
            if (response.status != 206) return statusAttempt(response.status, host)
            if (contentRangeOf(response.headers["Content-Range"]) != Triple(first, last, size)) {
                return Attempt.Retry(StepResult.Failed(ErrorClass.TRANSIENT, "wrong Content-Range from $host"))
            }
            return copy(response.body, file, block, host)
        } finally {
            response.body.close()
        }
    }

    /** Reads the body into the block up to its end, never past it; the watermark moves after each accepted write. */
    private suspend fun copy(
        body: HttpBody,
        file: TransferFile,
        block: Block,
        host: String,
    ): Attempt {
        val buffer = ByteArray(CHUNK)
        while (block.watermark < block.segment.end) {
            val want = minOf(CHUNK.toLong(), block.segment.end - block.watermark).toInt()
            val n =
                try {
                    body.read(buffer, 0, want)
                } catch (e: HttpIoException) {
                    return Attempt.Retry(ioFailed(e))
                }
            if (n < 0) return Attempt.Retry(StepResult.Failed(ErrorClass.TRANSIENT, "short body from $host"))
            if (n == 0) continue
            io.limiter.acquire(n)
            io.meter.record(n.toLong())
            val written = io.write(file.file.id, block.watermark, buffer, 0, n)
            if (written is StepResult.Failed) return Attempt.End(written)
            block.watermark += n
            io.checkpoint(mapOf(block.segment.id to block.watermark))
            progressLock.withLock {
                downloaded += n
                io.progress(downloaded)
            }
        }
        return Attempt.Done
    }

    private fun statusAttempt(
        status: Int,
        host: String,
    ): Attempt {
        val message = "HTTP $status from $host"
        return when (status) {
            408, in 500..599 -> Attempt.Retry(StepResult.Failed(ErrorClass.TRANSIENT, message))
            401, 403 -> Attempt.End(StepResult.Failed(ErrorClass.AUTH, message))
            404, 410 -> Attempt.End(StepResult.Failed(ErrorClass.GONE, message))
            429 -> Attempt.End(StepResult.Failed(ErrorClass.RATE_LIMITED, message))
            416 -> Attempt.End(StepResult.Failed(ErrorClass.TRANSIENT, message))
            else -> Attempt.End(StepResult.Failed(null, message))
        }
    }

    private companion object {
        val contentRange = Regex("""bytes\s+(\d+)-(\d+)/(\d+)""", RegexOption.IGNORE_CASE)

        fun contentRangeOf(header: String?): Triple<Long, Long, Long>? {
            val (a, b, n) = header?.trim()?.let(contentRange::matchEntire)?.destructured ?: return null
            return Triple(a.toLongOrNull() ?: return null, b.toLongOrNull() ?: return null, n.toLongOrNull() ?: return null)
        }

        fun ioFailed(e: HttpIoException) =
            StepResult.Failed(ErrorClass.TRANSIENT, e.message?.takeUnless { it.isBlank() } ?: e.kind.name.lowercase())
    }
}
