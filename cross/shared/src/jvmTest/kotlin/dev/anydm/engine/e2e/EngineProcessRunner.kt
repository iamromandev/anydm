package dev.anydm.engine.e2e

import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.Path
import kotlin.time.Duration

/**
 * A child JVM on this test JVM's classpath, running [start]'s main class, whose stdout lines are read as they come:
 * `progress <bytes>`, `done`, `failed <message>`; anything else is kept in [lines]. [killAt] kills it like kill -9.
 */
internal class EngineProcessRunner private constructor(
    private val process: Process,
    private val stderr: File,
) {
    @Volatile var progress = 0L
        private set

    val lines: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @Volatile private var done = false

    /** Any protocol line yet: before one the child may not even have started its engine. */
    @Volatile private var spoke = false

    @Volatile private var failed: String? = null
    private val ended = CountDownLatch(1)
    private val moved = Object()

    init {
        Thread {
            try {
                process.inputStream.bufferedReader().useLines { all ->
                    for (line in all) {
                        if (line.startsWith("progress ") || line == "done" || line.startsWith("failed ")) spoke = true
                        when {
                            line.startsWith("progress ") -> progress = line.removePrefix("progress ").trim().toLongOrNull() ?: progress
                            line == "done" -> done = true
                            line.startsWith("failed ") -> failed = line
                            else -> lines += line
                        }
                        synchronized(moved) { moved.notifyAll() }
                    }
                }
            } catch (_: java.io.IOException) {
                // The child was killed mid-line: its output simply ends.
            } finally {
                ended.countDown()
                synchronized(moved) { moved.notifyAll() }
            }
        }.apply { isDaemon = true }.start()
    }

    val isAlive: Boolean get() = process.isAlive

    /**
     * Waits until the child has spoken and its progress reaches [bytes], then kills it (kill -9) and returns true; false
     * if the job was done first. A child that fails, exits or makes no such progress within [timeout] fails the test,
     * with its stderr.
     */
    fun killAt(
        bytes: Long,
        timeout: Duration,
    ): Boolean {
        val deadline = System.nanoTime() + timeout.inWholeNanoseconds
        synchronized(moved) {
            while ((!spoke || progress < bytes) && !done && failed == null && ended.count > 0 && System.nanoTime() < deadline) {
                moved.wait(10)
            }
        }
        val reached = spoke && progress >= bytes && failed == null
        val wasDone = done
        destroy()
        return when {
            wasDone -> {
                false
            }

            reached -> {
                true
            }

            else -> {
                fail(
                    if (failed !=
                        null
                    ) {
                        "child $failed"
                    } else if (ended.count ==
                        0L
                    ) {
                        "child exited before $bytes bytes"
                    } else {
                        "no progress to $bytes bytes in $timeout"
                    },
                )
            }
        }
    }

    private fun fail(why: String): Nothing = throw AssertionError("$why; stderr:\n${stderr.readText()}")

    /** Returns once the child prints `done`; throws with its stderr if it fails, exits without `done` or times out. */
    fun awaitDone(timeout: Duration) {
        val deadline = System.nanoTime() + timeout.inWholeNanoseconds
        synchronized(moved) {
            while (!done && failed == null && ended.count > 0 && System.nanoTime() < deadline) moved.wait(10)
        }
        if (done) return
        val why =
            when {
                failed != null -> failed
                ended.count == 0L -> "exited without done (code ${process.waitFor()})"
                else -> "timed out after $timeout"
            }
        destroy()
        fail("child $why")
    }

    /** Kills the child at once (kill -9) and waits for it to go. */
    fun destroy() {
        process.destroyForcibly()
        process.waitFor(10, TimeUnit.SECONDS)
    }

    companion object {
        fun start(
            mainClass: String,
            args: List<String>,
            stderr: File,
        ): EngineProcessRunner {
            val java = Path(System.getProperty("java.home"), "bin", "java").toString()
            val command = listOf(java, "-cp", System.getProperty("java.class.path"), mainClass) + args
            // Appended, not truncated: the stderr of every run of a case is kept for its failure message.
            val process = ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.appendTo(stderr)).start()
            return EngineProcessRunner(process, stderr)
        }
    }
}
