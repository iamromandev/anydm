package dev.anydm.engine.core

/**
 * One source of a [Download]. Mirrors are tried in [position] order, 0 first. (This is not a
 * priority: Download's priority runs the other way.)
 */
data class Mirror(
    val id: MirrorId,
    val downloadId: DownloadId,
    val url: String,
    val position: Int,
) {
    init {
        require(url.isNotBlank()) { "url must not be blank" }
        require(position >= 0) { "position must be >= 0: $position" }
    }
}
