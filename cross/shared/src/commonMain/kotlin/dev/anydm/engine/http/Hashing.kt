package dev.anydm.engine.http

internal enum class HashAlgorithm { MD5, SHA_256 }

/** [data]'s [algorithm] digest in lowercase hex. */
internal expect fun hashHex(
    algorithm: HashAlgorithm,
    data: ByteArray,
): String
