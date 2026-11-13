package dev.anydm.engine.http

import java.security.SecureRandom

private val random = SecureRandom()

/** A fresh identity for a `.part` file: [TRAILER_BYTES] random bytes, as hex. */
internal fun newPartId(): String = partIdOf(ByteArray(TRAILER_BYTES).also { random.nextBytes(it) })

internal fun partIdOf(trailer: ByteArray): String = trailer.joinToString("") { "%02x".format(it) }

/** The trailer bytes of [partId]; null when it is not an identity this engine made. */
internal fun trailerOf(partId: String): ByteArray? {
    if (partId.length != TRAILER_BYTES * 2) return null
    return ByteArray(TRAILER_BYTES) {
        val hi = Character.digit(partId[it * 2], 16)
        val lo = Character.digit(partId[it * 2 + 1], 16)
        if (hi < 0 || lo < 0) return null
        ((hi shl 4) or lo).toByte()
    }
}
