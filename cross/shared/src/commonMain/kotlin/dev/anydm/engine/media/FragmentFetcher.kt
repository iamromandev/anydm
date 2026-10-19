package dev.anydm.engine.media

import dev.anydm.engine.core.ErrorClass
import dev.anydm.engine.core.FileId
import dev.anydm.engine.core.RequestContext
import dev.anydm.engine.core.toStepFailed
import dev.anydm.engine.http.HttpProtocol
import dev.anydm.engine.platform.FileResult
import dev.anydm.engine.platform.FileSystem
import dev.anydm.engine.protocol.StepResult
import dev.anydm.engine.protocol.TransferIo
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.files.Path
import kotlin.time.Duration.Companion.milliseconds

private const val TRIES = 3
private val RETRY_STEP = 500.milliseconds
private const val JOIN_CHUNK = 1 shl 20

/** The name of fragment [index] in its folder: six digits, so the folder lists in order. */
internal fun fragmentName(index: Int): String = index.toString().padStart(6, '0')

/**
 * Saves the fragments of one stream whole, each in its own file in a folder, and later joins them (MED-03). A fragment
 * is written under a temporary name and renamed, so a stop never leaves half of one under its real name: whatever is
 * there is complete, and a rerun fetches only what is missing.
 */
internal class FragmentFetcher(
    private val http: HttpProtocol,
    private val fileSystem: FileSystem,
) {
    /** Makes sure every fragment of [fragments] is in [folder] and returns the bytes there. */
    suspend fun fetch(
        folder: Path,
        fragments: List<FragmentRef>,
        context: RequestContext?,
        io: TransferIo,
    ): StepResult<Long> {
        val made = fileSystem.prepareFolder(folder)
        if (made is FileResult.Failed) return made.toStepFailed()
        val listed =
            when (val found = fileSystem.list(folder)) {
                is FileResult.Failed -> return found.toStepFailed()
                is FileResult.Ok -> found.value
            }
        val present = mutableSetOf<Int>()
        var done = 0L
        for (path in listed) {
            if (path.name.endsWith(".tmp")) {
                fileSystem.delete(path)
                continue
            }
            val index = path.name.toIntOrNull()?.takeIf { it in fragments.indices } ?: continue
            val size = fileSystem.size(path)
            if (size is FileResult.Ok) {
                present += index
                done += size.value
            }
        }
        if (done > 0) io.progress(done)
        val missing = fragments.indices.filter { it !in present }
        if (missing.isEmpty()) return StepResult.Ok(done)
        val queue = Channel<Int>(Channel.UNLIMITED)
        missing.forEach { queue.trySend(it) }
        queue.close()
        val lock = Mutex()
        var failure: StepResult.Failed? = null
        coroutineScope {
            val workers = mutableListOf<Job>()
            repeat(minOf(http.connectionCount(), missing.size)) {
                workers +=
                    launch {
                        for (index in queue) {
                            when (val got = fetchOne(folder, index, fragments[index], context, io)) {
                                is StepResult.Failed -> {
                                    lock.withLock { if (failure == null) failure = got }
                                    workers.forEach { it.cancel() }
                                    return@launch
                                }

                                is StepResult.Ok -> {
                                    lock.withLock {
                                        done += got.value
                                        io.progress(done)
                                    }
                                }
                            }
                        }
                    }
            }
        }
        return failure ?: StepResult.Ok(done)
    }

    /** Writes fragments 0 until [count] in order into [file] through [io] and returns the bytes written. */
    suspend fun join(
        folder: Path,
        count: Int,
        file: FileId,
        io: TransferIo,
    ): StepResult<Long> {
        var offset = 0L
        for (index in 0 until count) {
            val path = Path(folder, fragmentName(index))
            val size =
                when (val found = fileSystem.size(path)) {
                    is FileResult.Ok -> found.value
                    is FileResult.Failed -> return StepResult.Failed(null, "fragment $index is missing from ${folder.name}")
                }
            var at = 0L
            while (at < size) {
                val chunk =
                    when (val read = fileSystem.read(path, at, minOf(JOIN_CHUNK.toLong(), size - at).toInt())) {
                        is FileResult.Failed -> return read.toStepFailed()
                        is FileResult.Ok -> read.value
                    }
                if (chunk.isEmpty()) return StepResult.Failed(null, "fragment $index is shorter than it was")
                val written = io.write(file, offset, chunk)
                if (written is StepResult.Failed) return written
                offset += chunk.size
                at += chunk.size
            }
        }
        return StepResult.Ok(offset)
    }

    /** Fetches fragment [index] up to [TRIES] times and saves it; returns its size. */
    private suspend fun fetchOne(
        folder: Path,
        index: Int,
        ref: FragmentRef,
        context: RequestContext?,
        io: TransferIo,
    ): StepResult<Long> {
        var last: StepResult.Failed? = null
        for (attempt in 1..TRIES) {
            if (attempt > 1) delay(RETRY_STEP * (attempt - 1))
            val got = http.withSlot(ref.url) { http.fetchFragment(ref.url, context, ref.rangeStart, ref.rangeEnd, io) }
            when (got) {
                is StepResult.Ok -> {
                    return save(folder, index, got.value)
                }

                is StepResult.Failed -> {
                    if (got.errorClass != ErrorClass.TRANSIENT && got.errorClass != ErrorClass.RATE_LIMITED) return got
                    last = got
                }
            }
        }
        return requireNotNull(last)
    }

    private suspend fun save(
        folder: Path,
        index: Int,
        bytes: ByteArray,
    ): StepResult<Long> {
        val final = Path(folder, fragmentName(index))
        val temp = Path(folder, fragmentName(index) + ".tmp")
        fileSystem.delete(temp)
        val made = fileSystem.preallocate(temp, bytes.size.toLong())
        if (made is FileResult.Failed) return made.toStepFailed()
        val writer =
            when (val opened = fileSystem.openWriter(temp)) {
                is FileResult.Failed -> return opened.toStepFailed()
                is FileResult.Ok -> opened.value
            }
        writer.use {
            if (bytes.isNotEmpty()) {
                val written = it.write(0, bytes)
                if (written is FileResult.Failed) return written.toStepFailed()
            }
            val synced = it.sync()
            if (synced is FileResult.Failed) return synced.toStepFailed()
        }
        val moved = fileSystem.atomicMove(temp, final, replace = true)
        if (moved is FileResult.Failed) return moved.toStepFailed()
        return StepResult.Ok(bytes.size.toLong())
    }
}
