package dev.anydm.engine.ftp

import dev.anydm.engine.core.ErrorClass
import dev.anydm.engine.core.Segment
import dev.anydm.engine.protocol.StepResult
import dev.anydm.engine.protocol.TransferFile
import dev.anydm.engine.protocol.TransferIo
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * One run of one remote file (FTP-03, FTP-05): [workers] coroutines take the unfinished blocks of [file] one at a time,
 * each block over a connection of its own that starts at the block's watermark. A file with no blocks (its size unknown)
 * is read whole from zero. Every chunk goes through the limiter and the meter, is written with `io.write` and only then
 * checkpointed, so a watermark is never ahead of the bytes on disk.
 */
internal class FtpTransfer(
    private val clients: RemoteClientFactory,
    private val target: RemoteTarget,
    private val login: RemoteLogin,
    private val file: TransferFile,
    private val io: TransferIo,
    private val workers: Int,
) {
    private class Block(
        val segment: Segment,
    ) {
        var watermark = segment.watermark
    }

    /** A step failure that ends the run: thrown inside workers so the others are cancelled. */
    private class Ended(
        val failed: StepResult.Failed,
    ) : Exception(failed.message)

    /** A server that can't start at an offset: the file is read whole instead. */
    private class RestUnsupported : Exception()

    private enum class Fetched { DONE, REFUSED }

    private val queueLock = Mutex()
    private val queue = ArrayDeque<Block>()
    private var alive = 0
    private val progressLock = Mutex()
    private var downloaded = file.segments.sumOf { it.written }

    suspend fun run(): StepResult<Unit> {
        if (file.segments.isEmpty()) return whole()
        queue += file.segments.filter { it.watermark < it.end }.map(::Block)
        if (queue.isEmpty()) return StepResult.Ok(Unit)
        alive = minOf(workers, queue.size)
        return try {
            coroutineScope { repeat(alive) { launch { worker() } } }
            StepResult.Ok(Unit)
        } catch (e: Ended) {
            e.failed
        } catch (_: RestUnsupported) {
            whole()
        }
    }

    private suspend fun worker() {
        var refused = 0
        while (true) {
            val block = queueLock.withLock { queue.removeFirstOrNull().also { if (it == null) alive-- } } ?: return
            if (fetch(block) == Fetched.DONE) continue
            // The server won't take this connection. Others still working will pick the block up; alone, try again.
            val othersLeft =
                queueLock.withLock {
                    queue.addFirst(block)
                    (alive > 1).also { if (it) alive-- }
                }
            if (othersLeft) return
            if (++refused > RETRIES) throw Ended(StepResult.Failed(ErrorClass.TRANSIENT, "the server won't take a connection"))
        }
    }

    // One block from its watermark to its end, over fresh connections: a dropped one resumes where the bytes stopped.
    private suspend fun fetch(block: Block): Fetched {
        var failures = 0
        while (block.watermark < block.segment.end) {
            val before = block.watermark
            val client = clients.create()
            try {
                client.connect(target, login)
                client.read(target.path, block.watermark, block.segment.end - block.watermark) { bytes, n -> accept(block, bytes, n) }
                if (block.watermark < block.segment.end) failures = another(failures, before, block.watermark, "the file ended early")
            } catch (e: RemoteException) {
                when (e.failure) {
                    RemoteFailure.TOO_MANY_CONNECTIONS -> return Fetched.REFUSED
                    RemoteFailure.REST_UNSUPPORTED -> throw RestUnsupported()
                    RemoteFailure.TRANSIENT -> failures = another(failures, before, block.watermark, e.message)
                    else -> throw Ended(e.asFailed())
                }
            } finally {
                withContext(NonCancellable) { client.close() }
            }
        }
        return Fetched.DONE
    }

    // Counts a failed attempt that got no bytes; too many in a row end the run, left for the queue to retry.
    private fun another(
        failures: Int,
        before: Long,
        after: Long,
        message: String?,
    ): Int {
        val next = if (after > before) 1 else failures + 1
        if (next > RETRIES) throw Ended(StepResult.Failed(ErrorClass.TRANSIENT, message?.takeIf { it.isNotBlank() } ?: "connection lost"))
        return next
    }

    private suspend fun accept(
        block: Block,
        bytes: ByteArray,
        n: Int,
    ) {
        val take = minOf(n.toLong(), block.segment.end - block.watermark).toInt()
        if (take <= 0) return
        io.limiter.acquire(take)
        io.meter.record(take.toLong())
        val written = io.write(file.file.id, block.watermark, bytes, 0, take)
        if (written is StepResult.Failed) throw Ended(written)
        block.watermark += take
        io.checkpoint(mapOf(block.segment.id to block.watermark))
        progressLock.withLock {
            downloaded += take
            io.progress(downloaded)
        }
    }

    // The whole file from zero over one connection: no blocks (size unknown), or a server that can't start at an offset.
    private suspend fun whole(): StepResult<Unit> {
        var failures = 0
        while (true) {
            val client = clients.create()
            var at = 0L
            try {
                client.connect(target, login)
                client.read(target.path, 0, null) { bytes, n ->
                    io.limiter.acquire(n)
                    io.meter.record(n.toLong())
                    val written = io.write(file.file.id, at, bytes, 0, n)
                    if (written is StepResult.Failed) throw Ended(written)
                    at += n
                    io.progress(at)
                }
                val size = file.file.size
                if (size != null && at < size) {
                    if (++failures > RETRIES) return StepResult.Failed(ErrorClass.TRANSIENT, "the file ended early")
                    continue
                }
                if (file.segments.isNotEmpty()) io.checkpoint(file.segments.associate { it.id to it.end })
                return StepResult.Ok(Unit)
            } catch (e: Ended) {
                return e.failed
            } catch (e: RemoteException) {
                val again = e.failure == RemoteFailure.TRANSIENT || e.failure == RemoteFailure.TOO_MANY_CONNECTIONS
                if (!again || ++failures > RETRIES) return e.asFailed()
            } finally {
                withContext(NonCancellable) { client.close() }
            }
        }
    }

    private companion object {
        /** Attempts after the first that make no progress before the run gives up and the queue retries it. */
        const val RETRIES = 2
    }
}
