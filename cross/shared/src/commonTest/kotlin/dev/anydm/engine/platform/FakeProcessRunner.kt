package dev.anydm.engine.platform

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration

/**
 * A ProcessRunner that plays a script: the first [Scripted] whose [Scripted.prefix] starts the command answers it, and a
 * command nothing matches is a missing program. Every spec is recorded in [started]. Delays are virtual time.
 */
internal class FakeProcessRunner(
    private val script: List<Scripted>,
) : ProcessRunner {
    data class Scripted(
        val prefix: List<String>,
        val exitCode: Int = 0,
        val stdout: List<String> = emptyList(),
        val stderr: List<String> = emptyList(),
        val delay: Duration = Duration.ZERO,
    )

    val started = mutableListOf<ProcessSpec>()

    private fun scriptFor(spec: ProcessSpec) = script.firstOrNull { spec.command.take(it.prefix.size) == it.prefix }

    override suspend fun run(spec: ProcessSpec): ProcessOutcome<ProcessResult> {
        started += spec
        val scripted = scriptFor(spec) ?: return ProcessOutcome.Failed(ProcessFailure.NotFound(spec.command.first()))
        delay(scripted.delay)
        val result =
            ProcessResult(
                scripted.exitCode,
                scripted.stdout.joinToString("") { it + "\n" },
                scripted.stderr.joinToString("") {
                    it +
                        "\n"
                },
                false,
            )
        return ProcessOutcome.Ok(result)
    }

    override suspend fun start(spec: ProcessSpec): ProcessOutcome<RunningProcess> {
        started += spec
        val scripted = scriptFor(spec) ?: return ProcessOutcome.Failed(ProcessFailure.NotFound(spec.command.first()))
        return ProcessOutcome.Ok(Running(scripted))
    }

    private class Running(
        private val scripted: Scripted,
    ) : RunningProcess {
        private val stopped = CompletableDeferred<Unit>()

        override val lines: Flow<ProcessLine> =
            flow {
                scripted.stdout.forEach { emit(ProcessLine(ProcessStream.STDOUT, it)) }
                scripted.stderr.forEach { emit(ProcessLine(ProcessStream.STDERR, it)) }
            }

        override suspend fun await(): Int {
            if (stopped.isCompleted) return STOPPED
            if (scripted.delay == Duration.ZERO) return scripted.exitCode
            val wasStopped = withTimeoutOrNull(scripted.delay) { stopped.await() } != null
            return if (wasStopped) STOPPED else scripted.exitCode
        }

        override suspend fun stop() {
            stopped.complete(Unit)
        }
    }

    companion object {
        /** The exit code a stopped fake process reports, like a SIGTERM. */
        const val STOPPED = 143
    }
}
