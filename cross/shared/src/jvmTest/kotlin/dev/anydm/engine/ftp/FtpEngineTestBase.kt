package dev.anydm.engine.ftp

import dev.anydm.engine.Engine
import dev.anydm.engine.EngineEvent
import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.DownloadStatus
import dev.anydm.engine.http.HttpProtocol
import dev.anydm.engine.http.OkHttpTransport
import dev.anydm.engine.log.Log
import dev.anydm.engine.log.LogLevel
import dev.anydm.engine.log.LogSink
import dev.anydm.engine.platform.DesktopFileSystem
import dev.anydm.engine.queue.CommandResult
import dev.anydm.engine.store.NewDownload
import dev.anydm.engine.store.Store
import dev.anydm.engine.store.openDatabaseFile
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path
import java.io.File
import java.nio.file.Files
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * What the end-to-end FTP tests share: FTP and SFTP servers on loopback, a keychain in memory that outlives an engine, and
 * an engine over a file database, so an engine can be stopped and another started over the same jobs. The log and the
 * events are kept to check that no secret reaches them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal abstract class FtpEngineTestBase {
    protected val root = Files.createTempDirectory("anydm-ftp-e2e-").toRealPath().toFile()
    protected val dl = File(root, "dl").also { it.mkdirs() }
    protected val keyDir = File(root, "keys").also { it.mkdirs() }
    protected val dbFile = File(root, "anydm.db")
    protected val keychain = FakeKeychain()
    protected val logins = KeychainCredentials(keychain)
    protected val logLines: MutableList<String> = Collections.synchronizedList(mutableListOf())
    protected val events: MutableList<EngineEvent> = Collections.synchronizedList(mutableListOf())
    protected var engine: Engine? = null
    protected var store: Store? = null

    private val servers = mutableListOf<AutoCloseable>()
    private val scope = CoroutineScope(Dispatchers.Default)

    // A test method returns nothing: runBlocking would return its last expression.
    protected fun blocking(block: suspend CoroutineScope.() -> Unit) = runBlocking { block() }

    @AfterTest
    fun close() {
        runBlocking { engine?.stop() }
        scope.cancel()
        servers.forEach { runCatching { it.close() } }
        root.deleteRecursively()
    }

    protected fun ftpServer(
        path: String = "/data.bin",
        bytes: ByteArray? = null,
    ) = FtpServerFixture().also {
        servers += it
        if (bytes != null) it.put(path, bytes)
    }

    protected fun sftpServer(
        authorized: List<ClientKey> = emptyList(),
        path: String = "/data.bin",
        bytes: ByteArray? = null,
    ) = SftpServerFixture(authorized = authorized).also {
        servers += it
        if (bytes != null) it.put(path, bytes)
    }

    protected fun <T : AutoCloseable> own(server: T): T = server.also { servers += it }

    /** An engine over the file database; it finds logins in [logins] and keeps its log and events for the test to read. */
    protected fun newEngine(): Engine {
        val opened = Store(openDatabaseFile(Path(dbFile.absolutePath)), Dispatchers.IO.limitedParallelism(1))
        store = opened
        val ftp = FtpProtocol(DefaultRemoteClients(opened.hostKeys), logins, logins, connections = { 4 })
        val http = HttpProtocol(OkHttpTransport(), credentials = logins)
        val log =
            Log(
                object : LogSink {
                    override fun write(line: String) {
                        logLines += line
                    }
                },
                Clock.System,
                minLevel = LogLevel.DEBUG,
            )
        return Engine(
            store = opened,
            fileSystem = DesktopFileSystem(),
            protocols = mapOf(DownloadKind.FTP to ftp, DownloadKind.DIRECT to http),
            defaultFolder = Path(dl.toString()),
            dispatcher = Dispatchers.Default,
            checkpointEvery = 50.milliseconds,
            log = log,
            credentials = logins,
        ).also { started ->
            engine = started
            runBlocking { started.start() }
            scope.launch(start = CoroutineStart.UNDISPATCHED) { started.events.collect { events += it } }
        }
    }

    protected suspend fun Engine.addUrl(
        url: String,
        kind: DownloadKind = DownloadKind.FTP,
    ): DownloadId =
        add(NewDownload(kind, mirrors = listOf(url)))
            .shouldBeInstanceOf<CommandResult.Done>()
            .download!!
            .id

    /** The first snapshot of job [id] that [matches], or a failure with the engine's state if none comes in [within]. */
    protected suspend fun awaitSnapshot(
        id: DownloadId,
        within: Duration = 60.seconds,
        matches: (dev.anydm.engine.DownloadSnapshot) -> Boolean,
    ): dev.anydm.engine.DownloadSnapshot {
        val running = engine!!
        try {
            return withTimeout(within) {
                running.downloads.first { snaps -> snaps.firstOrNull { it.download.id == id }?.let(matches) == true }.first {
                    it.download.id == id
                }
            }
        } catch (e: TimeoutCancellationException) {
            val state =
                running.downloads.value.joinToString("\n") {
                    "${it.download.id.value} ${it.download.status} downloaded=${it.downloaded} failure=${it.lastFailure?.message}"
                }
            throw AssertionError("no snapshot of job ${id.value} matched\n$state")
        }
    }

    protected suspend fun await(
        id: DownloadId,
        status: DownloadStatus,
        within: Duration = 60.seconds,
    ) = awaitSnapshot(id, within) { it.download.status == status }
}
