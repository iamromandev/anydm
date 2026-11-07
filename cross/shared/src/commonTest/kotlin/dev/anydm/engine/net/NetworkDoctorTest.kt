package dev.anydm.engine.net

import dev.anydm.engine.http.FakeTransport
import dev.anydm.engine.http.HttpIoException
import dev.anydm.engine.http.HttpIoKind
import dev.anydm.engine.http.HttpRequest
import dev.anydm.engine.http.response
import dev.anydm.engine.platform.FakeNetworkBinder
import dev.anydm.engine.platform.FakeOnlineProbe
import dev.anydm.engine.platform.network
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/** Network Doctor (#635): each check against fake networks, and the public address lookup only on request. */
class NetworkDoctorTest {
    private val wifi = network("en0")
    private val phone = network("en5")

    private val binder = FakeNetworkBinder()
    private val probe = FakeOnlineProbe()
    private val lookups = mutableListOf<String>()
    private val ips = mutableMapOf("en0" to "203.0.113.7", "en5" to "198.51.100.9")
    private val ipFailures = mutableSetOf<String>()

    /** Networks whose bound requests fail, by what fails: name lookups, literal addresses. */
    private val noName = mutableSetOf<String>()
    private val noRoute = mutableSetOf<String>()
    private val sentOn = mutableListOf<String?>()

    /** Hosts that never answer, networks whose requests never answer, and networks that answer names but not addresses. */
    private val blockedHosts = mutableSetOf<String>()
    private val hanging = mutableSetOf<String>()
    private val noLiteral = mutableSetOf<String>()

    private val transport =
        FakeTransport { request ->
            val net = currentBoundNetwork()
            sentOn += net?.key
            val host = request.url.substringAfter("://").substringBefore("/")
            val literal = host.first().isDigit()
            when {
                net == null -> throw IllegalStateException("sent by the default route")
                net.key in hanging -> awaitCancellation()
                host in blockedHosts -> throw HttpIoException(HttpIoKind.TIMEOUT, host)
                literal && net.key in noLiteral -> throw HttpIoException(HttpIoKind.TIMEOUT, host)
                net.key in noRoute -> throw HttpIoException(HttpIoKind.TIMEOUT, host)
                !literal && net.key in noName -> throw HttpIoException(HttpIoKind.CONNECT, host)
                else -> response(204, finalUrl = request.url)
            }
        }

    private val ip =
        PublicIpLookup { net ->
            lookups += net.key
            if (net.key in ipFailures) error("lookup failed")
            ips.getValue(net.key)
        }

    private fun doctor(os: DoctorOs = DoctorOs.MACOS) = NetworkDoctor(transport, binder, probe, ip, os)

    private fun NetworkDiagnosis.result(check: DoctorCheck) = findings.first { it.check == check }.result

    @Test
    fun aNetworkThatAnswersOnItsOwnWorks() =
        runTest {
            val d = doctor().diagnose(wifi, withPublicIp = false)
            d.verdict shouldBe Verdict.WORKS
            d.result(DoctorCheck.BINDING) shouldBe CheckResult.PASS
            d.result(DoctorCheck.ADDRESS) shouldBe CheckResult.PASS
            d.result(DoctorCheck.DNS) shouldBe CheckResult.PASS
        }

    @Test
    fun everyRequestLeavesOnlyByTheCheckedNetwork() =
        runTest {
            doctor().diagnose(phone, withPublicIp = false)
            sentOn.isNotEmpty() shouldBe true
            sentOn.toSet() shouldBe setOf("en5")
        }

    @Test
    fun aNetworkWithNoWayToTheInternetFailsTheAddressAndTheNameCheck() =
        runTest {
            noRoute += "en5"
            val d = doctor().diagnose(phone, withPublicIp = false)
            d.verdict shouldBe Verdict.NO_ROUTE
            d.result(DoctorCheck.ADDRESS) shouldBe CheckResult.FAIL
            d.result(DoctorCheck.DNS) shouldBe CheckResult.FAIL
        }

    @Test
    fun aNetworkThatReachesAddressesButNotNamesFailsDns() =
        runTest {
            noName += "en5"
            val d = doctor().diagnose(phone, withPublicIp = false)
            d.verdict shouldBe Verdict.NO_DNS
            d.result(DoctorCheck.ADDRESS) shouldBe CheckResult.PASS
            d.result(DoctorCheck.DNS) shouldBe CheckResult.FAIL
        }

    @Test
    fun aNetworkThatIsDownIsNotTried() =
        runTest {
            val d = doctor().diagnose(network("en5", up = false), withPublicIp = false)
            d.verdict shouldBe Verdict.DOWN
            transport.requests.size shouldBe 0
        }

    @Test
    fun bindingThatIsUnsupportedStopsTheChecksAndSendsNothing() =
        runTest {
            binder.statuses["en5"] = BindStatus.UNSUPPORTED
            val d = doctor().diagnose(phone, withPublicIp = true)
            d.verdict shouldBe Verdict.NOT_BINDABLE
            d.result(DoctorCheck.BINDING) shouldBe CheckResult.FAIL
            d.result(DoctorCheck.ADDRESS) shouldBe CheckResult.SKIPPED
            transport.requests.size shouldBe 0
            lookups shouldBe emptyList()
        }

    @Test
    fun bestEffortBindingWorksWithAWarning() =
        runTest {
            binder.statuses["en0"] = BindStatus.BEST_EFFORT
            val d = doctor().diagnose(wifi, withPublicIp = false)
            d.verdict shouldBe Verdict.WORKS
            d.result(DoctorCheck.BINDING) shouldBe CheckResult.WARN
        }

    @Test
    fun theLookupDoesNotRunUnlessAsked() =
        runTest {
            val report = doctor().diagnoseAll(listOf(wifi, phone), withPublicIp = false)
            lookups shouldBe emptyList()
            report.diagnoses.all { it.result(DoctorCheck.PUBLIC_IP) == CheckResult.SKIPPED } shouldBe true
        }

    @Test
    fun theLookupRunsOncePerWorkingNetworkWhenAsked() =
        runTest {
            val report = doctor().diagnoseAll(listOf(wifi, phone), withPublicIp = true)
            lookups.sorted() shouldBe listOf("en0", "en5")
            report.diagnoses.map { it.verdict } shouldBe listOf(Verdict.WORKS, Verdict.WORKS)
            report.diagnoses.map { it.publicIp } shouldBe listOf("203.0.113.7", "198.51.100.9")
        }

    @Test
    fun twoNetworksWithTheSamePublicAddressShareOneConnection() =
        runTest {
            ips["en5"] = "203.0.113.7"
            val report = doctor().diagnoseAll(listOf(wifi, phone), withPublicIp = true)
            report.diagnoses.map { it.verdict } shouldBe listOf(Verdict.SHARED, Verdict.SHARED)
            report.diagnoses[0].sharesWith shouldBe listOf("en5")
            report.diagnoses[1].sharesWith shouldBe listOf("en0")
        }

    @Test
    fun aNetworkThatFailedEarlierIsNotLookedUpAndNotCalledShared() =
        runTest {
            noRoute += "en5"
            val report = doctor().diagnoseAll(listOf(wifi, phone), withPublicIp = true)
            lookups shouldBe listOf("en0")
            report.diagnoses.map { it.verdict } shouldBe listOf(Verdict.WORKS, Verdict.NO_ROUTE)
        }

    @Test
    fun aFailedLookupIsSaidAndDoesNotChangeTheVerdict() =
        runTest {
            ipFailures += "en5"
            val report = doctor().diagnoseAll(listOf(wifi, phone), withPublicIp = true)
            val phoneDiagnosis = report.diagnoses[1]
            phoneDiagnosis.verdict shouldBe Verdict.WORKS
            phoneDiagnosis.result(DoctorCheck.PUBLIC_IP) shouldBe CheckResult.WARN
            phoneDiagnosis.publicIp shouldBe null
            report.diagnoses[0].verdict shouldBe Verdict.WORKS
        }

    @Test
    fun theReportSaysWhetherTheMachineIsOnline() =
        runTest {
            probe.online = false
            doctor().diagnoseAll(listOf(wifi), withPublicIp = false).online shouldBe false
        }

    @Test
    fun aLookupWithNoAnswerAddressIsAFailure() =
        runTest {
            val blank = PublicIpLookup { "" }
            val d = NetworkDoctor(transport, binder, probe, blank, DoctorOs.LINUX)
            val report = d.diagnoseAll(listOf(wifi), withPublicIp = true)
            report.diagnoses[0].result(DoctorCheck.PUBLIC_IP) shouldBe CheckResult.WARN
        }

    @Test
    fun aSecondPlainAddressIsTriedBeforeTheAddressCheckFails() =
        runTest {
            blockedHosts += "1.1.1.1"
            val d = doctor().diagnose(wifi, withPublicIp = false)
            d.verdict shouldBe Verdict.WORKS
            d.result(DoctorCheck.ADDRESS) shouldBe CheckResult.PASS
            transport.requests.map { it.url } shouldContain "http://9.9.9.9/"
        }

    @Test
    fun namesThatWorkWhileAddressesAreBlockedIsNotNoRoute() =
        runTest {
            noLiteral += "en5"
            val d = doctor().diagnose(phone, withPublicIp = false)
            d.verdict shouldBe Verdict.WORKS
            d.result(DoctorCheck.ADDRESS) shouldBe CheckResult.WARN
            d.result(DoctorCheck.DNS) shouldBe CheckResult.PASS
        }

    @Test
    fun addressesThatAnswerWhileNamesDoNotIsNoDnsAndNothingAnsweringIsNoRoute() =
        runTest {
            noName += "en5"
            doctor().diagnose(phone, withPublicIp = false).verdict shouldBe Verdict.NO_DNS
            noRoute += "en0"
            val d = doctor().diagnose(wifi, withPublicIp = false)
            d.verdict shouldBe Verdict.NO_ROUTE
            d.result(DoctorCheck.ADDRESS) shouldBe CheckResult.FAIL
            d.result(DoctorCheck.DNS) shouldBe CheckResult.FAIL
        }

    @Test
    fun aNetworkWhoseRequestsNeverAnswerFailsInsteadOfHanging() =
        runTest {
            hanging += "en5"
            val d = doctor().diagnose(phone, withPublicIp = true)
            d.verdict shouldBe Verdict.NO_ROUTE
            d.result(DoctorCheck.PUBLIC_IP) shouldBe CheckResult.SKIPPED
            currentTime shouldBe 3 * NetworkDoctor.REQUEST_TIMEOUT_MS
        }

    @Test
    fun networksAreCheckedAtTheSameTime() =
        runTest {
            hanging += "en0"
            hanging += "en5"
            val report = doctor().diagnoseAll(listOf(wifi, phone), withPublicIp = false)
            report.diagnoses.map { it.verdict } shouldBe listOf(Verdict.NO_ROUTE, Verdict.NO_ROUTE)
            currentTime shouldBe 3 * NetworkDoctor.REQUEST_TIMEOUT_MS
        }

    @Test
    fun aLookupThatNeverAnswersIsAWarning() =
        runTest {
            val never = PublicIpLookup { awaitCancellation() }
            val d = NetworkDoctor(transport, binder, probe, never, DoctorOs.LINUX).diagnose(wifi, withPublicIp = true)
            d.verdict shouldBe Verdict.WORKS
            d.result(DoctorCheck.PUBLIC_IP) shouldBe CheckResult.WARN
            currentTime shouldBe NetworkDoctor.LOOKUP_TIMEOUT_MS
        }

    @Test
    fun cancellingTheCheckStillCancels() =
        runTest {
            hanging += "en0"
            var cancelled = false
            val job =
                launch {
                    try {
                        doctor().diagnose(wifi, withPublicIp = false)
                    } catch (e: CancellationException) {
                        cancelled = true
                        throw e
                    }
                }
            runCurrent()
            job.cancel()
            job.join()
            cancelled shouldBe true
        }

    @Test
    fun aBinderThatThrowsMeansNotBindable() =
        runTest {
            val broken =
                object : dev.anydm.engine.platform.NetworkBinder {
                    override suspend fun probe(network: NetworkInfo): BindStatus = error("no binder")
                }
            val d = NetworkDoctor(transport, broken, probe, ip, DoctorOs.LINUX).diagnose(wifi, withPublicIp = false)
            d.verdict shouldBe Verdict.NOT_BINDABLE
        }

    @Test
    fun aBinderThatNeverAnswersMeansNotBindable() =
        runTest {
            val never =
                object : dev.anydm.engine.platform.NetworkBinder {
                    override suspend fun probe(network: NetworkInfo): BindStatus = awaitCancellation()
                }
            val d = NetworkDoctor(transport, never, probe, ip, DoctorOs.LINUX).diagnose(wifi, withPublicIp = false)
            d.verdict shouldBe Verdict.NOT_BINDABLE
        }

    @Test
    fun anOnlineProbeThatThrowsMeansOffline() =
        runTest {
            val throwing =
                object : OnlineProbe {
                    override fun isOnline(): Boolean = error("probe broke")
                }
            NetworkDoctor(transport, binder, throwing, ip, DoctorOs.LINUX).diagnoseAll(listOf(wifi), false).online shouldBe false
        }

    @Test
    fun equalAddressesOnABestEffortBindingAreNotCalledShared() =
        runTest {
            ips["en5"] = "203.0.113.7"
            binder.statuses["en5"] = BindStatus.BEST_EFFORT
            val report = doctor().diagnoseAll(listOf(wifi, phone), withPublicIp = true)
            report.diagnoses.map { it.verdict } shouldBe listOf(Verdict.WORKS, Verdict.WORKS)
            report.diagnoses.map { it.sharesWith } shouldBe listOf(emptyList(), emptyList())
            report.diagnoses.map { it.result(DoctorCheck.PUBLIC_IP) } shouldBe listOf(CheckResult.WARN, CheckResult.WARN)
            report.diagnoses[0]
                .findings
                .last()
                .detail shouldContain "may only share"
        }

    @Test
    fun publicAddressesAreComparedInTheirCanonicalForm() =
        runTest {
            ips["en0"] = "2001:DB8::1"
            ips["en5"] = "[2001:db8::1]"
            val report = doctor().diagnoseAll(listOf(wifi, phone), withPublicIp = true)
            report.diagnoses.map { it.verdict } shouldBe listOf(Verdict.SHARED, Verdict.SHARED)
        }

    @Test
    fun aLookupAnswerThatIsNotAnAddressIsAWarning() =
        runTest {
            ips["en0"] = "not an address"
            val d = doctor().diagnoseAll(listOf(wifi), withPublicIp = true).diagnoses.single()
            d.result(DoctorCheck.PUBLIC_IP) shouldBe CheckResult.WARN
            d.publicIp shouldBe null
        }
}
