package dev.anydm.engine.protocol

/**
 * What a probe learned. Each protocol returns its own implementation (#538 HttpProbe...) and gets it back unchanged;
 * the runner reads only these.
 */
interface ProbeResult {
    /** The name to save under (HTTP-02 falls back to one of its own); a user's file name wins. */
    val name: String

    /** Total bytes, or null when unknown. */
    val size: Long?
}
