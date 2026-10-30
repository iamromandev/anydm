package dev.anydm.engine.platform

/** Asks the OS whether a network is metered (NET-14). */
fun interface MeteredDetector {
    /** True/false for [network] (a NetworkIdentity key); null when it can't tell (no command, failure, unknown). */
    suspend fun isMetered(network: String): Boolean?

    companion object {
        val None: MeteredDetector = MeteredDetector { null }
    }
}
