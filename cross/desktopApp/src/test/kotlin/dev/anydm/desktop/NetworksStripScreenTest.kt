package dev.anydm.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.anydm.engine.core.DownloadStatus.DOWNLOADING
import dev.anydm.engine.core.DownloadStatus.QUEUED
import dev.anydm.engine.net.BindStatus
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
class NetworksStripScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val source = FakeSource()
    private val prefs = MemoryPrefs()
    private val mb = 1024L * 1024

    @AfterTest
    fun close() = scope.cancel()

    private fun show(
        width: Dp? = null,
        height: Dp = 800.dp,
    ) {
        val model =
            AppModel(
                source,
                object : FirstRun {
                    override fun isDone() = true

                    override fun markDone() = Unit
                },
                scope,
                FakeFiles(),
                window = WindowModel(prefs, trayAvailable = false),
            )
        model.start()
        rule.setContent {
            AnydmTheme(dark = false) {
                if (width == null) Shell(model) else Box(Modifier.width(width).height(height).clipToBounds()) { Shell(model) }
            }
        }
        rule.waitForIdle()
    }

    private fun tag(name: String) = rule.onNodeWithTag(name, useUnmergedTree = true)

    private fun two() {
        source.networks.value =
            BondingState(listOf(network("wifi", enabled = true, speed = 4.0 * mb), network("usb", enabled = true, speed = 2.0 * mb)))
    }

    @Test
    fun `it lists both networks with the total and a bar split 67 and 33`() {
        two()
        show()
        tag("networks-strip").assertExists()
        tag("network-chip-wifi").assertExists()
        tag("network-chip-usb").assertExists()
        tag("strip-total").assertTextContains("6.0 MB/s", substring = true)
        tag("strip-speed-wifi").assertTextEquals("4.0 MB/s")
        tag("network-split-bar").assertContentDescriptionEquals("Network wifi 67%, Network usb 33%")
    }

    @Test
    fun `a network that is down is marked, shows no speed and leaves the split`() {
        source.networks.value =
            BondingState(
                listOf(
                    network("wifi", enabled = true, speed = 4.0 * mb),
                    network("usb", enabled = true, speed = 2.0 * mb),
                    network("eth", enabled = true, speed = 2.0 * mb),
                ),
            )
        show()
        tag("network-split-bar").assertContentDescriptionEquals("Network wifi 50%, Network usb 25%, Network eth 25%")
        source.networks.value =
            BondingState(
                listOf(
                    network("wifi", enabled = true, speed = 4.0 * mb),
                    network("usb", enabled = true, speed = 2.0 * mb, up = false),
                    network("eth", enabled = true, speed = 2.0 * mb),
                ),
            )
        rule.waitForIdle()
        tag("strip-state-usb").assertTextEquals("Down")
        tag("strip-speed-usb").assertTextEquals("–")
        tag("network-split-bar").assertContentDescriptionEquals("Network wifi 67%, Network eth 33%")
    }

    @Test
    fun `a speed not yet known is a dash and an idle machine shows an empty bar`() {
        source.networks.value = BondingState(listOf(network("wifi", enabled = true), network("usb", enabled = true)))
        show()
        tag("strip-speed-wifi").assertTextEquals("–")
        tag("strip-total").assertTextContains("0 B/s", substring = true)
        tag("network-split-bar").assertContentDescriptionEquals("No traffic")
    }

    @Test
    fun `one network still renders, with no split`() {
        source.networks.value = BondingState(listOf(network("wifi", enabled = true, speed = 1.0 * mb)))
        show()
        tag("network-chip-wifi").assertExists()
        tag("network-split-bar").assertDoesNotExist()
    }

    @Test
    fun `with no networks there is no strip`() {
        show()
        tag("networks-strip").assertDoesNotExist()
    }

    @Test
    fun `usage against the budget shows with the reset and over budget is flagged`() {
        two()
        source.rules = NetworkRules(networks = mapOf("usb" to NetworkPolicy(speedLimit = mb)))
        source.usage = mapOf("usb" to usage("usb", 1536 * mb, 2048 * mb), "wifi" to usage("wifi", 3 * mb, 2 * mb))
        show()
        tag("strip-usage-usb").assertTextContains("1.5 / 2 GB", substring = true)
        tag("strip-usage-usb").assertTextContains("resets", substring = true)
        tag("strip-usage-usb").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
        tag("strip-usage-wifi").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Over budget"))
        tag("strip-over-budget").assertExists()
        tag("strip-cap-usb").assertTextContains("1.0 MB/s", substring = true)
    }

    @Test
    fun `the over budget marker stays in the header while collapsed`() {
        two()
        source.usage = mapOf("wifi" to usage("wifi", 3 * mb, 2 * mb))
        prefs.stripCollapsed = true
        show()
        tag("strip-over-budget").assertExists()
        tag("network-chip-wifi").assertDoesNotExist()
    }

    @Test
    fun `the segments of the split bar are proportional to the speeds`() {
        two()
        show()
        val wifi = tag("network-split-wifi").getUnclippedBoundsInRoot()
        val usb = tag("network-split-usb").getUnclippedBoundsInRoot()
        val ratio = (wifi.right - wifi.left).value / (usb.right - usb.left).value
        assertTrue(ratio in 1.9f..2.1f, "ratio $ratio")
    }

    @Test
    fun `a network bonding can not use is not called bonded`() {
        source.networks.value = BondingState(listOf(network("tun0", BindStatus.UNSUPPORTED, enabled = true)))
        show()
        tag("strip-state-tun0").assertTextEquals("Not usable")
    }

    @Test
    fun `an unusable network that is off can not be switched on`() {
        source.networks.value = BondingState(listOf(network("tun0", BindStatus.UNSUPPORTED)))
        show()
        tag("strip-toggle-tun0").assertIsNotEnabled()
    }

    @Test
    fun `the controls say what they are for`() {
        two()
        show()
        tag("strip-toggle-usb").assertContentDescriptionEquals("Use Network usb")
        tag("network-limits-usb").assertContentDescriptionEquals("Limits for Network usb")
    }

    @Test
    fun `two networks over budget stay on one row`() {
        two()
        source.usage = mapOf("wifi" to usage("wifi", 3 * mb, 2 * mb), "usb" to usage("usb", 3 * mb, 2 * mb))
        show(width = 900.dp, height = 640.dp)
        val wifi = tag("network-chip-wifi").getUnclippedBoundsInRoot()
        val usb = tag("network-chip-usb").getUnclippedBoundsInRoot()
        assertEquals(wifi.top, usb.top)
    }

    @Test
    fun `three budgeted networks are all reachable`() {
        source.networks.value = BondingState(listOf("a", "b", "c").map { network(it, enabled = true, speed = 1.0 * mb) })
        source.usage = mapOf("a" to usage("a", 1 * mb, 2 * mb), "b" to usage("b", 1 * mb, 2 * mb), "c" to usage("c", 3 * mb, 2 * mb))
        show(width = 900.dp, height = 640.dp)
        tag("strip-chips").performScrollToNode(hasTestTag("network-chip-c"))
        tag("network-chip-c").assertIsDisplayed()
    }

    @Test
    fun `collapsing hides the networks and is kept`() {
        two()
        show()
        tag("networks-strip-toggle").performClick()
        rule.waitForIdle()
        tag("network-chip-wifi").assertDoesNotExist()
        tag("strip-total").assertExists()
        assertTrue(prefs.stripCollapsed)
    }

    @Test
    fun `it opens collapsed when it was left collapsed`() {
        two()
        prefs.stripCollapsed = true
        show()
        tag("network-chip-wifi").assertDoesNotExist()
        tag("networks-strip-toggle").performClick()
        rule.waitForIdle()
        tag("network-chip-wifi").assertExists()
    }

    @Test
    fun `a network is switched from its chip`() {
        two()
        show()
        tag("strip-toggle-usb").assertIsOn()
        tag("strip-toggle-usb").performClick()
        rule.waitForIdle()
        assertEquals(listOf("setNetworkEnabled usb false"), source.calls)
        tag("strip-toggle-usb").assertIsOff()
    }

    @Test
    fun `eight networks keep the list usable at the minimum window`() {
        source.networks.value = BondingState((1..8).map { network("n$it", enabled = true, speed = it * 1.0 * mb) })
        source.downloads.value = listOf(snapshot(1, DOWNLOADING), snapshot(2, QUEUED))
        show(width = 900.dp, height = 640.dp)
        tag("row-1").performClick()
        rule.waitForIdle()
        tag("strip-chips").performScrollToNode(hasTestTag("network-chip-n8"))
        tag("network-chip-n8").assertIsDisplayed()
        val strip = tag("networks-strip").getUnclippedBoundsInRoot()
        val chips = tag("strip-chips").getUnclippedBoundsInRoot()
        assertTrue(chips.top >= strip.top && chips.bottom <= strip.bottom, "chips $chips outside strip $strip")
        val list = tag("list").getUnclippedBoundsInRoot()
        assertTwoCardsFit(list.bottom - list.top)
    }

    // Font metrics change every height, so "room for a list" is counted in cards: two of the first row's height.
    private fun assertTwoCardsFit(listHeight: Dp) {
        val card = tag("row-1").getUnclippedBoundsInRoot().let { it.bottom - it.top }
        assertTrue(listHeight >= card * 2, "list $listHeight should hold two cards of $card")
    }

    @Test
    fun `the strip keeps one height however many networks there are`() {
        source.networks.value = BondingState((1..3).map { network("n$it", enabled = true, speed = 1.0 * mb) })
        show(width = 900.dp, height = 640.dp)
        val three = tag("networks-strip").getUnclippedBoundsInRoot().let { it.bottom - it.top }
        source.networks.value = BondingState((1..8).map { network("n$it", enabled = true, speed = 1.0 * mb) })
        rule.waitForIdle()
        assertEquals(three, tag("networks-strip").getUnclippedBoundsInRoot().let { it.bottom - it.top })
    }

    @Test
    fun `three networks with a selected row and three downloads leave two cards in the list`() {
        source.networks.value = BondingState(listOf("a", "b", "c").map { network(it, enabled = true, speed = 1.0 * mb) })
        source.usage = mapOf("a" to usage("a", 1 * mb, 2 * mb), "b" to usage("b", 3 * mb, 2 * mb))
        source.downloads.value = listOf(snapshot(1, DOWNLOADING), snapshot(2, QUEUED), snapshot(3, QUEUED))
        show(width = 900.dp, height = 640.dp)
        tag("row-1").performClick()
        rule.waitForIdle()
        val list = tag("list").getUnclippedBoundsInRoot()
        assertTwoCardsFit(list.bottom - list.top)
        tag("row-2").assertIsDisplayed()
    }
}
