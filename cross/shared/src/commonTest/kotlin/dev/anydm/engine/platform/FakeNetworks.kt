package dev.anydm.engine.platform

import dev.anydm.engine.net.BindStatus
import dev.anydm.engine.net.NetworkInfo

/** A [NetworkLister] whose networks the test changes. */
class FakeNetworkLister(
    vararg networks: NetworkInfo,
) : NetworkLister {
    var networks: List<NetworkInfo> = networks.toList()
    var calls = 0

    override suspend fun list(): List<NetworkInfo> {
        calls++
        return networks
    }
}

/** A [NetworkBinder] that answers from [statuses] (ISOLATED when a network has none) and counts its probes per network. */
class FakeNetworkBinder : NetworkBinder {
    val statuses = mutableMapOf<String, BindStatus>()
    val probes = mutableMapOf<String, Int>()

    override suspend fun probe(network: NetworkInfo): BindStatus {
        probes[network.key] = (probes[network.key] ?: 0) + 1
        return statuses[network.key] ?: BindStatus.ISOLATED
    }
}

/** A network with one address, up unless said otherwise. */
fun network(
    key: String,
    up: Boolean = true,
    vararg addresses: String = arrayOf("10.0.0.1"),
): NetworkInfo = NetworkInfo(key, key, up, addresses.toList(), index = key.hashCode().and(0xff) + 1)
