package dev.anydm.engine.http

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import java.io.EOFException
import java.net.SocketException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

class OkHttpTransportTest {
    private val server = FaultServer()
    private val transport = OkHttpTransport()
    private val file = ByteArray(5000) { (it % 251).toByte() }

    @AfterTest
    fun close() = server.close()

    private suspend fun get(
        path: String,
        vararg headers: Pair<String, String>,
    ) = transport.open(HttpRequest(server.url(path), headers = headers.toList()))

    @Test
    fun rangesAreServed() =
        runBlocking<Unit> {
            server.serve("/a.bin", file)
            val response = get("/a.bin", "Range" to "bytes=10-19")
            response.status shouldBe 206
            response.headers["Content-Range"] shouldBe "bytes 10-19/5000"
            response.body.readAll() shouldBe file.copyOfRange(10, 20)
        }

    @Test
    fun ignoredRangesGiveTheWholeFile() =
        runBlocking<Unit> {
            server.serve("/a.bin", file)
            server.fault("/a.bin", Fault.IgnoreRanges)
            val response = get("/a.bin", "Range" to "bytes=10-19")
            response.status shouldBe 200
            response.body.readAll() shouldBe file
        }

    @Test
    fun fiveRedirectsAreFollowed() =
        runBlocking<Unit> {
            server.serve("/a.bin", file)
            server.fault("/a.bin", Fault.Redirects(5))
            val response = get("/a.bin")
            response.status shouldBe 200
            response.redirects shouldBe 5
            response.finalUrl shouldBe server.url("/__hop/5/a.bin")
            response.body.readAll() shouldBe file
        }

    @Test
    fun aSixthRedirectFails() =
        runBlocking<Unit> {
            server.serve("/a.bin", file)
            server.fault("/a.bin", Fault.Redirects(6))
            val e = shouldThrow<HttpIoException> { get("/a.bin") }
            e.kind shouldBe HttpIoKind.TOO_MANY_REDIRECTS
            e.host shouldBe hostOf(server.url("/"))
        }

    @Test
    fun aRelativeRedirectIsResolved() =
        runBlocking<Unit> {
            server.serve("/files/a.bin", file)
            server.fault("/files/a.bin", Fault.Redirects(2))
            val response = get("/files/a.bin")
            response.status shouldBe 200
            response.finalUrl shouldBe server.url("/__hop/2/files/a.bin")
            server.requests.map { it.url.encodedPath } shouldBe listOf("/files/a.bin", "/__hop/1/files/a.bin", "/__hop/2/files/a.bin")
        }

    @Test
    fun credentialsStayOnTheirOrigin() =
        runBlocking<Unit> {
            MockWebServer().use { other ->
                other.start()
                other.enqueue(MockResponse.Builder().body("x").build())
                MockWebServer().use { first ->
                    first.start()
                    first.enqueue(
                        MockResponse
                            .Builder()
                            .code(302)
                            .addHeader("Location", other.url("/x"))
                            .build(),
                    )
                    val headers = listOf("Cookie" to "a=1", "Authorization" to "Bearer t", "X-A" to "1")
                    transport.open(HttpRequest(first.url("/").toString(), headers = headers)).body.close()
                    first.takeRequest().headers["Cookie"] shouldBe "a=1"
                    val moved = other.takeRequest().headers
                    moved["Cookie"].shouldBeNull()
                    moved["Authorization"].shouldBeNull()
                    moved.names() shouldContain "X-A"
                }
            }
            server.serve("/a.bin", file)
            server.fault("/a.bin", Fault.Redirects(2))
            get("/a.bin", "Cookie" to "a=1").body.close()
            server.requests.last().headers["Cookie"] shouldBe "a=1"
        }

    @Test
    fun cancellingCancelsTheCall() =
        runBlocking<Unit> {
            val client = defaultClient()
            server.serve("/slow.bin", ByteArray(64 * 1024))
            server.fault("/slow.bin", Fault.Throttle(1024))
            val reading = CompletableDeferred<Unit>()
            val job =
                launch(Dispatchers.Default) {
                    val body = OkHttpTransport(client).open(HttpRequest(server.url("/slow.bin"))).body
                    val buffer = ByteArray(8192)
                    body.read(buffer)
                    reading.complete(Unit)
                    while (body.read(buffer) >= 0) Unit
                }
            reading.await()
            val took = measureTime { job.cancelAndJoin() }
            (took < 500.milliseconds) shouldBe true
            var waited = 0
            while (client.connectionPool.connectionCount() > 0 && waited < 2_000) {
                delay(20)
                waited += 20
            }
            client.connectionPool.connectionCount() shouldBe 0
        }

    @Test
    fun aDropMidBodyIsAReset() =
        runBlocking<Unit> {
            server.serve("/d.bin", file)
            server.fault("/d.bin", Fault.DropAfter(100))
            val response = get("/d.bin")
            response.status shouldBe 200
            val e = shouldThrow<HttpIoException> { response.body.readAll() }
            e.kind shouldBe HttpIoKind.RESET
            e.host shouldBe hostOf(server.url("/"))
        }

    // Final review: a read on a closed body never returned, and a cancelled read could still be using the body.

    @Test
    fun readingAClosedBodyFails() =
        runBlocking<Unit> {
            server.serve("/a.bin", file)
            val body = get("/a.bin").body
            body.close()
            withTimeout(5.seconds) { shouldThrow<IllegalStateException> { body.read(ByteArray(10)) } }
        }

    @Test
    fun aBadRangeFailsInsteadOfHanging() =
        runBlocking<Unit> {
            server.serve("/a.bin", file)
            val body = get("/a.bin").body
            withTimeout(5.seconds) { shouldThrow<IndexOutOfBoundsException> { body.read(ByteArray(10), 0, 20) } }
            body.close()
        }

    @Test
    fun aRefusedConnectionIsAConnectFailure() =
        runBlocking<Unit> {
            val closed = FaultServer().apply { close() }
            val e = shouldThrow<HttpIoException> { transport.open(HttpRequest(closed.url("/a.bin"))) }
            e.kind shouldBe HttpIoKind.CONNECT
            e.host shouldBe hostOf(closed.url("/"))
        }

    @Test
    fun tlsFailuresAreTls() {
        httpIoExceptionOf(SSLHandshakeException("bad cert for secret.example/x"), "h").kind shouldBe HttpIoKind.TLS
        httpIoExceptionOf(SSLPeerUnverifiedException("x"), "h").kind shouldBe HttpIoKind.TLS
        httpIoExceptionOf(SSLException("x", CertificateException("y")), "h").kind shouldBe HttpIoKind.TLS
        httpIoExceptionOf(SSLException("connection reset"), "h").kind shouldBe HttpIoKind.RESET
        httpIoExceptionOf(SSLHandshakeException("bad cert for secret.example/x"), "h").message shouldBe "TLS handshake failed (h)"
    }

    @Test
    fun aNonHttpUrlIsBadUrl() =
        runBlocking<Unit> {
            val e = shouldThrow<HttpIoException> { transport.open(HttpRequest("ftp://u:p@h/f")) }
            e.kind shouldBe HttpIoKind.BAD_URL
            e.host shouldBe "h"
        }

    // Final review

    @Test
    fun aResetDuringTheHandshakeIsAReset() {
        httpIoExceptionOf(SSLHandshakeException("Remote host terminated the handshake").apply { initCause(EOFException()) }, "h")
            .kind shouldBe HttpIoKind.RESET
        httpIoExceptionOf(SSLHandshakeException("x").apply { initCause(SocketException("reset")) }, "h").kind shouldBe HttpIoKind.RESET
    }

    @Test
    fun urlsAreCanonicalAsSent() {
        hostOf(transport.canonicalUrl("https://evil.example\\@good.example/f")) shouldBe "evil.example"
        transport.canonicalUrl("https://h/a b/../c.zip?q=O'B") shouldBe "https://h/c.zip?q=O%27B"
        transport.canonicalUrl("ftp://h/f") shouldBe "ftp://h/f"
    }

    @Test
    fun aSameOriginRedirectDropsADigestAnswer() =
        runBlocking<Unit> {
            server.serve("/a.bin", file)
            server.fault("/a.bin", Fault.Redirects(1))
            get("/a.bin", "Authorization" to "Digest username=\"u\"").body.close()
            server.requests
                .last()
                .headers["Authorization"]
                .shouldBeNull()
            get("/a.bin", "Authorization" to "Basic dTpw").body.close()
            server.requests.last().headers["Authorization"] shouldBe "Basic dTpw"
        }

    @Test
    fun aNonAsciiAuthorizationIsSent() =
        runBlocking<Unit> {
            server.serve("/a.bin", file)
            get("/a.bin", "Authorization" to "Digest username=\"jürgen\"").body.close()
            server.requests.last().headers["Authorization"] shouldBe "Digest username=\"jürgen\""
        }
}
