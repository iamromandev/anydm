package dev.anydm.engine.net

import dev.anydm.engine.log.Log
import dev.anydm.engine.platform.NetworkBinder
import dev.anydm.engine.platform.NetworkLister
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import javax.net.SocketFactory

/**
 * The desktop's networks (BND-01) and the sockets tied to them (BND-02), over `java.net.NetworkInterface` and the native
 * options in [NativeBind].
 *
 * A network is listed if it has an address a connection can start from; loopback, point-to-point links (VPN and other
 * tunnels), the usual container and virtual-machine bridges and addresses that are link-local are left out.
 *
 * A socket is tied to its interface where the OS lets it ([BindStatus.ISOLATED]). On Linux the right to do so can be
 * missing; the socket is then given the network's address as its source ([BindStatus.BEST_EFFORT]), which only keeps the
 * traffic on that network when the host routes by source address. [probe] tells which of the two a network gets, and
 * [factoryFor] follows it. A network that gets neither is [BindStatus.UNSUPPORTED], and its factory fails every
 * socket with a [BindFailure]: a connection is never made on the default route in its place.
 */
class DesktopNetworks(
    private val log: Log = Log.None,
) : NetworkLister,
    NetworkBinder,
    BoundSocketFactories {
    private val statuses = ConcurrentHashMap<String, BindStatus>()
    private val linux = "linux" in System.getProperty("os.name").lowercase()

    override suspend fun list(): List<NetworkInfo> = withContext(Dispatchers.IO) { listBlocking() }

    private fun listBlocking(): List<NetworkInfo> =
        try {
            NetworkInterface
                .networkInterfaces()
                .toList()
                .filterNot { it.isLoopback || it.isPointToPoint || isVirtual(it.name) }
                .mapNotNull { nic ->
                    val addresses =
                        nic
                            .inetAddresses()
                            .toList()
                            .filter(::usable)
                            .map { it.hostAddress.substringBefore('%') }
                    if (addresses.isEmpty()) null else NetworkInfo(nic.name, nic.displayName ?: nic.name, nic.isUp, addresses, nic.index)
                }.sortedBy { it.key }
        } catch (_: Exception) {
            emptyList()
        }

    private fun usable(address: InetAddress): Boolean =
        !address.isLoopbackAddress && !address.isLinkLocalAddress && !address.isAnyLocalAddress

    private fun isVirtual(name: String): Boolean = VIRTUAL.any { name.startsWith(it, ignoreCase = true) }

    override suspend fun probe(network: NetworkInfo): BindStatus =
        withContext(Dispatchers.IO) {
            val status = probeBlocking(network)
            statuses[network.key] = status
            status
        }

    private fun probeBlocking(network: NetworkInfo): BindStatus {
        try {
            Socket(Proxy.NO_PROXY).use { socket ->
                NativeBind.bind(socket, network)
                // What the OS says the socket is tied to, not just that the call returned.
                if (NativeBind.boundTo(socket) == network.key) return BindStatus.ISOLATED
            }
        } catch (e: BindFailure) {
            // Said once per probe, so a missing --add-opens or a refused option is not a silent UNSUPPORTED.
            log.warn("net", e) { "network ${network.key} can't be tied by interface: ${e.message}" }
        }
        // Only Linux has the fallback: a source address does not pin the interface on macOS or Windows, where the interface
        // option is the only way, and a network that fails it is not used.
        if (!linux) return BindStatus.UNSUPPORTED
        val source = sourceOf(network) ?: return BindStatus.UNSUPPORTED
        return try {
            Socket(Proxy.NO_PROXY).use { it.bind(InetSocketAddress(source, 0)) }
            BindStatus.BEST_EFFORT
        } catch (_: Exception) {
            BindStatus.UNSUPPORTED
        }
    }

    // The source address of a network is only a stand-in for tying the interface on a system that has no such option,
    // so it is tried only where the interface option is refused; macOS and Windows have it, and never need one.
    private fun sourceOf(network: NetworkInfo): InetAddress? =
        network.addresses.mapNotNull { runCatching { InetAddress.getByName(it) }.getOrNull() }.let { all ->
            all.firstOrNull { it is Inet4Address } ?: all.firstOrNull()
        }

    override fun factoryFor(network: NetworkInfo): SocketFactory = BoundFactory(network)

    private inner class BoundFactory(
        private val network: NetworkInfo,
    ) : SocketFactory() {
        override fun createSocket(): Socket {
            val socket = Socket(Proxy.NO_PROXY)
            try {
                tie(socket)
            } catch (e: Throwable) {
                runCatching { socket.close() }
                throw e
            }
            return socket
        }

        // Not yet probed: tie by interface, and fail if that can't be done.
        private fun tie(socket: Socket) {
            when (statuses[network.key]) {
                BindStatus.BEST_EFFORT -> {
                    if (!linux) throw BindFailure("${network.key} has no interface binding on this system")
                    val source = sourceOf(network) ?: throw BindFailure("${network.key} has no address to bind to")
                    try {
                        socket.bind(InetSocketAddress(source, 0))
                    } catch (e: java.io.IOException) {
                        throw BindFailure("can't bind to ${network.key}: ${e.message}", e)
                    }
                }

                BindStatus.UNSUPPORTED -> {
                    throw BindFailure("${network.key} can't be bound to on this machine")
                }

                else -> {
                    NativeBind.bind(socket, network)
                }
            }
        }

        override fun createSocket(
            host: String,
            port: Int,
        ): Socket = createSocket().also { connected(it) { it.connect(InetSocketAddress(host, port)) } }

        override fun createSocket(
            host: String,
            port: Int,
            localAddress: InetAddress,
            localPort: Int,
        ): Socket =
            createSocket().also {
                connected(it) {
                    it.bind(InetSocketAddress(localAddress, localPort))
                    it.connect(InetSocketAddress(host, port))
                }
            }

        override fun createSocket(
            address: InetAddress,
            port: Int,
        ): Socket = createSocket().also { connected(it) { it.connect(InetSocketAddress(address, port)) } }

        override fun createSocket(
            address: InetAddress,
            port: Int,
            localAddress: InetAddress,
            localPort: Int,
        ): Socket =
            createSocket().also {
                connected(it) {
                    it.bind(InetSocketAddress(localAddress, localPort))
                    it.connect(InetSocketAddress(address, port))
                }
            }

        private inline fun connected(
            socket: Socket,
            connect: () -> Unit,
        ) {
            try {
                connect()
            } catch (e: Throwable) {
                runCatching { socket.close() }
                throw e
            }
        }
    }

    private companion object {
        /** Bridges and virtual adapters of container and virtual-machine tools, which are not a way out of the machine. */
        val VIRTUAL =
            listOf(
                "docker",
                "veth",
                "br-",
                "virbr",
                "vmnet",
                "vboxnet",
                "utun",
                "tun",
                "tap",
                "zt",
                "gif",
                "stf",
                "vEthernet",
                "anpi",
                "awdl",
                "llw",
                "ap",
            )
    }
}
