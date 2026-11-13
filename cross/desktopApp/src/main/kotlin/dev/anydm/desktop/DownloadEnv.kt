package dev.anydm.desktop

import dev.anydm.engine.http.Clock
import dev.anydm.engine.http.DiskIo
import dev.anydm.engine.http.DownloadEngine
import dev.anydm.engine.http.Engine
import dev.anydm.engine.http.EngineEvent
import dev.anydm.engine.http.HttpProber
import dev.anydm.engine.http.JdkTransport
import dev.anydm.engine.http.JobSpec
import dev.anydm.engine.http.LinkProber
import dev.anydm.engine.http.PartFile
import dev.anydm.engine.http.ProbeOutcome
import dev.anydm.engine.http.RateShares
import dev.anydm.engine.http.RealDiskIo
import dev.anydm.engine.torrent.DefaultTorrentEngine
import dev.anydm.engine.torrent.HttpTrackerClient
import dev.anydm.engine.torrent.PEER_ID_PREFIX
import dev.anydm.engine.torrent.SchemeTrackerClient
import dev.anydm.engine.torrent.TcpPeerTransport
import dev.anydm.engine.torrent.TorrentEngine
import dev.anydm.engine.torrent.TorrentJob
import dev.anydm.engine.torrent.UdpTrackerClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.awt.Desktop
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom

/** Opens files in the OS. Both answer false when the file is not there or the OS cannot do it. Blocking: call off the UI thread. */
interface OsOpener {
    fun open(path: Path): Boolean

    fun reveal(path: Path): Boolean

    /** Moves the file to the OS Trash. False when it is not there or the OS has no Trash: the caller then deletes it for good. */
    fun trash(path: Path): Boolean
}

class RealOsOpener : OsOpener {
    override fun open(path: Path): Boolean = act(path, Desktop.Action.OPEN) { Desktop.getDesktop().open(path.toFile()) }

    override fun reveal(path: Path): Boolean =
        if (supports(Desktop.Action.BROWSE_FILE_DIR)) {
            act(path, Desktop.Action.BROWSE_FILE_DIR) { Desktop.getDesktop().browseFileDirectory(path.toFile()) }
        } else {
            val parent = path.parent
            parent != null && act(parent, Desktop.Action.OPEN) { Desktop.getDesktop().open(parent.toFile()) }
        }

    override fun trash(path: Path): Boolean =
        act(path, Desktop.Action.MOVE_TO_TRASH) { check(Desktop.getDesktop().moveToTrash(path.toFile())) }

    private fun supports(a: Desktop.Action) = Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(a)

    private fun act(
        path: Path,
        action: Desktop.Action,
        block: () -> Unit,
    ): Boolean {
        if (!Files.exists(path) || !supports(action)) return false
        return try {
            block()
            true
        } catch (_: Exception) {
            false
        }
    }
}

/** The engine, the link prober, the OS and the disk the Home screen works with. Tests pass fakes; [inert] touches nothing. */
data class DownloadEnv(
    val engine: Engine,
    val prober: LinkProber,
    val opener: OsOpener,
    val disk: DiskIo,
    /** How long a Download pressed before the link check answered waits for the server's name. */
    val probeWaitMs: Long = PROBE_WAIT_MS,
    /** Runs the torrent rows; the HTTP [engine] runs the rest. Both report through the same event types. */
    val torrents: TorrentEngine = InertTorrents,
    /** The speed cap and shares that [engine] and [torrents] both draw on, so the cap holds for their sum. */
    val rates: RateShares = RateShares(),
    /** The app's copy of each `.torrent` a row runs from. */
    val torrentFiles: TorrentFileStore = NoTorrentFiles,
    /** Our peer id for this run: [PEER_ID_PREFIX] and 12 random bytes. */
    val peerId: ByteArray = newPeerId(),
) {
    // The peer id is an array: the generated equality would compare it by reference, so two envs with the same id would differ.
    override fun equals(other: Any?): Boolean =
        this === other ||
            (
                other is DownloadEnv &&
                    engine == other.engine &&
                    prober == other.prober &&
                    opener == other.opener &&
                    disk == other.disk &&
                    probeWaitMs == other.probeWaitMs &&
                    torrents == other.torrents &&
                    rates == other.rates &&
                    torrentFiles == other.torrentFiles &&
                    peerId.contentEquals(other.peerId)
            )

    override fun hashCode(): Int =
        listOf(engine, prober, opener, disk, probeWaitMs, torrents, rates, torrentFiles).fold(peerId.contentHashCode()) { h, x ->
            31 * h + x.hashCode()
        }

    companion object {
        /**
         * Real downloads. The engine's jobs run in [scope], which must be the engine's own and have a supervisor: a job that fails
         * (or a probe) never cancels the others. Only the app's `main` builds this, and it passes no scope of the composition.
         */
        fun real(
            scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            /** The folder of `downloads.json`: the `.torrent` copies go to `torrents` beside it (made on the first save). */
            dataDir: Path = appDataDir(Platform.current(), System.getenv(), userHome(), System.getProperty("os.name").orEmpty()),
        ): DownloadEnv {
            val client = JdkTransport.defaultClient()
            val disk = RealDiskIo()
            val clock = Clock { System.currentTimeMillis() }
            // One object splits the speed limit over every running download, whichever engine runs it.
            val shares = RateShares()
            return DownloadEnv(
                DownloadEngine(
                    JdkTransport(client, DOWNLOAD_HEADERS_TIMEOUT_MS),
                    disk,
                    clock,
                    scope,
                    shares = shares,
                ),
                // A link check that takes longer than this is a failure: the user is waiting on "Checking link…".
                HttpProber(JdkTransport(client, PROBE_TIMEOUT_MS)),
                RealOsOpener(),
                disk,
                torrents =
                    DefaultTorrentEngine(
                        TcpPeerTransport(),
                        SchemeTrackerClient(HttpTrackerClient(), UdpTrackerClient(clock)),
                        disk,
                        clock,
                        scope,
                        shares,
                    ),
                rates = shares,
                torrentFiles = DiskTorrentFileStore(dataDir.resolve("torrents")),
            )
        }

        private fun userHome() = System.getProperty("user.home").orEmpty()

        /** [PEER_ID_PREFIX] and 12 random bytes: the 20 bytes trackers and peers know us by. */
        fun newPeerId(): ByteArray = PEER_ID_PREFIX.encodeToByteArray() + ByteArray(PEER_ID_RANDOM).also { SecureRandom().nextBytes(it) }

        private const val PEER_ID_RANDOM = 12

        /** How long a server may take to answer a download request before the row fails with "Network error". */
        const val DOWNLOAD_HEADERS_TIMEOUT_MS = 30_000L

        const val PROBE_TIMEOUT_MS = 10_000L

        const val PROBE_WAIT_MS = 5_000L

        /** No network, no files, no OS: the default for [HomeScreen], so a test can never download or open anything by accident. */
        fun inert(): DownloadEnv =
            DownloadEnv(
                object : Engine {
                    override val events: SharedFlow<EngineEvent> = MutableSharedFlow()

                    override fun start(job: JobSpec) = Unit

                    override fun stop(id: String) = Unit

                    override fun setRate(bytesPerSec: Long?) = Unit
                },
                object : LinkProber {
                    override suspend fun probe(url: String): ProbeOutcome = ProbeOutcome.Failed("offline")
                },
                object : OsOpener {
                    override fun open(path: Path) = false

                    override fun reveal(path: Path) = false

                    override fun trash(path: Path) = false
                },
                object : DiskIo {
                    override fun size(p: Path): Long? = null

                    override fun exists(p: Path) = false

                    override fun openPart(
                        p: Path,
                        append: Boolean,
                    ): OutputStream = throw UnsupportedOperationException("inert disk")

                    override fun move(
                        from: Path,
                        to: Path,
                    ) = Unit

                    override fun delete(p: Path) = Unit

                    override fun mkdirs(p: Path) = Unit

                    override fun openSpans(
                        p: Path,
                        size: Long?,
                    ): PartFile = throw UnsupportedOperationException("inert disk")

                    override fun freeSpace(dir: Path) = 0L

                    override fun readTail(
                        p: Path,
                        len: Int,
                    ): ByteArray? = null

                    override fun readAt(
                        p: Path,
                        pos: Long,
                        buf: ByteArray,
                        off: Int,
                        len: Int,
                    ): Int = throw UnsupportedOperationException("inert disk")
                },
            )
    }
}

/** A torrent engine that does nothing: the default of [DownloadEnv], so no test starts a torrent by accident. */
object InertTorrents : TorrentEngine {
    override val events: SharedFlow<EngineEvent> = MutableSharedFlow()

    override fun start(job: TorrentJob) = Unit

    override fun stop(id: String) = Unit
}
