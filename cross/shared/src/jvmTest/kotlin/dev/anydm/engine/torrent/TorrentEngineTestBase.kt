package dev.anydm.engine.torrent

import dev.anydm.engine.AddTorrentResult
import dev.anydm.engine.Engine
import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.DownloadStatus
import dev.anydm.engine.files.NameCleaner
import dev.anydm.engine.files.OsProfile
import dev.anydm.engine.http.HttpProtocol
import dev.anydm.engine.http.OkHttpTransport
import dev.anydm.engine.platform.DesktopFileSystem
import dev.anydm.engine.protocol.StepResult
import dev.anydm.engine.queue.CommandResult
import dev.anydm.engine.store.NewDownload
import dev.anydm.engine.store.Settings
import dev.anydm.engine.store.Store
import dev.anydm.engine.store.openDatabaseFile
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path
import org.junit.Assume.assumeTrue
import org.libtorrent4j.TorrentBuilder
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * What the end-to-end torrent tests share: a test seeder session that holds the torrent, a leecher session that is told where
 * the engine is, and an engine over a file database with a libtorrent session of its own that reaches the seeder, all on
 * loopback. The database is a file, so an engine can be stopped and another started over the same jobs.
 */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class TorrentEngineTestBase {
    protected val root = Files.createTempDirectory("anydm-seeding-e2e-").toRealPath().toFile()
    protected val dl = File(root, "dl").also { it.mkdirs() }
    protected val seedDir = File(root, "seed").also { it.mkdirs() }
    protected val leechDir = File(root, "leech").also { it.mkdirs() }
    protected val metaDir = File(root, "meta").also { it.mkdirs() }
    protected val dbFile = File(root, "anydm.db")
    protected val seedPort = freePort()
    protected val enginePort = freePort()
    protected val leechPort = freePort()

    protected val seeder = LibTorrentBackend(nativesForTests, TorrentConfig({ seedPort }, loopbackOnly = true))
    protected val leecher =
        LibTorrentBackend(nativesForTests, TorrentConfig({ leechPort }, loopbackOnly = true, peerHints = listOf("127.0.0.1:$enginePort")))
    protected val backends = mutableListOf(seeder, leecher)
    protected var store: Store? = null
    protected var engine: Engine? = null
    protected var protocol: TorrentProtocol? = null
    protected var engineBackend: LibTorrentBackend? = null

    @BeforeTest
    fun hostHasANative() {
        val os = System.getProperty("os.name")
        val arch = System.getProperty("os.arch")
        assumeTrue("no libtorrent for $os $arch", nativeResource(os, arch) != null)
    }

    @AfterTest
    fun close() {
        runBlocking {
            engine?.stop()
            backends.forEach { it.close() }
        }
        root.deleteRecursively()
    }

    protected fun freePort() = ServerSocket(0).use { it.localPort }

    protected fun openStore(): Store = Store(openDatabaseFile(Path(dbFile.absolutePath)), Dispatchers.IO.limitedParallelism(1))

    /**
     * An engine over the file database, with a libtorrent session of its own that reaches the seeder through its address.
     * Seeds are checked every 200 ms, and a "minute" of seeding is [secondsPerMinute] seconds.
     */
    protected fun newEngine(
        secondsPerMinute: Int = 60,
        settings: (Settings) -> Settings = { it },
    ): Engine {
        runBlocking { nativesForTests.load() }
        val opened = openStore()
        runBlocking { opened.settings.update(settings) }
        store = opened
        val backend =
            LibTorrentBackend(
                nativesForTests,
                TorrentConfig({ enginePort }, loopbackOnly = true, peerHints = listOf("127.0.0.1:$seedPort")),
            )
        backends += backend
        engineBackend = backend
        val torrents =
            TorrentProtocol(
                HttpProtocol(OkHttpTransport()),
                backend,
                opened.torrents,
                NameCleaner(OsProfile.WINDOWS),
                DesktopFileSystem(),
                { opened.settings.load().clashPolicy },
                30.seconds,
                settings = { opened.settings.load() },
                seedPollEvery = 200.milliseconds,
                secondsPerMinute = secondsPerMinute,
                files = { opened.downloads.files(it) },
            )
        protocol = torrents
        return Engine(
            store = opened,
            fileSystem = DesktopFileSystem(),
            protocols = mapOf(DownloadKind.TORRENT to torrents),
            defaultFolder = Path(dl.toString()),
            dispatcher = Dispatchers.Default,
            checkpointEvery = 50.milliseconds,
            torrents = torrents,
        ).also {
            engine = it
            runBlocking { it.start() }
        }
    }

    protected class Made(
        val info: TorrentInfo,
        val file: File,
    ) {
        val source get() = "file://" + file.absolutePath
    }

    protected fun make(
        name: String,
        data: ByteArray,
    ): Made {
        runBlocking { nativesForTests.load() }
        File(seedDir, name).writeBytes(data)
        val bytes =
            TorrentBuilder()
                .path(File(seedDir, name))
                .pieceSize(32 * 1024)
                .flags(TorrentBuilder.V1_ONLY)
                .generate()
                .entry()
                .bencode()
        val info = (seeder.metadataOfBytes(bytes) as StepResult.Ok).value
        return Made(info, File(metaDir, "$name.torrent").also { it.writeBytes(bytes) })
    }

    protected fun single(
        size: Int = 1024 * 1024,
        seed: Int = 1,
    ): Pair<ByteArray, Made> = Random(seed).nextBytes(size).let { it to make("data.bin", it) }

    protected suspend fun seed(made: Made): RunningTorrent {
        val running =
            (
                seeder.add(
                    AddTorrent(made.info, Path(seedDir.toString()), emptyList(), false, made.info.files.map { it.path }),
                ) as StepResult.Ok
            ).value
        withTimeout(30.seconds) { while (!running.progress().finished) delay(50) }
        return running
    }

    /** The leecher session downloads [made] from wherever it can: only the engine, which it is told about. */
    protected suspend fun leech(made: Made): RunningTorrent = startLeech(made).also { finishLeech(it) }

    /** Starts the leecher's download without waiting for it, at [limit] bytes per second if given, so a test can look while it runs. */
    protected suspend fun startLeech(
        made: Made,
        limit: Long? = null,
    ): RunningTorrent {
        val running =
            (
                leecher.add(
                    AddTorrent(made.info, Path(leechDir.toString()), emptyList(), false, made.info.files.map { it.path }),
                ) as StepResult.Ok
            ).value
        if (limit != null) running.setDownloadLimit(limit)
        return running
    }

    protected suspend fun finishLeech(running: RunningTorrent) {
        running.setDownloadLimit(null)
        withTimeout(60.seconds) { while (!running.progress().finished) delay(50) }
    }

    /** The first snapshot of job [id] that [matches], or a failure with the engine's state if none comes in [within]. */
    protected suspend fun awaitSnapshot(
        id: DownloadId,
        within: Duration = 30.seconds,
        matches: (dev.anydm.engine.DownloadSnapshot) -> Boolean,
    ): dev.anydm.engine.DownloadSnapshot =
        try {
            withTimeout(within) {
                engine!!.downloads.first { snaps -> snaps.firstOrNull { it.download.id == id }?.let(matches) == true }.first {
                    it.download.id ==
                        id
                }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            val state =
                engine!!.downloads.value.joinToString("\n") {
                    "${it.download.id.value} ${it.download.status} downloaded=${it.downloaded} torrent=${it.torrent}"
                }
            throw AssertionError("no snapshot of job ${id.value} matched\n$state")
        }

    protected suspend fun add(
        made: Made,
        seedMinutes: SeedRule<Int> = SeedRule.Inherit,
        template: NewDownload = NewDownload(DownloadKind.TORRENT),
    ): DownloadId =
        engine!!
            .addTorrent(made.source, template = template, seedMinutes = seedMinutes)
            .shouldBeInstanceOf<AddTorrentResult.Added>()
            .download.id

    protected suspend fun await(
        id: DownloadId,
        status: DownloadStatus,
        within: Duration = 60.seconds,
    ) {
        try {
            withTimeout(within) {
                engine!!.downloads.first { snaps -> snaps.firstOrNull { it.download.id == id }?.download?.status == status }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            val state =
                engine!!.downloads.value.joinToString("\n") {
                    "${it.download.id.value} ${it.download.status} downloaded=${it.downloaded} failure=${it.lastFailure?.message}"
                }
            throw AssertionError("timed out waiting for $status\n$state")
        }
    }

    protected fun leftovers() = dl.list().orEmpty().filter { it.startsWith(".") }

    // The runner removes the staging folder just after it stores COMPLETED.
    protected suspend fun noLeftovers() {
        withTimeout(5.seconds) { while (leftovers().isNotEmpty()) delay(25) }
    }
}
