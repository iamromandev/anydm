package dev.anydm.engine.platform

import dev.anydm.engine.net.NetworkInfo

/** Lists the networks the machine has (BND-01): Wi-Fi, Ethernet, a tethered phone. Loopback and tunnels are left out. */
fun interface NetworkLister {
    /** The networks now, up or down; empty when it can't tell. */
    suspend fun list(): List<NetworkInfo>

    companion object {
        val None: NetworkLister = NetworkLister { emptyList() }
    }
}
