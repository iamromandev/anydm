package dev.anydm.desktop

import dev.anydm.engine.net.BindStatus
import dev.anydm.engine.net.BondedNetwork
import dev.anydm.engine.net.BondingState
import dev.anydm.engine.queue.CommandResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class NetworksState(
    val message: String? = null,
)

fun bindingLabel(status: BindStatus): String =
    when (status) {
        BindStatus.UNKNOWN -> "Unknown"
        BindStatus.ISOLATED -> "Isolated"
        BindStatus.BEST_EFFORT -> "Best effort"
        BindStatus.UNSUPPORTED -> "Not usable"
    }

/** What a binding capability means for downloads on that network. */
fun bindingHelp(status: BindStatus): String =
    when (status) {
        BindStatus.UNKNOWN -> {
            "Not checked yet."
        }

        BindStatus.ISOLATED -> {
            "The system keeps this network's traffic on it."
        }

        BindStatus.BEST_EFFORT -> {
            "Only the source address is set, so without routing rules for it the traffic may still leave by the default route (Linux)."
        }

        BindStatus.UNSUPPORTED -> {
            "This network can't be used: nothing can be bound to it here, so it is listed but never used."
        }
    }

fun canSwitch(network: BondedNetwork): Boolean = network.status != BindStatus.UNSUPPORTED

fun bondingSummary(state: BondingState): String =
    when {
        state.networks.isEmpty() -> "No networks found."
        state.bonded -> "Downloads are shared across ${state.active.size} networks."
        else -> "Turn on two or more networks that are up to share downloads across them."
    }

/** The networks section (#609): which networks bonding uses. The engine's network list is live, so this only sends the switches. */
class NetworksModel(
    private val source: EngineSource,
    private val scope: CoroutineScope,
) {
    private val ui = MutableStateFlow(NetworksState())
    val state: StateFlow<NetworksState> = ui.asStateFlow()
    val networks: StateFlow<BondingState> get() = source.networks

    fun enable(
        key: String,
        on: Boolean,
    ) {
        scope.launch {
            try {
                when (val result = source.setNetworkEnabled(key, on)) {
                    is CommandResult.Done -> ui.update { it.copy(message = null) }
                    is CommandResult.Invalid -> ui.update { it.copy(message = result.reason) }
                    else -> ui.update { it.copy(message = result.toString()) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: IllegalStateException) {
                ui.update { it.copy(message = "The download engine has stopped, so nothing was changed.") }
            }
        }
    }
}
