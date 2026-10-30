package dev.anydm.desktop

import dev.anydm.engine.DownloadSnapshot
import dev.anydm.engine.EngineEvent
import dev.anydm.engine.EngineState
import dev.anydm.engine.FileSnapshot
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
import dev.anydm.engine.core.Mirror
import dev.anydm.engine.core.MirrorId
import dev.anydm.engine.core.PauseReason
import dev.anydm.engine.files.Category
import dev.anydm.engine.queue.CommandResult
import dev.anydm.engine.queue.RemoveFiles
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

    override suspend fun pause(id: DownloadId) = call("pause ${id.value}")

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
