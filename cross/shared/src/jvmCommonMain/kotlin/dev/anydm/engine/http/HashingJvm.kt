package dev.anydm.engine.http

import java.security.MessageDigest

internal actual fun hashHex(
    algorithm: HashAlgorithm,
    data: ByteArray,
): String {
    val name =
        when (algorithm) {
            HashAlgorithm.MD5 -> "MD5"
            HashAlgorithm.SHA_256 -> "SHA-256"
        }
    return MessageDigest.getInstance(name).digest(data).joinToString("") { "%02x".format(it) }
}
