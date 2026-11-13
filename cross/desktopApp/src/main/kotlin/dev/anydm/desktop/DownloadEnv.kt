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
import dev.anydm.engine.http.RealDiskIo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.awt.Desktop
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path

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
) {
    companion object {
        /**
         * Real downloads. The engine's jobs run in [scope], which must be the engine's own and have a supervisor: a job that fails
         * (or a probe) never cancels the others. Only the app's `main` builds this, and it passes no scope of the composition.
         */
        fun real(scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)): DownloadEnv {
            val client = JdkTransport.defaultClient()
            val disk = RealDiskIo()
            return DownloadEnv(
                DownloadEngine(
                    JdkTransport(client, DOWNLOAD_HEADERS_TIMEOUT_MS),
                    disk,
                    Clock {
                        System.currentTimeMillis()
                    },
                    scope,
                ),
                // A link check that takes longer than this is a failure: the user is waiting on "Checking link…".
                HttpProber(JdkTransport(client, PROBE_TIMEOUT_MS)),
                RealOsOpener(),
                disk,
            )
        }

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
                },
            )
    }
}
