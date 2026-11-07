package dev.anydm.desktop

import dev.anydm.engine.net.BondingState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/** One line of the networks menu. */
data class NetworkRow(
    val key: String,
    /** What to call it: the name a person gave, else [osName]. */
    val name: String,
    /** The name the OS gives it, shown as a tag beside the name. */
    val osName: String,
    val kind: NetworkKind,
    val colorId: String,
    /** Bytes per second it carries now; null when it is idle. */
    val speed: Long?,
    val up: Boolean,
    /** It failed a connection a moment ago and is left out for a while. */
    val faulted: Boolean = false,
    /** The user has it switched on for bonding. */
    val enabled: Boolean = true,
) {
    /** Switched on, and idle, down or failing: the row offers "Why isn't this network adding speed?". */
    val canDiagnose: Boolean get() = enabled && (!up || faulted || speed == null)
}

data class NetworksState(
    val open: Boolean = false,
    val rows: List<NetworkRow> = emptyList(),
)

/**
 * The networks menu: a row for each network the engine lists, whether the menu is open, and where a row's chevron and the
 * "Speed & data limits" link go ([onOpenLimits], a network key or null for the general page; the limits dialog is built apart),
 * and where the Network Doctor entry and the "why" links go ([onOpenDoctor]; also built apart).
 */
class NetworksModel(
    source: EngineSource,
    looks: StateFlow<NetworkLooks>,
    scope: CoroutineScope,
    private val onOpenDoctor: () -> Unit = {},
    private val onOpenLimits: (String?) -> Unit = {},
) {
    private val open = MutableStateFlow(false)

    val state: StateFlow<NetworksState> =
        combine(source.networks, looks, open) { networks, looks, open -> NetworksState(open, rowsOf(networks, looks)) }
            .stateIn(scope, SharingStarted.Eagerly, NetworksState(false, rowsOf(source.networks.value, looks.value)))

    fun open() = open.update { true }

    fun close() = open.update { false }

    fun toggle() = open.update { !it }

    /** Closes the menu and goes to the limits of [key], or the general limits for null. */
    fun openLimits(key: String?) {
        close()
        onOpenLimits(key)
    }

    /** Closes the menu and opens Network Doctor; it checks nothing until its own button is pressed. */
    fun openDoctor() {
        close()
        onOpenDoctor()
    }

    private fun rowsOf(
        networks: BondingState,
        looks: NetworkLooks,
    ): List<NetworkRow> =
        networks.networks.sortedBy { it.info.key }.map { net ->
            val key = net.info.key
            NetworkRow(
                key = key,
                name = looks.nameOf(key, net.info.name),
                osName = net.info.name,
                kind = kindOf(net.info),
                colorId = looks.colorIds[key] ?: SWATCH_IDS.first(),
                speed = net.speed?.toLong()?.takeIf { it > 0 },
                up = net.info.up,
                faulted = net.faulted,
                enabled = net.enabled,
            )
        }
}

/** What the rename and color popover holds while it is open: a draft, and why it can't be saved yet. */
data class NetworkEdit(
    val key: String,
    /** Which part of the screen opened it (the menu, a chip, the table): only that part draws it. */
    val anchor: String,
    val osName: String,
    val name: String,
    val colorId: String,
    /** The person touched a swatch: whatever it shows when saved is their pick, even if it is the one it started on. */
    val colorTouched: Boolean = false,
    val error: String? = null,
)

/**
 * Renames and recolors a network. The popover is a draft that [save] writes (Done, Enter or a click away) and [cancel] throws
 * away (Escape). One popover is open at a time, for whichever part of the screen opened it.
 */
class NetworkEditor(
    private val visuals: NetworkVisuals,
    private val looks: StateFlow<NetworkLooks>,
) {
    private val current = MutableStateFlow<NetworkEdit?>(null)
    val editing: StateFlow<NetworkEdit?> = current.asStateFlow()

    fun start(
        key: String,
        anchor: String,
        osName: String,
    ) {
        val now = looks.value
        current.value =
            NetworkEdit(key, anchor, osName, name = now.nameOf(key, osName), colorId = now.colorIds[key] ?: SWATCH_IDS.first())
    }

    fun setName(raw: String) = current.update { it?.copy(name = raw.take(MAX_NETWORK_NAME), error = null) }

    fun setColor(id: String) = current.update { it?.copy(colorId = id, colorTouched = true, error = null) }

    /** Writes the draft and closes; false (and open) when the name is refused or could not be written. */
    fun save(): Boolean {
        val edit = current.value ?: return true
        val now = looks.value
        var failure: String? = null
        if (edit.name.trim() != now.nameOf(edit.key, edit.osName)) {
            failure = reason(visuals.rename(edit.key, edit.name))
        }
        // A color nobody touched is left as it is (automatic, or the pin it has), so a plugged-in network can still move it.
        if (failure == null && edit.colorTouched) {
            failure = reason(visuals.pick(edit.key, edit.colorId))
        }
        if (failure != null) {
            current.update { it?.copy(error = failure) }
            return false
        }
        current.value = null
        return true
    }

    fun cancel() {
        current.value = null
    }

    private fun reason(result: VisualsResult): String? =
        when (result) {
            VisualsResult.Saved -> null
            is VisualsResult.Invalid -> result.reason
            is VisualsResult.NotSaved -> result.reason
        }
}
