package dev.anydm.engine.http

import dev.anydm.engine.net.DirectSocketFactory
import dev.anydm.engine.net.ProxyEndpoint
import dev.anydm.engine.net.ProxyFailure
import dev.anydm.engine.net.ProxyRoute
import dev.anydm.engine.net.ProxySocketFactory
import dev.anydm.engine.net.ProxyType
import dev.anydm.engine.net.ProxyUse
import dev.anydm.engine.net.currentProxyRoute
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.Credentials
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.ProtocolException
import java.net.Proxy
import java.net.SocketException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Connect in 15 s, wait at most 30 s for each read; redirects are [OkHttpTransport]'s, not OkHttp's. */
fun defaultClient(): OkHttpClient =
    OkHttpClient
        .Builder()
        // Direct means direct: the JVM's own proxy selector, which the system settings can fill, is never asked (NET-15).
        .proxy(Proxy.NO_PROXY)
        // A plain Socket() asks that selector again when it connects.
        .socketFactory(DirectSocketFactory)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

/**
 * [HttpTransport] over OkHttp. Follows up to [MAX_REDIRECTS] redirects itself, so it knows the final URL and the hop
 * count, and drops credentials when a redirect leaves the origin. Cancelling the coroutine cancels the call, also
 * while a body read is blocked. Each hop asks for its proxy route (NET-15, see ProxyScope): direct on [client], through
 * a proxy on a client of its own (its own connection pool, so no connection is shared between proxies), and never at
 * all when the route is blocked.
 */
class OkHttpTransport(
    private val client: OkHttpClient = defaultClient(),
) : HttpTransport {
    // A client and a connection pool for each proxy in use, the least recently used going first, so old logins don't pile up.
    private val proxied =
        object : LinkedHashMap<ProxyEndpoint, OkHttpClient>(8, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ProxyEndpoint, OkHttpClient>): Boolean {
                val full = size > MAX_PROXIED
                if (full) eldest.value.connectionPool.evictAll()
                return full
            }
        }

    private fun clientVia(endpoint: ProxyEndpoint): OkHttpClient =
        synchronized(proxied) {
            proxied.getOrPut(endpoint) { proxiedClient(endpoint) }
        }

    override suspend fun open(request: HttpRequest): HttpResponse {
        var url = request.url.toHttpUrlOrNull() ?: throw HttpIoException(HttpIoKind.BAD_URL, hostOf(request.url))
        var method = request.method
        var headers = request.headers
        var redirects = 0
        while (true) {
            val route = currentProxyRoute(url.toString(), ProxyUse.HTTP)
            val chosen =
                when (route) {
                    ProxyRoute.Direct -> client
                    is ProxyRoute.Via -> clientVia(route.endpoint)
                    is ProxyRoute.Blocked -> throw HttpIoException(HttpIoKind.PROXY, hostOf(url.toString()), detail = route.reason)
                }
            val call = chosen.newCall(okRequest(url, method, headers))
            val response = call.await(hostOf(url.toString()))
            // Only an HTTP proxy answers 407 itself; through SOCKS it comes from the server.
            if (route is ProxyRoute.Via && route.endpoint.type == ProxyType.HTTP && response.code == PROXY_AUTH_REQUIRED) {
                response.close()
                throw HttpIoException(HttpIoKind.PROXY, hostOf(url.toString()), detail = "the proxy refused the login")
            }
            val location = response.header("Location")?.let(url::resolve)
            if (response.code !in redirectCodes || location == null) {
                return HttpResponse(
                    status = response.code,
                    headers = Headers(response.headers.map { it.first to it.second }),
                    finalUrl = url.toString(),
                    redirects = redirects,
                    body = OkHttpBody(call, response, hostOf(url.toString())),
                )
            }
            response.close()
            if (++redirects > MAX_REDIRECTS) {
                throw HttpIoException(HttpIoKind.TOO_MANY_REDIRECTS, hostOf(url.toString()))
            }
            if (response.code == 303 && method != "HEAD") method = "GET"
            // A Digest answer names the old path in its `uri`; the new one challenges again (AuthTransport answers).
            headers =
                headers.filterNot {
                    it.first.equals("Authorization", ignoreCase = true) &&
                        it.second.startsWith("Digest ", ignoreCase = true)
                }
            if (!sameOrigin(url, location)) headers = headers.filterNot { it.first.lowercase() in credentialHeaders }
            url = location
        }
    }

    private fun proxiedClient(endpoint: ProxyEndpoint): OkHttpClient {
        val builder = client.newBuilder().connectionPool(ConnectionPool())
        return when (endpoint.type) {
            ProxyType.HTTP -> {
                builder
                    .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(endpoint.host, endpoint.port)))
                    .proxyAuthenticator { _, response ->
                        val username = endpoint.username
                        val basic = response.challenges().any { it.scheme.equals("Basic", ignoreCase = true) }
                        if (username == null || !basic || response.request.header("Proxy-Authorization") != null) {
                            null
                        } else {
                            response.request
                                .newBuilder()
                                .header("Proxy-Authorization", Credentials.basic(username, endpoint.password ?: "", Charsets.UTF_8))
                                .build()
                        }
                    }.build()
            }

            // Our own sockets do the SOCKS handshake and take the host name along; Dns hands the name through unresolved.
            ProxyType.SOCKS5 -> {
                builder
                    .proxy(Proxy.NO_PROXY)
                    .socketFactory(ProxySocketFactory(endpoint))
                    .dns(NamePassthrough)
                    .build()
            }

            ProxyType.HTTPS -> {
                error("an HTTPS proxy can't carry OkHttp's traffic; ProxyRoute.checkedFor blocks it")
            }
        }
    }

    /** A placeholder address that keeps the host name, so a proxy's socket sends the name and nothing resolves it here. */
    private object NamePassthrough : Dns {
        override fun lookup(hostname: String): List<InetAddress> = listOf(InetAddress.getByAddress(hostname, byteArrayOf(0, 0, 0, 0)))
    }

    override fun canonicalUrl(url: String): String = url.toHttpUrlOrNull()?.toString() ?: url

    private fun okRequest(
        url: HttpUrl,
        method: String,
        headers: List<Pair<String, String>>,
    ): Request =
        Request
            .Builder()
            .url(url)
            .method(method, null)
            // Unsafe only in name: a non-ASCII Digest user or realm goes out as the UTF-8 it was hashed as.
            .headers(
                okhttp3.Headers
                    .Builder()
                    .apply { headers.forEach { (name, value) -> addUnsafeNonAscii(name, value) } }
                    .build(),
            ).build()

    private suspend fun Call.await(host: String): Response =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { cancel() }
            enqueue(
                object : Callback {
                    override fun onResponse(
                        call: Call,
                        response: Response,
                    ) {
                        continuation.resume(response) { _, value, _ -> value.close() }
                    }

                    override fun onFailure(
                        call: Call,
                        e: IOException,
                    ) {
                        continuation.resumeWithException(httpIoExceptionOf(e, host))
                    }
                },
            )
        }

    private class OkHttpBody(
        private val call: Call,
        private val response: Response,
        private val host: String,
    ) : HttpBody {
        private val source = response.body.source()

        override suspend fun read(
            into: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            // Bytes already buffered need no thread; and close() is never called while a read is in flight.
            if (source.buffer.size > 0) return source.read(into, offset, length)
            return try {
                coroutineScope {
                    val done = AtomicBoolean(false)
                    // A blocked socket read doesn't notice cancellation; cancelling the call closes the socket under it.
                    val watcher =
                        launch(start = CoroutineStart.UNDISPATCHED) {
                            try {
                                awaitCancellation()
                            } finally {
                                if (!done.get()) call.cancel()
                            }
                        }
                    try {
                        runInterruptible(Dispatchers.IO) { source.read(into, offset, length) }
                    } finally {
                        done.set(true)
                        watcher.cancel()
                    }
                }
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                throw httpIoExceptionOf(e, host)
            }
        }

        override fun close() = response.close()
    }

    private companion object {
        const val MAX_REDIRECTS = 5
        const val PROXY_AUTH_REQUIRED = 407
        const val MAX_PROXIED = 16
        val redirectCodes = setOf(301, 302, 303, 307, 308)
        val credentialHeaders = setOf("authorization", "proxy-authorization", "cookie")

        fun sameOrigin(
            a: HttpUrl,
            b: HttpUrl,
        ) = a.scheme == b.scheme && a.host == b.host && a.port == b.port
    }
}

/**
 * What [e] means for a request to [host]: a rejected TLS handshake or certificate is TLS, a handshake the peer broke
 * off and any other TLS failure a broken connection; the message is never [e]'s own, which may hold the URL.
 */
internal fun httpIoExceptionOf(
    e: IOException,
    host: String,
): HttpIoException {
    val refusal = (sequenceOf(e) + e.causes()).filterIsInstance<ProxyFailure>().firstOrNull()
    if (refusal != null) return HttpIoException(HttpIoKind.PROXY, host, e, refusal.message)
    if (e.message == "Failed to authenticate with proxy") return HttpIoException(HttpIoKind.PROXY, host, e, "the proxy refused the login")
    val kind =
        when (e) {
            is SSLHandshakeException -> if (e.droppedMidway()) HttpIoKind.RESET else HttpIoKind.TLS
            is SSLPeerUnverifiedException -> HttpIoKind.TLS
            is SSLException -> if (e.causes().any { it is CertificateException }) HttpIoKind.TLS else HttpIoKind.RESET
            is ConnectException, is UnknownHostException, is NoRouteToHostException -> HttpIoKind.CONNECT
            is InterruptedIOException -> HttpIoKind.TIMEOUT
            is SocketException, is EOFException, is ProtocolException -> HttpIoKind.RESET
            else -> if (e.javaClass.simpleName == "StreamResetException") HttpIoKind.RESET else HttpIoKind.OTHER
        }
    return HttpIoException(kind, host, e)
}

/** A handshake the peer broke off (a reset or EOF under it, no certificate problem): a dropped connection. */
private fun Throwable.droppedMidway(): Boolean =
    causes().none { it is CertificateException } && causes().any { it is SocketException || it is EOFException }

private fun Throwable.causes(): Sequence<Throwable> = generateSequence(cause) { it.cause.takeUnless { c -> c === it } }
