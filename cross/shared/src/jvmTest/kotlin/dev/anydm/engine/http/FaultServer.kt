package dev.anydm.engine.http

import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import okio.Buffer
import java.security.MessageDigest
import java.util.Base64
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

    /** Applies [fault] to the next request for the path only, then serves it normally. */
    data class Once(
        val fault: Fault,
    ) : Fault

    /** Answers 401 with a Basic challenge unless the request logs in as [user]:[password]. */
    data class Basic(
        val user: String,
        val password: String,
    ) : Fault

    /** Answers 401 with a Digest challenge ([algorithm], with `qop=auth` if [qop]) unless the request answers it. */
    data class Digest(
        val user: String,
        val password: String,
        val algorithm: String = "SHA-256",
        val qop: Boolean = true,
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
    private companion object {
        const val NONCE = "fault-nonce"
        val authParam = Regex("""(\w+)=(?:"((?:[^"\\]|\\.)*)"|([^,\s]+))""")
    }

    private class Served(
        val bytes: ByteArray,
        val etag: String?,
        val lastModified: String?,
        val disposition: String?,
        val contentType: String?,
        val digest: String?,
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
        digest: String? = null,
    ) {
        files[path] = Served(bytes, etag, lastModified, disposition, contentType, digest)
    }

    /** Replaces [path]'s fault. */
    fun fault(
        path: String,
        fault: Fault,
    ) {
        faults[path] = fault
    }

    /** Serves [path] normally again. */
    fun clear(path: String) {
        faults.remove(path)
    }

    fun url(path: String): String = server.url(path).toString()

    override fun close() = server.close()

    private fun respond(request: RecordedRequest): MockResponse {
        val target = request.url.encodedPath
        val hop = hopOf(target)
        val path = hop?.second ?: target
        val served = files[path] ?: return MockResponse.Builder().code(404).build()
        val fault =
            when (val set = faults[path]) {
                is Fault.Once -> set.fault.also { faults.remove(path, set) }
                else -> set
            }
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
        if (fault is Fault.Basic || fault is Fault.Digest) {
            denied(fault, request)?.let { return it }
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
        served.digest?.let { builder.addHeader("Digest", it) }
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
                // A tenth every 100 ms: the same rate, without sending a whole second's worth at once at the start of
                // every response (#576 measures against this limit).
                builder.body(Buffer().write(bytes)).throttleBody((fault.bytesPerSecond / 10).coerceAtLeast(1), 100, TimeUnit.MILLISECONDS)
            }

            else -> {
                builder.body(Buffer().write(bytes))
            }
        }
        return builder.build()
    }

    /** A 401 with [fault]'s challenge, or null when [request] logs in; checked with this server's own digest code. */
    private fun denied(
        fault: Fault,
        request: RecordedRequest,
    ): MockResponse? {
        val authorization = request.headers["Authorization"]
        val (accepted, challenge) =
            when (fault) {
                is Fault.Basic -> {
                    val expected = "Basic " + Base64.getEncoder().encodeToString("${fault.user}:${fault.password}".toByteArray())
                    (authorization == expected) to "Basic realm=\"fault\""
                }

                is Fault.Digest -> {
                    val qop = if (fault.qop) ", qop=\"auth\"" else ""
                    digestAccepted(fault, request, authorization) to
                        "Digest realm=\"fault\", nonce=\"$NONCE\", algorithm=${fault.algorithm}$qop"
                }

                else -> {
                    return null
                }
            }
        if (accepted) return null
        return MockResponse
            .Builder()
            .code(401)
            .addHeader("WWW-Authenticate", challenge)
            .body("log in")
            .build()
    }

    private fun digestAccepted(
        fault: Fault.Digest,
        request: RecordedRequest,
        authorization: String?,
    ): Boolean {
        if (authorization == null || !authorization.startsWith("Digest ")) return false
        val p =
            authParam
                .findAll(authorization.removePrefix("Digest "))
                .associate {
                    it.groupValues[1].lowercase() to (
                        it.groups[2]?.value?.replace(
                            Regex("""\\(.)"""),
                            "$1",
                        ) ?: it.groupValues[3]
                    )
                }
        val target = request.url.encodedPath + (request.url.encodedQuery?.let { "?$it" } ?: "")
        if (p["username"] != fault.user || p["realm"] != "fault" || p["nonce"] != NONCE || p["uri"] != target) return false
        val name = if (fault.algorithm.startsWith("SHA-256")) "SHA-256" else "MD5"

        fun h(text: String) = MessageDigest.getInstance(name).digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
        val ha1 = h("${fault.user}:fault:${fault.password}")
        val ha2 = h("${request.method}:$target")
        val expected =
            if (fault.qop) {
                if (p["qop"] != "auth") return false
                h("$ha1:$NONCE:${p["nc"]}:${p["cnonce"]}:auth:$ha2")
            } else {
                h("$ha1:$NONCE:$ha2")
            }
        return p["response"] == expected
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
