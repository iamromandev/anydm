package dev.anydm.engine.net

import dev.anydm.engine.http.HttpIoException
import dev.anydm.engine.http.HttpIoKind
import dev.anydm.engine.http.HttpRequest
import dev.anydm.engine.http.HttpResponse
import dev.anydm.engine.http.HttpTransport
import dev.anydm.engine.platform.NetworkBinder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/** What the doctor looks at, per network. */
enum class DoctorCheck { BINDING, ADDRESS, DNS, PUBLIC_IP }

enum class CheckResult {
    PASS,

    /** Works, with a caveat. */
    WARN,
    FAIL,

    /** Not run: an earlier check made it pointless, or it was not asked for. */
    SKIPPED,
}

data class Finding(
    val check: DoctorCheck,
    val result: CheckResult,
    /** One plain line about it. */
    val detail: String,
)

/** The plain answer for one network, most serious first. */
enum class Verdict { DOWN, NOT_BINDABLE, NO_ROUTE, NO_DNS, SHARED, WORKS }

/** The operating system whose fixes are suggested. */
enum class DoctorOs { MACOS, WINDOWS, LINUX, OTHER }

/** The OS family for a JVM `os.name` value. */
fun doctorOsOf(osName: String): DoctorOs =
    when {
        osName.startsWith("Mac", ignoreCase = true) || osName.startsWith("Darwin", ignoreCase = true) -> DoctorOs.MACOS
        osName.startsWith("Windows", ignoreCase = true) -> DoctorOs.WINDOWS
        osName.startsWith("Linux", ignoreCase = true) -> DoctorOs.LINUX
        else -> DoctorOs.OTHER
    }

data class NetworkDiagnosis(
    val network: NetworkInfo,
    val findings: List<Finding>,
    val verdict: Verdict,
    /** The public address seen through this network, when it was asked for and answered. */
    val publicIp: String? = null,
    /** The other networks that leave by the same public address (keys). */
    val sharesWith: List<String> = emptyList(),
)

data class DoctorReport(
    /** The machine has a route to the internet by its default way. */
    val online: Boolean,
    val os: DoctorOs,
    val diagnoses: List<NetworkDiagnosis>,
)

/** Finds the public address seen through [network]; throws when it can't. It asks an outside server. */
fun interface PublicIpLookup {
    suspend fun lookup(network: NetworkInfo): String

    companion object {
        /** No lookup is possible. */
        val None: PublicIpLookup = PublicIpLookup { throw UnsupportedOperationException("no public address lookup") }
    }
}

/**
 * Checks why a network may not add speed (#635): can this system bind to it, can a request sent only through it reach the
 * internet by a literal address and by a name (DNS), and does it share one internet connection with another network. The
 * public-address lookup asks an outside server, so it runs only when [diagnose] is told to, never by itself.
 */
class NetworkDoctor(
    private val transport: HttpTransport,
    private val binder: NetworkBinder,
    private val online: OnlineProbe,
    private val publicIp: PublicIpLookup,
    private val os: DoctorOs,
) {
    /** Checks [network] alone; the public address is looked up only with [withPublicIp]. Sharing is judged by [diagnoseAll]. */
    suspend fun diagnose(
        network: NetworkInfo,
        withPublicIp: Boolean,
    ): NetworkDiagnosis {
        val findings = mutableListOf<Finding>()

        fun skipped(
            check: DoctorCheck,
            why: String,
        ) = findings.add(Finding(check, CheckResult.SKIPPED, why))
        if (!network.up) {
            skipped(DoctorCheck.BINDING, "Not checked: the network is not connected.")
            skipped(DoctorCheck.ADDRESS, "Not checked: the network is not connected.")
            skipped(DoctorCheck.DNS, "Not checked: the network is not connected.")
            skipped(DoctorCheck.PUBLIC_IP, NOT_ASKED)
            return NetworkDiagnosis(network, findings, Verdict.DOWN)
        }
        val status = bindStatus(network)
        findings +=
            when (status) {
                BindStatus.ISOLATED -> {
                    Finding(DoctorCheck.BINDING, CheckResult.PASS, "This system can send through this network alone.")
                }

                BindStatus.BEST_EFFORT -> {
                    Finding(
                        DoctorCheck.BINDING,
                        CheckResult.WARN,
                        "This system sets only the source address, so traffic may still leave by the default route.",
                    )
                }

                else -> {
                    Finding(DoctorCheck.BINDING, CheckResult.FAIL, "This system can't send through this network alone.")
                }
            }
        if (status != BindStatus.ISOLATED && status != BindStatus.BEST_EFFORT) {
            skipped(DoctorCheck.ADDRESS, "Not checked: nothing can be sent through this network.")
            skipped(DoctorCheck.DNS, "Not checked: nothing can be sent through this network.")
            skipped(DoctorCheck.PUBLIC_IP, NOT_ASKED)
            return NetworkDiagnosis(network, findings, Verdict.NOT_BINDABLE)
        }
        // The name check runs even when the literal address fails: names that work while addresses are blocked is not "no route".
        val byAddress = ADDRESS_URLS.any { reaches(network, it) }
        val byName = reaches(network, NAME_URL)
        findings +=
            if (byAddress) {
                Finding(DoctorCheck.ADDRESS, CheckResult.PASS, "A request to a plain address was answered (any answer counts).")
            } else if (byName) {
                Finding(
                    DoctorCheck.ADDRESS,
                    CheckResult.WARN,
                    "Plain addresses (1.1.1.1, 9.9.9.9) got no answer, but names work. Some networks block addresses.",
                )
            } else {
                Finding(DoctorCheck.ADDRESS, CheckResult.FAIL, "Plain addresses (1.1.1.1, 9.9.9.9) got no answer through this network.")
            }
        if (!byName) {
            findings += Finding(DoctorCheck.DNS, CheckResult.FAIL, "Names could not be looked up through this network.")
            skipped(DoctorCheck.PUBLIC_IP, NOT_ASKED)
            return NetworkDiagnosis(network, findings, if (byAddress) Verdict.NO_DNS else Verdict.NO_ROUTE)
        }
        findings += Finding(DoctorCheck.DNS, CheckResult.PASS, "Names are looked up.")
        if (!withPublicIp) {
            skipped(DoctorCheck.PUBLIC_IP, NOT_ASKED)
            return NetworkDiagnosis(network, findings, Verdict.WORKS)
        }
        val address = lookup(network)
        findings +=
            if (address != null) {
                Finding(DoctorCheck.PUBLIC_IP, CheckResult.PASS, "The outside world sees this network as $address.")
            } else {
                Finding(DoctorCheck.PUBLIC_IP, CheckResult.WARN, "The public address could not be found, so sharing is not known.")
            }
        return NetworkDiagnosis(network, findings, Verdict.WORKS, publicIp = address)
    }

    /**
     * Checks every network, and with [withPublicIp] marks the networks that leave by the same public address as sharing one
     * internet connection: such a pair can never add speed to each other.
     */
    suspend fun diagnoseAll(
        networks: List<NetworkInfo>,
        withPublicIp: Boolean,
    ): DoctorReport {
        val isOnline =
            try {
                online.isOnline()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
        val single = coroutineScope { networks.map { async { diagnose(it, withPublicIp) } }.awaitAll() }

        fun NetworkDiagnosis.bestEffort() = findings.any { it.check == DoctorCheck.BINDING && it.result == CheckResult.WARN }
        val diagnoses =
            single.map { d ->
                val address = d.publicIp ?: return@map d
                val same = single.filter { it !== d && it.publicIp == address }
                if (same.isEmpty()) return@map d
                if (d.bestEffort() || same.any { it.bestEffort() }) {
                    // Without a real binding the request may have left by the default route, so equal addresses prove nothing.
                    val hedged =
                        Finding(
                            DoctorCheck.PUBLIC_IP,
                            CheckResult.WARN,
                            "Seen as $address, the same as another network. This system can't send through one network alone, " +
                                "so they may only share the default route; sharing is not known.",
                        )
                    return@map d.copy(findings = d.findings.filter { it.check != DoctorCheck.PUBLIC_IP } + hedged)
                }
                d.copy(verdict = Verdict.SHARED, sharesWith = same.map { it.network.key })
            }
        return DoctorReport(isOnline, os, diagnoses)
    }

    private suspend fun bindStatus(network: NetworkInfo): BindStatus =
        try {
            withTimeoutOrNull(PROBE_TIMEOUT_MS) { binder.probe(network) } ?: BindStatus.UNSUPPORTED
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            BindStatus.UNSUPPORTED
        }

    // Any HTTP answer counts: the question is whether the network carries a request, not what the server says.
    private suspend fun reaches(
        network: NetworkInfo,
        url: String,
    ): Boolean =
        try {
            // A timeout ends only this block (withTimeoutOrNull answers null); real cancellation still propagates below.
            withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
                withNetwork(network) { transport.open(HttpRequest(url, headers = listOf("Range" to "bytes=0-0"))).body.close() }
                true
            } ?: false
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }

    private suspend fun lookup(network: NetworkInfo): String? =
        try {
            withTimeoutOrNull(LOOKUP_TIMEOUT_MS) { normalizeIp(publicIp.lookup(network)) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

    internal companion object {
        const val NOT_ASKED = "Not checked. It asks an outside server and runs only when you ask."

        /** Literal addresses, no name lookup, so they tell the route from DNS; a second one before giving up. */
        val ADDRESS_URLS = listOf("http://1.1.1.1/", "http://9.9.9.9/")

        /** How long one request, the binding probe or the address lookup may take before it counts as failed. */
        const val REQUEST_TIMEOUT_MS = 8_000L
        const val PROBE_TIMEOUT_MS = 8_000L
        const val LOOKUP_TIMEOUT_MS = 8_000L
        const val NAME_URL = "http://connectivitycheck.gstatic.com/generate_204"
    }
}

/** The doctor's transport when none is given: every request fails as a network that can't be used. */
internal object UnavailableTransport : HttpTransport {
    override suspend fun open(request: HttpRequest): HttpResponse =
        throw HttpIoException(HttpIoKind.BIND, "", detail = "no transport for the checks")
}
