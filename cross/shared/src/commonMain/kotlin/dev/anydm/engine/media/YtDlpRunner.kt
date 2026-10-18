package dev.anydm.engine.media

import dev.anydm.engine.core.ErrorClass
import dev.anydm.engine.platform.ProcessOutcome
import dev.anydm.engine.platform.ProcessRunner
import dev.anydm.engine.platform.ProcessSpec
import dev.anydm.engine.platform.ProcessStream
import dev.anydm.engine.protocol.StepResult
import dev.anydm.engine.tools.ResolvedTool
import dev.anydm.engine.tools.Tool
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.minutes

private const val MAX_OUTPUT = 32 * 1024 * 1024
private const val KEPT_ERROR_LINES = 50
private val EXTRACT_LIMIT = 2.minutes

/** Runs `yt-dlp -J` for a page (MED-01) and says what came back as a [StepResult]. */
internal class YtDlpRunner(
    private val runner: ProcessRunner,
) {
    /** The `-J` JSON of [url]. Streams stdout, so a long page isn't cut at the runner's collect limit. */
    suspend fun extract(
        ytDlp: ResolvedTool,
        deno: ResolvedTool?,
        url: String,
    ): StepResult<String> {
        val command =
            buildList {
                add(ytDlp.executable(Tool.YT_DLP.id).toString())
                addAll(listOf("-J", "--no-playlist", "--no-warnings"))
                if (deno != null) addAll(listOf("--js-runtimes", "deno:${deno.executable(Tool.DENO.id)}"))
                add("--")
                add(url)
            }
        val process =
            when (val started = runner.start(ProcessSpec(command))) {
                is ProcessOutcome.Failed -> return StepResult.Failed(ErrorClass.TOOL_MISSING, Tool.YT_DLP.id)
                is ProcessOutcome.Ok -> started.value
            }
        val out = StringBuilder()
        val errors = ArrayDeque<String>()
        var truncated = false
        val exit =
            withTimeoutOrNull(EXTRACT_LIMIT) {
                coroutineScope {
                    process.lines.collect { line ->
                        when (line.stream) {
                            ProcessStream.STDOUT -> {
                                if (out.length + line.text.length <= MAX_OUTPUT) out.appendLine(line.text) else truncated = true
                            }

                            ProcessStream.STDERR -> {
                                errors.addLast(line.text)
                                if (errors.size > KEPT_ERROR_LINES) errors.removeFirst()
                            }
                        }
                    }
                    process.await()
                }
            }
        if (exit == null) {
            process.stop()
            return StepResult.Failed(ErrorClass.TRANSIENT, "yt-dlp took longer than $EXTRACT_LIMIT to read the page")
        }
        if (exit != 0) return failureOf(exit, errors)
        if (truncated) return StepResult.Failed(null, "yt-dlp's answer for this page is too big to read")
        val json = out.lineSequence().lastOrNull { it.startsWith("{") }
        return if (json == null) StepResult.Ok("") else StepResult.Ok(json)
    }

    private fun failureOf(
        exit: Int,
        errors: List<String>,
    ): StepResult.Failed {
        val line = errors.lastOrNull { it.startsWith("ERROR:") } ?: errors.lastOrNull { it.isNotBlank() }
        val message = line?.removePrefix("ERROR:")?.trim().takeUnless { it.isNullOrBlank() } ?: "yt-dlp exited with code $exit"
        val lower = message.lowercase()
        val errorClass =
            when {
                GONE_HINTS.any { it in lower } -> ErrorClass.GONE
                RATE_HINTS.any { it in lower } -> ErrorClass.RATE_LIMITED
                TRANSIENT_HINTS.any { it in lower } -> ErrorClass.TRANSIENT
                else -> null
            }
        return StepResult.Failed(errorClass, message)
    }

    private companion object {
        val GONE_HINTS =
            listOf(
                "private video",
                "video unavailable",
                "this video is not available",
                "has been removed",
                "does not exist",
                "no longer available",
            )
        val RATE_HINTS = listOf("429", "too many requests")
        val TRANSIENT_HINTS = listOf("unable to download", "timed out", "connection", "temporary failure")
    }
}
