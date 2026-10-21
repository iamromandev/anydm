package dev.anydm.engine.net

/** Whether this machine can reach the internet (NET-13). The monitor polls it every few seconds. */
fun interface OnlineProbe {
    /** True when this machine has a route to the internet; false on any failure. Cheap and non-blocking in practice. */
    fun isOnline(): Boolean

    companion object {
        /** Always online: until a platform can tell. */
        val Always: OnlineProbe = OnlineProbe { true }
    }
}
