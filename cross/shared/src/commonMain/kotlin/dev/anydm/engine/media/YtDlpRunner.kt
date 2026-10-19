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

/** The JSON of a page, and whether yt-dlp said formats were withheld (MED-19). */
internal data class Extracted(
    val json: String,
    val hinted: Boolean,
)

private val WITHHELD_HINTS = listOf("formats have been skipped", "requested format is not available", "sign in to confirm")

/** Runs `yt-dlp -J` for a page (MED-01) and says what came back as a [StepResult]. */
internal class YtDlpRunner(
    private val runner: ProcessRunner,
) {
    /** The `-J` JSON of [url]. Streams stdout, so a long page isn't cut at the runner's collect limit. */
    suspend fun extract(
        ytDlp: ResolvedTool,
        deno: ResolvedTool?,
        url: String,
        extraArgs: List<String> = emptyList(),
        /** A playlist's flat listing (`--flat-playlist`), not one video (`--no-playlist`). */
        flat: Boolean = false,
    ): StepResult<Extracted> {
        val command =
            buildList {
                add(ytDlp.executable(Tool.YT_DLP.id).toString())
                // Our command alone: a user's yt-dlp config must not change what we get.
                add("--ignore-config")
                // Warnings stay on: "some formats have been skipped" in stderr is what tells a withheld format (MED-19).
                addAll(listOf("-J", if (flat) "--flat-playlist" else "--no-playlist"))
                if (deno != null) addAll(listOf("--js-runtimes", "deno:${deno.executable(Tool.DENO.id)}"))
                addAll(extraArgs)
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
        if (exit != 0) return ytDlpFailure(exit, errors)
        if (truncated) return StepResult.Failed(null, "yt-dlp's answer for this page is too big to read")
        val json = out.lineSequence().lastOrNull { it.startsWith("{") }
        val hinted = errors.any { line -> WITHHELD_HINTS.any { it in line.lowercase() } }
        return StepResult.Ok(Extracted(json ?: "", hinted))
    }
}

/** What yt-dlp's last words say about why it failed: gone, rate limited, transient, or a plain failure with its message. */
internal fun ytDlpFailure(
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

private val GONE_HINTS =
    listOf("private video", "video unavailable", "this video is not available", "has been removed", "does not exist", "no longer available")
private val RATE_HINTS = listOf("429", "too many requests")
private val TRANSIENT_HINTS = listOf("unable to download", "timed out", "connection", "temporary failure")
