package dev.anydm.engine.torrent

import dev.anydm.engine.http.Clock
import dev.anydm.engine.http.TokenBucket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/** What a [PeerSession] tells the torrent run. */
internal sealed interface PeerEvent {
    /** The handshake is done: the peer speaks for our torrent and is not us. [remoteId] is its 20-byte peer id. */
    class Ready(
        val remoteId: ByteArray,
    ) : PeerEvent {
        override fun toString() = "Ready"
    }

    /** A message the run cares about: `choke`, `unchoke`, `have`, `bitfield` and `piece`. Requests and the like are never passed on. */
    class Msg(
        val msg: PeerMsg,
    ) : PeerEvent {
        override fun toString() = "Msg($msg)"
    }

    /**
     * The session ended by itself (not because it was cancelled), for [reason]. [retry] is true when the connection failed or was
     * closed (an [IOException]: the peer may be fine next time) and false for a protocol violation.
     */
    class Closed(
        val reason: String,
        val retry: Boolean = false,
    ) : PeerEvent {
        override fun toString() = "Closed($reason)"
    }
}

/** How long a session waits; [tickMs] is how often it looks at the clock (the waits themselves are on the clock it is given). */
internal data class SessionLimits(
    val connectTimeoutMs: Int = 10_000,
    val handshakeMs: Long = 30_000,
    val tickMs: Long = 250,
)

/**
 * One connection to one peer: connects, shakes hands, and then turns what the peer sends into [PeerEvent]s for [sink] and writes
 * what [send] queues. It sends a keep-alive after [KEEPALIVE_MS] without writing, drops the peer after [PEER_SILENCE_MS] without
 * reading (both on [clock]) and on any protocol violation, and never answers a request. Reads go through [bucket].
 *
 * [run] returns when the connection is over, and reports [PeerEvent.Closed] unless it was cancelled. Cancelling closes the socket at
 * once, so a read that is blocked does not keep the caller waiting.
 */
internal class PeerSession(
    private val addr: PeerAddr,
    private val transport: PeerTransport,
    private val infoHash: ByteArray,
    private val ownId: ByteArray,
    private val layout: Layout,
    private val clock: Clock,
    private val bucket: TokenBucket,
    private val limits: SessionLimits,
    private val sink: suspend (PeerEvent) -> Unit,
) {
    private val outbox = Channel<ByteArray>(Channel.UNLIMITED)

    @Volatile private var ready = false
    private val lastRead = AtomicLong()
    private val lastWrite = AtomicLong()

    /** Queues [msg] for the peer. Dropped when the session is over. */
    fun send(msg: PeerMsg) {
        outbox.trySend(encodeMessage(msg))
    }

    suspend fun run() {
        var conn: PeerConn? = null
        var reason: String
        var retry = false
        try {
            conn = transport.connect(addr, limits.connectTimeoutMs)
            // the handshake's time starts when the connection is made: a slow connect does not eat into it
            serve(conn, clock.nowMs())
            reason = "Closed"
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            // closing the connection is how a cancelled session stops its blocked read: that failure is the cancellation
            currentCoroutineContext().ensureActive()
            reason = e.message ?: "Network error"
            retry = true
        } catch (e: WireError) {
            currentCoroutineContext().ensureActive()
            reason = e.message ?: "Protocol error"
        } finally {
            conn?.close()
            outbox.close()
        }
        sink(PeerEvent.Closed(reason, retry))
    }

    /** Runs until the connection ends or something is wrong: always by an exception. */
    private suspend fun serve(
        conn: PeerConn,
        started: Long,
    ): Unit =
        coroutineScope {
            // blocking reads and writes don't see coroutine cancellation: closing the connection is what unblocks them
            launch {
                try {
                    awaitCancellation()
                } finally {
                    conn.close()
                }
            }
            launch { watch(started) }
            conn.write(encodeHandshake(infoHash, ownId))
            launch { writeLoop(conn) }
            val remote = shakeHands(conn)
            val now = clock.nowMs()
            lastRead.set(now)
            lastWrite.set(now)
            ready = true
            sink(PeerEvent.Ready(remote))
            readLoop(conn)
        }

    private suspend fun shakeHands(conn: PeerConn): ByteArray {
        val bytes = readExactly(conn, HANDSHAKE_SIZE)
        val hello = parseHandshake(bytes) ?: throw WireError("Bad handshake")
        if (!hello.infoHash.contentEquals(infoHash)) throw WireError("Wrong info hash")
        return hello.peerId
    }

    private suspend fun readExactly(
        conn: PeerConn,
        n: Int,
    ): ByteArray {
        val out = ByteArray(n)
        var got = 0
        while (got < n) {
            val r = conn.read(out, got, n - got)
            if (r < 0) throw IOException("Closed by peer")
            got += r
        }
        return out
    }

    private suspend fun readLoop(conn: PeerConn): Nothing {
        val decoder = WireDecoder(layout = layout, extensions = false)
        val state = PeerProtocolState()
        val buf = ByteArray(READ_CHUNK)
        while (true) {
            val n = conn.read(buf, 0, minOf(buf.size, bucket.maxChunk()))
            if (n < 0) throw IOException("Closed by peer")
            val now = clock.nowMs()
            lastRead.set(now)
            delay(bucket.reserve(n.toLong(), now))
            for (msg in decoder.feed(buf, 0, n)) {
                state.accept(msg)
                when (msg) {
                    PeerMsg.Choke, PeerMsg.Unchoke, is PeerMsg.Have, is PeerMsg.Bitfield, is PeerMsg.Piece -> sink(PeerEvent.Msg(msg))

                    // we never upload: these only have to be well formed, which the decoder has checked
                    else -> Unit
                }
            }
        }
    }

    private suspend fun writeLoop(conn: PeerConn) {
        for (bytes in outbox) {
            conn.write(bytes)
            lastWrite.set(clock.nowMs())
        }
    }

    /** The timers: the handshake's deadline, the silence limit and the keep-alive. */
    private suspend fun watch(started: Long): Nothing {
        while (true) {
            delay(limits.tickMs)
            val now = clock.nowMs()
            if (!ready) {
                if (now - started >= limits.handshakeMs) throw IOException("Handshake timed out")
            } else {
                if (now - lastRead.get() >= PEER_SILENCE_MS) throw IOException("Peer went silent")
                if (now - lastWrite.get() >= KEEPALIVE_MS) {
                    lastWrite.set(now)
                    send(PeerMsg.KeepAlive)
                }
            }
        }
    }

    private companion object {
        const val READ_CHUNK = 32 * 1024
    }
}
