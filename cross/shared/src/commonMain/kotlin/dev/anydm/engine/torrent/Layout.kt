package dev.anydm.engine.torrent

/** A run of [length] bytes at [offset] in file number [file] (the torrent's file order). */
data class Segment(
    val file: Int,
    val offset: Long,
    val length: Int,
)

/**
 * Where pieces and blocks sit in a torrent's files: files laid end to end, cut into pieces of [pieceLength] bytes, the last one
 * short. A piece can span several files. Every lookup is checked, so a range from a peer can never reach outside the files.
 */
class Layout(
    fileSizes: List<Long>,
    pieceLength: Long,
) {
    constructor(meta: TorrentMeta) : this(meta.files.map { it.size }, meta.pieceLength)

    /** Where each file starts in the byte stream of the whole torrent. */
    private val starts = LongArray(fileSizes.size)
    private val sizes = fileSizes.toLongArray()
    private val total: Long
    private val length: Int

    /** How many pieces cover the torrent; 0 for an empty torrent or a piece length that is not positive. */
    val pieceCount: Int

    init {
        var sum = 0L
        for (i in sizes.indices) {
            starts[i] = sum
            sum += sizes[i]
        }
        total = sum
        length = if (pieceLength in 1..Int.MAX_VALUE) pieceLength.toInt() else 0
        pieceCount =
            if (length == 0) 0 else (total / length + if (total % length > 0) 1 else 0).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    /** The bytes in piece [piece]: the piece length, less for the last one; 0 for a piece that does not exist. */
    fun pieceSize(piece: Int): Int =
        when {
            piece < 0 || piece >= pieceCount -> 0
            piece == pieceCount - 1 -> (total - piece.toLong() * length).toInt()
            else -> length
        }

    /**
     * The file runs holding [length] bytes at [offset] within piece [piece], in order, skipping zero-length files; empty for a
     * zero [length]. Null when the piece does not exist or the range is not inside it. Defaults to the rest of the piece.
     */
    fun segments(
        piece: Int,
        offset: Int = 0,
        length: Int = pieceSize(piece) - offset,
    ): List<Segment>? {
        val size = pieceSize(piece)
        if (piece < 0 || piece >= pieceCount || offset < 0 || length < 0 || offset > size || length > size - offset) return null
        if (length == 0) return emptyList()
        var at = piece.toLong() * this.length + offset
        var left = length
        var file = fileAt(at)
        val out = ArrayList<Segment>(2)
        while (left > 0) {
            val inFile = at - starts[file]
            val take = minOf(left.toLong(), sizes[file] - inFile).toInt()
            if (take > 0) {
                out += Segment(file, inFile, take)
                at += take
                left -= take
            }
            file++
        }
        return out
    }

    /** The index of the file holding byte [at] of the torrent, which must be below the total: the last file starting at or before it. */
    private fun fileAt(at: Long): Int {
        var lo = 0
        var hi = starts.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (starts[mid] <= at) lo = mid else hi = mid - 1
        }
        return lo
    }

    /** How many [BLOCK]-sized blocks piece [piece] has, the last one possibly short; 0 for a piece that does not exist. */
    fun blockCount(piece: Int): Int = (pieceSize(piece) + BLOCK - 1) / BLOCK

    /** The blocks of piece [piece] in order; empty for a piece that does not exist. */
    fun blocks(piece: Int): List<Block> {
        val size = pieceSize(piece)
        return List(blockCount(piece)) { Block(piece, it * BLOCK, minOf(BLOCK, size - it * BLOCK)) }
    }

    /** The block at [offset] with [length] bytes of piece [piece] if that is exactly one of the piece's blocks, else null. */
    fun blockAt(
        piece: Int,
        offset: Int,
        length: Int,
    ): Block? {
        val size = pieceSize(piece)
        if (offset < 0 || offset >= size || offset % BLOCK != 0) return null
        return if (length == minOf(BLOCK, size - offset)) Block(piece, offset, length) else null
    }
}
