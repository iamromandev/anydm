package dev.anydm.engine.protocol

import dev.anydm.engine.core.Checksum

/**
 * What a probe learned. Each protocol returns its own implementation (#538 HttpProbe...) and gets it back unchanged;
 * the runner reads only these.
 */
interface ProbeResult {
    /** The name to save under (HTTP-02 falls back to one of its own); a user's file name wins. */
    val name: String

    /** Total bytes, or null when unknown. */
    val size: Long?

    /**
     * An opaque token for what the source is now (an ETag or Last-Modified for HTTP, #538; an infohash for torrents),
     * or null when this protocol can't tell. A later probe that returns a different one makes the download start over
     * (FND-10): the partial files may no longer belong to the source.
     */
    val validator: String? get() = null

    /** What the whole source hashes to, if the protocol learned it (HTTP's `Digest`); the user's checksum wins (HTTP-14). */
    val checksum: Checksum? get() = null

    /** The content's MIME type, lowercase and without parameters, if the protocol learned one (category rules, FIL-01). */
    val mime: String? get() = null
}
