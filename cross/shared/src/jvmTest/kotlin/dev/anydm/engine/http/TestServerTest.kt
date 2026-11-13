package dev.anydm.engine.http

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TestServerTest {
    private val server = TestServer()
    private val client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()

    @AfterTest
    fun tearDown() = server.close()

    private fun awaitIdle() {
        val end = System.currentTimeMillis() + 5_000
        while (server.openNow > 0) {
            check(System.currentTimeMillis() < end) { "responses still open" }
            Thread.sleep(5)
        }
    }

    private fun get(
        path: String,
        vararg headers: Pair<String, String>,
    ): HttpResponse<ByteArray> {
        val b = HttpRequest.newBuilder(URI("${server.base}$path"))
        headers.forEach { (k, v) -> b.header(k, v) }
        return client.send(b.build(), HttpResponse.BodyHandlers.ofByteArray())
    }

    @Test
    fun aClosedRangeReturnsExactlyThoseBytes() {
        val r = get("/file?size=100000", "Range" to "bytes=1000-1999")
        assertEquals(206, r.statusCode())
        assertEquals("bytes 1000-1999/100000", r.headers().firstValue("content-range").get())
        assertEquals("1000", r.headers().firstValue("content-length").get())
        assertContentEquals(TestServer.expected(2000).copyOfRange(1000, 2000), r.body())
    }

    @Test
    fun anOpenEndedRangeRunsToTheEndAndAnEndPastTheFileIsClamped() {
        val open = get("/file?size=5000", "Range" to "bytes=4000-")
        assertEquals("bytes 4000-4999/5000", open.headers().firstValue("content-range").get())
        assertEquals(1000, open.body().size)
        val clamped = get("/file?size=5000", "Range" to "bytes=4000-9999")
        assertEquals("bytes 4000-4999/5000", clamped.headers().firstValue("content-range").get())
        assertEquals(416, get("/file?size=5000", "Range" to "bytes=5000-").statusCode())
    }

    @Test
    fun aRangeIsIgnoredByAServerWithoutRangeSupport() {
        val r = get("/norange?size=5000", "Range" to "bytes=10-20")
        assertEquals(200, r.statusCode())
        assertEquals(5000, r.body().size)
        assertNull(r.headers().firstValue("content-range").orElse(null))
    }

    @Test
    fun aPerConnectionCapSlowsEachResponse() {
        val t0 = System.nanoTime()
        val r = get("/file?size=400000&cap=1000000")
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals(400_000, r.body().size)
        assertTrue(ms in 300..1_500, "400 KB at 1 MB/s took $ms ms")
    }

    @Test
    fun theDropHookCutsTheNthResponseAfterTheGivenOffset() {
        server.dropAfter = { _, nth, _ -> if (nth == 1) 5_000L else null }
        assertEquals(200_000, get("/file?size=200000").body().size)
        assertFailsWith<IOException> { get("/file?size=200000") }
        assertEquals(200_000, get("/file?size=200000").body().size)
    }

    @Test
    fun concurrentRequestsEachSeeTheirOwnIndexInTheDropHook() {
        val seen =
            java.util.concurrent.ConcurrentHashMap
                .newKeySet<Int>()
        val dupes =
            java.util.concurrent.atomic
                .AtomicInteger()
        server.dropAfter = { _, nth, _ ->
            if (!seen.add(nth)) dupes.incrementAndGet()
            null
        }
        val pool =
            java.util.concurrent.Executors
                .newFixedThreadPool(16)
        try {
            val jobs = List(400) { pool.submit<Int> { get("/file?size=1000").body().size } }
            jobs.forEach { assertEquals(1000, it.get()) }
        } finally {
            pool.shutdownNow()
        }
        assertEquals(0, dupes.get(), "requests saw another request's index")
        assertEquals(400, seen.size)
    }

    @Test
    fun aSlowRangeDelaysEveryChunkOfTheResponseThatStartsThere() {
        server.slowRange(start = 70_000, delayMs = 150)
        val t0 = System.nanoTime()
        get("/file?size=200000", "Range" to "bytes=0-69999")
        val fast = (System.nanoTime() - t0) / 1_000_000
        val t1 = System.nanoTime()
        val r = get("/file?size=200000", "Range" to "bytes=70000-199999")
        val slow = (System.nanoTime() - t1) / 1_000_000
        assertEquals(130_000, r.body().size)
        assertTrue(fast < 100 && slow >= 140, "fast $fast ms, slow $slow ms") // the client has the last byte before the last pause
    }

    @Test
    fun oneConnectionModeRefusesASecondOpenResponseOfTheSamePath() {
        val first =
            client.send(
                HttpRequest.newBuilder(URI("${server.base}/file?size=300000&oneconn=1&chunkDelayMs=200")).build(),
                HttpResponse.BodyHandlers.ofInputStream(),
            )
        assertEquals(200, first.statusCode())
        first.body().read()
        assertEquals(503, get("/file?size=300000&oneconn=1&chunkDelayMs=200", "Range" to "bytes=0-9").statusCode())
        first.body().readAllBytes()
        awaitIdle()
        assertEquals(206, get("/file?size=300000&oneconn=1", "Range" to "bytes=0-9").statusCode())
    }

    @Test
    fun theServerCountsTheResponsesOpenAtOnce() {
        val threads =
            List(3) {
                Thread { get("/file?size=300000&chunkDelayMs=100") }.also { it.start() }
            }
        threads.forEach { it.join() }
        assertEquals(3, server.maxOpen)
        awaitIdle()
        assertEquals(0, server.openNow)
    }

    @Test
    fun theRequestLogKeepsTheRangeHeaders() {
        get("/file?size=100", "Range" to "bytes=0-9", "If-Range" to "\"v1\"")
        val seen = server.requests("/file").single()
        assertEquals("bytes=0-9", seen.headers["range"])
        assertEquals("\"v1\"", seen.headers["if-range"])
    }

    @Test
    fun aLaterRequestCanAnswer200ToARangedGet() {
        assertEquals(206, get("/file?size=1000&full200from=1", "Range" to "bytes=0-9").statusCode())
        val b = get("/file?size=1000&full200from=1", "Range" to "bytes=0-9")
        assertEquals(200, b.statusCode())
        assertEquals(1000, b.body().size)
    }

    @Test
    fun aLaterRequestCanAnswerWithAChangedEtagDespiteIfRange() {
        val c = get("/file?size=1000&etagFrom=1&ignoreIfRange=1", "Range" to "bytes=0-9")
        assertEquals("\"v1\"", c.headers().firstValue("etag").get())
        val d = get("/file?size=1000&etagFrom=1&ignoreIfRange=1", "Range" to "bytes=0-9", "If-Range" to "\"v1\"")
        assertEquals(206, d.statusCode())
        assertEquals("\"v2\"", d.headers().firstValue("etag").get())
    }

    @Test
    fun aLaterRequestCanAnswer416() {
        assertEquals(206, get("/file?size=1000&gone416from=1", "Range" to "bytes=0-9").statusCode())
        assertEquals(416, get("/file?size=1000&gone416from=1", "Range" to "bytes=0-9").statusCode())
    }
}
