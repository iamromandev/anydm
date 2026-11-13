package dev.anydm.engine.torrent

import dev.anydm.engine.http.DiskIo
import dev.anydm.engine.http.EngineEvent
import dev.anydm.engine.http.RealDiskIo
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** R14: what a row claims is trusted only after its bytes on disk are hashed again. */
class TorrentResumeTest {
    private val rig = Rig()

    @AfterTest
    fun tearDown() = rig.close()

    /** a.bin 40,000 + b.bin 70,000 + c.bin 30,000 bytes in 16 KiB pieces: 9 pieces, 2 of them across a border, the last one short. */
    private val t = rig.torrent(listOf("a.bin" to 40_000, "b.bin" to 70_000, "c.bin" to 30_000))
    private val root: Path = rig.save.resolve("t")
    private val count = t.meta.pieceCount
    private val all = PieceSet.of(count, 0 until count)

    private fun partOf(name: String): Path = root.resolve("$name.part")

    /** Every file of the torrent as a `.part` with its right bytes, as a run that was stopped just before the end leaves them. */
    private fun lay(vararg only: String) {
        Files.createDirectories(root)
        for ((i, f) in t.files.withIndex()) if (only.isEmpty() || f.first in only) Files.write(partOf(f.first), t.contents[i])
    }

    private fun flip(
        file: Path,
        at: Int,
    ) {
        val b = Files.readAllBytes(file)
        b[at] = (b[at].toInt() xor 0x5A).toByte()
        Files.write(file, b)
    }

    private fun resume(
        engine: DefaultTorrentEngine,
        claimed: PieceSet = all,
        addrs: List<PeerAddr> = emptyList(),
    ) = engine.start(t.job(peers = addrs, folder = rig.save, pieces = claimed))

    private fun started() = rig.eventsOf().filterIsInstance<EngineEvent.Started>().last()

    private fun finished() = assertIs<EngineEvent.Finished>(rig.awaitTerminal())

    @Test
    fun aPieceCorruptedOnDiskIsDroppedAndFetchedAgainAndNoOtherPieceIs() {
        lay()
        flip(partOf("b.bin"), 5_000) // global 45,000: piece 2, which runs from a.bin into b.bin
        val swarm = rig.swarm(t, PeerScript())
        resume(rig.engine(), addrs = swarm.addrs)
        finished()
        assertEquals(PieceSet.of(count, (0 until count) - 2), started().pieces)
        assertEquals(t.meta.totalSize - 16L * KB, started().done, "done is the sum of the pieces that held")
        t.assertDownloaded(rig.save)
        assertTrue(swarm.requestsFor(2) > 0, "the bad piece was fetched")
        for (i in 0 until count) if (i != 2) assertEquals(0, swarm.requestsFor(i), "piece $i was fine and is not fetched")
    }

    @Test
    fun theShortLastPieceIsCheckedOverItsRealLength() {
        lay()
        flip(partOf("c.bin"), 29_999)
        val swarm = rig.swarm(t, PeerScript())
        resume(rig.engine(), addrs = swarm.addrs)
        finished()
        assertEquals(PieceSet.of(count, 0 until count - 1), started().pieces)
        t.assertDownloaded(rig.save)
    }

    @Test
    fun aFileThatIsGoneTakesItsPiecesWithIt() {
        lay()
        Files.delete(partOf("b.bin")) // global 40,000 until 110,000: pieces 2 to 6; 0 and 1 are in a.bin, 7 and 8 in c.bin
        val swarm = rig.swarm(t, PeerScript())
        resume(rig.engine(), addrs = swarm.addrs)
        finished()
        assertEquals(PieceSet.of(count, listOf(0, 1, 7, 8)), started().pieces)
        for (i in listOf(0, 1, 7, 8)) assertEquals(0, swarm.requestsFor(i), "piece $i is in a file that is there")
        t.assertDownloaded(rig.save)
    }

    @Test
    fun aFileThatIsZeroFilledTakesItsPiecesWithIt() {
        lay()
        Files.write(partOf("b.bin"), ByteArray(70_000))
        val swarm = rig.swarm(t, PeerScript())
        resume(rig.engine(), addrs = swarm.addrs)
        finished()
        assertEquals(PieceSet.of(count, listOf(0, 1, 7, 8)), started().pieces)
        t.assertDownloaded(rig.save)
    }

    @Test
    fun aFileThatWasCutShortTakesItsPiecesWithIt() {
        lay()
        Files.write(partOf("b.bin"), t.contents[1].copyOf(10_000))
        val swarm = rig.swarm(t, PeerScript())
        resume(rig.engine(), addrs = swarm.addrs)
        finished()
        val trusted = assertNotNull(started().pieces)
        for (i in listOf(0, 1, 7, 8)) assertTrue(trusted.has(i), "piece $i is whole")
        for (i in 3..6) assertTrue(!trusted.has(i), "piece $i is in the cut part")
        t.assertDownloaded(rig.save)
    }

    @Test
    fun everyFileGoneMeansNothingIsTrustedAndTheWholeTorrentIsFetched() {
        val swarm = rig.swarm(t, PeerScript())
        resume(rig.engine(), addrs = swarm.addrs)
        finished()
        assertEquals(PieceSet.empty(count), started().pieces)
        assertEquals(0L, started().done)
        t.assertDownloaded(rig.save)
    }

    @Test
    fun aFinishThatStoppedHalfWayIsResumedWithThePiecesReadFromWhereTheFilesAre() {
        lay("b.bin", "c.bin")
        Files.createDirectories(root)
        Files.write(root.resolve("a.bin"), t.contents[0]) // renamed already, the others not
        val counting = CountingTransport()
        resume(rig.engine(transport = counting))
        finished()
        assertEquals(all, started().pieces, "a.bin was read where it is")
        assertEquals(0, counting.tried, "nothing to fetch")
        t.assertDownloaded(rig.save)
    }

    @Test
    fun aFileRenamedByAFinishThatStoppedHalfWayAndDamagedSinceIsFetchedAgainInPlace() {
        lay("b.bin", "c.bin")
        Files.write(root.resolve("a.bin"), t.contents[0])
        flip(root.resolve("a.bin"), 100) // piece 0
        val swarm = rig.swarm(t, PeerScript())
        resume(rig.engine(), addrs = swarm.addrs)
        finished()
        assertEquals(PieceSet.of(count, (0 until count) - 0), started().pieces)
        t.assertDownloaded(rig.save)
        assertTrue(swarm.requestsFor(0) > 0)
    }

    @Test
    fun theStartedEventComesWithTheCheckedPiecesBeforeAnyPeerIsConnectedTo() {
        lay()
        flip(partOf("b.bin"), 5_000)
        val swarm = rig.swarm(t, PeerScript())
        val startedFirst = CopyOnWriteArrayList<Boolean>()
        val watching =
            object : PeerTransport {
                val inner = TcpPeerTransport()

                override suspend fun connect(
                    addr: PeerAddr,
                    timeoutMs: Int,
                ): PeerConn {
                    startedFirst.add(rig.eventsOf().any { it is EngineEvent.Started })
                    return inner.connect(addr, timeoutMs)
                }
            }
        resume(rig.engine(transport = watching), addrs = swarm.addrs)
        finished()
        assertTrue(startedFirst.isNotEmpty() && startedFirst.all { it }, "Started came first: $startedFirst")
        assertEquals(PieceSet.of(count, (0 until count) - 2), started().pieces)
    }

    @Test
    fun aFreshStartAndAnEmptyClaimReadNothingFromDisk() {
        val reads = AtomicInteger()
        val disk =
            object : DiskIo by RealDiskIo() {
                override fun readAt(
                    p: Path,
                    pos: Long,
                    buf: ByteArray,
                    off: Int,
                    len: Int,
                ): Int {
                    reads.incrementAndGet()
                    return RealDiskIo().readAt(p, pos, buf, off, len)
                }
            }
        val swarm = rig.swarm(t, PeerScript())
        val engine = rig.engine(disk = disk)
        engine.start(t.job(id = "fresh", peers = swarm.addrs, folder = rig.save))
        assertIs<EngineEvent.Finished>(rig.awaitTerminal("fresh"))
        assertEquals(0, reads.get(), "a fresh start has nothing to check")
    }

    // R12 / R14: the check is reported while it runs
    private class GatedReads(
        private val inner: DiskIo,
        private val allow: Int,
    ) : DiskIo by inner {
        val gate = CountDownLatch(1)
        private val calls = AtomicInteger()

        override fun readAt(
            p: Path,
            pos: Long,
            buf: ByteArray,
            off: Int,
            len: Int,
        ): Int {
            if (calls.incrementAndGet() > allow) gate.await(30, TimeUnit.SECONDS)
            return inner.readAt(p, pos, buf, off, len)
        }
    }

    /** 20 pieces in one file, laid out as a `.part`: every read of a piece is one read of the disk. */
    private val single = rig.torrent(listOf("f.bin" to 20 * 16 * KB), single = true, name = "f.bin", seed = 3)
    private val singleAll = PieceSet.of(single.meta.pieceCount, 0 until single.meta.pieceCount)

    private fun laySingle() = Files.write(rig.save.resolve("f.bin.part"), single.data)

    @Test
    fun theCheckReportsHowMuchIsVerifiedWhileItRunsAndTheStartedEventComesAfterIt() {
        laySingle()
        val disk = GatedReads(RealDiskIo(), allow = 4)
        rig.engine(disk = disk).start(single.job(folder = rig.save, pieces = singleAll))
        val progress = { rig.eventsOf().filterIsInstance<EngineEvent.Progress>() }
        // the clock is the fake one: a report is due when it has moved by the report interval
        rig.await("a report with the pieces checked so far") {
            rig.clock.advance(60)
            Thread.sleep(20)
            progress().any { it.done == 4L * 16 * KB }
        }
        val during = progress().last()
        assertEquals(single.meta.totalSize, during.total)
        assertEquals(0, during.peers)
        assertNull(during.pieces, "the row keeps the pieces it had until the check is over")
        assertTrue(rig.eventsOf().none { it is EngineEvent.Started }, "the check is not over")
        disk.gate.countDown()
        finished()
        assertEquals(singleAll, started().pieces)
        val doneDuring = progress().map { it.done }
        assertEquals(doneDuring.sorted(), doneDuring, "it only grows")
    }

    @Test
    fun aPauseDuringTheCheckKeepsTheClaimsTheRowHad() {
        laySingle()
        val disk = GatedReads(RealDiskIo(), allow = 2)
        val engine = rig.engine(disk = disk)
        engine.start(single.job(folder = rig.save, pieces = singleAll))
        Thread.sleep(150) // the check is under way and stuck
        engine.stop("1")
        disk.gate.countDown()
        runBlocking { engine.join("1") }
        val stopped = rig.awaitStopped()
        assertEquals(singleAll, stopped.pieces, "nothing was found wrong: the claims are the row's own")
        assertEquals(single.meta.totalSize, stopped.done)
        assertTrue(rig.eventsOf().none { it is EngineEvent.Started })
    }

    // NFR4: what the check holds at once is bounded
    @Test
    fun theCheckHoldsNoMoreThanTheBufferCapOfPieceDataAtOnce() {
        val big = rig.torrent(listOf("f.bin" to 40 * 16 * KB), single = true, name = "f.bin", seed = 5)
        Files.write(rig.save.resolve("f.bin.part"), big.data)
        val now = AtomicInteger()
        val peak = AtomicInteger()
        val disk =
            object : DiskIo by RealDiskIo() {
                override fun readAt(
                    p: Path,
                    pos: Long,
                    buf: ByteArray,
                    off: Int,
                    len: Int,
                ): Int {
                    peak.accumulateAndGet(now.incrementAndGet()) { a, b -> maxOf(a, b) }
                    try {
                        Thread.sleep(15)
                        return RealDiskIo().readAt(p, pos, buf, off, len)
                    } finally {
                        now.decrementAndGet()
                    }
                }
            }
        // a cap of four pieces: no more than four may be read and not yet hashed
        val engine = rig.engine(disk = disk, limits = TorrentLimits(reportMs = 50, maxBuffered = 4L * 16 * KB))
        engine.start(big.job(folder = rig.save, pieces = PieceSet.of(big.meta.pieceCount, 0 until big.meta.pieceCount)))
        assertIs<EngineEvent.Finished>(rig.awaitTerminal())
        assertTrue(peak.get() <= 4, "at most 4 pieces at once, saw ${peak.get()}")
        big.assertDownloaded(rig.save)
    }

    @Test
    fun theFilesAreRightAfterAStopInTheMiddleOfADownloadAndAResumeWithOneBadPiece() {
        // the whole path: a run stopped part way, a piece damaged while it was stopped, a resume that ends right
        val big = rig.torrent(listOf("f.bin" to 3_000_000), pieceLength = 32 * KB, single = true, name = "f.bin", seed = 9)
        val swarm = rig.swarm(big, PeerScript(bytesPerSec = 400_000), PeerScript(bytesPerSec = 400_000))
        val engine = rig.engine()
        engine.start(big.job(peers = swarm.addrs, folder = rig.save))
        rig.await("some blocks") { swarm.peers.sumOf { it.answers } >= 60 }
        engine.stop("1")
        runBlocking { engine.join("1") }
        val stopped = rig.awaitStopped()
        val pieces = assertNotNull(stopped.pieces)
        val victim = (0 until pieces.size).first { pieces.has(it) }
        val part = rig.save.resolve("f.bin.part")
        flip(part, victim * 32 * KB + 7)
        rig.await("the peers to let go") { swarm.peers.all { it.open == 0 } }
        engine.start(big.job(peers = swarm.addrs, folder = rig.save, pieces = pieces))
        assertIs<EngineEvent.Finished>(rig.awaitTerminal())
        val checked =
            rig
                .eventsOf()
                .filterIsInstance<EngineEvent.Started>()
                .last()
                .pieces
        assertEquals(pieces.count - 1, assertNotNull(checked).count)
        big.assertDownloaded(rig.save)
        assertContentEquals(big.data, Files.readAllBytes(rig.save.resolve("f.bin")))
    }
}
