package dev.anydm.engine.os

import kotlin.random.Random

/** HMAC-SHA-256 of [message] under [key]. */
internal expect fun hmacSha256(
    key: ByteArray,
    message: ByteArray,
): ByteArray

/** SHA-256 of [data]. */
internal expect fun sha256(data: ByteArray): ByteArray

/**
 * Whether [a] and [b] hold the same bytes, in a time that depends on their length and not on where they first differ. Every
 * comparison of a secret, a proof or a MAC goes through this one.
 */
internal expect fun constantTimeEquals(
    a: ByteArray,
    b: ByteArray,
): Boolean

/** A cryptographic random source: pairing codes, nonces and ids come from here and from nothing predictable. */
internal expect fun secureRandom(): Random

internal fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

/** The bytes of lowercase or uppercase hex text; null if it is not whole bytes of hex. */
internal fun String.hexToBytes(): ByteArray? {
    if (length % 2 != 0) return null
    val out = ByteArray(length / 2)
    for (i in out.indices) {
        val high = this[2 * i].digitToIntOrNull(16) ?: return null
        val low = this[2 * i + 1].digitToIntOrNull(16) ?: return null
        out[i] = ((high shl 4) or low).toByte()
    }
    return out
}
