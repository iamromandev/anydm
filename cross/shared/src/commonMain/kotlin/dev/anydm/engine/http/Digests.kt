package dev.anydm.engine.http

import dev.anydm.engine.core.Checksum
import dev.anydm.engine.core.ChecksumAlgorithm

/** What the probe asks for, so servers that send a digest only when asked do (RFC 3230, RFC 9530). */
internal val wantDigests =
    listOf(
        "Want-Digest" to "sha-256;q=1, sha;q=0.5, md5;q=0.1",
        "Want-Repr-Digest" to "sha-256=10, sha=5, md5=1",
    )

private val digestNames =
    mapOf(
        "sha-256" to ChecksumAlgorithm.SHA256,
        "sha" to ChecksumAlgorithm.SHA1,
        "sha-1" to ChecksumAlgorithm.SHA1,
        "md5" to ChecksumAlgorithm.MD5,
    )

/**
 * The whole file's checksum from `Digest` (`SHA-256=<base64>`, RFC 3230) and `Repr-Digest` (`sha-256=:<base64>:`, RFC
 * 9530), the strongest found: SHA-256, then SHA-1, then MD5. An entry with an unknown name, bad base64 or the wrong
 * length is skipped. `Content-Digest` and `Content-MD5` are not read: on a ranged answer they cover only that range.
 */
internal fun checksumOf(headers: Headers): Checksum? {
    val entries =
        headers.all("Digest").flatMap { it.split(',') }.map { it to false } +
            headers.all("Repr-Digest").flatMap { it.split(',') }.map { it to true }
    val found =
        entries.mapNotNull { (entry, structured) ->
            val name = entry.substringBefore('=').trim().lowercase()
            val algorithm = digestNames[name] ?: return@mapNotNull null
            var value = entry.substringAfter('=', "").trim()
            if (structured) {
                if (value.length < 2 || !value.startsWith(':') || !value.endsWith(':')) return@mapNotNull null
                value = value.substring(1, value.length - 1)
            }
            val bytes = base64Decoded(value) ?: return@mapNotNull null
            if (bytes.size * 2 != algorithm.hexLength) return@mapNotNull null
            Checksum(algorithm, bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') })
        }
    return found.maxByOrNull { it.algorithm.hexLength }
}

private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

/** Standard base64, padding optional; null for anything else. */
private fun base64Decoded(text: String): ByteArray? {
    val body = text.trimEnd('=')
    if (text.length - body.length > 2 || body.length % 4 == 1) return null
    val out = ArrayList<Byte>(body.length * 3 / 4)
    var buffer = 0
    var bits = 0
    for (c in body) {
        val value = ALPHABET.indexOf(c)
        if (value < 0) return null
        buffer = (buffer shl 6) or value
        bits += 6
        if (bits >= 8) {
            bits -= 8
            out += (buffer shr bits and 0xff).toByte()
        }
    }
    return out.toByteArray()
}
