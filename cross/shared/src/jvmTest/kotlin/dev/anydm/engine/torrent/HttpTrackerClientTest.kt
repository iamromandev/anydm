package dev.anydm.engine.torrent

import dev.anydm.engine.http.JdkTransport
import kotlinx.coroutines.runBlocking
import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The HTTP tracker client against an in-process tracker on loopback. */
class HttpTrackerClientTest {
    private val tracker = FakeHttpTracker()
    private val hash = ByteArray(20) { (it * 7).toByte() }
    private val peerId = "-AD0001-abcdefghijkl".encodeToByteArray()
    private val addrs = listOf(PeerAddr("93.184.216.34", 6881), PeerAddr("10.1.2.3", 51_413))

    @AfterTest
    fun tearDown() = tracker.close()

    private fun client(
        timeoutMs: Long = 5_000,
        transport: JdkTransport = JdkTransport(requestTimeoutMs = timeoutMs),
    ) = HttpTrackerClient(transport, timeoutMs)

    private fun req(event: AnnounceEvent? = null) = AnnounceRequest(hash, peerId, 6881, downloaded = 10, left = 90, event = event)

    private fun announce(
        c: HttpTrackerClient = client(),
        url: String = tracker.announceUrl,
        event: AnnounceEvent? = AnnounceEvent.Started,
    ) = runBlocking { c.announce(url, req(event)) }

    // AC4: compact and dictionary peer lists
    @Test
    fun compactPeersAndTheIntervalsComeBack() {
        tracker.answer = { _, _ -> TrackerAnswer.Body(FakeHttpTracker.peersReply(addrs, interval = 900, minInterval = 120)) }
        val r = assertIs<AnnounceResult>(announce())
        assertEquals(addrs, r.peers)
        assertEquals(900, r.intervalS)
        assertEquals(120, r.minIntervalS)
    }

    @Test
    fun dictionaryPeersComeBack() {
        tracker.answer = { _, _ -> TrackerAnswer.Body(FakeHttpTracker.dictionaryReply(addrs)) }
        assertEquals(addrs, assertIs<AnnounceResult>(announce()).peers)
    }

    @Test
    fun theRequestCarriesTheHashTheIdTheEventAndTheNumbers() {
        announce(event = AnnounceEvent.Started)
        val s = tracker.seen.single()
        assertEquals("/announce", s.path)
        assertContentEquals(hash, s.infoHash)
        assertContentEquals(peerId, s.params["peer_id"])
        assertEquals("started", s.event)
        assertEquals(10L, s.downloaded)
        assertEquals(90L, s.left)
        assertEquals("0", s.params["uploaded"]!!.decodeToString())
        assertEquals("1", s.params["compact"]!!.decodeToString())
    }

    @Test
    fun aTrackerUrlWithItsOwnQueryKeepsIt() {
        announce(url = tracker.announceUrl + "?passkey=abc")
        assertEquals(
            "abc",
            tracker.seen
                .single()
                .params["passkey"]!!
                .decodeToString(),
        )
        assertContentEquals(hash, tracker.seen.single().infoHash)
    }

    @Test
    fun aFailureReasonIsTheTrackersAnswerNotAnError() {
        tracker.answer = { _, _ -> TrackerAnswer.Body(FakeHttpTracker.failureReply("torrent not registered")) }
        val f = assertIs<TrackerFailure>(announce())
        assertEquals("torrent not registered", f.reason)
        assertFalse(f.malformed)
    }

    @Test
    fun aFailureReasonSentWithAnErrorStatusIsStillTheTrackersAnswer() {
        tracker.answer = { _, _ -> TrackerAnswer.Body(FakeHttpTracker.failureReply("banned"), status = 403) }
        assertEquals("banned", assertIs<TrackerFailure>(announce()).reason)
    }

    @Test
    fun anErrorStatusWithNoReasonIsAnUnreachableTracker() {
        tracker.answer = { _, _ -> TrackerAnswer.Body("<html>oops</html>".encodeToByteArray(), status = 500) }
        assertFailsWith<IOException> { announce() }
    }

    @Test
    fun aBodyThatIsNotBencodedIsAMalformedAnswer() {
        tracker.answer = { _, _ -> TrackerAnswer.Body("<html>hi</html>".encodeToByteArray()) }
        assertTrue(assertIs<TrackerFailure>(announce()).malformed)
    }

    // NFR2: a response over 1 MiB is refused, and not read to its end
    @Test
    fun aResponseOverOneMebibyteIsRefused() {
        val big = bdict("interval" to bint(60), "peers" to bstr(ByteArray(MAX_TRACKER_RESPONSE)))
        assertTrue(big.size > MAX_TRACKER_RESPONSE)
        tracker.answer = { _, _ -> TrackerAnswer.Body(big) }
        assertTrue(assertIs<TrackerFailure>(announce()).malformed)
    }

    // the cap is held while reading too: a length the headers do not give (or give wrongly) is no way round it
    @Test
    fun aChunkedResponseOverOneMebibyteIsRefusedAsTooLarge() {
        val big = bdict("interval" to bint(60), "peers" to bstr(ByteArray(MAX_TRACKER_RESPONSE)))
        tracker.answer = { _, _ -> TrackerAnswer.Chunked(big) }
        val f = assertIs<TrackerFailure>(announce())
        assertTrue(f.malformed)
        assertEquals("Response too large", f.reason)
    }

    @Test
    fun aChunkedResponseOfExactlyTheCapIsRead() {
        fun bodyOf(n: Int) = bdict("interval" to bint(60), "peers" to bstr(ByteArray(0)), "x" to bstr(ByteArray(n)))
        val body = bodyOf((MAX_TRACKER_RESPONSE downTo 0).first { bodyOf(it).size <= MAX_TRACKER_RESPONSE })
        assertEquals(MAX_TRACKER_RESPONSE, body.size)
        tracker.answer = { _, _ -> TrackerAnswer.Chunked(body) }
        assertIs<AnnounceResult>(announce())
    }

    @Test
    fun aResponseThatNeverEndsIsRefusedAtTheCapNotReadToItsEnd() {
        tracker.answer = { _, _ -> TrackerAnswer.Endless(bdict("interval" to bint(60)).dropLast(1).toByteArray()) }
        val started = System.currentTimeMillis()
        val f = assertIs<TrackerFailure>(announce(client(timeoutMs = 2_000)))
        assertEquals("Response too large", f.reason)
        assertTrue(System.currentTimeMillis() - started < 10_000, "stopped at the cap, not at the timeout")
    }

    @Test
    fun aContentLengthThatLiesIsNoWayAroundTheCap() {
        val transport =
            object : dev.anydm.engine.http.HttpTransport {
                override suspend fun open(
                    url: String,
                    method: String,
                    headers: Map<String, String>,
                ) = dev.anydm.engine.http.HttpOpen(
                    200,
                    mapOf("content-length" to listOf("10")),
                    url,
                    // says 10 bytes and sends zeros for ever
                    object : java.io.InputStream() {
                        override fun read(): Int = 0

                        override fun read(
                            b: ByteArray,
                            off: Int,
                            len: Int,
                        ): Int = len
                    },
                )
            }
        val f = assertIs<TrackerFailure>(announce(HttpTrackerClient(transport, timeoutMs = 2_000)))
        assertEquals("Response too large", f.reason)
    }

    @Test
    fun aResponseOfExactlyTheCapIsRead() {
        fun bodyOf(n: Int) = bdict("interval" to bint(60), "peers" to bstr(ByteArray(0)), "x" to bstr(ByteArray(n)))
        val body = bodyOf((MAX_TRACKER_RESPONSE downTo 0).first { bodyOf(it).size <= MAX_TRACKER_RESPONSE })
        assertEquals(MAX_TRACKER_RESPONSE, body.size)
        tracker.answer = { _, _ -> TrackerAnswer.Body(body) }
        assertIs<AnnounceResult>(announce())
    }

    @Test
    fun aRedirectIsFollowed() {
        tracker.answer = { _, s ->
            if (s.path == "/announce") {
                TrackerAnswer.Redirect("/moved?" + s.query)
            } else {
                TrackerAnswer.Body(FakeHttpTracker.peersReply(addrs))
            }
        }
        assertEquals(addrs, assertIs<AnnounceResult>(announce()).peers)
        assertEquals(listOf("/announce", "/moved"), tracker.seen.map { it.path })
    }

    @Test
    fun aRedirectLoopEndsInAnUnreachableTracker() {
        tracker.answer = { _, s -> TrackerAnswer.Redirect("/announce?again=" + s.seenCount()) }
        assertFailsWith<IOException> { announce() }
        assertTrue(tracker.seen.size <= 12, "bounded: ${tracker.seen.size} requests")
    }

    private fun AnnounceSeen.seenCount() = tracker.seen.size

    @Test
    fun aTrackerThatNeverAnswersTimesOut() {
        tracker.answer = { _, _ -> TrackerAnswer.Hang }
        val started = System.currentTimeMillis()
        assertFailsWith<IOException> { announce(client(timeoutMs = 300)) }
        assertTrue(System.currentTimeMillis() - started < 3_000, "gave up on time")
    }

    @Test
    fun aBodyThatNeverEndsTimesOutToo() {
        // headers come at once, the body stops half way: the read must not wait for ever
        tracker.answer = { _, _ -> TrackerAnswer.Body(FakeHttpTracker.peersReply(addrs)) }
        val slow =
            HttpTrackerClient(
                object : dev.anydm.engine.http.HttpTransport {
                    override suspend fun open(
                        url: String,
                        method: String,
                        headers: Map<String, String>,
                    ) = dev.anydm.engine.http.HttpOpen(
                        200,
                        emptyMap(),
                        url,
                        object : java.io.InputStream() {
                            override fun read(): Int {
                                Thread.sleep(60_000)
                                return -1
                            }

                            override fun read(
                                b: ByteArray,
                                off: Int,
                                len: Int,
                            ): Int {
                                Thread.sleep(60_000)
                                return -1
                            }
                        },
                    )
                },
                timeoutMs = 300,
            )
        val started = System.currentTimeMillis()
        assertFailsWith<IOException> { announce(slow) }
        assertTrue(System.currentTimeMillis() - started < 3_000, "gave up on time")
    }

    @Test
    fun aUrlThatIsNotHttpIsRefused() {
        assertFailsWith<IOException> { announce(url = "ftp://127.0.0.1/announce") }
    }

    @Test
    fun aTrackerThatIsNotListeningIsUnreachable() {
        val dead = FakeHttpTracker().also { it.close() }.announceUrl
        assertFailsWith<IOException> { announce(url = dead) }
    }
}
