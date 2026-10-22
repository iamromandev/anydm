package dev.anydm.engine.net

import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * A proxy on a local port for tests (NET-15): SOCKS5 or HTTP CONNECT, with an optional login. It sends each tunnel to
 * [target] whatever name was asked for, so a name nothing can resolve still works only if the proxy, not the client,
 * did the lookup; [requests] has the `host:port` each client asked for, [logins] the login each one offered.
 */
internal class FakeProxyServer(
    private val kind: Kind,
    private val login: Pair<String, String>? = null,
    private val target: InetSocketAddress,
    /** Serves the proxy over TLS with this context (an HTTPS proxy). */
    tls: javax.net.ssl.SSLContext? = null,
) : AutoCloseable {
    enum class Kind { SOCKS5, HTTP }

    private val server: ServerSocket =
        tls?.serverSocketFactory?.createServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress())
            ?: ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress())
    val port: Int get() = server.localPort
    val requests = CopyOnWriteArrayList<String>()
    val logins = CopyOnWriteArrayList<String>()
    val connections = CopyOnWriteArrayList<Socket>()

    init {
        thread(isDaemon = true, name = "fake-proxy-accept") {
            while (!server.isClosed) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                connections += client
                thread(isDaemon = true, name = "fake-proxy-conn") { runCatching { serve(client) }.also { runCatching { client.close() } } }
            }
        }
    }

    private fun serve(client: Socket) {
        val input = client.getInputStream()
        val output = client.getOutputStream()
        val destination =
            when (kind) {
                Kind.SOCKS5 -> socks5(input, output)
                Kind.HTTP -> http(input, output)
            } ?: return
        val upstream = Socket()
        val host = destination.substringBeforeLast(':')
        val port = destination.substringAfterLast(':').toInt()
        upstream.connect(if (host.all { it.isDigit() || it == '.' }) InetSocketAddress(host, port) else target, 5000)
        thread(
            isDaemon = true,
        ) { runCatching { input.copyTo(upstream.getOutputStream()) }.also { runCatching { upstream.shutdownOutput() } } }
        runCatching { upstream.getInputStream().copyTo(output) }
        runCatching { upstream.close() }
    }

    private fun socks5(
        input: InputStream,
        output: java.io.OutputStream,
    ): String? {
        require(input.read() == 5)
        val methods = ByteArray(input.read()).also { input.readNBytes(it, 0, it.size) }.map { it.toInt() }
        val method = if (login != null) 2 else 0
        if (method !in methods) {
            output.write(byteArrayOf(5, -1))
            return null
        }
        output.write(byteArrayOf(5, method.toByte()))
        if (login != null) {
            input.read()
            val user = String(input.readNBytes(input.read()))
            val password = String(input.readNBytes(input.read()))
            logins += "$user:$password"
            if (user to password != login) {
                output.write(byteArrayOf(1, 1))
                return null
            }
            output.write(byteArrayOf(1, 0))
        }
        val header = input.readNBytes(4)
        val host =
            when (header[3].toInt()) {
                1 -> input.readNBytes(4).joinToString(".") { (it.toInt() and 0xFF).toString() }
                4 -> "ipv6"
                else -> String(input.readNBytes(input.read()))
            }
        val port = (input.read() shl 8) or input.read()
        requests += "$host:$port"
        output.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
        return "$host:$port"
    }

    private fun http(
        input: InputStream,
        output: java.io.OutputStream,
    ): String? {
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) head.append(input.read().also { check(it >= 0) }.toChar())
        val lines = head.lines()
        val destination = lines.first().split(' ')[1]
        val auth = lines.firstOrNull { it.startsWith("Proxy-Authorization:", ignoreCase = true) }?.substringAfter(':')?.trim()
        if (auth != null) logins += String(Base64.getDecoder().decode(auth.removePrefix("Basic ")))
        if (login != null && auth != "Basic " + Base64.getEncoder().encodeToString("${login.first}:${login.second}".toByteArray())) {
            output.write(
                "HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Basic realm=\"x\"\r\nContent-Length: 0\r\n\r\n"
                    .toByteArray(),
            )
            return null
        }
        requests += destination
        output.write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray())
        return destination
    }

    override fun close() {
        runCatching { server.close() }
        connections.forEach { runCatching { it.close() } }
    }
}

/** Sends back what it is sent, for tests that need something at the end of a tunnel. */
internal class EchoServer : AutoCloseable {
    private val server = ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress())
    val address: InetSocketAddress get() = InetSocketAddress(server.inetAddress, server.localPort)

    init {
        thread(isDaemon = true, name = "echo-accept") {
            while (!server.isClosed) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                thread(
                    isDaemon = true,
                ) { runCatching { client.getInputStream().copyTo(client.getOutputStream()) }.also { runCatching { client.close() } } }
            }
        }
    }

    override fun close() {
        runCatching { server.close() }
    }
}
