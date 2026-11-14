package dev.anydm.engine.torrent

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MetadataTest {
    // ---- extension handshake

    @Test
    fun ourExtensionHandshakeOffersMetadataUnderOurIdAndNoSize() {
        val decoded = assertNotNull(decodeExtHandshake(encodeExtHandshake()))
        assertEquals(1, decoded.utMetadata)
        assertNull(decoded.metadataSize)
        assertEquals(3, assertNotNull(decodeExtHandshake(encodeExtHandshake(3))).utMetadata)
        assertContentEquals("d1:md11:ut_metadatai1eee".encodeToByteArray(), encodeExtHandshake())
    }

    @Test
    fun aPeersExtensionHandshakeGivesItsMetadataIdAndSize() {
        val h = assertNotNull(decodeExtHandshake(extHandshakePayload(2, 31_235)))
        assertEquals(2, h.utMetadata)
        assertEquals(31_235L, h.metadataSize)
    }

    @Test
    fun aPeerWithoutMetadataOrWithItDisabledHasNoId() {
        assertNull(assertNotNull(decodeExtHandshake(extHandshakePayload(null, 10))).utMetadata)
        assertNull(assertNotNull(decodeExtHandshake(extHandshakePayload(0, 10))).utMetadata)
        assertNull(assertNotNull(decodeExtHandshake(extHandshakePayload(256, 10))).utMetadata)
        assertNull(assertNotNull(decodeExtHandshake(extHandshakePayload(-1, 10))).utMetadata)
        assertNull(assertNotNull(decodeExtHandshake(extHandshakePayload(2, null))).metadataSize)
    }

    @Test
    fun anExtensionHandshakeKeepsAnySizeTheCallerMustJudge() {
        assertEquals(-5L, assertNotNull(decodeExtHandshake(extHandshakePayload(2, -5))).metadataSize)
        assertEquals(Long.MAX_VALUE, assertNotNull(decodeExtHandshake(extHandshakePayload(2, Long.MAX_VALUE))).metadataSize)
    }

    @Test
    fun anExtensionHandshakeThatIsNotADictionaryIsRefused() {
        assertNull(decodeExtHandshake(ByteArray(0)))
        assertNull(decodeExtHandshake("i5e".encodeToByteArray()))
        assertNull(decodeExtHandshake("le".encodeToByteArray()))
        assertNull(decodeExtHandshake("d1:m".encodeToByteArray()))
        assertNull(decodeExtHandshake(extHandshakePayload(2, 5) + 0))
    }

    @Test
    fun aWrongTypedFieldInAnExtensionHandshakeIsIgnored() {
        val odd = bdict("m" to bint(3), "metadata_size" to bstr("big"))
        val h = assertNotNull(decodeExtHandshake(odd))
        assertNull(h.utMetadata)
        assertNull(h.metadataSize)
    }

    // ---- ut_metadata messages

    @Test
    fun aRequestIsADictionaryOfTypeZeroAndPiece() {
        assertContentEquals("d8:msg_typei0e5:piecei3ee".encodeToByteArray(), encodeMetaRequest(3))
        assertEquals(MetaMsg.Request(3), decodeMetaMsg(encodeMetaRequest(3)))
        assertEquals(MetaMsg.Request(0), decodeMetaMsg(encodeMetaRequest(0)))
    }

    @Test
    fun aRejectCarriesThePiece() {
        assertEquals(MetaMsg.Reject(4), decodeMetaMsg(metaRejectPayload(4)))
    }

    @Test
    fun aDataMessageIsTheHeaderThenTheRawBlock() {
        val block = ByteArray(300) { (it * 5).toByte() }
        val msg = assertIs<MetaMsg.Data>(decodeMetaMsg(metaDataPayload(2, 40_000, block)))
        assertEquals(2, msg.piece)
        assertEquals(40_000L, msg.total)
        assertContentEquals(block, msg.block)
    }

    @Test
    fun aBlockThatLooksLikeBencodeIsStillTheBlock() {
        val block = "d1:ai1ee".encodeToByteArray()
        assertContentEquals(block, assertIs<MetaMsg.Data>(decodeMetaMsg(metaDataPayload(0, 8, block))).block)
    }

    @Test
    fun aDataMessageWithNoBlockHasAnEmptyBlock() {
        assertEquals(0, assertIs<MetaMsg.Data>(decodeMetaMsg(metaDataPayload(0, 8, ByteArray(0)))).block.size)
    }

    @Test
    fun dataMessagesCompareByTheirBlock() {
        assertEquals(MetaMsg.Data(1, 5, byteArrayOf(1)), MetaMsg.Data(1, 5, byteArrayOf(1)))
        assertEquals(MetaMsg.Data(1, 5, byteArrayOf(1)).hashCode(), MetaMsg.Data(1, 5, byteArrayOf(1)).hashCode())
        assertFalse(MetaMsg.Data(1, 5, byteArrayOf(1)) == MetaMsg.Data(1, 5, byteArrayOf(2)))
    }

    @Test
    fun malformedMetadataMessagesAreRefused() {
        listOf(
            ByteArray(0),
            "i1e".encodeToByteArray(),
            "d8:msg_typei0ee".encodeToByteArray(), // no piece
            "d8:msg_typei9e5:piecei0ee".encodeToByteArray(), // unknown type
            "d8:msg_typei0e5:piecei-1ee".encodeToByteArray(),
            "d8:msg_typei0e5:piecei4294967296ee".encodeToByteArray(),
            "d8:msg_type1:05:piecei0ee".encodeToByteArray(),
            "d8:msg_typei1e5:piecei0ee".encodeToByteArray(), // data without total_size
            "d8:msg_typei1e5:piecei0e10:total_sizei-1eeXX".encodeToByteArray(),
            "d8:msg_typei0e5:piecei0e".encodeToByteArray(), // cut short
        ).forEach { assertNull(decodeMetaMsg(it), it.decodeToString()) }
    }

    @Test
    fun garbageNeverThrows() {
        val rnd = kotlin.random.Random(5)
        repeat(2_000) {
            val bytes = rnd.nextBytes(rnd.nextInt(0, 60))
            decodeMetaMsg(bytes)
            decodeExtHandshake(bytes)
        }
    }

    // ---- assembler

    private fun metadata(size: Int) = contentOf(size, seed = 3)

    private fun blocks(data: ByteArray) = data.toList().chunked(METADATA_BLOCK).map { it.toByteArray() }

    @Test
    fun theMissingBlocksStartAsAllOfThem() {
        val a = MetadataAssembler(2L * METADATA_BLOCK + 5, sha1(metadata(2 * METADATA_BLOCK + 5)))
        assertEquals(listOf(0, 1, 2), a.missing())
        assertFalse(a.complete())
        assertNull(a.verified())
    }

    @Test
    fun blocksInAnyOrderAssembleToTheVerifiedBytes() {
        val data = metadata(3 * METADATA_BLOCK + 123)
        val parts = blocks(data)
        val a = MetadataAssembler(data.size.toLong(), sha1(data))
        for (i in listOf(3, 0, 2)) assertTrue(a.accept(i, parts[i]))
        assertEquals(listOf(1), a.missing())
        assertNull(a.verified())
        assertTrue(a.accept(1, parts[1]))
        assertTrue(a.complete())
        assertContentEquals(data, a.verified())
    }

    @Test
    fun aSingleShortBlockIsEnough() {
        val data = metadata(100)
        val a = MetadataAssembler(100, sha1(data))
        assertTrue(a.accept(0, data))
        assertContentEquals(data, a.verified())
    }

    @Test
    fun aBlockOfTheWrongLengthOrNumberIsRefusedAndNotStored() {
        val data = metadata(2 * METADATA_BLOCK + 10)
        val parts = blocks(data)
        val a = MetadataAssembler(data.size.toLong(), sha1(data))
        assertFalse(a.accept(0, parts[0].copyOf(METADATA_BLOCK - 1)), "a short block that is not the last")
        assertFalse(a.accept(0, parts[0] + 1), "a long block")
        assertFalse(a.accept(2, parts[2].copyOf(9)), "a last block that is short of its size")
        assertFalse(a.accept(2, parts[2] + 1), "a last block that is long")
        assertFalse(a.accept(1, ByteArray(0)))
        assertFalse(a.accept(3, parts[2]))
        assertFalse(a.accept(-1, parts[0]))
        assertEquals(listOf(0, 1, 2), a.missing())
    }

    @Test
    fun theLastBlockOfAnExactMultipleIsFull() {
        val data = metadata(2 * METADATA_BLOCK)
        val a = MetadataAssembler(data.size.toLong(), sha1(data))
        assertFalse(a.accept(1, ByteArray(10)))
        assertTrue(a.accept(0, blocks(data)[0]))
        assertTrue(a.accept(1, blocks(data)[1]))
        assertContentEquals(data, a.verified())
    }

    @Test
    fun aSecondCopyOfABlockIsAcceptedAndChangesNothing() {
        val data = metadata(METADATA_BLOCK + 1)
        val parts = blocks(data)
        val a = MetadataAssembler(data.size.toLong(), sha1(data))
        assertTrue(a.accept(0, parts[0]))
        assertTrue(a.accept(0, ByteArray(METADATA_BLOCK) { 9 }), "right length, other bytes: the first one stays")
        assertEquals(listOf(1), a.missing())
    }

    @Test
    fun dataThatFailsTheHashIsDroppedAndEveryBlockIsMissingAgain() {
        val data = metadata(2 * METADATA_BLOCK)
        val parts = blocks(data)
        val a = MetadataAssembler(data.size.toLong(), sha1(data))
        assertTrue(a.accept(0, parts[0]))
        assertTrue(a.accept(1, parts[1].copyOf().also { it[0] = (it[0] + 1).toByte() }))
        assertTrue(a.complete())
        assertNull(a.verified())
        assertFalse(a.complete())
        assertEquals(listOf(0, 1), a.missing())
        assertTrue(a.accept(0, parts[0]))
        assertTrue(a.accept(1, parts[1]))
        assertContentEquals(data, a.verified())
    }

    @Test
    fun anAssemblerForAnImpossibleSizeIsAProgrammingError() {
        assertFailsWith<IllegalArgumentException> { MetadataAssembler(0, ByteArray(20)) }
        assertFailsWith<IllegalArgumentException> { MetadataAssembler(-1, ByteArray(20)) }
        assertFailsWith<IllegalArgumentException> { MetadataAssembler(MAX_METADATA_BYTES + 1L, ByteArray(20)) }
        assertFailsWith<IllegalArgumentException> { MetadataAssembler(1L shl 30, ByteArray(20)) }
        assertFailsWith<IllegalArgumentException> { MetadataAssembler(10, ByteArray(19)) }
        MetadataAssembler(MAX_METADATA_BYTES.toLong(), ByteArray(20))
    }

    // ---- the torrent file

    private fun built(vararg trackers: String): Pair<BuiltTorrent, ByteArray> {
        val data = contentOf(50_000, 4)
        val t = torrentOf(listOf("a.bin" to 50_000), 16 * 1024, data, name = "a.bin", single = true)
        return t to torrentFileBytes(t.info, trackers.toList())
    }

    @Test
    fun theTorrentFileHoldsTheInfoBytesVerbatimSoTheHashHolds() {
        val (t, file) = built("http://t1.example/announce", "udp://t2.example:80")
        val meta = assertIs<TorrentResult.Ok>(readTorrent(file)).meta
        assertContentEquals(sha1(t.info), meta.infoHash)
        assertEquals("a.bin", meta.name)
        assertEquals(50_000L, meta.totalSize)
        // the info dictionary sits in the file exactly as it came
        val at = file.indices.first { i -> i + t.info.size <= file.size && file.copyOfRange(i, i + t.info.size).contentEquals(t.info) }
        assertEquals(file.size - 1, at + t.info.size)
    }

    @Test
    fun everyTrackerIsItsOwnTierAndTheFirstIsTheAnnounce() {
        val (_, file) = built("http://t1.example/announce", "udp://t2.example:80", "https://t3.example/a")
        val meta = assertIs<TorrentResult.Ok>(readTorrent(file)).meta
        assertEquals(
            listOf(listOf("http://t1.example/announce"), listOf("udp://t2.example:80"), listOf("https://t3.example/a")),
            meta.trackers,
        )
        val root = assertIs<BValue.BDict>(assertIs<DecodeResult.Ok>(decodeBencode(file)).value).entries
        assertEquals(BValue.BBytes("http://t1.example/announce".encodeToByteArray()), root["announce"])
    }

    @Test
    fun aTorrentFileWithNoTrackersIsJustTheInfo() {
        val (t, file) = built()
        assertContentEquals("d4:info".encodeToByteArray() + t.info + "e".encodeToByteArray(), file)
        assertEquals(emptyList(), assertIs<TorrentResult.Ok>(readTorrent(file)).meta.trackers)
    }

    // review: the assembler says how much it holds, so a caller can bound what several of them keep
    @Test
    fun theAssemblerCountsTheBytesItHoldsAndForgetsThemOnABadHash() {
        val data = metadata(2 * METADATA_BLOCK + 10)
        val parts = blocks(data)
        val a = MetadataAssembler(data.size.toLong(), sha1(data))
        assertEquals(0L, a.heldBytes)
        assertFalse(a.holds(2))
        assertTrue(a.accept(2, parts[2]))
        assertTrue(a.holds(2))
        assertEquals(10L, a.heldBytes)
        assertTrue(a.accept(2, parts[2]), "a second copy")
        assertEquals(10L, a.heldBytes, "counted once")
        assertFalse(a.accept(0, ByteArray(5)))
        assertEquals(10L, a.heldBytes, "a refused block is not held")
        assertTrue(a.accept(0, parts[0].copyOf().also { it[0] = (it[0] + 1).toByte() }))
        assertTrue(a.accept(1, parts[1]))
        assertEquals(2L * METADATA_BLOCK + 10, a.heldBytes)
        assertNull(a.verified())
        assertEquals(0L, a.heldBytes)
        assertFalse(a.holds(0))
    }

    // review: the copy of a magnet is a `.torrent` and must stay readable (and loadable) under MAX_TORRENT_BYTES
    @Test
    fun theLargestMetadataStillFitsTheTorrentFileLimitWithItsWrapper() {
        val info = ByteArray(MAX_METADATA_BYTES)
        assertTrue(torrentFileBytes(info, emptyList()).size <= MAX_TORRENT_BYTES)
    }

    @Test
    fun trackersAreLeftOutOfTheCopyWhenTheyWouldMakeItTooBig() {
        val info = ByteArray(1_000) { it.toByte() }
        val trackers = List(10) { "http://tracker$it.example/announce" }
        val full = torrentFileBytes(info, trackers)
        assertContentEquals(full, torrentFileBytes(info, trackers, maxBytes = full.size))
        val cut = torrentFileBytes(info, trackers, maxBytes = full.size - 1)
        assertContentEquals(torrentFileBytes(info, emptyList()), cut)
        assertTrue(cut.size < full.size - 1)
    }
}
