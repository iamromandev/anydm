package dev.anydm.engine.torrent

import kotlin.io.encoding.Base64

/**
 * A set of piece indexes below [size], kept as a bitfield in the BEP 3 layout: piece 0 is the high bit of byte 0, and the unused
 * low bits of the last byte are always zero. Immutable.
 */
class PieceSet private constructor(
    val size: Int,
    private val bits: ByteArray,
    /** How many pieces are in the set. */
    val count: Int,
) {
    private constructor(size: Int, bits: ByteArray) : this(size, bits, bits.sumOf { (it.toInt() and 0xFF).countOneBits() })

    /** Whether every piece below [size] is in the set. True for an empty torrent. */
    val isComplete: Boolean get() = count == size

    /** Whether piece [i] is in the set; false for an index outside `0 until size`. */
    fun has(i: Int): Boolean = i in 0 until size && bits[i shr 3].toInt() and (0x80 shr (i and 7)) != 0

    /** This set plus piece [i]; the same set when [i] is already in it or outside `0 until size`. */
    fun with(i: Int): PieceSet {
        if (i !in 0 until size || has(i)) return this
        val copy = bits.copyOf()
        copy[i shr 3] = (copy[i shr 3].toInt() or (0x80 shr (i and 7))).toByte()
        return PieceSet(size, copy, count + 1)
    }

    /**
     * The bytes of the pieces in the set: the sum of their sizes in [meta], the short last piece included. 0 when this set is
     * not for [meta]'s piece count, so a set from another torrent never claims bytes.
     */
    fun doneBytes(meta: TorrentMeta): Long {
        if (size != meta.pieceCount || count == 0) return 0
        val last = size - 1
        val shortBy = if (has(last)) meta.pieceLength - meta.pieceSize(last) else 0
        return count * meta.pieceLength - shortBy
    }

    /**
     * Whether [theirs], a bitfield in the layout of [toBytes], holds a piece this set lacks. Compares in place, with no copy of this
     * set's bits, and only as far as both are long.
     */
    fun lacksAnyOf(theirs: ByteArray): Boolean {
        for (i in 0 until minOf(bits.size, theirs.size)) if (theirs[i].toInt() and bits[i].toInt().inv() and 0xFF != 0) return true
        return false
    }

    /** The bitfield as BEP 3 sends it: `(size + 7) / 8` bytes. A copy. */
    fun toBytes(): ByteArray = bits.copyOf()

    /** The bitfield as padded standard base64, for `downloads.json`. */
    fun toBase64(): String = Base64.encode(bits)

    override fun equals(other: Any?): Boolean = other is PieceSet && size == other.size && bits.contentEquals(other.bits)

    override fun hashCode(): Int = 31 * size + bits.contentHashCode()

    companion object {
        private fun byteCount(size: Int): Int = (size.toLong() + 7).toInt() / 8

        /** A set of [size] pieces with none in it. */
        fun empty(size: Int): PieceSet {
            val n = size.coerceAtLeast(0)
            return PieceSet(n, ByteArray(byteCount(n)))
        }

        /**
         * The set of [size] pieces holding [indices]; duplicates and indexes outside `0 until size` are skipped, as in [with]. One pass,
         * so it costs O(size + indices) where adding the pieces with [with] one at a time costs O(size) each.
         */
        fun of(
            size: Int,
            indices: Iterable<Int>,
        ): PieceSet {
            val n = size.coerceAtLeast(0)
            val bits = ByteArray(byteCount(n))
            for (i in indices) if (i in 0 until n) bits[i shr 3] = (bits[i shr 3].toInt() or (0x80 shr (i and 7))).toByte()
            return PieceSet(n, bits)
        }

        /**
         * The set a peer's `bitfield` message describes for [size] pieces, or null when the message is not exactly
         * `(size + 7) / 8` bytes or has a spare bit set: both are protocol violations.
         */
        fun fromPeerBitfield(
            size: Int,
            bytes: ByteArray,
        ): PieceSet? {
            if (size < 0 || bytes.size != byteCount(size)) return null
            if (spareMask(size) and bytes.lastOrNull().toInt() != 0) return null
            return PieceSet(size, bytes.copyOf())
        }

        /**
         * The set stored by [toBase64] for [size] pieces, or null when [text] is not padded base64 of exactly the right number of
         * bytes. The length is checked before decoding, so a huge string costs nothing. Spare bits are cleared, not refused.
         */
        fun fromBase64(
            size: Int,
            text: String,
        ): PieceSet? {
            if (size < 0) return null
            val bytes = byteCount(size)
            if (text.length != (bytes + 2) / 3 * 4) return null
            val decoded =
                try {
                    Base64.decode(text)
                } catch (_: IllegalArgumentException) {
                    return null
                }
            if (decoded.size != bytes) return null
            if (bytes > 0) decoded[bytes - 1] = (decoded[bytes - 1].toInt() and spareMask(size).inv()).toByte()
            return PieceSet(size, decoded)
        }

        /** The unused low bits of the last byte for [size] pieces. */
        private fun spareMask(size: Int): Int = (1 shl ((8 - size % 8) % 8)) - 1

        private fun Byte?.toInt(): Int = (this?.let { it.toInt() and 0xFF }) ?: 0
    }
}
