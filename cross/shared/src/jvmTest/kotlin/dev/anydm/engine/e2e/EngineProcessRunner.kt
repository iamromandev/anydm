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

    @Volatile private var failed: String? = null
    private val ended = CountDownLatch(1)
    private val moved = Object()

    init {
        Thread {
            process.inputStream.bufferedReader().useLines { all ->
                for (line in all) {
                    when {
                        line.startsWith("progress ") -> progress = line.removePrefix("progress ").trim().toLongOrNull() ?: progress
                        line == "done" -> done = true
                        line.startsWith("failed") -> failed = line
                        else -> lines += line
                    }
                    synchronized(moved) { moved.notifyAll() }
                }
            }
            ended.countDown()
            synchronized(moved) { moved.notifyAll() }
        }.apply { isDaemon = true }.start()
    }

    val isAlive: Boolean get() = process.isAlive

    /** Waits until progress reaches [bytes] (or the job is done), then kills the child; false if it ended first. */
    fun killAt(
        bytes: Long,
        timeout: Duration,
    ): Boolean {
        val deadline = System.nanoTime() + timeout.inWholeNanoseconds
        synchronized(moved) {
            while (progress < bytes && !done && failed == null && process.isAlive && System.nanoTime() < deadline) {
                moved.wait(10)
            }
        }
        if (!process.isAlive || done) return false.also { destroy() }
        destroy()
        return true
    }

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
        throw AssertionError("child $why; stderr:\n${stderr.readText()}")
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
            val process = ProcessBuilder(command).redirectError(stderr).start()
            return EngineProcessRunner(process, stderr)
        }
    }
}
