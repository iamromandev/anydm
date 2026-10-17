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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile
import kotlin.time.Clock
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

/** How many finished block attempts give the run's speed per connection. */
private const val RECENT = 4

/** Where blocks can come from: a URL, what its requests carry, and the origin whose [HostSlots] its workers hold. */
internal data class Source(
    val url: String,
    val headers: List<Pair<String, String>>,
    val origin: String,
)

/**
 * Fetches one file's incomplete blocks with workers that each hold one of their source origin's [slots] (HTTP-05, HTTP-06). It
 * starts with one; every [rampEvery] a controller adds another while blocks wait and a slot is free, and stops adding
 * once an addition didn't raise the speed by 10%. A worker that finishes a block gives its slot to another download
 * waiting for the same host if this run holds more than one. A worker that finds no block left races the in-flight
 * block that would finish last, if it is slow enough (HTTP-07): the racer buffers the rest of the block in memory, and
 * whoever has the last byte first writes the tail and cancels the other. Each block is fetched from its watermark with a
 * `Range` request whose answer is checked (HTTP-08): 206, `Content-Range` exactly as asked, never more bytes than the
 * range. A failed attempt is retried by the same worker from the block's new watermark; [ATTEMPTS] per block, then the
 * run fails, so a server that fails everything gets at most about [ATTEMPTS] × the cap requests. An error that
 * retrying can't fix, or a `Retry-After` (HTTP-09), ends the run at once.
 *
 * A file without blocks is one plain stream from byte 0. So is the rest of a run whose server answered a ranged
 * request with 200: that body is read from byte 0, bytes below each block's watermark are discarded, and the rest
 * written.
 *
 * Other mirrors with the same content join through [addSource] (HTTP-12). Each worker is bound to one source, whose
 * origin's slot it holds, and takes blocks from the shared queue, so a faster mirror takes more of them. A source that
 * joins while blocks wait gets a worker at once if its origin has a free slot; the ramp adds each new worker to the
 * source with the fewest. Every source follows the same failure rules. Plain streams use the primary only.
 */
internal class RangeTransfer(
    private val transport: HttpTransport,
    private val primary: Source,
    private val io: TransferIo,
    private val slots: HostSlots,
    private val timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
    private val rampEvery: Duration = 1.seconds,
    private val clock: Clock = Clock.System,
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
        @Volatile var original: Job? = null

        @Volatile var racer: Job? = null

        /** Raced once in this attempt already; reset by the next attempt only while no racer runs. */
        @Volatile var raced = false
    }

    /** A source in this run, with its workers (under [takeLock]); [order] breaks ties, the primary first. */
    private class Live(
        val source: Source,
        val order: Int,
    ) {
        var workers = 0
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
    private val live = mutableListOf(Live(primary, 0))

    /** Sources added by [addSource], taken in by the run; closed when the run ends, so later ones are dropped. */
    private val joining = Channel<Source>(Channel.UNLIMITED)

    /** Speeds of the last [RECENT] block attempts that finished, bytes per second; under [takeLock]. */
    private val finished = ArrayDeque<Double>()

    /** Bumped on every tick and whenever a block or race ends: what idle workers wait for. */
    private val changes = MutableStateFlow(0L)

    /** Guards [ended] and [switched], which workers on other threads may set at once. */
    private val stateLock = Mutex()
    private var ended: StepResult.Failed? = null

    /** Adds a source with the same content (HTTP-12); thread-safe, before or during [run], ignored after it. */
    fun addSource(source: Source) {
        joining.trySend(source)
    }

    suspend fun run(file: TransferFile): StepResult<Unit> =
        try {
            runBlocks(file)
        } finally {
            joining.close()
        }

    private suspend fun runBlocks(file: TransferFile): StepResult<Unit> {
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
                slots.acquire(primary.origin, this@RangeTransfer)
                // The controller and the joiner first: the first worker may finish every block before it suspends.
                lateinit var controller: Job
                lateinit var joiner: Job
                val lastOut = {
                    controller.cancel()
                    joiner.cancel()
                }
                controller = launch { ramp(file, size, queue, lastOut) }
                joiner = launch { join(file, size, queue, lastOut) }
                worker(file, size, queue, live.first(), lastOut)
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
        from: Live,
        lastOut: () -> Unit,
    ) = launch(start = CoroutineStart.UNDISPATCHED) {
        var yielded = false
        val source = from.source
        try {
            takeLock.withLock {
                workers++
                from.workers++
            }
            while (true) {
                val block = takeLock.withLock { queue.tryReceive().getOrNull()?.also { waiting-- } }
                if (block == null) {
                    val seen = changes.value
                    val target = raceTarget()
                    if (target != null) {
                        race(file, size, target, source)
                        continue
                    }
                    // Nothing to race yet: a block may turn slow later, so look again next tick, unless the blocks
                    // are all done or another download needs the slot.
                    if (takeLock.withLock { inFlight.none { !it.done } }) break
                    if (slots.tryYield(source.origin, this@RangeTransfer)) {
                        yielded = true
                        break
                    }
                    changes.first { it != seen }
                    continue
                }
                original(file, size, block, source)
                if (slots.tryYield(source.origin, this@RangeTransfer)) {
                    yielded = true
                    break
                }
            }
        } finally {
            withContext(NonCancellable) {
                if (!yielded) slots.release(source.origin, this@RangeTransfer)
                val last =
                    takeLock.withLock {
                        from.workers--
                        --workers == 0 && waiting == 0
                    }
                if (last) lastOut()
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
            changes.update { it + 1 }
            before?.let { if (speed < it * 1.1) stopped = true }
            before = null
            // Keeps ticking after the ramp stops: idle workers wake on each tick. The last worker out cancels this.
            if (stopped || takeLock.withLock { waiting } == 0) continue
            val candidates = takeLock.withLock { live.sortedWith(compareBy({ it.workers }, { it.order })) }
            val chosen = candidates.firstOrNull { slots.tryAcquire(it.source.origin, this@RangeTransfer) } ?: continue
            worker(file, size, queue, chosen, lastOut)
            before = speed
        }
    }

    /** Takes in each source [addSource] sends, with a worker at once if blocks wait and its origin has a free slot. */
    private suspend fun CoroutineScope.join(
        file: TransferFile,
        size: Long,
        queue: Channel<Block>,
        lastOut: () -> Unit,
    ) {
        for (source in joining) {
            val (joined, blocksWait) = takeLock.withLock { Live(source, live.size).also { live += it } to (waiting > 0) }
            if (blocksWait && slots.tryAcquire(source.origin, this@RangeTransfer)) worker(file, size, queue, joined, lastOut)
        }
    }

    /** Fetches [block] as its original, in its own job so a winning racer can cancel it; retried in place. */
    private suspend fun original(
        file: TransferFile,
        size: Long,
        block: Block,
        source: Source,
    ) = coroutineScope {
        val attempts =
            launch(start = CoroutineStart.LAZY) {
                while (!block.done) {
                    block.lock.withLock {
                        block.attemptStart = timeSource.markNow()
                        block.attemptBytes = 0
                        if (block.racer == null) block.raced = false
                    }
                    when (val attempt = fetch(file, size, block, source)) {
                        Attempt.Done -> break
                        is Attempt.Retry -> if (!block.done && ++block.attempts >= ATTEMPTS) end(attempt.failed)
                        is Attempt.End -> end(attempt.failed.from(source))
                        is Attempt.Ignored -> switchTo(attempt.response)
                    }
                }
            }
        block.lock.withLock { block.original = attempts }
        takeLock.withLock { inFlight += block }
        try {
            attempts.start()
            attempts.join()
            val elapsed = block.attemptStart?.elapsedNow()
            if (block.done && elapsed != null && elapsed.isPositive()) {
                val speed = block.attemptBytes / (elapsed.inWholeMilliseconds.coerceAtLeast(1) / 1000.0)
                takeLock.withLock {
                    finished.addLast(speed)
                    if (finished.size > RECENT) finished.removeFirst()
                }
            }
        } finally {
            withContext(NonCancellable) { takeLock.withLock { inFlight -= block } }
            changes.update { it + 1 }
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
            // What one connection does: the last few blocks that finished. A tick's speed over the blocks in flight now
            // overstates it at the tail, where blocks finish within the tick.
            if (finished.isEmpty()) return@withLock null
            val perConnection = finished.average()
            inFlight
                .filter { !it.done && !it.raced && it.racer == null && it.segment.end - it.watermark >= RACE_MIN_LEFT }
                .mapNotNull { block -> speedOf(block)?.let { block to it } }
                .filter { (_, speed) -> speed * RACE_SLOWER < perConnection }
                .maxByOrNull { (block, speed) -> (block.segment.end - block.watermark) / speed.coerceAtLeast(1e-9) }
                ?.first
                ?.also { it.raced = true }
        }

    /**
     * Bytes per second in the block's current attempt; null until it has run one [rampEvery], which also covers the time
     * to its first byte.
     */
    private fun speedOf(block: Block): Double? {
        val elapsed = block.attemptStart?.elapsedNow() ?: return null
        if (elapsed < rampEvery) return null
        return block.attemptBytes / (elapsed.inWholeMilliseconds / 1000.0)
    }

    /** Races [block] from its watermark into memory; quietly gives up on any failure but a 200, which switches. */
    private suspend fun race(
        file: TransferFile,
        size: Long,
        block: Block,
        source: Source,
    ) = coroutineScope {
        // Claimed under the lock, and the job made only after: a LAZY child never started would hang this scope.
        val job =
            block.lock.withLock {
                if (block.done || block.racer != null) return@coroutineScope
                launch(start = CoroutineStart.LAZY) { racer(file, size, block, source) }.also { block.racer = it }
            }
        try {
            job.start()
            job.join()
        } finally {
            withContext(NonCancellable) { block.lock.withLock { if (block.racer === job) block.racer = null } }
            changes.update { it + 1 }
        }
    }

    private suspend fun racer(
        file: TransferFile,
        size: Long,
        block: Block,
        source: Source,
    ) {
        val from = block.watermark
        val last = block.segment.end - 1
        val response =
            try {
                transport.open(HttpRequest(source.url, headers = listOf("Range" to "bytes=$from-$last") + source.headers))
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

    /** Runs [block] holding one slot of the primary's origin. */
    private suspend fun <T> withSlot(block: suspend () -> T): T {
        slots.acquire(primary.origin, this)
        try {
            return block()
        } finally {
            withContext(NonCancellable) { slots.release(primary.origin, this@RangeTransfer) }
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
                    transport.open(HttpRequest(primary.url, headers = primary.headers))
                } catch (e: HttpIoException) {
                    val failed = failedOf(e)
                    if (failed.errorClass != ErrorClass.TRANSIENT) return failed
                    last = failed
                    return@repeat
                }
            next = null
            val attempt =
                try {
                    if (response.status == 200) {
                        copyStream(response, file, size, blocks, hostOf(response.finalUrl))
                    } else {
                        statusAttempt(response)
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
                    return ioAttempt(e)
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

    /**
     * The queue records a run's failure against the job's chosen mirror: a secondary's GONE or AUTH says nothing about
     * that one, so the run ends TRANSIENT instead and the next run probes the mirrors again.
     */
    private fun StepResult.Failed.from(source: Source): StepResult.Failed =
        if (source != primary && (errorClass == ErrorClass.GONE || errorClass == ErrorClass.AUTH)) {
            StepResult.Failed(ErrorClass.TRANSIENT, message)
        } else {
            this
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
        source: Source,
    ): Attempt {
        val first = block.watermark
        val last = block.segment.end - 1
        val response =
            try {
                transport.open(HttpRequest(source.url, headers = listOf("Range" to "bytes=$first-$last") + source.headers))
            } catch (e: HttpIoException) {
                return ioAttempt(e)
            }
        var handedOver = false
        try {
            val host = hostOf(response.finalUrl)
            if (response.status == 200) {
                handedOver = true
                return Attempt.Ignored(response)
            }
            if (response.status != 206) return statusAttempt(response)
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
                    return ioAttempt(e)
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

    /**
     * A 408 or 5xx is retried in place unless the server said when to come back (`Retry-After`): then, like every other
     * error status, it ends the run, and the queue waits.
     */
    private fun statusAttempt(response: HttpResponse): Attempt {
        val failed = failedOf(response, clock.now())
        val status = response.status
        val retriable = (status == 408 || status in 500..599) && failed.retryAfter == null
        return if (retriable) Attempt.Retry(failed) else Attempt.End(failed)
    }

    /** A network failure is retried in place only if it is TRANSIENT; TLS, redirect loops and bad URLs end the run. */
    private fun ioAttempt(e: HttpIoException): Attempt {
        val failed = failedOf(e)
        return if (failed.errorClass == ErrorClass.TRANSIENT) Attempt.Retry(failed) else Attempt.End(failed)
    }

    private companion object {
        val contentRange = Regex("""bytes\s+(\d+)-(\d+)/(\d+)""", RegexOption.IGNORE_CASE)

        fun contentRangeOf(header: String?): Triple<Long, Long, Long>? {
            val (a, b, n) = header?.trim()?.let(contentRange::matchEntire)?.destructured ?: return null
            return Triple(a.toLongOrNull() ?: return null, b.toLongOrNull() ?: return null, n.toLongOrNull() ?: return null)
        }
    }
}
