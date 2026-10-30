package dev.anydm.desktop

import dev.anydm.engine.AddPlaylistResult
import dev.anydm.engine.AddTorrentResult
import dev.anydm.engine.DownloadSnapshot
import dev.anydm.engine.Engine
import dev.anydm.engine.EngineEvent
import dev.anydm.engine.EngineState
import dev.anydm.engine.InspectResult
import dev.anydm.engine.SpeedWindow
import dev.anydm.engine.TorrentDetailResult
import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.ItemSelection
import dev.anydm.engine.core.MirrorId
import dev.anydm.engine.files.Category
import dev.anydm.engine.files.Profile
import dev.anydm.engine.ftp.SshKeyLogin
import dev.anydm.engine.os.LinkProbeResult
import dev.anydm.engine.queue.CommandResult
import dev.anydm.engine.queue.RemoveFiles
import dev.anydm.engine.store.NewDownload
import dev.anydm.engine.store.Store
import dev.anydm.engine.torrent.TorrentInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * What the app reads from the engine and the commands it gives it; the tests stand in for it with a fake. Every command
 * throws IllegalStateException once the engine has stopped.
 */
interface EngineSource {
    val state: StateFlow<EngineState>
    val downloads: StateFlow<List<DownloadSnapshot>>
    val totalSpeed: StateFlow<SpeedWindow>
    val events: Flow<EngineEvent>

    suspend fun categories(): List<Category>

    suspend fun start()

    suspend fun stop()

    suspend fun pause(id: DownloadId): CommandResult

    suspend fun resume(id: DownloadId): CommandResult

    suspend fun cancel(id: DownloadId): CommandResult

    suspend fun remove(
        id: DownloadId,
        files: RemoveFiles,
    ): CommandResult

    suspend fun retry(
        id: DownloadId,
        allowDuplicate: Boolean,
    ): CommandResult

    suspend fun reprioritize(
        id: DownloadId,
        priority: Int,
    ): CommandResult

    suspend fun relink(
        id: DownloadId,
        mirrorId: MirrorId,
        url: String,
    ): CommandResult

    suspend fun stopSeeding(id: DownloadId): CommandResult

    suspend fun pauseAll(): CommandResult

    suspend fun resumeAll(): CommandResult

    suspend fun torrentDetail(id: DownloadId): TorrentDetailResult

    /** The kinds of download this engine can take; a part that couldn't be set up is missing. */
    val available: Set<DownloadKind>

    suspend fun profiles(): List<Profile>

    suspend fun probeLink(raw: String): LinkProbeResult

    suspend fun inspectTorrent(source: String): InspectResult

    suspend fun add(new: NewDownload): CommandResult

    suspend fun addTorrent(
        source: String,
        files: Set<String>?,
        sequential: Boolean,
        template: NewDownload,
        info: TorrentInfo?,
    ): AddTorrentResult

    suspend fun addPlaylist(
        url: String,
        selection: ItemSelection,
        template: NewDownload,
    ): AddPlaylistResult

    /** Keeps an SFTP key login for [url]'s server; false when it only reached memory. */
    suspend fun saveKeyLogin(
        url: String,
        login: SshKeyLogin,
    ): Boolean
}

fun Engine.asSource(
    store: Store,
    kinds: Set<DownloadKind>,
): EngineSource =
    object : EngineSource {
        private val engine = this@asSource
        override val available = kinds
        override val state = engine.state
        override val downloads = engine.downloads
        override val totalSpeed = engine.totalSpeed
        override val events = engine.events

        override suspend fun categories() = store.categories.list()

        override suspend fun start() = engine.start()

        // A failed engine's stop() does nothing, so its store is closed here.
        override suspend fun stop() {
            engine.stop()
            if (engine.state.value is EngineState.Failed) store.close()
        }

        override suspend fun pause(id: DownloadId) = engine.pause(id)

        override suspend fun resume(id: DownloadId) = engine.resume(id)

        override suspend fun cancel(id: DownloadId) = engine.cancel(id)

        override suspend fun remove(
            id: DownloadId,
            files: RemoveFiles,
        ) = engine.remove(id, files)

        override suspend fun retry(
            id: DownloadId,
            allowDuplicate: Boolean,
        ) = engine.retry(id, allowDuplicate)

        override suspend fun reprioritize(
            id: DownloadId,
            priority: Int,
        ) = engine.reprioritize(id, priority)

        override suspend fun relink(
            id: DownloadId,
            mirrorId: MirrorId,
            url: String,
        ) = engine.relink(id, mirrorId, url)

        override suspend fun stopSeeding(id: DownloadId) = engine.stopSeeding(id)

        override suspend fun pauseAll() = engine.pauseAll()

        override suspend fun resumeAll() = engine.resumeAll()

        override suspend fun torrentDetail(id: DownloadId) = engine.torrentDetail(id)

        override suspend fun profiles() = engine.profiles()

        override suspend fun probeLink(raw: String) = engine.probeLink(raw)

        override suspend fun inspectTorrent(source: String) = engine.inspectTorrent(source)

        override suspend fun add(new: NewDownload) = engine.add(new)

        override suspend fun addTorrent(
            source: String,
            files: Set<String>?,
            sequential: Boolean,
            template: NewDownload,
            info: TorrentInfo?,
        ) = engine.addTorrent(source, files, sequential, template, info)

        override suspend fun addPlaylist(
            url: String,
            selection: ItemSelection,
            template: NewDownload,
        ) = engine.addPlaylist(url, selection, template)

        override suspend fun saveKeyLogin(
            url: String,
            login: SshKeyLogin,
        ) = engine.saveKeyLogin(url, login)
    }
