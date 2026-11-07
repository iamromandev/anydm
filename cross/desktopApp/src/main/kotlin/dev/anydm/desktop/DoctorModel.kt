package dev.anydm.desktop

import dev.anydm.engine.net.CheckResult
import dev.anydm.engine.net.DoctorCheck
import dev.anydm.engine.net.DoctorReport
import dev.anydm.engine.net.DoctorText
import dev.anydm.engine.net.NetworkInfo
import dev.anydm.engine.net.Verdict
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the dialog tells the user before anything is sent. */
const val DOCTOR_NOTICE =
    "Run check sends requests through each network to outside servers: api.ipify.org (which public address the network " +
        "shows), 1.1.1.1 and 9.9.9.9 (can it reach the internet), and connectivitycheck.gstatic.com (can it look up names). " +
        "Those servers learn only the public address of that network. Nothing is sent until you press Run check."

/** Shown beside Copy report: the text holds addresses the user may not want to post. */
const val DOCTOR_COPY_NOTICE = "The report includes your public addresses."

/** How far the check got. */
sealed interface DoctorLoad {
    /** Nothing was asked yet: the button has not been pressed. */
    data object Idle : DoctorLoad

    data object Running : DoctorLoad

    data class Done(
        val report: DoctorReport,
    ) : DoctorLoad

    data class Failed(
        val message: String,
    ) : DoctorLoad
}

/** One line of a network's result. */
data class DoctorFindingRow(
    val check: DoctorCheck,
    val result: CheckResult,
    val text: String,
)

/** The result for one network: its shown name, the plain [answer], what was checked and what may fix it. */
data class DoctorRow(
    val key: String,
    val name: String,
    val answer: String,
    val good: Boolean,
    val findings: List<DoctorFindingRow>,
    val fixes: List<String>,
)

data class DoctorState(
    val load: DoctorLoad = DoctorLoad.Idle,
    val rows: List<DoctorRow> = emptyList(),
    /** The report was put on the clipboard. */
    val copied: Boolean = false,
    /** Why it could not be put there. */
    val copyError: String? = null,
) {
    val running: Boolean get() = load == DoctorLoad.Running
    val canCopy: Boolean get() = load is DoctorLoad.Done
}

/**
 * Network Doctor (#635): asks the engine to check the networks only when [run] is called, shows an answer for each (under the
 * name the user gave it), and puts the report on the clipboard through [copier], which answers a reason when it could not.
 */
class DoctorModel(
    private val source: EngineSource,
    private val looks: StateFlow<NetworkLooks>,
    private val scope: CoroutineScope,
    private val copier: (String) -> String?,
) {
    private val load = MutableStateFlow<DoctorLoad>(DoctorLoad.Idle)
    private val copy = MutableStateFlow<Pair<Boolean, String?>>(false to null)

    val state: StateFlow<DoctorState> =
        combine(load, looks, copy) { load, looks, copy ->
            DoctorState(load, (load as? DoctorLoad.Done)?.let { rowsOf(it.report, looks) }.orEmpty(), copy.first, copy.second)
        }.stateIn(scope, SharingStarted.Eagerly, DoctorState())

    /** Checks the networks now; ignored while a check runs. */
    fun run() {
        if (load.getAndUpdate { DoctorLoad.Running } == DoctorLoad.Running) return
        copy.value = false to null
        scope.launch {
            load.value =
                try {
                    DoctorLoad.Done(source.diagnose())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    DoctorLoad.Failed("The check could not run: ${e.message ?: e::class.simpleName}")
                }
        }
    }

    /** Puts the report text on the clipboard. */
    fun copy() {
        val done = load.value as? DoctorLoad.Done ?: return
        val error = copier(DoctorText.report(done.report, nameFn(looks.value)))
        copy.update { (error == null) to error }
    }

    private fun nameFn(looks: NetworkLooks): (NetworkInfo) -> String = { looks.nameOf(it.key, it.name) }

    private fun rowsOf(
        report: DoctorReport,
        looks: NetworkLooks,
    ): List<DoctorRow> {
        val name = nameFn(looks)
        val byKey = report.diagnoses.associate { it.network.key to name(it.network) }
        return report.diagnoses.map { d ->
            DoctorRow(
                key = d.network.key,
                name = name(d.network),
                answer = DoctorText.answer(d, name) { byKey[it] ?: it },
                good = d.verdict == Verdict.WORKS,
                findings = d.findings.map { DoctorFindingRow(it.check, it.result, it.detail) },
                fixes = DoctorText.fixes(d, report.os, name),
            )
        }
    }
}

/** Puts [text] on the system clipboard; the reason it could not, else null. */
fun copyToSystemClipboard(text: String): String? =
    try {
        java.awt.Toolkit
            .getDefaultToolkit()
            .systemClipboard
            .setContents(java.awt.datatransfer.StringSelection(text), null)
        null
    } catch (e: Exception) {
        "The report could not be copied: ${e.message ?: e::class.simpleName}"
    }
