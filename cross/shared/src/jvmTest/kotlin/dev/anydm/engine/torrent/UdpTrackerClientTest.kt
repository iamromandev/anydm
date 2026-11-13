package dev.anydm.engine.torrent

import dev.anydm.engine.http.FakeClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The UDP tracker client against an in-process BEP 15 tracker on loopback; its waits run on a fake clock. */
class UdpTrackerClientTest {
    private val addrs = listOf(PeerAddr("93.184.216.34", 6881), PeerAddr("10.1.2.3", 51_413))
    private val tracker = FakeUdpTracker(addrs, interval = 900)
    private val clock = FakeClock()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val hash = ByteArray(20) { (it * 3).toByte() }
    private val peerId = "-AD0001-abcdefghijkl".encodeToByteArray()
    private val client = UdpTrackerClient(clock, tickMs = 5)

    @AfterTest
    fun tearDown() {
        scope.cancel()
        tracker.close()
    }

    private fun req(event: AnnounceEvent? = AnnounceEvent.Started) =
        AnnounceRequest(hash, peerId, 6881, downloaded = 25, left = 75, event = event)

    private fun announce(
        url: String = tracker.url,
        event: AnnounceEvent? = AnnounceEvent.Started,
    ): Deferred<TrackerReply> = scope.async { client.announce(url, req(event)) }

    private fun result(d: Deferred<TrackerReply>): TrackerReply = runBlocking { withTimeout(10_000) { d.await() } }

    private fun await(
        what: String,
        cond: () -> Boolean,
    ) {
        val end = System.currentTimeMillis() + 10_000
        while (!cond()) {
            check(System.currentTimeMillis() < end) { "timed out waiting for $what" }
            Thread.sleep(2)
        }
    }

    @Test
    fun aConnectThenAnAnnounceGivesThePeersAndTheInterval() {
        val r = assertIs<AnnounceResult>(result(announce()))
        assertEquals(addrs, r.peers)
        assertEquals(900, r.intervalS)
        assertEquals(1, tracker.connectCount)
        val s = tracker.seen.single()
        assertContentEquals(hash, s.infoHash)
        assertEquals(25L, s.downloaded)
        assertEquals(75L, s.left)
        assertEquals(2, s.event, "started")
        assertEquals(6881, s.port)
    }

    @Test
    fun theConnectionIdIsReusedForAMinuteAndThenAskedForAgain() {
        result(announce())
        clock.advance(59_000)
        result(announce(event = null))
        assertEquals(1, tracker.connectCount, "reused within the minute")
        assertEquals(2, tracker.announceCount)
        assertEquals(tracker.seen[0].connectionId, tracker.seen[1].connectionId)
        clock.advance(2_000)
        result(announce(event = AnnounceEvent.Completed))
        assertEquals(2, tracker.connectCount, "a new one after the minute")
        assertTrue(tracker.seen[2].connectionId != tracker.seen[0].connectionId)
    }

    @Test
    fun aConnectionIdThatTheTrackerNoLongerKnowsIsReplacedAtOnce() {
        result(announce())
        tracker.expireIds()
        val r = assertIs<AnnounceResult>(result(announce(event = null)))
        assertEquals(addrs, r.peers)
        assertEquals(2, tracker.connectCount)
    }

    @Test
    fun anUnansweredConnectIsSentAgainAfterFifteenSecondsThenThirtyThenSixty() {
        tracker.dropConnects = 3
        val d = announce()
        await("the first connect") { tracker.connectCount == 1 }
        clock.advance(14_999)
        Thread.sleep(60)
        assertEquals(1, tracker.connectCount, "not before 15 s")
        clock.advance(1)
        await("the second connect") { tracker.connectCount == 2 }
        clock.advance(29_000)
        Thread.sleep(60)
        assertEquals(2, tracker.connectCount, "the second wait is 30 s")
        clock.advance(1_000)
        await("the third connect") { tracker.connectCount == 3 }
        clock.advance(59_000)
        Thread.sleep(60)
        assertEquals(3, tracker.connectCount, "the third wait is 60 s")
        clock.advance(1_000)
        assertIs<AnnounceResult>(result(d))
        assertEquals(4, tracker.connectCount)
    }

    @Test
    fun aTrackerThatNeverAnswersIsGivenUpAfterFourTimeouts() {
        tracker.dropConnects = Int.MAX_VALUE
        val d = announce()
        for ((n, wait) in listOf(15_000L, 30_000L, 60_000L, 120_000L).withIndex()) {
            await("connect ${n + 1}") { tracker.connectCount == n + 1 }
            clock.advance(wait)
        }
        assertFailsWith<IOException> { result(d) }
        assertEquals(4, tracker.connectCount, "four tries and no more")
    }

    @Test
    fun anUnansweredAnnounceIsSentAgainOnTheSameSchedule() {
        tracker.dropAnnounces = 1
        val d = announce()
        await("the first announce") { tracker.announceCount == 1 }
        clock.advance(15_000)
        assertIs<AnnounceResult>(result(d))
        assertEquals(2, tracker.announceCount)
        assertEquals(1, tracker.connectCount, "the connection id was still good")
    }

    // BEP 15 / NFR2: what is not the answer to our request is no answer
    @Test
    fun answersWithAnotherTransactionIdOrTooShortAreIgnored() {
        tracker.stray = true
        assertEquals(addrs, assertIs<AnnounceResult>(result(announce())).peers)
    }

    @Test
    fun anAnswerWithTheWrongActionIsIgnored() {
        tracker.wrongActionFirst = true
        assertEquals(addrs, assertIs<AnnounceResult>(result(announce())).peers)
    }

    @Test
    fun aDatagramFromAnotherPortIsIgnored() {
        tracker.strayFromOtherPort = true
        assertEquals(addrs, assertIs<AnnounceResult>(result(announce())).peers)
    }

    @Test
    fun anErrorAnswerToTheAnnounceIsTheTrackersFailureReason() {
        tracker.announceError = "unregistered torrent"
        val f = assertIs<TrackerFailure>(result(announce()))
        assertEquals("unregistered torrent", f.reason)
        assertFalse(f.malformed)
    }

    @Test
    fun anErrorAnswerToTheConnectIsTheTrackersFailureReason() {
        tracker.connectError = "go away"
        assertEquals("go away", assertIs<TrackerFailure>(result(announce())).reason)
        assertEquals(0, tracker.announceCount)
    }

    @Test
    fun aCancelEndsTheWaitAtOnce() {
        tracker.dropConnects = Int.MAX_VALUE
        val d = announce()
        await("the connect") { tracker.connectCount == 1 }
        runBlocking { withTimeout(2_000) { d.cancelAndJoin() } }
        assertTrue(d.isCancelled)
    }

    // a name lookup cannot be interrupted: a pause must not wait for it
    @Test
    fun aCancelDuringANameLookupThatNeverReturnsEndsAtOnce() {
        val stuck = java.util.concurrent.CountDownLatch(1)
        val entered = java.util.concurrent.CountDownLatch(1)
        val hanging =
            UdpTrackerClient(clock, tickMs = 5, resolver = { host, port ->
                entered.countDown()
                stuck.await()
                java.net.InetSocketAddress(host, port)
            })
        try {
            val d = scope.async { hanging.announce("udp://tracker.example:6969/announce", req()) }
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS), "the lookup started")
            val begun = System.currentTimeMillis()
            runBlocking { withTimeout(2_000) { d.cancelAndJoin() } }
            assertTrue(System.currentTimeMillis() - begun < 1_000, "cancelled within a second")
            assertTrue(d.isCancelled)
        } finally {
            stuck.countDown()
        }
    }

    @Test
    fun aNameLookupThatNeverReturnsIsGivenUpAsUnreachable() {
        val stuck = java.util.concurrent.CountDownLatch(1)
        val hanging =
            UdpTrackerClient(clock, tickMs = 5, dnsTimeoutMs = 300, resolver = { host, port ->
                stuck.await()
                java.net.InetSocketAddress(host, port)
            })
        try {
            val begun = System.currentTimeMillis()
            assertFailsWith<IOException> {
                result(scope.async { hanging.announce("udp://tracker.example:6969/announce", req()) })
            }
            assertTrue(System.currentTimeMillis() - begun < 3_000, "gave up on time")
        } finally {
            stuck.countDown()
        }
    }

    @Test
    fun anAddressThatIsNotUsableIsUnreachable() {
        for (url in listOf(
            "udp://127.0.0.1/announce",
            "udp:///announce",
            "udp://127.0.0.1:0/announce",
            "udp://127.0.0.1:70000/x",
            "not a url",
        )) {
            assertFailsWith<IOException>(url) { result(announce(url)) }
        }
    }

    @Test
    fun aTrackerThatIsNotThereIsUnreachableAtOnceWhenTheSystemSaysSo() {
        val gone = FakeUdpTracker().also { it.close() }.url
        // the port is closed: the system reports it on the next read of the connected socket, so no clock time passes
        assertFailsWith<IOException> { result(announce(gone)) }
    }
}
