package dev.anydm.engine.torrent

import dev.anydm.engine.protocol.StepResult
import kotlinx.io.files.Path
import kotlin.time.Duration

/** What to start: the torrent, where libtorrent keeps its files, a priority per file, the order, and each file's name under [saveTo]. */
class AddTorrent(
    val info: TorrentInfo,
    val saveTo: Path,
    val priorities: List<Int>,
    val sequential: Boolean,
    val fileNames: List<String>,
)

/** [LOST]: the torrent is no longer in the session (it will be added again on a retry). */
enum class TorrentErrorKind { DISK_FULL, LOST, OTHER }

class TorrentError(
    val kind: TorrentErrorKind,
    val message: String,
)

/** How far a running torrent is: the bytes of the wanted files done and wanted, whether they are all done, and what stopped it. */
class TorrentProgress(
    val wantedDone: Long,
    val wanted: Long,
    val finished: Boolean,
    val error: TorrentError? = null,
)

/** One torrent in the session. Its calls come from the transfer's own coroutine; nothing here runs engine code on a library thread. */
interface RunningTorrent {
    fun progress(): TorrentProgress

    /** New priorities for the files, one per file in torrent order. */
    fun setPriorities(priorities: List<Int>)

    fun setSequential(on: Boolean)

    /** Renames each file to [names] (relative to the torrent's save path), one per file in torrent order. */
    suspend fun renameFiles(names: List<String>): StepResult<Unit>

    /** Moves the files to [to] (TOR-10) and waits for the library to say so; [replaceExisting] overwrites what is there. */
    suspend fun moveStorage(
        to: Path,
        replaceExisting: Boolean,
    ): StepResult<Unit>

    /** Takes the torrent out of the session, keeping its files. */
    suspend fun remove()
}

/** The library underneath (libtorrent4j); a fake stands in for it in most tests. */
interface TorrentBackend {
    /** The metadata of a magnet link, waited for up to [timeout]; TRANSIENT when it doesn't come. */
    suspend fun metadataOfMagnet(
        uri: String,
        timeout: Duration,
    ): StepResult<TorrentInfo>

    /** The metadata in `.torrent` bytes, or why they aren't one. */
    fun metadataOfBytes(bytes: ByteArray): StepResult<TorrentInfo>

    suspend fun add(request: AddTorrent): StepResult<RunningTorrent>

    suspend fun close()
}
