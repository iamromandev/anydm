package dev.anydm.engine.platform

import dev.anydm.engine.platform.ProcessOutcome.Ok

/** The metered detector for [os]; macOS has no readable API, so it only has the manual flag. */
internal fun meteredDetectorFor(
    os: DesktopOs,
    runner: ProcessRunner,
): MeteredDetector =
    when (os) {
        DesktopOs.MACOS -> MeteredDetector.None
        DesktopOs.WINDOWS -> PowerShellMeteredDetector(runner)
        DesktopOs.LINUX -> NmcliMeteredDetector(runner)
    }

fun meteredDetectorForCurrentOs(runner: ProcessRunner): MeteredDetector =
    meteredDetectorFor(DesktopOs.of(System.getProperty("os.name")), runner)

/** NetworkManager's answer for one interface, through `nmcli`; null when it can't tell. */
internal class NmcliMeteredDetector(
    private val runner: ProcessRunner,
) : MeteredDetector {
    override suspend fun isMetered(network: String): Boolean? {
        if (network == "unknown" || !INTERFACE.matches(network)) return null
        val spec = ProcessSpec(listOf("nmcli", "-g", "GENERAL.METERED", "dev", "show", network), environment = mapOf("LC_ALL" to "C"))
        val result = (runner.run(spec) as? Ok)?.value ?: return null
        if (result.exitCode != 0) return null
        val answer = result.stdout.trim().lowercase()
        return when {
            answer == "yes" || answer.startsWith("yes ") || answer == "guess-yes" -> true
            answer == "no" || answer.startsWith("no ") || answer == "guess-no" -> false
            else -> null
        }
    }

    private companion object {
        val INTERFACE = Regex("[A-Za-z0-9_.:@-]{1,15}")
    }
}

/** Windows' cost of the internet connection profile, through Windows PowerShell 5.1; the key is ignored. */
internal class PowerShellMeteredDetector(
    private val runner: ProcessRunner,
) : MeteredDetector {
    override suspend fun isMetered(network: String): Boolean? {
        val spec = ProcessSpec(listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", SCRIPT))
        val result = (runner.run(spec) as? Ok)?.value ?: return null
        if (result.exitCode != 0) return null
        return when (result.stdout.trim()) {
            "Unrestricted" -> false
            "Fixed", "Variable" -> true
            else -> null
        }
    }

    private companion object {
        const val SCRIPT =
            "[void][Windows.Networking.Connectivity.NetworkInformation,Windows.Networking.Connectivity,ContentType=WindowsRuntime]; " +
                "\$p=[Windows.Networking.Connectivity.NetworkInformation]::GetInternetConnectionProfile(); " +
                "if (\$p) { \$p.GetConnectionCost().NetworkCostType }"
    }
}
