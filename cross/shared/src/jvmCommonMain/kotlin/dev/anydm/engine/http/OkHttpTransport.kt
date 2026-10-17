package dev.anydm.engine.http

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
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.ProtocolException
import java.net.SocketException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Connect in 15 s, wait at most 30 s for each read; redirects are [OkHttpTransport]'s, not OkHttp's. */
fun defaultClient(): OkHttpClient =
    OkHttpClient
        .Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

/**
 * [HttpTransport] over OkHttp. Follows up to [MAX_REDIRECTS] redirects itself, so it knows the final URL and the hop
 * count, and drops credentials when a redirect leaves the origin. Cancelling the coroutine cancels the call, also
 * while a body read is blocked.
 */
class OkHttpTransport(
    private val client: OkHttpClient = defaultClient(),
) : HttpTransport {
    override suspend fun open(request: HttpRequest): HttpResponse {
        var url = request.url.toHttpUrlOrNull() ?: throw HttpIoException(HttpIoKind.OTHER, "not an HTTP URL: ${hostOf(request.url)}")
        var method = request.method
        var headers = request.headers
        var redirects = 0
        while (true) {
            val call = client.newCall(okRequest(url, method, headers))
            val response = call.await()
            val location = response.header("Location")?.let(url::resolve)
            if (response.code !in redirectCodes || location == null) {
                return HttpResponse(
                    status = response.code,
                    headers = Headers(response.headers.map { it.first to it.second }),
                    finalUrl = url.toString(),
                    redirects = redirects,
                    body = OkHttpBody(call, response),
                )
            }
            response.close()
            if (++redirects > MAX_REDIRECTS) {
                throw HttpIoException(HttpIoKind.TOO_MANY_REDIRECTS, "more than $MAX_REDIRECTS redirects from ${url.host}")
            }
            if (response.code == 303 && method != "HEAD") method = "GET"
            if (!sameOrigin(url, location)) headers = headers.filterNot { it.first.lowercase() in credentialHeaders }
            url = location
        }
    }

    private fun okRequest(
        url: HttpUrl,
        method: String,
        headers: List<Pair<String, String>>,
    ): Request =
        Request
            .Builder()
            .url(url)
            .method(method, null)
            .apply { headers.forEach { (name, value) -> addHeader(name, value) } }
            .build()

    private suspend fun Call.await(): Response =
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
                        continuation.resumeWithException(ioException(e))
                    }
                },
            )
        }

    private class OkHttpBody(
        private val call: Call,
        private val response: Response,
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
                throw ioException(e)
            }
        }

        override fun close() = response.close()
    }

    private companion object {
        const val MAX_REDIRECTS = 5
        val redirectCodes = setOf(301, 302, 303, 307, 308)
        val credentialHeaders = setOf("authorization", "proxy-authorization", "cookie")

        fun sameOrigin(
            a: HttpUrl,
            b: HttpUrl,
        ) = a.scheme == b.scheme && a.host == b.host && a.port == b.port

        fun ioException(e: IOException): HttpIoException {
            val kind =
                when (e) {
                    is ConnectException, is UnknownHostException, is NoRouteToHostException -> HttpIoKind.CONNECT
                    is InterruptedIOException -> HttpIoKind.TIMEOUT
                    is SocketException, is EOFException, is ProtocolException -> HttpIoKind.RESET
                    else -> if (e.javaClass.simpleName == "StreamResetException") HttpIoKind.RESET else HttpIoKind.OTHER
                }
            return HttpIoException(kind, e.message?.takeUnless { it.isBlank() } ?: kind.name.lowercase(), e)
        }
    }
}
