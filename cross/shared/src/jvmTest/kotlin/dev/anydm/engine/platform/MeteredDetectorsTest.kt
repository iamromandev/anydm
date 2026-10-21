package dev.anydm.engine.platform

import dev.anydm.engine.platform.FakeProcessRunner.Scripted
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class MeteredDetectorsTest {
    private fun nmcli(
        out: String,
        exit: Int = 0,
    ) = FakeProcessRunner(listOf(Scripted(listOf("nmcli"), exitCode = exit, stdout = if (out.isEmpty()) emptyList() else listOf(out))))

    private fun powerShell(out: String) =
        FakeProcessRunner(listOf(Scripted(listOf("powershell.exe"), stdout = if (out.isEmpty()) emptyList() else listOf(out))))

    @Test
    fun nmcliCommandAndEnvironment() =
        runTest {
            val runner = nmcli("no")
            NmcliMeteredDetector(runner).isMetered("wlan0") shouldBe false

            runner.started.single() shouldBe
                ProcessSpec(listOf("nmcli", "-g", "GENERAL.METERED", "dev", "show", "wlan0"), environment = mapOf("LC_ALL" to "C"))
        }

    @Test
    fun nmcliParses() =
        runTest {
            for (text in listOf("yes", "yes (guessed)", "guess-yes", " YES \t")) {
                NmcliMeteredDetector(nmcli(text)).isMetered("eth0") shouldBe true
            }
            for (text in listOf("no", "no (guessed)", "guess-no")) {
                NmcliMeteredDetector(nmcli(text)).isMetered("eth0") shouldBe false
            }
            NmcliMeteredDetector(nmcli("unknown")).isMetered("eth0") shouldBe null
            NmcliMeteredDetector(nmcli("")).isMetered("eth0") shouldBe null
            NmcliMeteredDetector(nmcli("none")).isMetered("eth0") shouldBe null
        }

    @Test
    fun nmcliNonZeroOrMissingIsNull() =
        runTest {
            NmcliMeteredDetector(nmcli("yes", exit = 1)).isMetered("eth0") shouldBe null
            NmcliMeteredDetector(FakeProcessRunner(emptyList())).isMetered("eth0") shouldBe null
        }

    @Test
    fun aBadInterfaceNameMakesNoCall() =
        runTest {
            val runner = nmcli("yes")
            val detector = NmcliMeteredDetector(runner)

            detector.isMetered("en0;rm") shouldBe null
            detector.isMetered("a".repeat(16)) shouldBe null
            detector.isMetered("") shouldBe null
            detector.isMetered("unknown") shouldBe null
            runner.started shouldBe emptyList()

            detector.isMetered("a".repeat(15)) shouldBe true
            runner.started.size shouldBe 1
        }

    @Test
    fun powerShellCommandIsExact() =
        runTest {
            val runner = powerShell("Unrestricted")
            PowerShellMeteredDetector(runner).isMetered("Ethernet")

            runner.started.single().command shouldBe
                listOf(
                    "powershell.exe",
                    "-NoProfile",
                    "-NonInteractive",
                    "-Command",
                    "[void][Windows.Networking.Connectivity.NetworkInformation," +
                        "Windows.Networking.Connectivity,ContentType=WindowsRuntime]; " +
                        "\$p=[Windows.Networking.Connectivity.NetworkInformation]::GetInternetConnectionProfile(); " +
                        "if (\$p) { \$p.GetConnectionCost().NetworkCostType }",
                )
        }

    @Test
    fun powerShellParses() =
        runTest {
            PowerShellMeteredDetector(powerShell("Unrestricted")).isMetered("x") shouldBe false
            PowerShellMeteredDetector(powerShell("Fixed")).isMetered("x") shouldBe true
            PowerShellMeteredDetector(powerShell("Variable\r")).isMetered("x") shouldBe true
            PowerShellMeteredDetector(powerShell("Unknown")).isMetered("x") shouldBe null
            PowerShellMeteredDetector(powerShell("")).isMetered("x") shouldBe null
            PowerShellMeteredDetector(FakeProcessRunner(listOf(Scripted(listOf("powershell.exe"), exitCode = 1, stdout = listOf("Fixed")))))
                .isMetered("x") shouldBe null
            PowerShellMeteredDetector(FakeProcessRunner(emptyList())).isMetered("x") shouldBe null
        }

    @Test
    fun powerShellIgnoresTheKey() =
        runTest {
            val runner = powerShell("Fixed")
            PowerShellMeteredDetector(runner).isMetered("unknown") shouldBe true
            PowerShellMeteredDetector(runner).isMetered("en0;rm") shouldBe true
            runner.started.size shouldBe 2
            runner.started[0] shouldBe runner.started[1]
        }

    @Test
    fun macOsHasNoDetector() {
        val runner = FakeProcessRunner(emptyList())

        meteredDetectorFor(DesktopOs.of("Mac OS X"), runner) shouldBe MeteredDetector.None
        meteredDetectorFor(DesktopOs.of("Linux"), runner).shouldBeInstanceOf<NmcliMeteredDetector>()
        meteredDetectorFor(DesktopOs.of("Windows 11"), runner).shouldBeInstanceOf<PowerShellMeteredDetector>()
    }
}
