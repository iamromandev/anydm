package dev.anydm.engine.torrent

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertIs

/** Checks the common code's SHA-1 and info hash against the JDK's own digest, an independent implementation. */
class TorrentInfoHashJvmTest {
    private fun jdk(
        bytes: ByteArray,
        off: Int = 0,
        len: Int = bytes.size - off,
    ): ByteArray = MessageDigest.getInstance("SHA-1").also { it.update(bytes, off, len) }.digest()

    @Test
    fun sha1MatchesTheJdkOnRandomRanges() {
        val rnd = kotlin.random.Random(11)
        repeat(200) {
            val bytes = rnd.nextBytes(rnd.nextInt(0, 5000))
            val off = if (bytes.isEmpty()) 0 else rnd.nextInt(0, bytes.size)
            val len = rnd.nextInt(0, bytes.size - off + 1)
            assertContentEquals(jdk(bytes, off, len), sha1(bytes, off, len))
        }
    }

    @Test
    fun theInfoHashEqualsTheJdkDigestOfTheInfoBytesInTheFile() {
        for (seed in 1..5) {
            val built =
                torrentOf(
                    listOf("d/a" to 30_000, "d/b" to 12_345),
                    16_384,
                    contentOf(42_345, seed),
                    announce = "http://t/a",
                    private =
                        seed % 2 == 0,
                )
            val m = assertIs<TorrentResult.Ok>(readTorrent(built.bytes)).meta
            assertContentEquals(jdk(built.info), m.infoHash)
        }
    }

    @Test
    fun theInfoHashOfAnUnsortedInfoDictionaryIsTheJdkDigestOfItsRawBytes() {
        val info =
            "d4:name1:a6:lengthi5e12:piece lengthi16384e6:pieces20:".encodeToByteArray() + jdk(ByteArray(5)) + "e".encodeToByteArray()
        val file = "d4:info".encodeToByteArray() + info + "e".encodeToByteArray()
        assertContentEquals(jdk(info), assertIs<TorrentResult.Ok>(readTorrent(file)).meta.infoHash)
    }
}
