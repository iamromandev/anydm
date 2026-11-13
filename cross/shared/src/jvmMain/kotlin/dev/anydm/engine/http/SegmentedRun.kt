package dev.anydm.engine.http

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/** What a [SegmentedRun] takes from the engine. */
internal class SegmentedEnv(
    val transport: HttpTransport,
    val disk: DiskIo,
    val clock: Clock,
    val emit: suspend (EngineEvent) -> Unit,
    /** The download's own share of the speed cap, spent by all its connections. */
    val bucket: TokenBucket,
    /** The connections open across the app: one permit each. */
    val permits: ConnectionPermits,
    val dispatcher: CoroutineDispatcher,
    val stallMs: Long,
    val reportMs: Long,
    /** The smallest piece a free connection may take from a range. */
    val minSplit: Long,
    /** How a run waits between failures and how often it asks for a permit it gave away. */
    val retry: RetryPolicy,
    /** Where a run leaves its final ranges, by job id, for a start that follows a stop. */
    val lastSpans: MutableMap<String, SavedRanges>,
)

/** The ranges of a segmented download with the identity of the `.part` file they were written for. */
internal class SavedRanges(
    val spans: List<Span>,
    val partId: String,
)

/**
 * The connections open across the app, one permit each: every response the engine holds open has one. A download's first
 * connection must never wait on another download's extra ones: extras take a permit only while no download is waiting for its
 * first, and give theirs back (see [shouldYield]) when a download has been left without. A permit that is released while a download
 * waits goes straight to the first of them, so what is free and who is waiting never disagree.
 */
internal class ConnectionPermits(
    total: Int,
) {
    private val lock = Any()
    private var free = total
    private val waiters = ArrayDeque<CompletableDeferred<Unit>>()

    /** Extra connections that have been asked to give their permit away and have not yet done so. */
    private var yielding = 0

    /** A download's first connection: waits for a permit as long as it takes. */
    suspend fun acquireFirst() {
        val wait =
            synchronized(lock) {
                if (free > 0) {
                    free--
                    return
                }
                CompletableDeferred<Unit>().also { waiters.addLast(it) }
            }
        try {
            wait.await()
        } catch (e: CancellationException) {
            // Cancelled while waiting: out of the line, or, when a permit was handed over meanwhile, pass it on.
            synchronized(lock) { if (!waiters.remove(wait)) handOver() }
            throw e
        }
    }

    /** An extra connection: only a permit that no download is waiting for. */
    fun tryAcquireExtra(): Boolean =
        synchronized(lock) {
            if (free > 0 && waiters.isEmpty()) {
                free--
                true
            } else {
                false
            }
        }

    /** Gives a permit back; [yielded]: it is the one an extra connection was asked to give (see [shouldYield]). */
    fun release(yielded: Boolean = false) =
        synchronized(lock) {
            if (yielded) yielding = (yielding - 1).coerceAtLeast(0)
            handOver()
        }

    private fun handOver() {
        val next = waiters.removeFirstOrNull()
        if (next == null) free++ else next.complete(Unit)
    }

    /**
     * Whether an extra connection should give its permit away: true for as many callers as there are downloads waiting for a first
     * connection and not yet served by a permit on its way, so one waiter makes one extra connection give way, not all of them.
     * A caller that gets true must release with `release(yielded = true)`.
     */
    fun shouldYield(): Boolean =
        synchronized(lock) {
            if (waiters.size > yielding) {
                yielding++
                true
            } else {
                false
            }
        }
}

/** The file changed on the server: the engine deletes [part] and starts the job again from zero. */
internal class SegmentedChanged(
    val part: Path,
) : Exception("File changed on server")

/** A range that failed and may be tried again. */
private class SpanFailure(
    val reason: String,
) : Exception(reason)

/** An extra connection was refused (429/503): the run goes on with the connections it has. */
private class ExtraRefused : Exception()

/** An extra connection gives its permit to a download that has none: its range goes back to the pool, not counted as a failure. */
private class Yielded : Exception()

/** One range. [reserved] bytes are being written but are not yet in [done]; only [end] changes, and only under the run's lock. */
private class SpanState(
    val start: Long,
    var end: Long,
    var done: Long,
) {
    var reserved = 0L
    var owner = -1
    var failures = 0

    /** Where this range was when its failure count began: a failure after [RetryPolicy.resetBytes] of progress since starts it again. */
    var countedFrom = done

    /** Set by the connection that was asked to give its permit away, until the permit has gone. */
    @Volatile var yieldClaimed = false

    val next: Long get() = start + done
    val finished: Boolean get() = next > end
}

/** An open response, watched for stalls. */
private class Conn {
    @Volatile var open: HttpOpen? = null

    @Volatile var lastByte = 0L

    @Volatile var stalled = false
}

/**
 * One download over up to `job.connections` connections, each writing its own range of the pre-sized part file.
 *
 * Integrity rests on one order: a connection writes, then advances `span.done` under [lock], then reports. A span's `end`
 * may shrink while its connection reads (a free connection steals the second half of what is left), so the connection
 * re-reads `end` under the lock before each write and only writes up to it; [SpanState.reserved] keeps a thief from
 * taking bytes that are about to be written.
 */
internal class SegmentedRun(
    private val env: SegmentedEnv,
    private val job: JobSpec,
    private val live: Live,
    private val name: String,
    private val part: Path,
    initial: List<Span>,
    /** The probe's connection already holds a permit, and this run takes it over (it must be released with [releaseUnusedPermit]). */
    private var holdsPermit: Boolean,
    /** The identity of the `.part` file when it is already one of a segmented run (a resume); null: this run makes the file's. */
    partId: String?,
    /** The answer to the probe request, for the first range when it matches. */
    private var first: HttpOpen?,
    private val etag: String?,
    private val modified: String?,
) {
    private val lock = Any()
    private val spans = initial.map { SpanState(it.start, it.end, it.done) }.toMutableList()
    val total: Long = initial.maxOf { it.end } + 1
    private var totalDone = doneOf(initial)
    private var liveConnections = 0
    private var alive = 0
    private var maxConnections = job.connections.coerceIn(1, MAX_CONNECTIONS)
    private val meter = SpeedMeter()
    private var lastReport = 0L
    private val reportLock = Mutex()
    private val conns = ConcurrentHashMap.newKeySet<Conn>()
    private lateinit var file: PartFile
    private var discard = false
    private var preassigned: SpanState? = null

    /** Counted failures of this run, over all its ranges. */
    private var runFailures = 0

    /** Completed once every range is finished: what a connection waiting for a permit stops waiting on. */
    private val allDone = CompletableDeferred<Unit>()

    /** The identity of the `.part` file: the trailer after its data, known once the file is open. */
    @Volatile var partId: String? = partId
        private set

    /** Set once a request has been sent: with the probe's answer in hand, the job's first request is already behind it. */
    private var asked = first != null

    /** The ranges as they are on disk now. */
    fun snapshot(): List<Span> = synchronized(lock) { snapshotLocked() }

    private fun snapshotLocked(): List<Span> = compact(spans.map { Span(it.start, it.end, it.done) })

    suspend fun run() {
        file =
            try {
                env.disk.openSpans(part, total + TRAILER_BYTES)
            } catch (e: IOException) {
                throw Fail(diskReason(e, Reasons.NOT_WRITABLE))
            }
        if (partId == null) stamp()
        live.segmented = this
        live.done = totalDone
        live.total = total
        try {
            env.emit(EngineEvent.Started(job.id, name, total, etag, modified, totalDone, snapshot(), partId))
            lastReport = env.clock.nowMs()
            if (first != null) {
                spans.firstOrNull()?.takeIf { it.start == 0L && !it.finished }?.let {
                    it.owner = 0
                    preassigned = it
                }
            }
            coroutineScope {
                val monitor = launch(env.dispatcher) { monitor() }
                try {
                    while (!allFinished()) round()
                } finally {
                    monitor.cancel()
                }
            }
            try {
                // The identity goes with the data: what is left is exactly the file, which is renamed next.
                file.truncate(total)
                file.force()
                file.close()
            } catch (e: IOException) {
                throw Fail(diskReason(e, Reasons.NETWORK))
            }
            saveRanges(snapshot())
        } catch (e: CancellationException) {
            wrapUp(stopped = true)
            throw e
        } catch (e: Throwable) {
            wrapUp(stopped = false)
            throw e
        }
    }

    /** Closes the file on every way out (never on a success: that path closes it itself) and leaves the ranges for a restart. */
    private suspend fun wrapUp(stopped: Boolean) {
        withContext(NonCancellable) {
            runCatching { file.force() }
            runCatching { file.close() }
            first?.close()
            if (discard) {
                runCatching { env.disk.delete(part) }
                env.lastSpans.remove(job.id)
                live.done = 0
                live.segmented = null
                live.cleared = true
                return@withContext
            }
            val final = snapshot()
            saveRanges(final)
            live.done = doneOf(final)
            if (stopped) env.emit(EngineEvent.Stopped(job.id, live.done, final, partId))
        }
    }

    /** Gives a file this run created its identity, and makes sure it is on disk before any byte of data is. */
    private fun stamp() {
        val id = newPartId()
        try {
            val trailer = trailerOf(id)!!
            file.write(total, trailer, 0, trailer.size)
            file.force()
        } catch (e: IOException) {
            runCatching { file.close() }
            throw Fail(diskReason(e, Reasons.NOT_WRITABLE))
        }
        partId = id
    }

    private fun saveRanges(spans: List<Span>) {
        partId?.let { env.lastSpans[job.id] = SavedRanges(spans, it) }
    }

    private fun allFinished(): Boolean = synchronized(lock) { spans.all { it.finished } }

    /** One round: up to `maxConnections` workers, until no range is left to take. A refused extra connection lowers the count. */
    private suspend fun round() {
        val n = synchronized(lock) { maxConnections }
        // Worker 0 owns the probe connection, which is already open (and holds the permit the engine took for it) or, with no probe,
        // takes a permit before any other worker can take one.
        if (holdsPermit) holdsPermit = false else env.permits.acquireFirst()
        coroutineScope {
            repeat(n) { idx ->
                // ATOMIC: worker 0 holds a permit from before it starts, so it must run to hand it back even if the round is cancelled
                launch(env.dispatcher, if (idx == 0) CoroutineStart.ATOMIC else CoroutineStart.DEFAULT) { worker(idx) }
            }
        }
    }

    /** Gives back the permit taken over from the probe when no worker ever got to use it (the run failed before its first round). */
    fun releaseUnusedPermit() {
        if (holdsPermit) {
            holdsPermit = false
            env.permits.release()
        }
    }

    private suspend fun worker(idx: Int) {
        var held = idx == 0 // worker 0's permit was taken by round()
        if (idx != 0) {
            if (!env.permits.tryAcquireExtra()) return
            held = true
        }
        synchronized(lock) { alive++ }
        var current: SpanState? = null
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                val st = takeWork(idx) ?: return
                current = st
                val pre = synchronized(lock) { if (idx == 0 && st === preassigned) first.also { first = null } else null }
                var yielded = false
                try {
                    fetch(idx, st, pre)
                    if (allFinished()) allDone.complete(Unit)
                } catch (e: SpanFailure) {
                    // The range stays this worker's while it waits, so the wait is a real one for the range, not for the worker.
                    yielded = !pause(idx, st, retryDelay(st, e.reason))
                    if (yielded) release(st)
                } catch (_: Yielded) {
                    release(st)
                    yielded = true
                } catch (_: ExtraRefused) {
                    release(st)
                    synchronized(lock) { maxConnections = maxOf(1, minOf(maxConnections, alive - 1)) }
                    return
                }
                if (yielded) {
                    // The permit goes to the download that has none; this connection asks again once one is free.
                    held = false
                    st.yieldClaimed = false
                    synchronized(lock) { alive-- }
                    env.permits.release(yielded = true)
                    if (!regain()) return
                    held = true
                }
            }
        } finally {
            if (held) {
                synchronized(lock) { alive-- }
                env.permits.release(yielded = current?.yieldClaimed == true)
            }
        }
    }

    /** Waits for a permit that is free (the downloads that were waiting have theirs); false when there is nothing left to do. */
    private suspend fun regain(): Boolean {
        while (true) {
            if (withTimeoutOrNull(env.retry.yieldRetryMs) { allDone.await() } != null) return false
            val wanted = synchronized(lock) { alive < maxConnections && spans.any { !it.finished } }
            if (!wanted || !env.permits.tryAcquireExtra()) continue
            val joined = synchronized(lock) { (alive < maxConnections).also { if (it) alive++ } }
            if (joined) return true
            env.permits.release()
        }
    }

    /** Waits [ms] before a retry, as long as that does not hold up a download that has no connection; false: gave the permit way. */
    private suspend fun pause(
        idx: Int,
        st: SpanState,
        ms: Long,
    ): Boolean {
        var left = ms
        while (left > 0) {
            if (idx != 0 && env.permits.shouldYield()) {
                st.yieldClaimed = true
                return false
            }
            val slice = minOf(left, PAUSE_SLICE_MS)
            delay(slice)
            left -= slice
        }
        return true
    }

    private fun release(st: SpanState) = synchronized(lock) { st.owner = -1 }

    /** A range nobody is on, else the second half of the biggest one that is being fetched. */
    private fun takeWork(idx: Int): SpanState? =
        synchronized(lock) {
            spans.firstOrNull { !it.finished && it.owner == idx }?.let { return@synchronized it } // set aside for this worker
            spans.firstOrNull { !it.finished && it.owner < 0 }?.let {
                it.owner = idx
                return@synchronized it
            }
            val view = spans.map { Span(it.start, it.end, it.done + it.reserved) }
            val s = steal(view, env.minSplit) ?: return@synchronized null
            spans[s.index].end = s.newEnd
            val taken = SpanState(s.taken.start, s.taken.end, 0).also { it.owner = idx }
            spans.add(s.index + 1, taken)
            taken
        }

    /**
     * Counts a failure of [st] and says how long to wait before it is tried again. A range that has moved forward [RetryPolicy.resetBytes]
     * since its count began starts the count again; the [RANGE_RETRIES]+1st failure of a range, or one over the run's budget of
     * [RetryPolicy.maxRunFailures], is what fails the download.
     */
    private fun retryDelay(
        st: SpanState,
        reason: String,
    ): Long =
        synchronized(lock) {
            if (st.done - st.countedFrom >= env.retry.resetBytes) {
                st.failures = 0
                st.countedFrom = st.done
            }
            st.failures++
            runFailures++
            if (st.failures > RANGE_RETRIES || runFailures > env.retry.maxRunFailures) throw Fail(reason)
            retryDelayMs(st.failures, env.retry.backoffMs)
        }

    private suspend fun fetch(
        idx: Int,
        st: SpanState,
        pre: HttpOpen?,
    ) {
        val conn = Conn()
        val usable = pre != null && covers(pre, st)
        if (!usable) pre?.close()
        // Whether this is the first request the job sends at all (the probe, when there is one, was before this run).
        val firstAsk = !usable && synchronized(lock) { (!asked).also { asked = true } } && idx == 0
        val open = if (usable) pre!! else request(idx, st)
        conn.open = open
        conn.lastByte = env.clock.nowMs()
        conns.add(conn)
        synchronized(lock) { liveConnections++ }
        try {
            if (!usable) validate(idx, st, open, firstAsk)
            read(idx, st, open, conn)
        } finally {
            conns.remove(conn)
            synchronized(lock) { liveConnections-- }
            open.close()
        }
    }

    /** Whether the probe answer is the bytes this range starts with. */
    private fun covers(
        open: HttpOpen,
        st: SpanState,
    ): Boolean {
        val cr = probedRange(open) ?: return false
        return synchronized(lock) { cr.start == st.next && cr.end >= st.end }
    }

    private fun probedRange(open: HttpOpen): ContentRange? =
        if (open.status == 206) open.headers["content-range"]?.firstOrNull()?.let(::parseContentRange) else null

    private suspend fun request(
        idx: Int,
        st: SpanState,
    ): HttpOpen {
        val (from, to) = synchronized(lock) { st.next to st.end }
        val headers = HashMap<String, String>()
        headers["Range"] = "bytes=$from-$to"
        ifRangeOf(etag, modified)?.let { headers["If-Range"] = it }
        return try {
            env.transport.open(job.url, "GET", headers)
        } catch (e: TransportException) {
            throw SpanFailure(e.reason)
        } catch (_: IOException) {
            throw SpanFailure(Reasons.NETWORK)
        }
    }

    /** What the answer to a ranged request means for the run. */
    private fun validate(
        idx: Int,
        st: SpanState,
        open: HttpOpen,
        firstAsk: Boolean,
    ) {
        val refusable = open.status == 429 || open.status == 503
        when {
            open.status == 206 -> {
                Unit
            }

            open.status == 200 -> {
                throw SegmentedChanged(part)
            }

            open.status == 416 -> {
                discard = true
                throw Fail(Reasons.CHANGED)
            }

            refusable && idx != 0 -> {
                throw ExtraRefused()
            }

            refusable && firstAsk -> {
                throw Fail(Reasons.http(open.status)) // the very first request of the job: nothing says the server will ever serve it
            }

            refusable -> {
                // The first connection, asking again later: the server is busy, so the run goes on with fewer connections and tries again
                synchronized(lock) { maxConnections = maxOf(1, minOf(maxConnections, alive - 1)) }
                throw SpanFailure(Reasons.http(open.status))
            }

            open.status in 500..599 -> {
                throw SpanFailure(Reasons.http(open.status))
            }

            else -> {
                throw Fail(Reasons.http(open.status))
            }
        }
        val cr = probedRange(open) ?: throw SpanFailure(Reasons.NETWORK)
        val from = synchronized(lock) { st.next }
        if (cr.start != from || (cr.total != null && cr.total != total)) throw SegmentedChanged(part)
        val tag = open.headers["etag"]?.firstOrNull()
        val stamp = open.headers["last-modified"]?.firstOrNull()
        if ((etag != null && tag != null && tag != etag) || (modified != null && stamp != null && stamp != modified)) {
            throw SegmentedChanged(part)
        }
    }

    private suspend fun read(
        idx: Int,
        st: SpanState,
        open: HttpOpen,
        conn: Conn,
    ) {
        val buf = ByteArray(CHUNK)
        coroutineScope {
            // blocking reads don't see coroutine cancellation: closing the stream is what unblocks them
            val closer =
                launch {
                    try {
                        awaitCancellation()
                    } finally {
                        open.close()
                    }
                }
            try {
                while (true) {
                    currentCoroutineContext().ensureActive()
                    if (idx != 0 && env.permits.shouldYield()) {
                        st.yieldClaimed = true
                        throw Yielded()
                    }
                    val remaining = synchronized(lock) { st.end - st.next + 1 }
                    if (remaining <= 0) break
                    val want = minOf(buf.size.toLong(), env.bucket.maxChunk().toLong(), remaining).toInt()
                    val n =
                        try {
                            open.body.read(buf, 0, want)
                        } catch (_: IOException) {
                            currentCoroutineContext().ensureActive()
                            throw SpanFailure(Reasons.NETWORK)
                        }
                    if (n < 0) throw SpanFailure(Reasons.NETWORK) // closed before the range was complete
                    val now = env.clock.nowMs()
                    conn.lastByte = now
                    delay(env.bucket.reserve(n.toLong(), now))
                    // The range may have been cut short while this chunk waited: write only what is still this range's.
                    val (offset, allowed) =
                        synchronized(lock) {
                            val room = (st.end - st.next + 1).coerceAtLeast(0)
                            val take = minOf(n.toLong(), room).toInt()
                            st.reserved = take.toLong()
                            st.next to take
                        }
                    if (allowed <= 0) break
                    try {
                        file.write(offset, buf, 0, allowed)
                    } catch (e: IOException) {
                        synchronized(lock) { st.reserved = 0 }
                        throw Fail(diskReason(e, Reasons.NETWORK))
                    }
                    val written = env.clock.nowMs()
                    synchronized(lock) {
                        st.done += allowed
                        st.reserved = 0
                        totalDone += allowed
                        live.done = totalDone
                        meter.add(written, allowed.toLong())
                    }
                    report(written)
                }
            } finally {
                closer.cancel()
            }
        }
    }

    /** Fails a stalled connection, and reports progress while nothing is read, so a stall shows as a speed falling to 0. */
    private suspend fun monitor() {
        val tick =
            minOf(
                (env.stallMs / 4).coerceIn(10, 1_000),
                if (env.reportMs > 0) (env.reportMs / 2).coerceIn(10, 250) else Long.MAX_VALUE,
            )
        while (true) {
            delay(tick)
            val now = env.clock.nowMs()
            for (c in conns) {
                val o = c.open
                if (o != null && !c.stalled && now - c.lastByte >= env.stallMs) {
                    c.stalled = true
                    o.close()
                }
            }
            if (env.reportMs > 0) report(now)
        }
    }

    /** A progress report, at most one per `reportMs`, in the order the numbers were taken. */
    private suspend fun report(now: Long) {
        reportLock.withLock {
            val event =
                synchronized(lock) {
                    if (now - lastReport < env.reportMs) {
                        null
                    } else {
                        lastReport = now
                        EngineEvent.Progress(job.id, totalDone, total, meter.bytesPerSec(now), snapshotLocked(), liveConnections, partId)
                    }
                }
            if (event != null) env.emit(event)
        }
    }

    private companion object {
        const val CHUNK = 64 * 1024

        /** A retry's wait is taken in pieces this long, so a download waiting for a connection need not wait it out. */
        const val PAUSE_SLICE_MS = 100L
    }
}
