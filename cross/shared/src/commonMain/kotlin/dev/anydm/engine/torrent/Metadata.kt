package dev.anydm.engine.torrent

/** The size of a `ut_metadata` block (BEP 9): 16 KiB; only the last block of the metadata is shorter. */
const val METADATA_BLOCK = 16 * 1024

/**
 * The most metadata bytes a peer may claim; a larger claim is refused before anything is allocated. A little under
 * [MAX_TORRENT_BYTES]: the metadata is saved inside a `.torrent` (see [torrentFileBytes]), which must still be readable.
 */
const val MAX_METADATA_BYTES = MAX_TORRENT_BYTES - 1024

private const val UT_METADATA = "ut_metadata"
private const val MSG_REQUEST = 0L
private const val MSG_DATA = 1L
private const val MSG_REJECT = 2L

/** What a peer's extension handshake (BEP 10) says about metadata: [utMetadata] is the id it wants our requests sent under. */
class ExtHandshake(
    /** The id the peer gave `ut_metadata`; null when it does not offer it (absent, 0 which means "off", or not an id). */
    val utMetadata: Int?,
    /** The size of the metadata it claims; any number, to be judged by the caller (it is not trusted). */
    val metadataSize: Long?,
)

/** Our extension handshake: `ut_metadata` under [ourUtMetadataId], and no `metadata_size` (we serve no metadata). */
fun encodeExtHandshake(ourUtMetadataId: Int = 1): ByteArray =
    encodeBencode(
        BValue.BDict(mapOf("m" to BValue.BDict(mapOf(UT_METADATA to BValue.BInt(ourUtMetadataId.toLong()))))),
    )

/** The extension handshake in [payload], or null when it is not one bencoded dictionary. Fields of the wrong type are left out. */
fun decodeExtHandshake(payload: ByteArray): ExtHandshake? {
    val root = (decodeBencode(payload, MAX_HEADER_DEPTH, MAX_HANDSHAKE_NODES) as? DecodeResult.Ok)?.value as? BValue.BDict ?: return null
    val id = ((root.entries["m"] as? BValue.BDict)?.entries?.get(UT_METADATA) as? BValue.BInt)?.v
    return ExtHandshake(
        utMetadata = id?.takeIf { it in 1..MAX_EXT_ID }?.toInt(),
        metadataSize = (root.entries["metadata_size"] as? BValue.BInt)?.v,
    )
}

/** What a peer's bencoded header may hold: these messages are small, so a hostile one is cut off early. */
private const val MAX_HEADER_DEPTH = 4
private const val MAX_HEADER_NODES = 64
private const val MAX_HANDSHAKE_NODES = 10_000

/** The extension id is one byte; 0 is the handshake itself. */
private const val MAX_EXT_ID = 255L

/** A `ut_metadata` message (BEP 9). */
sealed interface MetaMsg {
    data class Request(
        val piece: Int,
    ) : MetaMsg

    class Data(
        val piece: Int,
        /** The total metadata size the sender claims. */
        val total: Long,
        val block: ByteArray,
    ) : MetaMsg {
        override fun equals(other: Any?): Boolean =
            other is Data && piece == other.piece && total == other.total && block.contentEquals(other.block)

        override fun hashCode(): Int = 31 * (31 * piece + total.hashCode()) + block.contentHashCode()

        override fun toString(): String = "Data($piece, $total, ${block.size} bytes)"
    }

    data class Reject(
        val piece: Int,
    ) : MetaMsg
}

/** The payload of a request for block [piece]. */
fun encodeMetaRequest(piece: Int): ByteArray =
    encodeBencode(BValue.BDict(mapOf("msg_type" to BValue.BInt(MSG_REQUEST), "piece" to BValue.BInt(piece.toLong()))))

/**
 * The `ut_metadata` message in [payload]: a bencoded dictionary, and for data the raw block after its end. Null when it is none of
 * the three, or has no usable `piece` (or, for data, `total_size`). Never throws.
 */
fun decodeMetaMsg(payload: ByteArray): MetaMsg? {
    val head = decodeBencodePrefix(payload, MAX_HEADER_DEPTH, MAX_HEADER_NODES) ?: return null
    val dict = (head.value as? BValue.BDict)?.entries ?: return null
    val type = (dict["msg_type"] as? BValue.BInt)?.v ?: return null
    val piece = (dict["piece"] as? BValue.BInt)?.v?.takeIf { it in 0..Int.MAX_VALUE } ?: return null
    return when (type) {
        MSG_REQUEST -> {
            MetaMsg.Request(piece.toInt())
        }

        MSG_REJECT -> {
            MetaMsg.Reject(piece.toInt())
        }

        MSG_DATA -> {
            val total = (dict["total_size"] as? BValue.BInt)?.v?.takeIf { it >= 0 } ?: return null
            MetaMsg.Data(piece.toInt(), total, payload.copyOfRange(head.length, payload.size))
        }

        else -> {
            null
        }
    }
}

/**
 * The blocks of a torrent's metadata as they arrive, from any peer, and the check against the info hash. The caller has judged the
 * claimed [size] first (1 to [MAX_METADATA_BYTES]): a size outside that is a programming error, so nothing larger is ever allocated.
 */
class MetadataAssembler(
    private val size: Long,
    private val infoHash: ByteArray,
) {
    init {
        require(size in 1..MAX_METADATA_BYTES) { "metadata size out of range" }
        require(infoHash.size == 20) { "an info hash is 20 bytes" }
    }

    private val blocks = arrayOfNulls<ByteArray>(((size + METADATA_BLOCK - 1) / METADATA_BLOCK).toInt())

    /** How many bytes of blocks are held. */
    var heldBytes: Long = 0
        private set

    /** Whether block [piece] is held. */
    fun holds(piece: Int): Boolean = piece in blocks.indices && blocks[piece] != null

    /** How many blocks the metadata has. */
    val blockCount: Int get() = blocks.size

    /** The numbers of the blocks not held yet. */
    fun missing(): List<Int> = blocks.indices.filter { blocks[it] == null }

    private fun expected(piece: Int): Int =
        if (piece ==
            blocks.size - 1
        ) {
            (size - piece.toLong() * METADATA_BLOCK).toInt()
        } else {
            METADATA_BLOCK
        }

    /**
     * Stores [block] as block [piece]. False when there is no such block or it is not exactly the right length (16 KiB, except the
     * last, which has what is left); then nothing is stored. A block already held stays as it is.
     */
    fun accept(
        piece: Int,
        block: ByteArray,
    ): Boolean {
        if (piece < 0 || piece >= blocks.size || block.size != expected(piece)) return false
        if (blocks[piece] == null) {
            blocks[piece] = block
            heldBytes += block.size
        }
        return true
    }

    /** True when every block is held (whether or not they are right: see [verified]). */
    fun complete(): Boolean = blocks.all { it != null }

    /**
     * The metadata when every block is held and its SHA-1 is the info hash. When all are held and it is not, every block is dropped (the
     * data is bad and nobody can say which block made it so) and the result is null; null too while blocks are missing.
     */
    fun verified(): ByteArray? {
        if (!complete()) return null
        val out = ByteArray(size.toInt())
        for ((i, b) in blocks.withIndex()) b!!.copyInto(out, i * METADATA_BLOCK)
        if (sha1(out).contentEquals(infoHash)) return out
        blocks.fill(null)
        heldBytes = 0
        return null
    }
}

/**
 * The bytes of a `.torrent` file for [info], the bencoded `info` dictionary exactly as it was received (copied, never re-encoded, so
 * its SHA-1 is still the info hash), with [trackers]: the first as `announce` and every one as a tier of its own in `announce-list`.
 * The file is never over [maxBytes] (what this client reads): when the trackers would make it so, they are left out.
 */
fun torrentFileBytes(
    info: ByteArray,
    trackers: List<String>,
    maxBytes: Int = MAX_TORRENT_BYTES,
): ByteArray {
    if (trackers.isNotEmpty()) {
        val with = wrap(info, trackers)
        if (with.size <= maxBytes) return with
    }
    return wrap(info, emptyList())
}

private fun wrap(
    info: ByteArray,
    trackers: List<String>,
): ByteArray {
    val parts = ArrayList<ByteArray>()
    parts += "d".encodeToByteArray()
    if (trackers.isNotEmpty()) {
        parts += encodeBencode(BValue.BBytes("announce".encodeToByteArray()))
        parts += encodeBencode(BValue.BBytes(trackers.first().encodeToByteArray()))
        parts += encodeBencode(BValue.BBytes("announce-list".encodeToByteArray()))
        parts += encodeBencode(BValue.BList(trackers.map { BValue.BList(listOf(BValue.BBytes(it.encodeToByteArray()))) }))
    }
    parts += encodeBencode(BValue.BBytes("info".encodeToByteArray()))
    parts += info
    parts += "e".encodeToByteArray()
    val out = ByteArray(parts.sumOf { it.size })
    var at = 0
    for (p in parts) {
        p.copyInto(out, at)
        at += p.size
    }
    return out
}
