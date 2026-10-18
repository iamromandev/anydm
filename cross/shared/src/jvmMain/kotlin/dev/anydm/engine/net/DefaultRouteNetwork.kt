package dev.anydm.engine.net

import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The network interface the default route leaves by (`en0`, `eth0`, `wlan0`), looked up at most every 10 seconds;
 * `"unknown"` offline or on any failure. A Wi-Fi change on the same interface looks the same: NET-13 tells them apart.
 */
class DefaultRouteNetwork internal constructor(
    private val clock: Clock,
    private val lookup: () -> String?,
) : NetworkIdentity {
    constructor() : this(Clock.System, ::defaultRouteInterface)

    private var cached: String? = null
    private var lookedAt: Instant? = null

    @Synchronized
    override fun current(): String {
        val now = clock.now()
        val last = lookedAt
        if (last == null || now - last >= 10.seconds) {
            cached = runCatching(lookup).getOrNull()?.takeIf { it.isNotBlank() } ?: UNKNOWN
            lookedAt = now
        }
        return cached ?: UNKNOWN
    }

    private companion object {
        const val UNKNOWN = "unknown"
    }
}

/** A UDP "connect" picks the default route's local address and sends nothing; its interface's name, or null. */
internal fun defaultRouteInterface(): String? =
    try {
        DatagramSocket().use { socket ->
            socket.connect(InetAddress.getByName("1.1.1.1"), 53)
            socket.localAddress?.let { NetworkInterface.getByInetAddress(it)?.name }
        }
    } catch (_: Exception) {
        null
    }
