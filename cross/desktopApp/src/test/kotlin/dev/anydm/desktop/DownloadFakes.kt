package dev.anydm.desktop

import dev.anydm.engine.http.DiskIo
import dev.anydm.engine.http.Engine
import dev.anydm.engine.http.EngineEvent
import dev.anydm.engine.http.JobSpec
import dev.anydm.engine.http.LinkProber
import dev.anydm.engine.http.PartFile
import dev.anydm.engine.http.ProbeOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** Counts calls from a thread in [forbidden] (the UI thread): no HTTP or file call may come from there. */
class ThreadGuard {
    val forbidden = CopyOnWriteArrayList<Thread>()

    @Volatile
    var violations = 0

    fun check() {
        if (Thread.currentThread() in forbidden) violations++
    }
}

/** Records every start and stop; the test emits the events the engine would. Never touches the network. */
class FakeEngine(
    val guard: ThreadGuard = ThreadGuard(),
) : Engine {
    private val bus = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 256)
    override val events: SharedFlow<EngineEvent> = bus
    val starts = CopyOnWriteArrayList<JobSpec>()
    val stops = CopyOnWriteArrayList<String>()
    val rates = CopyOnWriteArrayList<Long?>()

    /** Every call in the order it came: "start:1", "stop:1", "join:1". */
    val log = CopyOnWriteArrayList<String>()

    /** Starts that throw, as an engine with a bug would: the next [failStarts] calls. */
    @Volatile var failStarts = 0

    /** When set, [join] waits for it, as a job that is slow to wind down would make it. */
    @Volatile var joinGate: CompletableDeferred<Unit>? = null

    override fun start(job: JobSpec) {
        guard.check()
        log.add("start:${job.id}")
        if (failStarts > 0) {
            failStarts--
            error("engine bug")
        }
        starts.add(job)
    }

    override fun stop(id: String) {
        guard.check()
        log.add("stop:$id")
        stops.add(id)
    }

    override suspend fun join(id: String) {
        guard.check()
        log.add("join:$id")
        joinGate?.await()
    }

    override fun setRate(bytesPerSec: Long?) {
        guard.check()
        rates.add(bytesPerSec)
    }

    override fun forget(id: String) {
        guard.check()
        log.add("forget:$id")
    }

    fun emit(e: EngineEvent) {
        check(bus.tryEmit(e))
    }
}

/** Answers from [answers] by URL (default: [fallback]); [gate], when set, runs first so a test can hold a probe in flight. */
class FakeProber(
    val guard: ThreadGuard = ThreadGuard(),
    val answers: MutableMap<String, ProbeOutcome> = ConcurrentHashMap(),
    var fallback: ProbeOutcome = ProbeOutcome.Failed("offline"),
    var gate: (suspend (String) -> Unit)? = null,
) : LinkProber {
    val probed = CopyOnWriteArrayList<String>()
    val cancelled = CopyOnWriteArrayList<String>()

    override suspend fun probe(url: String): ProbeOutcome {
        guard.check()
        probed.add(url)
        try {
            gate?.invoke(url)
        } catch (e: kotlinx.coroutines.CancellationException) {
            cancelled.add(url)
            throw e
        }
        return answers[url] ?: fallback
    }
}

class FakeOpener(
    val guard: ThreadGuard = ThreadGuard(),
    @Volatile var result: Boolean = true,
    /** Whether the OS has a Trash; false makes the app delete for good. */
    @Volatile var canTrash: Boolean = true,
) : OsOpener {
    val opened = CopyOnWriteArrayList<Path>()
    val revealed = CopyOnWriteArrayList<Path>()
    val trashed = CopyOnWriteArrayList<Path>()

    override fun trash(path: Path): Boolean {
        guard.check()
        trashed.add(path)
        return canTrash
    }

    override fun open(path: Path): Boolean {
        guard.check()
        opened.add(path)
        return result
    }

    override fun reveal(path: Path): Boolean {
        guard.check()
        revealed.add(path)
        return result
    }
}

/** A folder in memory: file name to bytes. Writes through [openPart] land in the map when the stream is flushed or closed. */
class MemoryDiskIo(
    val guard: ThreadGuard = ThreadGuard(),
) : DiskIo {
    val files = ConcurrentHashMap<Path, ByteArray>()
    val deleted = CopyOnWriteArrayList<Path>()

    fun put(
        p: String,
        size: Int,
    ) {
        files[Path.of(p)] = ByteArray(size)
    }

    override fun size(p: Path): Long? {
        guard.check()
        return files[p]?.size?.toLong()
    }

    override fun exists(p: Path): Boolean {
        guard.check()
        return files.containsKey(p)
    }

    override fun openPart(
        p: Path,
        append: Boolean,
    ): OutputStream {
        guard.check()
        val start = if (append) files[p] ?: ByteArray(0) else ByteArray(0)
        files[p] = start
        return object : ByteArrayOutputStream() {
            init {
                write(start)
            }

            override fun flush() {
                files[p] = toByteArray()
            }

            override fun close() = flush()
        }
    }

    override fun move(
        from: Path,
        to: Path,
    ) {
        guard.check()
        files[to] = files.remove(from) ?: ByteArray(0)
    }

    override fun delete(p: Path) {
        guard.check()
        files.remove(p)
        deleted.add(p)
    }

    override fun mkdirs(p: Path) {
        guard.check()
    }

    /** Positional writes into the in-memory file, which is [size] long when given (zero-filled). */
    override fun openSpans(
        p: Path,
        size: Long?,
    ): PartFile {
        guard.check()
        files.compute(p) { _, old ->
            val bytes = old ?: ByteArray(0)
            if (size != null && bytes.size.toLong() != size) bytes.copyOf(size.toInt()) else bytes
        }
        return object : PartFile {
            override fun write(
                offset: Long,
                buf: ByteArray,
                off: Int,
                len: Int,
            ) {
                guard.check()
                files.compute(p) { _, old ->
                    val bytes = old ?: ByteArray(0)
                    val grown = if (bytes.size < offset + len) bytes.copyOf((offset + len).toInt()) else bytes
                    System.arraycopy(buf, off, grown, offset.toInt(), len)
                    grown
                }
            }

            override fun force() = guard.check()

            override fun truncate(size: Long) {
                guard.check()
                files.compute(p) { _, old -> old?.let { if (it.size > size) it.copyOf(size.toInt()) else it } }
            }

            override fun close() = Unit
        }
    }

    override fun readTail(
        p: Path,
        len: Int,
    ): ByteArray? {
        guard.check()
        val bytes = files[p] ?: return null
        return if (bytes.size < len) null else bytes.copyOfRange(bytes.size - len, bytes.size)
    }

    override fun freeSpace(dir: Path): Long {
        guard.check()
        return Long.MAX_VALUE
    }
}
