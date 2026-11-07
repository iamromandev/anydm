package dev.anydm.engine.net

import dev.anydm.engine.platform.network
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlin.test.Test

/** The plain answers, the known fixes per OS and the copyable report (#635). */
class DoctorTextTest {
    private val wifi = network("en0")
    private val phone = NetworkInfo("en5", "iPhone USB", true, listOf("172.20.10.2"), 5)
    private val name: (NetworkInfo) -> String = { if (it.key == "en0") "Home Wi-Fi" else it.name }

    private fun diagnosis(
        network: NetworkInfo,
        verdict: Verdict,
        sharesWith: List<String> = emptyList(),
        findings: List<Finding> = emptyList(),
    ) = NetworkDiagnosis(network, findings, verdict, sharesWith = sharesWith)

    @Test
    fun aWorkingNetworkIsAnsweredByItsShownName() {
        DoctorText.answer(diagnosis(wifi, Verdict.WORKS), name) shouldBe "Home Wi-Fi works on its own."
    }

    @Test
    fun aSharedConnectionNamesTheOtherNetworkButOnlyHedgesThatItCannotAddSpeed() {
        val text = DoctorText.answer(diagnosis(wifi, Verdict.SHARED, listOf("en5")), name) { "iPhone USB" }
        text shouldContain "probably shares one internet connection with iPhone USB"
        text shouldContain "likely cannot add speed"
    }

    @Test
    fun everyVerdictHasAnAnswerThatNamesTheNetwork() {
        for (v in Verdict.entries) {
            DoctorText.answer(diagnosis(wifi, v, listOf("en5")), name) shouldContain "Home Wi-Fi"
        }
    }

    @Test
    fun macOsSuggestsDisableUnlessNeededForABrokenIPhoneNetwork() {
        DoctorText.fixes(diagnosis(phone, Verdict.NO_ROUTE), DoctorOs.MACOS, name) shouldContain DoctorText.MACOS_PHONE
    }

    @Test
    fun theMacOsTipIsNotGivenForAWorkingNetworkOrANonIPhoneUsbAdapter() {
        DoctorText.fixes(diagnosis(phone, Verdict.WORKS), DoctorOs.MACOS, name).shouldBeEmpty()
        val dongle = NetworkInfo("en7", "USB 10/100 LAN", true, listOf("10.1.1.2"), 7)
        DoctorText.fixes(diagnosis(dongle, Verdict.NO_ROUTE), DoctorOs.MACOS, name).joinToString() shouldNotContain "Disable unless needed"
    }

    @Test
    fun otherSystemsDoNotGetTheMacOsSetting() {
        for (os in listOf(DoctorOs.WINDOWS, DoctorOs.LINUX)) {
            DoctorText.fixes(diagnosis(phone, Verdict.NO_ROUTE), os, name).joinToString() shouldNotContain "Disable unless needed"
        }
    }

    @Test
    fun aNetworkWithNoRouteGetsTheVpnAndFirewallHintOfItsSystem() {
        DoctorText.fixes(diagnosis(wifi, Verdict.NO_ROUTE), DoctorOs.WINDOWS, name).joinToString() shouldContain "Windows Defender Firewall"
        DoctorText.fixes(diagnosis(wifi, Verdict.NO_ROUTE), DoctorOs.MACOS, name).joinToString() shouldContain "Little Snitch"
    }

    @Test
    fun aPlainWorkingNetworkNeedsNoFix() {
        DoctorText.fixes(diagnosis(wifi, Verdict.WORKS), DoctorOs.LINUX, name).shouldBeEmpty()
    }

    @Test
    fun theReportListsEachNetworkWithItsAnswerFindingsAndFixes() {
        val found = listOf(Finding(DoctorCheck.ADDRESS, CheckResult.FAIL, "No answer."))
        val report =
            DoctorReport(
                online = true,
                os = DoctorOs.MACOS,
                diagnoses = listOf(diagnosis(wifi, Verdict.WORKS), diagnosis(phone, Verdict.NO_ROUTE, findings = found)),
            )
        val text = DoctorText.report(report, name)
        text shouldContain "Home Wi-Fi (en0)"
        text shouldContain "Home Wi-Fi works on its own."
        text shouldContain "iPhone USB has no way to the internet"
        text shouldContain "Internet by address: failed - No answer."
        text shouldContain "Fix: ${DoctorText.MACOS_PHONE}"
        text shouldContain "System: macOS"
    }

    @Test
    fun theReportNamesTheSharingPartnerByShownName() {
        val report =
            DoctorReport(
                true,
                DoctorOs.LINUX,
                listOf(diagnosis(wifi, Verdict.SHARED, listOf("en5")), diagnosis(phone, Verdict.SHARED, listOf("en0"))),
            )
        DoctorText.report(report, name) shouldContain "probably shares one internet connection with iPhone USB"
    }

    @Test
    fun osNamesMapToFamilies() {
        doctorOsOf("Mac OS X") shouldBe DoctorOs.MACOS
        doctorOsOf("Windows 11") shouldBe DoctorOs.WINDOWS
        doctorOsOf("Linux") shouldBe DoctorOs.LINUX
        doctorOsOf("FreeBSD") shouldBe DoctorOs.OTHER
    }
}
