package dev.anydm.engine.torrent

import dev.anydm.engine.http.ConnectionPermits
import dev.anydm.engine.http.Fail
import dev.anydm.engine.http.TokenBucket
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The metadata fetch against scripted in-memory peers (see [ScriptedTransport]): the order of what peers say and the fake clock are
 * the test's, so the timing rules (stalls, drips, timeouts, retries) are tested without waiting and without races.
 */
class MetadataFetchScriptedTest {
    private val rig = Rig()

    @AfterTest
    fun tearDown() = rig.close()

    private fun addr(i: Int) = PeerAddr("10.0.0.$i", 6881)

    private fun blob(
        blocks: Int,
        extra: Int = 0,
    ) = contentOf(blocks * METADATA_BLOCK + extra, seed = blocks + 40)

    private val limits =
        TorrentLimits(reportMs = 50, noPeersMs = 100_000_000, minAnnounceMs = 1_000, handshakeMs = 100_000_000, farewellMs = 50)

    /** Offers what [offer] says at every announce. */
    private class ScriptedTracker(
        @Volatile var offer: List<PeerAddr>,
    ) : TrackerClient {
        val calls = AtomicInteger()

        override suspend fun announce(
            url: String,
            req: AnnounceRequest,
        ): TrackerReply {
            calls.incrementAndGet()
            return AnnounceResult(offer, 1)
        }
    }

    private val built = CopyOnWriteArrayList<Long>()

    private fun start(
        info: ByteArray,
        transport: ScriptedTransport,
        peers: List<PeerAddr>,
        tracker: ScriptedTracker? = null,
        limits: TorrentLimits = this.limits,
        maxHeld: Long = MAX_HELD_METADATA_BYTES,
    ): Deferred<Result<ByteArray>> {
        val env =
            TorrentEnv(
                transport,
                rig.clock,
                {},
                TokenBucket(null),
                ConnectionPermits(200),
                Random(3),
                limits,
                tracker ?: NoTrackers,
                rig.scope,
            )
        val link = MagnetLink(sha1(info), "x", if (tracker != null) listOf("http://tracker.example/announce") else emptyList())
        val fetch =
            MetadataFetch(
                env,
                "1",
                link,
                Rig.PEER_ID,
                peers,
                maxHeld,
            ) { size, hash -> MetadataAssembler(size, hash).also { built.add(size) } }
        return rig.scope.async { runCatching { fetch.run() } }
    }

    private fun Deferred<Result<ByteArray>>.bytes(): ByteArray = runBlocking { withTimeout(20_000) { await() } }.getOrThrow()

    private fun Deferred<Result<ByteArray>>.failure(): Fail = runBlocking { withTimeout(20_000) { await() } }.exceptionOrNull() as Fail

    /** Moves the clock on a second at a time (a moment of real time after each, for the fetch to look at it) until [cond]. */
    private fun passUntil(
        maxMs: Long,
        each: (passedMs: Long) -> Unit = {},
        cond: () -> Boolean,
    ): Long {
        val from = rig.clock.nowMs()
        while (!cond()) {
            val passed = rig.clock.nowMs() - from
            check(passed < maxMs) { "not after ${passed / 1000} s of fake time" }
            rig.clock.advance(1_000)
            each(passed + 1_000)
            Thread.sleep(25)
        }
        return rig.clock.nowMs() - from
    }

    private fun pass(ms: Long) {
        val to = rig.clock.nowMs() + ms
        passUntil(ms + 1_000) { rig.clock.nowMs() >= to }
    }

    private fun ScriptedTransport.honest(
        i: Int,
        info: ByteArray,
        greetAtOnce: Boolean = true,
    ): ScriptedPeer =
        peer(addr(i)).also {
            it.claim = info.size.toLong()
            it.greetAtOnce = greetAtOnce
        }

    private fun ScriptedPeer.awaitRequests(n: Int) = rig.await("$n requests") { requests.size >= n }

    private fun ScriptedPeer.awaitConnected() = rig.await("a connection") { connects >= 1 }

    private fun corrupt(b: ByteArray) = b.copyOf().also { it[0] = (it[0] + 1).toByte() }

    // ---- sizes: no one peer's claim decides for the others

    @Test
    fun aSizeLiarThatDripsJunkDoesNotKeepAnHonestPeerIdle() {
        val info = blob(4)
        val liarSize = 40L * METADATA_BLOCK
        val t = ScriptedTransport(sha1(info))
        val liar = t.peer(addr(1)).also { it.claim = liarSize }
        val honest = t.honest(2, info, greetAtOnce = false)
        val result = start(info, t, listOf(addr(1), addr(2)))
        liar.awaitRequests(4)
        honest.awaitConnected()
        honest.greet()
        var dripped = 0
        val elapsed =
            passUntil(
                20_000,
                each = { passed ->
                    // one junk block of the right length every four seconds: never silent for long
                    if (passed % 4_000 == 0L && dripped < liar.requests.size) liar.sendJunk(liar.requests[dripped++], liarSize)
                },
            ) { honest.requests.size == 4 }
        assertTrue(elapsed <= 5_000, "the honest peer was asked after $elapsed ms of fake time")
        honest.answerAsked(info)
        assertContentEquals(info, result.bytes())
        assertTrue(built.contains(liarSize) && built.contains(info.size.toLong()), "one assembler for each size: $built")
    }

    @Test
    fun aSizeLiarAlongsideAnHonestPeerIsBadAndTheHonestPeersMetadataIsUsed() {
        val info = blob(4)
        val t = ScriptedTransport(sha1(info))
        val liar = t.peer(addr(1)).also { it.claim = info.size + 100L } // five blocks, the last of 100 bytes
        val honest = t.honest(2, info, greetAtOnce = false)
        val result = start(info, t, listOf(addr(1), addr(2)))
        liar.awaitRequests(4)
        honest.awaitConnected()
        honest.greet()
        // the liar gives the four blocks it has and junk for the fifth: its data fails the hash and it is bad
        liar.answerAsked(info, info.size + 100L)
        liar.awaitRequests(5)
        liar.sendJunk(4, info.size + 100L, length = 100)
        rig.await("the liar to be dropped") { liar.closes == 1 }
        honest.awaitRequests(4)
        honest.answerAsked(info)
        assertContentEquals(info, result.bytes())
        assertEquals(1, liar.connects, "a bad peer is not connected to again")
    }

    @Test
    fun aPeerWhoseBlockSaysAnotherTotalThanItsOfferIsBad() {
        val info = blob(4)
        val t = ScriptedTransport(sha1(info))
        val liar = t.honest(1, info)
        val honest = t.honest(2, info, greetAtOnce = false)
        val result = start(info, t, listOf(addr(1), addr(2)))
        liar.awaitRequests(4)
        liar.sendBlock(info, liar.requests.first(), total = info.size + 1L)
        rig.await("the peer to be dropped") { liar.closes == 1 }
        honest.awaitConnected()
        honest.greet()
        honest.awaitRequests(4)
        honest.answerAsked(info)
        assertContentEquals(info, result.bytes())
        assertEquals(1, liar.connects)
    }

    @Test
    fun aBlockNobodyAskedForMakesThePeerBad() {
        val info = blob(4)
        val t = ScriptedTransport(sha1(info))
        val peer = t.honest(1, info)
        val honest = t.honest(2, info, greetAtOnce = false)
        val result = start(info, t, listOf(addr(1), addr(2)))
        peer.awaitRequests(4)
        peer.sendJunk(9, info.size.toLong()) // there is no block 9, and none was asked
        rig.await("the peer to be dropped") { peer.closes == 1 }
        honest.awaitConnected()
        honest.greet()
        honest.awaitRequests(4)
        honest.answerAsked(info)
        assertContentEquals(info, result.bytes())
        assertEquals(1, peer.connects)
    }

    // ---- a failed check with several contributors

    private data class Isolation(
        val info: ByteArray,
        val a: ScriptedPeer,
        val b: ScriptedPeer,
        val result: Deferred<Result<ByteArray>>,
    )

    /** Two peers each give half of 8 blocks, the first one's wrong: returns once the data failed its check (the first is then asked alone). */
    private fun twoContributorsFailTheCheck(): Isolation {
        val info = blob(8)
        val t = ScriptedTransport(sha1(info))
        val a = t.honest(1, info)
        val b = t.honest(2, info, greetAtOnce = false)
        val result = start(info, t, listOf(addr(1), addr(2)))
        a.awaitRequests(4)
        b.awaitConnected()
        b.greet()
        b.awaitRequests(4)
        assertEquals(listOf(0, 1, 2, 3), a.requests)
        assertEquals(listOf(4, 5, 6, 7), b.requests)
        a.answerAsked(info, mutate = ::corrupt)
        b.answerAsked(info)
        return Isolation(info, a, b, result)
    }

    @Test
    fun afterAFailedCheckWithTwoContributorsOnePeerAtATimeGivesAllOfItAndTheHonestOneIsNotBlamed() {
        val (info, a, b, result) = twoContributorsFailTheCheck()
        // the first (lowest numbered) peer alone is asked for everything, and the other is left alone
        a.awaitRequests(8)
        Thread.sleep(100)
        assertEquals(4, b.requests.size, "the other peer is not asked while one is alone")
        a.answerAsked(info, mutate = ::corrupt)
        a.awaitRequests(12)
        a.answerAsked(info, mutate = ::corrupt)
        // its whole data failed the hash: it is bad; the other peer is next, alone, and its data holds
        rig.await("the bad peer to be dropped") { a.closes == 1 }
        b.awaitRequests(8)
        b.answerAsked(info)
        b.awaitRequests(12)
        b.answerAsked(info)
        assertContentEquals(info, result.bytes())
        assertEquals(listOf(4, 5, 6, 7, 0, 1, 2, 3, 4, 5, 6, 7), b.requests)
        assertEquals(1, a.connects, "the peer that failed alone is not connected to again")
        assertEquals(1, b.connects)
    }

    @Test
    fun aPeerThatDripsWhileAloneInTheCheckLosesItsTurnInBoundedTime() {
        val (info, a, b, result) = twoContributorsFailTheCheck()
        a.awaitRequests(8)
        var dripped = 4
        // one block every twelve seconds: never silent for the request timeout, and far too slow for eight blocks
        val elapsed =
            passUntil(
                150_000,
                each = { passed ->
                    if (passed % 12_000 == 0L && dripped < a.requests.size) a.sendBlock(info, a.requests[dripped++], mutate = ::corrupt)
                },
            ) { b.requests.size == 8 }
        assertTrue(elapsed <= 70_000, "the other peer's turn came after $elapsed ms of fake time")
        b.answerAsked(info)
        b.awaitRequests(12)
        b.answerAsked(info)
        assertContentEquals(info, result.bytes())
        assertEquals(1, a.connects)
    }

    // ---- blocks kept, blocks asked twice

    @Test
    fun theBlocksOfAPeerThatLeftAreKeptForTheNextPeerOfThatSize() {
        val info = blob(4)
        val t = ScriptedTransport(sha1(info))
        val a = t.honest(1, info)
        val b = t.honest(2, info)
        val tracker = ScriptedTracker(listOf(addr(1)))
        val result = start(info, t, emptyList(), tracker)
        a.awaitRequests(4)
        a.sendBlock(info, 0)
        a.sendBlock(info, 1)
        tracker.offer = listOf(addr(2)) // the next announce brings another peer, and no more of this one
        a.hangUp()
        rig.await("the peer to leave") { a.closes == 1 }
        pass(2_000)
        b.awaitRequests(2)
        assertEquals(listOf(2, 3), b.requests, "the two blocks it gave are not asked again")
        b.answerAsked(info)
        assertContentEquals(info, result.bytes())
    }

    @Test
    fun anIdlePeerAsksForTheBlocksAnotherHoldsTooLongAndTheMetadataIsNotHeldUpByASilentPeer() {
        val info = blob(3)
        val t = ScriptedTransport(sha1(info))
        val silent = t.honest(1, info)
        val honest = t.honest(2, info, greetAtOnce = false)
        val result = start(info, t, listOf(addr(1), addr(2)))
        silent.awaitRequests(3) // all the blocks there are
        honest.awaitConnected()
        honest.greet()
        Thread.sleep(100)
        assertTrue(honest.requests.isEmpty(), "at first there is nothing for it to ask")
        val elapsed = passUntil(20_000) { honest.requests.size == 3 }
        assertTrue(elapsed in 1_000..10_000, "the blocks were asked of the idle peer after $elapsed ms")
        honest.answerAsked(info)
        assertContentEquals(info, result.bytes())
    }

    @Test
    fun aPeerThatNeverAnswersIsLetGoAfterTheRequestTimeoutAndItsBlocksAreAskedOfAnother() {
        val info = blob(8)
        val t = ScriptedTransport(sha1(info))
        val stuck = t.honest(1, info)
        val honest = t.honest(2, info, greetAtOnce = false)
        val result = start(info, t, listOf(addr(1), addr(2)))
        stuck.awaitRequests(4)
        honest.awaitConnected()
        honest.greet()
        honest.awaitRequests(4)
        honest.answerAsked(info) // blocks 4 to 7: now it has nothing to do
        // it is given the stuck peer's blocks too once they have been held for a while: that shows its four answers were taken in
        passUntil(10_000) { honest.requests.size == 8 }
        assertEquals(listOf(4, 5, 6, 7, 0, 1, 2, 3), honest.requests)
        val letGo = passUntil(30_000) { stuck.closes == 1 }
        assertTrue(letGo in 5_000..15_000, "the stuck peer was let go ${letGo + 5_000} ms after its requests")
        honest.answerAsked(info)
        assertContentEquals(info, result.bytes())
    }

    @Test
    fun aSecondCopyOfABlockIsIgnoredAndTheFirstStays() {
        val info = blob(3)
        val t = ScriptedTransport(sha1(info))
        val slow = t.honest(1, info)
        val quick = t.honest(2, info, greetAtOnce = false)
        val result = start(info, t, listOf(addr(1), addr(2)))
        slow.awaitRequests(3)
        quick.awaitConnected()
        quick.greet()
        passUntil(20_000) { quick.requests.size == 3 } // it was given the blocks the slow one holds
        slow.sendBlock(info, 0)
        // the same block, wrong, from the other: ignored; the rest from it is right
        quick.sendBlock(info, 0, mutate = ::corrupt)
        quick.sendBlock(info, 1)
        quick.sendBlock(info, 2)
        assertContentEquals(info, result.bytes())
    }

    // ---- peers that cannot help are not bad

    @Test
    fun aSwarmOfPeersThatCannotHelpEndsWithNoPeersAndNotWithMetadataInvalid() {
        val info = blob(2)
        val t = ScriptedTransport(sha1(info))
        val plain = t.peer(addr(1)).also { it.extensions = false }
        val noMetadata = t.peer(addr(2)).also { it.utId = null }
        val noSize = t.peer(addr(3)).also { it.claim = null }
        val zeroSize = t.peer(addr(4)).also { it.claim = 0 }
        val ourselves =
            t.peer(addr(5)).also {
                it.claim = info.size.toLong()
                it.peerId = Rig.PEER_ID
            }
        val all = listOf(plain, noMetadata, noSize, zeroSize, ourselves)
        val tracker = ScriptedTracker((1..5).map { addr(it) })
        val shorter = limits.copy(noPeersMs = 120_000)
        val result = start(info, t, emptyList(), tracker, shorter)
        rig.await("every peer to be tried") { all.all { it.closes == 1 } }
        pass(30_000)
        assertTrue(all.all { it.connects == 1 }, "no tight loop of reconnects: ${all.map { it.connects }}")
        passUntil(600_000) { result.isCompleted }
        assertEquals(NO_PEERS, result.failure().reason)
        assertTrue(all.all { it.connects <= 4 }, "retries are spaced: ${all.map { it.connects }}")
    }

    @Test
    fun aPeerThatCouldNotHelpIsTriedAgainOnALaterOfferAndThenGivesTheMetadata() {
        val info = blob(2)
        val t = ScriptedTransport(sha1(info))
        val peer =
            t.peer(addr(1)).also {
                it.claim = info.size.toLong()
                it.utId = null
            }
        val tracker = ScriptedTracker(listOf(addr(1)))
        val result = start(info, t, emptyList(), tracker)
        rig.await("the first try") { peer.closes == 1 }
        // it has the metadata now
        peer.utId = FAKE_UT_METADATA
        passUntil(300_000) { peer.connects == 2 }
        peer.awaitRequests(2)
        peer.answerAsked(info)
        assertContentEquals(info, result.bytes())
    }

    @Test
    fun aPeerThatWentSilentAfterAGoodOfferIsLetGoAfterTheMetadataTimeoutAndNotReconnectedAtOnce() {
        val info = blob(2)
        val t = ScriptedTransport(sha1(info))
        val peer = t.honest(1, info)
        val tracker = ScriptedTracker(listOf(addr(1)))
        val result = start(info, t, emptyList(), tracker)
        peer.awaitRequests(2)
        val letGo = passUntil(60_000) { peer.closes == 1 }
        assertTrue(letGo in METADATA_REQUEST_TIMEOUT_MS..METADATA_REQUEST_TIMEOUT_MS + 2_000, "let go after $letGo ms")
        peer.markAnswered()
        pass(30_000)
        assertEquals(1, peer.connects, "the trackers still offer it, and it is left alone for a while")
        passUntil(120_000) { peer.connects == 2 }
        peer.awaitRequests(4)
        peer.answerAsked(info)
        assertContentEquals(info, result.bytes())
    }

    // ---- what is kept

    @Test
    fun isolationEndsWhenEveryPeerOfThatSizeIsGoneSoNewPeersShareTheBlocksAgain() {
        val tracker = ScriptedTracker(emptyList())
        val info = blob(8)
        val t = ScriptedTransport(sha1(info))
        val a = t.honest(1, info)
        val b = t.honest(2, info, greetAtOnce = false)
        val c = t.honest(3, info)
        val d = t.honest(4, info, greetAtOnce = false)
        val result = start(info, t, listOf(addr(1), addr(2)), tracker)
        a.awaitRequests(4)
        b.awaitConnected()
        b.greet()
        b.awaitRequests(4)
        a.answerAsked(info, mutate = ::corrupt)
        b.answerAsked(info)
        a.awaitRequests(8) // the check failed: a is alone
        a.hangUp()
        b.hangUp()
        rig.await("both to leave") { a.closes == 1 && b.closes == 1 }
        tracker.offer = listOf(addr(3), addr(4))
        pass(2_000)
        c.awaitRequests(4)
        d.awaitConnected()
        d.greet()
        d.awaitRequests(4)
        assertEquals(listOf(0, 1, 2, 3), c.requests)
        assertEquals(listOf(4, 5, 6, 7), d.requests, "both are asked at once: nobody is alone any more")
        c.answerAsked(info)
        d.answerAsked(info)
        assertContentEquals(info, result.bytes())
    }

    @Test
    fun whatSeveralSizesHoldIsBoundedAndTheOneThatWentOverStartsOver() {
        val info = blob(8)
        val sizeX = 8L * METADATA_BLOCK + 1
        val sizeY = 9L * METADATA_BLOCK
        val t = ScriptedTransport(sha1(info))
        val x = t.peer(addr(1)).also { it.claim = sizeX }
        val y = t.peer(addr(2)).also { it.claim = sizeY }
        // room for three blocks in all
        val result = start(info, t, listOf(addr(1), addr(2)), maxHeld = 3L * METADATA_BLOCK)
        x.awaitRequests(4)
        y.awaitRequests(4)
        x.sendJunk(0, sizeX)
        x.sendJunk(1, sizeX)
        rig.await("x to be asked for more") { x.requests.size >= 6 }
        y.sendJunk(0, sizeY)
        y.sendJunk(1, sizeY)
        // four blocks held in all, over the three there is room for: the one whose block it was starts over and asks for them again
        rig.await("y to be asked again") { y.requests.size >= 6 }
        // (it was asked block 4 after the first answer, and 0 again once it started over)
        assertEquals(listOf(0, 1, 2, 3, 4, 0), y.requests)
        assertEquals(6, x.requests.size, "x was left as it was")
        result.cancel()
    }
}
