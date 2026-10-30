package dev.anydm.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import dev.anydm.engine.net.BindStatus
import dev.anydm.engine.net.BondingState
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
internal class NetworksScreenTest : PanelScreenTest(Section.NETWORKS) {
    @Test
    fun `it lists the networks with what binding can do on each`() {
        source.networks.value =
            BondingState(
                listOf(
                    network("en0", BindStatus.ISOLATED, enabled = true, speed = 2_000_000.0),
                    network("eth1", BindStatus.BEST_EFFORT),
                    network("tun0", BindStatus.UNSUPPORTED),
                ),
            )
        show()
        tag("network-name-en0").assertTextContains("Network en0")
        tag("network-speed-en0").assertTextContains("/s", substring = true)
        tag("network-status-eth1").assertTextContains("Best effort", substring = true)
        tag("network-help-eth1").assertTextContains("default route", substring = true)
        rule.onNodeWithTag("network-en0").assertIsOn()
        rule.onNodeWithTag("network-eth1").assertIsOff()
        rule.onNodeWithTag("network-tun0").assertIsNotEnabled()
    }

    @Test
    fun `switching a network on reaches the engine and updates the summary`() {
        source.networks.value = BondingState(listOf(network("en0", enabled = true), network("en1")))
        show()
        tag("network-summary").assertTextContains("two or more", substring = true)
        click("network-en1")
        rule.waitForIdle()
        assertEquals(listOf("setNetworkEnabled en1 true"), source.calls)
        rule.onNodeWithTag("network-en1").assertIsOn()
        tag("network-summary").assertTextContains("shared across 2 networks", substring = true)
    }

    @Test
    fun `an unusable network that is on can still be switched off`() {
        source.networks.value = BondingState(listOf(network("tun0", BindStatus.UNSUPPORTED, enabled = true)))
        show()
        rule.onNodeWithTag("network-tun0").assertIsEnabled()
    }

    @Test
    fun `a network that failed a bound connection says so`() {
        source.networks.value = BondingState(listOf(network("en0", enabled = true, faulted = true)))
        show()
        tag("network-fault-en0").assertTextContains("left out", substring = true)
    }

    @Test
    fun `share by speed is shown as always on`() {
        show()
        tag("network-share").assertTextContains("always", substring = true)
    }

    @Test
    fun `an empty machine says so`() {
        show()
        tag("network-summary").assertTextContains("No networks found.")
    }
}
