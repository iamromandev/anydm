package dev.anydm.desktop

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import dev.anydm.engine.net.BondingState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.util.concurrent.ConcurrentHashMap

/**
 * How every network looks, the same on every screen: the swatch each one has (a person's pick, else its kind's hue, else a free
 * one) and the names people gave. Both are by network key. A network with no name given has no entry in [names]: its own name,
 * the OS's, is used.
 */
@Immutable
data class NetworkLooks(
    val colorIds: Map<String, String> = emptyMap(),
    val names: Map<String, String> = emptyMap(),
) {
    /** The theme's swatch of each network; a network this doesn't know has none, and a screen draws it in the accent. */
    fun swatches(colors: PlexoColors): Map<String, Swatch> {
        val byId = colors.swatches.associateBy { it.id }
        return colorIds.mapNotNull { (key, id) -> byId[id]?.let { key to it } }.toMap()
    }

    /** The name to show for [key]: the one a person gave, else [osName]. */
    fun nameOf(
        key: String,
        osName: String,
    ): String = names[key] ?: osName
}

val LocalNetworkLooks = compositionLocalOf { NetworkLooks() }

/**
 * Works out [NetworkLooks] from the networks the engine lists, the networks the downloads carried bytes over (one that is
 * gone keeps its color and kind for as long as the app runs) and what people chose in [visuals].
 */
class NetworkLooksModel(
    source: EngineSource,
    private val visuals: NetworkVisuals,
    scope: CoroutineScope,
) {
    private val kinds = ConcurrentHashMap<String, NetworkKind>()

    val looks: StateFlow<NetworkLooks> =
        combine(source.networks, source.downloads, visuals.prefs) { networks, downloads, prefs ->
            build(networks, downloads.flatMap { it.networkShare.keys }, prefs)
        }.stateIn(
            scope,
            SharingStarted.Eagerly,
            build(
                source.networks.value,
                source.downloads.value.flatMap {
                    it.networkShare.keys
                },
                visuals.prefs.value,
            ),
        )

    private fun build(
        networks: BondingState,
        carried: List<String>,
        prefs: Map<String, NetworkPref>,
    ): NetworkLooks {
        for (net in networks.networks) kinds[net.info.key] = kindOf(net.info)
        for (key in carried) kinds.putIfAbsent(key, NetworkKind.OTHER)
        val known = kinds.toMap()
        val pinned = prefs.mapNotNull { (key, pref) -> pref.color?.let { key to it } }.toMap()
        return NetworkLooks(
            colorIds = assignSwatchIds(known, pinned),
            names = prefs.mapNotNull { (key, pref) -> pref.name?.let { key to it } }.toMap(),
        )
    }
}
