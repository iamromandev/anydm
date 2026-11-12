package dev.anydm.engine.http

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JdkTransportTest {
    private val transport = JdkTransport()

    @Test
    fun getsBodyAndLowercasedHeaders() =
        TestServer().use { s ->
            runBlocking {
                transport.open("${s.base}/file?size=1000", "GET", emptyMap()).use {
                    assertEquals(200, it.status)
                    assertEquals(listOf("1000"), it.headers["content-length"])
                    assertContentEquals(TestServer.expected(1000), it.body.readBytes())
                }
            }
        }

    @Test
    fun errorStatusesAreReturnedNotThrown() =
        TestServer().use { s ->
            runBlocking { transport.open("${s.base}/404", "GET", emptyMap()).use { assertEquals(404, it.status) } }
        }

    @Test
    fun tenRedirectsAreFollowed() =
        TestServer().use { s ->
            runBlocking {
                transport.open("${s.base}/redirect/9", "GET", emptyMap()).use {
                    assertEquals(200, it.status)
                    assertEquals("${s.base}/file", it.finalUrl)
                }
            }
        }

    @Test
    fun elevenRedirectsFail() =
        TestServer().use { s ->
            runBlocking {
                val e = assertFailsWith<TransportException> { transport.open("${s.base}/redirect/10", "GET", emptyMap()) }
                assertEquals(Reasons.NETWORK, e.reason)
            }
        }

    @Test
    fun redirectToFtpFails() =
        TestServer().use { s ->
            runBlocking {
                val e = assertFailsWith<TransportException> { transport.open("${s.base}/redirect-ftp", "GET", emptyMap()) }
                assertEquals(Reasons.NETWORK, e.reason)
            }
        }

    @Test
    fun aLocationThatIsNotAValidUriFailsAsNetworkError() =
        TestServer().use { s ->
            runBlocking {
                val e = assertFailsWith<TransportException> { transport.open("${s.base}/badlocation", "GET", emptyMap()) }
                assertEquals(Reasons.NETWORK, e.reason)
            }
        }

    @Test
    fun aServerThatNeverAnswersTimesOutAsNetworkError() =
        TestServer().use { s ->
            runBlocking {
                val quick = JdkTransport(requestTimeoutMs = 300)
                val e = assertFailsWith<TransportException> { withTimeout(10_000) { quick.open("${s.base}/hang", "GET", emptyMap()) } }
                assertEquals(Reasons.NETWORK, e.reason)
            }
        }

    @Test
    fun nonHttpUrlIsRejected() {
        runBlocking {
            assertFailsWith<TransportException> { transport.open("file:///etc/passwd", "GET", emptyMap()) }
            assertFailsWith<TransportException> { transport.open("not a url", "GET", emptyMap()) }
        }
    }

    @Test
    fun connectionRefusedIsNetworkError() {
        val dead = TestServer().use { it.base }
        runBlocking {
            val e = assertFailsWith<TransportException> { transport.open("$dead/file", "GET", emptyMap()) }
            assertEquals(Reasons.NETWORK, e.reason)
        }
    }

    @Test
    fun sendsRequestHeaders() =
        TestServer().use { s ->
            runBlocking {
                transport.open("${s.base}/file?size=10", "GET", mapOf("Range" to "bytes=5-")).use { assertEquals(206, it.status) }
                assertEquals("bytes=5-", s.requests("/file").single().headers["range"])
            }
        }
}
