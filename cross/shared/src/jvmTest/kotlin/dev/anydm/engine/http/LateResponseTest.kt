package dev.anydm.engine.http

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.Authenticator
import java.net.CookieHandler
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSession
import kotlin.test.Test
import kotlin.test.assertTrue

/** A client whose future ignores cancellation, as a real one does when the response is already on its way. */
private class LateClient : HttpClient() {
    val future =
        object : CompletableFuture<HttpResponse<InputStream>>() {
            override fun cancel(mayInterruptIfRunning: Boolean) = false
        }
    val requested = CompletableFuture<Unit>()

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any?> sendAsync(
        request: HttpRequest,
        handler: HttpResponse.BodyHandler<T>,
    ): CompletableFuture<HttpResponse<T>> {
        requested.complete(Unit)
        return future as CompletableFuture<HttpResponse<T>>
    }

    override fun <T : Any?> sendAsync(
        request: HttpRequest,
        handler: HttpResponse.BodyHandler<T>,
        pushPromiseHandler: HttpResponse.PushPromiseHandler<T>?,
    ) = sendAsync(request, handler)

    override fun <T : Any?> send(
        request: HttpRequest,
        handler: HttpResponse.BodyHandler<T>,
    ): HttpResponse<T> = throw UnsupportedOperationException()

    override fun cookieHandler(): Optional<CookieHandler> = Optional.empty()

    override fun connectTimeout(): Optional<Duration> = Optional.empty()

    override fun followRedirects(): Redirect = Redirect.NEVER

    override fun proxy(): Optional<ProxySelector> = Optional.empty()

    override fun sslContext(): SSLContext = SSLContext.getDefault()

    override fun sslParameters(): SSLParameters = SSLParameters()

    override fun authenticator(): Optional<Authenticator> = Optional.empty()

    override fun version(): Version = Version.HTTP_1_1

    override fun executor(): Optional<Executor> = Optional.empty()
}

private class Late(
    private val body: InputStream,
    private val req: HttpRequest,
) : HttpResponse<InputStream> {
    override fun statusCode() = 200

    override fun request() = req

    override fun previousResponse(): Optional<HttpResponse<InputStream>> = Optional.empty()

    override fun headers(): HttpHeaders = HttpHeaders.of(emptyMap()) { _, _ -> true }

    override fun body() = body

    override fun sslSession(): Optional<SSLSession> = Optional.empty()

    override fun uri(): URI = req.uri()

    override fun version() = HttpClient.Version.HTTP_1_1
}

class LateResponseTest {
    @Test
    fun aResponseThatArrivesAfterTheCancellationHasItsBodyClosed() {
        val client = LateClient()
        val closed = AtomicBoolean(false)
        val body =
            object : ByteArrayInputStream(ByteArray(0)) {
                override fun close() {
                    closed.set(true)
                }
            }
        runBlocking {
            val call = async(start = CoroutineStart.UNDISPATCHED) { JdkTransport(client).open("http://127.0.0.1:1/x", "GET", emptyMap()) }
            client.requested.get()
            call.cancelAndJoin()
            client.future.complete(Late(body, HttpRequest.newBuilder(URI("http://127.0.0.1:1/x")).build()))
        }
        assertTrue(closed.get(), "the late response's body was left open")
    }
}
