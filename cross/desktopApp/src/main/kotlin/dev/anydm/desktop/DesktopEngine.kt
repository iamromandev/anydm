package dev.anydm.desktop

import dev.anydm.engine.DownloadSnapshot
import dev.anydm.engine.Engine
import dev.anydm.engine.EngineEvent
import dev.anydm.engine.EngineState
import dev.anydm.engine.SpeedWindow
import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.MirrorId
import dev.anydm.engine.files.Category
import dev.anydm.engine.files.OsProfile
import dev.anydm.engine.http.HttpProtocol
import dev.anydm.engine.http.OkHttpTransport
import dev.anydm.engine.net.DefaultRouteNetwork
import dev.anydm.engine.platform.DesktopFileSystem
import dev.anydm.engine.platform.FileResult
import dev.anydm.engine.platform.current
import dev.anydm.engine.platform.desktopAppDirs
import dev.anydm.engine.queue.RemoveFiles
import dev.anydm.engine.store.openStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.io.files.Path

/**
 * The engine this machine runs. Only plain HTTP(S) downloads are wired so far; the stories that add the other protocols
 * add them here. If the store can't be opened the app still starts, with an engine that reports itself failed.
 */
suspend fun openDesktopSource(): EngineSource =
    try {
        val fileSystem = DesktopFileSystem()
        when (val opened = openStore(desktopAppDirs(), fileSystem)) {
            is FileResult.Ok -> {
                val store = opened.value
                try {
                    Engine(
                        store = store,
                        fileSystem = fileSystem,
                        protocols = mapOf(DownloadKind.DIRECT to HttpProtocol(OkHttpTransport(), connections = { 4 })),
                        defaultFolder = Path(System.getProperty("user.home"), "Downloads"),
                        identity = DefaultRouteNetwork(),
                        osProfile = OsProfile.current(),
                    ).asSource(store)
                } catch (e: Exception) {
                    store.close()
                    throw e
                }
            }

            is FileResult.Failed -> {
                UnavailableSource(opened.detail ?: "the data folder ${opened.path} can't be used")
            }
        }
    } catch (e: Exception) {
        UnavailableSource(e.message ?: e::class.simpleName ?: "unknown error")
    }

/** Stands in when the engine could not be built: it is failed from the start and does nothing. */
internal class UnavailableSource(
    reason: String,
) : EngineSource {
    override val state = MutableStateFlow<EngineState>(EngineState.Failed(IllegalStateException(reason)))
    override val downloads = MutableStateFlow<List<DownloadSnapshot>>(emptyList())
    override val totalSpeed = MutableStateFlow(SpeedWindow.Empty)
    override val events = emptyFlow<EngineEvent>()

    override suspend fun categories(): List<Category> = emptyList()

    override suspend fun start() = Unit

    override suspend fun stop() = Unit

    // Like a stopped engine: every command is refused.
    private fun stopped(): Nothing = throw IllegalStateException("the engine has stopped")

    override suspend fun pause(id: DownloadId) = stopped()

    override suspend fun resume(id: DownloadId) = stopped()

    override suspend fun cancel(id: DownloadId) = stopped()

    override suspend fun remove(
        id: DownloadId,
        files: RemoveFiles,
    ) = stopped()

    override suspend fun retry(
        id: DownloadId,
        allowDuplicate: Boolean,
    ) = stopped()

    override suspend fun reprioritize(
        id: DownloadId,
        priority: Int,
    ) = stopped()

    override suspend fun relink(
        id: DownloadId,
        mirrorId: MirrorId,
        url: String,
    ) = stopped()

    override suspend fun stopSeeding(id: DownloadId) = stopped()

    override suspend fun pauseAll() = stopped()

    override suspend fun resumeAll() = stopped()

    override suspend fun torrentDetail(id: DownloadId) = stopped()
}
