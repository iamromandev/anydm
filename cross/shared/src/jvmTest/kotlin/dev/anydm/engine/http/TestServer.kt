package dev.anydm.engine.http

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

private class Cut : RuntimeException()

class Seen(
    val method: String,
    val path: String,
    val headers: Map<String, String>,
)

/** In-process HTTP server on a free port. Content is a deterministic byte pattern, see [expected]. */
class TestServer : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
    private val stopped = CountDownLatch(1)
    private val changing = AtomicInteger()
    val seen = CopyOnWriteArrayList<Seen>()
    private val gets = ConcurrentHashMap<String, AtomicInteger>()
    private val openByPath = HashMap<String, Int>()
    private val slow = ConcurrentHashMap<Long, Long>()
    private val open = AtomicInteger()
    private val served = ConcurrentHashMap<String, AtomicLong>()
    private val peak = AtomicInteger()

    /**
     * Cuts a response after the returned number of body bytes: called with the path, the index of this GET among the GETs of that
     * path (0-based, the first request included) and the offset the response starts at; null leaves the response alone.
     */
    @Volatile var dropAfter: (path: String, nth: Int, from: Long) -> Long? = { _, _, _ -> null }

    /** Responses with a body being written right now, and the most there have been at once. */
    val openNow: Int get() = open.get()
    val maxOpen: Int get() = peak.get()

    /** The body bytes written so far to responses for [path]. */
    fun bytesServed(path: String): Long = served[path]?.get() ?: 0L

    /** Responses that start at byte [start] write a chunk only every [delayMs]. */
    fun slowRange(
        start: Long,
        delayMs: Long,
    ) {
        slow[start] = delayMs
    }

    val base: String get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.executor = Executors.newCachedThreadPool { r -> Thread(r).also { it.isDaemon = true } }
        server.createContext("/") { ex ->
            var cut = false
            try {
                route(ex, record(ex))
            } catch (e: Cut) {
                cut = true
                throw e // leaving the handler with an error makes the server drop the connection
            } catch (_: Exception) {
                // client went away
            } finally {
                if (!cut) runCatching { ex.close() }
            }
        }
        server.start()
    }

    fun requests(path: String) = seen.filter { it.path == path }

    override fun close() {
        stopped.countDown()
        server.stop(0)
    }

    /** Logs the request and returns the index of this GET among the GETs of its path (0 for a HEAD). Not an exchange attribute: those are shared by all exchanges of the context. */
    private fun record(ex: HttpExchange): Int {
        val h = ex.requestHeaders.entries.associate { it.key.lowercase() to it.value.first() }
        seen.add(Seen(ex.requestMethod, ex.requestURI.path, h))
        return if (ex.requestMethod != "HEAD") gets.computeIfAbsent(ex.requestURI.path) { AtomicInteger() }.getAndIncrement() else 0
    }

    private fun q(
        ex: HttpExchange,
        key: String,
        default: Long,
    ): Long =
        ex.requestURI.query
            ?.split('&')
            ?.firstOrNull { it.startsWith("$key=") }
            ?.substringAfter('=')
            ?.toLong() ?: default

    private fun route(
        ex: HttpExchange,
        nth: Int,
    ) {
        val path = ex.requestURI.path
        val size = q(ex, "size", 3L * 1024 * 1024)
        when {
            path == "/file" || path.startsWith("/file/") -> {
                serve(ex, nth, size, etag = "\"v1\"", ranges = true)
            }

            path == "/weak" -> {
                // A weak ETag (and, unless `nolm=1`, a Last-Modified): a weak tag never matches an If-Range, as RFC 9110 has it
                serve(ex, nth, size, etag = "W/\"v1\"", ranges = true, modified = if (q(ex, "nolm", 0) == 1L) null else LAST_MODIFIED)
            }

            path == "/norange" -> {
                serve(ex, nth, size, etag = null, ranges = false)
            }

            path == "/cd" -> {
                serve(
                    ex,
                    nth,
                    size,
                    etag = null,
                    ranges = false,
                    extra =
                        mapOf("Content-Disposition" to "attachment; filename=\"a.zip\""),
                )
            }

            path == "/changing" -> {
                serve(ex, nth, size, etag = "\"c${changing.incrementAndGet()}\"", ranges = true)
            }

            path == "/nolength" -> {
                serve(ex, nth, size, etag = null, ranges = false, chunked = true)
            }

            path == "/headrefused" -> {
                if (ex.requestMethod == "HEAD") {
                    ex.sendResponseHeaders(405, -1)
                } else {
                    serve(ex, nth, size, etag = null, ranges = true)
                }
            }

            path == "/404" -> {
                ex.sendResponseHeaders(404, -1)
            }

            path == "/redirect-ftp" -> {
                redirect(ex, "ftp://example.com/x")
            }

            path == "/badlocation" -> {
                redirect(ex, "/a b") // a raw space: not a valid URI
            }

            path == "/hang" -> {
                stopped.await() // accepts the request and never answers
            }

            path == "/headdrop" -> {
                if (ex.requestMethod == "HEAD") throw Cut() else serve(ex, nth, size, etag = null, ranges = true)
            }

            path.startsWith("/redirect/") -> {
                val n = path.removePrefix("/redirect/").toInt()
                redirect(ex, if (n > 0) "/redirect/${n - 1}" else "/file")
            }

            path == "/drop" -> {
                serve(ex, nth, size, etag = "\"v1\"", ranges = true, cutAfter = q(ex, "after", 1000))
            }

            path == "/stall" -> {
                serve(ex, nth, size, etag = "\"v1\"", ranges = true, cutAfter = q(ex, "after", 1000), stall = true)
            }

            else -> {
                ex.sendResponseHeaders(404, -1)
            }
        }
    }

    private fun redirect(
        ex: HttpExchange,
        to: String,
    ) {
        ex.responseHeaders.add("Location", to)
        ex.sendResponseHeaders(302, -1)
    }

    @Suppress("LongParameterList", "CyclomaticComplexMethod", "LongMethod")
    private fun serve(
        ex: HttpExchange,
        nth: Int,
        size: Long,
        etag: String?,
        ranges: Boolean,
        extra: Map<String, String> = emptyMap(),
        chunked: Boolean = false,
        cutAfter: Long = -1,
        stall: Boolean = false,
        modified: String? = null,
    ) {
        val path = ex.requestURI.path
        val get = ex.requestMethod != "HEAD"
        extra.forEach { (k, v) -> ex.responseHeaders.add(k, v) }
        // Per-request switches: a later request may answer 200, 416 or another ETag, to play a server whose file changes.
        val etagNow = if (etag != null && get && nth >= q(ex, "etagFrom", Long.MAX_VALUE)) "\"v2\"" else etag
        if (etagNow != null) ex.responseHeaders.add("ETag", etagNow)
        if (modified != null) ex.responseHeaders.add("Last-Modified", modified)
        if (ranges) ex.responseHeaders.add("Accept-Ranges", "bytes")
        var start = 0L
        var end = size - 1
        var status = 200
        val range = ex.requestHeaders.getFirst("Range")
        val ifRange = ex.requestHeaders.getFirst("If-Range")
        val honour = !(get && nth >= q(ex, "full200from", Long.MAX_VALUE))
        // Strong comparison: a weak validator never matches, even the very tag the server sent
        val ifRangeOk =
            ifRange == null ||
                (ifRange == etagNow && !ifRange.startsWith("W/")) ||
                (ifRange == modified) ||
                q(ex, "ignoreIfRange", 0) == 1L
        if (ranges && honour && range != null && ifRangeOk) {
            val spec = range.removePrefix("bytes=")
            start = spec.substringBefore('-').toLong()
            end = spec.substringAfter('-').toLongOrNull()?.let { minOf(it, size - 1) } ?: (size - 1)
            if (start >= size || (get && nth >= q(ex, "gone416from", Long.MAX_VALUE))) {
                ex.responseHeaders.add("Content-Range", "bytes */$size")
                ex.sendResponseHeaders(416, -1)
                return
            }
            status = 206
            ex.responseHeaders.add("Content-Range", "bytes $start-$end/$size")
        } else {
            end = size - 1
        }
        val length = end - start + 1
        if (!get) {
            if (!chunked) ex.responseHeaders.add("Content-Length", length.toString())
            ex.sendResponseHeaders(status, -1)
            return
        }
        val single = q(ex, "oneconn", 0) == 1L
        // A response that has just ended may not have left the count yet: wait a moment before calling it a second connection.
        var admitted = false
        var tries = 0
        while (!admitted && tries++ < 10) {
            admitted =
                synchronized(openByPath) {
                    val n = openByPath[path] ?: 0
                    if (single && n > 0) {
                        false
                    } else {
                        openByPath[path] = n + 1
                        true
                    }
                }
            if (!admitted) Thread.sleep(5)
        }
        if (!admitted) {
            ex.sendResponseHeaders(503, -1)
            return
        }
        peak.accumulateAndGet(open.incrementAndGet()) { a, b -> maxOf(a, b) }
        try {
            ex.sendResponseHeaders(status, if (chunked) 0 else length)
            ex.responseBody.flush() // the client has the headers even when the body that follows is empty or stalls
            val delay = slow[start] ?: q(ex, "chunkDelayMs", 0)
            val cap = q(ex, "cap", 0)
            val cut = dropAfter(path, nth, start) ?: cutAfter
            val limit = if (cut >= 0) minOf(length, cut) else length
            val buf = ByteArray(64 * 1024)
            var pos = start
            val stop = start + limit
            val began = System.nanoTime()
            ex.responseBody.use { out ->
                while (pos < stop) {
                    val n = minOf(buf.size.toLong(), stop - pos).toInt()
                    for (i in 0 until n) buf[i] = byteAt(pos + i)
                    out.write(buf, 0, n)
                    out.flush()
                    served.computeIfAbsent(path) { AtomicLong() }.addAndGet(n.toLong())
                    pos += n
                    if (delay > 0) Thread.sleep(delay)
                    if (cap > 0) {
                        val due = began + (pos - start) * 1_000_000_000L / cap
                        val wait = (due - System.nanoTime()) / 1_000_000
                        if (wait > 0) Thread.sleep(wait)
                    }
                }
                if (stall) stopped.await()
                if (cut >= 0 && limit < length) throw Cut()
            }
        } finally {
            open.decrementAndGet()
            synchronized(openByPath) { openByPath[path] = (openByPath[path] ?: 1) - 1 }
        }
    }

    companion object {
        const val LAST_MODIFIED = "Wed, 21 Oct 2015 07:28:00 GMT"

        fun byteAt(i: Long): Byte = ((i * 31 + 7) and 0xff).toByte()

        fun expected(
            size: Int,
            from: Int = 0,
        ) = ByteArray(size - from) { byteAt((from + it).toLong()) }
    }
}
