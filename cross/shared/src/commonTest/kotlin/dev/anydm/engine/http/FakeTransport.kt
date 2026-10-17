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
