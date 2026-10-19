package dev.anydm.engine.torrent

import dev.anydm.engine.core.ErrorClass
import dev.anydm.engine.protocol.StepResult
import kotlinx.coroutines.delay
import kotlin.time.Duration

/**
 * A [TorrentBackend] with no library: torrents are made with [torrent], whose "metadata" is its own hash as bytes;
 * magnets answer from [magnets]; [magnetNever] makes one wait out its timeout. Calls are recorded in [calls].
 */
internal class FakeTorrentBackend : TorrentBackend {
    val calls = mutableListOf<String>()
    val magnets = mutableMapOf<String, TorrentInfo>()
    var magnetNever = false
    var closed = 0
    private val known = mutableMapOf<String, TorrentInfo>()

    /** A torrent named [name] with these files (path to size, paths as the torrent has them), known to this backend. */
    fun torrent(
        name: String,
        vararg files: Pair<String, Long>,
        hash: String =
            name
                .padEnd(40, 'a')
                .take(40)
                .lowercase()
                .map { if (it in '0'..'9' || it in 'a'..'f') it else 'b' }
                .joinToString(""),
    ): TorrentInfo =
        TorrentInfo(name, hash, files.mapIndexed { i, (path, size) -> TorrentFile(i, path, size) }, hash.encodeToByteArray()).also {
            known[hash] =
                it
        }

    override suspend fun metadataOfMagnet(
        uri: String,
        timeout: Duration,
    ): StepResult<TorrentInfo> {
        calls += "magnet $uri"
        if (magnetNever) {
            delay(timeout)
            return StepResult.Failed(ErrorClass.TRANSIENT, "no metadata arrived")
        }
        return magnets[uri]?.let { StepResult.Ok(it) } ?: StepResult.Failed(ErrorClass.TRANSIENT, "no metadata arrived")
    }

    override fun metadataOfBytes(bytes: ByteArray): StepResult<TorrentInfo> {
        calls += "bytes"
        return known[bytes.decodeToString()]?.let { StepResult.Ok(it) } ?: StepResult.Failed(null, "not a valid .torrent: unreadable")
    }

    override suspend fun add(request: AddTorrent): StepResult<RunningTorrent> = StepResult.Failed(null, "the fake adds nothing yet")

    override suspend fun close() {
        closed++
    }
}
