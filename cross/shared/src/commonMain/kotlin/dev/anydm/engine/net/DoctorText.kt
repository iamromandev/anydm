package dev.anydm.engine.net

/** Plain words for a [DoctorReport] (#635): an answer and known fixes per network, and a report to copy. */
object DoctorText {
    /** The plain answer for [diagnosis]; [name] gives the name to show for a network key. */
    fun answer(
        diagnosis: NetworkDiagnosis,
        name: (NetworkInfo) -> String,
        nameOfKey: (String) -> String = { it },
    ): String {
        val me = name(diagnosis.network)
        return when (diagnosis.verdict) {
            Verdict.DOWN -> {
                "$me is not connected."
            }

            Verdict.NOT_BINDABLE -> {
                "This system can't send through $me alone, so it can't add speed."
            }

            Verdict.NO_ROUTE -> {
                "$me has no way to the internet: a request sent only through it got no answer."
            }

            Verdict.NO_DNS -> {
                "$me reaches the internet but can't look up names (DNS)."
            }

            Verdict.SHARED -> {
                "$me probably shares one internet connection with ${diagnosis.sharesWith.joinToString(" and ") { nameOfKey(it) }} " +
                    "(they show the same public address), so a second network likely cannot add speed."
            }

            Verdict.WORKS -> {
                "$me works on its own."
            }
        }
    }

    /** Known fixes for [diagnosis] on [os]; empty when there is nothing to suggest. */
    fun fixes(
        diagnosis: NetworkDiagnosis,
        os: DoctorOs,
        name: (NetworkInfo) -> String,
    ): List<String> {
        val label = "${name(diagnosis.network)} ${diagnosis.network.name}".lowercase()
        val iphone = "iphone" in label
        val fixes = mutableListOf<String>()
        when (diagnosis.verdict) {
            Verdict.DOWN -> {
                fixes += "Connect it, or plug it in, and check again."
            }

            Verdict.NOT_BINDABLE -> {
                fixes += "Nothing to fix in the app: this system does not let an app choose a network."
                fixes += vpnHint(os)
            }

            Verdict.NO_ROUTE -> {
                fixes += "Check that the network has internet by itself, for example by turning the others off."
                fixes += vpnHint(os)
            }

            Verdict.NO_DNS -> {
                fixes += "Check the DNS servers of this network, or try another one such as 1.1.1.1 or 8.8.8.8."
                fixes += vpnHint(os)
            }

            Verdict.SHARED -> {
                fixes += "Connect the networks to different internet providers, for example one Wi-Fi and one phone."
            }

            Verdict.WORKS -> {
                if (diagnosis.findings.any { it.check == DoctorCheck.BINDING && it.result == CheckResult.WARN }) {
                    fixes += "Without routing rules for its address, traffic may still leave by the default route."
                }
                if (diagnosis.findings.any { it.check == DoctorCheck.ADDRESS && it.result == CheckResult.WARN }) {
                    fixes +=
                        "Names work but plain addresses are blocked. This is fine for most downloads; a firewall may be filtering addresses."
                }
            }
        }
        val broken = diagnosis.verdict in setOf(Verdict.NOT_BINDABLE, Verdict.NO_ROUTE, Verdict.NO_DNS)
        if (os == DoctorOs.MACOS && iphone && broken) {
            fixes += MACOS_PHONE
        }
        return fixes
    }

    /** The whole report as text to copy into a message; [name] gives each network's name. */
    fun report(
        report: DoctorReport,
        name: (NetworkInfo) -> String,
    ): String {
        val byKey = report.diagnoses.associate { it.network.key to name(it.network) }
        return buildString {
            appendLine("anydm Network Doctor")
            appendLine("System: ${osLabel(report.os)}")
            appendLine("Internet by the default route: ${if (report.online) "yes" else "no"}")
            for (d in report.diagnoses) {
                appendLine()
                appendLine("${name(d.network)} (${d.network.key})")
                appendLine(answer(d, name) { byKey[it] ?: it })
                for (f in d.findings) appendLine("  ${label(f.check)}: ${f.result.label} - ${f.detail}")
                for (fix in fixes(d, report.os, name)) appendLine("  Fix: $fix")
            }
        }.trimEnd()
    }

    private fun vpnHint(os: DoctorOs): String =
        when (os) {
            DoctorOs.MACOS -> "A VPN or a firewall (such as Little Snitch) can block a network: turn it off and check again."
            DoctorOs.WINDOWS -> "A VPN or Windows Defender Firewall can block a network: turn it off and check again."
            DoctorOs.LINUX -> "A VPN or a firewall (ufw, nftables) can block a network: turn it off and check again."
            DoctorOs.OTHER -> "A VPN or a firewall can block a network: turn it off and check again."
        }

    private fun label(check: DoctorCheck): String =
        when (check) {
            DoctorCheck.BINDING -> "Sending through it alone"
            DoctorCheck.ADDRESS -> "Internet by address"
            DoctorCheck.DNS -> "DNS"
            DoctorCheck.PUBLIC_IP -> "Public address"
        }

    private fun osLabel(os: DoctorOs): String =
        when (os) {
            DoctorOs.MACOS -> "macOS"
            DoctorOs.WINDOWS -> "Windows"
            DoctorOs.LINUX -> "Linux"
            DoctorOs.OTHER -> "other"
        }

    private val CheckResult.label: String
        get() =
            when (this) {
                CheckResult.PASS -> "ok"
                CheckResult.WARN -> "warning"
                CheckResult.FAIL -> "failed"
                CheckResult.SKIPPED -> "not checked"
            }

    const val MACOS_PHONE =
        "On macOS, open System Settings > Network, choose the iPhone USB service and turn off \"Disable unless needed\". " +
            "With it on, macOS keeps the link idle."
}
