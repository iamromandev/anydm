package dev.anydm.engine.platform

import dev.anydm.engine.net.BindStatus
import dev.anydm.engine.net.NetworkInfo

/** Tells whether connections can be tied to a network on this machine (BND-02). The sockets themselves come from BoundSocketFactories. */
fun interface NetworkBinder {
    /** Tries it on a throwaway socket: [BindStatus.ISOLATED], [BindStatus.BEST_EFFORT] or [BindStatus.UNSUPPORTED]. */
    suspend fun probe(network: NetworkInfo): BindStatus

    companion object {
        /** A machine that can bind nothing: bonding is never active. */
        val None: NetworkBinder = NetworkBinder { BindStatus.UNSUPPORTED }
    }
}
