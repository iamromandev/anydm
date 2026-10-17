package dev.anydm.engine.http

import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import okio.Buffer
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** What a [FaultServer] path does instead of serving its file normally. */
sealed interface Fault {
    /** Answers every request with 200 and the whole file. */
    data object IgnoreRanges : Fault

    /** Serves the asked range under a Content-Range one byte off. */
    data object WrongContentRange : Fault

    data class Status(
        val code: Int,
        val retryAfter: String? = null,
    ) : Fault

    /** Sends [bytes] of the body, then breaks the connection. */
    data class DropAfter(
        val bytes: Long,
    ) : Fault

    data class Throttle(
        val bytesPerSecond: Long,
    ) : Fault

    /** Redirects [hops] times before serving the file; hops alternate absolute and path-relative Locations. */
    data class Redirects(
        val hops: Int,
    ) : Fault
}

/**
 * A local HTTP server for the transfer tests: serves files with `Range: bytes=a-b`, `a-` and `-n` (206 with
 * Content-Range, 416 `bytes N` when unsatisfiable), with optional validators and headers, and injects one [Fault] per
 * path.
 */
class FaultServer : AutoCloseable {
    private class Served(
        val bytes: ByteArray,
        val etag: String?,
        val lastModified: String?,
        val disposition: String?,
        val contentType: String?,
    )

    private val files = ConcurrentHashMap<String, Served>()
    private val faults = ConcurrentHashMap<String, Fault>()
    private val recorded = Collections.synchronizedList(mutableListOf<RecordedRequest>())
    private val server = MockWebServer()

    val requests: List<RecordedRequest> get() = synchronized(recorded) { recorded.toList() }

    init {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    recorded += request
                    return respond(request)
                }
            }
        server.start()
    }

    fun serve(
        path: String,
        bytes: ByteArray,
        etag: String? = null,
        lastModified: String? = null,
        disposition: String? = null,
        contentType: String? = null,
    ) {
        files[path] = Served(bytes, etag, lastModified, disposition, contentType)
    }

    /** Replaces [path]'s fault. */
    fun fault(
        path: String,
        fault: Fault,
    ) {
        faults[path] = fault
    }

    fun url(path: String): String = server.url(path).toString()

    override fun close() = server.close()

    private fun respond(request: RecordedRequest): MockResponse {
        val target = request.url.encodedPath
        val hop = hopOf(target)
        val path = hop?.second ?: target
        val served = files[path] ?: return MockResponse.Builder().code(404).build()
        val fault = faults[path]
        if (fault is Fault.Redirects) {
            val next = (hop?.first ?: 0) + 1
            if (next <= fault.hops) {
                val location = "/__hop/$next$path"
                return MockResponse
                    .Builder()
                    .code(302)
                    .addHeader("Location", if (next % 2 == 1) url(location) else location)
                    .build()
            }
        }
        if (fault is Fault.Status) {
            return MockResponse
                .Builder()
                .code(fault.code)
                .apply { fault.retryAfter?.let { addHeader("Retry-After", it) } }
                .body("error ${fault.code}")
                .build()
        }
        val size = served.bytes.size.toLong()
        val range = if (fault == Fault.IgnoreRanges) null else request.headers["Range"]?.let { rangeOf(it, size) }
        val builder = MockResponse.Builder().addHeader("Accept-Ranges", "bytes")
        served.etag?.let { builder.addHeader("ETag", it) }
        served.lastModified?.let { builder.addHeader("Last-Modified", it) }
        served.disposition?.let { builder.addHeader("Content-Disposition", it) }
        served.contentType?.let { builder.addHeader("Content-Type", it) }
        val bytes =
            when (range) {
                null -> {
                    builder.code(200)
                    served.bytes
                }

                Unsatisfiable -> {
                    return builder.code(416).addHeader("Content-Range", "bytes */$size").build()
                }

                is Satisfiable -> {
                    val shift = if (fault == Fault.WrongContentRange) 1 else 0
                    builder.code(206).addHeader("Content-Range", "bytes ${range.first + shift}-${range.last + shift}/$size")
                    served.bytes.copyOfRange(range.first.toInt(), range.last.toInt() + 1)
                }
            }
        when (fault) {
            is Fault.DropAfter -> {
                if (fault.bytes in 1 until bytes.size) {
                    // The server's body effect fires halfway through the body: send twice the bytes, close the socket
                    // at the midpoint, and still announce the whole length.
                    val doubled = bytes.copyOf((fault.bytes * 2).toInt())
                    builder
                        .body(Buffer().write(doubled))
                        .onResponseBody(SocketEffect.CloseSocket())
                        .setHeader("Content-Length", bytes.size)
                } else {
                    builder.body(Buffer().write(bytes))
                }
            }

            is Fault.Throttle -> {
                builder.body(Buffer().write(bytes)).throttleBody(fault.bytesPerSecond, 1, TimeUnit.SECONDS)
            }

            else -> {
                builder.body(Buffer().write(bytes))
            }
        }
        return builder.build()
    }

    private sealed interface RangeOf

    private data object Unsatisfiable : RangeOf

    private data class Satisfiable(
        val first: Long,
        val last: Long,
    ) : RangeOf

    /** `bytes=a-b`, `a-` or `-n` against [size]; null (serve it whole) for anything else, such as several ranges. */
    private fun rangeOf(
        header: String,
        size: Long,
    ): RangeOf? {
        val spec = header.trim().removePrefix("bytes=").takeIf { header.trim().startsWith("bytes=") && ',' !in it } ?: return null
        val start = spec.substringBefore('-').trim()
        val end = spec.substringAfter('-', "").trim()
        return when {
            start.isEmpty() -> {
                val suffix = end.toLongOrNull() ?: return null
                if (suffix == 0L || size == 0L) Unsatisfiable else Satisfiable(maxOf(0, size - suffix), size - 1)
            }

            else -> {
                val first = start.toLongOrNull() ?: return null
                val last = if (end.isEmpty()) size - 1 else end.toLongOrNull() ?: return null
                if (last < first) return null
                if (first >= size) Unsatisfiable else Satisfiable(first, minOf(last, size - 1))
            }
        }
    }

    /** `/__hop/<k>/<path>` → k and the path. */
    private fun hopOf(target: String): Pair<Int, String>? {
        if (!target.startsWith("/__hop/")) return null
        val rest = target.removePrefix("/__hop/")
        val k = rest.substringBefore('/').toIntOrNull() ?: return null
        return k to "/" + rest.substringAfter('/')
    }
}

/** Reads [this] to its end. */
suspend fun HttpBody.readAll(): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val n = read(buffer)
        if (n < 0) return out.toByteArray()
        out.write(buffer, 0, n)
    }
}
