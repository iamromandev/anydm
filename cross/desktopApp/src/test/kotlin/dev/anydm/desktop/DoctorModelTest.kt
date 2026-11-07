package dev.anydm.desktop

import dev.anydm.engine.net.CheckResult
import dev.anydm.engine.net.DoctorCheck
import dev.anydm.engine.net.DoctorOs
import dev.anydm.engine.net.DoctorReport
import dev.anydm.engine.net.Finding
import dev.anydm.engine.net.NetworkDiagnosis
import dev.anydm.engine.net.NetworkInfo
import dev.anydm.engine.net.Verdict
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
internal class DoctorModelTest {
    private val wifi = NetworkInfo("en0", "Wi-Fi", true, listOf("10.0.0.1"), 1)
    private val phone = NetworkInfo("en5", "iPhone USB", true, listOf("172.20.10.2"), 5)

    private fun report(vararg diagnoses: NetworkDiagnosis) =
        DoctorReport(online = true, os = DoctorOs.MACOS, diagnoses = diagnoses.toList())

    private fun working(network: NetworkInfo) = NetworkDiagnosis(network, emptyList(), Verdict.WORKS)

    private fun failing(network: NetworkInfo) =
        NetworkDiagnosis(
            network,
            listOf(Finding(DoctorCheck.ADDRESS, CheckResult.FAIL, "No answer.")),
            Verdict.NO_ROUTE,
        )

    private class Setup(
        val source: FakeSource,
        val visuals: NetworkVisuals,
        val model: DoctorModel,
        val copied: MutableList<String>,
    )

    private fun doctor(
        copier: (String) -> String? = { null },
        block: suspend TestScope.(Setup) -> Unit,
    ) = runTest(UnconfinedTestDispatcher()) {
        val source = FakeSource()
        val visuals = NetworkVisuals(null)
        val looks = NetworkLooksModel(source, visuals, backgroundScope)
        val copied = mutableListOf<String>()
        val model =
            DoctorModel(source, looks.looks, backgroundScope) { text ->
                copied += text
                copier(text)
            }
        block(Setup(source, visuals, model, copied))
    }

    @Test
    fun `nothing is checked until the button is pressed`() =
        doctor { s ->
            assertEquals(DoctorLoad.Idle, s.model.state.value.load)
            assertFalse("diagnose" in s.source.calls)
            assertFalse(s.model.state.value.canCopy)
        }

    @Test
    fun `run asks the engine and shows an answer for each network`() =
        doctor { s ->
            s.source.report = report(working(wifi), failing(phone))
            s.model.run()
            assertEquals(listOf("diagnose"), s.source.calls)
            val rows = s.model.state.value.rows
            assertEquals(listOf("en0", "en5"), rows.map { it.key })
            assertEquals("Wi-Fi works on its own.", rows[0].answer)
            assertTrue(rows[1].answer.startsWith("iPhone USB has no way to the internet"))
            assertTrue(rows[1].fixes.isNotEmpty())
            assertEquals(CheckResult.FAIL, rows[1].findings.single().result)
        }

    @Test
    fun `the state is Running while the engine checks and a second press is ignored`() =
        doctor { s ->
            val gate = CompletableDeferred<Unit>()
            s.source.diagnoseGate = gate
            s.model.run()
            assertEquals(DoctorLoad.Running, s.model.state.value.load)
            s.model.run()
            assertEquals(1, s.source.calls.count { it == "diagnose" })
            gate.complete(Unit)
            assertTrue(s.model.state.value.load is DoctorLoad.Done)
        }

    @Test
    fun `a failed check says so and can be run again`() =
        doctor { s ->
            s.source.diagnoseThrows = IllegalStateException("the engine has stopped")
            s.model.run()
            val load = s.model.state.value.load
            assertTrue(load is DoctorLoad.Failed && "the engine has stopped" in load.message)
            s.source.diagnoseThrows = null
            s.model.run()
            assertTrue(s.model.state.value.load is DoctorLoad.Done)
        }

    @Test
    fun `a name the user gave is shown in the answer`() =
        doctor { s ->
            s.visuals.rename("en0", "Home")
            s.source.report = report(working(wifi))
            s.model.run()
            assertEquals(
                "Home",
                s.model.state.value.rows
                    .single()
                    .name,
            )
            assertEquals(
                "Home works on its own.",
                s.model.state.value.rows
                    .single()
                    .answer,
            )
        }

    @Test
    fun `a rename after the check changes the names shown`() =
        doctor { s ->
            s.source.report = report(working(wifi))
            s.model.run()
            s.visuals.rename("en0", "Office")
            assertEquals(
                "Office",
                s.model.state.value.rows
                    .single()
                    .name,
            )
        }

    @Test
    fun `copy hands the report text with the shown names to the clipboard`() =
        doctor { s ->
            s.visuals.rename("en0", "Home")
            s.source.report = report(working(wifi), failing(phone))
            s.model.run()
            s.model.copy()
            val text = s.copied.single()
            assertTrue("Home (en0)" in text)
            assertTrue("iPhone USB has no way to the internet" in text)
            assertTrue(s.model.state.value.copied)
            assertNull(s.model.state.value.copyError)
        }

    @Test
    fun `copy does nothing before there is a report`() =
        doctor { s ->
            s.model.copy()
            assertTrue(s.copied.isEmpty())
            assertFalse(s.model.state.value.copied)
        }

    @Test
    fun `a clipboard that refuses is told and not marked copied`() =
        doctor(copier = { "the clipboard is busy" }) { s ->
            s.source.report = report(working(wifi))
            s.model.run()
            s.model.copy()
            assertFalse(s.model.state.value.copied)
            assertEquals("the clipboard is busy", s.model.state.value.copyError)
        }

    @Test
    fun `running again clears the copied mark`() =
        doctor { s ->
            s.source.report = report(working(wifi))
            s.model.run()
            s.model.copy()
            assertTrue(s.model.state.value.copied)
            s.source.diagnoseGate = CompletableDeferred()
            s.model.run()
            assertEquals(DoctorLoad.Running, s.model.state.value.load)
            assertFalse(s.model.state.value.copied)
        }

    @Test
    fun `dropping the model while it checks cancels the engine call`() =
        runTest(UnconfinedTestDispatcher()) {
            val source = FakeSource()
            val looks = NetworkLooksModel(source, NetworkVisuals(null), backgroundScope)
            val own = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
            val model = DoctorModel(source, looks.looks, own) { null }
            source.diagnoseGate = CompletableDeferred()
            model.run()
            assertEquals(DoctorLoad.Running, model.state.value.load)
            assertFalse(source.diagnoseCancelled)
            own.cancel()
            assertTrue(source.diagnoseCancelled)
            assertFalse(model.state.value.load is DoctorLoad.Failed)
        }
}
