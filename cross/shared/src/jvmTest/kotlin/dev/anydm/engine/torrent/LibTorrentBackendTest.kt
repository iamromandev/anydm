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
import java.io.RandomAccessFile
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
        resume: ByteArray? = null,
    ) = AddTorrent(info, Path(to.toString()), priorities, sequential, names, resume)

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

    /** The first byte of the finished file changed, its size and time kept: a resume that trusts its blob doesn't notice. */
    private fun damageQuietly(target: File) {
        val time = target.lastModified()
        RandomAccessFile(target, "rw").use {
            it.seek(0)
            val first = it.read()
            it.seek(0)
            it.write(first.inv() and 0xff)
        }
        target.setLastModified(time)
    }

    @Test
    fun resumeDataRoundTripsAndSkipsTheRecheck() =
        runBlocking<Unit> {
            val data = file("data.bin", 4 * 1024 * 1024, 21)
            val info = infoOf(torrentOf(File(seedDir, "data.bin")))
            seed(info)
            val staging = File(leechDir, "staging")
            val first = ok(leecher.add(addRequest(info, staging)))
            finish(first).finished shouldBe true
            val blob = first.saveResume()!!
            first.remove()
            delay(300)
            val target = File(staging, "data.bin")
            damageQuietly(target)

            val again = ok(leecher.add(addRequest(info, staging, resume = blob)))
            finish(again).finished shouldBe true

            // finished at once and the damaged byte is still there: the pieces were taken from the blob, not hashed again
            target.readBytes()[0] shouldBe data[0].toInt().inv().toByte()
            again.remove()
        }

    @Test
    fun withoutTheBlobADamagedFileIsFoundAndFetchedAgain() =
        runBlocking<Unit> {
            val data = file("data.bin", 1024 * 1024, 22)
            val info = infoOf(torrentOf(File(seedDir, "data.bin")))
            seed(info)
            val staging = File(leechDir, "staging")
            val first = ok(leecher.add(addRequest(info, staging)))
            finish(first).finished shouldBe true
            first.remove()
            delay(300)
            val target = File(staging, "data.bin")
            damageQuietly(target)

            val again = ok(leecher.add(addRequest(info, staging)))
            finish(again).finished shouldBe true

            target.readBytes().toList() shouldBe data.toList()
            again.remove()
        }

    @Test
    fun anUnreadableResumeBlobFallsBackToARecheck() =
        runBlocking<Unit> {
            val data = file("data.bin", 512 * 1024, 23)
            val info = infoOf(torrentOf(File(seedDir, "data.bin")))
            seed(info)

            val running = ok(leecher.add(addRequest(info, File(leechDir, "staging"), resume = "this is not bencode".toByteArray())))
            finish(running).finished shouldBe true

            File(leechDir, "staging/data.bin").readBytes().toList() shouldBe data.toList()
            running.remove()
        }

    @Test
    fun aResumeBlobForAnotherTorrentIsIgnored() =
        runBlocking<Unit> {
            val one = file("one.bin", 256 * 1024, 24)
            val two = file("two.bin", 256 * 1024, 25)
            val infoOne = infoOf(torrentOf(File(seedDir, "one.bin")))
            val infoTwo = infoOf(torrentOf(File(seedDir, "two.bin")))
            seed(infoOne)
            seed(infoTwo)
            val first = ok(leecher.add(addRequest(infoOne, File(leechDir, "one"))))
            finish(first).finished shouldBe true
            val blobOfOne = first.saveResume()!!
            first.remove()

            val second = ok(leecher.add(addRequest(infoTwo, File(leechDir, "two"), resume = blobOfOne)))
            finish(second).finished shouldBe true

            File(leechDir, "two/two.bin").readBytes().toList() shouldBe two.toList()
            File(leechDir, "one/one.bin").readBytes().toList() shouldBe one.toList()
            second.remove()
        }

    @Test
    fun statsCountUploadAndSeedingTime() =
        runBlocking<Unit> {
            val size = 1024 * 1024
            file("data.bin", size, 26)
            val info = infoOf(torrentOf(File(seedDir, "data.bin")))
            val seeding = seed(info)
            val running = ok(leecher.add(addRequest(info, File(leechDir, "staging"))))
            finish(running).finished shouldBe true

            val down = running.stats()
            down.payloadDown shouldBe size.toLong()
            down.wanted shouldBe size.toLong()
            // the seeder's counters follow a moment behind the leecher's
            withTimeout(10.seconds) { while (seeding.stats().payloadUp < size) delay(100) }
            val up = seeding.stats()
            up.seeding shouldBe true
            (up.allTimeUp >= size) shouldBe true
            (up.seedingSeconds >= 0) shouldBe true
            running.remove()
        }

    @Test
    fun seedingTimeCountsForATorrentWithSkippedFiles() =
        runBlocking<Unit> {
            file("pack/a.bin", 256 * 1024, 31)
            file("pack/b.bin", 256 * 1024, 32)
            val info = infoOf(torrentOf(File(seedDir, "pack")))
            seed(info)
            val skipped = info.files.single { it.path.endsWith("b.bin") }.index
            val running = ok(leecher.add(addRequest(info, File(leechDir, "staging"), info.files.map { if (it.index == skipped) 0 else 4 })))
            finish(running).finished shouldBe true

            delay(2200)

            // libtorrent's seeding time never runs for a torrent that lacks a file; the time since it finished does
            (running.stats().seedingSeconds >= 1) shouldBe true
            running.remove()
        }

    @Test
    fun uploadedBytesSurviveAResumeSavedWithPeersStillConnected() =
        runBlocking<Unit> {
            val size = 1024 * 1024
            file("data.bin", size, 33)
            val info = infoOf(torrentOf(File(seedDir, "data.bin")))
            val seeding = seed(info)
            val running = ok(leecher.add(addRequest(info, File(leechDir, "staging"))))
            finish(running).finished shouldBe true
            withTimeout(10.seconds) { while (seeding.stats().payloadUp < size) delay(50) }
            delay(1500)

            val blob = seeding.saveResume()!!
            seeding.remove()
            val again = ok(seeder.add(addRequest(info, seedDir, resume = blob)))

            (again.stats().allTimeUp >= size * 9L / 10) shouldBe true
            again.remove()
            running.remove()
        }

    @Test
    fun aDownloadLimitHolds() =
        runBlocking<Unit> {
            file("data.bin", 3 * 1024 * 1024, 27)
            val info = infoOf(torrentOf(File(seedDir, "data.bin")))
            seed(info)
            val running = ok(leecher.add(addRequest(info, File(leechDir, "staging"))))
            running.setDownloadLimit(512L * 1024)
            val started = System.nanoTime()

            finish(running).finished shouldBe true

            // 3 MiB at 512 KiB/s is 6 s; some of it came before the limit took hold
            ((System.nanoTime() - started) / 1_000_000_000.0 >= 3.0) shouldBe true
            running.remove()
        }

    @Test
    fun removingTheLimitLetsTheTorrentGoFast() =
        runBlocking<Unit> {
            file("data.bin", 3 * 1024 * 1024, 30)
            val info = infoOf(torrentOf(File(seedDir, "data.bin")))
            seed(info)
            val running = ok(leecher.add(addRequest(info, File(leechDir, "staging"))))
            running.setDownloadLimit(128L * 1024)
            delay(500)
            running.setDownloadLimit(null)

            // 3 MiB at 128 KiB/s would be 24 s
            withTimeout(8.seconds) { while (!running.progress().finished) delay(50) }
            running.remove()
        }

    @Test
    fun theSessionLimitsReachTheSessionBeforeAndAfterItStarts() =
        runBlocking<Unit> {
            // libtorrent leaves peers on the local network unthrottled, so the limit is read back from the session instead
            seeder.setSessionLimits(null, 512L * 1024)
            file("data.bin", 256 * 1024, 28)
            val info = infoOf(torrentOf(File(seedDir, "data.bin")))
            seed(info)
            seeder.appliedSessionLimits() shouldBe (0 to 512 * 1024)

            seeder.setSessionLimits(256L * 1024, null)

            seeder.appliedSessionLimits() shouldBe (256 * 1024 to 0)
        }

    @Test
    fun closeSavesResumeDataForRunningTorrents() =
        runBlocking<Unit> {
            file("data.bin", 256 * 1024, 29)
            val info = infoOf(torrentOf(File(seedDir, "data.bin")))
            val saved = mutableMapOf<String, ByteArray>()
            leecher.resumeSink = { hash, bytes -> saved[hash] = bytes }
            // no seeder has it: the torrent stays in the session, unfinished
            ok(leecher.add(addRequest(info, File(leechDir, "staging"))))

            leecher.close()

            saved[info.infoHash]!!.isNotEmpty() shouldBe true
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
