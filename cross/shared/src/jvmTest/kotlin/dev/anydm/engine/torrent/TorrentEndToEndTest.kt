package dev.anydm.engine.torrent

import dev.anydm.engine.AddTorrentResult
import dev.anydm.engine.Engine
import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.DownloadStatus
import dev.anydm.engine.core.ErrorClass
import dev.anydm.engine.files.NameCleaner
import dev.anydm.engine.files.OsProfile
import dev.anydm.engine.http.HttpProtocol
import dev.anydm.engine.http.OkHttpTransport
import dev.anydm.engine.platform.DesktopFileSystem
import dev.anydm.engine.protocol.StepResult
import dev.anydm.engine.queue.CommandResult
import dev.anydm.engine.queue.RemoveFiles
import dev.anydm.engine.store.Store
import dev.anydm.engine.store.openInMemoryDatabase
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path
import org.junit.Assume.assumeTrue
import org.libtorrent4j.TorrentBuilder
import java.io.File
import java.net.ServerSocket
import java.net.URLClassLoader
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A torrent through the real engine, queue, step runner, torrent protocol, libtorrent and file system: a torrent file or a
 * magnet link in, files in the save folder out. Two libtorrent sessions on loopback, one seeding what the test made and
 * the engine's own downloading it, told where the seeder is. No tracker, no DHT.
 */
class TorrentEndToEndTest {
    private val root = Files.createTempDirectory("anydm-torrent-e2e-").toRealPath().toFile()
    private val dl = File(root, "dl").also { it.mkdirs() }
    private val seedDir = File(root, "seed").also { it.mkdirs() }
    private val metaDir = File(root, "meta").also { it.mkdirs() }
    private val seedPort = freePort()

    // One thread for the database, as the desktop store has it: several jobs write at once.
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val store = Store(openInMemoryDatabase(), Dispatchers.IO.limitedParallelism(1))
    private val seeder = LibTorrentBackend(nativesForTests, TorrentConfig({ seedPort }, loopbackOnly = true))
    private val backends = mutableListOf(seeder)
    private var engine: Engine? = null

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

    /** An engine over a libtorrent session of its own, which reaches the seeder through its address. */
    private fun newEngine(
        magnetTimeout: Duration = 30.seconds,
        natives: TorrentNatives = nativesForTests,
    ): Engine {
        val backend =
            LibTorrentBackend(natives, TorrentConfig({ freePort() }, loopbackOnly = true, peerHints = listOf("127.0.0.1:$seedPort")))
        backends += backend
        val protocol =
            TorrentProtocol(
                HttpProtocol(OkHttpTransport()),
                backend,
                store.torrents,
                NameCleaner(OsProfile.WINDOWS),
                DesktopFileSystem(),
                { store.settings.load().clashPolicy },
                magnetTimeout,
            )
        return Engine(
            store = store,
            fileSystem = DesktopFileSystem(),
            protocols = mapOf(DownloadKind.TORRENT to protocol),
            defaultFolder = Path(dl.toString()),
            dispatcher = Dispatchers.Default,
            checkpointEvery = 50.milliseconds,
            torrents = protocol,
        ).also {
            engine = it
            runBlocking { it.start() }
        }
    }

    private class Made(
        val bytes: ByteArray,
        val info: TorrentInfo,
        val file: File,
    ) {
        val source get() = "file://" + file.absolutePath
        val magnet get() = "magnet:?xt=urn:btih:${info.infoHash}"
    }

    /** Files in the seeder's folder, and the `.torrent` for them; not seeded yet. */
    private fun make(
        name: String,
        vararg files: Pair<String, ByteArray>,
    ): Made {
        runBlocking { nativesForTests.load() }
        files.forEach { (path, data) -> File(seedDir, path).also { it.parentFile.mkdirs() }.writeBytes(data) }
        val bytes =
            TorrentBuilder()
                .path(File(seedDir, name))
                .pieceSize(32 * 1024)
                .flags(TorrentBuilder.V1_ONLY)
                .generate()
                .entry()
                .bencode()
        val info = (seeder.metadataOfBytes(bytes) as StepResult.Ok).value
        return Made(bytes, info, File(metaDir, "$name.torrent").also { it.writeBytes(bytes) })
    }

    private fun bytes(
        size: Int,
        seed: Int,
    ) = Random(seed).nextBytes(size)

    private fun single(
        size: Int = 1024 * 1024,
        seed: Int = 1,
    ) = bytes(size, seed).let { it to make("data.bin", "data.bin" to it) }

    private fun pack(): Triple<ByteArray, ByteArray, Made> {
        val a = bytes(512 * 1024, 2)
        val b = bytes(512 * 1024, 3)
        return Triple(a, b, make("pack", "pack/a.bin" to a, "pack/b.bin" to b))
    }

    private suspend fun seed(made: Made) {
        val running =
            (
                seeder.add(
                    AddTorrent(made.info, Path(seedDir.toString()), emptyList(), false, made.info.files.map { it.path }),
                ) as StepResult.Ok
            ).value
        withTimeout(30.seconds) { while (!running.progress().finished) delay(50) }
    }

    private suspend fun add(
        made: Made,
        magnet: Boolean = false,
        sequential: Boolean = false,
    ): DownloadId =
        engine!!
            .addTorrent(if (magnet) made.magnet else made.source, sequential = sequential)
            .shouldBeInstanceOf<AddTorrentResult.Added>()
            .download.id

    private fun statusOf(id: DownloadId) =
        engine!!
            .downloads.value
            .firstOrNull { it.download.id == id }
            ?.download
            ?.status

    private suspend fun await(
        id: DownloadId,
        status: DownloadStatus,
    ) {
        try {
            withTimeout(60.seconds) {
                engine!!.downloads.first { snaps ->
                    snaps.firstOrNull { it.download.id == id }?.download?.status ==
                        status
                }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            val state =
                engine!!.downloads.value.joinToString("\n") {
                    "${it.download.id.value} ${it.download.status} downloaded=${it.downloaded} failure=${it.lastFailure?.errorClass} ${it.lastFailure?.message}"
                }
            throw AssertionError("timed out waiting for $status\n$state\ndl=${dl.walkTopDown().map { it.relativeTo(dl).path }.toList()}")
        }
    }

    private fun leftovers() = dl.list().orEmpty().filter { it.startsWith(".") }

    @Test
    fun aTorrentFileDownloadsAndMovesIntoPlace() =
        runBlocking<Unit> {
            val (data, made) = single()
            seed(made)
            newEngine()

            val id = add(made)
            await(id, DownloadStatus.COMPLETED)

            File(dl, "data.bin").readBytes().toList() shouldBe data.toList()
            dl.list().orEmpty().toList() shouldBe listOf("data.bin")
            val files = store.downloads.files(id)
            files.size shouldBe 1
            files.single().placedPath shouldBe "data.bin"
            statusOf(id) shouldBe DownloadStatus.COMPLETED
        }

    @Test
    fun aMagnetDownloadsWithAPeerHint() =
        runBlocking<Unit> {
            val (a, b, made) = pack()
            seed(made)
            newEngine()

            val id = add(made, magnet = true)
            await(id, DownloadStatus.COMPLETED)

            File(dl, "pack/a.bin").readBytes().toList() shouldBe a.toList()
            File(dl, "pack/b.bin").readBytes().toList() shouldBe b.toList()
            leftovers() shouldBe emptyList()
            // the metadata the magnet link fetched is kept with the job
            (store.torrents.get(id)!!.metadata != null) shouldBe true
        }

    @Test
    fun aSkippedFileIsNeverOnDisk() =
        runBlocking<Unit> {
            val (a, _, made) = pack()
            seed(made)
            val engine = newEngine()
            val info = engine.inspectTorrent(made.source).shouldBeInstanceOf<dev.anydm.engine.InspectResult.Inspected>().info

            val id =
                (engine.addTorrent(made.source, files = setOf("pack/a.bin"), info = info) as AddTorrentResult.Added).download.id
            await(id, DownloadStatus.COMPLETED)

            File(dl, "pack/a.bin").readBytes().toList() shouldBe a.toList()
            File(dl, "pack/b.bin").exists() shouldBe false
            leftovers() shouldBe emptyList()
            dl
                .walkTopDown()
                .filter { it.isFile }
                .map { it.relativeTo(dl).path }
                .toList() shouldBe listOf("pack/a.bin")
        }

    @Test
    fun sequentialModeCompletes() =
        runBlocking<Unit> {
            val (data, made) = single(seed = 4)
            seed(made)
            newEngine()

            val id = add(made, sequential = true)
            await(id, DownloadStatus.COMPLETED)

            File(dl, "data.bin").readBytes().toList() shouldBe data.toList()
            store.torrents.get(id)!!.sequential shouldBe true
        }

    @Test
    fun pauseThenResumeCompletes() =
        runBlocking<Unit> {
            val (data, made) = single(seed = 5)
            newEngine()

            // nobody has it yet: the job runs and waits for peers
            val id = add(made)
            await(id, DownloadStatus.DOWNLOADING)
            engine!!.pause(id).shouldBeInstanceOf<CommandResult.Done>()
            await(id, DownloadStatus.PAUSED)
            seed(made)
            engine!!.resume(id).shouldBeInstanceOf<CommandResult.Done>()
            await(id, DownloadStatus.COMPLETED)

            File(dl, "data.bin").readBytes().toList() shouldBe data.toList()
            leftovers() shouldBe emptyList()
        }

    @Test
    fun cancelKeepsTheStagingFolderAndRemoveDeletesIt() =
        runBlocking<Unit> {
            val (_, made) = single(seed = 6)
            newEngine()

            val id = add(made)
            await(id, DownloadStatus.DOWNLOADING)
            withTimeout(10.seconds) { while (leftovers().isEmpty()) delay(25) }
            engine!!.cancel(id).shouldBeInstanceOf<CommandResult.Done>()
            await(id, DownloadStatus.CANCELLED)

            leftovers().size shouldBe 1
            leftovers().single() shouldContain ".torrent"
            engine!!.remove(id, RemoveFiles.KEEP).shouldBeInstanceOf<CommandResult.Done>()
            leftovers() shouldBe emptyList()
        }

    @Test
    fun aClashUnderRenameKeepsBothFolders() =
        runBlocking<Unit> {
            val (a, _, made) = pack()
            seed(made)
            File(dl, "pack").mkdirs()
            File(dl, "pack/mine.txt").writeText("mine")
            newEngine()

            val id = add(made)
            await(id, DownloadStatus.COMPLETED)

            File(dl, "pack/mine.txt").readText() shouldBe "mine"
            File(dl, "pack (1)/a.bin").readBytes().toList() shouldBe a.toList()
            File(dl, "pack/a.bin").exists() shouldBe false
            leftovers() shouldBe emptyList()
        }

    @Test
    fun metadataThatNeverArrivesFailsRetryable() =
        runBlocking<Unit> {
            val engine = newEngine(magnetTimeout = 1.seconds)

            val id = (engine.addTorrent("magnet:?xt=urn:btih:" + "cd".repeat(20)) as AddTorrentResult.Added).download.id
            withTimeout(30.seconds) {
                engine.downloads.first { snaps -> snaps.firstOrNull { it.download.id == id }?.lastFailure?.errorClass != null }
            }

            val snapshot = engine.downloads.value.single { it.download.id == id }
            snapshot.lastFailure!!.errorClass shouldBe ErrorClass.TRANSIENT
            // retryable: it waits for another go, it is not failed for good
            snapshot.download.status shouldBe DownloadStatus.PENDING
        }

    @Test
    fun aCorruptTorrentFailsWithItsReason() =
        runBlocking<Unit> {
            val engine = newEngine()
            val file = File(metaDir, "bad.torrent").also { it.writeText("this is not a torrent") }

            val id = (engine.addTorrent("file://" + file.absolutePath) as AddTorrentResult.Added).download.id
            await(id, DownloadStatus.FAILED)

            engine.downloads.value
                .single { it.download.id == id }
                .lastFailure!!
                .message shouldContain "not a valid .torrent"
        }

    @Test
    fun addingTheSameTorrentTwiceIsRefused() =
        runBlocking<Unit> {
            val (_, made) = single(seed = 7)
            val engine = newEngine()
            engine.addTorrent(made.source).shouldBeInstanceOf<AddTorrentResult.Added>()

            val second = engine.addTorrent(made.source).shouldBeInstanceOf<AddTorrentResult.Refused>()

            second.result.shouldBeInstanceOf<CommandResult.Duplicate>()
            engine.downloads.value.size shouldBe 1
        }

    @Test
    fun engineStopClosesTheSession() =
        runBlocking<Unit> {
            val (_, made) = single(seed = 8)
            seed(made)
            val engine = newEngine()
            val id = add(made)
            await(id, DownloadStatus.COMPLETED)
            val backend = backends.last()

            engine.stop()

            backend
                .add(AddTorrent(made.info, Path(File(root, "again").toString()), emptyList(), false, listOf("data.bin")))
                .shouldBeInstanceOf<StepResult.Failed>()
                .message shouldContain "closed"
        }

    @Test
    fun theNativeFailureMessageIsClear() =
        runBlocking<Unit> {
            val (_, made) = single(seed = 9)
            val none = TorrentNatives(Path(File(root, "natives").toString()), URLClassLoader(emptyArray(), null))
            val engine = newEngine(natives = none)

            val id = (engine.addTorrent(made.source) as AddTorrentResult.Added).download.id
            await(id, DownloadStatus.FAILED)

            engine.downloads.value
                .single { it.download.id == id }
                .lastFailure!!
                .message shouldContain "torrents aren't supported here"
        }
}
