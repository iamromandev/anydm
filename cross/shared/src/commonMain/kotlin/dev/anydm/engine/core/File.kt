package dev.anydm.engine.core

/**
 * One file a [Download] produces. Direct, site and FTP downloads usually have one File, at index 0;
 * a site download can have several (subtitle sidecars, or separate audio and video before merging).
 * A torrent has one per metainfo file.
 *
 * [path] is relative to the download's save folder. In JVM code, import this type with an alias
 * next to `java.io.File`.
 */
data class File(
    val id: FileId,
    val downloadId: DownloadId,
    val index: Int,
    val path: String,
    /** Bytes, or null when unknown. */
    val size: Long? = null,
    /** False for a torrent file the user chose to skip. */
    val selected: Boolean = true,
) {
    init {
        require(index >= 0) { "index must be >= 0: $index" }
        require(path.isNotBlank()) { "path must not be blank" }
        require(size == null || size >= 0) { "size must be >= 0: $size" }
    }
}
