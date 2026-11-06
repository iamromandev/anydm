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
    /** libtorrent's resume data from an earlier [RunningTorrent.saveResume], or null to check the files (TOR-06). */
    val resume: ByteArray? = null,
    /** The trackers the user added to this torrent earlier, put back on it as it starts. */
    val customTrackers: List<String> = emptyList(),
)

/**
 * A torrent's counters. [payloadDown] and [payloadUp] are the payload bytes moved since it was added to this session
 * (they restart with a re-add); [allTimeUp] and [seedingSeconds] carry across resume data. [wanted] is the bytes of the
 * files taken; [seeding] is whether it has everything it wants and is uploading.
 */
class TorrentStats(
    val payloadDown: Long,
    val payloadUp: Long,
    val allTimeUp: Long,
    val wanted: Long,
    val seedingSeconds: Long,
    val seeding: Boolean,
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
    /** Whether the torrent is private, as its metadata says; fixed for as long as it runs. */
    val isPrivate: Boolean

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

    fun stats(): TorrentStats

    /** libtorrent's resume data for the torrent as it is now; null if libtorrent could not make it in 10 seconds. */
    suspend fun saveResume(): ByteArray?

    /** What this torrent may take in, in bytes per second; null for no limit. */
    fun setDownloadLimit(bytesPerSecond: Long?)

    /** Peers, rates, availability and the bytes done of each file (TOR-07); [TorrentLiveStats.None] once the torrent is gone. */
    fun live(): TorrentLiveStats

    /** Checks the files against the torrent again; the progress climbs from zero meanwhile. Ignored while a check runs. */
    fun recheck()

    /**
     * Asks the tracker at [url] for peers now, ignoring its minimum interval, or every tracker when [url] is null.
     * A [url] the torrent doesn't have, or a tracker with no live endpoint (an untried backup tier, a disabled one), is ignored.
     */
    fun reannounce(url: String? = null)

    /**
     * Adds announce trackers the torrent doesn't have yet (the ones it has, and repeats, are ignored); they are reported with
     * [TorrentTracker.custom] set. Nothing happens once the torrent is gone.
     */
    fun addTrackers(urls: List<String>)

    /** The peer list, trackers and piece map; [TorrentDetail.Empty] once the torrent is gone. */
    fun detail(): TorrentDetail
}

/** The library underneath (libtorrent4j); a fake stands in for it in most tests. */
interface TorrentBackend {
    /**
     * A proxy setting changed (NET-15): the session follows the proxy its torrents now ask for, when they all ask for the
     * same one. A seed has no transfer to restart, so this is how it changes proxy.
     */
    suspend fun reroute() = Unit

    /**
     * The networks bonding uses changed (BND-04): the session sends its connections from these interfaces only, none for
     * its usual route. A session has one list, for all its torrents. Through a proxy nothing is bound (BND-05).
     */
    suspend fun rebind(interfaces: List<String>) = Unit

    /** The metadata of a magnet link, waited for up to [timeout]; TRANSIENT when it doesn't come. */
    suspend fun metadataOfMagnet(
        uri: String,
        timeout: Duration,
    ): StepResult<TorrentInfo>

    /** The metadata in `.torrent` bytes, or why they aren't one. */
    fun metadataOfBytes(bytes: ByteArray): StepResult<TorrentInfo>

    suspend fun add(request: AddTorrent): StepResult<RunningTorrent>

    /** The session's limits in bytes per second (null for none): applied when the session starts, and at once after. */
    fun setSessionLimits(
        download: Long?,
        upload: Long?,
    )

    /** Told the resume data of every torrent still in the session when [close] stops it. */
    var resumeSink: ((infoHash: String, bytes: ByteArray) -> Unit)?

    suspend fun close()
}
