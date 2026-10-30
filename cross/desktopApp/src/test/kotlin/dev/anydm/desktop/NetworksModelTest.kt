package dev.anydm.desktop

import dev.anydm.engine.net.BindStatus
import dev.anydm.engine.net.BondedNetwork
import dev.anydm.engine.net.BondingState
import dev.anydm.engine.net.NetworkInfo
import dev.anydm.engine.queue.CommandResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal fun network(
    key: String,
    status: BindStatus = BindStatus.ISOLATED,
    enabled: Boolean = false,
    speed: Double? = null,
    faulted: Boolean = false,
    up: Boolean = true,
) = BondedNetwork(NetworkInfo(key, "Network $key", up, listOf("10.0.0.1")), enabled, status, speed, faulted)

class NetworksModelTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val source = FakeSource()
    private val model = NetworksModel(source, scope)

    @AfterTest
    fun close() = scope.cancel()

    @Test
    fun `a network switches on and off through the engine`() {
        source.networks.value = BondingState(listOf(network("en0"), network("en1")))
        model.enable("en0", true)
        assertEquals(listOf("setNetworkEnabled en0 true"), source.calls)
        assertTrue(
            source.networks.value.networks
                .first { it.info.key == "en0" }
                .enabled,
        )
    }

    @Test
    fun `a refused switch says why`() {
        source.results["setNetworkEnabled en0 true"] = CommandResult.Invalid("not a network name")
        model.enable("en0", true)
        assertEquals("not a network name", model.state.value.message)
    }

    @Test
    fun `a stopped engine is reported`() {
        source.refuseAll = true
        model.enable("en0", true)
        assertEquals("The download engine has stopped, so nothing was changed.", model.state.value.message)
    }

    @Test
    fun `each binding capability has words that say what it means`() {
        assertTrue(bindingHelp(BindStatus.ISOLATED).contains("keeps"))
        assertTrue(bindingHelp(BindStatus.BEST_EFFORT).contains("default route"))
        assertTrue(bindingHelp(BindStatus.UNSUPPORTED).contains("can't"))
        assertEquals("Not checked yet.", bindingHelp(BindStatus.UNKNOWN))
        assertEquals("Best effort", bindingLabel(BindStatus.BEST_EFFORT))
    }

    @Test
    fun `the summary says whether downloads are bonded`() {
        assertEquals("No networks found.", bondingSummary(BondingState(emptyList())))
        assertTrue(bondingSummary(BondingState(listOf(network("en0", enabled = true)))).contains("two or more"))
        val two = BondingState(listOf(network("en0", enabled = true), network("en1", enabled = true)))
        assertEquals("Downloads are shared across 2 networks.", bondingSummary(two))
    }

    @Test
    fun `a network that cannot be bound cannot be switched on`() {
        assertFalse(canSwitch(network("tun0", BindStatus.UNSUPPORTED)))
        assertTrue(canSwitch(network("en0", BindStatus.BEST_EFFORT)))
        assertNull(model.state.value.message)
    }
}
