package dev.anydm.engine.torrent

import dev.anydm.engine.http.DiskIo
import dev.anydm.engine.http.EngineEvent
import dev.anydm.engine.http.PartFile
import dev.anydm.engine.http.RealDiskIo
import dev.anydm.engine.http.Reasons
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The torrent engine against fake peers on loopback: one run per test, a fixed peer list, no trackers. */
class TorrentRunTest {
    private val rig = Rig()

    @AfterTest
    fun tearDown() = rig.close()

    private fun finishedOf(id: String = "1") = assertIs<EngineEvent.Finished>(rig.awaitTerminal(id))

    private fun failedOf(id: String = "1") = assertIs<EngineEvent.Failed>(rig.awaitTerminal(id))

    private fun all(
        n: Int,
        script: PeerScript = PeerScript(),
    ) = Array(n) { script }

    private class LowSpace(
        private val inner: DiskIo,
        private val free: Long,
    ) : DiskIo by inner {
        override fun freeSpace(dir: Path) = free
    }

    /** An address nothing listens on. */
    private fun deadAddr(): PeerAddr {
        val s = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val port = s.localPort
        s.close()
        return PeerAddr("127.0.0.1", port)
    }

    // AC9 (single file)
    @Test
    fun aSingleFileComesFromOnePeerAndEndsAsTheFileWithTheRightBytes() {
        val t = rig.torrent(listOf("movie.bin" to 100_000), single = true, name = "movie.bin")
        val swarm = rig.swarm(t, PeerScript())
        rig.engine().start(t.job(peers = swarm.addrs, folder = rig.save))
        val end = finishedOf()
        assertEquals(100_000L, end.done)
        assertNull(end.name, "the name is the one asked for")
        t.assertDownloaded(rig.save)
        val started = rig.eventsOf().filterIsInstance<EngineEvent.Started>().single()
        assertEquals("movie.bin", started.name)
        assertEquals(100_000L, started.total)
        assertEquals(PieceSet.empty(t.meta.pieceCount), started.pieces)
    }

    // AC9
    @Test
    fun aMultiFileTorrentWithPiecesSpanningFilesKeepsPartFilesWhileRunningAndEndsWithEveryFileRight() {
        val t = rig.torrent(listOf("a.bin" to 40_000, "d/b.bin" to 0, "d/e/c.bin" to 70_000, "z" to 3), pieceLength = 16 * KB)
        val swarm = rig.swarm(t, PeerScript(holdUnchoke = true))
        rig.engine().start(t.job(peers = swarm.addrs, folder = rig.save))
        rig.await("Started") { rig.eventsOf().any { it is EngineEvent.Started } }
        val root = rig.save.resolve("t")
        assertTrue(Files.exists(root.resolve("a.bin.part")) && Files.exists(root.resolve("d/e/c.bin.part")), "part files while running")
        assertFalse(Files.exists(root.resolve("a.bin")) || Files.exists(root.resolve("d/e/c.bin")), "no final name yet")
        swarm.peers.single().also { p ->
            rig.await("the peer to be asked to unchoke") { p.received.contains(PeerMsg.Interested) }
            p.unchoke()
        }
        assertEquals(110_003L, finishedOf().done)
        t.assertDownloaded(rig.save)
        assertEquals(0L, Files.size(root.resolve("d/b.bin")))
    }

    // AC8
    @Test
    fun aCorruptPieceIsThrownAwayAndFetchedAgain() {
        val t = rig.torrent(listOf("f.bin" to 200_000), pieceLength = 32 * KB, single = true, name = "f.bin")
        val swarm = rig.swarm(t, PeerScript(corrupt = setOf(2), corruptTimes = 1))
        rig.engine().start(t.job(peers = swarm.addrs, folder = rig.save))
        finishedOf()
        t.assertDownloaded(rig.save)
        assertTrue(
            swarm.requestsFor(2) >= 2 * t.meta.let { Layout(it).blockCount(2) },
            "piece 2 was asked for twice: ${swarm.requestsFor(2)}",
        )
    }

    // AC8
    @Test
    fun aPeerThatSentDataForThreeFailedPiecesIsDroppedAndNeverConnectedToAgain() {
        val t = rig.torrent(listOf("f.bin" to 256 * KB), pieceLength = 32 * KB, single = true, name = "f.bin")
        val swarm =
            rig.swarm(
                t,
                PeerScript(corrupt = (0 until 8).toSet()), // the bad one
                PeerScript(holdUnchoke = true), // the good one, which only helps once the bad one is gone
                PeerScript(holdUnchoke = true), // another, offered later
            )
        val (bad, good, other) = swarm.peers
        val engine = rig.engine()
        engine.start(t.job(peers = listOf(bad.addr, good.addr), folder = rig.save))
        rig.await("the bad peer to be dropped") { bad.accepted == 1 && bad.open == 0 }
        // offered again, the banned address is skipped: the other one that follows is connected
        // (nobody is unchoked yet, so the run cannot have finished before it is told)
        runBlocking { engine.offerPeers("1", listOf(bad.addr, other.addr)) }
        rig.await("the other peer") { other.accepted == 1 }
        good.unchoke()
        other.unchoke()
        finishedOf()
        t.assertDownloaded(rig.save)
        assertEquals(1, bad.accepted, "never reconnected")
    }

    // R7: a choke returns the blocks at once
    @Test
    fun aChokeGivesTheBlocksOfThatPeerBackAtOnceSoNoClockIsNeeded() {
        // one piece of four blocks; three peers each take the four blocks (endgame lets them) and choke instead of answering
        val t = rig.torrent(listOf("f.bin" to 64 * KB), pieceLength = 64 * KB, single = true, name = "f.bin")
        val swarm = rig.swarm(t, *all(3, PeerScript(chokeAfterAnswers = 0)), PeerScript(holdUnchoke = true))
        val engine = rig.engine()
        engine.start(t.job(peers = swarm.addrs, folder = rig.save))
        val chokers = swarm.peers.take(3)
        val helper = swarm.peers.last()
        rig.await("every choking peer to have been asked") { chokers.all { it.requests.isNotEmpty() } }
        rig.await("the helper to be waiting") { helper.received.contains(PeerMsg.Interested) }
        helper.unchoke()
        finishedOf()
        t.assertDownloaded(rig.save)
        assertEquals(0, rig.clock.nowMs(), "the clock never moved: the stall rule did not help")
    }

    // R7: a peer that takes requests and delivers nothing
    @Test
    fun aPeerThatDeliversNoBlockForSixtySecondsHasItsRequestsGivenToOthers() {
        val t = rig.torrent(listOf("f.bin" to 64 * KB), pieceLength = 64 * KB, single = true, name = "f.bin")
        val swarm = rig.swarm(t, *all(3, PeerScript(neverAnswer = true)), PeerScript(holdUnchoke = true))
        val engine = rig.engine()
        engine.start(t.job(peers = swarm.addrs, folder = rig.save))
        val stalled = swarm.peers.take(3)
        val helper = swarm.peers.last()
        rig.await("every stalled peer to hold the four blocks") { stalled.all { it.requests.size == 4 } }
        rig.await("the helper to be waiting") { helper.received.contains(PeerMsg.Interested) }
        helper.unchoke()
        Thread.sleep(150)
        assertTrue(helper.requests.isEmpty(), "the block is held by three peers: nobody else may ask for it yet")
        rig.clock.advance(59_000)
        Thread.sleep(150)
        assertTrue(helper.requests.isEmpty(), "59 seconds are not 60")
        rig.clock.advance(2_000)
        finishedOf()
        t.assertDownloaded(rig.save)
    }

    // R7: ... and is asked for nothing for two minutes, then again
    @Test
    fun aStalledPeerIsAskedForNothingForTwoMinutesAndThenAgain() {
        val t = rig.torrent(listOf("f.bin" to 64 * KB), pieceLength = 64 * KB, single = true, name = "f.bin")
        val swarm = rig.swarm(t, PeerScript(neverAnswer = true))
        val peer = swarm.peers.single()
        rig.engine().start(t.job(peers = swarm.addrs, folder = rig.save))
        rig.await("the four blocks") { peer.requests.size == 4 }
        rig.clock.advance(60_001)
        peer.sendKeepAlive() // it is quiet, not dead
        Thread.sleep(200)
        assertEquals(4, peer.requests.size, "released and snubbed: not asked again")
        rig.clock.advance(119_000)
        Thread.sleep(200)
        assertEquals(4, peer.requests.size, "the two minutes are not over")
        rig.clock.advance(2_000)
        rig.await("the blocks to be asked for again") { peer.requests.size == 8 }
    }

    // NFR2
    @Test
    fun aBlockNobodyAskedForDropsThePeerWhoSentIt() {
        val t = rig.torrent(listOf("f.bin" to 128 * KB), pieceLength = 32 * KB, single = true, name = "f.bin")
        val swarm = rig.swarm(t, PeerScript(has = (1 until 4).toSet(), sendUnrequested = true), PeerScript(holdUnchoke = true))
        val (rude, honest) = swarm.peers
        rig.engine().start(t.job(peers = swarm.addrs, folder = rig.save))
        rig.await("the rude peer to be dropped") { rude.accepted == 1 && rude.open == 0 }
        rig.await("the honest peer to wait") { honest.received.contains(PeerMsg.Interested) }
        honest.unchoke()
        finishedOf()
        t.assertDownloaded(rig.save)
    }

    // a peer lost to a network error is not lost for good: it is tried again after a short wait
    @Test
    fun aPeerThatWasConnectedAndLostToANetworkErrorIsConnectedToAgainAfterAShortWait() {
        val t = rig.torrent(listOf("f.bin" to 100_000), single = true, name = "f.bin")
        val swarm = rig.swarm(t, PeerScript(dropAfterAnswers = 3))
        val peer = swarm.peers.single()
        rig.engine().start(t.job(peers = swarm.addrs, folder = rig.save))
        rig.await("the first connection to be cut") { peer.accepted == 1 && peer.open == 0 }
        rig.clock.advance(1_000)
        Thread.sleep(200)
        assertEquals(1, peer.accepted, "not at once")
        // each connection carries three blocks, the torrent has seven: three connections, a wait before each of the later two
        rig.clock.advance(RECONNECT_WAIT_MS)
        rig.await("the second connection") { peer.accepted == 2 }
        rig.await("the second connection to be cut") { peer.open == 0 }
        Thread.sleep(300) // the run has seen it: its wait starts at this time on the clock, so the clock must not move before
        rig.clock.advance(RECONNECT_WAIT_MS)
        finishedOf()
        t.assertDownloaded(rig.save)
        assertEquals(3, peer.accepted)
    }

    @Test
    fun aPeerThatAlwaysDropsWithoutDeliveringAnythingIsGivenUpAfterAFewTries() {
        val t = rig.torrent(listOf("f.bin" to 100_000), single = true, name = "f.bin")
        val swarm = rig.swarm(t, PeerScript(closeAtFirstRequest = true))
        val peer = swarm.peers.single()
        rig.engine().start(t.job(peers = swarm.addrs, folder = rig.save))
        rig.await("the first connection to be cut") { peer.accepted == 1 && peer.open == 0 }
        repeat(MAX_RECONNECTS) { n ->
            Thread.sleep(300) // the run has seen the drop: its wait starts at this time on the clock, so the clock must not move before
            rig.clock.advance(RECONNECT_WAIT_MS)
            rig.await("connection ${n + 2} to be made and cut") { peer.accepted == n + 2 && peer.open == 0 }
        }
        Thread.sleep(300)
        rig.clock.advance(RECONNECT_WAIT_MS)
        Thread.sleep(300)
        assertEquals(MAX_RECONNECTS + 1, peer.accepted, "the first connection and the retries, then it is let go")
    }

    @Test
    fun aPeerDroppedForAProtocolViolationIsNotConnectedToAgain() {
        val t = rig.torrent(listOf("f.bin" to 128 * KB), pieceLength = 32 * KB, single = true, name = "f.bin")
        val swarm = rig.swarm(t, PeerScript(twoBitfields = true), PeerScript(holdUnchoke = true))
        val (rude, honest) = swarm.peers
        rig.engine().start(t.job(peers = swarm.addrs, folder = rig.save))
        rig.await("the rude peer to be dropped") { rude.accepted == 1 && rude.open == 0 }
        rig.await("the honest peer to wait") { honest.received.contains(PeerMsg.Interested) }
        rig.clock.advance(60_000)
        Thread.sleep(300)
        assertEquals(1, rude.accepted, "never connected to again")
    }

    // NFR2
    @Test
    fun aBlockOfAPieceThatDoesNotExistDropsThePeerAndNothingIsWrittenOutsideTheTorrent() {
        val t = rig.torrent(listOf("f.bin" to 128 * KB), pieceLength = 32 * KB, single = true, name = "f.bin")
        val swarm = rig.swarm(t, PeerScript(has = setOf(0), sendOutOfRange = true), PeerScript(holdUnchoke = true))
        val (rude, honest) = swarm.peers
        rig.engine().start(t.job(peers = swarm.addrs, folder = rig.save))
        rig.await("the rude peer to be dropped") { rude.accepted == 1 && rude.open == 0 }
        honest.let { h ->
            rig.await("the honest peer to wait") { h.received.contains(PeerMsg.Interested) }
            h.unchoke()
        }
        finishedOf()
        t.assertDownloaded(rig.save)
        val names = Files.walk(rig.save).use { s -> s.map { rig.save.relativize(it).toString() }.toList() }
        assertEquals(setOf("", "f.bin"), names.toSet(), "only the torrent's file is in the folder: $names")
    }

    @Test
    fun aPeerWithAnotherInfoHashIsDroppedAndTheOthersFinishTheTorrent() {
        val t = rig.torrent(listOf("f.bin" to 100_000), single = true, name = "f.bin")
        val swarm = rig.swarm(t, PeerScript(wrongInfoHash = true), PeerScript())
        rig.engine().start(t.job(peers = swarm.addrs, folder = rig.save))
        finishedOf()
        t.assertDownloaded(rig.save)
        assertTrue(swarm.peers[0].requests.isEmpty(), "never asked for anything")
    }

    @Test
    fun aSecondBitfieldDropsThePeerAndTheTorrentFinishesWithTheOthers() {
        val t = rig.torrent(listOf("f.bin" to 100_000), single = true, name = "f.bin")
        val swarm = rig.swarm(t, PeerScript(twoBitfields = true), PeerScript(holdUnchoke = true))
        val (twice, honest) = swarm.peers
        rig.engine().start(t.job(peers = swarm.addrs, folder = rig.save))
        rig.await("the drop") { twice.accepted == 1 && twice.open == 0 }
        rig.await("the honest peer to wait") { honest.received.contains(PeerMsg.Interested) }
        honest.unchoke()
        finishedOf()
        t.assertDownloaded(rig.save)
    }

    @Test
    fun peersThatAnnounceTheirPiecesWithHaveMessagesAreUsedToo() {
        val t = rig.torrent(listOf("f.bin" to 100_000), single = true, name = "f.bin")
        val swarm = rig.swarm(t, PeerScript(haveInsteadOfBitfield = true))
        rig.engine().start(t.job(peers = swarm.addrs, folder = rig.save))
        finishedOf()
        t.assertDownloaded(rig.save)
    }

    // AC5
    @Test
    fun atMostFiftyPeersAreConnectedForOneTorrentWhenSixtyAreAvailable() {
        val t = rig.torrent(listOf("f.bin" to 100_000), single = true, name = "f.bin")
        val swarm = rig.swarm(t, *all(60, PeerScript(holdUnchoke = true)))
        val counting = CountingTransport()
        rig.engine(transport = counting).start(t.job(peers = swarm.addrs, folder = rig.save))
        rig.await("fifty connections") { counting.open == 50 }
        Thread.sleep(300)
        assertEquals(50, counting.peak, "never more than fifty")
        assertEquals(50, counting.tried)
        assertEquals(50, swarm.peers.sumOf { it.accepted })
    }

    // AC5
    @Test
    fun aListWithADuplicateAndOurOwnPeerIdMakesNoDoubleAndNoSelfConnectionThatIsAskedForAnything() {
        val t = rig.torrent(listOf("f.bin" to 100_000), single = true, name = "f.bin")
        val swarm = rig.swarm(t, PeerScript(), PeerScript(peerId = Rig.PEER_ID), PeerScript())
        val (a, us, c) = swarm.peers
        val counting = CountingTransport()
        rig.engine(transport = counting).start(t.job(peers = listOf(a.addr, a.addr, us.addr, c.addr, a.addr), folder = rig.save))
        finishedOf()
        t.assertDownloaded(rig.save)
        assertEquals(1, a.accepted, "one connection per address")
        assertEquals(1, c.accepted)
        assertTrue(us.requests.isEmpty(), "never asked ourselves for a block")
        assertEquals(3, counting.tried)
    }

    // AC10
    @Test
    fun aDiskThatCannotReserveTheSizeFailsTheRowWithDiskFullBeforeAnyPeerIsContacted() {
        val t = rig.torrent(listOf("f.bin" to 100_000), single = true, name = "f.bin")
        val swarm = rig.swarm(t, PeerScript())
        val counting = CountingTransport()
        rig.engine(transport = counting, disk = LowSpace(RealDiskIo(), 99_999)).start(t.job(peers = swarm.addrs, folder = rig.save))
        assertEquals(Reasons.DISK_FULL, failedOf().reason)
        Thread.sleep(150)
        assertEquals(0, counting.tried, "no connection was tried")
        assertEquals(0, swarm.peers.single().accepted)
        assertTrue(rig.eventsOf().none { it is EngineEvent.Started }, "the row never started")
    }

    // AC11
    @Test
    fun doneBytesAreTheSumOfVerifiedPiecesWithTheShortLastOneAndProgressComesEveryReportInterval() {
        val total = 4 * 16 * KB + 5_000 // five pieces, the last one 5000 bytes
        val t = rig.torrent(listOf("f.bin" to total), single = true, name = "f.bin")
        val swarm = rig.swarm(t, PeerScript(has = setOf(0, 4)))
        val limits = TorrentLimits(reportMs = 500)
        rig.engine(limits = limits).start(t.job(peers = swarm.addrs, folder = rig.save))
        rig.await("Started") { rig.eventsOf().any { it is EngineEvent.Started } }

        fun progress() = rig.eventsOf().filterIsInstance<EngineEvent.Progress>()
        // every 500 ms of the clock brings one report, until the peer's two pieces are in
        var reports = 0
        while (progress().lastOrNull()?.done != 16L * KB + 5_000) {
            check(reports < 40) { "the pieces never arrived: ${progress().lastOrNull()}" }
            rig.clock.advance(500)
            reports++
            rig.await("progress number $reports") { progress().size >= reports }
        }
        assertEquals(reports, progress().size, "one report per 500 ms of the clock")
        val last = progress().last()
        assertEquals(16L * KB + 5_000, last.done, "piece 0 and the short last piece")
        assertEquals(PieceSet.of(5, listOf(0, 4)), last.pieces)
        assertEquals(total.toLong(), last.total)
        assertEquals(1, last.peers)
        assertTrue(last.inFlight.isEmpty(), "nothing is in flight: the peer has no other piece")
        val before = progress().size
        rig.clock.advance(100)
        Thread.sleep(150)
        assertEquals(before, progress().size, "not before the interval is over")
        rig.clock.advance(400)
        rig.await("the next one") { progress().size == before + 1 }
    }

    // AC12
    @Test
    fun pauseClosesEveryConnectionWithinASecondAndResumeFetchesNoVerifiedPieceAgain() {
        val t = rig.torrent(listOf("f.bin" to 3_000_000), pieceLength = 32 * KB, single = true, name = "f.bin")
        val swarm = rig.swarm(t, *all(4, PeerScript(bytesPerSec = 250_000)))
        val counting = CountingTransport()
        val engine = rig.engine(transport = counting)
        engine.start(t.job(peers = swarm.addrs, folder = rig.save))
        rig.await("some blocks") { swarm.peers.sumOf { it.answers } >= 40 }
        assertTrue(counting.open > 0)
        engine.stop("1")
        rig.await("every connection to close", timeoutMs = 1_000) { counting.open == 0 }
        runBlocking { engine.join("1") }
        val stopped = rig.awaitStopped()
        val pieces = assertNotNull(stopped.pieces)
        assertTrue(pieces.count > 0 && !pieces.isComplete, "paused part way: ${pieces.count} of ${pieces.size}")
        assertEquals(pieces.doneBytes(t.meta), stopped.done)
        // what the row says is verified is on disk, byte for byte
        val part = Files.readAllBytes(rig.save.resolve("f.bin.part"))
        for (i in 0 until pieces.size) {
            if (pieces.has(i)) {
                val from = i * 32 * KB
                val len = t.meta.pieceSize(i).toInt()
                assertContentEquals(t.data.copyOfRange(from, from + len), part.copyOfRange(from, from + len), "piece $i")
            }
        }
        rig.await("the peers to have seen every request of the first run") { swarm.peers.all { it.open == 0 } }
        val asked = (0 until pieces.size).associateWith { swarm.requestsFor(it) }
        engine.start(t.job(peers = swarm.addrs, folder = rig.save, pieces = pieces))
        finishedOf()
        t.assertDownloaded(rig.save)
        for (i in 0 until pieces.size) if (pieces.has(i)) assertEquals(asked[i], swarm.requestsFor(i), "piece $i was fetched again")
    }

    @Test
    fun pauseWhileConnectingStopsTheAttemptAtOnce() {
        // a listener that takes connections and says nothing: the handshake is what the run waits for
        val quiet = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val t = rig.torrent(listOf("f.bin" to 100_000), single = true, name = "f.bin")
        val counting = CountingTransport()
        val engine = rig.engine(transport = counting)
        try {
            engine.start(t.job(peers = listOf(PeerAddr("127.0.0.1", quiet.localPort)), folder = rig.save))
            rig.await("the connection") { counting.open == 1 }
            engine.stop("1")
            rig.await("the connection to close", timeoutMs = 1_000) { counting.open == 0 }
            runBlocking { engine.join("1") }
            rig.awaitStopped()
        } finally {
            quiet.close()
        }
    }

    // AC15 (the run's part)
    @Test
    fun withNoPeerConnectedForTwoMinutesTheRowFailsWithNoPeers() {
        val t = rig.torrent(listOf("f.bin" to 100_000), single = true, name = "f.bin")
        rig.engine().start(t.job(peers = listOf(deadAddr()), folder = rig.save))
        rig.await("Started") { rig.eventsOf().any { it is EngineEvent.Started } }
        rig.clock.advance(119_000)
        Thread.sleep(200)
        assertTrue(rig.eventsOf().none { it is EngineEvent.Failed }, "two minutes have not passed")
        rig.clock.advance(2_000)
        val failed = failedOf()
        assertEquals(NO_PEERS, failed.reason)
        assertEquals(PieceSet.empty(t.meta.pieceCount), failed.pieces)
    }

    @Test
    fun aPeerThatStaysConnectedKeepsTheRowFromFailingWithNoPeersEvenWhenItNeverUnchokes() {
        val t = rig.torrent(listOf("f.bin" to 100_000), single = true, name = "f.bin")
        val swarm = rig.swarm(t, PeerScript(holdUnchoke = true))
        val peer = swarm.peers.single()
        rig.engine().start(t.job(peers = swarm.addrs, folder = rig.save))
        rig.await("the peer to be waiting") { peer.received.contains(PeerMsg.Interested) }
        rig.clock.advance(170_000) // the peer is quiet for under three minutes, and the row has been without data for over two
        Thread.sleep(300)
        assertTrue(rig.eventsOf().none { it is EngineEvent.Failed }, "choked is not 'no peers': ${rig.eventsOf().takeLast(3)}")
    }

    @Test
    fun aMissingTorrentCopyFailsTheRowWithCantResume() {
        val t = rig.torrent(listOf("f.bin" to 1_000), single = true, name = "f.bin")
        Files.delete(t.torrentFile)
        rig.engine().start(t.job(folder = rig.save))
        val failed = failedOf()
        assertEquals(CANT_RESUME, failed.reason)
        assertNull(failed.pieces, "the row keeps its own")
    }

    @Test
    fun aTorrentCopyThatIsNotATorrentFailsTheRowWithCantResume() {
        val t = rig.torrent(listOf("f.bin" to 1_000), single = true, name = "f.bin")
        Files.write(t.torrentFile, "not a torrent".encodeToByteArray())
        rig.engine().start(t.job(folder = rig.save))
        assertEquals(CANT_RESUME, failedOf().reason)
    }

    @Test
    fun aTorrentOfNothingFinishesWithoutConnectingToAnyPeer() {
        val t = rig.torrent(listOf("empty.txt" to 0), single = true, name = "empty.txt")
        val counting = CountingTransport()
        val swarm = rig.swarm(t, PeerScript())
        rig.engine(transport = counting).start(t.job(peers = swarm.addrs, folder = rig.save))
        assertEquals(0L, finishedOf().done)
        assertEquals(0, counting.tried)
        assertEquals(0L, Files.size(rig.save.resolve("empty.txt")))
    }

    @Test
    fun aResumeWithEveryPieceAlreadyDoneRenamesTheFilesWithoutConnectingToAnyPeer() {
        val t = rig.torrent(listOf("f.bin" to 50_000), single = true, name = "f.bin")
        Files.write(rig.save.resolve("f.bin.part"), t.data) // what a run stopped just before the end left
        val counting = CountingTransport()
        val swarm = rig.swarm(t, PeerScript())
        val all = PieceSet.of(t.meta.pieceCount, 0 until t.meta.pieceCount)
        rig.engine(transport = counting).start(t.job(peers = swarm.addrs, folder = rig.save, pieces = all))
        assertEquals(50_000L, finishedOf().done)
        assertEquals(0, counting.tried)
        t.assertDownloaded(rig.save)
    }

    @Test
    fun aNameTakenByAFileOfTheUserGetsAFreeNameAtStartAndFinishedReportsIt() {
        val t = rig.torrent(listOf("f.bin" to 50_000), single = true, name = "f.bin")
        Files.writeString(rig.save.resolve("f.bin"), "the user's")
        val swarm = rig.swarm(t, PeerScript())
        rig.engine().start(t.job(peers = swarm.addrs, folder = rig.save))
        val end = finishedOf()
        assertEquals("f (1).bin", end.name)
        assertEquals(
            "f (1).bin",
            rig
                .eventsOf()
                .filterIsInstance<EngineEvent.Started>()
                .single()
                .name,
        )
        assertEquals("the user's", Files.readString(rig.save.resolve("f.bin")))
        assertContentEquals(t.data, Files.readAllBytes(rig.save.resolve("f (1).bin")))
    }

    // NFR4
    @Test
    fun unverifiedDataStaysWithinTheBufferCapAndTheTorrentStillFinishes() {
        val t = rig.torrent(listOf("f.bin" to 2 * MB), pieceLength = 256 * KB, single = true, name = "f.bin")
        val swarm = rig.swarm(t, *all(10, PeerScript(answerDelayMs = 5)))
        val engine = rig.engine(limits = TorrentLimits(reportMs = 50, maxBuffered = 100_000))
        engine.start(t.job(peers = swarm.addrs, folder = rig.save))
        rig.await("the run") { engine.runOf("1") != null }
        val run = engine.runOf("1")!!
        finishedOf()
        t.assertDownloaded(rig.save)
        val cap = maxOf(100_000L, 256L * KB) // one piece: the cap is never below it
        assertTrue(run.peakBuffered <= cap, "held ${run.peakBuffered} bytes at most, the cap is $cap")
        assertTrue(run.peakBuffered > 0)
    }

    // NFR4: pieces held back by the cap that nobody can finish
    @Test
    fun whenTheBufferIsFullOfHalfPiecesAndNothingIsOnItsWayTheLeastCompleteOneIsGivenUp() {
        val t = rig.torrent(listOf("f.bin" to 512 * KB), pieceLength = 128 * KB, single = true, name = "f.bin")
        val choking = (0 until 4).map { PeerScript(has = setOf(it), chokeAfterAnswers = 4) }
        val swarm = rig.swarm(t, *choking.toTypedArray(), PeerScript(holdUnchoke = true))
        val engine = rig.engine(limits = TorrentLimits(reportMs = 50, maxBuffered = 8 * BLOCK.toLong() * 2))
        engine.start(t.job(peers = swarm.addrs, folder = rig.save))
        val helper = swarm.peers.last()
        rig.await("every choking peer to have given four blocks") { swarm.peers.take(4).all { it.answers == 4 } }
        rig.await("the helper to wait") { helper.received.contains(PeerMsg.Interested) }
        helper.unchoke()
        finishedOf()
        t.assertDownloaded(rig.save)
    }

    /** A disk that has room for [writes] writes of pieces and then says "No space left". */
    private class FillingDisk(
        private val inner: DiskIo,
        private val writes: Int,
    ) : DiskIo by inner {
        private val left =
            java.util.concurrent.atomic
                .AtomicInteger(writes)

        override fun openSpans(
            p: Path,
            size: Long?,
        ): PartFile {
            val real = inner.openSpans(p, size)
            return object : PartFile by real {
                override fun write(
                    offset: Long,
                    buf: ByteArray,
                    off: Int,
                    len: Int,
                ) {
                    if (left.getAndDecrement() <= 0) throw java.io.IOException("No space left on device")
                    real.write(offset, buf, off, len)
                }
            }
        }
    }

    /** A disk whose writes wait for [gate] to be opened. */
    private class GatedDisk(
        private val inner: DiskIo,
    ) : DiskIo by inner {
        val gate = java.util.concurrent.CountDownLatch(1)
        val waiting =
            java.util.concurrent.atomic
                .AtomicInteger()

        override fun openSpans(
            p: Path,
            size: Long?,
        ): PartFile {
            val real = inner.openSpans(p, size)
            return object : PartFile by real {
                override fun write(
                    offset: Long,
                    buf: ByteArray,
                    off: Int,
                    len: Int,
                ) {
                    waiting.incrementAndGet()
                    gate.await()
                    real.write(offset, buf, off, len)
                }
            }
        }
    }

    // R12 / the integrity order: write, then mark, then report
    @Test
    fun aPieceIsNotReportedVerifiedUntilItsBytesAreWritten() {
        val t = rig.torrent(listOf("f.bin" to 160 * KB), single = true, name = "f.bin")
        val swarm = rig.swarm(t, PeerScript())
        val disk = GatedDisk(RealDiskIo())
        val real =
            dev.anydm.engine.http
                .Clock { System.currentTimeMillis() }
        try {
            rig
                .engine(
                    disk = disk,
                    clock = real,
                    limits = TorrentLimits(reportMs = 30),
                ).start(t.job(peers = swarm.addrs, folder = rig.save))
            rig.await("a write to be waiting") { disk.waiting.get() > 0 }
            rig.await("two reports while it waits") { rig.eventsOf().count { it is EngineEvent.Progress } >= 2 }
            val reports = rig.eventsOf().filterIsInstance<EngineEvent.Progress>()
            assertTrue(
                reports.all { it.pieces!!.count == 0 && it.done == 0L },
                "nothing is verified yet: ${reports.map { it.pieces!!.count }}",
            )
        } finally {
            disk.gate.countDown()
        }
        finishedOf()
        t.assertDownloaded(rig.save)
    }

    @Test
    fun aDiskThatFillsUpAfterTheReservationFailsTheRowWithDiskFullKeepsTheVerifiedPiecesAndFreesTheConnections() {
        val t = rig.torrent(listOf("f.bin" to 400_000), pieceLength = 32 * KB, single = true, name = "f.bin")
        val swarm = rig.swarm(t, *all(2, PeerScript()))
        val counting = CountingTransport()
        rig.engine(transport = counting, disk = FillingDisk(RealDiskIo(), 3)).start(t.job(peers = swarm.addrs, folder = rig.save))
        val failed = failedOf()
        assertEquals(Reasons.DISK_FULL, failed.reason)
        val pieces = assertNotNull(failed.pieces)
        assertTrue(pieces.count <= 3, "at most the three that were written: ${pieces.count}")
        val part = Files.readAllBytes(rig.save.resolve("f.bin.part"))
        for (i in 0 until pieces.size) {
            if (pieces.has(i)) {
                val from = i * 32 * KB
                val to = from + t.meta.pieceSize(i).toInt()
                assertContentEquals(t.data.copyOfRange(from, to), part.copyOfRange(from, to), "piece $i")
            }
        }
        rig.await("every connection to close", timeoutMs = 1_000) { counting.open == 0 }
    }

    @Test
    fun everyConnectionIsClosedWhenTheTorrentFinishes() {
        val t = rig.torrent(listOf("f.bin" to 100_000), single = true, name = "f.bin")
        val swarm = rig.swarm(t, PeerScript())
        val counting = CountingTransport()
        rig.engine(transport = counting).start(t.job(peers = swarm.addrs, folder = rig.save))
        finishedOf()
        rig.await("the connection to close") { counting.open == 0 }
    }

    // NFR4: a full buffer that nobody who may ask can finish
    @Test
    fun aBufferFullOfPiecesOnlyChokedPeersHaveStillLetsAPeerWithOtherPiecesFinishItsOwn() {
        // eight pieces of eight blocks; four peers each hold one of the upper four and choke after four blocks, so the buffer (sixteen
        // blocks) is full of halves nobody can finish; the only peer that may ask has the lower four, none of the started ones
        val t = rig.torrent(listOf("f.bin" to MB), pieceLength = 128 * KB, single = true, name = "f.bin")
        val holders = (4 until 8).map { PeerScript(has = setOf(it), chokeAfterAnswers = 4) }
        val swarm =
            rig.swarm(
                t,
                *holders.toTypedArray(),
                PeerScript(has = (0 until 4).toSet(), holdUnchoke = true),
                PeerScript(holdUnchoke = true), // has everything, joins in once the lower four are in
            )
        val (helper, late) = swarm.peers.drop(4)
        val engine = rig.engine(limits = TorrentLimits(reportMs = 50, maxBuffered = 16L * BLOCK))
        engine.start(t.job(peers = swarm.addrs, folder = rig.save))
        rig.await("every holder to have given four blocks") { swarm.peers.take(4).all { it.answers == 4 } }
        rig.await("the helper to wait") { helper.received.contains(PeerMsg.Interested) }
        helper.unchoke()
        rig.await("the helper's four pieces to be in", timeoutMs = 20_000) { (engine.runOf("1")?.verified?.count ?: 0) >= 4 }
        rig.await("the last peer to wait") { late.received.contains(PeerMsg.Interested) }
        late.unchoke()
        finishedOf()
        t.assertDownloaded(rig.save)
    }

    // R8: endgame
    @Test
    fun inEndgameASlowPeerIsToldToCancelWhatAnotherDeliveredIsNotDroppedAndItsLateBlocksAreTakenAsDuplicates() {
        val t = rig.torrent(listOf("f.bin" to 128 * KB), pieceLength = 64 * KB, single = true, name = "f.bin")
        val swarm =
            rig.swarm(
                t,
                PeerScript(has = setOf(0), answerDelayMs = 200), // slow: asked for piece 0, answers one block per 200 ms
                PeerScript(has = setOf(1), answerDelayMs = 400), // keeps the run going: the only one with piece 1
                PeerScript(has = setOf(0), holdUnchoke = true), // fast, and asked for piece 0 only once every block is in the air
            )
        val (slow, other, fast) = swarm.peers
        rig.engine().start(t.job(peers = swarm.addrs, folder = rig.save))
        // the fake peers note a request when they come to it, one answer delay apart: the first one says the run asked for all four
        rig.await("every block to be in the air") { slow.requests.isNotEmpty() && other.requests.isNotEmpty() }
        rig.await("the fast peer to wait") { fast.received.contains(PeerMsg.Interested) }
        fast.unchoke()
        val piece0 = (0 until 4).map { Block(0, it * BLOCK, BLOCK) }
        rig.await("the fast peer to be asked for piece 0, in endgame") { fast.requests.toSet() == piece0.toSet() }
        rig.await("the cancels to reach the slow peer") { slow.cancels.toSet() == piece0.toSet() }
        rig.await("the slow peer to answer all four blocks, late") { slow.answers == 4 }
        Thread.sleep(150) // the run reads them
        assertEquals(1, slow.open, "late blocks of a cancelled request are no violation")
        assertTrue(
            rig.eventsOf().none {
                it is EngineEvent.Finished || it is EngineEvent.Failed
            },
            "the run is still going: ${rig.eventsOf().takeLast(2)}",
        )
        finishedOf()
        t.assertDownloaded(rig.save)
    }

    /** A disk that remembers the size of every array it is asked to write. */
    private class RecordingDisk(
        private val inner: DiskIo,
    ) : DiskIo by inner {
        val sizes = java.util.concurrent.CopyOnWriteArrayList<Int>()

        override fun openSpans(
            p: Path,
            size: Long?,
        ): PartFile {
            val real = inner.openSpans(p, size)
            return object : PartFile by real {
                override fun write(
                    offset: Long,
                    buf: ByteArray,
                    off: Int,
                    len: Int,
                ) {
                    sizes.add(buf.size)
                    real.write(offset, buf, off, len)
                }
            }
        }
    }

    // NFR4: what the cap bounds is all there is
    @Test
    fun aVerifiedPieceIsWrittenFromTheBlocksItArrivedInWithNoWholePieceCopyOnTop() {
        // pieces of four blocks that run across the borders of the files, and a border inside a block
        val t = rig.torrent(listOf("a.bin" to 40_000, "d/b.bin" to 70_000, "z" to 3), pieceLength = 64 * KB)
        val swarm = rig.swarm(t, PeerScript())
        val disk = RecordingDisk(RealDiskIo())
        rig.engine(disk = disk).start(t.job(peers = swarm.addrs, folder = rig.save))
        finishedOf()
        t.assertDownloaded(rig.save)
        assertTrue(disk.sizes.isNotEmpty())
        assertTrue(disk.sizes.all { it <= BLOCK }, "every write came straight from a block: ${disk.sizes.toSet()}")
    }

    // R9 / NFR1: the files of a finish that stopped half way
    @Test
    fun aFinishThatFailedHalfWayIsResumedWithoutZeroingTheFilesAlreadyRenamed() {
        val t = rig.torrent(listOf("a.bin" to 40_000, "d/b.bin" to 40_000, "c.bin" to 1_000))
        val swarm = rig.swarm(t, PeerScript())
        val failing =
            object : DiskIo by RealDiskIo() {
                val fail =
                    java.util.concurrent.atomic
                        .AtomicBoolean(true)

                override fun move(
                    from: Path,
                    to: Path,
                ) {
                    if (from.fileName.toString() == "b.bin.part" &&
                        fail.getAndSet(false)
                    ) {
                        throw java.io.IOException("No space left on device")
                    }
                    RealDiskIo().move(from, to)
                }
            }
        val engine = rig.engine(disk = failing)
        engine.start(t.job(peers = swarm.addrs, folder = rig.save))
        val failed = failedOf()
        val pieces = assertNotNull(failed.pieces)
        assertTrue(pieces.isComplete, "every piece was in before the rename failed")
        assertTrue(Files.exists(rig.save.resolve("t/a.bin")) && Files.exists(rig.save.resolve("t/d/b.bin.part")), "half way")
        runBlocking { engine.join("1") }
        engine.start(t.job(peers = emptyList(), folder = rig.save, pieces = pieces))
        rig.await("the second end") { rig.eventsOf().count { it is EngineEvent.Finished || it is EngineEvent.Failed } == 2 }
        assertIs<EngineEvent.Finished>(rig.eventsOf().last { it is EngineEvent.Finished || it is EngineEvent.Failed })
        t.assertDownloaded(rig.save)
    }

    // R12
    @Test
    fun aReportThatIsDueButSeenByALateTickStillComesWithinTheReportInterval() {
        val t = rig.torrent(listOf("f.bin" to 100_000), single = true, name = "f.bin")
        val swarm = rig.swarm(t, PeerScript(holdUnchoke = true))
        rig.engine(limits = TorrentLimits(reportMs = 500)).start(t.job(peers = swarm.addrs, folder = rig.save))
        rig.await("Started") { rig.eventsOf().any { it is EngineEvent.Started } }
        // ticks are real time and may be late: the report of 500 ms is due by the first tick that sees 480 ms, not the one after
        rig.clock.advance(480)
        rig.await("a report 480 ms in", timeoutMs = 2_000) { rig.eventsOf().any { it is EngineEvent.Progress } }
    }

    // R12: the row's own figure survives an event sent before the metainfo is known
    @Test
    fun aFailureBeforeTheMetainfoIsReadHandsBackTheBytesTheRowShowedAndNotZero() {
        val t = rig.torrent(listOf("f.bin" to 1_000), single = true, name = "f.bin")
        Files.delete(t.torrentFile)
        rig.engine().start(t.job(folder = rig.save, doneBytes = 1_234))
        val failed = failedOf()
        assertEquals(CANT_RESUME, failed.reason)
        assertEquals(1_234L, failed.done)
    }

    @Test
    fun aFailureAfterTheMetainfoIsReadReportsTheBytesOfTheRowsOwnPieces() {
        val t = rig.torrent(listOf("f.bin" to 100_000), pieceLength = 32 * KB, single = true, name = "f.bin")
        val some = PieceSet.of(t.meta.pieceCount, listOf(0, 3))
        rig.engine(disk = LowSpace(RealDiskIo(), 0)).start(t.job(folder = rig.save, pieces = some, doneBytes = 999))
        val failed = failedOf()
        assertEquals(Reasons.DISK_FULL, failed.reason)
        assertEquals(some, failed.pieces)
        assertEquals(some.doneBytes(t.meta), failed.done)
    }

    // NFR2 / R6: a peer id is no identity
    @Test
    fun twoPeersThatShareAPeerIdAreBothKept() {
        val t = rig.torrent(listOf("f.bin" to 100_000), single = true, name = "f.bin")
        val shared = "-XX0001-".encodeToByteArray() + ByteArray(12)
        val swarm = rig.swarm(t, PeerScript(holdUnchoke = true, peerId = shared), PeerScript(holdUnchoke = true, peerId = shared))
        val (a, b) = swarm.peers
        rig.engine().start(t.job(peers = swarm.addrs, folder = rig.save))
        rig.await("both to be asked to unchoke") { a.received.contains(PeerMsg.Interested) && b.received.contains(PeerMsg.Interested) }
        Thread.sleep(100)
        assertEquals(1, a.open)
        assertEquals(1, b.open)
        a.unchoke()
        b.unchoke()
        finishedOf()
    }

    /** A transport that tries nothing: every connect is refused at once, and remembered. */
    private class RefusingTransport : PeerTransport {
        val addrs = java.util.concurrent.CopyOnWriteArrayList<PeerAddr>()

        override suspend fun connect(
            addr: PeerAddr,
            timeoutMs: Int,
        ): PeerConn {
            addrs.add(addr)
            throw java.io.IOException("refused")
        }
    }

    // NFR4: what a tracker (or anyone else) can make the run remember is bounded
    @Test
    fun theAddressesWaitingForAConnectionAreKeptToTheNewestThousandAndAnAddressLetGoCanBeOfferedAgain() {
        val t = rig.torrent(listOf("f.bin" to 100_000), single = true, name = "f.bin")
        val offered = List(5_000) { PeerAddr("10.0.${it / 250}.${it % 250 + 1}", 6881) }
        val transport = RefusingTransport()
        val engine = rig.engine(transport = transport)
        engine.start(t.job(peers = offered, folder = rig.save))
        rig.await("a thousand connects to have been tried") { transport.addrs.size >= 1_000 }
        Thread.sleep(300)
        assertEquals(1_000, transport.addrs.size, "the oldest four thousand were let go")
        assertEquals(offered.takeLast(1_000).toSet(), transport.addrs.toSet())
        runBlocking { engine.offerPeers("1", listOf(offered.first())) }
        rig.await("the first address to be tried now") { transport.addrs.size == 1_001 }
        assertEquals(offered.first(), transport.addrs.last())
    }

    // NFR2: a peer that floods us with messages
    @Test
    fun aFloodOfChokesFromAPeerWithNothingOutstandingDoesNotMakeTheRunAskThePickerOnEveryone() {
        val t = rig.torrent(listOf("f.bin" to 400_000), pieceLength = 32 * KB, single = true, name = "f.bin")
        val swarm = rig.swarm(t, *all(5, PeerScript(bytesPerSec = 50_000)), PeerScript(has = emptySet(), chokeFlood = 3_000))
        val (honest, flooder) = swarm.peers.take(5) to swarm.peers.last()
        val engine = rig.engine()
        engine.start(t.job(peers = honest.map { it.addr }, folder = rig.save))
        rig.await("the honest peers to be asked for blocks") { honest.all { it.requests.isNotEmpty() } }
        val run = engine.runOf("1")!!
        val before = run.pickerCalls
        runBlocking { engine.offerPeers("1", listOf(flooder.addr)) }
        rig.await("the flooder to have been connected") { flooder.accepted == 1 }
        Thread.sleep(500)
        val during = run.pickerCalls - before
        finishedOf()
        t.assertDownloaded(rig.save)
        assertTrue(during < 300, "3000 chokes cost $during asks of the picker")
    }

    // NFR2: a peer that floods us with `have` messages
    @Test
    fun aFloodOfHavesForPiecesWeHaveDoesNotMakeTheRunAskThePickerForRequests() {
        val t = rig.torrent(listOf("f.bin" to 64 * KB), pieceLength = 32 * KB, single = true, name = "f.bin")
        val have = PieceSet.of(t.meta.pieceCount, listOf(0)) // piece 0 is ours; the peer has piece 1 as well and says so over and over
        val swarm = rig.swarm(t, PeerScript(haveFlood = 3_000, answerDelayMs = 400))
        val engine = rig.engine(limits = TorrentLimits(reportMs = 4_000)) // a tick every 250 ms
        Files.write(rig.save.resolve("f.bin.part"), t.data.copyOf().also { it.fill(0, 32 * KB, 64 * KB) })
        engine.start(t.job(peers = swarm.addrs, folder = rig.save, pieces = have))
        rig.await("the run") { engine.runOf("1") != null }
        val run = engine.runOf("1")!!
        finishedOf()
        t.assertDownloaded(rig.save)
        // the whole run, flood included, is about a second: a few ticks, and no ask for each of the 3000 haves
        assertTrue(run.pickerCalls < 100, "3000 haves cost ${run.pickerCalls} asks of the picker")
    }

    /** A disk whose renames wait for [gate]; the arrivals at it are counted. */
    private class GatedMove(
        private val inner: DiskIo,
    ) : DiskIo by inner {
        val gate = java.util.concurrent.CountDownLatch(1)
        val waiting =
            java.util.concurrent.atomic
                .AtomicInteger()

        override fun move(
            from: Path,
            to: Path,
        ) {
            waiting.incrementAndGet()
            gate.await()
            inner.move(from, to)
        }
    }

    // R13 / the integrity order: a stop while pieces are being written
    @Test
    fun aStopWhileAPieceIsBeingWrittenDoesNotCountItAndTheResumeFetchesItAgain() {
        val t = rig.torrent(listOf("f.bin" to 160 * KB), single = true, name = "f.bin")
        val swarm = rig.swarm(t, PeerScript())
        val disk = GatedDisk(RealDiskIo())
        val engine = rig.engine(disk = disk)
        engine.start(t.job(peers = swarm.addrs, folder = rig.save))
        rig.await("a write to be waiting") { disk.waiting.get() > 0 }
        engine.stop("1")
        disk.gate.countDown() // the write that was under way ends, after the stop
        runBlocking { engine.join("1") }
        val stopped = rig.awaitStopped()
        assertEquals(0, assertNotNull(stopped.pieces).count, "a piece whose write was not confirmed is not a verified piece")
        assertEquals(0L, stopped.done)
        engine.start(t.job(peers = swarm.addrs, folder = rig.save, pieces = stopped.pieces))
        finishedOf()
        t.assertDownloaded(rig.save)
    }

    // R13: nothing is left half renamed
    @Test
    fun aStopDuringTheFinalRenamesDoesNotInterruptThemAndTheRowEndsFinishedNotPaused() {
        val t = rig.torrent(listOf("a.bin" to 40_000, "b.bin" to 60_000))
        val swarm = rig.swarm(t, PeerScript())
        val disk = GatedMove(RealDiskIo())
        val engine = rig.engine(disk = disk)
        engine.start(t.job(peers = swarm.addrs, folder = rig.save))
        rig.await("the renames to have begun") { disk.waiting.get() > 0 }
        engine.stop("1")
        disk.gate.countDown()
        runBlocking { engine.join("1") }
        assertIs<EngineEvent.Finished>(rig.awaitTerminal())
        assertTrue(
            rig.eventsOf().none {
                it is EngineEvent.Stopped
            },
            "no Stopped for a torrent that finished: ${rig.eventsOf().map { it::class.simpleName }}",
        )
        t.assertDownloaded(rig.save)
    }
}
