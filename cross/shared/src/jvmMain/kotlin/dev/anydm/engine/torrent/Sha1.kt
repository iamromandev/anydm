package dev.anydm.engine.torrent

import java.security.MessageDigest

actual fun sha1(
    bytes: ByteArray,
    off: Int,
    len: Int,
): ByteArray {
    val digest = MessageDigest.getInstance("SHA-1")
    digest.update(bytes, off, len)
    return digest.digest()
}
