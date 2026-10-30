package dev.anydm.engine.net

/** Which network bytes arrive on now (NET-04), as a key traffic is counted under. Watching changes is NET-13's. */
fun interface NetworkIdentity {
    /** A key for the current network; `"unknown"` when it can't tell. */
    fun current(): String

    /** A reading that skips any cache; the monitor calls it on every tick (NET-13). */
    fun fresh(): String = current()

    companion object {
        /** Every byte under one network: until a platform tells them apart. */
        val Unknown: NetworkIdentity = NetworkIdentity { "unknown" }
    }
}
