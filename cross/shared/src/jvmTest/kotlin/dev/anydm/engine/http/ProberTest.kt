package dev.anydm.engine.http

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProberTest {
    private val prober = HttpProber(JdkTransport())

    private fun ok(o: ProbeOutcome) = (o as ProbeOutcome.Ok).r

    @Test
    fun headGivesNameSizeAndResumable() =
        TestServer().use { s ->
            runBlocking {
                val r = ok(prober.probe("${s.base}/file"))
                assertEquals(ProbeResult("file", 3L * 1024 * 1024, true), r)
                assertEquals(listOf("HEAD"), s.seen.map { it.method })
            }
        }

    @Test
    fun contentDispositionNamesTheFile() =
        TestServer().use { s ->
            runBlocking {
                val r = ok(prober.probe("${s.base}/cd"))
                assertEquals("a.zip", r.name)
                assertEquals(false, r.resumable)
            }
        }

    @Test
    fun unknownSizeWhenNoContentLength() =
        TestServer().use { s ->
            runBlocking {
                val r = ok(prober.probe("${s.base}/nolength?size=5000"))
                assertEquals(null, r.total)
            }
        }

    @Test
    fun headRefusedFallsBackToGet() =
        TestServer().use { s ->
            runBlocking {
                val r = ok(prober.probe("${s.base}/headrefused?size=4096"))
                assertEquals(4096L, r.total)
                assertTrue(r.resumable)
                assertEquals(listOf("HEAD", "GET"), s.seen.map { it.method })
            }
        }

    @Test
    fun aHeadThatDropsTheConnectionFallsBackToGetOnce() =
        TestServer().use { s ->
            runBlocking {
                val r = ok(prober.probe("${s.base}/headdrop?size=4096"))
                assertEquals(4096L, r.total)
                // The JDK client itself repeats a HEAD whose connection died; the GET comes once, after them.
                assertEquals(1, s.seen.count { it.method == "GET" })
                assertEquals("GET", s.seen.last().method)
            }
        }

    @Test
    fun aServerThatNeverAnswersFailsWithinTheRequestTimeout() =
        TestServer().use { s ->
            runBlocking {
                val quick = HttpProber(JdkTransport(requestTimeoutMs = 300))
                assertEquals(ProbeOutcome.Failed(Reasons.NETWORK), withTimeout(10_000) { quick.probe("${s.base}/hang") })
            }
        }

    @Test
    fun notFoundIsReportedWithoutFallback() =
        TestServer().use { s ->
            runBlocking {
                assertEquals(ProbeOutcome.Failed("HTTP 404"), prober.probe("${s.base}/404"))
                assertEquals(1, s.seen.size)
            }
        }

    @Test
    fun badSchemeAndDeadHostFail() {
        runBlocking {
            assertEquals(ProbeOutcome.Failed(Reasons.NETWORK), prober.probe("ftp://example.com/x"))
            val dead = TestServer().use { it.base }
            assertEquals(ProbeOutcome.Failed(Reasons.NETWORK), prober.probe("$dead/file"))
        }
    }

    @Test
    fun cancellingTheProbeClosesTheConnection() {
        val closed = CountDownLatch(1)
        ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { ss ->
            Thread {
                runCatching {
                    ss.accept().use { sock ->
                        // read the request, never answer; wait for the client to hang up
                        val buf = ByteArray(4096)
                        do {
                            val n = sock.getInputStream().read(buf)
                        } while (n >= 0)
                        closed.countDown()
                    }
                }
            }.apply { isDaemon = true }.start()
            runBlocking {
                assertFailsWith<TimeoutCancellationException> {
                    withTimeout(300) { prober.probe("http://127.0.0.1:${ss.localPort}/x") }
                }
            }
            assertTrue(closed.await(5, TimeUnit.SECONDS), "server never saw the client hang up")
        }
    }
}
