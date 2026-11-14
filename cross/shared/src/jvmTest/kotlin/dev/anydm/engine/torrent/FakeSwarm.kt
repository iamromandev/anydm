package dev.anydm.engine.torrent

import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/** What one fake peer does; every switch is off by default, so the default peer is an honest seed that unchokes at once. */
internal data class PeerScript(
    /** The pieces it says it has; null for all of them. */
    val has: Set<Int>? = null,
    /** Pieces whose first block it corrupts (see [corruptTimes]). */
    val corrupt: Set<Int> = emptySet(),
    /** How many times a block of a [corrupt] piece is corrupted, then it is sent right. */
    val corruptTimes: Int = Int.MAX_VALUE,
    /** A cap on the block bytes it sends per connection. */
    val bytesPerSec: Long? = null,
    /** Closes the connection after it has answered this many requests on it. */
    val dropAfterAnswers: Int? = null,
    /** Closes the connection at its first request, answering nothing. */
    val closeAtFirstRequest: Boolean = false,
    /** Answers the handshake with another info hash. */
    val wrongInfoHash: Boolean = false,
    /** Answers the handshake with bytes that are no handshake. */
    val garbageHandshake: Boolean = false,
    /** Handshakes and then sends nothing, whatever it receives. */
    val silent: Boolean = false,
    /** Sends a choke after it has answered this many requests, and answers nothing after that; 0: chokes at its first request. */
    val chokeAfterAnswers: Int? = null,
    /** Accepts requests and never answers them. */
    val neverAnswer: Boolean = false,
    /** Does not unchoke until [FakePeer.unchoke] is called. */
    val holdUnchoke: Boolean = false,
    /** Sends a block nobody asked for right after the unchoke. */
    val sendUnrequested: Boolean = false,
    /** Sends a block of a piece that does not exist right after the unchoke. */
    val sendOutOfRange: Boolean = false,
    /** Sends a second bitfield right after the first. */
    val twoBitfields: Boolean = false,
    /** Sends no bitfield, only a `have` message for each of [has] right after the handshake. */
    val haveInsteadOfBitfield: Boolean = false,
    /** Sends a request of its own right after the handshake. */
    val sendRequest: Boolean = false,
    /** Sends a length prefix over the limit right after the bitfield. */
    val sendOversized: Boolean = false,
    /** Sends this many chokes right after the bitfield. */
    val chokeFlood: Int = 0,
    /** Sends this many `have` messages for piece 0 right after each unchoke. */
    val haveFlood: Int = 0,
    /** Its peer id; random when null. */
    val peerId: ByteArray? = null,
    /** Waits this long before it answers each request, in real time. */
    val answerDelayMs: Long = 0,
    /**
     * The `info` bytes it serves over `ut_metadata` (BEP 9), and with it the extension bit in its handshake and an extension handshake
     * right after. Null: it does not speak the extension protocol at all.
     */
    val serveMetadata: ByteArray? = null,
    /** Corrupts the first byte of every metadata block it sends. */
    val metadataLie: Boolean = false,
    /** Closes the connection after it has sent this many metadata blocks on it. */
    val metadataDropAfter: Int? = null,
    /** Claims this `metadata_size` (in the extension handshake and in every data message) instead of the real one. */
    val metadataClaimSize: Long? = null,
    /** Answers every metadata request with a reject. */
    val metadataReject: Boolean = false,
    /** Accepts metadata requests and never answers them. */
    val metadataNeverAnswer: Boolean = false,
    /** Speaks the extension protocol but offers no `ut_metadata`. */
    val noUtMetadata: Boolean = false,
    /** Sends a data message with a block of this many bytes, whatever was asked, instead of the right one (a block of the wrong length). */
    val metadataBlockBytes: Int? = null,
    /** Sends the extension message with the metadata request number (and the data) under this id instead of the one the client gave. */
    val metadataAnswerUnderId: Int? = null,
)

/** The id this fake peer gives `ut_metadata`; the client's own is 1, so a mix-up of the two shows. */
internal const val FAKE_UT_METADATA = 3

/** A torrent a fake peer serves: the generated file with its content, and what the reader makes of it. */
internal class Served(
    val torrent: BuiltTorrent,
    val meta: TorrentMeta,
)

/** One fake seeding peer on a loopback port, serving the content of [served] (a handshake picks the torrent by its info hash). Records what the client sends it. */
internal class FakePeer(
    private val served: List<Served>,
    val script: PeerScript = PeerScript(),
    seed: Long = 0,
) : AutoCloseable {
    constructor(torrent: BuiltTorrent, meta: TorrentMeta, script: PeerScript = PeerScript(), seed: Long = 0) :
        this(listOf(Served(torrent, meta)), script, seed)

    private val server = ServerSocket(0, 100, InetAddress.getLoopbackAddress())
    val addr = PeerAddr("127.0.0.1", server.localPort)
    val id: ByteArray = script.peerId ?: ("-FK0001-".encodeToByteArray() + kotlin.random.Random(seed).nextBytes(12))

    /** Everything the client sent after its handshake, in order, over all connections. */
    val received = CopyOnWriteArrayList<PeerMsg>()
    val requests: List<Block> get() = received.filterIsInstance<PeerMsg.Request>().map { Block(it.piece, it.offset, it.length) }
    val cancels: List<Block> get() = received.filterIsInstance<PeerMsg.Cancel>().map { Block(it.piece, it.offset, it.length) }
    val keepAlives: Int get() = received.count { it == PeerMsg.KeepAlive }

    private val acceptedCount = AtomicInteger()
    private val openCount = AtomicInteger()
    private val peakOpen = AtomicInteger()
    private val answered = AtomicInteger()
    val blockBytesSent = AtomicLong()
    val accepted: Int get() = acceptedCount.get()
    val open: Int get() = openCount.get()
    val peak: Int get() = peakOpen.get()

    /** Requests answered with a block. */
    val answers: Int get() = answered.get()

    /** The numbers of the metadata blocks the client asked for, in order, over all connections. */
    val metadataRequests = CopyOnWriteArrayList<Int>()

    /** Metadata data messages sent. */
    val metadataAnswers: Int get() = metaAnswered.get()
    private val metaAnswered = AtomicInteger()

    private val sockets = CopyOnWriteArrayList<Socket>()
    private val waiting = CopyOnWriteArrayList<Socket>()
    private val corrupted = java.util.concurrent.ConcurrentHashMap<Int, AtomicInteger>()
    private val acceptor =
        thread(isDaemon = true, name = "fake-peer-accept") {
            while (!server.isClosed) {
                val s =
                    try {
                        server.accept()
                    } catch (_: IOException) {
                        break
                    }
                acceptedCount.incrementAndGet()
                // counted as open from the moment it is accepted, so "accepted and no longer open" always means it was closed
                peakOpen.accumulateAndGet(openCount.incrementAndGet()) { a, b -> maxOf(a, b) }
                sockets.add(s)
                thread(isDaemon = true, name = "fake-peer") { serve(s) }
            }
        }

    /** Sends the unchoke that [PeerScript.holdUnchoke] held back. */
    fun unchoke() {
        for (s in waiting) runCatching { write(s, PeerMsg.Unchoke) }
        waiting.clear()
    }

    /** Sends a keep-alive on every open connection. */
    fun sendKeepAlive() {
        for (s in sockets) runCatching { write(s, PeerMsg.KeepAlive) }
    }

    /** Closes every connection (the listener stays up). */
    fun dropAll() {
        sockets.forEach { runCatching { it.close() } }
    }

    private val locks = java.util.concurrent.ConcurrentHashMap<Socket, Any>()

    private fun write(
        s: Socket,
        msg: PeerMsg,
    ) = write(s, encodeMessage(msg))

    private fun write(
        s: Socket,
        bytes: ByteArray,
    ) {
        synchronized(locks.computeIfAbsent(s) { Any() }) {
            s.getOutputStream().write(bytes)
            s.getOutputStream().flush()
        }
    }

    private fun readFully(
        input: InputStream,
        n: Int,
    ): ByteArray? {
        val out = ByteArray(n)
        var got = 0
        while (got < n) {
            val r = input.read(out, got, n - got)
            if (r < 0) return null
            got += r
        }
        return out
    }

    private fun content(
        of: Served,
        piece: Int,
        offset: Int,
        len: Int,
    ): ByteArray {
        val from = piece.toLong() * of.torrent.pieceLength + offset
        return of.torrent.content.copyOfRange(from.toInt(), from.toInt() + len)
    }

    private fun serve(s: Socket) {
        try {
            val hello = readFully(s.getInputStream(), HANDSHAKE_SIZE)?.let { parseHandshake(it) } ?: return
            val of = served.firstOrNull { it.meta.infoHash.contentEquals(hello.infoHash) } ?: return
            val meta = of.meta
            when {
                script.garbageHandshake -> {
                    write(s, ByteArray(HANDSHAKE_SIZE) { 7 })
                    return
                }

                script.wrongInfoHash -> {
                    write(s, encodeHandshake(ByteArray(20) { 9 }, id))
                    s.getInputStream().read()
                    return
                }

                else -> {
                    write(s, encodeHandshake(meta.infoHash, id, extensions = script.serveMetadata != null))
                }
            }
            script.serveMetadata?.let { info ->
                val size = script.metadataClaimSize ?: info.size.toLong()
                write(s, PeerMsg.Extended(0, extHandshakePayload(FAKE_UT_METADATA.takeUnless { script.noUtMetadata }, size)))
            }
            if (script.silent) {
                drain(s.getInputStream())
                return
            }
            val have = PieceSet.of(meta.pieceCount, script.has ?: (0 until meta.pieceCount))
            if (!script.haveInsteadOfBitfield) {
                write(s, PeerMsg.Bitfield(have.toBytes()))
                if (script.twoBitfields) write(s, PeerMsg.Bitfield(have.toBytes()))
            } else {
                for (i in 0 until have.size) if (have.has(i)) write(s, PeerMsg.Have(i))
            }
            repeat(script.chokeFlood) { write(s, PeerMsg.Choke) }
            if (script.sendRequest) write(s, PeerMsg.Request(0, 0, BLOCK))
            if (script.sendOversized) write(s, byteArrayOf(0x7F, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()))
            talk(s, of, have)
        } catch (_: IOException) {
            // the client closed, or we did
        } finally {
            runCatching { s.close() }
            openCount.decrementAndGet()
        }
    }

    /** Takes in what the client sends, records it and answers nothing. */
    private fun drain(input: InputStream) {
        val decoder = WireDecoder()
        val buf = ByteArray(1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) return
            received.addAll(decoder.feed(buf, 0, n))
        }
    }

    private fun talk(
        s: Socket,
        of: Served,
        have: PieceSet,
    ) {
        val decoder = WireDecoder()
        val buf = ByteArray(16 * 1024)
        val input = s.getInputStream()
        var onThisConnection = 0
        var metaOnThisConnection = 0
        var clientMetaId: Int? = null
        var choked = false
        val started = System.nanoTime()
        var sent = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) return
            for (msg in decoder.feed(buf, 0, n)) {
                // waiting before it is seen in [received]: whoever sees the one may call unchoke() and expect to be heard
                if (msg == PeerMsg.Interested && script.holdUnchoke) waiting.add(s)
                received.add(msg)
                when (msg) {
                    PeerMsg.Interested -> {
                        if (!script.holdUnchoke) unchoked(s, of, have)
                    }

                    is PeerMsg.Request -> {
                        if (script.closeAtFirstRequest) return
                        if (script.chokeAfterAnswers == 0 && !choked) {
                            choked = true
                            write(s, PeerMsg.Choke)
                        }
                        if (script.neverAnswer || choked) continue
                        if (script.answerDelayMs > 0) Thread.sleep(script.answerDelayMs)
                        var data = content(of, msg.piece, msg.offset, msg.length)
                        val bad = msg.piece in script.corrupt && msg.offset == 0
                        if (bad && corrupted.computeIfAbsent(msg.piece) { AtomicInteger() }.getAndIncrement() < script.corruptTimes) {
                            data = data.copyOf().also { it[0] = (it[0] + 1).toByte() }
                        }
                        write(s, PeerMsg.Piece(msg.piece, msg.offset, data))
                        answered.incrementAndGet()
                        blockBytesSent.addAndGet(data.size.toLong())
                        onThisConnection++
                        sent += data.size
                        script.bytesPerSec?.let { rate ->
                            val due = started + sent * 1_000_000_000L / rate
                            val wait = (due - System.nanoTime()) / 1_000_000
                            if (wait > 0) Thread.sleep(wait)
                        }
                        if (script.chokeAfterAnswers == onThisConnection) {
                            choked = true
                            write(s, PeerMsg.Choke)
                        }
                        if (script.dropAfterAnswers == onThisConnection) return
                    }

                    is PeerMsg.Extended -> {
                        if (msg.extId == 0) {
                            clientMetaId = decodeExtHandshake(msg.payload)?.utMetadata
                        } else if (msg.extId == FAKE_UT_METADATA) {
                            val request = decodeMetaMsg(msg.payload) as? MetaMsg.Request ?: continue
                            metadataRequests.add(request.piece)
                            if (script.metadataNeverAnswer) continue
                            if (script.answerDelayMs > 0) Thread.sleep(script.answerDelayMs)
                            val to = script.metadataAnswerUnderId ?: clientMetaId ?: continue
                            val info = script.serveMetadata ?: continue
                            if (script.metadataReject) {
                                write(s, PeerMsg.Extended(to, metaRejectPayload(request.piece)))
                                continue
                            }
                            val from = request.piece * METADATA_BLOCK
                            if (from >= info.size) {
                                write(s, PeerMsg.Extended(to, metaRejectPayload(request.piece)))
                                continue
                            }
                            var block = info.copyOfRange(from, minOf(from + METADATA_BLOCK, info.size))
                            script.metadataBlockBytes?.let { block = ByteArray(it) }
                            if (script.metadataLie) block = block.copyOf().also { if (it.isNotEmpty()) it[0] = (it[0] + 1).toByte() }
                            val total = script.metadataClaimSize ?: info.size.toLong()
                            write(s, PeerMsg.Extended(to, metaDataPayload(request.piece, total, block)))
                            metaAnswered.incrementAndGet()
                            metaOnThisConnection++
                            if (script.metadataDropAfter == metaOnThisConnection) return
                        }
                    }

                    else -> {
                        // nothing else needs an answer
                    }
                }
            }
        }
    }

    private fun unchoked(
        s: Socket,
        of: Served,
        have: PieceSet,
    ) {
        write(s, PeerMsg.Unchoke)
        repeat(script.haveFlood) { write(s, PeerMsg.Have(0)) }
        if (script.sendUnrequested) write(s, PeerMsg.Piece(0, 0, content(of, 0, 0, BLOCK.coerceAtMost(of.meta.pieceSize(0).toInt()))))
        if (script.sendOutOfRange) write(s, PeerMsg.Piece(of.meta.pieceCount + 5, 0, ByteArray(BLOCK)))
    }

    override fun close() {
        runCatching { server.close() }
        dropAll()
        acceptor.join(2_000)
    }
}

/** Fake seeds, one per script, serving [served]. */
internal class FakeSwarm(
    val served: List<Served>,
    scripts: List<PeerScript>,
) : AutoCloseable {
    constructor(torrent: BuiltTorrent, meta: TorrentMeta, scripts: List<PeerScript>) : this(listOf(Served(torrent, meta)), scripts)

    val peers = scripts.mapIndexed { i, s -> FakePeer(served, s, seed = i.toLong() + 1) }
    val addrs: List<PeerAddr> get() = peers.map { it.addr }

    /** How many requests were made for [piece], over all peers. */
    fun requestsFor(piece: Int): Int = peers.sumOf { p -> p.requests.count { it.piece == piece } }

    override fun close() {
        peers.forEach { it.close() }
    }
}
