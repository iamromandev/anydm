package dev.anydm.engine.media

import dev.anydm.engine.core.ErrorClass
import dev.anydm.engine.platform.ProcessOutcome
import dev.anydm.engine.platform.ProcessRunner
import dev.anydm.engine.platform.ProcessSpec
import dev.anydm.engine.platform.ProcessStream
import dev.anydm.engine.protocol.StepResult
import dev.anydm.engine.protocol.TransferIo
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private const val KEPT_ERROR_LINES = 50
private const val CHARGE_CHUNK = 1L shl 20

/**
 * yt-dlp's own downloader for a job (MED-05, MED-07), held to the job's share of the bandwidth limit. Its program paces
 * itself with `--limit-rate`, which can only be set when it starts, so the share is asked again every [poll]; when it
 * has changed by a fifth or more, or began or ended, the program is stopped and started again with the new limit, and it
 * carries on from its partial file. Its progress lines go to the meter, the limiter and `io.progress`; [seed] is what its
 * files already held when the job started, which is not new.
 */
internal class YtDlpDownload(
    private val runner: ProcessRunner,
) {
    /**
     * Runs `command(limit)` until it exits. [minGap] is how long a process runs before a changed share may restart it,
     * so a share that keeps moving doesn't keep stopping it.
     */
    suspend fun download(
        command: (limit: Long?) -> List<String>,
        io: TransferIo,
        seed: Map<String, Long> = emptyMap(),
        poll: Duration = 2.seconds,
        minGap: Duration = 10.seconds,
        /** What to add to the program's environment (the proxy's login, NET-15). */
        environment: Map<String, String> = emptyMap(),
    ): StepResult<Unit> {
        val tally = ProgressTally()
        seed.forEach { (file, bytes) -> tally.seed(file, bytes) }
        if (tally.total > 0) io.progress(tally.total)
        var limit = io.speedShare()
        while (true) {
            val process =
                when (val started = runner.start(ProcessSpec(command(limit), environment = environment))) {
                    is ProcessOutcome.Failed -> return StepResult.Failed(ErrorClass.TOOL_MISSING, "yt-dlp")
                    is ProcessOutcome.Ok -> started.value
                }
            var restart = false
            val errors = ArrayDeque<String>()
            val exit =
                coroutineScope {
                    val collector =
                        launch {
                            process.lines.collect { line ->
                                when (line.stream) {
                                    ProcessStream.STDOUT -> {
                                        ProgressTemplate.parse(line.text)?.let { progress ->
                                            val added = tally.add(progress)
                                            // The program paces itself, so the limiter is charged after the fact: that keeps
                                            // the other jobs' share of a global limit honest.
                                            var left = added
                                            while (left > 0) {
                                                val n = minOf(left, CHARGE_CHUNK).toInt()
                                                io.limiter.acquire(n)
                                                left -= n
                                            }
                                            if (added > 0) io.meter.record(added)
                                            io.progress(tally.total)
                                        }
                                    }

                                    ProcessStream.STDERR -> {
                                        errors.addLast(line.text)
                                        if (errors.size > KEPT_ERROR_LINES) errors.removeFirst()
                                    }
                                }
                            }
                        }
                    val monitor =
                        launch {
                            var ran = Duration.ZERO
                            while (true) {
                                delay(poll)
                                ran += poll
                                val share = io.speedShare()
                                if (ran >= minGap && changed(limit, share)) {
                                    restart = true
                                    limit = share
                                    process.stop()
                                    return@launch
                                }
                            }
                        }
                    val code = process.await()
                    monitor.cancel()
                    collector.join()
                    code
                }
            if (exit == 0) return StepResult.Ok(Unit)
            if (!restart) return ytDlpFailure(exit, errors)
        }
    }

    /** A limit appearing or going, or moving by a fifth of the old one or more. */
    private fun changed(
        old: Long?,
        new: Long?,
    ): Boolean = (old == null) != (new == null) || (old != null && new != null && abs(new - old) * 5 >= old)
}
