package dev.anydm.engine.platform

import dev.anydm.engine.log.Log
import kotlin.coroutines.cancellation.CancellationException

/** The power actions for this machine's OS (NET-10). */
fun powerActionsForCurrentOs(
    runner: ProcessRunner,
    log: Log,
): PowerActions = powerActionsFor(DesktopOs.of(System.getProperty("os.name")), runner, log)

internal fun powerActionsFor(
    os: DesktopOs,
    runner: ProcessRunner,
    log: Log,
): PowerActions = DesktopPowerActions(os, runner, log)

/**
 * Sleep and shut down through the OS's own commands. True only when the program started and exited 0; a missing
 * command or a failure is logged and gives false. Tests must give it a fake runner: these commands are real.
 */
class DesktopPowerActions internal constructor(
    private val os: DesktopOs,
    private val runner: ProcessRunner,
    private val log: Log,
) : PowerActions {
    override suspend fun sleep(): Boolean =
        run(
            when (os) {
                DesktopOs.MACOS -> listOf("pmset", "sleepnow")
                DesktopOs.WINDOWS -> listOf("rundll32.exe", "powrprof.dll,SetSuspendState", "0,1,0")
                DesktopOs.LINUX -> listOf("systemctl", "suspend")
            },
        )

    override suspend fun shutDown(): Boolean =
        run(
            when (os) {
                DesktopOs.MACOS -> listOf("osascript", "-e", "tell application \"System Events\" to shut down")
                DesktopOs.WINDOWS -> listOf("shutdown", "/s", "/t", "0")
                DesktopOs.LINUX -> listOf("systemctl", "poweroff")
            },
        )

    private suspend fun run(command: List<String>): Boolean =
        try {
            when (val outcome = runner.run(ProcessSpec(command))) {
                is ProcessOutcome.Ok -> {
                    (outcome.value.exitCode == 0).also { if (!it) warn(command, "exit code ${outcome.value.exitCode}") }
                }

                is ProcessOutcome.Failed -> {
                    warn(command, "${outcome.failure}")
                    false
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            warn(command, e.message ?: e::class.simpleName.orEmpty())
            false
        }

    private fun warn(
        command: List<String>,
        detail: String,
    ) = log.warn("platform") { "power action ${command.first()} failed: $detail" }
}
