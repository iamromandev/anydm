package dev.anydm.desktop

import dev.anydm.engine.http.Headers
import dev.anydm.engine.http.HttpBody
import dev.anydm.engine.http.HttpIoException
import dev.anydm.engine.http.HttpIoKind
import dev.anydm.engine.http.HttpRequest
import dev.anydm.engine.http.HttpResponse
import dev.anydm.engine.http.HttpTransport

/** A body over [bytes]; fails with a reset after [failAfter] bytes, and records whether it was closed. */
internal class FakeBody(
    private val bytes: ByteArray,
    private val failAfter: Int = Int.MAX_VALUE,
    private val onRead: suspend (Int) -> Unit = {},
) : HttpBody {
    var closed = false
        private set
    private var position = 0

    override suspend fun read(
        into: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        if (position >= failAfter) throw HttpIoException(HttpIoKind.RESET, "github.com")
        if (position >= bytes.size) return -1
        val n = minOf(length, bytes.size - position, failAfter - position)
        bytes.copyInto(into, offset, position, position + n)
        position += n
        onRead(position)
        return n
    }

    override fun close() {
        closed = true
    }
}

/** What [FakeHttp] answers for a URL; [finalUrl] is where redirects ended (the URL itself by default). */
internal class Answer(
    val status: Int = 200,
    val body: () -> FakeBody,
    val finalUrl: String? = null,
    val headers: List<Pair<String, String>> = emptyList(),
) {
    constructor(text: String, finalUrl: String? = null) : this(body = { FakeBody(text.toByteArray()) }, finalUrl = finalUrl)
}

/** An [HttpTransport] that answers from [routes] (404 for any other URL) and records every request; no network. */
internal class FakeHttp(
    val routes: MutableMap<String, Answer> = mutableMapOf(),
) : HttpTransport {
    val requests = mutableListOf<HttpRequest>()
    val bodies = mutableListOf<FakeBody>()

    override suspend fun open(request: HttpRequest): HttpResponse {
        requests += request
        val answer = routes[request.url] ?: Answer(status = 404, body = { FakeBody(ByteArray(0)) })
        val body = answer.body().also { bodies += it }
        return HttpResponse(answer.status, Headers(answer.headers), answer.finalUrl ?: request.url, 0, body)
    }
}
