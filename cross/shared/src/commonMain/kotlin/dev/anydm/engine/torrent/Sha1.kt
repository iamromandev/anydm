package dev.anydm.engine.torrent

/** The SHA-1 digest (20 bytes) of [len] bytes of [bytes] starting at [off]. */
expect fun sha1(
    bytes: ByteArray,
    off: Int = 0,
    len: Int = bytes.size - off,
): ByteArray
