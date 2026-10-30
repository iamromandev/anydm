package dev.anydm.engine.platform

import kotlinx.coroutines.flow.Flow
import kotlinx.io.files.Path

/** A program to run: the program and its arguments, where to run it, and what to add to its environment. */
data class ProcessSpec(
    val command: List<String>,
    val workingDirectory: Path? = null,
    val environment: Map<String, String> = emptyMap(),
) {
    init {
        require(command.isNotEmpty()) { "command must not be empty" }
    }

    // The environment can hold a proxy login: only its names are printed.
    override fun toString(): String = "ProcessSpec(command=$command, workingDirectory=$workingDirectory, environment=${environment.keys})"
}

enum class ProcessStream { STDOUT, STDERR }

/** One line a running program wrote, without its line break. */
data class ProcessLine(
    val stream: ProcessStream,
    val text: String,
)

/** What a finished program wrote. [truncated] means more came than the runner keeps. */
data class ProcessResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val truncated: Boolean,
)

/** Why a program didn't start. */
sealed interface ProcessFailure {
    /** There is no such program. */
    data class NotFound(
        val command: String,
    ) : ProcessFailure

    data class StartFailed(
        val command: String,
        val detail: String?,
    ) : ProcessFailure
}

/** Expected failures come back as values; only bugs throw. */
sealed interface ProcessOutcome<out T> {
    data class Ok<out T>(
        val value: T,
    ) : ProcessOutcome<T>

    data class Failed(
        val failure: ProcessFailure,
    ) : ProcessOutcome<Nothing>
}

/** A program that is running. */
interface RunningProcess {
    /** Its output, line by line, until it closes both streams. Collect it once. */
    val lines: Flow<ProcessLine>

    /** Waits for it to end and gives its exit code. */
    suspend fun await(): Int

    /**
     * Ends it and everything it started (MED-08): a stop that reaches only the parent would leave yt-dlp's ffmpeg
     * running. Stopping a process that has ended changes nothing.
     */
    suspend fun stop()
}

/** Runs programs on this machine (the desktop implementation is in jvmMain; tests use a scripted fake). */
interface ProcessRunner {
    /**
     * Runs the program to its end and collects its output, up to 8 MiB per stream; for short commands. A caller that is
     * cancelled stops the program and its children.
     */
    suspend fun run(spec: ProcessSpec): ProcessOutcome<ProcessResult>

    /** Starts the program and hands it over for streaming; the caller must [RunningProcess.await] or stop it. */
    suspend fun start(spec: ProcessSpec): ProcessOutcome<RunningProcess>
}
