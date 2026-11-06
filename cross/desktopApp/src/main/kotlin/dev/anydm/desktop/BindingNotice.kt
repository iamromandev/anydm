package dev.anydm.desktop

import dev.anydm.engine.net.BindStatus
import dev.anydm.engine.net.BondingState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Binding is unsupported when more than one network is up, every one of them has been probed and none could be tied to
 * (UNSUPPORTED). A network not probed yet (UNKNOWN) holds the answer back, and one that binds (ISOLATED, BEST_EFFORT)
 * means binding works on this system: another network failing alone is a fault of that network, not of the system.
 * Linux with only a source-address fallback reports BEST_EFFORT, so it does not show this notice.
 * macOS and Windows tie by interface, so they reach this only when the engine itself reports UNSUPPORTED.
 */
internal fun bindingUnsupported(state: BondingState): Boolean {
    val up = state.networks.filter { it.info.up }
    return up.size > 1 && up.all { it.status == BindStatus.UNSUPPORTED }
}

/** Whether to tell the user that only the default network can be used; once dismissed it stays away until the next launch. */
class BindingNotice(
    source: EngineSource,
    scope: CoroutineScope,
) {
    private val dismissed = MutableStateFlow(false)

    val visible: StateFlow<Boolean> =
        combine(source.networks, dismissed) { state, gone -> !gone && bindingUnsupported(state) }
            .stateIn(scope, SharingStarted.Eagerly, false)

    fun dismiss() {
        dismissed.value = true
    }
}
