package dev.anydm.desktop

import dev.anydm.engine.DownloadSnapshot
import dev.anydm.engine.Engine
import dev.anydm.engine.EngineEvent
import dev.anydm.engine.EngineState
import dev.anydm.engine.SpeedWindow
import dev.anydm.engine.core.CategoryId
import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.ItemSelection
import dev.anydm.engine.core.MirrorId
import dev.anydm.engine.core.ProfileId
import dev.anydm.engine.files.Category
import dev.anydm.engine.files.NameCleaner
import dev.anydm.engine.files.NewCategory
import dev.anydm.engine.files.NewProfile
import dev.anydm.engine.files.OsProfile
import dev.anydm.engine.files.Profile
import dev.anydm.engine.ftp.DefaultRemoteClients
import dev.anydm.engine.ftp.FtpProtocol
import dev.anydm.engine.ftp.JavaKeyringKeychain
import dev.anydm.engine.ftp.KeychainCredentials
import dev.anydm.engine.ftp.SshKeyLogin
import dev.anydm.engine.http.HttpProtocol
import dev.anydm.engine.http.OkHttpTransport
import dev.anydm.engine.media.SiteProtocol
import dev.anydm.engine.media.YtDlpPlaylistSource
import dev.anydm.engine.net.DefaultRouteNetwork
import dev.anydm.engine.platform.AppDirs
import dev.anydm.engine.platform.DesktopFileSystem
import dev.anydm.engine.platform.DesktopProcessRunner
import dev.anydm.engine.platform.FileResult
import dev.anydm.engine.platform.current
import dev.anydm.engine.platform.desktopAppDirs
import dev.anydm.engine.protocol.Protocol
import dev.anydm.engine.protocol.StepResult
import dev.anydm.engine.queue.RemoveFiles
import dev.anydm.engine.store.NewDownload
import dev.anydm.engine.store.Store
import dev.anydm.engine.store.TransferSettings
import dev.anydm.engine.store.openStore
import dev.anydm.engine.tools.DesktopPathFinder
import dev.anydm.engine.tools.DesktopUnpacker
import dev.anydm.engine.tools.GithubReleases
import dev.anydm.engine.tools.Platform
import dev.anydm.engine.tools.ToolCatalog
import dev.anydm.engine.tools.ToolManager
import dev.anydm.engine.tools.ToolProtocol
import dev.anydm.engine.tools.current
import dev.anydm.engine.torrent.JnaSignalGuard
import dev.anydm.engine.torrent.LibTorrentBackend
import dev.anydm.engine.torrent.TorrentConfig
import dev.anydm.engine.torrent.TorrentInfo
import dev.anydm.engine.torrent.TorrentNatives
import dev.anydm.engine.torrent.TorrentProtocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.io.files.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Clock

/**
 * The engine this machine runs, with every protocol it has: web files (HTTP/HTTPS), media pages and playlists (yt-dlp,
 * fetched by the tool manager), torrents (libtorrent) and FTP/FTPS/SFTP, with logins in the system keychain. A part that
 * can't be set up (no libtorrent for this machine, say) is left out and the rest still works; [EngineSource.available]
 * says which kinds are there. If the store can't be opened the app still starts, with an engine that reports itself failed.
 */
suspend fun openDesktopSource(): EngineSource =
    try {
        val fileSystem = DesktopFileSystem()
        val dirs = desktopAppDirs()
        when (val opened = openStore(dirs, fileSystem)) {
            is FileResult.Ok -> {
                val store = opened.value
                try {
                    desktopEngine(store, fileSystem, dirs)
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

private suspend fun desktopEngine(
    store: Store,
    fileSystem: DesktopFileSystem,
    dirs: AppDirs,
): EngineSource {
    val keychain = JavaKeyringKeychain("anydm")
    val logins = KeychainCredentials(keychain)
    // HTTP and FTP ask for the connections at each download; the source tells this when the setting changes.
    val connections = AtomicInteger(store.settings.load().connectionsPerDownload)
    val http = HttpProtocol(OkHttpTransport(), connections = { connections.get() }, credentials = logins)
    val runner = DesktopProcessRunner()
    val platform = Platform.current()
    val tools =
        ToolManager(
            catalog = ToolCatalog.Pinned,
            store = store.tools,
            runner = runner,
            fileSystem = fileSystem,
            finder = DesktopPathFinder(),
            releases = GithubReleases(OkHttpTransport()),
            dirs = dirs,
            platform = platform,
            settings = { store.settings.load() },
            clock = Clock.System,
            toolJobs = { store.downloads.list().filter { it.kind == DownloadKind.TOOL } },
        )
    val protocols =
        mutableMapOf<DownloadKind, Protocol>(
            DownloadKind.DIRECT to http,
            DownloadKind.FTP to FtpProtocol(DefaultRemoteClients(store.hostKeys), logins, logins, connections = { connections.get() }),
            DownloadKind.SITE to SiteProtocol(http, runner, tools::resolve, store.extractions, fileSystem),
            DownloadKind.TOOL to ToolProtocol(http, runner, DesktopUnpacker(runner), fileSystem, platform.os),
        )
    val torrents = torrentProtocol(store, fileSystem, http, dirs)
    if (torrents != null) protocols[DownloadKind.TORRENT] = torrents
    val engine =
        Engine(
            store = store,
            fileSystem = fileSystem,
            protocols = protocols,
            defaultFolder = Path(System.getProperty("user.home"), "Downloads"),
            identity = DefaultRouteNetwork(),
            osProfile = OsProfile.current(),
            tools = tools,
            playlists = YtDlpPlaylistSource(runner, tools::resolve),
            torrents = torrents,
            credentials = logins,
            proxyKeychain = keychain,
        )
    return engine.asSource(store, protocols.keys) { connections.set(it.connectionsPerDownload) }
}

// Torrents need libtorrent's native library for this machine, unpacked into the app's own data folder; without it they
// are left out.
private suspend fun torrentProtocol(
    store: Store,
    fileSystem: DesktopFileSystem,
    http: HttpProtocol,
    dirs: AppDirs,
): TorrentProtocol? =
    // Everything here may fail on a machine libtorrent doesn't support, down to the signal guard's own native load.
    try {
        val natives = TorrentNatives(Path(dirs.data, "natives"), signals = JnaSignalGuard())
        when (val loaded = natives.load()) {
            is StepResult.Failed -> {
                System.err.println("anydm: torrents are unavailable: ${loaded.message}")
                null
            }

            is StepResult.Ok -> {
                TorrentProtocol(
                    http,
                    LibTorrentBackend(natives, TorrentConfig({ store.settings.load().torrentPort })),
                    store.torrents,
                    NameCleaner(OsProfile.current()),
                    fileSystem,
                    { store.settings.load().clashPolicy },
                    settings = { store.settings.load() },
                    files = { store.downloads.files(it) },
                )
            }
        }
    } catch (e: Exception) {
        System.err.println("anydm: torrents are unavailable: ${e.message}")
        null
    } catch (e: LinkageError) {
        System.err.println("anydm: torrents are unavailable: ${e.message}")
        null
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

    override val available = emptySet<DownloadKind>()

    override suspend fun profiles() = stopped()

    override suspend fun probeLink(raw: String) = stopped()

    override suspend fun inspectTorrent(source: String) = stopped()

    override suspend fun add(new: NewDownload) = stopped()

    override suspend fun addTorrent(
        source: String,
        files: Set<String>?,
        sequential: Boolean,
        template: NewDownload,
        info: TorrentInfo?,
    ) = stopped()

    override suspend fun addPlaylist(
        url: String,
        selection: ItemSelection,
        template: NewDownload,
    ) = stopped()

    override suspend fun saveKeyLogin(
        url: String,
        login: SshKeyLogin,
    ) = stopped()

    override suspend fun transferSettings() = stopped()

    override suspend fun applyTransferSettings(next: TransferSettings) = stopped()

    override suspend fun resetTransferSettings() = stopped()

    override suspend fun addCategory(new: NewCategory) = stopped()

    override suspend fun updateCategory(category: Category) = stopped()

    override suspend fun deleteCategory(id: CategoryId) = stopped()

    override suspend fun addProfile(new: NewProfile) = stopped()

    override suspend fun updateProfile(profile: Profile) = stopped()

    override suspend fun deleteProfile(id: ProfileId) = stopped()

    override suspend fun defaultProfile() = stopped()

    override suspend fun setDefaultProfile(id: ProfileId?) = stopped()

    override suspend fun history(limit: Int) = stopped()

    override suspend fun clearHistory() = stopped()

    override suspend fun trafficByDay(
        from: String,
        to: String,
    ) = stopped()

    override suspend fun exportSettings() = stopped()

    override suspend fun importSettings(text: String) = stopped()
}
