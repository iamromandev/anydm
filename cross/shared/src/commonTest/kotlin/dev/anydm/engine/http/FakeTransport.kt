package dev.anydm.engine.http

/** Answers each request with [responses] (which may delay in virtual time or throw) and records what was asked. */
class FakeTransport(
    private val responses: suspend (HttpRequest) -> HttpResponse,
) : HttpTransport {
    val requests = mutableListOf<HttpRequest>()

    override suspend fun open(request: HttpRequest): HttpResponse {
        requests += request
        return responses(request)
    }
}

/** A body over [bytes] that counts what was read and whether it was closed. */
class BytesBody(
    private val bytes: ByteArray,
) : HttpBody {
    var read = 0
        private set
    var closed = false
        private set

    override suspend fun read(
        into: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        check(!closed) { "read after close" }
        if (read == bytes.size) return -1
        val n = minOf(length, bytes.size - read)
        bytes.copyInto(into, offset, read, read + n)
        read += n
        return n
    }

    override fun close() {
        closed = true
    }
}

fun bytesBody(bytes: ByteArray = ByteArray(1024)) = BytesBody(bytes)

fun response(
    status: Int,
    vararg headers: Pair<String, String>,
    finalUrl: String = "https://h/f.zip",
    body: HttpBody = bytesBody(),
    redirects: Int = 0,
) = HttpResponse(status, Headers(headers.toList()), finalUrl, redirects, body)

private val rangeHeader = Regex("""bytes=(\d+)-(\d+)""")

/** Where this request's `Range: bytes=a-b` starts, or null without one. */
fun HttpRequest.rangeStart(): Long? =
    headers
        .firstOrNull { it.first == "Range" }
        ?.second
        ?.let(rangeHeader::matchEntire)
        ?.groupValues
        ?.get(1)
        ?.toLong()

/** Serves [bytes]: 206 with an exact Content-Range for `Range: bytes=a-b`, 200 and the whole of it otherwise. */
fun servingRanges(
    bytes: ByteArray,
    finalUrl: String = "https://h/f.bin",
): suspend (HttpRequest) -> HttpResponse =
    { request ->
        val range =
            request.headers
                .firstOrNull { it.first == "Range" }
                ?.second
                ?.let(rangeHeader::matchEntire)
        if (range == null) {
            response(200, "Content-Length" to "${bytes.size}", finalUrl = finalUrl, body = bytesBody(bytes))
        } else {
            val (a, b) = range.destructured
            val first = a.toInt()
            val last = minOf(b.toInt(), bytes.size - 1)
            response(
                206,
                "Content-Range" to "bytes $first-$last/${bytes.size}",
                finalUrl = finalUrl,
                body = bytesBody(bytes.copyOfRange(first, last + 1)),
            )
        }
    }
