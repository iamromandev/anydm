package dev.anydm.engine.core

/** A digest fed piece by piece, so a file of any size is hashed without holding it (HTTP-14). */
internal interface Hasher {
    fun update(
        bytes: ByteArray,
        offset: Int = 0,
        length: Int = bytes.size - offset,
    )

    /** The digest of everything fed, in lowercase hex. Call once. */
    fun hex(): String
}

internal expect fun hasherFor(algorithm: ChecksumAlgorithm): Hasher
