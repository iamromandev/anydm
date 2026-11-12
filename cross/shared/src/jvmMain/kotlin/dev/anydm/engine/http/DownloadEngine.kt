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

interface Engine {
    val events: SharedFlow<EngineEvent>

    fun start(job: JobSpec)

    fun stop(id: String)

    fun setRate(bytesPerSec: Long?)

    /** Returns once the job of [id], if one is running, has ended: after a [stop], its `.part` file is closed. */
    suspend fun join(id: String) = Unit
}

/**
 * One connection per download, one coroutine per job. `stop` cancels and emits nothing; the `.part` file stays.
 * Jobs run under their own supervisor: one that fails never takes the others down, whatever [scope] is.
 */
class DownloadEngine(
    private val transport: HttpTransport,
    private val disk: DiskIo,
    private val clock: Clock,
    scope: CoroutineScope,
    private val stallMs: Long = 30_000,
    private val reportMs: Long = 500,
) : Engine {
    private val jobScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    private val bus = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 1024)
    override val events: SharedFlow<EngineEvent> = bus.asSharedFlow()
    private val bucket = TokenBucket(null)
    private val jobs = HashMap<String, Job>()

    /**
     * The `.part` files that running jobs write, by the id of the job: two jobs never write the same file. Keyed by [keyOf], so
     * names that differ only in case count as one file (they are one on a case-insensitive file system).
     */
    private val claims = HashMap<String, String>()

    private fun keyOf(path: Path): String = path.normalize().toString().lowercase(Locale.ROOT)

    override fun setRate(bytesPerSec: Long?) = bucket.setRate(bytesPerSec)

    @Synchronized
    override fun start(job: JobSpec) {
        val old = jobs[job.id]
        if (old != null && old.isActive) return // already running; stop() first
        val created =
            jobScope.launch(Dispatchers.IO, CoroutineStart.LAZY) {
                old?.join() // a stopped predecessor may still be winding down on the same .part file
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

    private class Fail(
        val reason: String,
    ) : Exception(reason)

    /** What the reader and the monitor share; the monitor runs on another thread. */
    private class Live(
        done: Long,
    ) {
        @Volatile var done = done

        @Volatile var total: Long? = null

        @Volatile var lastByte = 0L

        @Volatile var lastReport = 0L

        @Volatile var stalled = false

        /** The part file this job holds in [claims], so it is released when the job ends. */
        @Volatile var claimed: Path? = null
        val meter = SpeedMeter()
    }

    private class Done(
        val bytes: Long,
        /** The name the file ended up with, when it is not the one the job asked for. */
        val renamed: String?,
    )

    private suspend fun run(job: JobSpec) {
        val live = Live(job.done)
        val end =
            try {
                transfer(job, live)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Fail) {
                bus.emit(EngineEvent.Failed(job.id, e.reason, live.done))
                return
            } catch (_: Exception) {
                // Anything unforeseen is this download's failure, not the engine's.
                bus.emit(EngineEvent.Failed(job.id, Reasons.NETWORK, live.done))
                return
            } finally {
                live.claimed?.let { release(it, job.id) }
            }
        bus.emit(EngineEvent.Finished(job.id, end.bytes, end.renamed))
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

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private suspend fun transfer(
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
        // What is really in the part file is the truth: a stopped predecessor may have written past what the job spec says.
        val start = if (known != null) sizeOf(known.second, job.done) else job.done
        live.done = start
        if (known != null) {
            claim(known.second, job.id, live)
            if (start > 0 && start == job.total) return complete(folder, job.name, known.second, start)
        }
        val headers = HashMap<String, String>()
        if (start > 0) {
            headers["Range"] = "bytes=$start-"
            (job.etag ?: job.lastModified)?.let { headers["If-Range"] = it }
        }
        val response =
            try {
                transport.open(job.url, "GET", headers)
            } catch (e: TransportException) {
                throw Fail(e.reason)
            } catch (e: IOException) {
                throw Fail(Reasons.NETWORK)
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
                            live.done = 0
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
            bus.emit(EngineEvent.Started(job.id, name, live.total, etag, modified, done))

            val out =
                try {
                    disk.openPart(part, append)
                } catch (e: IOException) {
                    throw Fail(diskReason(e, Reasons.NOT_WRITABLE))
                }
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
                            minOf((stallMs / 4).coerceIn(10, 1_000), if (reportMs > 0) (reportMs / 2).coerceIn(10, 250) else Long.MAX_VALUE)
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

    /**
     * Disk errors are recognised by their message, which is all the JVM gives us; anything unknown becomes [fallback].
     */
    private fun diskReason(
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

    private companion object {
        const val CHUNK = 64 * 1024
    }
}
