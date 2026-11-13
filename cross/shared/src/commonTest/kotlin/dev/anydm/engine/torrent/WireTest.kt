package dev.anydm.engine.torrent

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class WireTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private fun int(v: Int) = bytes(v ushr 24, v ushr 16, v ushr 8, v)

    private fun frame(
        id: Int,
        payload: ByteArray = ByteArray(0),
    ) = int(payload.size + 1) + bytes(id) + payload

    private fun decode(
        data: ByteArray,
        decoder: WireDecoder = WireDecoder(),
    ): List<PeerMsg> = decoder.feed(data, 0, data.size)

    private fun roundTrip(msg: PeerMsg) = assertEquals(listOf(msg), decode(encodeMessage(msg)), "$msg")

    @Test
    fun keepAliveIsFourZeroBytes() {
        assertContentEquals(bytes(0, 0, 0, 0), encodeMessage(PeerMsg.KeepAlive))
        roundTrip(PeerMsg.KeepAlive)
    }

    @Test
    fun theFourEmptyMessagesAreALengthOfOneAndTheirId() {
        assertContentEquals(bytes(0, 0, 0, 1, 0), encodeMessage(PeerMsg.Choke))
        assertContentEquals(bytes(0, 0, 0, 1, 1), encodeMessage(PeerMsg.Unchoke))
        assertContentEquals(bytes(0, 0, 0, 1, 2), encodeMessage(PeerMsg.Interested))
        assertContentEquals(bytes(0, 0, 0, 1, 3), encodeMessage(PeerMsg.NotInterested))
        listOf(PeerMsg.Choke, PeerMsg.Unchoke, PeerMsg.Interested, PeerMsg.NotInterested).forEach(::roundTrip)
    }

    @Test
    fun haveCarriesThePieceIndex() {
        assertContentEquals(bytes(0, 0, 0, 5, 4, 0, 0, 1, 2), encodeMessage(PeerMsg.Have(258)))
        roundTrip(PeerMsg.Have(0))
        roundTrip(PeerMsg.Have(Int.MAX_VALUE))
    }

    @Test
    fun bitfieldCarriesItsBytes() {
        assertContentEquals(bytes(0, 0, 0, 3, 5, 0xA0, 0x40), encodeMessage(PeerMsg.Bitfield(bytes(0xA0, 0x40))))
        roundTrip(PeerMsg.Bitfield(bytes(0xA0, 0x40)))
        roundTrip(PeerMsg.Bitfield(ByteArray(0)))
    }

    @Test
    fun requestAndCancelCarryPieceOffsetAndLength() {
        assertContentEquals(bytes(0, 0, 0, 13, 6, 0, 0, 0, 1, 0, 0, 64, 0, 0, 0, 64, 0), encodeMessage(PeerMsg.Request(1, 16_384, 16_384)))
        assertContentEquals(bytes(0, 0, 0, 13, 8, 0, 0, 0, 2, 0, 0, 0, 0, 0, 0, 64, 0), encodeMessage(PeerMsg.Cancel(2, 0, 16_384)))
        roundTrip(PeerMsg.Request(1, 16_384, 16_384))
        roundTrip(PeerMsg.Cancel(2, 0, 16_384))
    }

    @Test
    fun pieceCarriesTheBlockData() {
        val data = ByteArray(100) { it.toByte() }
        val wire = encodeMessage(PeerMsg.Piece(3, 32_768, data))
        assertContentEquals(int(109) + bytes(7) + int(3) + int(32_768) + data, wire)
        roundTrip(PeerMsg.Piece(3, 32_768, data))
        roundTrip(PeerMsg.Piece(0, 0, ByteArray(BLOCK) { (it * 7).toByte() }))
    }

    @Test
    fun anUnknownIdIsReportedAndItsPayloadIsSkipped() {
        val data = frame(20, ByteArray(300) { 9 }) + encodeMessage(PeerMsg.Choke)
        assertEquals(listOf(PeerMsg.Unknown(20), PeerMsg.Choke), decode(data))
        assertEquals(listOf(PeerMsg.Unknown(9)), decode(frame(9, bytes(0x1A, 0xE1))))
        assertEquals(listOf(PeerMsg.Unknown(255)), decode(frame(255)))
        assertEquals(listOf(PeerMsg.Unknown(200)), decode(encodeMessage(PeerMsg.Unknown(200))))
    }

    @Test
    fun aBigUnknownMessageIsSkippedAcrossChunksWithoutBuffering() {
        val d = WireDecoder()
        val payload = ByteArray((1 shl 20) - 1)
        val whole = frame(30, payload) + encodeMessage(PeerMsg.Unchoke)
        val out = ArrayList<PeerMsg>()
        var at = 0
        while (at < whole.size) {
            val n = minOf(65_536, whole.size - at)
            out += d.feed(whole, at, n)
            at += n
        }
        assertEquals(listOf(PeerMsg.Unknown(30), PeerMsg.Unchoke), out)
    }

    @Test
    fun messagesSplitAnywhereAreReassembled() {
        val msgs =
            listOf(
                PeerMsg.Bitfield(bytes(0xFF, 0x80)),
                PeerMsg.KeepAlive,
                PeerMsg.Have(7),
                PeerMsg.Piece(1, 0, ByteArray(500) { it.toByte() }),
                PeerMsg.Request(2, 0, BLOCK),
                PeerMsg.Unknown(20),
                PeerMsg.Choke,
            )
        // Unknown(20) is what an extension message with a 40-byte payload decodes to.
        val wire = concat(msgs.map { if (it == PeerMsg.Unknown(20)) frame(20, ByteArray(40)) else encodeMessage(it) })
        for (chunk in listOf(1, 2, 3, 5, 7, 64, wire.size)) {
            val d = WireDecoder()
            val out = ArrayList<PeerMsg>()
            var at = 0
            while (at < wire.size) {
                val n = minOf(chunk, wire.size - at)
                out += d.feed(wire, at, n)
                at += n
            }
            assertEquals(msgs, out, "chunk size $chunk")
        }
    }

    @Test
    fun feedingNothingReturnsNothing() {
        assertEquals(emptyList(), WireDecoder().feed(ByteArray(0), 0, 0))
    }

    @Test
    fun aMessageLongerThanTheCapIsRefusedFromItsLengthAlone() {
        for (len in listOf((1 shl 20) + 1, 0x7FFFFFFF, -1, Int.MIN_VALUE, 0x80000000.toInt() + 5)) {
            assertFailsWith<WireError>("length $len") { decode(int(len)) }
        }
    }

    @Test
    fun theCapIsInclusiveAndConfigurable() {
        assertEquals(PeerMsg.Unknown(40), decode(int(1 shl 20) + bytes(40) + ByteArray((1 shl 20) - 1)).single())
        assertFailsWith<WireError> { decode(int(101) + bytes(40), WireDecoder(maxMessage = 100)) }
        assertEquals(PeerMsg.Unknown(40), decode(int(100) + bytes(40), WireDecoder(maxMessage = 100)).single())
    }

    @Test
    fun aBitfieldOfExactlyTheCapIsAccepted() {
        val d = WireDecoder()
        assertEquals(1, decode(int(1 shl 20) + bytes(5) + ByteArray((1 shl 20) - 1), d).size)
    }

    @Test
    fun aPieceBlockOverSixteenKibIsRefused() {
        assertEquals(1, decode(encodeMessage(PeerMsg.Piece(0, 0, ByteArray(BLOCK)))).size)
        assertFailsWith<WireError> { decode(encodeMessage(PeerMsg.Piece(0, 0, ByteArray(BLOCK + 1)))) }
    }

    @Test
    fun aPieceWithNoDataIsRefused() {
        assertFailsWith<WireError> { decode(frame(7, int(0) + int(0))) }
        assertFailsWith<WireError> { decode(frame(7, int(0))) }
    }

    @Test
    fun aPieceLengthIsJudgedBeforeItsPayloadArrives() {
        // Only the length and id are sent: 1 MiB declared for a piece message must fail at once, not wait for data.
        assertFailsWith<WireError> { decode(int(1 shl 20) + bytes(7)) }
        assertFailsWith<WireError> { decode(int(10 + BLOCK) + bytes(7)) }
    }

    @Test
    fun fixedSizeMessagesWithTheWrongLengthAreRefused() {
        for (id in 0..3) {
            assertFailsWith<WireError>("id $id with payload") { decode(frame(id, bytes(0))) }
        }
        assertFailsWith<WireError> { decode(frame(4, bytes(0, 0, 0))) }
        assertFailsWith<WireError> { decode(frame(4, bytes(0, 0, 0, 0, 0))) }
        assertFailsWith<WireError> { decode(frame(4, ByteArray(0))) }
        for (id in listOf(6, 8)) {
            assertFailsWith<WireError>("id $id short") { decode(frame(id, ByteArray(11))) }
            assertFailsWith<WireError>("id $id long") { decode(frame(id, ByteArray(13))) }
            assertFailsWith<WireError>("id $id empty") { decode(frame(id)) }
        }
    }

    @Test
    fun negativeIndexesOffsetsAndLengthsAreRefused() {
        assertFailsWith<WireError> { decode(frame(4, int(-1))) }
        assertFailsWith<WireError> { decode(frame(6, int(-1) + int(0) + int(BLOCK))) }
        assertFailsWith<WireError> { decode(frame(6, int(0) + int(-1) + int(BLOCK))) }
        assertFailsWith<WireError> { decode(frame(6, int(0) + int(0) + int(-1))) }
        assertFailsWith<WireError> { decode(frame(8, int(0) + int(0) + int(Int.MIN_VALUE))) }
        assertFailsWith<WireError> { decode(frame(7, int(-1) + int(0) + bytes(1))) }
        assertFailsWith<WireError> { decode(frame(7, int(0) + int(-1) + bytes(1))) }
    }

    @Test
    fun withoutALayoutARequestLengthMustBeBetweenOneByteAndABlock() {
        assertEquals(1, decode(encodeMessage(PeerMsg.Request(0, 0, BLOCK))).size)
        assertEquals(1, decode(encodeMessage(PeerMsg.Request(0, 0, 1))).size)
        assertFailsWith<WireError> { decode(encodeMessage(PeerMsg.Request(0, 0, 0))) }
        assertFailsWith<WireError> { decode(encodeMessage(PeerMsg.Request(0, 0, BLOCK + 1))) }
        assertFailsWith<WireError> { decode(encodeMessage(PeerMsg.Cancel(0, 0, BLOCK + 1))) }
        assertFailsWith<WireError> { decode(encodeMessage(PeerMsg.Request(0, 0, Int.MAX_VALUE))) }
    }

    // --- BEP 3: a bitfield is only valid as the first message after the handshake

    private fun state(vararg msgs: PeerMsg): PeerProtocolState = PeerProtocolState().also { s -> msgs.forEach { s.accept(it) } }

    @Test
    fun aBitfieldAsTheFirstMessageIsAccepted() {
        state(PeerMsg.Bitfield(bytes(0xFF)))
        state(PeerMsg.Bitfield(bytes(0xFF)), PeerMsg.Have(1), PeerMsg.Unchoke, PeerMsg.Choke)
    }

    @Test
    fun aBitfieldAfterAnyOtherMessageIsAProtocolError() {
        val others =
            listOf(
                PeerMsg.Choke,
                PeerMsg.Unchoke,
                PeerMsg.Interested,
                PeerMsg.NotInterested,
                PeerMsg.Have(1),
                PeerMsg.Request(0, 0, BLOCK),
                PeerMsg.Cancel(0, 0, BLOCK),
                PeerMsg.Piece(0, 0, ByteArray(10)),
            )
        for (first in others) {
            val s = state(first)
            assertFailsWith<WireError>("after $first") { s.accept(PeerMsg.Bitfield(bytes(0xFF))) }
        }
    }

    @Test
    fun aSecondBitfieldIsAProtocolError() {
        val s = state(PeerMsg.Bitfield(bytes(0xFF)))
        assertFailsWith<WireError> { s.accept(PeerMsg.Bitfield(bytes(0xFF))) }
    }

    @Test
    fun aKeepAliveOrAnUnknownMessageDoesNotUseUpTheBitfieldSlot() {
        // Neither says anything about the peer's pieces, and some peers send one first; dropping them would cost real peers.
        state(PeerMsg.KeepAlive, PeerMsg.Unknown(20), PeerMsg.Bitfield(bytes(0xFF)))
    }

    @Test
    fun theOtherMessagesAreNeverRefusedByTheBitfieldRule() {
        state(PeerMsg.Have(1), PeerMsg.Have(2), PeerMsg.Choke, PeerMsg.Unchoke, PeerMsg.KeepAlive, PeerMsg.Piece(0, 0, ByteArray(3)))
    }

    @Test
    fun afterAnErrorTheDecoderKeepsRefusing() {
        val d = WireDecoder()
        assertFailsWith<WireError> { decode(int(-1), d) }
        assertFailsWith<WireError> { decode(encodeMessage(PeerMsg.Choke), d) }
        assertFailsWith<WireError> { d.feed(ByteArray(0), 0, 0) }
    }

    @Test
    fun badBufferRangesAreRefusedAsWireErrorsToo() {
        val d = WireDecoder()
        assertFailsWith<WireError> { d.feed(ByteArray(4), 3, 2) }
        assertFailsWith<WireError> { WireDecoder().feed(ByteArray(4), -1, 2) }
        assertFailsWith<WireError> { WireDecoder().feed(ByteArray(4), 0, -1) }
        assertFailsWith<WireError> { WireDecoder().feed(ByteArray(4), Int.MAX_VALUE, Int.MAX_VALUE) }
    }

    @Test
    fun theDecoderCopiesPayloadsSoReusingTheReadBufferIsSafe() {
        val wire = encodeMessage(PeerMsg.Piece(1, 0, ByteArray(10) { 5 }))
        val out = WireDecoder().feed(wire, 0, wire.size).single() as PeerMsg.Piece
        wire.fill(0)
        assertContentEquals(ByteArray(10) { 5 }, out.data)
    }

    // A 3-piece torrent: 16 KiB pieces, 40,000 bytes, so 3 blocks: two full and one of 7,232 bytes.
    private val layout = Layout(listOf(40_000L), 16_384)

    private fun withLayout() = WireDecoder(layout = layout)

    @Test
    fun withALayoutExactBlocksPass() {
        assertEquals(1, decode(encodeMessage(PeerMsg.Request(0, 0, BLOCK)), withLayout()).size)
        assertEquals(1, decode(encodeMessage(PeerMsg.Request(2, 0, 7_232)), withLayout()).size)
        assertEquals(1, decode(encodeMessage(PeerMsg.Cancel(2, 0, 7_232)), withLayout()).size)
        assertEquals(1, decode(encodeMessage(PeerMsg.Piece(2, 0, ByteArray(7_232))), withLayout()).size)
        assertEquals(1, decode(encodeMessage(PeerMsg.Piece(1, 0, ByteArray(BLOCK))), withLayout()).size)
    }

    @Test
    fun withALayoutBlocksOutsideThePiecesAreRefused() {
        val bad =
            listOf(
                PeerMsg.Request(3, 0, BLOCK),
                PeerMsg.Request(0, 1, BLOCK),
                PeerMsg.Request(0, BLOCK, BLOCK),
                PeerMsg.Request(2, 0, BLOCK),
                PeerMsg.Cancel(2, 0, BLOCK),
                PeerMsg.Piece(3, 0, ByteArray(10)),
                PeerMsg.Piece(0, 0, ByteArray(100)),
                PeerMsg.Piece(2, 0, ByteArray(BLOCK)),
                PeerMsg.Piece(0, 8_192, ByteArray(BLOCK)),
            )
        for (m in bad) assertFailsWith<WireError>("$m") { decode(encodeMessage(m), withLayout()) }
    }

    @Test
    fun withALayoutHaveMustNameAPieceOfTheTorrent() {
        assertEquals(1, decode(encodeMessage(PeerMsg.Have(2)), withLayout()).size)
        assertFailsWith<WireError> { decode(encodeMessage(PeerMsg.Have(3)), withLayout()) }
    }

    @Test
    fun withALayoutARequestOrCancelOnlyHasToLieInsideItsPiece() {
        // We never answer requests, so an odd but harmless one must not cost us the peer: only the bounds are checked.
        val fine =
            listOf(
                PeerMsg.Request(2, 0, 7_231),
                PeerMsg.Request(0, 1, 100),
                PeerMsg.Request(0, 8_192, 8_192),
                PeerMsg.Request(2, 7_000, 232),
                PeerMsg.Request(1, 16_383, 1),
                PeerMsg.Cancel(2, 0, 7_231),
                PeerMsg.Cancel(0, 5, 5_000),
            )
        for (m in fine) assertEquals(listOf(m), decode(encodeMessage(m), withLayout()), "$m")
        val bad =
            listOf(
                PeerMsg.Request(0, 8_192, 8_193),
                PeerMsg.Request(2, 7_000, 233),
                PeerMsg.Request(0, 16_384, 1),
                PeerMsg.Request(0, Int.MAX_VALUE, 1),
                PeerMsg.Request(0, Int.MAX_VALUE, BLOCK),
                PeerMsg.Request(3, 0, 1),
                PeerMsg.Request(-1, 0, 1),
                PeerMsg.Request(0, -1, 1),
                PeerMsg.Request(0, 0, 0),
                PeerMsg.Request(0, 0, -1),
                PeerMsg.Request(0, 0, BLOCK + 1),
                PeerMsg.Cancel(2, 7_000, 233),
                PeerMsg.Cancel(3, 0, 1),
                PeerMsg.Cancel(0, 0, 0),
            )
        for (m in bad) assertFailsWith<WireError>("$m") { decode(encodeMessage(m), withLayout()) }
    }

    @Test
    fun aPieceMessageStillHasToBeExactlyOneBlockWhateverARequestMayBe() {
        assertFailsWith<WireError> { decode(encodeMessage(PeerMsg.Piece(2, 0, ByteArray(7_231))), withLayout()) }
        assertFailsWith<WireError> { decode(encodeMessage(PeerMsg.Piece(0, 1, ByteArray(100))), withLayout()) }
    }

    @Test
    fun withALayoutABitfieldMustFitTheTorrentWithZeroSpareBits() {
        assertEquals(1, decode(encodeMessage(PeerMsg.Bitfield(bytes(0xE0))), withLayout()).size)
        assertFailsWith<WireError> { decode(encodeMessage(PeerMsg.Bitfield(bytes(0xE1))), withLayout()) }
        assertFailsWith<WireError> { decode(encodeMessage(PeerMsg.Bitfield(bytes(0xE0, 0))), withLayout()) }
        assertFailsWith<WireError> { decode(encodeMessage(PeerMsg.Bitfield(ByteArray(0))), withLayout()) }
    }

    @Test
    fun theHandshakeIsSixtyEightBytesInTheBitTorrentFormat() {
        val hash = ByteArray(20) { it.toByte() }
        val id = "-AD0001-abcdefghijkl".encodeToByteArray()
        val wire = encodeHandshake(hash, id)
        assertEquals(HANDSHAKE_SIZE, wire.size)
        assertEquals(19, wire[0].toInt())
        assertEquals("BitTorrent protocol", wire.copyOfRange(1, 20).decodeToString())
        assertContentEquals(ByteArray(8), wire.copyOfRange(20, 28))
        assertContentEquals(hash, wire.copyOfRange(28, 48))
        assertContentEquals(id, wire.copyOfRange(48, 68))
        val parsed = assertNotNull(parseHandshake(wire))
        assertContentEquals(hash, parsed.infoHash)
        assertContentEquals(id, parsed.peerId)
        assertContentEquals(ByteArray(8), parsed.reserved)
    }

    @Test
    fun aHandshakeKeepsTheReservedBitsThePeerSent() {
        val wire = encodeHandshake(ByteArray(20), ByteArray(20))
        wire[25] = 0x10
        assertEquals(0x10, assertNotNull(parseHandshake(wire)).reserved[5].toInt())
    }

    @Test
    fun aMalformedHandshakeIsRefused() {
        val good = encodeHandshake(ByteArray(20), ByteArray(20))
        assertNull(parseHandshake(good.copyOf(67)))
        assertNull(parseHandshake(good + 0))
        assertNull(parseHandshake(ByteArray(0)))
        assertNull(parseHandshake(good.copyOf().also { it[0] = 18 }))
        assertNull(parseHandshake(good.copyOf().also { it[1] = 'b'.code.toByte() }))
        assertNull(parseHandshake(good.copyOf().also { it[19] = 'L'.code.toByte() }))
        assertNull(parseHandshake(good, 0, 70))
        assertNull(parseHandshake(good, -1, 68))
        assertNull(parseHandshake(good, 60, 68))
    }

    @Test
    fun aHandshakeCanBeParsedFromInsideALargerBuffer() {
        val good = encodeHandshake(ByteArray(20) { 3 }, ByteArray(20) { 4 })
        val padded = ByteArray(5) + good + ByteArray(7)
        assertContentEquals(ByteArray(20) { 3 }, assertNotNull(parseHandshake(padded, 5, 68)).infoHash)
    }

    @Test
    fun encodingAHandshakeWithTheWrongSizedPartsIsAProgrammingError() {
        assertFailsWith<IllegalArgumentException> { encodeHandshake(ByteArray(19), ByteArray(20)) }
        assertFailsWith<IllegalArgumentException> { encodeHandshake(ByteArray(20), ByteArray(21)) }
    }
}
