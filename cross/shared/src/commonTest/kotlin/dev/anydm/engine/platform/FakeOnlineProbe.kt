package dev.anydm.engine.platform

import dev.anydm.engine.net.OnlineProbe

/** An OnlineProbe the test flips; it throws [failure] when set. */
internal class FakeOnlineProbe(
    var online: Boolean = true,
) : OnlineProbe {
    var calls = 0
    var failure: Throwable? = null

    override fun isOnline(): Boolean {
        calls++
        failure?.let { throw it }
        return online
    }
}
