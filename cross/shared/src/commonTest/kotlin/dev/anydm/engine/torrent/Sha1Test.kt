package dev.anydm.engine.torrent

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class Sha1Test {
    private fun hex(b: ByteArray) = b.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    @Test
    fun matchesTheKnownDigests() {
        assertEquals("da39a3ee5e6b4b0d3255bfef95601890afd80709", hex(sha1(ByteArray(0))))
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", hex(sha1("abc".encodeToByteArray())))
        assertEquals(
            "84983e441c3bd26ebaae4aa1f95129e5e54670f1",
            hex(sha1("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray())),
        )
    }

    @Test
    fun hashesOnlyTheGivenRange() {
        val bytes = "xxabcxx".encodeToByteArray()
        assertContentEquals(sha1("abc".encodeToByteArray()), sha1(bytes, 2, 3))
        assertContentEquals(sha1("abcxx".encodeToByteArray()), sha1(bytes, 2))
        assertContentEquals(sha1(ByteArray(0)), sha1(bytes, 7, 0))
    }

    @Test
    fun aMillionAsHaveTheKnownDigest() {
        assertEquals("34aa973cd4c4daa4f61eeb2bdbad27316534016f", hex(sha1(ByteArray(1_000_000) { 'a'.code.toByte() })))
    }
}
