package dev.anydm.engine.http

import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import javax.net.ssl.SSLException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Response headers have lowercase keys. Closing aborts the transfer if the body is not fully read. */
class HttpOpen(
    val status: Int,
    val headers: Map<String, List<String>>,
    val finalUrl: String,
    val body: InputStream,
) : Closeable {
    override fun close() = body.close()
}

/** [reason] is one of [Reasons]. */
class TransportException(
    val reason: String,
    cause: Throwable? = null,
) : IOException(reason, cause)

interface HttpTransport {
    /** Redirects are already followed. Statuses of 400 and up are returned, not thrown. */
    suspend fun open(
        url: String,
        method: String,
        headers: Map<String, String>,
    ): HttpOpen
}

/** [requestTimeoutMs] bounds the wait for the response headers (not the body): a server that never answers fails as a network error. */
class JdkTransport(
    private val client: HttpClient = defaultClient(),
    private val requestTimeoutMs: Long = DEFAULT_REQUEST_TIMEOUT_MS,
) : HttpTransport {
    override suspend fun open(
        url: String,
        method: String,
        headers: Map<String, String>,
    ): HttpOpen {
        var current = parse(url)
        repeat(MAX_REDIRECTS + 1) {
            val response = send(current, method, headers)
            val code = response.statusCode()
            if (code !in REDIRECTS) {
                val map =
                    response
                        .headers()
                        .map()
                        .entries
                        .associate { it.key.lowercase() to it.value }
                return HttpOpen(code, map, current.toString(), response.body())
            }
            val location = response.headers().firstValue("location").orElse(null)
            response.body().close()
            if (location == null) throw TransportException(Reasons.NETWORK)
            current =
                try {
                    parse(current.resolve(location).toString())
                } catch (e: IllegalArgumentException) {
                    throw TransportException(Reasons.NETWORK, e) // a Location that is not a valid URI
                }
        }
        throw TransportException(Reasons.NETWORK)
    }

    private fun parse(url: String): URI {
        val uri = runCatching { URI(url) }.getOrNull()
        if (uri == null || uri.scheme?.lowercase() !in setOf("http", "https") || uri.host == null) {
            throw TransportException(Reasons.NETWORK)
        }
        return uri
    }

    private suspend fun send(
        uri: URI,
        method: String,
        headers: Map<String, String>,
    ): HttpResponse<InputStream> {
        val request =
            try {
                HttpRequest
                    .newBuilder(uri)
                    .method(method, HttpRequest.BodyPublishers.noBody())
                    .timeout(Duration.ofMillis(requestTimeoutMs))
                    .header("User-Agent", "anydm")
                    .apply { headers.forEach { (k, v) -> header(k, v) } }
                    .build()
            } catch (e: IllegalArgumentException) {
                throw TransportException(Reasons.NETWORK, e)
            }
        return suspendCancellableCoroutine { cont ->
            val future = client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
            future.whenComplete { r, e ->
                when {
                    // A response that arrives after the cancellation has no reader: its connection must not stay open.
                    e == null -> cont.resume(r) { _, value, _ -> value.body().close() }

                    else -> cont.resumeWithException(map(e.cause ?: e))
                }
            }
            cont.invokeOnCancellation { future.cancel(true) }
        }
    }

    private fun map(e: Throwable): Throwable =
        when (e) {
            is SSLException -> TransportException(Reasons.CERT, e)
            is IOException -> TransportException(Reasons.NETWORK, e)
            is IllegalArgumentException -> TransportException(Reasons.NETWORK, e)
            else -> e
        }

    companion object {
        const val DEFAULT_REQUEST_TIMEOUT_MS = 30_000L

        fun defaultClient(): HttpClient =
            HttpClient
                .newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(15))
                .build()

        private const val MAX_REDIRECTS = 10
        private val REDIRECTS = setOf(301, 302, 303, 307, 308)
    }
}
