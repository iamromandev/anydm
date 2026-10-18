package dev.anydm.engine.tools

/** A published build of a tool that isn't pinned in the catalog: its [version], where to fetch it and its SHA-256. */
data class Release(
    val version: String,
    val url: String,
    val sha256: String,
)

/** Where the newest yt-dlp comes from (BIN-04). */
fun interface ReleaseSource {
    /** The newest release of the channel for [platform], or null when it can't be read or has no build for it. */
    suspend fun latest(
        channel: YtDlpChannel,
        platform: Platform,
    ): Release?
}
