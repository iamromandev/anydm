package dev.anydm.engine.torrent

/** A peer sent bytes that break the protocol or our limits; the connection must be dropped. The only exception [WireDecoder] throws. */
class WireError(
    message: String,
) : Exception(message)

/** The peer wire messages of BEP 3 (ids 0 to 8), plus [KeepAlive] and [Unknown]. */
sealed interface PeerMsg {
    data object KeepAlive : PeerMsg

    data object Choke : PeerMsg

    data object Unchoke : PeerMsg

    data object Interested : PeerMsg

    data object NotInterested : PeerMsg

    data class Have(
        val piece: Int,
    ) : PeerMsg

    class Bitfield(
        val bytes: ByteArray,
    ) : PeerMsg {
        override fun equals(other: Any?): Boolean = other is Bitfield && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = bytes.contentHashCode()

        override fun toString(): String = "Bitfield(${bytes.size} bytes)"
    }

    data class Request(
        val piece: Int,
        val offset: Int,
        val length: Int,
    ) : PeerMsg

    class Piece(
        val piece: Int,
        val offset: Int,
        val data: ByteArray,
    ) : PeerMsg {
        override fun equals(other: Any?): Boolean =
            other is Piece && piece == other.piece && offset == other.offset && data.contentEquals(other.data)

        override fun hashCode(): Int = 31 * (31 * piece + offset) + data.contentHashCode()

        override fun toString(): String = "Piece($piece, $offset, ${data.size} bytes)"
    }

    data class Cancel(
        val piece: Int,
        val offset: Int,
        val length: Int,
    ) : PeerMsg

    /** A message with an id this client does not know (an extension); its payload is skipped, never stored. */
    data class Unknown(
        val id: Int,
    ) : PeerMsg
}

private const val ID_CHOKE = 0
private const val ID_UNCHOKE = 1
private const val ID_INTERESTED = 2
private const val ID_NOT_INTERESTED = 3
private const val ID_HAVE = 4
private const val ID_BITFIELD = 5
private const val ID_REQUEST = 6
private const val ID_PIECE = 7
private const val ID_CANCEL = 8

/** Payload bytes of a piece message besides the block: piece index and offset. */
private const val PIECE_HEADER = 8

private fun ByteArray.putInt(
    at: Int,
    v: Int,
) {
    this[at] = (v ushr 24).toByte()
    this[at + 1] = (v ushr 16).toByte()
    this[at + 2] = (v ushr 8).toByte()
    this[at + 3] = v.toByte()
}

private fun ByteArray.int(at: Int): Int =
    ((this[at].toInt() and 0xFF) shl 24) or ((this[at + 1].toInt() and 0xFF) shl 16) or
        ((this[at + 2].toInt() and 0xFF) shl 8) or (this[at + 3].toInt() and 0xFF)

private fun frame(
    id: Int,
    payload: Int,
): ByteArray {
    val out = ByteArray(5 + payload)
    out.putInt(0, payload + 1)
    out[4] = id.toByte()
    return out
}

private fun triple(
    id: Int,
    a: Int,
    b: Int,
    c: Int,
): ByteArray =
    frame(id, 12).also {
        it.putInt(5, a)
        it.putInt(9, b)
        it.putInt(13, c)
    }

/** The bytes of [msg] as sent on the wire: 4-byte big-endian length, id, payload. Does not limit sizes; the receiver does. */
fun encodeMessage(msg: PeerMsg): ByteArray =
    when (msg) {
        PeerMsg.KeepAlive -> {
            ByteArray(4)
        }

        PeerMsg.Choke -> {
            frame(ID_CHOKE, 0)
        }

        PeerMsg.Unchoke -> {
            frame(ID_UNCHOKE, 0)
        }

        PeerMsg.Interested -> {
            frame(ID_INTERESTED, 0)
        }

        PeerMsg.NotInterested -> {
            frame(ID_NOT_INTERESTED, 0)
        }

        is PeerMsg.Have -> {
            frame(ID_HAVE, 4).also { it.putInt(5, msg.piece) }
        }

        is PeerMsg.Bitfield -> {
            frame(ID_BITFIELD, msg.bytes.size).also { msg.bytes.copyInto(it, 5) }
        }

        is PeerMsg.Request -> {
            triple(ID_REQUEST, msg.piece, msg.offset, msg.length)
        }

        is PeerMsg.Cancel -> {
            triple(ID_CANCEL, msg.piece, msg.offset, msg.length)
        }

        is PeerMsg.Piece -> {
            frame(ID_PIECE, PIECE_HEADER + msg.data.size).also {
                it.putInt(5, msg.piece)
                it.putInt(9, msg.offset)
                msg.data.copyInto(it, 13)
            }
        }

        is PeerMsg.Unknown -> {
            frame(msg.id, 0)
        }
    }

/** The size of a handshake: 1 + 19 + 8 + 20 + 20. */
const val HANDSHAKE_SIZE = 68

private const val PROTOCOL = "BitTorrent protocol"
private const val ID_SIZE = 20

/** What a peer's handshake says. */
class Handshake(
    val reserved: ByteArray,
    val infoHash: ByteArray,
    val peerId: ByteArray,
)

/** Our handshake for [infoHash] and [peerId] (20 bytes each), reserved bits all zero. */
fun encodeHandshake(
    infoHash: ByteArray,
    peerId: ByteArray,
): ByteArray {
    require(infoHash.size == ID_SIZE && peerId.size == ID_SIZE) { "info hash and peer id are 20 bytes each" }
    val out = ByteArray(HANDSHAKE_SIZE)
    out[0] = PROTOCOL.length.toByte()
    PROTOCOL.encodeToByteArray().copyInto(out, 1)
    infoHash.copyInto(out, 28)
    peerId.copyInto(out, 48)
    return out
}

/** The handshake in exactly [len] bytes of [bytes] at [off], or null when it is not [HANDSHAKE_SIZE] bytes of the BitTorrent protocol. */
fun parseHandshake(
    bytes: ByteArray,
    off: Int = 0,
    len: Int = bytes.size - off,
): Handshake? {
    if (len != HANDSHAKE_SIZE || off < 0 || off > bytes.size - len) return null
    if (bytes[off].toInt() != PROTOCOL.length) return null
    val pstr = PROTOCOL.encodeToByteArray()
    if (pstr.indices.any { bytes[off + 1 + it] != pstr[it] }) return null
    return Handshake(
        reserved = bytes.copyOfRange(off + 20, off + 28),
        infoHash = bytes.copyOfRange(off + 28, off + 48),
        peerId = bytes.copyOfRange(off + 48, off + 68),
    )
}

/**
 * Turns a stream of bytes from one peer into messages. Partial messages are buffered across calls; the buffer grows only with
 * bytes actually received and never past the declared length, which is capped by [maxMessage] (payload including its id) before
 * anything is stored. A piece message carries at most one block. Messages with unknown ids are reported and skipped, their payload
 * never stored. With a [layout], blocks, `have` and `bitfield` are also checked against the torrent, so nothing the decoder returns
 * can name bytes outside its files: a `piece` must be exactly one block, a `request` or `cancel` (never answered) only has to lie
 * inside its piece. Order rules, such as the `bitfield` coming first, are [PeerProtocolState]'s.
 *
 * Not thread safe: one decoder per connection, fed from one reader.
 */
class WireDecoder(
    private val maxMessage: Int = 1 shl 20,
    private val layout: Layout? = null,
) {
    private var buf = ByteArray(32)
    private var fill = 0

    /** The payload length (id included) of the frame being read; -1 until its 4-byte prefix is in. */
    private var length = -1
    private var checked = false
    private var skip = 0L
    private var failure: WireError? = null

    /** The messages completed by [len] bytes of [bytes] at [off]. Throws [WireError] and nothing else; after that it keeps throwing. */
    fun feed(
        bytes: ByteArray,
        off: Int = 0,
        len: Int = bytes.size - off,
    ): List<PeerMsg> {
        failure?.let { throw it }
        try {
            if (off < 0 || len < 0 || off > bytes.size - len) throw WireError("Bad buffer range")
            return read(bytes, off, off + len)
        } catch (e: WireError) {
            failure = e
            buf = ByteArray(0)
            throw e
        }
    }

    private fun read(
        bytes: ByteArray,
        from: Int,
        end: Int,
    ): List<PeerMsg> {
        val out = ArrayList<PeerMsg>()
        var at = from
        while (at < end) {
            if (skip > 0) {
                val n = minOf(skip, (end - at).toLong()).toInt()
                skip -= n
                at += n
                continue
            }
            val need = need()
            val take = minOf(need - fill, end - at)
            append(bytes, at, take)
            at += take
            if (fill < need) break
            when {
                length < 0 -> prefix(out)
                !checked -> idByte(out)
                else -> complete(out)
            }
        }
        return out
    }

    private fun need(): Int =
        when {
            length < 0 -> 4
            !checked -> 5
            else -> 4 + length
        }

    private fun append(
        bytes: ByteArray,
        at: Int,
        n: Int,
    ) {
        if (fill + n > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, fill + n).coerceAtMost(4 + maxMessage).coerceAtLeast(fill + n))
        bytes.copyInto(buf, fill, at, at + n)
        fill += n
    }

    private fun reset() {
        fill = 0
        length = -1
        checked = false
        if (buf.size > 4096) buf = ByteArray(32)
    }

    private fun prefix(out: MutableList<PeerMsg>) {
        val declared = buf.int(0)
        if (declared < 0 || declared > maxMessage) throw WireError("Message too long")
        if (declared == 0) {
            out += PeerMsg.KeepAlive
            reset()
        } else {
            length = declared
        }
    }

    /** Judges the declared length against the id as soon as the id is known, before any payload is waited for. */
    private fun idByte(out: MutableList<PeerMsg>) {
        val id = buf[4].toInt() and 0xFF
        val ok =
            when (id) {
                ID_CHOKE, ID_UNCHOKE, ID_INTERESTED, ID_NOT_INTERESTED -> length == 1
                ID_HAVE -> length == 5
                ID_BITFIELD -> layout == null || length - 1 == (layout.pieceCount + 7) / 8
                ID_REQUEST, ID_CANCEL -> length == 13
                ID_PIECE -> length > 1 + PIECE_HEADER && length <= 1 + PIECE_HEADER + BLOCK
                else -> true
            }
        if (!ok) throw WireError("Bad length for message $id")
        if (id > ID_CANCEL) {
            out += PeerMsg.Unknown(id)
            skip = length - 1L
            reset()
            return
        }
        checked = true
        if (fill == 4 + length) complete(out)
    }

    private fun complete(out: MutableList<PeerMsg>) {
        out += decode()
        reset()
    }

    private fun decode(): PeerMsg =
        when (val id = buf[4].toInt() and 0xFF) {
            ID_CHOKE -> {
                PeerMsg.Choke
            }

            ID_UNCHOKE -> {
                PeerMsg.Unchoke
            }

            ID_INTERESTED -> {
                PeerMsg.Interested
            }

            ID_NOT_INTERESTED -> {
                PeerMsg.NotInterested
            }

            ID_HAVE -> {
                have(buf.int(5))
            }

            ID_BITFIELD -> {
                bitfield(buf.copyOfRange(5, 4 + length))
            }

            ID_REQUEST -> {
                PeerMsg.Request(buf.int(5), buf.int(9), buf.int(13)).also { checkRequested(it.piece, it.offset, it.length) }
            }

            ID_CANCEL -> {
                PeerMsg.Cancel(buf.int(5), buf.int(9), buf.int(13)).also { checkRequested(it.piece, it.offset, it.length) }
            }

            ID_PIECE -> {
                val data = buf.copyOfRange(13, 4 + length)
                checkBlock(buf.int(5), buf.int(9), data.size)
                PeerMsg.Piece(buf.int(5), buf.int(9), data)
            }

            else -> {
                throw WireError("Unexpected message $id")
            }
        }

    private fun have(piece: Int): PeerMsg {
        if (piece < 0 || (layout != null && piece >= layout.pieceCount)) throw WireError("Piece out of range")
        return PeerMsg.Have(piece)
    }

    private fun bitfield(bytes: ByteArray): PeerMsg {
        if (layout != null && PieceSet.fromPeerBitfield(layout.pieceCount, bytes) == null) throw WireError("Bad bitfield")
        return PeerMsg.Bitfield(bytes)
    }

    /**
     * Refuses a `request` or `cancel` with a negative number, a length that is empty or over a block, or, with a layout, a range that
     * is not inside its piece. We never answer requests, so anything inside the piece is accepted, aligned or not.
     */
    private fun checkRequested(
        piece: Int,
        offset: Int,
        len: Int,
    ) {
        if (piece < 0 || offset < 0 || len < 1 || len > BLOCK) throw WireError("Bad block")
        if (layout != null && (piece >= layout.pieceCount || offset.toLong() + len > layout.pieceSize(piece))) {
            throw WireError("Block out of range")
        }
    }

    /** Refuses a block that has a negative number, is empty or over a block, or with a layout is not exactly one of its blocks. */
    private fun checkBlock(
        piece: Int,
        offset: Int,
        len: Int,
    ) {
        if (piece < 0 || offset < 0 || len < 1 || len > BLOCK) throw WireError("Bad block")
        if (layout != null && layout.blockAt(piece, offset, len) == null) throw WireError("Block out of range")
    }
}

/**
 * The order rules of BEP 3 that a single message cannot show, for one connection. The torrent run passes every message the
 * [WireDecoder] returns, in order, to [accept] and drops the peer when it throws [WireError].
 *
 * Today that is one rule: a `bitfield` is only valid as the first message after the handshake, and only once. A [PeerMsg.KeepAlive]
 * or [PeerMsg.Unknown] message does not count as the first message: they say nothing about the peer's pieces and some peers send one
 * first, so refusing them would lose real peers.
 *
 * Not thread safe: one per connection.
 */
class PeerProtocolState {
    private var started = false

    /** Notes [msg] as the next message from the peer. Throws [WireError] for a `bitfield` that is not the first real message. */
    fun accept(msg: PeerMsg) {
        if (msg == PeerMsg.KeepAlive || msg is PeerMsg.Unknown) return
        if (msg is PeerMsg.Bitfield && started) throw WireError("Bitfield is only valid as the first message")
        started = true
    }
}
