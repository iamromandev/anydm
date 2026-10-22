package dev.anydm.engine.os

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.anydm.engine.store.Settings
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.seconds

/**
 * The browser bridge on a socket (OSI-01): a small HTTP server for the extension, on `127.0.0.1` only, on the first free
 * port of [ports] (the extension scans them, and `GET /ping` tells which one is anydm).
 *
 * A request is looked at in this order, and the first thing wrong ends it: its `Host` must be `127.0.0.1` or `localhost`
 * with this server's port (403, which stops a web page that got the address by DNS rebinding); its `Origin` must be a
 * browser extension's, and one in [origins] when that is not empty (403, which stops web pages); the path and method must be
 * one of `GET /ping`, `POST /add`, `POST /confirm` and `OPTIONS` for the preflight of those; the body must be
 * `application/json` (415) and at most [MAX_BODY] bytes (413). Every answer is JSON, and says nothing of what was sent.
 */
class BridgeServer(
    private val bridge: Bridge,
    private val origins: () -> Set<String>,
    private val ports: IntRange = PORTS,
) : AutoCloseable {
    private var server: HttpServer? = null
    private var executor: ExecutorService? = null

    /** The address it listens on, once [start]ed. */
    val address: InetSocketAddress get() = checkNotNull(server) { "not started" }.address

    /** Starts on the first free port of the range and returns it; an [IOException] if none is free. */
    fun start(): Int {
        check(server == null) { "already started" }
        val loopback = InetAddress.getByName("127.0.0.1")
        for (port in ports) {
            val created =
                try {
                    HttpServer.create(InetSocketAddress(loopback, port), 0)
                } catch (_: IOException) {
                    // Taken, or not to be bound for another reason: the next one may be fine.
                    continue
                }
            val pool = Executors.newCachedThreadPool { task -> Thread(task, "anydm-bridge").apply { isDaemon = true } }
            created.executor = pool
            created.createContext("/") { exchange -> handle(exchange, port) }
            created.start()
            server = created
            executor = pool
            return port
        }
        throw IOException("no free port for the browser bridge in $ports")
    }

    override fun close() {
        server?.stop(0)
        executor?.shutdownNow()
        server = null
        executor = null
    }

    private fun handle(
        exchange: HttpExchange,
        port: Int,
    ) {
        exchange.use { ex ->
            try {
                answer(ex, port)
            } catch (_: Exception) {
                // Never what went wrong: it could hold what was sent.
                runCatching { send(ex, 500, error("internal", "something went wrong"), null) }
            }
        }
    }

    private fun answer(
        exchange: HttpExchange,
        port: Int,
    ) {
        // Exactly one of each: two are ambiguous, and which one a proxy or this server believes would be a way round the check.
        val host = exchange.requestHeaders["Host"]?.singleOrNull()?.lowercase()
        if (host != "127.0.0.1:$port" && host != "localhost:$port") {
            return send(exchange, 403, error("forbidden", "not a host this bridge answers to"), null)
        }
        val origin = exchange.requestHeaders["Origin"]?.singleOrNull()
        if (origin == null || !originAllowed(origin)) {
            return send(exchange, 403, error("forbidden", "not an origin this bridge answers to"), null)
        }
        val method = exchange.requestMethod
        val path = exchange.requestURI.path
        val allowed = ROUTES[path] ?: return send(exchange, 404, error("not_found", "no such path"), origin)
        if (method == "OPTIONS") return preflight(exchange, origin)
        if (method != allowed) {
            exchange.responseHeaders.add("Allow", "$allowed, OPTIONS")
            return send(exchange, 405, error("method_not_allowed", "use $allowed"), origin)
        }
        if (method == "GET") return send(exchange, 200, buildJsonObject { put("app", "anydm") }, origin)

        val type =
            exchange.requestHeaders
                .getFirst("Content-Type")
                ?.substringBefore(';')
                ?.trim()
                ?.lowercase()
        if (type != "application/json") return send(exchange, 415, error("unsupported_media_type", "send application/json"), origin)
        val body = readBody(exchange) ?: return send(exchange, 413, error("too_large", "at most $MAX_BODY bytes"), origin)
        val parsed =
            try {
                Json.parseToJsonElement(body.decodeToString()) as? JsonObject
            } catch (_: SerializationException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            } ?: return send(exchange, 400, error("bad_request", "the body is not a JSON object"), origin)
        when (path) {
            "/add" -> add(exchange, parsed, origin)
            else -> confirm(exchange, parsed, origin)
        }
    }

    private fun originAllowed(origin: String): Boolean {
        if (!origin.matches(Settings.EXTENSION_ORIGIN)) return false
        val allowed = origins()
        return allowed.isEmpty() || origin in allowed
    }

    // At most MAX_BODY bytes; null for more. The rest of a body that is too large is read away (up to DRAIN), so the client
    // gets its answer and not a closed connection under its writes.
    private fun readBody(exchange: HttpExchange): ByteArray? {
        val input = exchange.requestBody
        val bytes = input.readNBytes(MAX_BODY + 1)
        if (bytes.size <= MAX_BODY) return bytes
        var drained = 0L
        val sink = ByteArray(8192)
        while (drained < DRAIN) {
            val n = input.read(sink)
            if (n < 0) break
            drained += n
        }
        return null
    }

    private fun add(
        exchange: HttpExchange,
        body: JsonObject,
        origin: String,
    ) {
        val url = body.string("url", required = true) ?: return send(exchange, 400, error("bad_request", "url is needed"), origin)
        val headers = mutableListOf<Pair<String, String>>()
        val given = body["headers"]
        if (given != null && given !is JsonNull) {
            val map = given as? JsonObject ?: return send(exchange, 400, error("bad_request", "headers is an object"), origin)
            if (map.size > MAX_HEADERS) return send(exchange, 400, error("bad_request", "too many headers"), origin)
            for ((name, value) in map) {
                val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
                headers += name to (text ?: return send(exchange, 400, error("bad_request", "a header is a string"), origin))
            }
        }
        val request =
            try {
                Bridge.AddRequest(
                    url = url,
                    cookies = body.optional("cookies"),
                    headers = headers,
                    referrer = body.optional("referrer"),
                    pageUrl = body.optional("pageUrl"),
                )
            } catch (_: TypeMismatch) {
                return send(exchange, 400, error("bad_request", "cookies, referrer and pageUrl are strings"), origin)
            }
        when (val reply = call { bridge.add(request) }) {
            null -> {
                send(exchange, 504, error("timeout", "the link did not answer in time"), origin)
            }

            is Bridge.AddReply.Refused -> {
                send(exchange, status(reply.problem), error(reply.problem.name.lowercase(), reply.message), origin)
            }

            is Bridge.AddReply.Probed -> {
                send(
                    exchange,
                    200,
                    buildJsonObject {
                        put("token", reply.token)
                        put("name", reply.name)
                        put("size", reply.size)
                        put("kind", reply.kind.name)
                        put("rangesSupported", reply.rangesSupported)
                        put("duplicate", reply.duplicate)
                    },
                    origin,
                )
            }
        }
    }

    private fun confirm(
        exchange: HttpExchange,
        body: JsonObject,
        origin: String,
    ) {
        val token = body.string("token", required = true) ?: return send(exchange, 400, error("bad_request", "token is needed"), origin)
        when (val reply = call { bridge.confirm(token) }) {
            null -> {
                send(exchange, 504, error("timeout", "adding did not finish in time"), origin)
            }

            Bridge.ConfirmReply.UnknownToken -> {
                send(
                    exchange,
                    410,
                    error("unknown_token", "the token was used, ran out, or was never given"),
                    origin,
                )
            }

            is Bridge.ConfirmReply.Refused -> {
                send(
                    exchange,
                    status(reply.problem),
                    error(reply.problem.name.lowercase(), reply.message),
                    origin,
                )
            }

            is Bridge.ConfirmReply.Added -> {
                send(exchange, 200, buildJsonObject { put("id", reply.id.value) }, origin)
            }
        }
    }

    // The answer of [block], or null if it took longer than CALL_LIMIT.
    private fun <T> call(block: suspend () -> T): T? =
        try {
            runBlocking { withTimeout(CALL_LIMIT) { block() } }
        } catch (_: TimeoutCancellationException) {
            null
        }

    private fun status(problem: Bridge.Problem): Int =
        when (problem) {
            Bridge.Problem.BAD_REQUEST -> 400
            Bridge.Problem.NOT_A_LINK -> 422
            Bridge.Problem.UNREADABLE -> 502
            Bridge.Problem.UNAVAILABLE -> 503
            Bridge.Problem.DUPLICATE -> 409
        }

    private fun preflight(
        exchange: HttpExchange,
        origin: String,
    ) {
        cors(exchange, origin)
        exchange.responseHeaders.add("Access-Control-Max-Age", "600")
        exchange.sendResponseHeaders(204, -1)
    }

    private fun cors(
        exchange: HttpExchange,
        origin: String,
    ) {
        val headers = exchange.responseHeaders
        headers.add("Access-Control-Allow-Origin", origin)
        headers.add("Vary", "Origin")
        headers.add("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        headers.add("Access-Control-Allow-Headers", "Content-Type")
        // Chrome asks before a page reaches a private address (Private Network Access); the extension is allowed to.
        if (exchange.requestHeaders.getFirst("Access-Control-Request-Private-Network").equals("true", ignoreCase = true)) {
            headers.add("Access-Control-Allow-Private-Network", "true")
        }
    }

    private fun send(
        exchange: HttpExchange,
        status: Int,
        body: JsonObject,
        origin: String?,
    ) {
        if (origin != null) cors(exchange, origin)
        val bytes = body.toString().encodeToByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        exchange.responseHeaders.add("Cache-Control", "no-store")
        exchange.responseHeaders.add("X-Content-Type-Options", "nosniff")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun error(
        code: String,
        message: String,
    ): JsonObject =
        buildJsonObject {
            put("error", code)
            put("message", message)
        }

    private class TypeMismatch : Exception()

    // A string field, null when absent or null; required: null also when missing or not a string.
    private fun JsonObject.string(
        name: String,
        required: Boolean,
    ): String? {
        val value = this[name]
        val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
        return if (required && text.isNullOrBlank()) null else text
    }

    private fun JsonObject.optional(name: String): String? {
        val value = this[name] ?: return null
        if (value is JsonNull) return null
        return (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw TypeMismatch()
    }

    companion object {
        /** The ports the bridge tries, in order; the extension (#546) scans the same ones. */
        val PORTS = 18400..18409

        /** The biggest request body taken, in bytes (OSI-01). */
        const val MAX_BODY = 64 * 1024

        private const val DRAIN = 1L shl 20
        private const val MAX_HEADERS = 64
        private val CALL_LIMIT = 30.seconds
        private val ROUTES = mapOf("/ping" to "GET", "/add" to "POST", "/confirm" to "POST")
    }
}
