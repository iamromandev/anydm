package dev.anydm.engine.torrent

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/** What an announce request carried, with the percent-encoded parameters decoded to bytes. */
internal class AnnounceSeen(
    val path: String,
    val query: String,
    val params: Map<String, ByteArray>,
) {
    val event: String? get() = params["event"]?.decodeToString()
    val infoHash: ByteArray? get() = params["info_hash"]
    val left: Long? get() = params["left"]?.decodeToString()?.toLongOrNull()
    val downloaded: Long? get() = params["downloaded"]?.decodeToString()?.toLongOrNull()
}

/** What the fake tracker answers. */
internal sealed interface TrackerAnswer {
    class Body(
        val bytes: ByteArray,
        val status: Int = 200,
    ) : TrackerAnswer

    /** [bytes] sent chunked, with no `Content-Length`: the client cannot tell from the headers how long the answer is. */
    class Chunked(
        val bytes: ByteArray,
    ) : TrackerAnswer

    /** Chunked, and it never ends: the answer is [prefix] and then zeros for as long as the client reads. */
    class Endless(
        val prefix: ByteArray = ByteArray(0),
    ) : TrackerAnswer

    class Redirect(
        val location: String,
    ) : TrackerAnswer

    /** Takes the request and never answers. */
    data object Hang : TrackerAnswer
}

/** An HTTP tracker on a free loopback port. [answer] decides, request by request (by index over all paths); every request is recorded. */
internal class FakeHttpTracker(
    @Volatile var answer: (nth: Int, seen: AnnounceSeen) -> TrackerAnswer = { _, _ -> TrackerAnswer.Body(peersReply(emptyList())) },
) : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
    private val stopped = CountDownLatch(1)
    val seen = CopyOnWriteArrayList<AnnounceSeen>()
    val base: String get() = "http://127.0.0.1:${server.address.port}"
    val announceUrl: String get() = "$base/announce"

    /** The `event` of each request, in order (null for a periodic one). */
    val events: List<String?> get() = seen.map { it.event }

    init {
        server.executor = Executors.newCachedThreadPool { r -> Thread(r).also { it.isDaemon = true } }
        server.createContext("/") { ex ->
            try {
                handle(ex)
            } catch (_: Exception) {
                // the client went away
            } finally {
                runCatching { ex.close() }
            }
        }
        server.start()
    }

    private fun handle(ex: HttpExchange) {
        val query = ex.requestURI.rawQuery.orEmpty()
        val s = AnnounceSeen(ex.requestURI.path, query, parse(query))
        val nth = synchronized(seen) { seen.size.also { seen.add(s) } }
        when (val a = answer(nth, s)) {
            is TrackerAnswer.Body -> {
                ex.sendResponseHeaders(
                    a.status,
                    a.bytes.size
                        .toLong()
                        .let { if (it == 0L) -1 else it },
                )
                if (a.bytes.isNotEmpty()) ex.responseBody.use { it.write(a.bytes) }
            }

            is TrackerAnswer.Chunked -> {
                ex.sendResponseHeaders(200, 0)
                ex.responseBody.use { it.write(a.bytes) }
            }

            is TrackerAnswer.Endless -> {
                ex.sendResponseHeaders(200, 0)
                ex.responseBody.use { out ->
                    out.write(a.prefix)
                    val zeros = ByteArray(8 * 1024)
                    // ends when the client closes the connection (a write then fails) or the tracker is closed
                    while (stopped.count > 0) out.write(zeros)
                }
            }

            is TrackerAnswer.Redirect -> {
                ex.responseHeaders.add("Location", a.location)
                ex.sendResponseHeaders(302, -1)
            }

            TrackerAnswer.Hang -> {
                stopped.await()
            }
        }
    }

    override fun close() {
        stopped.countDown()
        server.stop(0)
    }

    companion object {
        fun parse(query: String): Map<String, ByteArray> =
            query
                .split('&')
                .filter { '=' in it }
                .associate { it.substringBefore('=') to unescape(it.substringAfter('=')) }

        private fun unescape(s: String): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            var i = 0
            while (i < s.length) {
                if (s[i] == '%') {
                    out.write(s.substring(i + 1, i + 3).toInt(16))
                    i += 3
                } else {
                    out.write(s[i].code)
                    i++
                }
            }
            return out.toByteArray()
        }

        private fun compact(peers: List<PeerAddr>): ByteArray =
            peers
                .flatMap { p ->
                    p.host.split('.').map { it.toInt().toByte() } + listOf((p.port ushr 8).toByte(), p.port.toByte())
                }.toByteArray()

        /** A good answer with [peers] in the compact form. */
        fun peersReply(
            peers: List<PeerAddr>,
            interval: Long = 1_800,
            minInterval: Long? = null,
        ): ByteArray =
            bdict(
                buildList {
                    add("interval" to bint(interval))
                    minInterval?.let { add("min interval" to bint(it)) }
                    add("peers" to bstr(compact(peers)))
                },
            )

        /** A good answer with [peers] as a list of dictionaries. */
        fun dictionaryReply(
            peers: List<PeerAddr>,
            interval: Long = 1_800,
        ): ByteArray =
            bdict(
                "interval" to bint(interval),
                "peers" to blist(peers.map { bdict("ip" to bstr(it.host), "port" to bint(it.port.toLong())) }),
            )

        fun failureReply(reason: String): ByteArray = bdict("failure reason" to bstr(reason))
    }
}
