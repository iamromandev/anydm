package dev.anydm.engine.net

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketAddress
import java.util.Base64
import javax.net.SocketFactory
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** The proxy answered, and refused: a wrong login, a forbidden address, a protocol it doesn't speak (NET-15). Not a network drop. */
class ProxyFailure(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/**
 * Sockets that reach their address through [endpoint] (NET-15): SOCKS5 with a username and password (RFC 1928, 1929) or
 * HTTP CONNECT with Basic authentication, the latter also over TLS for an HTTPS proxy. The address is sent to the proxy
 * as a name, so only the proxy looks it up. Written here because the JDK's SOCKS support takes its login from a
 * process-wide Authenticator, which two proxies cannot share.
 */
class ProxySocketFactory(
    private val endpoint: ProxyEndpoint,
    /** How an HTTPS proxy is reached; the JVM's default, replaceable in tests. */
    private val tls: SSLSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory,
) : SocketFactory() {
    override fun createSocket(): Socket = ProxiedSocket(endpoint, tls)

    override fun createSocket(
        host: String,
        port: Int,
    ): Socket = createSocket().also { it.connect(InetSocketAddress.createUnresolved(host, port)) }

    override fun createSocket(
        host: String,
        port: Int,
        localAddress: InetAddress,
        localPort: Int,
    ): Socket =
        createSocket()
            .also {
                it.bind(InetSocketAddress(localAddress, localPort))
            }.also { it.connect(InetSocketAddress.createUnresolved(host, port)) }

    override fun createSocket(
        address: InetAddress,
        port: Int,
    ): Socket = createSocket().also { it.connect(InetSocketAddress(address, port)) }

    override fun createSocket(
        address: InetAddress,
        port: Int,
        localAddress: InetAddress,
        localPort: Int,
    ): Socket =
        createSocket()
            .also {
                it.bind(InetSocketAddress(localAddress, localPort))
            }.also { it.connect(InetSocketAddress(address, port)) }
}

/**
 * Sockets that never use a proxy (NET-15). A plain `Socket()` asks the JVM's default ProxySelector, which the system
 * settings can fill; a Direct route has to mean direct, so it gets these.
 */
object DirectSocketFactory : SocketFactory() {
    override fun createSocket(): Socket = Socket(Proxy.NO_PROXY)

    override fun createSocket(
        host: String,
        port: Int,
    ): Socket = createSocket().also { it.connect(InetSocketAddress(host, port)) }

    override fun createSocket(
        host: String,
        port: Int,
        localAddress: InetAddress,
        localPort: Int,
    ): Socket =
        createSocket()
            .also { it.bind(InetSocketAddress(localAddress, localPort)) }
            .also { it.connect(InetSocketAddress(host, port)) }

    override fun createSocket(
        address: InetAddress,
        port: Int,
    ): Socket = createSocket().also { it.connect(InetSocketAddress(address, port)) }

    override fun createSocket(
        address: InetAddress,
        port: Int,
        localAddress: InetAddress,
        localPort: Int,
    ): Socket =
        createSocket()
            .also { it.bind(InetSocketAddress(localAddress, localPort)) }
            .also { it.connect(InetSocketAddress(address, port)) }
}

/**
 * An address for [host] that keeps the name and looks nothing up: a proxied socket sends the name to the proxy, which does
 * the lookup. An IP literal is its own address.
 */
fun namedAddress(host: String): InetAddress {
    val bare = host.removePrefix("[").removeSuffix("]")
    val literal = parseAddress(bare)
    // A literal keeps its own text as its name, so nothing asks DNS what it is called (sshj does, for its host key).
    return InetAddress.getByAddress(bare, literal ?: byteArrayOf(0, 0, 0, 0))
}

/** The proxy's refusal behind [e], if that is why it failed. */
fun proxyFailureOf(e: Throwable): ProxyFailure? =
    generateSequence(e) { it.cause.takeUnless { c -> c === it } }.take(8).filterIsInstance<ProxyFailure>().firstOrNull()

private class ProxiedSocket(
    private val endpoint: ProxyEndpoint,
    private val tls: SSLSocketFactory,
) : Socket(Proxy.NO_PROXY) {
    private var tunnelIn: InputStream? = null
    private var tunnelOut: OutputStream? = null

    // Once the tunnel stands, the far end is the destination, not the proxy: sshj keys its host check on the socket's
    // remote address, and Commons Net asks it for the server's address.
    private var farEnd: InetSocketAddress? = null

    override fun connect(endpoint: SocketAddress) = connect(endpoint, 0)

    override fun connect(
        destination: SocketAddress,
        timeout: Int,
    ) {
        val target = destination as? InetSocketAddress ?: throw IllegalArgumentException("unsupported address type: $destination")
        val previous = soTimeout
        // No limit would hang a thread on a proxy that accepts and never answers: the caller's timeout, else a default.
        val limit = if (timeout > 0) timeout else DEFAULT_TIMEOUT_MS
        super.connect(InetSocketAddress(endpoint.host, endpoint.port), limit)
        try {
            soTimeout = limit
            var input = super.getInputStream()
            var output = super.getOutputStream()
            if (endpoint.type == ProxyType.HTTPS) {
                val secured = tls.createSocket(this, endpoint.host, endpoint.port, false) as SSLSocket
                secured.sslParameters = secured.sslParameters.also { it.endpointIdentificationAlgorithm = "HTTPS" }
                secured.startHandshake()
                input = secured.inputStream
                output = secured.outputStream
            }
            val host = target.hostString
            when (endpoint.type) {
                ProxyType.SOCKS5 -> socks5(input, output, host, target.port)
                ProxyType.HTTP, ProxyType.HTTPS -> httpConnect(input, output, host, target.port)
            }
            tunnelIn = input
            tunnelOut = output
            farEnd = target
            soTimeout = previous
        } catch (e: IOException) {
            runCatching { close() }
            throw if (e is ProxyFailure) e else IOException("can't reach ${target.hostString} through the proxy: ${e.message}", e)
        }
    }

    override fun getInetAddress(): InetAddress? = farEnd?.let { namedAddress(it.hostString) } ?: super.getInetAddress()

    override fun getPort(): Int = farEnd?.port ?: super.getPort()

    override fun getRemoteSocketAddress(): SocketAddress? = farEnd ?: super.getRemoteSocketAddress()

    override fun getInputStream(): InputStream = tunnelIn ?: super.getInputStream()

    override fun getOutputStream(): OutputStream = tunnelOut ?: super.getOutputStream()

    private fun socks5(
        input: InputStream,
        output: OutputStream,
        host: String,
        port: Int,
    ) {
        val login = endpoint.username != null
        output.write(if (login) byteArrayOf(5, 2, 0, 2) else byteArrayOf(5, 1, 0))
        output.flush()
        if (input.readByte() != 5) throw ProxyFailure("${endpoint.host}:${endpoint.port} isn't a SOCKS5 proxy")
        when (val method = input.readByte()) {
            0 -> {
                Unit
            }

            2 -> {
                if (!login) throw ProxyFailure("the SOCKS5 proxy wants a login, and none is set")
                val user = endpoint.username!!.toByteArray()
                val password = (endpoint.password ?: "").toByteArray()
                if (user.size > 255 || password.size > 255) throw ProxyFailure("the proxy login is too long for SOCKS5")
                output.write(byteArrayOf(1, user.size.toByte()) + user + byteArrayOf(password.size.toByte()) + password)
                output.flush()
                input.readByte()
                if (input.readByte() != 0) throw ProxyFailure("the SOCKS5 proxy refused the login")
            }

            else -> {
                throw ProxyFailure(
                    if (method ==
                        0xFF
                    ) {
                        "the SOCKS5 proxy accepts none of our login methods"
                    } else {
                        "the SOCKS5 proxy chose method $method"
                    },
                )
            }
        }
        val request = ByteArrayOutputStream()
        request.write(byteArrayOf(5, 1, 0))
        val literal = parseAddress(host)
        when {
            literal != null && literal.size == 4 -> {
                request.write(byteArrayOf(1) + literal)
            }

            literal != null -> {
                request.write(byteArrayOf(4) + literal)
            }

            else -> {
                val name = host.toByteArray()
                if (name.size > 255) throw ProxyFailure("the host name is too long for SOCKS5")
                request.write(byteArrayOf(3, name.size.toByte()) + name)
            }
        }
        request.write(byteArrayOf((port shr 8).toByte(), port.toByte()))
        output.write(request.toByteArray())
        output.flush()
        if (input.readByte() != 5) throw ProxyFailure("the SOCKS5 proxy answered nonsense")
        val reply = input.readByte()
        input.readByte()
        val skip =
            when (input.readByte()) {
                1 -> 4
                4 -> 16
                3 -> input.readByte()
                else -> throw ProxyFailure("the SOCKS5 proxy answered nonsense")
            }
        input.readNBytes(skip + 2)
        if (reply != 0) throw ProxyFailure("the SOCKS5 proxy refused $host:$port: ${socksReason(reply)}")
    }

    private fun httpConnect(
        input: InputStream,
        output: OutputStream,
        host: String,
        port: Int,
    ) {
        // A host with a line break or a space would end the request line early.
        if (host.any { it <= ' ' }) throw ProxyFailure("not a host name")
        val authority = if (':' in host) "[$host]:$port" else "$host:$port"
        val request =
            buildString {
                append("CONNECT $authority HTTP/1.1\r\nHost: $authority\r\n")
                if (endpoint.username != null) {
                    val token = Base64.getEncoder().encodeToString("${endpoint.username}:${endpoint.password ?: ""}".toByteArray())
                    append("Proxy-Authorization: Basic $token\r\n")
                }
                append("\r\n")
            }
        output.write(request.toByteArray())
        output.flush()
        // One byte at a time: whatever follows the blank line belongs to the tunnel.
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) {
            if (head.length > MAX_HEAD) throw ProxyFailure("the proxy's answer is too long")
            head.append(input.readByte().toChar())
        }
        val status =
            head
                .lineSequence()
                .first()
                .split(' ', limit = 3)
                .getOrNull(1)
                ?.toIntOrNull()
        if (status == null) throw ProxyFailure("${endpoint.host}:${endpoint.port} isn't an HTTP proxy")
        if (status !in 200..299) throw ProxyFailure("the HTTP proxy refused $authority: $status")
    }

    private fun InputStream.readByte(): Int = read().also { if (it < 0) throw IOException("the proxy closed the connection") }

    private fun socksReason(code: Int): String =
        when (code) {
            1 -> "general failure"
            2 -> "not allowed"
            3 -> "network unreachable"
            4 -> "host unreachable"
            5 -> "connection refused"
            6 -> "TTL expired"
            7 -> "command not supported"
            8 -> "address type not supported"
            else -> "error $code"
        }

    private companion object {
        const val MAX_HEAD = 16 * 1024
        const val DEFAULT_TIMEOUT_MS = 30_000
    }
}
