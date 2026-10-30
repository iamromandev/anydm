package dev.anydm.engine.torrent

import dev.anydm.engine.core.saturatingSum

/** One file of a torrent: its place in the metadata, its [path] as the torrent has it (`name/dir/file`), and its size. */
data class TorrentFile(
    val index: Int,
    val path: String,
    val size: Long,
)

/**
 * What a torrent's metadata says (TOR-02): [name], the [infoHash] in lowercase hex, the [files] in torrent order, and the
 * `.torrent` bytes themselves ([metadata]) so they can be stored. Two infos are equal when their hashes are.
 */
class TorrentInfo(
    val name: String,
    val infoHash: String,
    val files: List<TorrentFile>,
    val metadata: ByteArray,
) {
    /** All files' bytes. */
    val total: Long get() = files.map { it.size.coerceAtLeast(0) }.saturatingSum()

    /** One file with no folder: its name is the torrent's. */
    val singleFile: Boolean get() = files.size == 1 && '/' !in files.single().path

    override fun equals(other: Any?): Boolean = other is TorrentInfo && other.infoHash == infoHash

    override fun hashCode(): Int = infoHash.hashCode()

    override fun toString(): String = "TorrentInfo($name, $infoHash, ${files.size} files)"
}
