package dev.anydm.engine.torrent

import dev.anydm.engine.http.Clock
import dev.anydm.engine.http.ConnectionPermits
import dev.anydm.engine.http.EngineEvent
import dev.anydm.engine.http.Fail
import dev.anydm.engine.http.RateShares
import dev.anydm.engine.http.TokenBucket
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The metadata fetch of a magnet job against fake peers (and fake trackers): BEP 9 over BEP 10, with peers that lie, drop and refuse. */
class MetadataFetchTest {
    private val rig = Rig()
    private val own = mutableListOf<AutoCloseable>()

    @AfterTest
    fun tearDown() {
        rig.close()
        own.forEach { runCatching { it.close() } }
    }

    private fun <T : AutoCloseable> T.keep(): T = also { own.add(it) }

    /** Metadata of [blocks] blocks and [extra] bytes more; its content is no torrent, the fetch only checks its hash. */
    private fun blob(
        blocks: Int,
        extra: Int = 0,
    ) = contentOf(blocks * METADATA_BLOCK + extra, seed = blocks + 40)

    private fun served(info: ByteArray) =
        Served(
            BuiltTorrent(ByteArray(0), info, ByteArray(0), 16 * KB),
            TorrentMeta(
                "x",
                listOf(TorrentFile("x", 1)),
                infoHash = sha1(info),
                pieceLength = 16L * KB,
                pieceHashes = listOf(ByteArray(20)),
            ),
        )

    private fun swarm(
        info: ByteArray,
        vararg scripts: PeerScript,
    ) = FakeSwarm(listOf(served(info)), scripts.toList()).keep()

    private fun honest(info: ByteArray) = PeerScript(serveMetadata = info)

    /** The sizes the fetch built an assembler for. */
    private val built = CopyOnWriteArrayList<Long>()

    private fun fetch(
        info: ByteArray,
        peers: List<PeerAddr>,
        trackers: List<String> = emptyList(),
        client: TrackerClient = NoTrackers,
        // handshakeMs: a test that moves the clock (see failed) must not outrun the connects, which take real time
        limits: TorrentLimits = TorrentLimits(reportMs = 50, noPeersMs = 5_000, minAnnounceMs = 1_000, handshakeMs = 3_600_000),
        bucket: TokenBucket = TokenBucket(null),
        permits: ConnectionPermits = ConnectionPermits(200),
        clock: Clock = rig.clock,
        emit: suspend (EngineEvent) -> Unit = {},
    ): MetadataFetch {
        val env = TorrentEnv(TcpPeerTransport(), clock, emit, bucket, permits, kotlin.random.Random(3), limits, client, rig.scope)
        val link = MagnetLink(sha1(info), "x", trackers)
        return MetadataFetch(env, "1", link, Rig.PEER_ID, peers) { size, hash -> MetadataAssembler(size, hash).also { built.add(size) } }
    }

    private fun MetadataFetch.fetched(onPeers: suspend (Int) -> Unit = {}): ByteArray = runBlocking { withTimeout(20_000) { run(onPeers) } }

    /** Starts the fetch and moves the clock along until it ends: for the runs that end with the fetch's timers. */
    private fun MetadataFetch.failed(after: () -> Boolean = { true }): Fail {
        val result = rig.scope.async { runCatching { run() } }
        val end = System.currentTimeMillis() + 20_000
        while (!result.isCompleted) {
            check(System.currentTimeMillis() < end) { "the fetch did not end" }
            if (after()) rig.clock.advance(1_000)
            Thread.sleep(10)
        }
        val failure = runBlocking { result.await() }.exceptionOrNull()
        return failure as? Fail ?: throw AssertionError("expected a Fail, got $failure")
    }

    private fun deadAddr(): PeerAddr {
        val s = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val port = s.localPort
        s.close()
        return PeerAddr("127.0.0.1", port)
    }

    // AC6 (the fetch part)
    @Test
    fun anHonestPeerGivesTheMetadataAndEveryBlockIsAskedOnce() {
        val info = blob(5, extra = 100)
        val s = swarm(info, honest(info))
        assertContentEquals(info, fetch(info, s.addrs).fetched())
        assertEquals(
            (0..5).toList(),
            s.peers
                .single()
                .metadataRequests
                .sorted(),
        )
    }

    @Test
    fun aSingleBlockOfMetadataIsFetched() {
        val info = blob(0, extra = 700)
        val s = swarm(info, honest(info))
        assertContentEquals(info, fetch(info, s.addrs).fetched())
        assertEquals(listOf(0), s.peers.single().metadataRequests)
    }

    @Test
    fun atMostFourBlocksAreAskedOfAPeerAtOnce() {
        val info = blob(10)
        val s = swarm(info, PeerScript(serveMetadata = info, metadataNeverAnswer = true))
        val job = rig.scope.async { runCatching { fetch(info, s.addrs).run() } }
        rig.await("four requests") {
            s.peers
                .single()
                .metadataRequests.size >= 4
        }
        Thread.sleep(300)
        assertEquals(listOf(0, 1, 2, 3), s.peers.single().metadataRequests)
        job.cancel()
    }

    @Test
    fun theBlocksAreSplitBetweenPeersAndNoneIsAskedTwice() {
        val info = blob(12)
        val s = swarm(info, honest(info), honest(info), honest(info))
        assertContentEquals(info, fetch(info, s.addrs).fetched())
        assertEquals((0 until 12).toList(), s.peers.flatMap { it.metadataRequests }.sorted())
    }

    @Test
    fun peersComeFromTheTrackersOfTheMagnetAndTheyHearStartedWithSomethingLeft() {
        val http = FakeHttpTracker().keep()
        val info = blob(3)
        val s = swarm(info, honest(info))
        http.answer = { _, _ -> TrackerAnswer.Body(FakeHttpTracker.peersReply(s.addrs)) }
        val client = SchemeTrackerClient(HttpTrackerClient(timeoutMs = 3_000), UdpTrackerClient(rig.clock, tickMs = 5))
        assertContentEquals(info, fetch(info, emptyList(), listOf(http.announceUrl), client).fetched())
        val first = http.seen.first()
        assertEquals("started", first.event)
        assertContentEquals(sha1(info), first.infoHash)
        assertTrue((first.left ?: 0) > 0, "a client with no metadata is no seed: left ${first.left}")
    }

    // AC13 (NFR2): one honest peer, 1 MB, and the clock is never moved: no timer was needed
    @Test
    fun aMegabyteOfMetadataIsVerifiedWithinFiveSecondsOfTheFirstConnection() {
        val info = contentOf(MB, seed = 9)
        assertEquals(64, info.size / METADATA_BLOCK)
        val s = swarm(info, honest(info))
        val start = System.nanoTime()
        val before = rig.clock.nowMs()
        assertContentEquals(info, fetch(info, s.addrs).fetched())
        assertEquals(before, rig.clock.nowMs())
        assertTrue((System.nanoTime() - start) / 1_000_000 < 5_000, "took ${(System.nanoTime() - start) / 1_000_000} ms")
        assertEquals(64, s.peers.single().metadataAnswers)
    }

    // AC7 and NFR1
    @Test
    fun aPeerThatLiesAndOneThatDropsAreLeftOutAndTheHonestPeersMetadataIsUsed() {
        val info = blob(16)
        val liar = PeerScript(serveMetadata = info, metadataLie = true)
        val dropper = PeerScript(serveMetadata = info, metadataDropAfter = 3)
        val slow = PeerScript(serveMetadata = info, answerDelayMs = 20)
        val s = swarm(info, liar, dropper, slow)
        assertContentEquals(info, fetch(info, s.addrs).fetched())
        rig.await("the liar to be dropped") { s.peers[0].open == 0 }
    }

    @Test
    fun aPeerThatDropsHalfwayLeavesItsBlocksToTheOthers() {
        val info = blob(10)
        val s = swarm(info, PeerScript(serveMetadata = info, metadataDropAfter = 5), PeerScript(serveMetadata = info, answerDelayMs = 20))
        assertContentEquals(info, fetch(info, s.addrs).fetched())
        assertTrue(s.peers[0].metadataAnswers <= 5, "it dropped after five")
    }

    @Test
    fun aHonestPeerThatContributedBeforeAFailedCheckIsNotBlamedForIt() {
        val info = blob(8)
        // the liar answers at once, the honest peers one block at a time: both are in the assembly when it fails
        val s = swarm(info, PeerScript(serveMetadata = info, metadataLie = true), PeerScript(serveMetadata = info, answerDelayMs = 10))
        assertContentEquals(info, fetch(info, s.addrs).fetched())
        assertTrue(s.peers[1].metadataAnswers >= 8, "the honest peer served the whole of it in the end: ${s.peers[1].metadataAnswers}")
    }

    @Test
    fun aPeerThatRejectsARequestIsDropped() {
        val info = blob(6)
        val s = swarm(info, PeerScript(serveMetadata = info, metadataReject = true), PeerScript(serveMetadata = info, answerDelayMs = 10))
        assertContentEquals(info, fetch(info, s.addrs).fetched())
        rig.await("the rejecting peer to be dropped") { s.peers[0].open == 0 }
    }

    @Test
    fun aPeerThatSendsABlockOfTheWrongLengthIsDropped() {
        val info = blob(6)
        val s = swarm(info, PeerScript(serveMetadata = info, metadataBlockBytes = 5), PeerScript(serveMetadata = info, answerDelayMs = 10))
        assertContentEquals(info, fetch(info, s.addrs).fetched())
        rig.await("the peer to be dropped") { s.peers[0].open == 0 }
    }

    @Test
    fun aPeerWithNoExtensionSupportOrNoMetadataIsDropped() {
        val info = blob(3)
        val s =
            swarm(
                info,
                PeerScript(), // a plain seed: no extension bit
                PeerScript(serveMetadata = info, noUtMetadata = true),
                honest(info),
            )
        assertContentEquals(info, fetch(info, s.addrs).fetched())
        rig.await("the two to be dropped") { s.peers[0].open == 0 && s.peers[1].open == 0 }
        assertTrue(s.peers[0].metadataRequests.isEmpty() && s.peers[1].metadataRequests.isEmpty())
    }

    // AC8 (NFR3)
    @Test
    fun aPeerClaimingAGigabyteIsDroppedAndNothingThatBigIsBuilt() {
        val info = blob(4)
        val s =
            swarm(
                info,
                PeerScript(serveMetadata = info, metadataClaimSize = 1L shl 30),
                PeerScript(serveMetadata = info, answerDelayMs = 10),
            )
        assertContentEquals(info, fetch(info, s.addrs).fetched())
        rig.await("the peer to be dropped") { s.peers[0].open == 0 }
        assertTrue(built.isNotEmpty() && built.all { it == info.size.toLong() }, "assemblers built for $built")
        assertEquals(0, s.peers[0].metadataRequests.size, "it was never asked for anything")
    }

    @Test
    fun claimsOfNothingOrOverTheCapAreRefusedWithoutBuildingAnything() {
        val info = blob(2)
        val claims = listOf(0L, -1L, MAX_METADATA_BYTES + 1L, 1L shl 30, Long.MAX_VALUE)
        val s = swarm(info, *claims.map { PeerScript(serveMetadata = info, metadataClaimSize = it) }.toTypedArray())
        val failure = fetch(info, s.addrs).failed()
        assertEquals(METADATA_INVALID, failure.reason)
        assertEquals(emptyList(), built.toList(), "no buffer was made for any of them")
        assertTrue(s.peers.all { it.metadataRequests.isEmpty() })
    }

    @Test
    fun aClaimOfExactlyTheCapIsAcceptedAsASize() {
        val info = blob(2)
        val s = swarm(info, PeerScript(serveMetadata = info, metadataClaimSize = MAX_METADATA_BYTES.toLong(), metadataNeverAnswer = true))
        val job = rig.scope.async { runCatching { fetch(info, s.addrs).run() } }
        rig.await("a request") {
            s.peers
                .single()
                .metadataRequests
                .isNotEmpty()
        }
        assertEquals(listOf(MAX_METADATA_BYTES.toLong()), built.toList())
        job.cancel()
    }

    @Test
    fun aPeerThatClaimsAnotherSizeThanTheOnesBeforeIsSetAsideNotTrustedWithTheTruth() {
        val info = blob(4)
        // the first one to answer sets the size; here a liar about the size comes first and an honest peer second
        val s =
            swarm(
                info,
                PeerScript(serveMetadata = info, metadataClaimSize = info.size + 1L),
                PeerScript(serveMetadata = info, answerDelayMs = 10),
            )
        assertContentEquals(info, fetch(info, s.addrs).fetched())
        rig.await("the liar to be dropped") { s.peers[0].open == 0 }
    }

    // AC11 (the engine's part)
    @Test
    fun peersThatAllSendBadMetadataEndWithMetadataInvalid() {
        val info = blob(4)
        val s = swarm(info, PeerScript(serveMetadata = info, metadataLie = true), PeerScript(serveMetadata = info, metadataLie = true))
        assertEquals(METADATA_INVALID, fetch(info, s.addrs).failed().reason)
        assertTrue(s.peers.all { it.open == 0 })
    }

    @Test
    fun noPeerAtAllEndsWithNoPeers() {
        val info = blob(2)
        val client = SchemeTrackerClient(HttpTrackerClient(timeoutMs = 1_000), UdpTrackerClient(rig.clock, tickMs = 5))
        val http = FakeHttpTracker().keep()
        assertEquals(NO_PEERS, fetch(info, emptyList(), listOf(http.announceUrl), client).failed { http.seen.isNotEmpty() }.reason)
    }

    @Test
    fun peersThatCannotBeReachedEndWithNoPeers() {
        val info = blob(2)
        assertEquals(NO_PEERS, fetch(info, listOf(deadAddr(), deadAddr())).failed().reason)
    }

    @Test
    fun trackersThatAllRefuseEndWithTheTrackerError() {
        val info = blob(2)
        val http = FakeHttpTracker { _, _ -> TrackerAnswer.Body(FakeHttpTracker.failureReply("nope")) }.keep()
        val client = SchemeTrackerClient(HttpTrackerClient(timeoutMs = 3_000), UdpTrackerClient(rig.clock, tickMs = 5))
        assertEquals(trackerError("nope"), fetch(info, emptyList(), listOf(http.announceUrl), client).failed().reason)
    }

    // AC9 (the engine's part)
    @Test
    fun cancellingTheFetchClosesEveryConnectionAndSendsNothingMore() {
        val info = blob(10)
        val s =
            swarm(
                info,
                PeerScript(serveMetadata = info, metadataNeverAnswer = true),
                PeerScript(serveMetadata = info, metadataNeverAnswer = true),
            )
        val job = rig.scope.async { runCatching { fetch(info, s.addrs).run() } }
        rig.await("requests to both") { s.peers.all { it.metadataRequests.isNotEmpty() } }
        job.cancel()
        rig.await("the connections to close") { s.peers.all { it.open == 0 } }
        val seen = s.peers.map { it.received.size }
        Thread.sleep(300)
        assertEquals(seen, s.peers.map { it.received.size }, "nothing after the cancel")
    }

    // AC14 (NFR4)
    @Test
    fun metadataBytesGoThroughTheSharedLimit() {
        val info = blob(9) // 147 KB
        val s = swarm(info, honest(info))
        val shares = RateShares()
        shares.register("1")
        shares.setRate(100_000)
        val start = System.nanoTime()
        val real = Clock { System.nanoTime() / 1_000_000 }
        assertContentEquals(info, fetch(info, s.addrs, bucket = shares.bucket("1"), clock = real).fetched())
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue(ms in 1_100..10_000, "147 KB at 100 KB/s took $ms ms")
    }

    @Test
    fun theConnectionsAreTheJobsPermitsAndComeBack() {
        val info = blob(3)
        val s = swarm(info, honest(info), honest(info), honest(info))
        val permits = ConnectionPermits(2)
        val counting = CountingTransport()
        val env =
            TorrentEnv(counting, rig.clock, {
            }, TokenBucket(null), permits, kotlin.random.Random(3), TorrentLimits(reportMs = 50), NoTrackers, rig.scope)
        val link = MagnetLink(sha1(info), null, emptyList())
        assertContentEquals(info, runBlocking { withTimeout(20_000) { MetadataFetch(env, "1", link, Rig.PEER_ID, s.addrs).run() } })
        assertTrue(counting.peak <= 2, "peak ${counting.peak} with two permits")
        assertTrue(permits.tryAcquireExtra() && permits.tryAcquireExtra(), "both permits are back")
        assertFalse(permits.tryAcquireExtra(), "and no more than the two there were")
    }

    @Test
    fun noMoreThanPerTorrentPeersAreConnectedAtOnce() {
        val info = blob(10)
        val s = swarm(info, *Array(5) { PeerScript(serveMetadata = info, metadataNeverAnswer = true) })
        val counting = CountingTransport()
        val limits = TorrentLimits(reportMs = 50, perTorrent = 2)
        val env =
            TorrentEnv(
                counting,
                rig.clock,
                {},
                TokenBucket(null),
                ConnectionPermits(200),
                kotlin.random.Random(3),
                limits,
                NoTrackers,
                rig.scope,
            )
        val job =
            rig.scope.async {
                runCatching {
                    MetadataFetch(
                        env,
                        "1",
                        MagnetLink(sha1(info), null, emptyList()),
                        Rig.PEER_ID,
                        s.addrs,
                    ).run()
                }
            }
        rig.await("two peers") { counting.open == 2 }
        Thread.sleep(300)
        assertEquals(2, counting.peak)
        job.cancel()
    }

    @Test
    fun theCallerHearsHowManyPeersAreConnected() {
        val info = blob(10)
        val s =
            swarm(
                info,
                PeerScript(serveMetadata = info, metadataNeverAnswer = true),
                PeerScript(serveMetadata = info, metadataNeverAnswer = true),
            )
        val heard = CopyOnWriteArrayList<Int>()
        val job = rig.scope.async { runCatching { fetch(info, s.addrs).run { heard.add(it) } } }
        // reports are paced by the clock
        rig.await("two peers heard") {
            rig.clock.advance(100)
            heard.contains(2)
        }
        job.cancel()
    }

    @Test
    fun theFetchEndsWhenCancelledEvenWhileConnecting() {
        val info = blob(2)
        val quiet = ServerSocket(0, 50, InetAddress.getLoopbackAddress()).keep()
        val job = rig.scope.async { runCatching { fetch(info, listOf(PeerAddr("127.0.0.1", quiet.localPort))).run() } }
        Thread.sleep(200)
        job.cancel()
        runBlocking { withTimeout(5_000) { job.join() } }
    }

    private fun ServerSocket.keepSocket(): ServerSocket = also { own.add(AutoCloseable { it.close() }) }
}
