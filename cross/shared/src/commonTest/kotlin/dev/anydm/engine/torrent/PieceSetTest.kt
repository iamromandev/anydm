package dev.anydm.engine.torrent

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PieceSetTest {
    private fun setOf(
        size: Int,
        vararg pieces: Int,
    ): PieceSet = pieces.fold(PieceSet.empty(size)) { s, i -> s.with(i) }

    @Test
    fun anEmptySetHasNothing() {
        val s = PieceSet.empty(10)
        assertEquals(10, s.size)
        assertEquals(0, s.count)
        assertFalse(s.isComplete)
        assertTrue((0 until 10).none { s.has(it) })
        assertContentEquals(ByteArray(2), s.toBytes())
    }

    @Test
    fun bitZeroIsTheHighBitOfTheFirstByte() {
        assertContentEquals(byteArrayOf(0x80.toByte(), 0), setOf(10, 0).toBytes())
        assertContentEquals(byteArrayOf(0x01, 0), setOf(10, 7).toBytes())
        assertContentEquals(byteArrayOf(0, 0x80.toByte()), setOf(10, 8).toBytes())
        assertContentEquals(byteArrayOf(0, 0x40), setOf(10, 9).toBytes())
    }

    @Test
    fun withAddsOnePieceAndLeavesTheOriginalAlone() {
        val a = setOf(16, 3)
        val b = a.with(5)
        assertTrue(a.has(3) && !a.has(5))
        assertTrue(b.has(3) && b.has(5))
        assertEquals(1, a.count)
        assertEquals(2, b.count)
        assertEquals(2, b.with(5).count)
    }

    @Test
    fun indexesOutsideTheSetAreNotInIt() {
        val s = setOf(10, 9)
        assertFalse(s.has(-1))
        assertFalse(s.has(10))
        assertFalse(s.has(15))
        assertFalse(s.has(Int.MAX_VALUE))
        assertEquals(s, s.with(-1))
        assertEquals(s, s.with(10))
        assertEquals(s, s.with(Int.MIN_VALUE))
    }

    @Test
    fun ofBuildsTheSameSetAsAddingOneByOne() {
        val rnd = Random(9)
        for (n in listOf(0, 1, 7, 8, 9, 100, 1_001)) {
            val picks = (0 until n).filter { rnd.nextInt(3) == 0 }
            val expected = picks.fold(PieceSet.empty(n)) { s, i -> s.with(i) }
            val built = PieceSet.of(n, picks)
            assertEquals(expected, built, "size $n")
            assertEquals(picks.size, built.count, "count for size $n")
            assertEquals(n, built.size)
        }
    }

    @Test
    fun ofIgnoresDuplicatesAndIndexesOutsideTheSet() {
        val s = PieceSet.of(10, listOf(3, 3, -1, 10, Int.MAX_VALUE, Int.MIN_VALUE, 9, 3))
        assertEquals(setOf(10, 3, 9), s)
        assertEquals(2, s.count)
        assertEquals(PieceSet.empty(0), PieceSet.of(0, listOf(0, 1)))
        assertEquals(PieceSet.empty(0), PieceSet.of(-4, listOf(0)))
        assertEquals(PieceSet.empty(5), PieceSet.of(5, emptyList()))
    }

    @Test
    fun ofMakesAHugeSetQuickly() {
        val n = 500_000
        val took = kotlin.time.measureTime { assertEquals(n, PieceSet.of(n, 0 until n).count) }
        assertTrue(took.inWholeMilliseconds < 1_000, "took $took")
    }

    @Test
    fun countAndCompleteFollowTheBits() {
        val all = (0 until 10).fold(PieceSet.empty(10)) { s, i -> s.with(i) }
        assertEquals(10, all.count)
        assertTrue(all.isComplete)
        assertFalse(setOf(10, 0, 1, 2).isComplete)
        assertEquals(3, setOf(10, 0, 1, 2).count)
    }

    @Test
    fun anEmptyTorrentSetIsComplete() {
        assertTrue(PieceSet.empty(0).isComplete)
        assertEquals(0, PieceSet.empty(0).toBytes().size)
    }

    @Test
    fun aPeerBitfieldMustHaveTheRightLengthAndZeroSpareBits() {
        val ok = assertNotNull(PieceSet.fromPeerBitfield(10, byteArrayOf(0xFF.toByte(), 0xC0.toByte())))
        assertEquals(10, ok.count)
        assertTrue(ok.has(9))
        assertNull(PieceSet.fromPeerBitfield(10, byteArrayOf(0xFF.toByte(), 0xC1.toByte())), "a spare bit is set")
        assertNull(PieceSet.fromPeerBitfield(10, byteArrayOf(0xFF.toByte(), 0xA0.toByte())), "a spare bit is set")
        assertNull(PieceSet.fromPeerBitfield(10, byteArrayOf(0xFF.toByte())), "too short")
        assertNull(PieceSet.fromPeerBitfield(10, ByteArray(3)), "too long")
        assertNotNull(PieceSet.fromPeerBitfield(8, byteArrayOf(0xFF.toByte())), "no spare bits at all")
        assertNotNull(PieceSet.fromPeerBitfield(0, ByteArray(0)))
        assertNull(PieceSet.fromPeerBitfield(0, ByteArray(1)))
        assertNull(PieceSet.fromPeerBitfield(-1, ByteArray(0)))
    }

    @Test
    fun aPeerBitfieldIsCopiedSoLaterChangesDoNotShowThrough() {
        val bytes = byteArrayOf(0x80.toByte())
        val s = assertNotNull(PieceSet.fromPeerBitfield(8, bytes))
        bytes[0] = 0
        assertTrue(s.has(0))
        s.toBytes()[0] = 0
        assertTrue(s.has(0))
    }

    @Test
    fun base64RoundTripsSetsOfManySizes() {
        val rnd = Random(5)
        for (n in listOf(0, 1, 7, 8, 9, 15, 16, 17, 100, 1_000, 100_000)) {
            val s = PieceSet.of(n, (0 until n).filter { rnd.nextInt(3) == 0 })
            val back = assertNotNull(PieceSet.fromBase64(n, s.toBase64()), "size $n")
            assertEquals(s, back)
            assertEquals(s.count, back.count)
        }
    }

    @Test
    fun base64OfKnownBitsIsStandardWithPadding() {
        assertEquals("gA==", setOf(8, 0).toBase64())
        assertEquals("/8A=", setOf(10, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9).toBase64())
        assertEquals("", PieceSet.empty(0).toBase64())
    }

    @Test
    fun base64SpareBitsAreIgnoredOnParse() {
        // 0xFF 0xFF for 10 pieces: the six spare bits of the second byte are noise from an older or damaged file.
        val s = assertNotNull(PieceSet.fromBase64(10, "//8="))
        assertEquals(10, s.count)
        assertEquals(0xC0, s.toBytes()[1].toInt() and 0xFF)
        assertEquals(PieceSet.fromBase64(10, "/8A="), s)
    }

    @Test
    fun base64OfTheWrongShapeIsRefused() {
        assertNull(PieceSet.fromBase64(10, ""), "nothing for ten pieces")
        assertNull(PieceSet.fromBase64(10, "gA=="), "one byte for ten pieces")
        assertNull(PieceSet.fromBase64(10, "/8A"), "unpadded")
        assertNull(PieceSet.fromBase64(10, "/8A!"), "bad character")
        assertNull(PieceSet.fromBase64(10, "/8A=/8A="), "too long")
        assertNull(PieceSet.fromBase64(8, "/8A="), "two bytes for eight pieces")
        assertNull(PieceSet.fromBase64(0, "AA=="), "bytes for nothing")
        assertNull(PieceSet.fromBase64(-1, ""))
    }

    @Test
    fun aHugeStringIsRefusedByLengthBeforeAnythingIsDecoded() {
        assertNull(PieceSet.fromBase64(10, "A".repeat(50_000_000)))
        assertNull(PieceSet.fromBase64(Int.MAX_VALUE, "AAAA"))
    }

    @Test
    fun doneBytesAreTheSumOfTheVerifiedPiecesWithTheShortLastOne() {
        val meta = assertNotNull(readMeta(torrentOf(listOf("a" to 40_000), 16_384, contentOf(40_000), single = true)))
        assertEquals(0L, PieceSet.empty(3).doneBytes(meta))
        assertEquals(7_232L, setOf(3, 2).doneBytes(meta))
        assertEquals(16_384L, setOf(3, 0).doneBytes(meta))
        assertEquals(32_768L, setOf(3, 0, 1).doneBytes(meta))
        assertEquals(40_000L, setOf(3, 0, 1, 2).doneBytes(meta))
        assertEquals(16_384L + 7_232L, setOf(3, 1, 2).doneBytes(meta))
    }

    @Test
    fun doneBytesOfASetForAnotherTorrentClaimNothing() {
        val meta = assertNotNull(readMeta(torrentOf(listOf("a" to 40_000), 16_384, contentOf(40_000), single = true)))
        assertEquals(0L, setOf(5, 0, 1).doneBytes(meta))
    }

    @Test
    fun setsWithTheSameSizeAndPiecesAreEqual() {
        assertEquals(setOf(10, 1, 2), setOf(10, 2, 1))
        assertEquals(setOf(10, 1, 2).hashCode(), setOf(10, 2, 1).hashCode())
        assertNotEquals(setOf(10, 1, 2), setOf(10, 1))
        assertNotEquals(setOf(10, 1), setOf(11, 1))
    }

    private fun readMeta(built: BuiltTorrent): TorrentMeta? = (readTorrent(built.bytes) as? TorrentResult.Ok)?.meta

    @Test
    fun lacksAnyOfSaysWhetherTheOtherBitfieldHoldsAPieceThisSetDoesNot() {
        val mine = setOf(20, 0, 1, 9, 19)
        assertFalse(mine.lacksAnyOf(setOf(20, 0, 1).toBytes()))
        assertFalse(mine.lacksAnyOf(mine.toBytes()))
        assertFalse(mine.lacksAnyOf(PieceSet.empty(20).toBytes()))
        assertTrue(mine.lacksAnyOf(setOf(20, 0, 2).toBytes()))
        assertTrue(mine.lacksAnyOf(setOf(20, 18).toBytes()), "a piece in the last byte")
        assertTrue(PieceSet.empty(20).lacksAnyOf(setOf(20, 7).toBytes()))
    }

    @Test
    fun lacksAnyOfDoesNotCopyAndToleratesABitfieldOfAnotherLength() {
        val mine = setOf(20, 0)
        assertFalse(mine.lacksAnyOf(ByteArray(0)))
        assertTrue(mine.lacksAnyOf(ByteArray(5) { 0x40 }), "only the bytes both have are compared: the first holds piece 1")
        assertFalse(mine.lacksAnyOf(byteArrayOf(0x80.toByte(), 0, 0, 0xFF.toByte(), 0xFF.toByte())))
    }
}
