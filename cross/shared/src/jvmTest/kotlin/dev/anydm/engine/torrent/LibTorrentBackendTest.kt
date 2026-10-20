package dev.anydm.engine.torrent

import dev.anydm.engine.core.ErrorClass
import dev.anydm.engine.protocol.StepResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
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
import kotlin.time.Duration.Companion.seconds

/**
 * The libtorrent backend against a real libtorrent: two sessions on loopback, one seeding what the test made and one
 * downloading it. No tracker, no DHT: the downloader is told where the seeder is.
 */
class LibTorrentBackendTest {
    private val root = Files.createTempDirectory("anydm-libtorrent-test-").toRealPath().toFile()
    private val seedDir = File(root, "seed").also { it.mkdirs() }
    private val leechDir = File(root, "leech").also { it.mkdirs() }
    private val seedPort = freePort()
    private val backends = mutableListOf<LibTorrentBackend>()

    private val natives = nativesForTests

    private val signals get() = natives.signals

    private fun freePort() = ServerSocket(0).use { it.localPort }

    private fun backend(
        port: Int,
        vararg hints: String,
    ) = LibTorrentBackend(natives, TorrentConfig({ port }, loopbackOnly = true, peerHints = hints.toList())).also { backends += it }

    private val seeder by lazy { backend(seedPort) }
    private val leecher by lazy { backend(freePort(), "127.0.0.1:$seedPort") }

    @BeforeTest
    fun hostHasANative() {
        val os = System.getProperty("os.name")
        val arch = System.getProperty("os.arch")
        assumeTrue("no libtorrent for $os $arch", nativeResource(os, arch) != null)
    }

    @AfterTest
    fun close() {
        runBlocking { backends.forEach { it.close() } }
        root.deleteRecursively()
    }

    private fun torrentOf(path: File): ByteArray {
        runBlocking { natives.load() }
        return TorrentBuilder()
            .path(path)
            .pieceSize(32 * 1024)
            .flags(TorrentBuilder.V1_ONLY)
            .generate()
            .entry()
            .bencode()
    }

    private fun infoOf(bytes: ByteArray): TorrentInfo = (seeder.metadataOfBytes(bytes) as StepResult.Ok).value

    private fun <T> ok(result: StepResult<T>): T = result.shouldBeInstanceOf<StepResult.Ok<T>>().value

    private suspend fun seed(info: TorrentInfo): RunningTorrent {
        val running = ok(seeder.add(AddTorrent(info, Path(seedDir.toString()), emptyList(), false, info.files.map { it.path })))
        withTimeout(30.seconds) { while (!running.progress().finished) delay(50) }
        return running
    }

    private fun addRequest(
        info: TorrentInfo,
        to: File = leechDir,
        priorities: List<Int> = emptyList(),
        names: List<String> = info.files.map { it.path },
        sequential: Boolean = false,
    ) = AddTorrent(info, Path(to.toString()), priorities, sequential, names)

    private suspend fun finish(running: RunningTorrent): TorrentProgress =
        withTimeout(60.seconds) {
            while (true) {
                val progress = running.progress()
                if (progress.finished || progress.error != null) return@withTimeout progress
                delay(50)
            }
            error("unreachable")
        }

    private fun file(
        name: String,
        size: Int,
        seed: Int,
    ): ByteArray = Random(seed).nextBytes(size).also { File(seedDir, name).also { f -> f.parentFile.mkdirs() }.writeBytes(it) }

    @Test
    fun aSingleFileMovesBetweenTwoSessions() =
        runBlocking<Unit> {
            val data = file("data.bin", 1024 * 1024, 7)
            val info = infoOf(torrentOf(File(seedDir, "data.bin")))
            seed(info)

            val running = ok(leecher.add(addRequest(info, File(leechDir, "staging"))))
            val progress = finish(running)

            progress.error shouldBe null
            progress.finished shouldBe true
            progress.wantedDone shouldBe data.size.toLong()
            val final = File(root, "final")
            ok(running.moveStorage(Path(final.toString()), false))
            File(final, "data.bin").readBytes().toList() shouldBe data.toList()
            File(leechDir, "staging/data.bin").exists() shouldBe false
            running.remove()
        }

    @Test
    fun aMultiFileTorrentWithASkippedFileNeverCreatesIt() =
        runBlocking<Unit> {
            val a = file("pack/a.bin", 512 * 1024, 1)
            file("pack/b.bin", 512 * 1024, 2)
            val info = infoOf(torrentOf(File(seedDir, "pack")))
            seed(info)
            val skipped = info.files.single { it.path.endsWith("b.bin") }.index
            val priorities = info.files.map { if (it.index == skipped) 0 else 4 }

            val running = ok(leecher.add(addRequest(info, File(leechDir, "staging"), priorities)))
            val progress = finish(running)

            progress.finished shouldBe true
            progress.wanted shouldBe a.size.toLong()
            val final = File(root, "final")
            ok(running.moveStorage(Path(final.toString()), false))
            File(final, "pack/a.bin").readBytes().toList() shouldBe a.toList()
            File(final, "pack/b.bin").exists() shouldBe false
            File(leechDir, "staging/pack/b.bin").exists() shouldBe false
            // libtorrent's own bookkeeping isn't left in the destination as a download
            final
                .walkTopDown()
                .filter { it.isFile }
                .map { it.relativeTo(final).path }
                .toList() shouldBe listOf("pack/a.bin")
            running.remove()
        }

    @Test
    fun renamedFilesLandUnderTheNewNames() =
        runBlocking<Unit> {
            val data = file("data.bin", 256 * 1024, 3)
            val info = infoOf(torrentOf(File(seedDir, "data.bin")))
            seed(info)

            val running = ok(leecher.add(addRequest(info, File(leechDir, "staging"), names = listOf("renamed/new name.bin"))))
            finish(running).finished shouldBe true

            File(leechDir, "staging/renamed/new name.bin").readBytes().toList() shouldBe data.toList()
            File(leechDir, "staging/data.bin").exists() shouldBe false
            running.remove()
        }

    @Test
    fun renamingAfterTheDownloadMovesTheFilesOnDisk() =
        runBlocking<Unit> {
            val data = file("data.bin", 256 * 1024, 4)
            val info = infoOf(torrentOf(File(seedDir, "data.bin")))
            seed(info)
            val running = ok(leecher.add(addRequest(info, File(leechDir, "staging"))))
            finish(running).finished shouldBe true

            ok(running.renameFiles(listOf("data (1).bin")))
            val final = File(root, "final")
            ok(running.moveStorage(Path(final.toString()), false))

            File(final, "data (1).bin").readBytes().toList() shouldBe data.toList()
            running.remove()
        }

    @Test
    fun moveStorageFailsWhenAFileIsInTheWay() =
        runBlocking<Unit> {
            file("data.bin", 256 * 1024, 5)
            val info = infoOf(torrentOf(File(seedDir, "data.bin")))
            seed(info)
            val running = ok(leecher.add(addRequest(info, File(leechDir, "staging"))))
            finish(running)
            val final = File(root, "final").also { it.mkdirs() }
            File(final, "data.bin").writeText("mine")

            running.moveStorage(Path(final.toString()), false).shouldBeInstanceOf<StepResult.Failed>()

            File(final, "data.bin").readText() shouldBe "mine"
            running.remove()
        }

    @Test
    fun removeKeepsTheFiles() =
        runBlocking<Unit> {
            val data = file("data.bin", 256 * 1024, 6)
            val info = infoOf(torrentOf(File(seedDir, "data.bin")))
            seed(info)
            val running = ok(leecher.add(addRequest(info, File(leechDir, "staging"))))
            finish(running)

            running.remove()

            File(leechDir, "staging/data.bin").readBytes().toList() shouldBe data.toList()
        }

    @Test
    fun magnetMetadataArrivesWithAPeerHint() =
        runBlocking<Unit> {
            file("data.bin", 256 * 1024, 8)
            val info = infoOf(torrentOf(File(seedDir, "data.bin")))
            seed(info)

            val fetched = ok(leecher.metadataOfMagnet("magnet:?xt=urn:btih:${info.infoHash}", 30.seconds))

            fetched.infoHash shouldBe info.infoHash
            fetched.name shouldBe "data.bin"
            fetched.files.map { it.path } shouldBe listOf("data.bin")
            fetched.files.single().size shouldBe 256L * 1024
        }

    @Test
    fun aMagnetWithNoPeerTimesOutTransient() =
        runBlocking<Unit> {
            val alone = backend(freePort())

            val failed = alone.metadataOfMagnet("magnet:?xt=urn:btih:" + "ab".repeat(20), 1.seconds).shouldBeInstanceOf<StepResult.Failed>()

            failed.errorClass shouldBe ErrorClass.TRANSIENT
        }

    @Test
    fun aCorruptTorrentIsRefused() {
        val failed = seeder.metadataOfBytes("this is not a torrent".toByteArray()).shouldBeInstanceOf<StepResult.Failed>()

        failed.message shouldContain "not a valid .torrent"
        failed.errorClass shouldBe null
    }

    @Test
    fun closeStopsTheSession() =
        runBlocking<Unit> {
            file("data.bin", 64 * 1024, 9)
            val info = infoOf(torrentOf(File(seedDir, "data.bin")))
            val running = ok(seeder.add(addRequest(info, seedDir)))

            seeder.close()

            // what a transfer still polling, or a finalize still waiting, finds when the engine stops under it
            running.progress().error?.kind shouldBe TorrentErrorKind.LOST
            running.setPriorities(listOf(4))
            running.setSequential(true)
            running.remove()
            running.moveStorage(Path(File(root, "final").toString()), false).shouldBeInstanceOf<StepResult.Failed>()
            running.renameFiles(listOf("x.bin")).shouldBeInstanceOf<StepResult.Failed>()
            seeder.add(addRequest(info, seedDir)).shouldBeInstanceOf<StepResult.Failed>()
        }

    @Test
    fun anAddedTorrentIsNotManagedByLibtorrentsOwnQueue() =
        runBlocking<Unit> {
            // libtorrent's queue lets only 3 auto-managed torrents download at once, whatever the engine's own limit is
            file("data.bin", 64 * 1024, 12)
            val info = infoOf(torrentOf(File(seedDir, "data.bin")))
            ok(leecher.add(addRequest(info, File(leechDir, "staging"))))

            leecher.sessionTorrents().single().autoManaged shouldBe false
        }

    @Test
    fun aCancelledAddLeavesNothingInTheSession() =
        runBlocking<Unit> {
            file("data.bin", 64 * 1024, 13)
            val info = infoOf(torrentOf(File(seedDir, "data.bin")))
            // the session comes up before the timing starts
            ok(leecher.add(addRequest(info, File(leechDir, "first")))).remove()
            delay(200)

            for (after in 0..20L) {
                val adding = async { leecher.add(addRequest(info, File(leechDir, "again-$after"))) }
                delay(after)
                adding.cancelAndJoin()
                // an add that finished before the cancel arrived is the caller's to remove, as the protocol's transfer does
                if (!adding.isCancelled) ok(adding.await()).remove()
                delay(100)
                leecher.sessionTorrents() shouldBe emptyList()
            }
        }

    @Test
    fun aTorrentWithTwentyThousandFilesCanBeRenamed() =
        runBlocking<Unit> {
            // libtorrent says each rename in an alert, and its queue holds 2000 by default
            val dir = File(seedDir, "many").also { it.mkdirs() }
            repeat(20_000) { File(dir, "f$it.bin").writeBytes(byteArrayOf(it.toByte())) }
            val info = infoOf(torrentOf(dir))
            val running = ok(leecher.add(addRequest(info, File(leechDir, "staging"))))

            ok(running.renameFiles(info.files.map { "renamed/" + it.path.substringAfterLast('/') }))

            running.remove()
        }

    @Test
    fun theJvmsSignalHandlersSurviveATransfer() =
        runBlocking<Unit> {
            file("data.bin", 1024 * 1024, 11)
            val info = infoOf(torrentOf(File(seedDir, "data.bin")))
            seed(info)
            val running = ok(leecher.add(addRequest(info, File(leechDir, "staging"))))
            finish(running).finished shouldBe true
            ok(running.moveStorage(Path(File(root, "final").toString()), false))
            running.remove()

            // libtorrent puts its own SIGSEGV and SIGBUS handlers in place of the JVM's; the next null check the JIT compiled
            // then kills the process. The backend puts the JVM's back.
            delay(500)
            signals.intact() shouldBe true
        }
}

/** One libtorrent loader for the JVM, as in the app: the signal guard has to see the JVM's handlers before libtorrent loads. */
internal val nativesForTests =
    TorrentNatives(
        Path(File(System.getProperty("java.io.tmpdir"), "anydm-test-natives").toString()),
        signals = JnaSignalGuard(),
    )
