package dev.anydm.engine.torrent

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LayoutTest {
    // Files of 20,000, 0 and 30,000 bytes in 16 KiB pieces: 4 pieces, the last one 848 bytes.
    private val layout = Layout(listOf(20_000L, 0L, 30_000L), 16_384)

    @Test
    fun theLastPieceIsShort() {
        assertEquals(4, layout.pieceCount)
        assertEquals(listOf(16_384, 16_384, 16_384, 848), List(4) { layout.pieceSize(it) })
        assertEquals(0, layout.pieceSize(-1))
        assertEquals(0, layout.pieceSize(4))
    }

    @Test
    fun aPieceInsideOneFileIsOneSegment() {
        assertEquals(listOf(Segment(0, 0, 16_384)), layout.segments(0))
        assertEquals(listOf(Segment(2, 29_152, 848)), layout.segments(3))
    }

    @Test
    fun aPieceSpanningAFileBorderIsSplitSkippingTheZeroLengthFile() {
        assertEquals(listOf(Segment(0, 16_384, 3_616), Segment(2, 0, 12_768)), layout.segments(1))
    }

    @Test
    fun aRangeInsideAPieceMapsToTheRightBytes() {
        assertEquals(listOf(Segment(0, 19_384, 616), Segment(2, 0, 384)), layout.segments(1, offset = 3_000, length = 1_000))
        assertEquals(listOf(Segment(2, 12_868, 50)), layout.segments(2, offset = 100, length = 50))
    }

    @Test
    fun anEmptyRangeIsNoSegmentsButValid() {
        assertEquals(emptyList(), layout.segments(1, offset = 5, length = 0))
        assertEquals(emptyList(), layout.segments(1, offset = layout.pieceSize(1), length = 0))
    }

    @Test
    fun rangesOutsideThePieceAreRefused() {
        assertNull(layout.segments(-1))
        assertNull(layout.segments(4))
        assertNull(layout.segments(0, offset = -1, length = 10))
        assertNull(layout.segments(0, offset = 0, length = -1))
        assertNull(layout.segments(0, offset = 0, length = 16_385))
        assertNull(layout.segments(0, offset = 16_384, length = 1))
        assertNull(layout.segments(3, offset = 0, length = 849))
        assertNull(layout.segments(0, offset = Int.MAX_VALUE, length = Int.MAX_VALUE), "the sum must not wrap")
        assertNull(layout.segments(0, offset = 1, length = Int.MAX_VALUE))
    }

    @Test
    fun theSegmentsOfAllPiecesCoverEveryFileExactlyOnceInOrder() {
        val rnd = Random(9)
        repeat(50) {
            val sizes = List(rnd.nextInt(1, 8)) { if (rnd.nextInt(4) == 0) 0L else rnd.nextLong(1, 100_000) }
            val pieceLength = 16_384L shl rnd.nextInt(0, 3)
            val l = Layout(sizes, pieceLength)
            val next = LongArray(sizes.size)
            var covered = 0L
            for (p in 0 until l.pieceCount) {
                for (s in assertNotNull(l.segments(p))) {
                    assertEquals(next[s.file], s.offset, "sizes $sizes piece $p")
                    next[s.file] += s.length
                    covered += s.length
                }
            }
            assertEquals(sizes, next.toList(), "sizes $sizes")
            assertEquals(sizes.sum(), covered)
        }
    }

    @Test
    fun aTorrentSmallerThanOnePieceIsOneShortPiece() {
        val l = Layout(listOf(10L, 5L), 16_384)
        assertEquals(1, l.pieceCount)
        assertEquals(15, l.pieceSize(0))
        assertEquals(listOf(Segment(0, 0, 10), Segment(1, 0, 5)), l.segments(0))
    }

    @Test
    fun aTorrentOfNothingHasNoPieces() {
        val l = Layout(listOf(0L, 0L), 16_384)
        assertEquals(0, l.pieceCount)
        assertNull(l.segments(0))
        assertEquals(emptyList(), l.blocks(0))
    }

    @Test
    fun aNonsensePieceLengthMeansNoPieces() {
        assertEquals(0, Layout(listOf(100L), 0).pieceCount)
        assertEquals(0, Layout(listOf(100L), -5).pieceCount)
    }

    @Test
    fun aPieceIsCutIntoSixteenKibBlocksWithAShortLastOne() {
        val l = Layout(listOf(3 * 65_536L + 20_000), 65_536)
        assertEquals(4, l.pieceCount)
        assertEquals(4, l.blockCount(0))
        assertEquals(
            listOf(Block(1, 0, BLOCK), Block(1, BLOCK, BLOCK), Block(1, 2 * BLOCK, BLOCK), Block(1, 3 * BLOCK, BLOCK)),
            l.blocks(1),
        )
        assertEquals(2, l.blockCount(3))
        assertEquals(listOf(Block(3, 0, BLOCK), Block(3, BLOCK, 20_000 - BLOCK)), l.blocks(3))
        assertEquals(0, l.blockCount(4))
        assertEquals(emptyList(), l.blocks(-1))
    }

    @Test
    fun aBlockIsOnlyValidWhenItIsExactlyOneOfThePiecesBlocks() {
        val l = Layout(listOf(3 * 65_536L + 20_000), 65_536)
        assertEquals(Block(0, 0, BLOCK), l.blockAt(0, 0, BLOCK))
        assertEquals(Block(0, 3 * BLOCK, BLOCK), l.blockAt(0, 3 * BLOCK, BLOCK))
        assertEquals(Block(3, BLOCK, 20_000 - BLOCK), l.blockAt(3, BLOCK, 20_000 - BLOCK))
        assertNull(l.blockAt(0, 100, BLOCK), "misaligned")
        assertNull(l.blockAt(0, 0, BLOCK - 1), "short but not the last block")
        assertNull(l.blockAt(0, 0, BLOCK + 1))
        assertNull(l.blockAt(0, 0, 0))
        assertNull(l.blockAt(3, BLOCK, BLOCK), "runs past the short last piece")
        assertNull(l.blockAt(3, 0, 20_000), "the whole short piece is not one block")
        assertNull(l.blockAt(0, 4 * BLOCK, BLOCK), "past the end of the piece")
        assertNull(l.blockAt(4, 0, BLOCK), "no such piece")
        assertNull(l.blockAt(-1, 0, BLOCK))
        assertNull(l.blockAt(0, -BLOCK, BLOCK))
        assertNull(l.blockAt(0, Int.MAX_VALUE, BLOCK))
    }

    @Test
    fun aLayoutBuiltFromAMetaFollowsItsFilesAndPieceLength() {
        val data = contentOf(50_000, seed = 2)
        val meta = (readTorrent(torrentOf(listOf("a" to 20_000, "b" to 30_000), 16_384, data).bytes) as TorrentResult.Ok).meta
        val l = Layout(meta)
        assertEquals(meta.pieceCount, l.pieceCount)
        assertEquals(List(l.pieceCount) { meta.pieceSize(it).toInt() }, List(l.pieceCount) { l.pieceSize(it) })
    }
}
