package dev.anydm.desktop

import dev.anydm.engine.AddPlaylistResult
import dev.anydm.engine.AddTorrentResult
import dev.anydm.engine.DownloadSnapshot
import dev.anydm.engine.EngineEvent
import dev.anydm.engine.EngineState
import dev.anydm.engine.FileSnapshot
import dev.anydm.engine.InspectResult
import dev.anydm.engine.SpeedWindow
import dev.anydm.engine.TorrentDetailResult
import dev.anydm.engine.core.Attempt
import dev.anydm.engine.core.CategoryId
import dev.anydm.engine.core.CollectionKind
import dev.anydm.engine.core.Download
import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.DownloadStatus
import dev.anydm.engine.core.ItemCounts
import dev.anydm.engine.core.ItemSelection
import dev.anydm.engine.core.Mirror
import dev.anydm.engine.core.MirrorId
import dev.anydm.engine.core.PauseReason
import dev.anydm.engine.files.Category
import dev.anydm.engine.files.Profile
import dev.anydm.engine.ftp.SshKeyLogin
import dev.anydm.engine.os.LinkIntake
import dev.anydm.engine.os.LinkProbeResult
import dev.anydm.engine.queue.CommandResult
import dev.anydm.engine.queue.RemoveFiles
import dev.anydm.engine.store.NewDownload
import dev.anydm.engine.torrent.TorrentInfo
import dev.anydm.engine.torrent.TorrentSummary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * An [EngineSource] the tests drive by hand. [calls] records each command as text ("pause 3", "remove 2 KEEP"); [results]
 * answers one by that text, [Done] otherwise; [refuseAll] makes every command throw as a stopped engine does.
 */
internal class FakeSource(
    var categoryList: List<Category> = emptyList(),
    var startError: Throwable? = null,
) : EngineSource {
    override val state = MutableStateFlow<EngineState>(EngineState.NotStarted)
    override val downloads = MutableStateFlow<List<DownloadSnapshot>>(emptyList())
    override val totalSpeed = MutableStateFlow(SpeedWindow.Empty)
    override val events = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 64)
    var started = 0
    var stopped = 0
    val calls = mutableListOf<String>()
    val results = mutableMapOf<String, CommandResult>()
    var refuseAll = false
    var torrent: TorrentDetailResult = TorrentDetailResult.NotInSession

    /** While set, every command waits for it after it is recorded: a command still in flight. */
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun categories(): List<Category> = categoryList

    override suspend fun start() {
        started++
        startError?.let { throw it }
        state.value = EngineState.Running
    }

    override suspend fun stop() {
        stopped++
        state.value = EngineState.Stopped
    }

    private suspend fun call(text: String): CommandResult {
        if (refuseAll) throw IllegalStateException("the engine has stopped")
        synchronized(calls) { calls += text }
        gate?.await()
        return results[text] ?: CommandResult.Done(null)
    }

    override suspend fun pause(id: DownloadId): CommandResult {
        pauseThrows?.let { throw it }
        return call("pause ${id.value}")
    }

    override suspend fun resume(id: DownloadId) = call("resume ${id.value}")

    override suspend fun cancel(id: DownloadId) = call("cancel ${id.value}")

    override suspend fun remove(
        id: DownloadId,
        files: RemoveFiles,
    ) = call("remove ${id.value} $files")

    override suspend fun retry(
        id: DownloadId,
        allowDuplicate: Boolean,
    ) = call(if (allowDuplicate) "retry ${id.value} anyway" else "retry ${id.value}")

    override suspend fun reprioritize(
        id: DownloadId,
        priority: Int,
    ) = call("priority ${id.value} $priority")

    override suspend fun relink(
        id: DownloadId,
        mirrorId: MirrorId,
        url: String,
    ) = call("relink ${id.value} ${mirrorId.value} $url")

    override suspend fun stopSeeding(id: DownloadId) = call("stopSeeding ${id.value}")

    override suspend fun pauseAll() = call("pauseAll")

    override suspend fun resumeAll() = call("resumeAll")

    override suspend fun torrentDetail(id: DownloadId): TorrentDetailResult {
        call("torrentDetail ${id.value}")
        return torrent
    }

    override var available: Set<DownloadKind> = setOf(DownloadKind.DIRECT, DownloadKind.SITE, DownloadKind.TORRENT, DownloadKind.FTP)
    var profileList: List<Profile> = emptyList()

    /** What [probeLink] answers per link; any other web link probes as a new file named after its last segment. */
    val probes = mutableMapOf<String, LinkProbeResult>()
    val probed = mutableListOf<String>()
    val inspects = mutableMapOf<String, InspectResult>()
    val addResults = mutableMapOf<String, CommandResult>()
    var torrentResult: AddTorrentResult? = null
    var playlistResult: AddPlaylistResult? = null

    /** Every add as the engine got it. */
    val added = mutableListOf<NewDownload>()
    val torrentsAdded = mutableListOf<TorrentAdd>()
    val playlistsAdded = mutableListOf<Triple<String, ItemSelection, NewDownload>>()
    val keyLogins = mutableListOf<Pair<String, SshKeyLogin>>()

    data class TorrentAdd(
        val source: String,
        val files: Set<String>?,
        val sequential: Boolean,
        val template: NewDownload,
        val info: TorrentInfo? = null,
    )

    /** Thrown by every add, as a store error would be. */
    var addThrows: Exception? = null

    /** What saving a key login answers: false when it only reached memory. */
    var keySaved = true

    /** Thrown by pause, as an engine stopping right then would. */
    var pauseThrows: Exception? = null

    override suspend fun profiles(): List<Profile> = profileList

    override suspend fun probeLink(raw: String): LinkProbeResult {
        if (refuseAll) throw IllegalStateException("the engine has stopped")
        synchronized(probed) { probed += raw }
        gate?.await()
        return probes[raw]
            ?: LinkIntake.parse(raw)?.let { LinkProbeResult.Probed(it.link, it.kind, it.link.substringAfterLast('/'), 1000, true, false) }
            ?: LinkProbeResult.NotALink
    }

    override suspend fun inspectTorrent(source: String): InspectResult = inspects[source] ?: InspectResult.Unavailable

    override suspend fun add(new: NewDownload): CommandResult {
        call("add ${new.mirrors.first()}")
        addThrows?.let { throw it }
        added += new
        return addResults[new.mirrors.first()] ?: CommandResult.Done(Download(DownloadId(100L + added.size), new.kind))
    }

    override suspend fun addTorrent(
        source: String,
        files: Set<String>?,
        sequential: Boolean,
        template: NewDownload,
        info: TorrentInfo?,
    ): AddTorrentResult {
        call("addTorrent $source")
        torrentsAdded += TorrentAdd(source, files, sequential, template, info)
        return torrentResult ?: AddTorrentResult.Added(Download(DownloadId(200), DownloadKind.TORRENT))
    }

    override suspend fun addPlaylist(
        url: String,
        selection: ItemSelection,
        template: NewDownload,
    ): AddPlaylistResult {
        call("addPlaylist $url")
        playlistsAdded += Triple(url, selection, template)
        return playlistResult ?: AddPlaylistResult.Added(Download(DownloadId(300), DownloadKind.SITE), emptyList(), 0, 0)
    }

    override suspend fun saveKeyLogin(
        url: String,
        login: SshKeyLogin,
    ): Boolean {
        call("saveKeyLogin $url")
        keyLogins += url to login
        return keySaved
    }
}

internal fun snapshot(
    id: Long,
    status: DownloadStatus,
    category: CategoryId? = null,
    pauseReasons: Set<PauseReason> = emptySet(),
    name: String? = null,
    size: Long? = null,
    downloaded: Long = 0,
    total: Long? = size,
    speed: Long = 0,
    priority: Int = 0,
    parent: Long? = null,
    collection: CollectionKind? = null,
    playlistIndex: Int? = null,
    items: ItemCounts? = null,
    attempts: List<Attempt> = emptyList(),
    mirrors: List<Mirror> = emptyList(),
    files: List<FileSnapshot> = emptyList(),
    torrent: TorrentSummary? = null,
    saveFolder: String? = null,
): DownloadSnapshot =
    DownloadSnapshot(
        download =
            Download(
                DownloadId(id),
                if (torrent != null) DownloadKind.TORRENT else DownloadKind.DIRECT,
                status,
                parentId = parent?.let(::DownloadId),
                collection = collection,
                categoryId = category,
                saveFolder = saveFolder,
                fileName = name,
                size = size,
                priority = priority,
                pauseReasons = pauseReasons,
                playlistIndex = playlistIndex,
            ),
        downloaded = downloaded,
        total = total,
        speed = speed,
        speedSamples = emptyList(),
        lastFailure = attempts.lastOrNull { it.errorClass != null || it.message != null },
        attempts = attempts,
        mirrors = mirrors,
        files = files,
        items = items,
        torrent = torrent,
    )

/** [FileActions] that only record what they were asked; [failure] is what each answers. */
internal class FakeFiles(
    var failure: String? = null,
) : FileActions {
    val done = mutableListOf<String>()

    override fun open(path: java.nio.file.Path) = failure.also { done += "open $path" }

    override fun reveal(path: java.nio.file.Path) = failure.also { done += "reveal $path" }

    override fun copy(text: String) = failure.also { done += "copy $text" }
}
