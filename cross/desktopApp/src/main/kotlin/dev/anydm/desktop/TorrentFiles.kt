package dev.anydm.desktop

import dev.anydm.engine.torrent.MAX_TORRENT_BYTES
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

private val infoHashHex = Regex("^[0-9a-f]{40}$")

/** Whether [s] is an info hash as this app writes it: 40 lowercase hex digits. */
fun isInfoHashHex(s: String): Boolean = infoHashHex.matches(s)

/**
 * The app's own copy of each `.torrent` it was given, by info hash (R2): a row resumes from it even when the original is moved or
 * deleted. [save] and [delete] block on the disk: call them off the UI thread. [path] only builds a path.
 */
interface TorrentFileStore {
    /** Writes [bytes] as the copy of [hex]; an existing copy is replaced. Throws [IOException] when the disk will not take it. */
    fun save(
        hex: String,
        bytes: ByteArray,
    )

    fun path(hex: String): Path

    /** The bytes of the copy of [hex]; null when there is none or it cannot be read. Blocks on the disk. */
    fun read(hex: String): ByteArray?

    /** Removes the copy of [hex]; no error when there is none. */
    fun delete(hex: String)
}

/**
 * `torrents/<infohash>.torrent` in [dir]. The folder is made on the first save, never before. A copy is written to a temp file of its
 * own (so saves and deletes of the same hash at once never meet in one file), forced to disk, and moved over the real name in one
 * step ([move]: atomic where the file system can, else a replacing move): a crash or a full disk never leaves half a copy under it.
 */
class DiskTorrentFileStore(
    private val dir: Path,
    private val move: (from: Path, to: Path) -> Unit = { from, to -> Files.move(from, to, StandardCopyOption.ATOMIC_MOVE) },
) : TorrentFileStore {
    override fun path(hex: String): Path {
        require(isInfoHashHex(hex)) { "not an info hash" }
        return dir.resolve("$hex.torrent")
    }

    override fun save(
        hex: String,
        bytes: ByteArray,
    ) {
        val target = path(hex)
        Files.createDirectories(dir)
        val tmp = Files.createTempFile(dir, "$hex.", ".tmp")
        try {
            FileChannel.open(tmp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { ch ->
                val buf = ByteBuffer.wrap(bytes)
                while (buf.hasRemaining()) ch.write(buf)
                ch.force(true)
            }
            try {
                move(tmp, target)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    override fun read(hex: String): ByteArray? =
        try {
            val p = path(hex)
            if (Files.isRegularFile(p) && Files.size(p) <= MAX_TORRENT_BYTES) Files.readAllBytes(p) else null
        } catch (_: IOException) {
            null
        }

    override fun delete(hex: String) {
        Files.deleteIfExists(path(hex))
    }
}

/** Keeps nothing: the default for [DownloadEnv.inert], so a test or a preview never writes a file. */
object NoTorrentFiles : TorrentFileStore {
    override fun save(
        hex: String,
        bytes: ByteArray,
    ) = Unit

    override fun path(hex: String): Path = Path.of("torrents", "$hex.torrent")

    override fun read(hex: String): ByteArray? = null

    override fun delete(hex: String) = Unit
}
