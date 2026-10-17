package dev.anydm.engine.http

import dev.anydm.engine.core.ErrorClass
import dev.anydm.engine.core.Segment
import dev.anydm.engine.protocol.StepResult
import dev.anydm.engine.protocol.TransferFile
import dev.anydm.engine.protocol.TransferIo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** Tries per block (or per single stream) in one run before the run fails. */
internal const val ATTEMPTS = 3

/** The most bytes read, written and checkpointed at once. */
internal const val CHUNK = 64 * 1024

/** A block with less left than this is never raced (HTTP-07). */
internal const val RACE_MIN_LEFT = 256L * 1024

/** A block is raced when its speed is less than 1 / this of the run's speed per connection. */
internal const val RACE_SLOWER = 2.0

/**
 * Fetches one file's incomplete blocks with workers that each hold one of [origin]'s [slots] (HTTP-05, HTTP-06). It
 * starts with one; every [rampEvery] a controller adds another while blocks wait and a slot is free, and stops adding
 * once an addition didn't raise the speed by 10%. A worker that finishes a block gives its slot to another download
 * waiting for the same host if this run holds more than one. A worker that finds no block left races the in-flight
 * block that would finish last, if it is slow enough (HTTP-07): the racer buffers the rest of the block in memory, and
 * whoever has the last byte first writes the tail and cancels the other. Each block is fetched from its watermark with a
 * `Range` request whose answer is checked (HTTP-08): 206, `Content-Range` exactly as asked, never more bytes than the
 * range. A failed attempt is retried by the same worker from the block's new watermark; [ATTEMPTS] per block, then the
 * run fails, so a server that fails everything gets at most about [ATTEMPTS] × the cap requests. An HTTP error
 * that retrying can't fix ends the run at once.
 *
 * A file without blocks is one plain stream from byte 0. So is the rest of a run whose server answered a ranged
 * request with 200: that body is read from byte 0, bytes below each block's watermark are discarded, and the rest
 * written.
 */
internal class RangeTransfer(
    private val transport: HttpTransport,
    private val url: String,
    private val headers: List<Pair<String, String>>,
    private val io: TransferIo,
    private val slots: HostSlots,
    private val origin: String,
    private val timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
    private val rampEvery: Duration = 1.seconds,
) {
    private class Block(
        val segment: Segment,
        @Volatile var watermark: Long,
        var attempts: Int = 0,
    ) {
        /** Taken around every write and watermark move, by the original and a winning racer. */
        val lock = Mutex()

        /** Every byte written, by whoever got there first. */
        @Volatile var done = false

        /** When the current attempt started and what it has written since, for its speed. */
        @Volatile var attemptStart: ComparableTimeMark? = null

        @Volatile var attemptBytes = 0L

        /** The current attempt, which a winning racer cancels; and the racer, which a winning original cancels. */
        var original: Job? = null
        var racer: Job? = null

        /** Raced once in this attempt already. */
        var raced = false
    }

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

        /** A 200 to a ranged request: the server ignores ranges, and [response] is the whole file. */
        class Ignored(
            val response: HttpResponse,
        ) : Attempt
    }

    /** Thrown by the worker that ends the run, so the scope cancels the others; the reason is in [ended]. */
    private class RunEnded : Exception()

    /** Thrown by the worker that got a 200, so the scope cancels the others; the body is in [switched]. */
    private class RangesIgnored : Exception()

    private var switched: HttpResponse? = null

    private val progressLock = Mutex()
    private var downloaded = 0L

    /** Bytes received this run, for the ramp's speed; under [progressLock]. */
    private var received = 0L

    /** Guards [waiting] and [workers]: blocks not yet taken, and workers running. */
    private val takeLock = Mutex()
    private var waiting = 0
    private var workers = 0
    private val inFlight = mutableSetOf<Block>()

    /** The run's speed in the last tick, bytes per second. */
    @Volatile private var lastSpeed = 0.0

    /** Guards [ended] and [switched], which workers on other threads may set at once. */
    private val stateLock = Mutex()
    private var ended: StepResult.Failed? = null

    suspend fun run(file: TransferFile): StepResult<Unit> {
        val size = file.file.size
        if (file.segments.isEmpty()) return if (size == 0L) StepResult.Ok(Unit) else withSlot { stream(file, size, emptyList(), null) }
        requireNotNull(size) { "a file in blocks has a size" }
        downloaded = file.segments.sumOf { it.written }
        val blocks = file.segments.map { Block(it, it.watermark) }
        val incomplete = blocks.filterNot { it.watermark == it.segment.end }
        if (incomplete.isEmpty()) return StepResult.Ok(Unit)
        val queue = Channel<Block>(Channel.UNLIMITED)
        incomplete.forEach { queue.trySend(it) }
        queue.close()
        waiting = incomplete.size
        try {
            coroutineScope {
                slots.acquire(origin, this@RangeTransfer)
                // The controller first: the first worker may finish every block before it suspends.
                lateinit var controller: Job
                controller = launch { ramp(file, size, queue) { controller.cancel() } }
                worker(file, size, queue) { controller.cancel() }
            }
        } catch (_: RunEnded) {
            return checkNotNull(ended)
        } catch (_: RangesIgnored) {
            val whole = checkNotNull(switched)
            switched = null
            return withSlot { stream(file, size, blocks, whole) }
        } finally {
            // A 200 a worker got while the run was ending or being cancelled.
            switched?.body?.close()
        }
        return StepResult.Ok(Unit)
    }

    /**
     * Starts a worker holding a slot already taken: UNDISPATCHED, so its `finally` runs even if the scope is cancelled
     * before it gets a thread. It takes blocks until none wait, or until it gives its slot to another download; the
     * last worker out stops the ramp ([lastOut]).
     */
    private fun CoroutineScope.worker(
        file: TransferFile,
        size: Long,
        queue: Channel<Block>,
        lastOut: () -> Unit,
    ) = launch(start = CoroutineStart.UNDISPATCHED) {
        var yielded = false
        try {
            takeLock.withLock { workers++ }
            while (true) {
                val block = takeLock.withLock { queue.tryReceive().getOrNull()?.also { waiting-- } }
                if (block == null) {
                    val target = raceTarget() ?: break
                    race(file, size, target)
                    continue
                }
                original(file, size, block)
                if (slots.tryYield(origin, this@RangeTransfer)) {
                    yielded = true
                    break
                }
            }
        } finally {
            withContext(NonCancellable) {
                if (!yielded) slots.release(origin, this@RangeTransfer)
                if (takeLock.withLock { --workers == 0 && waiting == 0 }) lastOut()
            }
        }
    }

    /**
     * Every [rampEvery]: compares the speed after the last addition with the speed before it (a gain under 10% stops
     * the ramp; two zeros don't), then adds a worker if blocks wait and a slot is free. Ends with the last block.
     */
    private suspend fun CoroutineScope.ramp(
        file: TransferFile,
        size: Long,
        queue: Channel<Block>,
        lastOut: () -> Unit,
    ) {
        var last = 0L
        var before: Long? = null
        var stopped = false
        while (true) {
            delay(rampEvery)
            val now = progressLock.withLock { received }
            val speed = now - last
            last = now
            lastSpeed = speed * 1000.0 / rampEvery.inWholeMilliseconds.coerceAtLeast(1)
            before?.let { if (speed < it * 1.1) stopped = true }
            before = null
            // Keeps sampling after the ramp stops: racing needs the speed. The last worker out cancels this.
            if (stopped || takeLock.withLock { waiting } == 0) continue
            if (slots.tryAcquire(origin, this@RangeTransfer)) {
                worker(file, size, queue, lastOut)
                before = speed
            }
        }
    }

    /** Fetches [block] as its original, in its own job so a winning racer can cancel it; retried in place. */
    private suspend fun original(
        file: TransferFile,
        size: Long,
        block: Block,
    ) = coroutineScope {
        val attempts =
            launch(start = CoroutineStart.LAZY) {
                while (!block.done) {
                    block.lock.withLock {
                        block.attemptStart = timeSource.markNow()
                        block.attemptBytes = 0
                        block.raced = false
                    }
                    when (val attempt = fetch(file, size, block)) {
                        Attempt.Done -> break
                        is Attempt.Retry -> if (!block.done && ++block.attempts >= ATTEMPTS) end(attempt.failed)
                        is Attempt.End -> end(attempt.failed)
                        is Attempt.Ignored -> switchTo(attempt.response)
                    }
                }
            }
        block.lock.withLock { block.original = attempts }
        takeLock.withLock { inFlight += block }
        try {
            attempts.start()
            attempts.join()
        } finally {
            withContext(NonCancellable) { takeLock.withLock { inFlight -= block } }
        }
        // Done first: the racer's buffer goes nowhere.
        block.lock.withLock { block.racer }?.cancel()
    }

    /**
     * The in-flight block that would finish last, if it has at least [RACE_MIN_LEFT] left, no racer in this attempt,
     * and a speed under 1 / [RACE_SLOWER] of the run's speed per connection; claimed for racing, or null.
     */
    private suspend fun raceTarget(): Block? =
        takeLock.withLock {
            val perConnection = lastSpeed / workers.coerceAtLeast(1)
            inFlight
                .filter { !it.done && !it.raced && it.segment.end - it.watermark >= RACE_MIN_LEFT }
                .map { it to speedOf(it) }
                .filter { (_, speed) -> speed * RACE_SLOWER < perConnection }
                .maxByOrNull { (block, speed) -> (block.segment.end - block.watermark) / speed.coerceAtLeast(1e-9) }
                ?.first
                ?.also { it.raced = true }
        }

    private fun speedOf(block: Block): Double {
        val seconds =
            block.attemptStart
                ?.elapsedNow()
                ?.inWholeMilliseconds
                ?.div(1000.0) ?: return 0.0
        return if (seconds <= 0) 0.0 else block.attemptBytes / seconds
    }

    /** Races [block] from its watermark into memory; quietly gives up on any failure but a 200, which switches. */
    private suspend fun race(
        file: TransferFile,
        size: Long,
        block: Block,
    ) = coroutineScope {
        val job = launch(start = CoroutineStart.LAZY) { racer(file, size, block) }
        block.lock.withLock {
            if (block.done) return@coroutineScope
            block.racer = job
        }
        try {
            job.start()
            job.join()
        } finally {
            withContext(NonCancellable) { block.lock.withLock { if (block.racer === job) block.racer = null } }
        }
    }

    private suspend fun racer(
        file: TransferFile,
        size: Long,
        block: Block,
    ) {
        val from = block.watermark
        val last = block.segment.end - 1
        val response =
            try {
                transport.open(HttpRequest(url, headers = listOf("Range" to "bytes=$from-$last") + headers))
            } catch (_: HttpIoException) {
                return
            }
        var handedOver = false
        try {
            if (response.status == 200) {
                handedOver = true
                switchTo(response)
            }
            if (response.status != 206 || contentRangeOf(response.headers["Content-Range"]) != Triple(from, last, size)) return
            val buffer = ByteArray((block.segment.end - from).toInt())
            var got = 0
            while (got < buffer.size) {
                val n =
                    try {
                        response.body.read(buffer, got, minOf(CHUNK, buffer.size - got))
                    } catch (_: HttpIoException) {
                        return
                    }
                if (n < 0) return
                if (n == 0) continue
                io.limiter.acquire(n)
                io.meter.record(n.toLong())
                progressLock.withLock { received += n }
                got += n
            }
            win(file, block, from, buffer)
        } finally {
            if (!handedOver) response.body.close()
        }
    }

    /** The racer has every byte: unless the original got there first, write the tail and cancel the original. */
    private suspend fun win(
        file: TransferFile,
        block: Block,
        from: Long,
        buffer: ByteArray,
    ) {
        val original =
            block.lock.withLock {
                if (block.done) return
                block.done = true
                val start = block.watermark
                var at = start
                while (at < block.segment.end) {
                    val n = minOf(CHUNK.toLong(), block.segment.end - at).toInt()
                    val written = io.write(file.file.id, at, buffer, (at - from).toInt(), (at - from).toInt() + n)
                    if (written is StepResult.Failed) end(written)
                    at += n
                }
                block.watermark = block.segment.end
                io.checkpoint(mapOf(block.segment.id to block.watermark))
                progressLock.withLock {
                    downloaded += block.segment.end - start
                    io.progress(downloaded)
                }
                block.original
            }
        original?.cancel()
    }

    /** Runs [block] holding one slot of [origin]. */
    private suspend fun <T> withSlot(block: suspend () -> T): T {
        slots.acquire(origin, this)
        try {
            return block()
        } finally {
            withContext(NonCancellable) { slots.release(origin, this@RangeTransfer) }
        }
    }

    /**
     * One plain stream from byte 0, starting with [first] if a worker already has it. With [blocks], a byte below its
     * block's watermark is discarded and any other is written and checkpointed; without, everything is written. With a
     * known [size] the stream must reach it (and is never read past it); with an unknown one, its end is the file's.
     */
    private suspend fun stream(
        file: TransferFile,
        size: Long?,
        blocks: List<Block>,
        first: HttpResponse?,
    ): StepResult<Unit> {
        var next = first
        var last: StepResult.Failed? = null
        repeat(ATTEMPTS) {
            val response =
                next ?: try {
                    transport.open(HttpRequest(url, headers = headers))
                } catch (e: HttpIoException) {
                    last = ioFailed(e)
                    return@repeat
                }
            next = null
            val attempt =
                try {
                    if (response.status == 200) {
                        copyStream(response, file, size, blocks, hostOf(response.finalUrl))
                    } else {
                        statusAttempt(response.status, hostOf(response.finalUrl))
                    }
                } finally {
                    response.body.close()
                }
            when (attempt) {
                is Attempt.Retry -> last = attempt.failed
                is Attempt.End -> return attempt.failed
                else -> return StepResult.Ok(Unit)
            }
        }
        return checkNotNull(last)
    }

    /** A whole-file body of another length is another file: the run ends, so the next probe plans it afresh. */
    private suspend fun copyStream(
        response: HttpResponse,
        file: TransferFile,
        size: Long?,
        blocks: List<Block>,
        host: String,
    ): Attempt {
        val body = response.body
        val length = response.headers["Content-Length"]?.trim()?.toLongOrNull()
        val encoding = response.headers["Content-Encoding"]?.trim()
        val encoded = !encoding.isNullOrEmpty() && !encoding.equals("identity", ignoreCase = true)
        if (size != null && length != null && !encoded && length != size) {
            return Attempt.End(StepResult.Failed(ErrorClass.TRANSIENT, "HTTP 200 from $host is $length bytes, not $size"))
        }
        val buffer = ByteArray(CHUNK)
        var offset = 0L
        while (size == null || offset < size) {
            val want = if (size == null) CHUNK else minOf(CHUNK.toLong(), size - offset).toInt()
            val n =
                try {
                    body.read(buffer, 0, want)
                } catch (e: HttpIoException) {
                    return Attempt.Retry(ioFailed(e))
                }
            if (n < 0) {
                return if (size == null) Attempt.Done else Attempt.Retry(StepResult.Failed(ErrorClass.TRANSIENT, "short body from $host"))
            }
            if (n == 0) continue
            io.limiter.acquire(n)
            io.meter.record(n.toLong())
            val failed = if (blocks.isEmpty()) writeAll(file, offset, buffer, n) else writeBlocks(file, offset, buffer, n, blocks)
            if (failed != null) return Attempt.End(failed)
            offset += n
        }
        // Reached the size: a body that goes on is another, longer file.
        val more =
            try {
                body.read(buffer, 0, 1)
            } catch (_: HttpIoException) {
                -1 // every byte of the file arrived; a break after them changes nothing
            }
        if (more > 0) return Attempt.End(StepResult.Failed(ErrorClass.TRANSIENT, "HTTP 200 from $host is longer than $size bytes"))
        return Attempt.Done
    }

    /** Without blocks a stream starts over at 0, so its progress is the stream's own position. */
    private suspend fun writeAll(
        file: TransferFile,
        offset: Long,
        buffer: ByteArray,
        n: Int,
    ): StepResult.Failed? {
        val written = io.write(file.file.id, offset, buffer, 0, n)
        if (written is StepResult.Failed) return written
        io.progress(offset + n)
        return null
    }

    /** Writes the part of [buffer] (stream bytes [offset, offset + n)) at or past each block's watermark. */
    private suspend fun writeBlocks(
        file: TransferFile,
        offset: Long,
        buffer: ByteArray,
        n: Int,
        blocks: List<Block>,
    ): StepResult.Failed? {
        val end = offset + n
        for (block in blocks) {
            val from = maxOf(block.watermark, offset)
            val to = minOf(block.segment.end, end)
            if (from >= to || from != block.watermark) continue
            val written = io.write(file.file.id, from, buffer, (from - offset).toInt(), (to - offset).toInt())
            if (written is StepResult.Failed) return written
            block.watermark = to
            io.checkpoint(mapOf(block.segment.id to to))
            downloaded += to - from
            received += to - from
            io.progress(downloaded)
        }
        return null
    }

    private suspend fun end(failed: StepResult.Failed): Nothing {
        stateLock.withLock { if (ended == null) ended = failed }
        throw RunEnded()
    }

    /** The first 200 wins and becomes the stream; one another worker got at the same time is closed. */
    private suspend fun switchTo(response: HttpResponse): Nothing {
        // Not cancellable: a sibling's switch cancels this worker, and the body in hand must still be kept or closed.
        withContext(NonCancellable) {
            stateLock.withLock { if (switched == null) switched = response else response.body.close() }
        }
        throw RangesIgnored()
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
        var handedOver = false
        try {
            val host = hostOf(response.finalUrl)
            if (response.status == 200) {
                handedOver = true
                return Attempt.Ignored(response)
            }
            if (response.status != 206) return statusAttempt(response.status, host)
            if (contentRangeOf(response.headers["Content-Range"]) != Triple(first, last, size)) {
                return Attempt.Retry(StepResult.Failed(ErrorClass.TRANSIENT, "wrong Content-Range from $host"))
            }
            return copy(response.body, file, block, host)
        } finally {
            if (!handedOver) response.body.close()
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
            progressLock.withLock { received += n }
            val failed =
                block.lock.withLock {
                    // A racer that won wrote the rest already.
                    if (block.done) return Attempt.Done
                    val written = io.write(file.file.id, block.watermark, buffer, 0, n)
                    if (written is StepResult.Failed) return@withLock written
                    block.watermark += n
                    block.attemptBytes += n
                    if (block.watermark == block.segment.end) block.done = true
                    io.checkpoint(mapOf(block.segment.id to block.watermark))
                    progressLock.withLock {
                        downloaded += n
                        io.progress(downloaded)
                    }
                    null
                }
            if (failed != null) return Attempt.End(failed)
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
