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
 * Seeding (TOR-05), resume data (TOR-06) and the meter (TOR-09) through the real engine and libtorrent, on loopback. The
 * test's own seeder session holds the torrent; the engine downloads it, then seeds it to a leecher session that is told
 * where the engine is. The database is a file, so an engine can be stopped and another started over the same jobs.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TorrentSeedingEndToEndTest {
    private val root = Files.createTempDirectory("anydm-seeding-e2e-").toRealPath().toFile()
    private val dl = File(root, "dl").also { it.mkdirs() }
    private val seedDir = File(root, "seed").also { it.mkdirs() }
    private val leechDir = File(root, "leech").also { it.mkdirs() }
    private val metaDir = File(root, "meta").also { it.mkdirs() }
    private val dbFile = File(root, "anydm.db")
    private val seedPort = freePort()
    private val enginePort = freePort()
    private val leechPort = freePort()

    private val seeder = LibTorrentBackend(nativesForTests, TorrentConfig({ seedPort }, loopbackOnly = true))
    private val leecher =
        LibTorrentBackend(nativesForTests, TorrentConfig({ leechPort }, loopbackOnly = true, peerHints = listOf("127.0.0.1:$enginePort")))
    private val backends = mutableListOf(seeder, leecher)
    private var store: Store? = null
    private var engine: Engine? = null
    private var protocol: TorrentProtocol? = null
    private var engineBackend: LibTorrentBackend? = null

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

    private fun freePort() = ServerSocket(0).use { it.localPort }

    private fun openStore(): Store = Store(openDatabaseFile(Path(dbFile.absolutePath)), Dispatchers.IO.limitedParallelism(1))

    /**
     * An engine over the file database, with a libtorrent session of its own that reaches the seeder through its address.
     * Seeds are checked every 200 ms, and a "minute" of seeding is [secondsPerMinute] seconds.
     */
    private fun newEngine(
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

    private class Made(
        val info: TorrentInfo,
        val file: File,
    ) {
        val source get() = "file://" + file.absolutePath
    }

    private fun make(
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

    private fun single(
        size: Int = 1024 * 1024,
        seed: Int = 1,
    ): Pair<ByteArray, Made> = Random(seed).nextBytes(size).let { it to make("data.bin", it) }

    private suspend fun seed(made: Made): RunningTorrent {
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
    private suspend fun leech(made: Made): RunningTorrent {
        val running =
            (
                leecher.add(
                    AddTorrent(made.info, Path(leechDir.toString()), emptyList(), false, made.info.files.map { it.path }),
                ) as StepResult.Ok
            ).value
        withTimeout(60.seconds) { while (!running.progress().finished) delay(50) }
        return running
    }

    private suspend fun add(
        made: Made,
        seedMinutes: SeedRule<Int> = SeedRule.Inherit,
        template: NewDownload = NewDownload(DownloadKind.TORRENT),
    ): DownloadId =
        engine!!
            .addTorrent(made.source, template = template, seedMinutes = seedMinutes)
            .shouldBeInstanceOf<AddTorrentResult.Added>()
            .download.id

    private suspend fun await(
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

    private fun leftovers() = dl.list().orEmpty().filter { it.startsWith(".") }

    // The runner removes the staging folder just after it stores COMPLETED.
    private suspend fun noLeftovers() {
        withTimeout(5.seconds) { while (leftovers().isNotEmpty()) delay(25) }
    }

    @Test
    fun aTorrentSeedsUntilItsRatioThenCompletes() =
        runBlocking<Unit> {
            val (data, made) = single()
            seed(made)
            // The leecher may learn of the test's seeder from the engine and take part of the file from it, so the ratio is a
            // fraction: whatever split, the engine sends more than 5% of its size.
            newEngine(settings = { it.copy(seedRatio = 0.05) })

            val id = add(made)
            await(id, DownloadStatus.SEEDING)
            File(dl, "data.bin").readBytes().toList() shouldBe data.toList()
            leech(made)
            await(id, DownloadStatus.COMPLETED)

            File(leechDir, "data.bin").readBytes().toList() shouldBe data.toList()
            File(dl, "data.bin").readBytes().toList() shouldBe data.toList()
            noLeftovers()
            // gone from the session: there is no seed left to stop
            protocol!!.stopSeeding(id) shouldBe false
        }

    @Test
    fun aTimeLimitEndsASeed() =
        runBlocking<Unit> {
            val (_, made) = single(seed = 2)
            seed(made)
            newEngine(secondsPerMinute = 1)

            val id = add(made, seedMinutes = SeedRule.At(1))
            await(id, DownloadStatus.SEEDING)
            await(id, DownloadStatus.COMPLETED)

            protocol!!.stopSeeding(id) shouldBe false
        }

    @Test
    fun stopSeedingEndsItNow() =
        runBlocking<Unit> {
            val (_, made) = single(seed = 3)
            seed(made)
            newEngine(settings = { it.copy(seedRatio = 100.0) })
            val id = add(made)
            await(id, DownloadStatus.SEEDING)

            engine!!.stopSeeding(id).shouldBeInstanceOf<CommandResult.Done>()
            await(id, DownloadStatus.COMPLETED)

            protocol!!.stopSeeding(id) shouldBe false
        }

    @Test
    fun aSeedSurvivesAnEngineRestart() =
        runBlocking<Unit> {
            val (data, made) = single(seed = 4)
            seed(made)
            newEngine(settings = { it.copy(seedRatio = 100.0) })
            val id = add(made)
            await(id, DownloadStatus.SEEDING)
            engine!!.stop()

            newEngine()
            await(id, DownloadStatus.SEEDING)
            leech(made)

            File(leechDir, "data.bin").readBytes().toList() shouldBe data.toList()
            engine!!.stopSeeding(id).shouldBeInstanceOf<CommandResult.Done>()
            await(id, DownloadStatus.COMPLETED)
        }

    @Test
    fun aPausedDownloadResumesWithItsSavedState() =
        runBlocking<Unit> {
            val size = 4 * 1024 * 1024
            val (data, made) = single(size = size, seed = 5)
            val serving = seed(made)
            newEngine()
            val id = add(made, template = NewDownload(DownloadKind.TORRENT, speedLimit = 256L * 1024))
            withTimeout(60.seconds) {
                engine!!.downloads.first { snaps -> (snaps.firstOrNull { it.download.id == id }?.downloaded ?: 0) >= 1024 * 1024 }
            }

            engine!!.pause(id).shouldBeInstanceOf<CommandResult.Done>()
            await(id, DownloadStatus.PAUSED)
            (store!!.torrents.get(id)!!.resume != null) shouldBe true
            engine!!.setSpeedLimit(id, null)
            engine!!.resume(id).shouldBeInstanceOf<CommandResult.Done>()
            await(id, DownloadStatus.COMPLETED)

            File(dl, "data.bin").readBytes().toList() shouldBe data.toList()
            // what the first run fetched (over 1 MiB) was not fetched again; pieces in flight at the pause are
            serving.stats().payloadUp shouldBeLessThan size + 768L * 1024
        }

    @Test
    fun aSeedWhoseFilesAreRemovedFails() =
        runBlocking<Unit> {
            val (_, made) = single(seed = 6)
            seed(made)
            newEngine(settings = { it.copy(seedRatio = 100.0) })
            val id = add(made)
            await(id, DownloadStatus.SEEDING)
            engine!!.stop()
            File(dl, "data.bin").delete() shouldBe true

            newEngine()

            await(id, DownloadStatus.FAILED)
            engine!!
                .downloads.value
                .first { it.download.id == id }
                .lastFailure!!
                .message!!
                .contains("data.bin") shouldBe true
        }

    @Test
    fun uploadIsMeteredForASeed() =
        runBlocking<Unit> {
            val size = 1024 * 1024
            val (_, made) = single(size = size, seed = 7)
            seed(made)
            newEngine(settings = { it.copy(seedRatio = 100.0) })
            val id = add(made)
            await(id, DownloadStatus.SEEDING)
            leech(made)
            // the seed's counters are sampled once a second
            delay(2500)

            engine!!.stop()

            val reopened = openStore()
            try {
                (reopened.traffic.downloadUpload(id) >= size) shouldBe true
            } finally {
                reopened.close()
            }
        }

    @Test
    fun theGlobalUploadLimitReachesTheSession() =
        runBlocking<Unit> {
            val (_, made) = single(seed = 8)
            seed(made)
            newEngine()
            val id = add(made)
            await(id, DownloadStatus.COMPLETED)

            engine!!.setGlobalUploadLimit(100_000).shouldBeInstanceOf<CommandResult.Done>()
            engineBackend!!.appliedSessionLimits() shouldBe (0 to 100_000)

            engine!!.setGlobalUploadLimit(null).shouldBeInstanceOf<CommandResult.Done>()
            engineBackend!!.appliedSessionLimits() shouldBe (0 to 0)
        }

    @Test
    fun noRuleFinishesAsCompletedImmediately() =
        runBlocking<Unit> {
            val (_, made) = single(seed = 9)
            seed(made)
            newEngine()

            val id = add(made)
            await(id, DownloadStatus.COMPLETED)

            protocol!!.stopSeeding(id) shouldBe false
            noLeftovers()
        }
}
