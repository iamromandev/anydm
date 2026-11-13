package dev.anydm.engine.torrent

import dev.anydm.engine.http.Clock
import dev.anydm.engine.http.HttpTransport
import dev.anydm.engine.http.JdkTransport
import dev.anydm.engine.http.Reasons
import dev.anydm.engine.http.TransportException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.URI
import java.security.SecureRandom
import kotlin.random.Random
import kotlin.random.asKotlinRandom

/**
 * Asks one tracker for peers. Everything a tracker sends is untrusted: the answers are read by the codecs in `TrackerCodec.kt`, which
 * cap sizes and clean text.
 */
interface TrackerClient {
    /**
     * Announces [req] to the tracker at [url]. Returns the tracker's answer: an [AnnounceResult], or a [TrackerFailure] when it said no
     * (`malformed` false) or answered with something unusable (`malformed` true). Fails with an [IOException] when the tracker could
     * not be reached or did not answer in time, and never with anything else but a cancellation.
     */
    suspend fun announce(
        url: String,
        req: AnnounceRequest,
    ): TrackerReply
}

/** How long an HTTP tracker may take, from the request to the last byte of the answer. */
const val HTTP_TRACKER_TIMEOUT_MS = 15_000L

/**
 * HTTP and HTTPS trackers (BEP 3) through [transport], which follows a bounded number of redirects. The answer is read up to
 * [MAX_TRACKER_RESPONSE] bytes: one that is longer is refused, whatever its `Content-Length` says. The whole exchange, the body
 * included, is held to [timeoutMs].
 */
class HttpTrackerClient(
    private val transport: HttpTransport = JdkTransport(requestTimeoutMs = HTTP_TRACKER_TIMEOUT_MS),
    private val timeoutMs: Long = HTTP_TRACKER_TIMEOUT_MS,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : TrackerClient {
    override suspend fun announce(
        url: String,
        req: AnnounceRequest,
    ): TrackerReply {
        // null only for this timeout: a cancel from outside (the farewell's bound, a pause) goes on as a cancel
        return withTimeoutOrNull(timeoutMs) { exchange(httpAnnounceUrl(url, req)) } ?: throw IOException("Tracker timed out")
    }

    private suspend fun exchange(url: String): TrackerReply {
        val open = transport.open(url, "GET", emptyMap())
        open.use {
            val length = it.headers["content-length"]?.firstOrNull()?.toLongOrNull()
            val body =
                if (length != null && length > MAX_TRACKER_RESPONSE) {
                    null
                } else {
                    // a read blocks: an interrupt (from the timeout or a cancel) ends it
                    runInterruptible(io) { readCapped(it.body) }
                }
            if (body == null) return TrackerFailure("Response too large", malformed = true)
            val reply = parseHttpAnnounce(body)
            // an error status is no answer from a tracker unless the body is its own failure reason
            if (it.status !in 200..299 && !(reply is TrackerFailure && !reply.malformed)) throw TransportException(Reasons.http(it.status))
            return reply
        }
    }

    /** The bytes of [input] up to the cap, or null when there are more. */
    private fun readCapped(input: InputStream): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) return out.toByteArray()
            if (out.size() + n > MAX_TRACKER_RESPONSE) return null
            out.write(buf, 0, n)
        }
    }
}

/** How long a UDP tracker is waited for, by the number of timeouts so far: 15 s, doubling, for the 4 tries a client makes (BEP 15). */
val UDP_TRACKER_TIMEOUTS_MS: List<Long> = listOf(15_000L, 30_000L, 60_000L, 120_000L)

/** How long a UDP tracker's connection id is used again: BEP 15 lets a client keep it for a minute. */
const val UDP_CONNECTION_TTL_MS = 60_000L

/** How long a name lookup for a UDP tracker may take before the tracker counts as not found. */
const val DNS_TIMEOUT_MS = 10_000L

/** The most a UDP tracker's answer may weigh: 20 bytes of header and up to 500 peers of 6 bytes are read, the rest of a datagram is not. */
private const val UDP_DATAGRAM = 4_096

/**
 * UDP trackers (BEP 15) over one socket per announce, connected to the tracker's address so that the system drops datagrams from
 * anywhere else. A connect gives a connection id that the next announces to that tracker use for [connectionTtlMs]. A request that
 * gets no answer is sent again after the next of [timeoutsMs] (the same transaction id, so a late answer still counts) and the
 * announce is given up after the last; an announce answered with an error for a connection id that was reused is made again with
 * a new one. Datagrams that are not the answer to the request (another transaction id, another action, too short) are ignored.
 *
 * Waits are measured on [clock], looked at every [tickMs] (which is also how soon a cancel is seen); a blocking read is all that is
 * left of the thread in between.
 */
class UdpTrackerClient(
    private val clock: Clock,
    private val timeoutsMs: List<Long> = UDP_TRACKER_TIMEOUTS_MS,
    private val connectionTtlMs: Long = UDP_CONNECTION_TTL_MS,
    private val tickMs: Long = 100,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val random: Random = SecureRandom().asKotlinRandom(),
    /** How long a name lookup may take, in real time. */
    private val dnsTimeoutMs: Long = DNS_TIMEOUT_MS,
    /** Turns the tracker's host and port into an address; the system's lookup, which blocks and cannot be interrupted. */
    private val resolver: (String, Int) -> InetSocketAddress = { host, port -> InetSocketAddress(host, port) },
) : TrackerClient {
    private class Known(
        val id: Long,
        val at: Long,
    )

    private val known = HashMap<InetSocketAddress, Known>()

    /** Where lookups run: apart from the announces, which must be able to end while one is still going. */
    private val lookups = CoroutineScope(SupervisorJob() + io)

    override suspend fun announce(
        url: String,
        req: AnnounceRequest,
    ): TrackerReply {
        val (host, port) = hostAndPort(url)
        val target = lookup(host, port)
        return withContext(io) {
            DatagramSocket().use { socket ->
                socket.soTimeout = tickMs.toInt().coerceAtLeast(1)
                socket.connect(target)
                Round(socket, target, req).run()
            }
        }
    }

    /**
     * The address of [host]. The system's lookup blocks and ignores interrupts, so it runs in a coroutine of its own that nobody waits
     * for: a cancel (or [dnsTimeoutMs]) ends the wait here at once and leaves the lookup to finish by itself.
     */
    private suspend fun lookup(
        host: String,
        port: Int,
    ): InetSocketAddress {
        val pending = lookups.async { resolver(host, port) }
        val target =
            try {
                withTimeoutOrNull(dnsTimeoutMs) { pending.await() } ?: throw IOException("Tracker not found")
            } catch (e: CancellationException) {
                pending.cancel()
                throw e
            } catch (e: IOException) {
                throw e
            } catch (e: Exception) {
                throw IOException("Tracker not found", e)
            }
        if (target.isUnresolved) throw IOException("Tracker not found")
        return target
    }

    private fun hostAndPort(url: String): Pair<String, Int> {
        val uri = runCatching { URI(url) }.getOrNull()
        val host = uri?.host
        val port = uri?.port ?: -1
        if (uri == null || !uri.scheme.equals("udp", ignoreCase = true) || host.isNullOrEmpty() || port !in 1..65_535) {
            throw IOException("Unusable tracker address")
        }
        return host to port
    }

    @Synchronized
    private fun cached(target: InetSocketAddress): Known? = known[target]?.takeIf { clock.nowMs() - it.at < connectionTtlMs }

    @Synchronized
    private fun remember(
        target: InetSocketAddress,
        id: Known?,
    ) {
        val now = clock.nowMs()
        known.values.removeAll { now - it.at >= connectionTtlMs }
        if (id == null) known.remove(target) else known[target] = id
    }

    /** One announce: its socket, the retries so far and the connection id in use. */
    private inner class Round(
        private val socket: DatagramSocket,
        private val target: InetSocketAddress,
        private val req: AnnounceRequest,
    ) {
        private var timeouts = 0
        private var connectTx = random.nextInt()
        private var announceTx = random.nextInt()
        private var id: Known? = cached(target)
        private var reused = id != null

        suspend fun run(): TrackerReply {
            while (true) {
                if (timeouts >= timeoutsMs.size) throw IOException("Tracker did not answer")
                val held = id
                if (held == null || clock.nowMs() - held.at >= connectionTtlMs) {
                    id = null
                    reused = false
                    when (val c = attempt(connectRequest(connectTx), connectTx, ::parseConnect)) {
                        null -> {
                            Unit
                        }

                        is UdpReply.Ok -> {
                            id = Known(c.value, clock.nowMs()).also { remember(target, it) }
                            announceTx = random.nextInt()
                        }

                        is UdpReply.Failure -> {
                            return TrackerFailure(c.reason)
                        }

                        UdpReply.Ignored -> {
                            Unit
                        }
                    }
                    continue
                }
                when (val a = attempt(announceRequest(held.id, announceTx, req), announceTx, ::parseUdpAnnounce)) {
                    null -> {
                        Unit
                    }

                    is UdpReply.Ok -> {
                        return a.value
                    }

                    is UdpReply.Failure -> {
                        remember(target, null)
                        // the id we had kept may be one the tracker forgot: a new one, once; with a new one the answer is final
                        if (!reused) return TrackerFailure(a.reason)
                        id = null
                        reused = false
                        connectTx = random.nextInt()
                    }

                    UdpReply.Ignored -> {
                        Unit
                    }
                }
            }
        }

        /** Sends [packet] and waits for its answer for the current timeout: null when it did not come (the next try is a longer wait). */
        private suspend fun <T> attempt(
            packet: ByteArray,
            tx: Int,
            parse: (ByteArray, Int) -> UdpReply<T>,
        ): UdpReply<T>? {
            socket.send(DatagramPacket(packet, packet.size))
            val deadline = clock.nowMs() + timeoutsMs[timeouts]
            val buf = ByteArray(UDP_DATAGRAM)
            while (clock.nowMs() < deadline) {
                currentCoroutineContext().ensureActive()
                val datagram = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(datagram)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                val reply = parse(buf.copyOf(datagram.length), tx)
                if (reply != UdpReply.Ignored) return reply
            }
            timeouts++
            return null
        }
    }
}

/** Sends each announce to the client for its URL scheme: `http` and `https`, or `udp`. Any other scheme is unreachable. */
class SchemeTrackerClient(
    private val http: TrackerClient,
    private val udp: TrackerClient,
) : TrackerClient {
    override suspend fun announce(
        url: String,
        req: AnnounceRequest,
    ): TrackerReply =
        when (runCatching { URI(url).scheme?.lowercase() }.getOrNull()) {
            "http", "https" -> http.announce(url, req)
            "udp" -> udp.announce(url, req)
            else -> throw IOException("Unsupported tracker address")
        }
}
