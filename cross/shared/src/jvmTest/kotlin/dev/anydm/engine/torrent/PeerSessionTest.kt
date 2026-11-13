package dev.anydm.engine.torrent

import dev.anydm.engine.http.FakeClock
import dev.anydm.engine.http.TokenBucket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** [PeerSession] against a fake peer on loopback: the handshake, the timers (on a fake clock) and what is never answered. */
class PeerSessionTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val clock = FakeClock()
    private val own = PEER_ID_PREFIX.encodeToByteArray() + ByteArray(12) { 5 }
    private val events = CopyOnWriteArrayList<PeerEvent>()
    private val toClose = CopyOnWriteArrayList<AutoCloseable>()

    private val data = contentOf(4 * 16 * 1024, 9)
    private val built = torrentOf(listOf("f.bin" to data.size), 32 * 1024, data, name = "f.bin", single = true)
    private val meta = assertIs<TorrentResult.Ok>(readTorrent(built.bytes)).meta

    @AfterTest
    fun tearDown() {
        scope.cancel()
        toClose.forEach { runCatching { it.close() } }
    }

    private fun peer(script: PeerScript = PeerScript()): FakePeer = FakePeer(built, meta, script).also { toClose.add(it) }

    private fun session(
        peer: FakePeer,
        transport: PeerTransport = TcpPeerTransport(),
    ) = PeerSession(
        addr = peer.addr,
        transport = transport,
        infoHash = meta.infoHash,
        ownId = own,
        layout = Layout(meta),
        clock = clock,
        bucket = TokenBucket(null),
        limits = SessionLimits(connectTimeoutMs = 2_000, handshakeMs = 30_000, tickMs = 10),
        sink = { events.add(it) },
    )

    private fun start(s: PeerSession): Job = scope.launch { s.run() }

    private fun await(
        what: String,
        timeoutMs: Long = 10_000,
        cond: () -> Boolean,
    ) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            check(System.currentTimeMillis() < end) { "timed out waiting for $what; events=$events" }
            Thread.sleep(5)
        }
    }

    private fun closed() = events.filterIsInstance<PeerEvent.Closed>()

    private fun ready() = events.filterIsInstance<PeerEvent.Ready>()

    private fun msgs() = events.filterIsInstance<PeerEvent.Msg>().map { it.msg }

    @Test
    fun aHandshakeWithTheRightInfoHashMakesThePeerReadyAndItsMessagesFollow() {
        val p = peer()
        val s = session(p)
        start(s)
        await("the bitfield") { msgs().isNotEmpty() }
        assertContentEquals(p.id, ready().single().remoteId)
        assertIs<PeerEvent.Ready>(events.first(), "ready comes before any message")
        assertEquals(PeerMsg.Bitfield(PieceSet.of(meta.pieceCount, 0 until meta.pieceCount).toBytes()), msgs().first())
        s.send(PeerMsg.Interested)
        await("the unchoke") { PeerMsg.Unchoke in msgs() }
        assertTrue(closed().isEmpty())
        assertEquals(listOf<PeerMsg>(PeerMsg.Interested), p.received.toList(), "what we sent arrived, nothing else")
    }

    @Test
    fun aPeerWithAnotherInfoHashIsDroppedAfterTheHandshake() {
        val p = peer(PeerScript(wrongInfoHash = true))
        start(session(p))
        await("the drop") { closed().isNotEmpty() }
        assertTrue(ready().isEmpty(), "never ready")
        await("the connection to close") { p.open == 0 }
    }

    @Test
    fun aHandshakeThatIsNotBitTorrentIsDropped() {
        val p = peer(PeerScript(garbageHandshake = true))
        start(session(p))
        await("the drop") { closed().isNotEmpty() }
        assertTrue(ready().isEmpty())
        await("the connection to close") { p.open == 0 }
    }

    @Test
    fun aPeerThatAnswersNoHandshakeInTimeIsDropped() {
        // a listener that accepts and says nothing
        val quiet = java.net.ServerSocket(0, 10, java.net.InetAddress.getLoopbackAddress())
        toClose.add(quiet)
        val accepted = CopyOnWriteArrayList<java.net.Socket>()
        kotlin.concurrent.thread(isDaemon = true) { while (true) accepted.add(quiet.accept()) }
        val s =
            PeerSession(
                PeerAddr("127.0.0.1", quiet.localPort),
                TcpPeerTransport(),
                meta.infoHash,
                own,
                Layout(meta),
                clock,
                TokenBucket(null),
                SessionLimits(2_000, 30_000, 10),
            ) { events.add(it) }
        start(s)
        await("the connection") { accepted.isNotEmpty() }
        clock.advance(29_000)
        Thread.sleep(100)
        assertTrue(closed().isEmpty(), "30 s have not passed")
        clock.advance(2_000)
        await("the drop") { closed().isNotEmpty() }
        await("the connection to close") {
            accepted.single().isClosed ||
                runCatching { accepted.single().getInputStream().read() }.getOrDefault(-1) < 0
        }
    }

    /** A transport whose connect takes [ms] of the fake clock: a slow connection. */
    private class SlowConnect(
        private val inner: PeerTransport,
        private val clock: FakeClock,
        private val ms: Long,
    ) : PeerTransport {
        override suspend fun connect(
            addr: PeerAddr,
            timeoutMs: Int,
        ): PeerConn = inner.connect(addr, timeoutMs).also { clock.advance(ms) }
    }

    @Test
    fun theHandshakeDeadlineCountsFromTheMomentTheConnectionIsMadeNotFromTheStartOfTheConnect() {
        val quiet = java.net.ServerSocket(0, 10, java.net.InetAddress.getLoopbackAddress())
        toClose.add(quiet)
        val accepted = CopyOnWriteArrayList<java.net.Socket>()
        kotlin.concurrent.thread(isDaemon = true) { while (true) accepted.add(quiet.accept()) }
        val s =
            PeerSession(
                PeerAddr("127.0.0.1", quiet.localPort),
                SlowConnect(TcpPeerTransport(), clock, 20_000),
                meta.infoHash,
                own,
                Layout(meta),
                clock,
                TokenBucket(null),
                SessionLimits(2_000, 30_000, 10),
            ) { events.add(it) }
        start(s)
        await("the connection") { accepted.isNotEmpty() && clock.nowMs() >= 20_000 }
        Thread.sleep(100) // the session notes the time its handshake starts right after the connect
        clock.advance(25_000) // 45 s since the connect began, 25 s since it was made
        Thread.sleep(150)
        assertTrue(closed().isEmpty(), "the handshake has had 25 s of its 30: ${closed()}")
        clock.advance(6_000)
        await("the drop") { closed().isNotEmpty() }
    }

    @Test
    fun aKeepAliveIsSentAfterTwoMinutesWithoutWritingAndNotBefore() {
        val p = peer(PeerScript(silent = true))
        start(session(p))
        await("ready") { ready().isNotEmpty() }
        clock.advance(119_000)
        Thread.sleep(150)
        assertEquals(0, p.keepAlives)
        clock.advance(2_000)
        await("the keep-alive") { p.keepAlives == 1 }
        Thread.sleep(150)
        assertEquals(1, p.keepAlives, "one per two minutes, not one per tick")
        assertTrue(closed().isEmpty())
    }

    @Test
    fun aPeerSilentForThreeMinutesIsDropped() {
        val p = peer(PeerScript(silent = true))
        start(session(p))
        await("ready") { ready().isNotEmpty() }
        clock.advance(179_000)
        Thread.sleep(150)
        assertTrue(closed().isEmpty(), "three minutes have not passed")
        clock.advance(2_000)
        await("the drop") { closed().isNotEmpty() }
        await("the connection to close") { p.open == 0 }
    }

    @Test
    fun aKeepAliveOfOursDoesNotCountAsHearingFromThePeer() {
        val p = peer(PeerScript(sendRequest = true))
        val s = session(p)
        start(s)
        await("ready") { ready().isNotEmpty() }
        clock.advance(170_000)
        // the peer sends nothing more; a keep-alive from us does not count as hearing from it
        Thread.sleep(100)
        assertTrue(closed().isEmpty())
        clock.advance(20_000)
        await("the drop") { closed().isNotEmpty() }
    }

    @Test
    fun aRequestFromThePeerIsNeverAnswered() {
        val p = peer(PeerScript(sendRequest = true))
        val s = session(p)
        start(s)
        await("ready") { ready().isNotEmpty() }
        s.send(PeerMsg.Interested)
        await("the unchoke that follows its request") { PeerMsg.Unchoke in msgs() }
        // The peer's request was read before that unchoke, so an answer would already be queued; the writes leave in order, so once
        // this marker has arrived anything the session had to say about the request has too.
        s.send(PeerMsg.NotInterested)
        await("the marker to arrive") { PeerMsg.NotInterested in p.received }
        assertEquals(listOf(PeerMsg.Interested, PeerMsg.NotInterested), p.received.toList(), "no piece, no reject: nothing came back")
        assertTrue(msgs().none { it is PeerMsg.Request }, "requests are not passed on")
    }

    @Test
    fun aSecondBitfieldDropsThePeer() {
        val p = peer(PeerScript(twoBitfields = true))
        start(session(p))
        await("the drop") { closed().isNotEmpty() }
        assertEquals(1, msgs().count { it is PeerMsg.Bitfield }, "the first one was passed on, the second was the violation")
        await("the connection to close") { p.open == 0 }
    }

    @Test
    fun aMessageLongerThanTheLimitDropsThePeer() {
        val p = peer(PeerScript(sendOversized = true))
        start(session(p))
        await("the drop") { closed().isNotEmpty() }
        await("the connection to close") { p.open == 0 }
    }

    @Test
    fun aBlockOfAPieceThatDoesNotExistDropsThePeerBeforeAnythingIsPassedOn() {
        val p = peer(PeerScript(sendOutOfRange = true))
        val s = session(p)
        start(s)
        await("the first message") { msgs().isNotEmpty() }
        s.send(PeerMsg.Interested)
        await("the drop") { closed().isNotEmpty() }
        assertTrue(msgs().none { it is PeerMsg.Piece })
        assertFalse(closed().single().retry, "a protocol violation is not worth a second try")
    }

    @Test
    fun cancellingTheSessionClosesTheConnectionAtOnce() {
        val p = peer()
        val job = start(session(p))
        await("ready") { ready().isNotEmpty() }
        assertEquals(1, p.open)
        job.cancel()
        await("the connection to close", timeoutMs = 1_000) { p.open == 0 }
        assertFalse(closed().any(), "a cancelled session reports nothing: the one who cancelled knows")
    }

    @Test
    fun aPeerThatClosesIsReportedClosed() {
        val p = peer()
        start(session(p))
        await("ready") { ready().isNotEmpty() }
        p.dropAll()
        await("closed") { closed().isNotEmpty() }
        assertTrue(closed().single().retry, "a connection lost is worth a second try")
    }

    @Test
    fun aPeerThatCannotBeReachedIsReportedClosedWithoutBeingReady() {
        val dead = java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())
        val port = dead.localPort
        dead.close()
        val s =
            PeerSession(
                PeerAddr("127.0.0.1", port),
                TcpPeerTransport(),
                meta.infoHash,
                own,
                Layout(meta),
                clock,
                TokenBucket(null),
                SessionLimits(2_000, 30_000, 10),
            ) { events.add(it) }
        start(s)
        await("closed") { closed().isNotEmpty() }
        assertTrue(ready().isEmpty())
    }
}
