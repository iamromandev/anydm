package dev.anydm.engine.platform

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlin.coroutines.cancellation.CancellationException

/**
 * Runs programs with [ProcessBuilder]. Stopping ends the whole tree (MED-08): TERM to the process and everything it
 * started, a short grace, then KILL; `taskkill /T /F` on Windows. A caller that is cancelled while [run]ning stops it.
 */
class DesktopProcessRunner(
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ProcessRunner {
    override suspend fun run(spec: ProcessSpec): ProcessOutcome<ProcessResult> {
        val process =
            when (val started = launch(spec)) {
                is ProcessOutcome.Failed -> return started
                is ProcessOutcome.Ok -> started.value
            }
        // The pipes are read outside the caller's scope: a read blocked on a pipe can't be interrupted, and a caller
        // that is cancelled must not wait for it before the program is stopped.
        val readers = CoroutineScope(io + SupervisorJob())
        val stdout = readers.async { readCapped(process.inputStream) }
        val stderr = readers.async { readCapped(process.errorStream) }
        try {
            val code = runInterruptible(io) { process.waitFor() }
            val out = stdout.await()
            val err = stderr.await()
            return ProcessOutcome.Ok(ProcessResult(code, out.first, err.first, out.second || err.second))
        } catch (e: CancellationException) {
            withContext(NonCancellable + io) { killTree(process) }
            throw e
        }
    }

    override suspend fun start(spec: ProcessSpec): ProcessOutcome<RunningProcess> =
        when (val started = launch(spec)) {
            is ProcessOutcome.Failed -> started
            is ProcessOutcome.Ok -> ProcessOutcome.Ok(Running(started.value, io))
        }

    private suspend fun launch(spec: ProcessSpec): ProcessOutcome<Process> =
        withContext(io) {
            try {
                val builder = ProcessBuilder(spec.command)
                spec.workingDirectory?.let { builder.directory(File(it.toString())) }
                builder.environment().putAll(spec.environment)
                val process = builder.start()
                process.outputStream.close() // nothing is ever typed to a tool; one waiting for input would hang
                ProcessOutcome.Ok(process)
            } catch (e: IOException) {
                val missing = NOT_FOUND.containsMatchIn(e.message.orEmpty())
                val program = spec.command.first()
                ProcessOutcome.Failed(if (missing) ProcessFailure.NotFound(program) else ProcessFailure.StartFailed(program, e.message))
            }
        }

    private class Running(
        private val process: Process,
        private val io: CoroutineDispatcher,
    ) : RunningProcess {
        // Read from the start, whoever collects: a program must never block on a full pipe because nobody is looking.
        // The channel is bounded, so a program whose output is never collected stalls after 1024 lines; collect or stop.
        private val channel = Channel<ProcessLine>(1024)
        private val readers: List<Job>

        init {
            val scope = CoroutineScope(io + SupervisorJob())
            readers =
                listOf(ProcessStream.STDOUT to process.inputStream, ProcessStream.STDERR to process.errorStream).map { (stream, source) ->
                    scope.launch {
                        try {
                            source.bufferedReader().use { reader ->
                                while (true) channel.send(ProcessLine(stream, reader.readLine() ?: break))
                            }
                        } catch (e: IOException) {
                            // The process was stopped and its pipe closed under the reader.
                        }
                    }
                }
            scope.launch {
                readers.joinAll()
                channel.close()
            }
        }

        // A collector whose coroutine is cancelled takes the program with it; one that just stops early (first, take)
        // leaves it running.
        override val lines: Flow<ProcessLine> = channel.receiveAsFlow().onCompletion { if (!currentCoroutineContext().isActive) stop() }

        override suspend fun await(): Int =
            try {
                runInterruptible(io) { process.waitFor() }
            } catch (e: CancellationException) {
                stop()
                throw e
            }

        override suspend fun stop() {
            withContext(NonCancellable + io) {
                killTree(process)
                // A reader stuck sending into a full channel lets go of its pipe.
                readers.forEach { it.cancel() }
            }
        }
    }

    private companion object {
        const val CAP = 8 * 1024 * 1024
        const val GRACE_MILLIS = 2_000L
        val NOT_FOUND = Regex("error=2,|No such file|cannot find the file", RegexOption.IGNORE_CASE)
        val onWindows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

        /** Up to [CAP] bytes as text; the rest is read and thrown away so the program never blocks on a full pipe. */
        fun readCapped(stream: InputStream): Pair<String, Boolean> {
            val kept = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var truncated = false
            try {
                while (true) {
                    val n = stream.read(buffer)
                    if (n < 0) break
                    val room = CAP - kept.size()
                    if (n <= room) {
                        kept.write(buffer, 0, n)
                    } else {
                        if (room > 0) kept.write(buffer, 0, room)
                        truncated = true
                    }
                }
            } catch (e: IOException) {
                // Closed by a stop.
            }
            return kept.toString(Charsets.UTF_8) to truncated
        }

        fun killTree(process: Process) {
            if (!process.isAlive) return
            if (onWindows) {
                ProcessBuilder("taskkill", "/T", "/F", "/PID", process.pid().toString()).start().waitFor()
                return
            }
            val tree = process.toHandle().descendants().toList() + process.toHandle()
            tree.forEach { it.destroy() }
            val deadline = System.currentTimeMillis() + GRACE_MILLIS
            while (tree.any { it.isAlive } && System.currentTimeMillis() < deadline) Thread.sleep(50)
            tree.filter { it.isAlive }.forEach { it.destroyForcibly() }
        }
    }
}
