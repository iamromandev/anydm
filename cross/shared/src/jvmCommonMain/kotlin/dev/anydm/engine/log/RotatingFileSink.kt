package dev.anydm.engine.log

import kotlinx.io.files.Path
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import java.nio.file.Path as NioPath

/**
 * Log files in [folder] (DIA-01): `anydm.log`, rolled to `anydm.1.log` … `anydm.<keep-1>.log` when the next line would
 * take it past [maxBytes], the oldest dropped. Lines are appended unbuffered, so a crash loses none already written.
 * It never throws: a line it can't write is counted, and the next one that can be written is preceded by a note saying
 * how many were lost. The folder is made on the first write.
 */
class RotatingFileSink internal constructor(
    folder: Path,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val keep: Int = DEFAULT_KEEP,
    private val open: (NioPath) -> OutputStream,
) : LogSink {
    constructor(
        folder: Path,
        maxBytes: Long = DEFAULT_MAX_BYTES,
        keep: Int = DEFAULT_KEEP,
    ) : this(folder, maxBytes, keep, { Files.newOutputStream(it, StandardOpenOption.CREATE, StandardOpenOption.APPEND) })

    init {
        require(maxBytes > 0) { "maxBytes must be > 0: $maxBytes" }
        require(keep >= 1) { "keep must be >= 1: $keep" }
    }

    private val dir: NioPath = NioPath.of(folder.toString())
    private val lock = ReentrantLock()
    private var stream: OutputStream? = null
    private var size = 0L
    private var closed = false

    /** Lines lost since the last one written; for tests. */
    internal var dropped = 0
        private set

    override fun write(line: String) =
        lock.withLock {
            if (closed) return@withLock
            try {
                var out = opened()
                if (dropped > 0) {
                    append(out, "${now()} WARN  log $dropped lines dropped")
                    dropped = 0
                }
                val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
                if (size > 0 && size + bytes.size > maxBytes) {
                    rotate()
                    out = opened()
                }
                out.write(bytes)
                out.flush()
                size += bytes.size
            } catch (_: IOException) {
                dropped++
                closeStream()
            }
        }

    override fun close() =
        lock.withLock {
            closed = true
            closeStream()
        }

    private fun opened(): OutputStream {
        stream?.let { return it }
        Files.createDirectories(dir)
        val file = dir.resolve(CURRENT)
        size = if (Files.exists(file)) Files.size(file) else 0
        return open(file).also { stream = it }
    }

    private fun append(
        out: OutputStream,
        text: String,
    ) {
        val bytes = (text + "\n").toByteArray(Charsets.UTF_8)
        out.write(bytes)
        out.flush()
        size += bytes.size
    }

    // Closed first: Windows can't rename an open file.
    private fun rotate() {
        closeStream()
        if (keep == 1) {
            Files.deleteIfExists(dir.resolve(CURRENT))
        } else {
            Files.deleteIfExists(dir.resolve(rolled(keep - 1)))
            for (n in keep - 2 downTo 1) {
                val from = dir.resolve(rolled(n))
                if (Files.exists(from)) Files.move(from, dir.resolve(rolled(n + 1)), StandardCopyOption.REPLACE_EXISTING)
            }
            Files.move(dir.resolve(CURRENT), dir.resolve(rolled(1)), StandardCopyOption.REPLACE_EXISTING)
        }
        size = 0
    }

    private fun closeStream() {
        try {
            stream?.close()
        } catch (_: IOException) {
            // Nothing more to lose.
        }
        stream = null
    }

    companion object {
        const val DEFAULT_MAX_BYTES: Long = 2L * 1024 * 1024
        const val DEFAULT_KEEP = 5
        private const val CURRENT = "anydm.log"

        private fun rolled(n: Int) = "anydm.$n.log"

        // The same form as Log's lines.
        private fun now(): String =
            Instant.now().truncatedTo(ChronoUnit.MILLIS).toString().let {
                if ('.' in
                    it
                ) {
                    it
                } else {
                    it.removeSuffix("Z") + ".000Z"
                }
            }
    }
}
