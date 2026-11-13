package dev.anydm.engine.torrent

import dev.anydm.engine.http.EngineEvent
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The engine with real tracker clients against in-process HTTP and UDP trackers and fake peers: where its peers come from. */
class TrackerRunTest {
    private val rig = Rig()
    private val toClose = mutableListOf<AutoCloseable>()

    @AfterTest
    fun tearDown() {
        rig.close()
        toClose.forEach { it.close() }
    }

    private fun <T : AutoCloseable> T.own(): T = also { toClose.add(it) }

    private val trackers = SchemeTrackerClient(HttpTrackerClient(timeoutMs = 3_000), UdpTrackerClient(rig.clock, tickMs = 5))

    private fun engine(
        transport: PeerTransport = TcpPeerTransport(),
        limits: TorrentLimits = TorrentLimits(reportMs = 50),
        client: TrackerClient = trackers,
    ) = rig.engine(transport = transport, trackers = client, limits = limits)

    private fun small(
        announce: String?,
        announceList: List<List<String>>? = null,
        size: Int = 100_000,
    ) = rig.torrent(listOf("f.bin" to size), single = true, name = "f.bin", announce = announce, announceList = announceList)

    private fun deadAddr(): PeerAddr {
        val s = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val port = s.localPort
        s.close()
        return PeerAddr("127.0.0.1", port)
    }

    private fun failed() = assertIs<EngineEvent.Failed>(rig.awaitTerminal())

    // AC4: HTTP
    @Test
    fun anHttpTrackerSuppliesThePeersAndHearsStartedThenCompletedAndStopped() {
        val http = FakeHttpTracker().own()
        val t = small(http.announceUrl)
        val swarm = rig.swarm(t, PeerScript())
        http.answer = { _, _ -> TrackerAnswer.Body(FakeHttpTracker.peersReply(swarm.addrs)) }
        engine().start(t.job(folder = rig.save))
        assertIs<EngineEvent.Finished>(rig.awaitTerminal())
        t.assertDownloaded(rig.save)
        rig.await("completed and stopped to reach the tracker") { http.events.contains("stopped") }
        assertEquals(listOf("started", "completed", "stopped"), http.events)
        assertEquals(100_000L, http.seen.first().left, "nothing downloaded at the start")
        assertEquals(0L, http.seen[1].left, "and nothing left at completed")
        assertContentEqualsHash(t, http.seen.first())
    }

    private fun assertContentEqualsHash(
        t: TestTorrent,
        s: AnnounceSeen,
    ) = kotlin.test.assertContentEquals(t.meta.infoHash, s.infoHash)

    // AC4: UDP
    @Test
    fun aUdpTrackerSuppliesThePeersAndHearsStartedThenCompletedAndStopped() {
        val udp = FakeUdpTracker().own()
        val t = small(udp.url)
        val swarm = rig.swarm(t, PeerScript())
        udp.peers = swarm.addrs
        engine().start(t.job(folder = rig.save))
        assertIs<EngineEvent.Finished>(rig.awaitTerminal())
        t.assertDownloaded(rig.save)
        rig.await("completed and stopped to reach the tracker") { udp.seen.size == 3 }
        assertEquals(listOf(2, 1, 3), udp.seen.map { it.event }, "started, completed, stopped")
        assertEquals(1, udp.connectCount, "one connection id for all three")
    }

    @Test
    fun dictionaryPeersFromAnHttpTrackerAreUsedToo() {
        val http = FakeHttpTracker().own()
        val t = small(http.announceUrl)
        val swarm = rig.swarm(t, PeerScript())
        http.answer = { _, _ -> TrackerAnswer.Body(FakeHttpTracker.dictionaryReply(swarm.addrs)) }
        engine().start(t.job(folder = rig.save))
        assertIs<EngineEvent.Finished>(rig.awaitTerminal())
    }

    @Test
    fun theTrackerIsAskedAgainAtItsIntervalWithNoEvent() {
        val http = FakeHttpTracker().own()
        val t = small(http.announceUrl)
        val swarm = rig.swarm(t, PeerScript(holdUnchoke = true))
        http.answer = { _, _ -> TrackerAnswer.Body(FakeHttpTracker.peersReply(swarm.addrs, interval = 100)) }
        engine().start(t.job(folder = rig.save))
        rig.await("the first announce") { http.seen.size == 1 }
        // its answer is in (the peer is connected to) before the clock moves: the interval counts from the end of the round
        rig.await("the peer") { swarm.peers.single().accepted == 1 }
        Thread.sleep(100)
        rig.clock.advance(99_000)
        Thread.sleep(150)
        assertEquals(1, http.seen.size, "not before the interval")
        rig.clock.advance(1_000)
        rig.await("the second announce") { http.seen.size == 2 }
        assertEquals(listOf("started", null), http.events)
        // the peer is still connected (a silence is dropped after 180 s, not yet): a peer already there is not connected twice
        assertEquals(1, swarm.peers.single().accepted)
    }

    @Test
    fun aDeadTrackerInATierIsSkippedAndTheOneThatAnsweredIsAskedFirstNextTime() {
        val x = FakeHttpTracker().own()
        val y = FakeHttpTracker().own()
        // the run shuffles the tier with a random of its own: find which of the two comes first, and make that one the dead one
        val first = listOf(x.announceUrl, y.announceUrl).shuffled(Random(Rig.PEER_ID.contentHashCode().toLong())).first()
        val (down, up) = if (first == x.announceUrl) x to y else y to x
        down.answer = { _, _ -> TrackerAnswer.Body("oops".encodeToByteArray(), status = 500) }
        val t = small(null, listOf(listOf(x.announceUrl, y.announceUrl)))
        val swarm = rig.swarm(t, PeerScript(holdUnchoke = true))
        up.answer = { _, _ -> TrackerAnswer.Body(FakeHttpTracker.peersReply(swarm.addrs, interval = 100)) }
        engine().start(t.job(folder = rig.save))
        rig.await("the first announce to the live tracker") { up.seen.size == 1 }
        assertEquals(1, down.seen.size, "the dead one was in front and was asked first")
        rig.await("the peer") { swarm.peers.single().accepted == 1 } // the round is over: the interval counts from here
        Thread.sleep(100)
        rig.clock.advance(100_000)
        rig.await("the second announce to the live tracker") { up.seen.size == 2 }
        Thread.sleep(100)
        assertEquals(1, down.seen.size, "the live one is first now: the dead one is not asked again")
    }

    // a UDP tracker that never answers takes 225 s to give up: the next tracker is asked long before "No peers" (2 minutes)
    @Test
    fun aTrackerThatNeverAnswersDoesNotMakeTheRowFailWithNoPeersBeforeTheNextOneIsAsked() {
        val t = small(null, listOf(listOf("http://silent.invalid/announce"), listOf("http://live.invalid/announce")))
        val swarm = rig.swarm(t, PeerScript())
        val client =
            ClockedTrackers(
                rig.clock,
                mapOf(
                    "http://silent.invalid/announce" to null,
                    "http://live.invalid/announce" to (100_000L to AnnounceResult(swarm.addrs, 1_800, null)),
                ),
            )
        engine(client = client).start(t.job(folder = rig.save))
        rig.await("the silent tracker to be asked") { client.calls.size == 1 }
        rig.clock.advance(100_000)
        assertIs<EngineEvent.Finished>(rig.awaitTerminal(timeoutMs = 10_000))
        // (the farewell of the finished torrent asks the live one again)
        assertEquals(listOf("http://silent.invalid/announce", "http://live.invalid/announce"), client.urls().take(2))
    }

    @Test
    fun aHttpAndAUdpTrackerInTheSameListBothWork() {
        val http = FakeHttpTracker { _, _ -> TrackerAnswer.Body(FakeHttpTracker.peersReply(emptyList())) }.own()
        val udp = FakeUdpTracker().own()
        val t = small(null, listOf(listOf(http.announceUrl), listOf(udp.url)))
        val swarm = rig.swarm(t, PeerScript())
        udp.peers = swarm.addrs
        engine().start(t.job(folder = rig.save))
        assertIs<EngineEvent.Finished>(rig.awaitTerminal())
        assertTrue(http.seen.isNotEmpty() && udp.seen.isNotEmpty(), "the empty first answer sent it on to the second")
    }

    // R5: a pause is not held up by a tracker, and the tracker still hears of it
    @Test
    fun aPauseIsNotHeldUpByATrackerThatNeverAnswersStoppedAndTheTrackerGetsIt() {
        val http = FakeHttpTracker().own()
        val t = small(http.announceUrl, size = 3_000_000)
        val swarm = rig.swarm(t, PeerScript(bytesPerSec = 200_000))
        http.answer = { _, s ->
            if (s.event == "stopped") TrackerAnswer.Hang else TrackerAnswer.Body(FakeHttpTracker.peersReply(swarm.addrs))
        }
        val engine = engine()
        engine.start(t.job(folder = rig.save))
        rig.await("some blocks") { swarm.peers.sumOf { it.answers } >= 20 }
        val at = System.currentTimeMillis()
        engine.stop("1")
        runBlocking { engine.join("1") }
        rig.awaitStopped()
        assertTrue(System.currentTimeMillis() - at < 1_000, "the pause took ${System.currentTimeMillis() - at} ms")
        rig.await("the stopped announce to arrive") { http.events.contains("stopped") }
    }

    /** Passes announces on, those with the event `stopped` half a second late: a slow network. Records the events in the order the tracker gets them. */
    private class SlowStopped(
        private val inner: TrackerClient,
    ) : TrackerClient {
        val arrived = java.util.concurrent.CopyOnWriteArrayList<AnnounceEvent?>()

        override suspend fun announce(
            url: String,
            req: AnnounceRequest,
        ): TrackerReply {
            if (req.event == AnnounceEvent.Stopped) kotlinx.coroutines.delay(500)
            arrived.add(req.event)
            return inner.announce(url, req)
        }
    }

    @Test
    fun aResumeDoesNotAnnounceStartedBeforeTheFarewellOfTheRunBeforeHasArrived() {
        val http = FakeHttpTracker().own()
        val t = small(http.announceUrl, size = 3_000_000)
        val swarm = rig.swarm(t, PeerScript(bytesPerSec = 200_000))
        http.answer = { _, _ -> TrackerAnswer.Body(FakeHttpTracker.peersReply(swarm.addrs)) }
        val slow = SlowStopped(trackers)
        val engine = engine(client = slow)
        engine.start(t.job(folder = rig.save))
        rig.await("some blocks") { swarm.peers.sumOf { it.answers } >= 20 }
        engine.stop("1")
        runBlocking { engine.join("1") }
        val stopped = rig.awaitStopped()
        engine.start(t.job(folder = rig.save, pieces = stopped.pieces))
        rig.await("the new started and the old stopped") { slow.arrived.size >= 3 }
        assertEquals(listOf(AnnounceEvent.Started, AnnounceEvent.Stopped, AnnounceEvent.Started), slow.arrived.take(3))
    }

    // R16
    @Test
    fun everyTrackerAnsweringWithAFailureReasonFailsTheRowWithTrackerError() {
        val a = FakeHttpTracker { _, _ -> TrackerAnswer.Body(FakeHttpTracker.failureReply("torrent not registered")) }.own()
        val b = FakeHttpTracker { _, _ -> TrackerAnswer.Body(FakeHttpTracker.failureReply("other reason")) }.own()
        val t = small(null, listOf(listOf(a.announceUrl), listOf(b.announceUrl)))
        engine().start(t.job(folder = rig.save))
        assertEquals(trackerError("torrent not registered"), failed().reason)
        assertTrue(a.seen.isNotEmpty() && b.seen.isNotEmpty(), "every tracker was asked first")
    }

    @Test
    fun aUdpTrackerErrorFailsTheRowToo() {
        val udp = FakeUdpTracker().own()
        udp.announceError = "info hash not allowed"
        val t = small(udp.url)
        engine().start(t.job(folder = rig.save))
        assertEquals("Tracker error: info hash not allowed", failed().reason)
    }

    @Test
    fun aFailureReasonWithPeersAlreadyConnectedDoesNotFailTheRow() {
        val http = FakeHttpTracker { _, _ -> TrackerAnswer.Body(FakeHttpTracker.failureReply("overloaded")) }.own()
        val t = small(http.announceUrl)
        val swarm = rig.swarm(t, PeerScript(holdUnchoke = true))
        engine().start(t.job(peers = swarm.addrs, folder = rig.save))
        rig.await("the announce") { http.seen.isNotEmpty() }
        rig.await("the peer") { swarm.peers.single().open == 1 }
        Thread.sleep(300)
        assertTrue(rig.eventsOf().none { it is EngineEvent.Failed }, "the peer is there: ${rig.eventsOf().takeLast(3)}")
    }

    @Test
    fun theFailureReasonFailsTheRowOnceTheLastPeerIsGone() {
        val http = FakeHttpTracker { _, _ -> TrackerAnswer.Body(FakeHttpTracker.failureReply("overloaded")) }.own()
        val t = small(http.announceUrl)
        val swarm = rig.swarm(t, PeerScript(holdUnchoke = true))
        engine().start(t.job(peers = swarm.addrs, folder = rig.save))
        rig.await("the peer") { swarm.peers.single().open == 1 }
        rig.await("the announce") { http.seen.isNotEmpty() }
        Thread.sleep(100)
        swarm.peers.single().close()
        assertEquals(trackerError("overloaded"), failed().reason)
    }

    @Test
    fun aRefusalThatWasFollowedByASilentRoundIsNotAReasonToFailWhenThePeerIsGone() {
        // round 1: "overloaded"; round 2: the tracker is down (an error status says nothing): the old reason is out of date
        val http =
            FakeHttpTracker { nth, _ ->
                if (nth == 0) {
                    TrackerAnswer.Body(FakeHttpTracker.failureReply("overloaded"))
                } else {
                    TrackerAnswer.Body("oops".encodeToByteArray(), status = 500)
                }
            }.own()
        val t = small(http.announceUrl)
        val swarm = rig.swarm(t, PeerScript(holdUnchoke = true))
        engine().start(t.job(peers = swarm.addrs, folder = rig.save))
        rig.await("the peer") { swarm.peers.single().open == 1 }
        rig.await("the refusal") { http.seen.size == 1 }
        Thread.sleep(200) // the round is over
        rig.clock.advance(60_000)
        rig.await("the second announce") { http.seen.size == 2 }
        Thread.sleep(200)
        swarm.peers.single().close()
        Thread.sleep(300)
        assertTrue(rig.eventsOf().none { it is EngineEvent.Failed }, "no tracker error: ${rig.eventsOf().takeLast(3)}")
        // the lost peer is tried again, in vain, and the timer for "no peers" starts afresh, so it takes two long waits
        repeat(2) {
            rig.clock.advance(121_000)
            Thread.sleep(300)
        }
        assertEquals(NO_PEERS, failed().reason)
    }

    @Test
    fun aTrackerThatCannotBeReachedIsNotATrackerError() {
        val gone = FakeHttpTracker().also { it.close() }.announceUrl
        val t = small(gone)
        engine().start(t.job(folder = rig.save))
        rig.await("Started") { rig.eventsOf().any { it is EngineEvent.Started } }
        rig.clock.advance(119_000)
        Thread.sleep(200)
        assertTrue(rig.eventsOf().none { it is EngineEvent.Failed })
        rig.clock.advance(2_000)
        assertEquals(NO_PEERS, failed().reason, "unreachable trackers count as no peers")
    }

    // AC15
    @Test
    fun trackersThatReturnNoPeersForTwoMinutesFailTheRowWithNoPeersAndAResumeAnnouncesAgain() {
        val http = FakeHttpTracker { _, _ -> TrackerAnswer.Body(FakeHttpTracker.peersReply(emptyList(), interval = 60)) }.own()
        val t = small(http.announceUrl)
        val engine = engine()
        engine.start(t.job(folder = rig.save))
        rig.await("the first announce") { http.seen.size == 1 }
        rig.clock.advance(119_000)
        Thread.sleep(200)
        assertTrue(rig.eventsOf().none { it is EngineEvent.Failed }, "two minutes have not passed")
        rig.clock.advance(2_000)
        val end = failed()
        assertEquals(NO_PEERS, end.reason)
        rig.await("the stopped announce") { http.events.contains("stopped") }
        val before = http.seen.size
        engine.start(t.job(folder = rig.save, pieces = end.pieces))
        rig.await("a new announce") { http.seen.size > before }
        assertEquals("started", http.events.last(), "a resume announces from the start")
    }

    @Test
    fun aTrackerThatReturnsPeersStartsTheTwoMinutesAgain() {
        val http = FakeHttpTracker().own()
        val dead = deadAddr()
        http.answer = { nth, _ ->
            TrackerAnswer.Body(FakeHttpTracker.peersReply(if (nth == 1) listOf(dead) else emptyList(), interval = 60))
        }
        val t = small(http.announceUrl)
        val counting = CountingTransport()
        engine(transport = counting).start(t.job(folder = rig.save))
        rig.await("the first announce") { http.seen.size == 1 }
        Thread.sleep(200) // the round is over: the interval counts from here
        rig.clock.advance(61_000)
        rig.await("the peer from the second answer to be tried") { counting.tried == 1 }
        Thread.sleep(100)
        rig.clock.advance(89_000) // 150 s in: 2 minutes after the start, not 2 minutes after the peer
        Thread.sleep(300)
        assertTrue(
            rig.eventsOf().none { it is EngineEvent.Failed },
            "the peer from the tracker restarted the timer: ${rig.eventsOf().takeLast(3)}",
        )
        rig.clock.advance(40_000)
        assertEquals(NO_PEERS, failed().reason)
    }

    @Test
    fun aTorrentThatIsAlreadyCompleteOnResumeNeverTalksToATracker() {
        val http = FakeHttpTracker().own()
        val t = small(http.announceUrl, size = 50_000)
        java.nio.file.Files
            .write(rig.save.resolve("f.bin.part"), t.data)
        val all = PieceSet.of(t.meta.pieceCount, 0 until t.meta.pieceCount)
        engine().start(t.job(folder = rig.save, pieces = all))
        assertIs<EngineEvent.Finished>(rig.awaitTerminal())
        Thread.sleep(200)
        assertTrue(http.seen.isEmpty(), "no announce at all")
    }

    @Test
    fun hostileTrackerAddressesAreSkippedAndTheGoodOnesUsed() {
        val http = FakeHttpTracker().own()
        val t = small(http.announceUrl)
        val swarm = rig.swarm(t, PeerScript())
        val answer =
            bdict(
                "interval" to bint(60),
                "peers" to
                    bstr(
                        byteArrayOf(
                            0,
                            0,
                            0,
                            0,
                            0x1A,
                            0xE1.toByte(), // 0.0.0.0
                            224.toByte(),
                            0,
                            0,
                            1,
                            0x1A,
                            0xE1.toByte(), // multicast
                            127,
                            0,
                            0,
                            1,
                            0,
                            0, // port 0
                        ) +
                            swarm.addrs.single().let { a ->
                                byteArrayOf(127, 0, 0, 1, (a.port ushr 8).toByte(), a.port.toByte())
                            },
                    ),
            )
        http.answer = { _, _ -> TrackerAnswer.Body(answer) }
        val counting = CountingTransport()
        engine(transport = counting).start(t.job(folder = rig.save))
        assertIs<EngineEvent.Finished>(rig.awaitTerminal())
        assertEquals(1, counting.tried, "only the usable address was connected to")
    }
}
