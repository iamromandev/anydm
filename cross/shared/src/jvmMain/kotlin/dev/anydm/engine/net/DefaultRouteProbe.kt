package dev.anydm.engine.net

import java.net.InetAddress

/**
 * Online when a UDP "connect" to a well-known address picks a usable local address (NET-13). Nothing is sent. The
 * IPv4 target is tried first, then the IPv6 one; any exception is a failed try. It says "online" behind a captive
 * portal: only the route is checked.
 */
class DefaultRouteProbe internal constructor(
    private val localFor: (String) -> InetAddress?,
) : OnlineProbe {
    constructor() : this(::defaultRouteAddress)

    override fun isOnline(): Boolean = TARGETS.any { usable(it) }

    private fun usable(target: String): Boolean =
        try {
            localFor(target)?.let { !it.isAnyLocalAddress && !it.isLoopbackAddress } ?: false
        } catch (_: Exception) {
            false
        }

    private companion object {
        val TARGETS = listOf("1.1.1.1", "2606:4700:4700::1111")
    }
}
