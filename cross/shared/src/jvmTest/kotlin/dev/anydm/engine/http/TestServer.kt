package dev.anydm.engine.http

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

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
    val base: String get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.executor = Executors.newCachedThreadPool { r -> Thread(r).also { it.isDaemon = true } }
        server.createContext("/") { ex ->
            var cut = false
            try {
                record(ex)
                route(ex)
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

    private fun record(ex: HttpExchange) {
        val h = ex.requestHeaders.entries.associate { it.key.lowercase() to it.value.first() }
        seen.add(Seen(ex.requestMethod, ex.requestURI.path, h))
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

    private fun route(ex: HttpExchange) {
        val path = ex.requestURI.path
        val size = q(ex, "size", 3L * 1024 * 1024)
        when {
            path == "/file" -> {
                serve(ex, size, etag = "\"v1\"", ranges = true)
            }

            path == "/norange" -> {
                serve(ex, size, etag = null, ranges = false)
            }

            path == "/cd" -> {
                serve(
                    ex,
                    size,
                    etag = null,
                    ranges = false,
                    extra =
                        mapOf("Content-Disposition" to "attachment; filename=\"a.zip\""),
                )
            }

            path == "/changing" -> {
                serve(ex, size, etag = "\"c${changing.incrementAndGet()}\"", ranges = true)
            }

            path == "/nolength" -> {
                serve(ex, size, etag = null, ranges = false, chunked = true)
            }

            path == "/headrefused" -> {
                if (ex.requestMethod == "HEAD") {
                    ex.sendResponseHeaders(405, -1)
                } else {
                    serve(ex, size, etag = null, ranges = true)
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
                if (ex.requestMethod == "HEAD") throw Cut() else serve(ex, size, etag = null, ranges = true)
            }

            path.startsWith("/redirect/") -> {
                val n = path.removePrefix("/redirect/").toInt()
                redirect(ex, if (n > 0) "/redirect/${n - 1}" else "/file")
            }

            path == "/drop" -> {
                serve(ex, size, etag = "\"v1\"", ranges = true, cutAfter = q(ex, "after", 1000))
            }

            path == "/stall" -> {
                serve(ex, size, etag = "\"v1\"", ranges = true, cutAfter = q(ex, "after", 1000), stall = true)
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

    @Suppress("LongParameterList", "CyclomaticComplexMethod")
    private fun serve(
        ex: HttpExchange,
        size: Long,
        etag: String?,
        ranges: Boolean,
        extra: Map<String, String> = emptyMap(),
        chunked: Boolean = false,
        cutAfter: Long = -1,
        stall: Boolean = false,
    ) {
        extra.forEach { (k, v) -> ex.responseHeaders.add(k, v) }
        if (etag != null) ex.responseHeaders.add("ETag", etag)
        if (ranges) ex.responseHeaders.add("Accept-Ranges", "bytes")
        var start = 0L
        var status = 200
        val range = ex.requestHeaders.getFirst("Range")
        val ifRange = ex.requestHeaders.getFirst("If-Range")
        if (ranges && range != null && (ifRange == null || ifRange == etag)) {
            start = range.removePrefix("bytes=").substringBefore('-').toLong()
            if (start >= size) {
                ex.responseHeaders.add("Content-Range", "bytes */$size")
                ex.sendResponseHeaders(416, -1)
                return
            }
            status = 206
            ex.responseHeaders.add("Content-Range", "bytes $start-${size - 1}/$size")
        }
        val length = size - start
        if (ex.requestMethod == "HEAD") {
            if (!chunked) ex.responseHeaders.add("Content-Length", length.toString())
            ex.sendResponseHeaders(status, -1)
            return
        }
        ex.sendResponseHeaders(status, if (chunked) 0 else length)
        val delay = q(ex, "chunkDelayMs", 0)
        val limit = if (cutAfter >= 0) minOf(length, cutAfter) else length
        val buf = ByteArray(64 * 1024)
        var pos = start
        val end = start + limit
        ex.responseBody.use { out ->
            while (pos < end) {
                val n = minOf(buf.size.toLong(), end - pos).toInt()
                for (i in 0 until n) buf[i] = byteAt(pos + i)
                out.write(buf, 0, n)
                out.flush()
                pos += n
                if (delay > 0) Thread.sleep(delay)
            }
            if (stall) stopped.await()
            if (cutAfter >= 0 && limit < length) throw Cut()
        }
    }

    companion object {
        fun byteAt(i: Long): Byte = ((i * 31 + 7) and 0xff).toByte()

        fun expected(
            size: Int,
            from: Int = 0,
        ) = ByteArray(size - from) { byteAt((from + it).toLong()) }
    }
}
