package dev.anydm.engine.torrent

import dev.anydm.engine.http.Clock
import dev.anydm.engine.http.DiskIo
import dev.anydm.engine.http.EngineEvent
import dev.anydm.engine.http.PartFile
import dev.anydm.engine.http.RateShares
import dev.anydm.engine.http.RealDiskIo
import dev.anydm.engine.http.Reasons
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The torrent engine as a whole: several torrents, the app-wide peer cap, the shared rates, restarts. */
class TorrentEngineTest {
    private val rig = Rig()
    private val real = Clock { System.currentTimeMillis() }

    @AfterTest
    fun tearDown() = rig.close()

    private fun deadAddr(): PeerAddr {
        val s = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val port = s.localPort
        s.close()
        return PeerAddr("127.0.0.1", port)
    }

    private fun small(
        i: Int,
        size: Int = 100_000,
    ) = rig.torrent(listOf("f$i.bin" to size), single = true, name = "f$i.bin", seed = i + 10)

    // AC5
    @Test
    fun fiveTorrentsOnSixtyPeersHoldAtMostTwoHundredConnectionsTogether() {
        val torrents = (0 until 5).map { small(it) }
        val swarm = rig.swarm(torrents, *Array(60) { PeerScript(holdUnchoke = true) })
        val counting = CountingTransport()
        val engine = rig.engine(transport = counting)
        torrents.forEachIndexed { i, t -> engine.start(t.job(id = "$i", peers = swarm.addrs, folder = rig.save)) }
        rig.await("two hundred connections", id = "0") { counting.open == 200 }
        Thread.sleep(400)
        assertEquals(200, counting.peak, "never more than two hundred")
        assertEquals(200, counting.tried)
        assertEquals(200, swarm.peers.sumOf { it.accepted })
    }

    @Test
    fun permitsFromConnectsThatFailAreGivenBackAndUsedForTheNextPeers() {
        val t = small(0)
        val live = rig.swarm(t, *Array(3) { PeerScript(holdUnchoke = true) })
        val counting = CountingTransport()
        val engine = rig.engine(transport = counting, limits = TorrentLimits(total = 3, reportMs = 50))
        engine.start(t.job(peers = List(6) { deadAddr() } + live.addrs, folder = rig.save))
        rig.await("the three live peers") { counting.open == 3 }
        assertEquals(9, counting.tried, "the six that failed freed their permits")
        assertEquals(3, counting.peak)
    }

    @Test
    fun aStoppedTorrentGivesItsPermitsToTheNextOne() {
        val a = small(0)
        val b = small(1)
        val swarm = rig.swarm(listOf(a, b), *Array(3) { PeerScript(holdUnchoke = true) })
        val counting = CountingTransport()
        val engine = rig.engine(transport = counting, limits = TorrentLimits(total = 3, reportMs = 50))
        engine.start(a.job(id = "a", peers = swarm.addrs, folder = rig.save))
        rig.await("a's connections") { counting.open == 3 }
        engine.start(b.job(id = "b", peers = swarm.addrs, folder = rig.save))
        Thread.sleep(200)
        assertEquals(3, counting.tried, "b waits for a permit")
        engine.stop("a")
        runBlocking { engine.join("a") }
        rig.await("b's connections", id = "b") { counting.tried == 6 && counting.open == 3 }
        assertEquals(3, counting.peak)
    }

    @Test
    fun aFinishedTorrentGivesItsPermitsToTheNextOne() {
        val a = small(0)
        val b = small(1)
        val seeds = rig.swarm(a, PeerScript())
        val holders = rig.swarm(b, *Array(3) { PeerScript(holdUnchoke = true) })
        val counting = CountingTransport()
        val engine = rig.engine(transport = counting, limits = TorrentLimits(total = 3, reportMs = 50))
        // fills all three permits with `a`'s one seed and two dead ones, then ends
        engine.start(a.job(id = "a", peers = seeds.addrs + List(2) { deadAddr() }, folder = rig.save))
        assertIs<EngineEvent.Finished>(rig.awaitTerminal("a"))
        engine.start(b.job(id = "b", peers = holders.addrs, folder = rig.save))
        rig.await("b's three connections", id = "b") { counting.open == 3 }
    }

    @Test
    fun theRunIsRegisteredInTheSharedRatesWhileItRunsAndLeavesAtTheEnd() {
        val shares = RateShares()
        val t = small(0)
        val swarm = rig.swarm(t, PeerScript(holdUnchoke = true))
        val engine = rig.engine(shares = shares)
        engine.start(t.job(peers = swarm.addrs, folder = rig.save))
        rig.await("registered") { runCatching { shares.bucket("1") }.isSuccess }
        shares.setRate(100_000)
        assertEquals(10_000, shares.bucket("1").maxChunk())
        engine.stop("1")
        rig.awaitStopped()
        rig.await("unregistered") { runCatching { shares.bucket("1") }.isFailure }
        assertFailsWith<IllegalStateException> { shares.bucket("1") }
    }

    @Test
    fun aStartThatFollowsAStopWaitsForTheStoppedRunToEnd() {
        val t = rig.torrent(listOf("f.bin" to 400_000), pieceLength = 32 * KB, single = true, name = "f.bin")
        val swarm = rig.swarm(t, PeerScript(bytesPerSec = 300_000))
        val engine = rig.engine()
        engine.start(t.job(peers = swarm.addrs, folder = rig.save))
        rig.await("some blocks") { swarm.peers.single().answers >= 10 }
        engine.stop("1")
        engine.start(t.job(peers = swarm.addrs, folder = rig.save, pieces = PieceSet.empty(t.meta.pieceCount)))
        rig.await("the second start") { rig.eventsOf().count { it is EngineEvent.Started } == 2 }
        val kinds = rig.eventsOf().map { it::class.simpleName }
        assertTrue(kinds.indexOf("Stopped") in 0 until kinds.lastIndexOf("Started"), "the stop came first: $kinds")
        assertIs<EngineEvent.Finished>(rig.awaitTerminal())
        t.assertDownloaded(rig.save)
    }

    @Test
    fun twoJobsThatWouldWriteTheSameFilesNeverShareThem() {
        val t = rig.torrent(listOf("f.bin" to 100_000), single = true, name = "f.bin")
        val swarm = rig.swarm(t, PeerScript(holdUnchoke = true))
        val engine = rig.engine()
        engine.start(t.job(id = "a", peers = swarm.addrs, folder = rig.save))
        rig.await("a to start", id = "a") { rig.eventsOf("a").any { it is EngineEvent.Started } }
        engine.start(t.job(id = "b", peers = emptyList(), folder = rig.save))
        val started = rig.await("b to start", id = "b") { rig.eventsOf("b").any { it is EngineEvent.Started } }
        assertEquals("f (1).bin", (rig.eventsOf("b").first { it is EngineEvent.Started } as EngineEvent.Started).name, "$started")
        // a resume of a's own name while a is running is refused
        engine.start(t.job(id = "c", peers = emptyList(), folder = rig.save, pieces = PieceSet.empty(t.meta.pieceCount)))
        val failed = assertIs<EngineEvent.Failed>(rig.awaitTerminal("c"))
        assertEquals(Reasons.NOT_WRITABLE, failed.reason)
    }

    // R16: a torrent that has peers but no permit for them is not a torrent without peers
    @Test
    fun aTorrentWaitingForPermitsAnotherOneHoldsDoesNotFailWithNoPeers() {
        val a = small(0)
        val b = small(1)
        val swarm = rig.swarm(listOf(a, b), *Array(4) { PeerScript(holdUnchoke = true) })
        val counting = CountingTransport()
        val engine = rig.engine(transport = counting, limits = TorrentLimits(total = 2, reportMs = 50))
        engine.start(a.job(id = "a", peers = swarm.addrs.take(2), folder = rig.save))
        rig.await("a to hold both permits", id = "a") { counting.open == 2 }
        engine.start(b.job(id = "b", peers = swarm.addrs.drop(2), folder = rig.save))
        rig.await("b to start", id = "b") { rig.eventsOf("b").any { it is EngineEvent.Started } }
        rig.clock.advance(130_000) // over the two minutes, with peers to connect to and no permit to do it with
        Thread.sleep(300)
        assertTrue(rig.eventsOf("b").none { it is EngineEvent.Failed }, "b has peers: ${rig.eventsOf("b").takeLast(2)}")
        assertEquals(2, counting.tried, "b is still waiting")
        engine.stop("a")
        runBlocking { engine.join("a") }
        rig.await("b's connections", id = "b") { counting.tried == 4 && counting.open == 2 }
    }

    // NFR4 / R6: however a run ends, its permits come back once
    @Test
    fun permitsAreGivenBackExactlyOnceWhateverEndsARun() {
        val quiet = ServerSocket(0, 50, InetAddress.getLoopbackAddress()) // takes connections, says nothing
        try {
            val a = small(0)
            val b = small(1)
            val seeds = rig.swarm(a, PeerScript())
            val holders = rig.swarm(b, *Array(6) { PeerScript(holdUnchoke = true) })
            val resume = PieceSet.empty(b.meta.pieceCount)
            val engine = rig.engine(limits = TorrentLimits(total = 3, reportMs = 50))
            val busy = PeerAddr("127.0.0.1", quiet.localPort)
            // a stop while connected, a stop while still connecting, an end by finishing
            repeat(3) { round ->
                val id = "r$round"
                engine.start(b.job(id = id, peers = holders.addrs.take(2) + busy + deadAddr(), folder = rig.save, pieces = resume))
                rig.await("the first connections", id = id) { holders.peers.take(2).any { it.open > 0 } }
                engine.stop(id)
                runBlocking { engine.join(id) }
                engine.forget(id)
            }
            engine.start(a.job(id = "fin", peers = seeds.addrs + deadAddr() + deadAddr(), folder = rig.save))
            assertIs<EngineEvent.Finished>(rig.awaitTerminal("fin"))
            runBlocking { engine.join("fin") }
            rig.await("the churn to be over") { holders.peers.all { it.open == 0 } }
            val before = holders.peers.sumOf { it.accepted }
            // all permits are back, no more than three: six live peers are offered and exactly three are connected
            engine.start(b.job(id = "last", peers = holders.addrs, folder = rig.save, pieces = resume))
            rig.await("three connections", id = "last") { holders.peers.sumOf { it.accepted } - before == 3 }
            Thread.sleep(400)
            assertEquals(3, holders.peers.sumOf { it.accepted } - before, "never more than the three permits")
            assertEquals(3, holders.peers.sumOf { it.open })
        } finally {
            quiet.close()
        }
    }

    // R7 / the engine: Failed is the end of the job, so a resume from it must run
    @Test
    fun aResumeAskedForRightWhenFailedIsSeenIsNotSwallowedByTheRunThatIsStillWindingDown() {
        val t = small(0)
        val engine = rig.engine(limits = TorrentLimits(reportMs = 50, noPeersMs = 1_000))
        val again = t.job(peers = listOf(deadAddr()), folder = rig.save, pieces = PieceSet.empty(t.meta.pieceCount))
        val resumed = AtomicBoolean(false)
        // Unconfined: the collector runs inside the emit of Failed, while the failed job is certainly still there
        rig.scope.launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            engine.events.collect { if (it is EngineEvent.Failed && resumed.compareAndSet(false, true)) engine.start(again) }
        }
        engine.start(t.job(peers = listOf(deadAddr()), folder = rig.save))
        rig.await("Started") { rig.eventsOf().any { it is EngineEvent.Started } }
        rig.clock.advance(2_000)
        rig.await("the run after the failure to start", timeoutMs = 5_000) { rig.eventsOf().count { it is EngineEvent.Started } == 2 }
        assertTrue(resumed.get())
    }

    /** A disk whose files take their time to close. */
    private class SlowCloseDisk(
        private val inner: DiskIo,
    ) : DiskIo by inner {
        val closed = AtomicInteger()

        override fun openSpans(
            p: Path,
            size: Long?,
        ): PartFile {
            val real = inner.openSpans(p, size)
            return object : PartFile by real {
                override fun close() {
                    Thread.sleep(200)
                    real.close()
                    closed.incrementAndGet()
                }
            }
        }
    }

    @Test
    fun theFilesOfAFailedRowAreClosedBeforeFailedIsEmitted() {
        val t = small(0)
        val disk = SlowCloseDisk(RealDiskIo())
        val engine = rig.engine(disk = disk, limits = TorrentLimits(reportMs = 50, noPeersMs = 1_000))
        val closedAtFailed = AtomicInteger(-1)
        rig.scope.launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            engine.events.collect { if (it is EngineEvent.Failed) closedAtFailed.set(disk.closed.get()) }
        }
        engine.start(t.job(peers = listOf(deadAddr()), folder = rig.save))
        rig.await("Started") { rig.eventsOf().any { it is EngineEvent.Started } }
        rig.clock.advance(2_000)
        assertIs<EngineEvent.Failed>(rig.awaitTerminal())
        assertEquals(1, closedAtFailed.get(), "the one file was closed when the row was told")
    }

    private fun anUnexpectedThrowableFailsTheRowAndIsLogged(boom: Throwable) {
        val log = Logger.getLogger(DefaultTorrentEngine::class.java.name)
        val records = CopyOnWriteArrayList<LogRecord>()
        val handler =
            object : Handler() {
                override fun publish(record: LogRecord) {
                    records.add(record)
                }

                override fun flush() = Unit

                override fun close() = Unit
            }
        log.addHandler(handler)
        try {
            val t = small(0)
            val disk =
                object : DiskIo by RealDiskIo() {
                    override fun openSpans(
                        p: Path,
                        size: Long?,
                    ): PartFile = throw boom
                }
            rig.engine(disk = disk).start(t.job(peers = emptyList(), folder = rig.save))
            val failed = assertIs<EngineEvent.Failed>(rig.awaitTerminal(timeoutMs = 5_000))
            assertEquals(Reasons.NETWORK, failed.reason)
            // the coroutines may pass on a copy of the exception that has the original as its cause
            val logged = records.mapNotNull { it.thrown }.any { e -> generateSequence(e) { it.cause }.any { it === boom } }
            assertTrue(logged, "the cause was logged: ${records.map { it.message }}")
        } finally {
            log.removeHandler(handler)
        }
    }

    @Test
    fun anUnexpectedExceptionFailsTheRowAndIsLoggedNotSwallowed() =
        anUnexpectedThrowableFailsTheRowAndIsLogged(IllegalStateException("boom"))

    @Test
    fun anErrorFailsTheRowAndIsLoggedToo() = anUnexpectedThrowableFailsTheRowAndIsLogged(AssertionError("boom"))

    @Test
    fun aClaimedPieceSetOfTheWrongSizeIsLoggedAndTheDownloadStartsFromNothing() {
        val log = Logger.getLogger(DefaultTorrentEngine::class.java.name)
        val records = CopyOnWriteArrayList<LogRecord>()
        val handler =
            object : Handler() {
                override fun publish(record: LogRecord) {
                    records.add(record)
                }

                override fun flush() = Unit

                override fun close() = Unit
            }
        log.addHandler(handler)
        try {
            val t = small(0)
            val swarm = rig.swarm(t, PeerScript())
            val wrong = PieceSet.empty(t.meta.pieceCount + 3)
            rig.engine().start(t.job(peers = swarm.addrs, folder = rig.save, pieces = wrong))
            assertIs<EngineEvent.Finished>(rig.awaitTerminal())
            t.assertDownloaded(rig.save)
            val said = records.filter { it.level == java.util.logging.Level.WARNING }.map { it.message }
            assertTrue(said.any { "pieces" in it && "${wrong.size}" in it }, "the claim was named in the log: $said")
        } finally {
            log.removeHandler(handler)
        }
    }

    // A fresh row has never run: until Started says the names its files got, no end of the job may claim pieces, or the row would
    // take its own name for settled and later delete (or write into) a file of the user's with that name.
    @Test
    fun aFreshRowThatFailsToOpenItsFilesEndsWithNoClaimedPieces() {
        val t = rig.torrent(listOf("f.bin" to 100_000), single = true, name = "f.bin")
        rig.engine(disk = LowSpace(RealDiskIo(), 99_999)).start(t.job(folder = rig.save))
        val failed = assertIs<EngineEvent.Failed>(rig.awaitTerminal())
        assertEquals(Reasons.DISK_FULL, failed.reason)
        assertNull(failed.pieces, "a row that never ran claims no pieces")
    }

    @Test
    fun aFreshRowPausedWhileItsFilesAreBeingOpenedEndsWithNoClaimedPieces() {
        val t = rig.torrent(listOf("f.bin" to 100_000), single = true, name = "f.bin")
        val inOpen = java.util.concurrent.CountDownLatch(1)
        val go = java.util.concurrent.CountDownLatch(1)
        val disk =
            object : DiskIo by RealDiskIo() {
                override fun freeSpace(dir: Path): Long {
                    inOpen.countDown()
                    go.await()
                    return RealDiskIo().freeSpace(dir)
                }
            }
        val engine = rig.engine(disk = disk)
        engine.start(t.job(folder = rig.save))
        assertTrue(inOpen.await(10, java.util.concurrent.TimeUnit.SECONDS), "the open began")
        engine.stop("1")
        go.countDown()
        val stopped = rig.awaitStopped()
        assertNull(stopped.pieces, "a row that never ran claims no pieces")
    }

    @Test
    fun aRowThatClaimsPiecesKeepsThemWhenItFailsToOpenItsFiles() {
        val t = rig.torrent(listOf("f.bin" to 100_000), single = true, name = "f.bin")
        val claim = PieceSet.of(t.meta.pieceCount, listOf(0))
        rig.engine(disk = LowSpace(RealDiskIo(), 0)).start(t.job(folder = rig.save, pieces = claim))
        val failed = assertIs<EngineEvent.Failed>(rig.awaitTerminal())
        assertEquals(claim, failed.pieces, "a resume's own claim is handed back")
    }

    private class LowSpace(
        private val inner: DiskIo,
        private val free: Long,
    ) : DiskIo by inner {
        override fun freeSpace(dir: Path) = free
    }

    // AC17
    @Test
    fun twentySeededRunsKilledAtRandomPointsAndResumedAlwaysEndWithMatchingFiles() {
        var killedSeeds = 0
        var kills = 0
        for (seed in 0 until 20) {
            val rnd = Random(seed)
            val pieceLength = 16 * KB shl rnd.nextInt(3)
            val sizes = List(rnd.nextInt(1, 5)) { if (it == 1 && rnd.nextInt(3) == 0) 0 else rnd.nextInt(30_000, 300_000) }
            val files = sizes.mapIndexed { i, n -> "d/f$i.bin" to n }
            val single = files.size == 1 && rnd.nextBoolean()
            val t =
                rig.torrent(
                    if (single) listOf("s$seed.bin" to files.single().second) else files,
                    pieceLength,
                    single,
                    name = if (single) "s$seed.bin" else "run$seed",
                    seed = 100 + seed,
                )
            val count = t.meta.pieceCount
            val blocks = ((t.meta.totalSize + BLOCK - 1) / BLOCK).toInt()
            // two pieces at most: nobody reaches the three failed pieces that get a peer banned (that rule has its own test)
            val bad = Random(seed).let { r -> List(2) { r.nextInt(count) }.toSet() }
            val swarm =
                rig.swarm(
                    t,
                    PeerScript(bytesPerSec = rnd.nextLong(400_000, 1_200_000), corrupt = bad, corruptTimes = 1),
                    PeerScript(bytesPerSec = rnd.nextLong(400_000, 1_200_000), dropAfterAnswers = rnd.nextInt(8, 40)),
                    PeerScript(bytesPerSec = rnd.nextLong(400_000, 1_200_000)),
                )
            val engine = rig.engine(clock = real)
            val id = "k$seed"
            var pieces: PieceSet? = null
            val maxKills = rnd.nextInt(1, 4)
            var killsOfSeed = 0
            while (true) {
                engine.start(t.job(id = id, peers = swarm.addrs, folder = rig.save, pieces = pieces))
                if (killsOfSeed == maxKills) break
                // a third of the torrent at most between two kills, so the kill lands while the run is still going
                val kill = swarm.peers.sumOf { it.answers } + rnd.nextInt(1, maxOf(2, blocks / 3))
                rig.await("a kill point or the end of run $seed", id = id) {
                    swarm.peers.sumOf { it.answers } >= kill || rig.eventsOf(id).any { it is EngineEvent.Finished }
                }
                if (rig.eventsOf(id).any { it is EngineEvent.Finished }) break
                val stale =
                    rig
                        .eventsOf(id)
                        .filterIsInstance<EngineEvent.Progress>()
                        .lastOrNull()
                        ?.pieces
                val stoppedBefore = rig.eventsOf(id).count { it is EngineEvent.Stopped }
                engine.stop(id)
                runBlocking { engine.join(id) }
                // the run may have finished just before the stop: then there is no Stopped, and nothing to resume
                rig.await("the stop or the end of run $seed", id = id) {
                    rig.eventsOf(id).count { it is EngineEvent.Stopped } > stoppedBefore ||
                        rig.eventsOf(id).any { it is EngineEvent.Finished }
                }
                if (rig.eventsOf(id).any { it is EngineEvent.Finished }) break
                val stopped = rig.eventsOf(id).filterIsInstance<EngineEvent.Stopped>().last()
                // a restart after a hard kill only has what the row last saved: sometimes the exact set, sometimes an older one
                pieces = if (rnd.nextBoolean() || stale == null) stopped.pieces else stale
                killsOfSeed++
                kills++
            }
            if (killsOfSeed > 0) killedSeeds++
            val end = assertIs<EngineEvent.Finished>(rig.awaitTerminal(id, 60_000), "run $seed")
            assertEquals(t.meta.totalSize, end.done)
            t.assertDownloaded(rig.save, end.name ?: t.meta.name)
            swarm.close()
        }
        println("TorrentKills: $kills kills over $killedSeeds of 20 seeds")
        assertTrue(killedSeeds >= 8, "the runs must really be killed part way: only $killedSeeds of 20 seeds were ($kills kills)")
    }

    // AC19: logged, never failed on a slow machine
    @Test
    fun eightCappedPeersDeliverASixtyFourMiBTorrentAndTheRateIsLogged() {
        val size = 64 * MB
        val t = rig.torrent(listOf("big.bin" to size), pieceLength = 256 * KB, single = true, name = "big.bin")
        val swarm = rig.swarm(t, *Array(8) { PeerScript(bytesPerSec = 2_000_000) })
        val engine = rig.engine(clock = real, limits = TorrentLimits(reportMs = 500))
        val begun = System.nanoTime()
        engine.start(t.job(peers = swarm.addrs, folder = rig.save))
        assertIs<EngineEvent.Finished>(rig.awaitTerminal(timeoutMs = 120_000))
        val seconds = (System.nanoTime() - begun) / 1e9
        val rate = size / seconds / 1e6
        println(
            "TorrentThroughput: 64 MiB from 8 peers capped at 2 MB/s each in %.1f s = %.1f MB/s (aim: at least 12)".format(seconds, rate),
        )
        if (rate < 12) println("TorrentThroughput: below the aim; not a failure on a slow machine")
        assertContentEquals(digestOf(t.data), digestOf(Files.readAllBytes(rig.save.resolve("big.bin"))))
    }

    private fun digestOf(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)
}
