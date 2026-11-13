package dev.anydm.engine.torrent

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Seeded fuzzing of the message parser: whatever bytes arrive, only [WireError] may come out, and nothing it accepts may leave the torrent. */
class WireFuzzTest {
    private val layout = Layout(listOf(40_000L, 0L, 70_000L), 32_768)

    private fun int(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    private fun randomFrame(rnd: Random): ByteArray {
        val id = if (rnd.nextInt(10) == 0) rnd.nextInt(0, 256) else rnd.nextInt(0, 10)
        val declared =
            when (rnd.nextInt(8)) {
                0 -> rnd.nextInt()
                1 -> listOf(0, 1, 5, 9, 10, 13, 16_393, 16_394, 1 shl 20, (1 shl 20) + 1, -1, Int.MAX_VALUE).random(rnd)
                else -> -1
            }
        val payload = rnd.nextBytes(rnd.nextInt(0, if (rnd.nextInt(20) == 0) 20_000 else 40))
        val length = if (declared == -1) payload.size + 1 else declared
        return int(length) + byteArrayOf(id.toByte()) + payload
    }

    private fun randomValid(rnd: Random): PeerMsg =
        when (rnd.nextInt(9)) {
            0 -> {
                PeerMsg.KeepAlive
            }

            1 -> {
                PeerMsg.Choke
            }

            2 -> {
                PeerMsg.Unchoke
            }

            3 -> {
                PeerMsg.Interested
            }

            4 -> {
                PeerMsg.NotInterested
            }

            5 -> {
                PeerMsg.Have(rnd.nextInt(0, layout.pieceCount))
            }

            6 -> {
                PeerMsg.Bitfield(PieceSet.of(layout.pieceCount, (0 until layout.pieceCount).filter { rnd.nextBoolean() }).toBytes())
            }

            7 -> {
                layout.blocks(rnd.nextInt(0, layout.pieceCount)).random(rnd).let { PeerMsg.Request(it.piece, it.offset, it.length) }
            }

            else -> {
                layout
                    .blocks(
                        rnd.nextInt(0, layout.pieceCount),
                    ).random(rnd)
                    .let { PeerMsg.Piece(it.piece, it.offset, rnd.nextBytes(it.length)) }
            }
        }

    /** Feeds [data] in random chunk sizes. Returns what came out; [WireError] is the only exception allowed to propagate. */
    private fun feedAll(
        decoder: WireDecoder,
        data: ByteArray,
        rnd: Random,
    ): List<PeerMsg> {
        val out = ArrayList<PeerMsg>()
        var at = 0
        try {
            while (at < data.size) {
                val n = minOf(rnd.nextInt(1, 70_000), data.size - at)
                out += decoder.feed(data, at, n)
                at += n
            }
        } catch (_: WireError) {
            // the one allowed way out
        }
        return out
    }

    @Test
    fun tenThousandRandomFramesOnlyEverRaiseWireError() {
        val rnd = Random(42)
        var accepted = 0
        repeat(10_000) {
            val data = randomFrame(rnd) + if (rnd.nextBoolean()) randomFrame(rnd) else ByteArray(0)
            accepted += feedAll(WireDecoder(), data, rnd).size
            accepted += feedAll(WireDecoder(layout = layout), data, rnd).size
        }
        assertTrue(accepted > 0, "the fuzz input should get some messages through")
    }

    @Test
    fun randomByteNoiseOnlyEverRaisesWireError() {
        val rnd = Random(43)
        repeat(10_000) {
            val noise = rnd.nextBytes(rnd.nextInt(0, 200))
            feedAll(WireDecoder(), noise, rnd)
            feedAll(WireDecoder(layout = layout), noise, rnd)
        }
    }

    @Test
    fun noiseShapedLikeFramesWithSmallLengthsAlsoOnlyRaisesWireError() {
        val rnd = Random(44)
        repeat(10_000) {
            val parts =
                List(rnd.nextInt(1, 6)) {
                    byteArrayOf(0, 0, 0, rnd.nextInt(0, 20).toByte(), rnd.nextInt(0, 10).toByte()) +
                        rnd.nextBytes(rnd.nextInt(0, 20))
                }
            feedAll(WireDecoder(layout = layout), concat(parts), rnd)
        }
    }

    @Test
    fun streamsOfValidMessagesDecodeBackExactlyWhateverTheChunking() {
        val rnd = Random(45)
        repeat(300) {
            val msgs = List(rnd.nextInt(1, 12)) { randomValid(rnd) }
            val wire = concat(msgs.map { encodeMessage(it) })
            val d = WireDecoder(layout = layout)
            val out = ArrayList<PeerMsg>()
            var at = 0
            while (at < wire.size) {
                val n = minOf(rnd.nextInt(1, 40_000), wire.size - at)
                out += d.feed(wire, at, n)
                at += n
            }
            assertEquals(msgs, out)
        }
    }

    /** A well-formed request, cancel or piece frame with random, often out-of-range numbers. */
    private fun forgedBlock(rnd: Random): ByteArray {
        val piece = rnd.nextInt(-2, 8)
        val offset = if (rnd.nextBoolean()) rnd.nextInt(-5, 70_000) else rnd.nextInt(0, 6) * BLOCK
        return when (rnd.nextInt(3)) {
            0 -> encodeMessage(PeerMsg.Request(piece, offset, if (rnd.nextBoolean()) BLOCK else rnd.nextInt(-5, 40_000)))
            1 -> encodeMessage(PeerMsg.Cancel(piece, offset, if (rnd.nextBoolean()) BLOCK else rnd.nextInt(-5, 40_000)))
            else -> encodeMessage(PeerMsg.Piece(piece, offset, ByteArray(rnd.nextInt(0, BLOCK + 3))))
        }
    }

    @Test
    fun everyBlockTheLayoutDecoderAcceptsLiesInsideTheTorrentsFiles() {
        val rnd = Random(46)
        var checked = 0
        repeat(20_000) {
            for (msg in feedAll(WireDecoder(layout = layout), forgedBlock(rnd), rnd)) {
                val (piece, offset, length) =
                    when (msg) {
                        is PeerMsg.Request -> Triple(msg.piece, msg.offset, msg.length)
                        is PeerMsg.Cancel -> Triple(msg.piece, msg.offset, msg.length)
                        is PeerMsg.Piece -> Triple(msg.piece, msg.offset, msg.data.size)
                        else -> continue
                    }
                val segments = assertNotNull(layout.segments(piece, offset, length), "$msg")
                assertEquals(length, segments.sumOf { it.length })
                checked++
            }
        }
        assertTrue(checked > 100, "only $checked forged blocks were valid")
    }
}
