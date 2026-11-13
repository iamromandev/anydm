package dev.anydm.engine.http

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.FileSystemException
import java.nio.file.InvalidPathException
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

interface Engine {
    val events: SharedFlow<EngineEvent>

    fun start(job: JobSpec)

    fun stop(id: String)

    fun setRate(bytesPerSec: Long?)

    /** Returns once the job of [id], if one is running, has ended: after a [stop], its `.part` file is closed. */
    suspend fun join(id: String) = Unit

    /** The row of [id] is gone: drop what the engine remembers about it. */
    fun forget(id: String) = Unit
}

/** A failure with a user-facing [reason] (one of [Reasons]). */
internal open class Fail(
    val reason: String,
) : Exception(reason)

/**
 * Disk errors are recognised by their message, which is all the JVM gives us; anything unknown becomes [fallback].
 */
internal fun diskReason(
    e: IOException,
    fallback: String,
): String {
    val msg = e.message.orEmpty()
    return when {
        msg.contains("No space left", ignoreCase = true) || msg.contains("not enough space", ignoreCase = true) -> Reasons.DISK_FULL
        e is AccessDeniedException || e is NoSuchFileException -> Reasons.NOT_WRITABLE
        e is FileSystemException && msg.contains("Read-only", ignoreCase = true) -> Reasons.NOT_WRITABLE
        else -> fallback
    }
}

/** What the reader, the monitor and the engine share about one running job; the monitor runs on another thread. */
internal class Live(
    done: Long,
) {
    @Volatile var done = done

    @Volatile var total: Long? = null

    @Volatile var lastByte = 0L

    @Volatile var lastReport = 0L

    @Volatile var stalled = false

    /** The part file this job holds in the engine's claims, so it is released when the job ends. */
    @Volatile var claimed: Path? = null

    /** Set while a segmented run owns the transfer: the source of the ranges for a failure. */
    @Volatile var segmented: SegmentedRun? = null

    /** The engine deleted the job's `.part` file, and none has been written since: the ranges of its row are gone. */
    @Volatile var cleared = false
    val meter = SpeedMeter()
}

/**
 * One coroutine per job, over one connection or (see [SegmentedRun]) several. `stop` cancels; the `.part` file stays, and a
 * segmented job reports its exact ranges in a `Stopped` event. Jobs run under their own supervisor: one that fails never
 * takes the others down, whatever [scope] is. [maxTotalConnections] bounds the segmented connections of all jobs together.
 */
class DownloadEngine(
    private val transport: HttpTransport,
    private val disk: DiskIo,
    private val clock: Clock,
    scope: CoroutineScope,
    private val stallMs: Long = 30_000,
    private val reportMs: Long = 500,
    maxTotalConnections: Int = MAX_TOTAL_CONNECTIONS,
    private val minSplit: Long = MIN_SPLIT,
    private val retry: RetryPolicy = RetryPolicy(),
) : Engine {
    private val jobScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    private val bus = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 1024)
    override val events: SharedFlow<EngineEvent> = bus.asSharedFlow()
    private val shares = RateShares()
    private val jobs = HashMap<String, Job>()
    private val permits = ConnectionPermits(maxTotalConnections)

    /** Blocking reads of every connection need a thread: more than [Dispatchers.IO] alone would give 64 busy jobs plus helpers. */
    private val connectionPool = Dispatchers.IO.limitedParallelism(maxTotalConnections + CONNECTION_POOL_EXTRA)

    /** The final ranges of the segmented jobs that ended with a part file to resume, by job id: never behind a job spec's. */
    private val lastSpans = ConcurrentHashMap<String, SavedRanges>()

    /**
     * The jobs whose `.part` file this engine saw one stream write (and no segmented run since): that file is a sequential
     * partial, so a row on several connections without ranges may continue it. Any other `.part` of such a row may be a
     * pre-sized file with gaps (or all zeros) that a segmented run left, which must never be taken for finished.
     */
    private val streamed = ConcurrentHashMap.newKeySet<String>()

    /**
     * The `.part` files that running jobs write, by the id of the job: two jobs never write the same file. Keyed by [keyOf], so
     * names that differ only in case count as one file (they are one on a case-insensitive file system).
     */
    private val claims = HashMap<String, String>()

    private fun keyOf(path: Path): String = path.normalize().toString().lowercase(Locale.ROOT)

    override fun setRate(bytesPerSec: Long?) = shares.setRate(bytesPerSec)

    override fun forget(id: String) {
        lastSpans.remove(id)
        streamed.remove(id)
    }

    @Synchronized
    override fun start(job: JobSpec) {
        val old = jobs[job.id]
        if (old != null && old.isActive) return // already running; stop() first
        val created =
            jobScope.launch(Dispatchers.IO, CoroutineStart.LAZY) {
                old?.join() // a stopped predecessor may still be winding down on the same .part file
                // A job with no ranges and nothing done knows nothing of an earlier file: what is kept under its id is a removed row's
                // (this runs after the predecessor ended, which is the one that writes these).
                if (job.spans == null && job.done == 0L) forget(job.id)
                run(job)
            }
        jobs[job.id] = created
        created.invokeOnCompletion {
            synchronized(this) { if (jobs[job.id] === created) jobs.remove(job.id) }
        }
        created.start()
    }

    @Synchronized
    override fun stop(id: String) {
        jobs[id]?.cancel()
    }

    override suspend fun join(id: String) {
        val job = synchronized(this) { jobs[id] }
        job?.join()
    }

    private class Done(
        val bytes: Long,
        /** The name the file ended up with, when it is not the one the job asked for. */
        val renamed: String?,
    )

    private suspend fun run(job: JobSpec) {
        val live = Live(job.done)
        shares.register(job.id)
        val end =
            try {
                transfer(job, live)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Fail) {
                bus.emit(failure(job, live, e.reason))
                return
            } catch (_: Exception) {
                // Anything unforeseen is this download's failure, not the engine's.
                bus.emit(failure(job, live, Reasons.NETWORK))
                return
            } finally {
                live.claimed?.let { release(it, job.id) }
                shares.unregister(job.id)
            }
        lastSpans.remove(job.id)
        streamed.remove(job.id)
        bus.emit(EngineEvent.Finished(job.id, end.bytes, end.renamed))
    }

    /** The ranges (and the file's identity) at a failure; when the engine deleted the file and left none, they are said to be gone. */
    private fun failure(
        job: JobSpec,
        live: Live,
        reason: String,
    ): EngineEvent.Failed {
        val run = live.segmented
        return EngineEvent.Failed(job.id, reason, live.done, run?.snapshot(), run?.partId, cleared = run == null && live.cleared)
    }

    private fun claim(
        part: Path,
        id: String,
        live: Live,
    ) {
        synchronized(claims) {
            val holder = claims[keyOf(part)]
            if (holder != null && holder != id) throw Fail(Reasons.NOT_WRITABLE)
            claims[keyOf(part)] = id
        }
        live.claimed = part
    }

    private fun release(
        part: Path,
        id: String,
    ) {
        synchronized(claims) { if (claims[keyOf(part)] == id) claims.remove(keyOf(part)) }
    }

    private fun claimed(part: Path): Boolean = synchronized(claims) { keyOf(part) in claims }

    /** The final and the `.part` path for [name] in [folder]; a name that would leave the folder is refused. */
    private fun pathsOf(
        folder: Path,
        name: String,
    ): Pair<Path, Path> {
        try {
            val final = folder.resolve(name).normalize()
            val part = folder.resolve("$name.part").normalize()
            if (final.parent != folder || part.parent != folder) throw Fail(Reasons.NOT_WRITABLE)
            return final to part
        } catch (_: InvalidPathException) {
            throw Fail(Reasons.NOT_WRITABLE)
        }
    }

    private fun sizeOf(
        part: Path,
        fallback: Long,
    ): Long =
        try {
            disk.size(part) ?: 0L
        } catch (_: IOException) {
            fallback
        }

    /** Renames the finished part file; a final name that has been taken meanwhile is never overwritten. */
    private fun complete(
        folder: Path,
        name: String,
        part: Path,
        done: Long,
    ): Done {
        try {
            val free = if (disk.exists(folder.resolve(name))) uniqueName(name) { disk.exists(folder.resolve(it)) } else name
            disk.move(part, folder.resolve(free))
            return Done(done, free.takeIf { it != name })
        } catch (e: IOException) {
            throw Fail(diskReason(e, Reasons.NOT_WRITABLE))
        }
    }

    /**
     * Runs the job; a segmented run that finds the file changed on the server is started again from zero, once: with one
     * connection per the rules of the new response. A second change fails the job.
     */
    private suspend fun transfer(
        job: JobSpec,
        live: Live,
    ): Done {
        var current = job
        var restarted = false
        while (true) {
            try {
                return attempt(current, live)
            } catch (e: SegmentedChanged) {
                live.segmented = null
                lastSpans.remove(job.id)
                streamed.remove(job.id)
                runCatching { disk.delete(e.part) }
                live.done = 0
                live.cleared = true
                if (restarted) throw Fail(Reasons.CHANGED)
                restarted = true
                current = current.copy(done = 0, etag = null, lastModified = null, total = null, spans = null, partId = null)
            }
        }
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private suspend fun attempt(
        job: JobSpec,
        live: Live,
    ): Done {
        val folder =
            try {
                Path.of(job.folder).normalize()
            } catch (_: InvalidPathException) {
                throw Fail(Reasons.NOT_WRITABLE)
            }
        try {
            disk.mkdirs(folder)
        } catch (e: IOException) {
            throw Fail(diskReason(e, Reasons.NOT_WRITABLE))
        }
        val known = if (job.name.isBlank()) null else pathsOf(folder, job.name)
        val bucket = shares.bucket(job.id)
        // A segmented job's part file is full size from the start: its ranges, not the file's size, say what is done.
        val resume = if (known != null) resumeSpans(job, known.second) else null
        if (known != null && resume != null) {
            claim(known.second, job.id, live)
            live.done = doneOf(resume.spans)
            return segmented(
                job,
                live,
                folder,
                job.name,
                known.second,
                resume.spans,
                resume.partId,
                false,
                null,
                job.etag,
                job.lastModified,
            )
        }
        // A row on several connections without ranges has a sequential partial file only if this engine saw one stream write it:
        // otherwise the file may be a segmented run's, pre-sized and full of gaps, and its size says nothing (this is decided here,
        // after the predecessor of the job has ended, because that one may only now create the file).
        // Ranges that were not accepted (they do not fit the file, or the file is not there as they left it) are just as untrusted.
        val noRanges = job.spans.isNullOrEmpty()
        val untrusted =
            known != null && ((!noRanges && resume == null) || (noRanges && job.connections > 1 && job.id !in streamed))
        // What is really in the part file is the truth: a stopped predecessor may have written past what the job spec says.
        val start =
            if (known == null) {
                job.done
            } else if (untrusted) {
                0L
            } else {
                sizeOf(known.second, job.done)
            }
        live.done = start
        if (known != null) {
            claim(known.second, job.id, live)
            if (untrusted) {
                runCatching { disk.delete(known.second) }
                live.cleared = true
            }
            if (start > 0 && start == job.total) return complete(folder, job.name, known.second, start)
        }
        // A fresh job over several connections asks for a range: a 206 with the total shows the server can be split.
        val splitting = job.connections > 1 && start == 0L
        val headers = HashMap<String, String>()
        if (start > 0) {
            headers["Range"] = "bytes=$start-"
            ifRangeOf(job.etag, job.lastModified)?.let { headers["If-Range"] = it }
        } else if (splitting) {
            val total = job.total
            if (total == null) {
                headers["Range"] = "bytes=0-"
            } else {
                initialSpans(total, job.connections).takeIf { it.size > 1 }?.let { headers["Range"] = "bytes=0-${it[0].end}" }
            }
        }
        // Every response held open has a permit, the probe's and a single stream's included (a segmented run takes the probe's over).
        permits.acquireFirst()
        var held = true
        try {
            var response = openOrFail(job.url, headers)
            val probe = if (splitting) probedRange(response) else null
            val plan =
                probe
                    ?.total
                    ?.takeIf { probe.start == 0L }
                    ?.let { initialSpans(it, job.connections) }
                    ?.takeIf { it.size > 1 }
            if (plan == null && probe != null && probe.total != probe.end + 1) {
                // The range asked for was a part of the file, and this file is not to be split: fetch all of it.
                response.close()
                response = openOrFail(job.url, headers - "Range")
            }
            response.use { open ->
                var done = start
                val range = open.headers["content-range"]?.firstOrNull()
                val append =
                    when (val d = resumeDecision(open.status, range, done)) {
                        ResumeDecision.Continue -> {
                            done > 0
                        }

                        ResumeDecision.Restart -> {
                            false.also { done = 0 }
                        }

                        is ResumeDecision.Fail -> {
                            if (d.reason == Reasons.CHANGED && known != null) {
                                // The part file no longer matches the server: a retry must start from zero, not repeat this request.
                                runCatching { disk.delete(known.second) }
                                streamed.remove(job.id)
                                live.done = 0
                                live.cleared = true
                            }
                            throw Fail(d.reason)
                        }
                    }
                live.done = done
                val length = open.headers["content-length"]?.firstOrNull()?.toLongOrNull()
                live.total = range?.substringAfter('/')?.toLongOrNull() ?: length?.let { done + it }
                val name =
                    job.name.ifBlank {
                        uniqueName(
                            nameFrom(open.headers["content-disposition"]?.firstOrNull(), open.finalUrl),
                        ) { disk.exists(folder.resolve(it)) || claimed(folder.resolve(it).normalize()) }
                    }
                val (_, part) = known ?: pathsOf(folder, name)
                if (known == null) claim(part, job.id, live)
                val etag = open.headers["etag"]?.firstOrNull() ?: if (append) job.etag else null
                val modified = open.headers["last-modified"]?.firstOrNull() ?: if (append) job.lastModified else null
                if (plan != null) {
                    held = false // the run has the probe's permit now, and releases it
                    return segmented(job, live, folder, name, part, plan, null, true, open, etag, modified)
                }
                bus.emit(EngineEvent.Started(job.id, name, live.total, etag, modified, done))

                val out =
                    try {
                        disk.openPart(part, append)
                    } catch (e: IOException) {
                        throw Fail(diskReason(e, Reasons.NOT_WRITABLE))
                    }
                streamed.add(job.id)
                live.cleared = false
                live.lastByte = clock.nowMs()
                live.lastReport = live.lastByte
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
                    // Fails a stalled job, and reports progress while nothing is read, so a stall shows as a speed falling to 0.
                    val monitor =
                        launch {
                            val tick =
                                minOf(
                                    (stallMs / 4).coerceIn(10, 1_000),
                                    if (reportMs >
                                        0
                                    ) {
                                        (reportMs / 2).coerceIn(10, 250)
                                    } else {
                                        Long.MAX_VALUE
                                    },
                                )
                            while (true) {
                                delay(tick)
                                val now = clock.nowMs()
                                if (now - live.lastByte >= stallMs) {
                                    live.stalled = true
                                    open.close()
                                    return@launch
                                }
                                if (reportMs > 0) report(job, live, now)
                            }
                        }
                    try {
                        out.use {
                            val buf = ByteArray(CHUNK)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val n =
                                    try {
                                        open.body.read(buf, 0, minOf(buf.size, bucket.maxChunk()))
                                    } catch (e: IOException) {
                                        currentCoroutineContext().ensureActive()
                                        throw Fail(Reasons.NETWORK)
                                    }
                                if (n < 0) break
                                val now = clock.nowMs()
                                live.lastByte = now
                                delay(bucket.reserve(n.toLong(), now))
                                try {
                                    out.write(buf, 0, n)
                                } catch (e: IOException) {
                                    throw Fail(diskReason(e, Reasons.NETWORK))
                                }
                                live.done += n
                                live.total?.let { if (live.done > it) live.total = null }
                                val written = clock.nowMs()
                                synchronized(live) { live.meter.add(written, n.toLong()) }
                                report(job, live, written)
                            }
                        }
                    } catch (e: IOException) {
                        throw Fail(diskReason(e, Reasons.NETWORK))
                    } finally {
                        closer.cancel()
                        monitor.cancel()
                    }
                }
                if (live.stalled) throw Fail(Reasons.NETWORK)
                if (live.total?.let { live.done < it } == true) throw Fail(Reasons.NETWORK)
                return complete(folder, name, part, live.done)
            }
        } finally {
            if (held) permits.release()
        }
    }

    private suspend fun openOrFail(
        url: String,
        headers: Map<String, String>,
    ): HttpOpen =
        try {
            transport.open(url, "GET", headers)
        } catch (e: TransportException) {
            throw Fail(e.reason)
        } catch (e: IOException) {
            throw Fail(Reasons.NETWORK)
        }

    /** The `Content-Range` of a 206 answer, null for any other answer. */
    private fun probedRange(open: HttpOpen): ContentRange? =
        if (open.status == 206) open.headers["content-range"]?.firstOrNull()?.let(::parseContentRange) else null

    /**
     * The ranges to resume a segmented job from: the newer of the job spec's and the ones the engine kept from a stopped
     * predecessor (never behind). Null, so that the job starts over, when there are none or when the `.part` file is not the one
     * they were made for: its length must be the ranges' end plus the identity trailer (a known [JobSpec.total] is the file's
     * size), and that trailer must be the identity the ranges were saved with. A file that is missing, recreated (even at the same
     * size, even all zeros) or cut says nothing about what the ranges call done. A disk that cannot say how big the file is
     * fails the job instead, with the file left alone.
     */
    private fun resumeSpans(
        job: JobSpec,
        part: Path,
    ): SavedRanges? {
        val given = job.spans?.takeIf { it.isNotEmpty() } ?: return null
        val size = onDisk { disk.size(part) } ?: return null
        val total = size - TRAILER_BYTES
        if (total <= 0 || (job.total != null && job.total != total)) return null
        val identity = onDisk { disk.readTail(part, TRAILER_BYTES) }?.let(::partIdOf) ?: return null
        val own = if (job.partId == identity && given.maxOf { it.end } + 1 == total) SavedRanges(given, identity) else null
        val kept = lastSpans[job.id]?.takeIf { it.partId == identity && it.spans.maxOf { s -> s.end } + 1 == total }
        return listOfNotNull(kept, own).maxByOrNull { doneOf(it.spans) }
    }

    private fun <T> onDisk(block: () -> T): T =
        try {
            block()
        } catch (e: IOException) {
            throw Fail(diskReason(e, Reasons.NOT_WRITABLE))
        }

    private suspend fun segmented(
        job: JobSpec,
        live: Live,
        folder: Path,
        name: String,
        part: Path,
        spans: List<Span>,
        partId: String?,
        holdsPermit: Boolean,
        first: HttpOpen?,
        etag: String?,
        modified: String?,
    ): Done {
        streamed.remove(job.id)
        val env =
            SegmentedEnv(
                transport,
                disk,
                clock,
                { bus.emit(it) },
                shares.bucket(job.id),
                permits,
                connectionPool,
                stallMs,
                reportMs,
                minSplit,
                retry,
                lastSpans,
            )
        val run = SegmentedRun(env, job, live, name, part, spans, holdsPermit, partId, first, etag, modified)
        try {
            run.run()
        } finally {
            run.releaseUnusedPermit()
        }
        return complete(folder, name, part, run.total)
    }

    /** A progress report, at most one per [reportMs]: the reader and the monitor both call this, and only one of them is due. */
    private suspend fun report(
        job: JobSpec,
        live: Live,
        now: Long,
    ) {
        val event =
            synchronized(live) {
                if (now - live.lastReport < reportMs) {
                    null
                } else {
                    live.lastReport = now
                    EngineEvent.Progress(job.id, live.done, live.total, live.meter.bytesPerSec(now))
                }
            }
        if (event != null) bus.emit(event)
    }

    private companion object {
        const val CHUNK = 64 * 1024
        const val CONNECTION_POOL_EXTRA = 32
    }
}
