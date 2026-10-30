package dev.anydm.engine.http

import dev.anydm.engine.store.NewSegment

/** The smallest block (HTTP-05). */
const val MIN_BLOCK = 1L shl 20

/** The largest block (HTTP-05). */
const val MAX_BLOCK = 8L shl 20

/** Blocks start on multiples of this. */
const val BLOCK_ALIGN = 64L shl 10

/** About four blocks per connection, within [MIN_BLOCK]..[MAX_BLOCK] and on a [BLOCK_ALIGN] boundary. */
internal fun blockSize(
    size: Long,
    connections: Int,
): Long = (size / (connections * 4L)).coerceIn(MIN_BLOCK, MAX_BLOCK) / BLOCK_ALIGN * BLOCK_ALIGN

/** [0, size) in blocks of [blockSize]; the last one takes the rest. Each block is a segment with its own watermark. */
internal fun planBlocks(
    size: Long,
    connections: Int,
): List<NewSegment> {
    require(size > 0) { "size must be > 0: $size" }
    require(connections >= 1) { "connections must be >= 1: $connections" }
    val block = blockSize(size, connections)
    return (0 until size step block).map { start -> NewSegment(start, minOf(start + block, size)) }
}
