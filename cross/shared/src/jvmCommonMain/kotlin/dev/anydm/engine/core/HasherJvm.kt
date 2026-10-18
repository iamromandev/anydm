package dev.anydm.engine.core

import java.security.MessageDigest

internal actual fun hasherFor(algorithm: ChecksumAlgorithm): Hasher {
    val digest =
        MessageDigest.getInstance(
            when (algorithm) {
                ChecksumAlgorithm.MD5 -> "MD5"
                ChecksumAlgorithm.SHA1 -> "SHA-1"
                ChecksumAlgorithm.SHA256 -> "SHA-256"
            },
        )
    return object : Hasher {
        override fun update(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ) = digest.update(bytes, offset, length)

        override fun hex(): String = digest.digest().joinToString("") { "%02x".format(it) }
    }
}
