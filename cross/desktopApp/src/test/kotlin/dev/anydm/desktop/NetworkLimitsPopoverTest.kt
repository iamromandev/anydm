package dev.anydm.desktop

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import dev.anydm.engine.net.Allowance
import dev.anydm.engine.net.AllowanceAction
import dev.anydm.engine.net.AllowancePeriod
import dev.anydm.engine.net.BondingState
import dev.anydm.engine.net.NetworkPolicy
import dev.anydm.engine.net.NetworkRules
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Rule
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class NetworkLimitsPopoverTest {
    @get:Rule
    val rule = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val source = FakeSource()
    private val mb = 1024L * 1024

    @AfterTest
    fun close() = scope.cancel()

    private fun show() {
        source.networks.value =
            BondingState(listOf(network("wifi", enabled = true, speed = 4.0 * mb), network("usb", enabled = true, speed = 2.0 * mb)))
        val model =
            AppModel(
                source,
                object : FirstRun {
                    override fun isDone() = true

                    override fun markDone() = Unit
                },
                scope,
                FakeFiles(),
            )
        model.start()
        rule.setContent { AnydmTheme(dark = false) { Shell(model) } }
        rule.waitForIdle()
    }

    private fun tag(name: String) = rule.onNodeWithTag(name, useUnmergedTree = true)

    private fun open() {
        tag("network-limits-usb").performClick()
        rule.waitForIdle()
    }

    private fun type(
        name: String,
        text: String,
    ) {
        tag(name).performTextClearance()
        tag(name).performTextInput(text)
    }

    @Test
    fun `the limits open on the current cap and budget`() {
        source.rules =
            NetworkRules(
                networks =
                    mapOf("usb" to NetworkPolicy(speedLimit = mb, allowance = Allowance(2048 * mb, AllowancePeriod.Daily, 80))),
            )
        show()
        open()
        tag("limits-cap").assertTextContains("1024")
        tag("limits-budget").assertTextContains("2048")
    }

    @Test
    fun `saving writes the cap and budget through the engine`() {
        show()
        open()
        type("limits-cap", "1024")
        type("limits-budget", "2048")
        tag("limits-save").performClick()
        rule.waitForIdle()
        assertEquals(listOf("setNetworkPolicy usb"), source.calls)
        val policy = source.rules.policyFor("usb")
        assertEquals(mb, policy.speedLimit)
        assertEquals(Allowance(2048 * mb, AllowancePeriod.Daily), policy.allowance)
        tag("limits-save").assertDoesNotExist()
        tag("strip-cap-usb").assertExists()
    }

    @Test
    fun `cancelling changes nothing`() {
        show()
        open()
        type("limits-cap", "1024")
        tag("limits-cancel").performClick()
        rule.waitForIdle()
        assertTrue(source.calls.isEmpty())
        tag("limits-save").assertDoesNotExist()
    }

    @Test
    fun `a budget that is not a number is refused inline and cannot be saved`() {
        show()
        open()
        type("limits-budget", "lots")
        tag("limits-budget-error").assertExists()
        tag("limits-save").assertIsNotEnabled()
        type("limits-budget", "5")
        tag("limits-save").assertIsEnabled()
    }

    @Test
    fun `the slider sets the cap and its last stop is unlimited`() {
        show()
        open()
        tag("limits-cap-slider").performSemanticsAction(SemanticsActions.SetProgress) { it(3f) }
        rule.waitForIdle()
        tag("limits-cap").assertTextContains("1024")
        tag("limits-cap-label").assertTextEquals("1.0 MB/s")
        tag("limits-cap-slider").performSemanticsAction(SemanticsActions.SetProgress) { it(CAP_STEPS_KBPS.size.toFloat()) }
        rule.waitForIdle()
        tag("limits-cap-label").assertTextEquals("Unlimited")
    }

    @Test
    fun `the at-limit action is chosen when there is a budget`() {
        show()
        open()
        tag("limits-atlimit-pause").assertDoesNotExist()
        type("limits-budget", "100")
        tag("limits-atlimit-slow").performClick()
        tag("limits-save").performClick()
        rule.waitForIdle()
        assertEquals(
            AllowanceAction.SLOW,
            source.rules
                .policyFor("usb")
                .allowance
                ?.atLimit,
        )
    }

    @Test
    fun `an allowance of another period is shown read only and kept on save`() {
        val monthly = Allowance(10L * 1024 * mb, AllowancePeriod.Monthly(1))
        source.rules = NetworkRules(networks = mapOf("usb" to NetworkPolicy(allowance = monthly)))
        show()
        open()
        tag("limits-budget-readonly").assertTextEquals("Monthly 10 GB, edit in Rules")
        tag("limits-budget").assertDoesNotExist()
        type("limits-cap", "512")
        tag("limits-save").performClick()
        rule.waitForIdle()
        assertEquals(monthly, source.rules.policyFor("usb").allowance)
        assertEquals(512 * 1024L, source.rules.policyFor("usb").speedLimit)
    }

    @Test
    fun `an engine refusal shows in the dialog`() {
        source.results["setNetworkPolicy usb"] =
            dev.anydm.engine.queue.CommandResult
                .Invalid("not allowed")
        show()
        open()
        type("limits-cap", "512")
        tag("limits-save").performClick()
        rule.waitForIdle()
        tag("limits-message").assertTextEquals("not allowed")
    }

    @Test
    fun `the slider says its value`() {
        show()
        open()
        tag("limits-cap-slider").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Unlimited"))
        type("limits-cap", "1024")
        tag("limits-cap-slider").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "1.0 MB/s"))
    }

    @Test
    fun `it closes without saving when the network disappears`() {
        show()
        open()
        source.networks.value = BondingState(listOf(network("wifi", enabled = true)))
        rule.waitForIdle()
        tag("limits-save").assertDoesNotExist()
        assertTrue(source.calls.isEmpty())
    }
}
