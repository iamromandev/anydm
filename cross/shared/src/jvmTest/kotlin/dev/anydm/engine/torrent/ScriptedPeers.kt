package dev.anydm.engine.torrent

import kotlinx.coroutines.channels.Channel
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Peers that live in memory and say only what the test tells them to, so a test can put messages in an exact order and read the fake
 * clock between them: [peer] makes the peer of an address, configured before the client connects to it.
 */
internal class ScriptedTransport(
    private val infoHash: ByteArray,
) : PeerTransport {
    private val byAddr = ConcurrentHashMap<PeerAddr, ScriptedPeer>()

    fun peer(addr: PeerAddr): ScriptedPeer = byAddr.computeIfAbsent(addr) { ScriptedPeer(infoHash) }

    override suspend fun connect(
        addr: PeerAddr,
        timeoutMs: Int,
    ): PeerConn = peer(addr).open()
}

/** One scripted peer. Until a test says otherwise it is a peer that speaks the extension protocol and offers `ut_metadata`. */
internal class ScriptedPeer(
    private val infoHash: ByteArray,
) {
    /** The extension bit of its handshake. */
    @Volatile var extensions = true

    /** The id it gives `ut_metadata`; null: it offers none. */
    @Volatile var utId: Int? = FAKE_UT_METADATA

    /** The `metadata_size` it claims; null: it says none. */
    @Volatile var claim: Long? = null

    /** The peer id it gives; the client's own id makes it "ourselves". */
    @Volatile var peerId: ByteArray = ByteArray(20) { 0x61 }

    /** Sends its handshake and extension handshake as soon as the client's handshake arrives; off: the test calls [greet]. */
    @Volatile var greetAtOnce = true

    /** Everything the client sent after its handshake, over all connections. */
    val received = CopyOnWriteArrayList<PeerMsg>()
    private val openCount = AtomicInteger()
    private val closedCount = AtomicInteger()

    /** How many connections the client made to it. */
    val connects: Int get() = openCount.get()

    /** How many of them the client has closed (or it has hung up). */
    val closes: Int get() = closedCount.get()

    /** Whether a connection is open now. */
    val isOpen: Boolean get() = connects > closes

    @Volatile private var conn: Conn? = null

    /** The numbers of the metadata blocks the client asked for, in order. */
    val requests: List<Int>
        get() =
            received.filterIsInstance<PeerMsg.Extended>().filter { it.extId == FAKE_UT_METADATA }.mapNotNull {
                (decodeMetaMsg(it.payload) as? MetaMsg.Request)?.piece
            }

    private var answered = 0

    internal fun open(): PeerConn {
        val c = Conn()
        conn = c
        openCount.incrementAndGet()
        return c
    }

    /** Sends the handshake and the extension handshake over the current connection. */
    fun greet(size: Long? = claim) {
        val c = conn ?: error("the client is not connected")
        c.push(encodeHandshake(infoHash, peerId, extensions = extensions))
        if (extensions) c.push(encodeMessage(PeerMsg.Extended(0, extHandshakePayload(utId, size))))
    }

    fun send(msg: PeerMsg) {
        (conn ?: error("the client is not connected")).push(encodeMessage(msg))
    }

    /** Sends block [piece] of [info] as a data message, claiming [total] bytes in all; [mutate] spoils the block before it is sent. */
    fun sendBlock(
        info: ByteArray,
        piece: Int,
        total: Long = info.size.toLong(),
        mutate: (ByteArray) -> ByteArray = { it },
    ) {
        val from = piece * METADATA_BLOCK
        val block = mutate(info.copyOfRange(from, minOf(from + METADATA_BLOCK, info.size)))
        send(PeerMsg.Extended(OUR_ID, metaDataPayload(piece, total, block)))
    }

    /** A block of [length] bytes of junk, as a data message for [piece]. */
    fun sendJunk(
        piece: Int,
        total: Long,
        length: Int = METADATA_BLOCK,
    ) = send(PeerMsg.Extended(OUR_ID, metaDataPayload(piece, total, ByteArray(length) { 0x55 })))

    /** Answers the requests it has not answered yet with the right blocks of [info]; returns how many. */
    fun answerAsked(
        info: ByteArray,
        total: Long = info.size.toLong(),
        mutate: (ByteArray) -> ByteArray = { it },
    ): Int {
        val asked = requests
        val fresh = asked.drop(answered)
        answered = asked.size
        for (piece in fresh) sendBlock(info, piece, total, mutate)
        return fresh.size
    }

    /** Counts the requests so far as answered: [answerAsked] answers only those that come later. */
    fun markAnswered() {
        answered = requests.size
    }

    /** Ends the stream: the client reads the end of the connection. */
    fun hangUp() {
        conn?.finish()
    }

    private inner class Conn : PeerConn {
        private val inbound = Channel<ByteArray>(Channel.UNLIMITED)
        private var left = ByteArray(0)
        private var at = 0
        private var wrote = false
        private val decoder = WireDecoder(maxMessage = 1 shl 20)

        @Volatile private var closed = false

        fun push(bytes: ByteArray) {
            inbound.trySend(bytes)
        }

        fun finish() {
            inbound.close()
        }

        override suspend fun read(
            buf: ByteArray,
            off: Int,
            len: Int,
        ): Int {
            if (closed) throw IOException("closed")
            if (at >= left.size) {
                val next = inbound.receiveCatching()
                if (closed) throw IOException("closed")
                left = next.getOrNull() ?: return -1
                at = 0
            }
            val n = minOf(len, left.size - at)
            left.copyInto(buf, off, at, at + n)
            at += n
            return n
        }

        override suspend fun write(bytes: ByteArray) {
            if (closed) throw IOException("closed")
            if (!wrote) {
                wrote = true // the client's handshake
                if (greetAtOnce) greet()
                return
            }
            received.addAll(decoder.feed(bytes, 0, bytes.size))
        }

        override fun close() {
            if (!closed) {
                closed = true
                closedCount.incrementAndGet()
                inbound.close()
            }
        }
    }

    companion object {
        /** The id the client gave `ut_metadata` in its extension handshake: what a data message is sent under. */
        const val OUR_ID = 1
    }
}
