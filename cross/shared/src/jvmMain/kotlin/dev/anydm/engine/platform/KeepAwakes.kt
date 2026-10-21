package dev.anydm.engine.platform

import dev.anydm.engine.log.Log

/** The keep-awake for this machine's OS (NET-11). Windows' JNA call is only referenced here, so it loads only there. */
fun keepAwakeForCurrentOs(
    runner: ProcessRunner,
    log: Log,
): KeepAwake = keepAwakeFor(DesktopOs.of(System.getProperty("os.name")), runner, ProcessHandle.current().pid(), JnaExecutionState, log)

internal fun keepAwakeFor(
    os: DesktopOs,
    runner: ProcessRunner,
    pid: Long,
    api: ExecutionStateApi,
    log: Log,
): KeepAwake =
    when (os) {
        DesktopOs.MACOS -> {
            HeldProcessKeepAwake(runner, ProcessSpec(listOf("caffeinate", "-i", "-w", pid.toString())), "caffeinate", log)
        }

        DesktopOs.WINDOWS -> {
            WindowsKeepAwake(api, log)
        }

        DesktopOs.LINUX -> {
            val command =
                listOf("systemd-inhibit", "--what=idle:sleep", "--who=AnyDM", "--why=Downloading", "--mode=block", "sleep", "infinity")
            HeldProcessKeepAwake(runner, ProcessSpec(command), "systemd-inhibit", log)
        }
    }
