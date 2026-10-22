package dev.anydm.engine.net

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.net.SocketFactory

/**
 * [BoundSocketFactories] for tests: sockets that stay on the loopback, counted per network, and networks that refuse to
 * bind.
 */
class FakeBoundSockets : BoundSocketFactories {
    /** Sockets made, per network key. */
    val made = ConcurrentHashMap<String, AtomicInteger>()

    /** Networks whose sockets fail to bind, as if the interface were gone. */
    val refusing: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun madeOn(key: String): Int = made[key]?.get() ?: 0

    override fun factoryFor(network: NetworkInfo): SocketFactory =
        object : SocketFactory() {
            override fun createSocket(): Socket {
                if (network.key in refusing) throw BindFailure("${network.key} is gone")
                made.getOrPut(network.key) { AtomicInteger() }.incrementAndGet()
                return Socket(Proxy.NO_PROXY)
            }

            override fun createSocket(
                host: String,
                port: Int,
            ): Socket = createSocket().also { it.connect(InetSocketAddress(host, port)) }

            override fun createSocket(
                host: String,
                port: Int,
                localAddress: InetAddress,
                localPort: Int,
            ): Socket = createSocket().also { it.connect(InetSocketAddress(host, port)) }

            override fun createSocket(
                address: InetAddress,
                port: Int,
            ): Socket = createSocket().also { it.connect(InetSocketAddress(address, port)) }

            override fun createSocket(
                address: InetAddress,
                port: Int,
                localAddress: InetAddress,
                localPort: Int,
            ): Socket = createSocket().also { it.connect(InetSocketAddress(address, port)) }
        }
}
