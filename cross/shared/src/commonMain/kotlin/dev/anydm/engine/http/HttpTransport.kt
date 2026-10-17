package dev.anydm.engine.http

/** Response headers: names compared without case, repeats kept in order. */
class Headers(
    val entries: List<Pair<String, String>>,
) {
    /** The first value of [name], or null. */
    operator fun get(name: String): String? = entries.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second

    fun all(name: String): List<String> = entries.filter { it.first.equals(name, ignoreCase = true) }.map { it.second }

    override fun toString(): String = entries.toString()
}

data class HttpRequest(
    val url: String,
    val method: String = "GET",
    val headers: List<Pair<String, String>> = emptyList(),
)

interface HttpBody {
    /** Reads up to [length] bytes into [into] at [offset]; -1 at the end. Throws HttpIoException on a network failure. */
    suspend fun read(
        into: ByteArray,
        offset: Int = 0,
        length: Int = into.size - offset,
    ): Int

    fun close()
}

/** What came back after following redirects: [finalUrl] is the address that answered, after [redirects] hops. */
class HttpResponse(
    val status: Int,
    val headers: Headers,
    val finalUrl: String,
    val redirects: Int,
    val body: HttpBody,
)

enum class HttpIoKind { CONNECT, TIMEOUT, RESET, TLS, TOO_MANY_REDIRECTS, BAD_URL, OTHER }

/**
 * A request that got no HTTP answer. The message names only the [kind] and [host] (no user, password, path, query or
 * the [cause]'s own text, which may hold any of them).
 */
class HttpIoException(
    val kind: HttpIoKind,
    val host: String,
    cause: Throwable? = null,
) : Exception(messageOf(kind, host), cause)

private fun messageOf(
    kind: HttpIoKind,
    host: String,
): String {
    val what =
        when (kind) {
            HttpIoKind.CONNECT -> "can't connect"
            HttpIoKind.TIMEOUT -> "timed out"
            HttpIoKind.RESET -> "connection broke"
            HttpIoKind.TLS -> "TLS handshake failed"
            HttpIoKind.TOO_MANY_REDIRECTS -> "more than 5 redirects"
            HttpIoKind.BAD_URL -> "not an HTTP URL"
            HttpIoKind.OTHER -> "network error"
        }
    return "$what ($host)"
}

/** How HTTP requests leave the engine; OkHttp on the JVM. */
interface HttpTransport {
    /** Sends [request], following up to 5 redirects. Throws HttpIoException for network failures; HTTP errors are responses. */
    suspend fun open(request: HttpRequest): HttpResponse
}
