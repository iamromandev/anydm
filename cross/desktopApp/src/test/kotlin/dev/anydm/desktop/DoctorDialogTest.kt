package dev.anydm.desktop

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.anydm.engine.net.DoctorOs
import dev.anydm.engine.net.DoctorReport
import dev.anydm.engine.net.NetworkDiagnosis
import dev.anydm.engine.net.NetworkInfo
import dev.anydm.engine.net.Verdict
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
internal class DoctorDialogTest {
    private val wifi = NetworkInfo("en0", "Wi-Fi", true, listOf("10.0.0.1"), 1)
    private val phone = NetworkInfo("en5", "iPhone USB", true, listOf("172.20.10.2"), 5)

    private class Shown(
        val source: FakeSource,
        val copied: MutableList<String>,
        val closed: IntArray,
    )

    private fun ComposeUiTest.show(
        theme: ThemeChoice = ThemeChoice.DARK,
        block: ComposeUiTest.(Shown) -> Unit,
    ) {
        val source = FakeSource()
        source.report =
            DoctorReport(
                true,
                DoctorOs.MACOS,
                listOf(
                    NetworkDiagnosis(wifi, emptyList(), Verdict.SHARED, sharesWith = listOf("en5")),
                    NetworkDiagnosis(phone, emptyList(), Verdict.SHARED, sharesWith = listOf("en0")),
                ),
            )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val copied = mutableListOf<String>()
        val closed = intArrayOf(0)
        val visuals = NetworkVisuals(null)
        val looks = NetworkLooksModel(source, visuals, scope)
        val model =
            DoctorModel(source, looks.looks, scope) {
                copied += it
                null
            }
        setContent { PlexoTheme(theme) { DoctorDialog(model, WindowKeys()) { closed[0]++ } } }
        try {
            block(Shown(source, copied, closed))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `it says the public-IP check asks an outside server and runs only on the button`() =
        runComposeUiTest {
            show {
                onNodeWithTag("doctor-dialog").assertIsDisplayed()
                for (word in listOf("api.ipify.org", "1.1.1.1", "connectivitycheck.gstatic.com", "only the public address")) {
                    onNodeWithText(word, substring = true, ignoreCase = true).assertIsDisplayed()
                }
                onNodeWithText("Run check").assertIsDisplayed()
            }
        }

    @Test
    fun `nothing is asked of the engine until Run check is pressed`() =
        runComposeUiTest {
            show { s ->
                waitForIdle()
                assertTrue(s.source.calls.isEmpty())
                onNodeWithTag("doctor-copy").assertIsNotEnabled()
                onNodeWithTag("doctor-run").performClick()
                waitForIdle()
                assertEquals(listOf("diagnose"), s.source.calls)
            }
        }

    @Test
    fun `each network shows its plain answer after the check`() =
        runComposeUiTest {
            show {
                onNodeWithTag("doctor-run").performClick()
                waitForIdle()
                onNodeWithTag("doctor-row-en0").assertIsDisplayed()
                onNodeWithTag("doctor-row-en5").assertIsDisplayed()
                onNodeWithText("Wi-Fi probably shares one internet connection with iPhone USB", substring = true).assertIsDisplayed()
            }
        }

    @Test
    fun `Copy report puts the report text on the clipboard`() =
        runComposeUiTest {
            show { s ->
                onNodeWithTag("doctor-run").performClick()
                waitForIdle()
                onNodeWithTag("doctor-copy").assertIsEnabled().performClick()
                waitForIdle()
                assertTrue("Wi-Fi (en0)" in s.copied.single())
                onNodeWithText("Copied").assertIsDisplayed()
                onNodeWithText("includes your public addresses", substring = true).assertIsDisplayed()
            }
        }

    @Test
    fun `the title does not say this network, since all are checked`() =
        runComposeUiTest {
            show {
                onNodeWithText("this network", substring = true).assertDoesNotExist()
            }
        }

    @Test
    fun `Close and Escape close it`() =
        runComposeUiTest {
            show { s ->
                onNodeWithTag("doctor-close").performClick()
                assertEquals(1, s.closed[0])
                onNodeWithTag("doctor-dialog").performKeyInput { pressKey(Key.Escape) }
                assertEquals(2, s.closed[0])
            }
        }

    @Test
    fun `a failed check shows why`() =
        runComposeUiTest {
            show { s ->
                s.source.diagnoseThrows = IllegalStateException("the engine has stopped")
                onNodeWithTag("doctor-run").performClick()
                waitForIdle()
                onNodeWithText("The check could not run: the engine has stopped").assertIsDisplayed()
                onNodeWithTag("doctor-row-en0").assertDoesNotExist()
            }
        }

    @Test
    fun `it is shown in the light theme too`() =
        runComposeUiTest {
            show(ThemeChoice.LIGHT) {
                onNodeWithTag("doctor-dialog").assertIsDisplayed()
            }
        }
}
