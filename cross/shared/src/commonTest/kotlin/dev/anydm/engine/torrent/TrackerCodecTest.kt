package dev.anydm.engine.torrent

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrackerCodecTest {
    private val hash = ByteArray(20) { it.toByte() }
    private val peerId = "-AD0001-abcdefghijkl".encodeToByteArray()

    private fun req(
        event: AnnounceEvent? = null,
        downloaded: Long = 0,
        left: Long = 1_000,
    ) = AnnounceRequest(hash, peerId, 6881, downloaded, left, event)

    private fun ip(
        a: Int,
        b: Int,
        c: Int,
        d: Int,
        port: Int,
    ) = byteArrayOf(a.toByte(), b.toByte(), c.toByte(), d.toByte(), (port ushr 8).toByte(), port.toByte())

    private fun ok(bytes: ByteArray): AnnounceResult = assertIs<AnnounceResult>(parseHttpAnnounce(bytes))

    private fun failure(bytes: ByteArray): TrackerFailure = assertIs<TrackerFailure>(parseHttpAnnounce(bytes))

    // --- peer addresses

    @Test
    fun anOrdinaryAddressIsAccepted() {
        assertEquals(PeerAddr("93.184.216.34", 6881), peerAddr("93.184.216.34", 6881))
        assertEquals(PeerAddr("127.0.0.1", 1), peerAddr("127.0.0.1", 1))
        assertEquals(PeerAddr("10.0.0.5", 65_535), peerAddr("10.0.0.5", 65_535))
        assertEquals(PeerAddr("223.255.255.255", 80), peerAddr("223.255.255.255", 80))
        assertEquals(PeerAddr("240.0.0.1", 80), peerAddr("240.0.0.1", 80))
    }

    @Test
    fun unspecifiedMulticastAndBroadcastAddressesAreSkipped() {
        assertNull(peerAddr("0.0.0.0", 6881))
        assertNull(peerAddr("224.0.0.1", 6881))
        assertNull(peerAddr("239.255.255.255", 6881))
        assertNull(peerAddr("255.255.255.255", 6881))
    }

    @Test
    fun portZeroAndPortsOutOfRangeAreSkipped() {
        assertNull(peerAddr("1.2.3.4", 0))
        assertNull(peerAddr("1.2.3.4", -1))
        assertNull(peerAddr("1.2.3.4", 65_536))
    }

    @Test
    fun onlyDottedQuadIpv4TextIsAccepted() {
        for (host in listOf(
            "",
            "localhost",
            "example.com",
            "::1",
            "1.2.3",
            "1.2.3.4.5",
            "1.2.3.256",
            "1.2.3.-1",
            "1.2.3.",
            ".1.2.3",
            "1.2.3.4 ",
            "1.2.3.a",
            "01.2.3.4",
            "1.2.3.04",
            "+1.2.3.4",
            "0x7f.0.0.1",
            "1..3.4",
            "１.2.3.4",
        )) {
            assertNull(peerAddr(host, 6881), "host '$host'")
        }
    }

    // --- HTTP announce URL

    @Test
    fun percentEncodingKeepsUnreservedBytesAndEscapesTheRestInCapitals() {
        assertEquals("AZaz09.-_~", percentEncode("AZaz09.-_~".encodeToByteArray()))
        assertEquals("%00%20%2F%7F%80%FF%0A", percentEncode(byteArrayOf(0, 0x20, 0x2F, 0x7F, 0x80.toByte(), 0xFF.toByte(), 0x0A)))
        assertEquals("", percentEncode(ByteArray(0)))
        val every = percentEncode(ByteArray(256) { it.toByte() })
        assertFalse(every.any { it.code > 0x7E || it == ' ' || it == '/' || it == '+' })
        assertEquals(256 - 66, every.count { it == '%' })
    }

    @Test
    fun theAnnounceUrlCarriesEveryParameter() {
        val h =
            byteArrayOf(
                0x12,
                0x34,
                0x56,
                0x78,
                0x9A.toByte(),
                0xBC.toByte(),
                0xDE.toByte(),
                0xF0.toByte(),
                0x41,
                0x2D,
                0x2E,
                0x20,
                0x21,
                0x2F,
                0x3D,
                0x26,
                0x25,
                0x7E,
                0x00,
                0xFF.toByte(),
            )
        val url = httpAnnounceUrl("http://tracker.example/announce", AnnounceRequest(h, peerId, 6881, 5, 1_000, AnnounceEvent.Started))
        assertEquals(
            "http://tracker.example/announce?info_hash=%124Vx%9A%BC%DE%F0A-.%20%21%2F%3D%26%25~%00%FF&peer_id=-AD0001-abcdefghijkl" +
                "&port=6881&uploaded=0&downloaded=5&left=1000&compact=1&numwant=50&event=started",
            url,
        )
    }

    @Test
    fun theEventIsOmittedForAPeriodicAnnounceAndNamedOtherwise() {
        assertFalse("event=" in httpAnnounceUrl("http://t/a", req(null)))
        assertTrue(httpAnnounceUrl("http://t/a", req(AnnounceEvent.Started)).endsWith("&event=started"))
        assertTrue(httpAnnounceUrl("http://t/a", req(AnnounceEvent.Stopped)).endsWith("&event=stopped"))
        assertTrue(httpAnnounceUrl("http://t/a", req(AnnounceEvent.Completed)).endsWith("&event=completed"))
    }

    @Test
    fun anExistingQueryIsKeptAndExtendedWithAnAmpersand() {
        val url = httpAnnounceUrl("http://t/a?passkey=abc", req())
        assertTrue(url.startsWith("http://t/a?passkey=abc&info_hash="), url)
        assertEquals(1, url.count { it == '?' })
    }

    @Test
    fun aBaseEndingInQuestionMarkOrAmpersandGetsNoSecondSeparator() {
        assertTrue(httpAnnounceUrl("http://t/a?", req()).startsWith("http://t/a?info_hash="))
        assertTrue(httpAnnounceUrl("http://t/a?x=1&", req()).startsWith("http://t/a?x=1&info_hash="))
    }

    @Test
    fun aFragmentIsNotSent() {
        val url = httpAnnounceUrl("http://t/a#frag", req())
        assertTrue(url.startsWith("http://t/a?info_hash="), url)
        assertFalse('#' in url)
    }

    @Test
    fun downloadedAndLeftAreWrittenAsPlainDecimals() {
        val url = httpAnnounceUrl("http://t/a", req(downloaded = 6_000_000_000L, left = Long.MAX_VALUE))
        assertTrue("&downloaded=6000000000&left=9223372036854775807&" in url, url)
    }

    @Test
    fun theNumwantAndPortComeFromTheRequest() {
        val r = AnnounceRequest(hash, peerId, 51_413, 0, 0, numWant = 30)
        val url = httpAnnounceUrl("http://t/a", r)
        assertTrue("&port=51413&" in url && "&numwant=30" in url, url)
    }

    // --- HTTP announce response

    @Test
    fun compactPeersAreReadSixBytesEach() {
        val body = bdict("interval" to bint(900), "peers" to bstr(ip(1, 2, 3, 4, 6881) + ip(10, 0, 0, 7, 51_413)))
        val r = ok(body)
        assertEquals(listOf(PeerAddr("1.2.3.4", 6881), PeerAddr("10.0.0.7", 51_413)), r.peers)
        assertEquals(900, r.intervalS)
        assertNull(r.minIntervalS)
    }

    @Test
    fun aPortAboveThirtyTwoThousandIsNotNegative() {
        assertEquals(PeerAddr("1.2.3.4", 65_535), ok(bdict("peers" to bstr(ip(1, 2, 3, 4, 65_535)))).peers.single())
    }

    @Test
    fun aTrailingPartialCompactEntryIsIgnored() {
        val r = ok(bdict("peers" to bstr(ip(1, 2, 3, 4, 80) + byteArrayOf(9, 9, 9))))
        assertEquals(listOf(PeerAddr("1.2.3.4", 80)), r.peers)
    }

    @Test
    fun compactEntriesWeMustNotConnectToAreDropped() {
        val body = ip(0, 0, 0, 0, 80) + ip(224, 1, 1, 1, 80) + ip(255, 255, 255, 255, 80) + ip(1, 2, 3, 4, 0) + ip(5, 6, 7, 8, 9)
        assertEquals(listOf(PeerAddr("5.6.7.8", 9)), ok(bdict("peers" to bstr(body))).peers)
    }

    @Test
    fun duplicateCompactEntriesAreKeptOnce() {
        assertEquals(1, ok(bdict("peers" to bstr(ip(1, 2, 3, 4, 80) + ip(1, 2, 3, 4, 80)))).peers.size)
    }

    @Test
    fun dictionaryPeersAreReadByIpAndPort() {
        fun peer(
            ip: String,
            port: Long,
        ) = bdict("ip" to bstr(ip), "port" to bint(port), "peer id" to bstr(ByteArray(20)))
        val r = ok(bdict("interval" to bint(60), "peers" to blist(peer("1.2.3.4", 80), peer("5.6.7.8", 6881))))
        assertEquals(listOf(PeerAddr("1.2.3.4", 80), PeerAddr("5.6.7.8", 6881)), r.peers)
    }

    @Test
    fun dictionaryPeersThatAreMalformedOrNotUsableAreSkipped() {
        val r =
            ok(
                bdict(
                    "peers" to
                        blist(
                            bdict("ip" to bstr("1.2.3.4")),
                            bdict("port" to bint(80)),
                            bdict("ip" to bstr("1.2.3.4"), "port" to bint(0)),
                            bdict("ip" to bstr("1.2.3.4"), "port" to bint(70_000)),
                            bdict("ip" to bstr("example.com"), "port" to bint(80)),
                            bdict("ip" to bstr("::1"), "port" to bint(80)),
                            bdict("ip" to bstr("0.0.0.0"), "port" to bint(80)),
                            bdict("ip" to bint(5), "port" to bint(80)),
                            bdict("ip" to bstr("1.2.3.4"), "port" to bstr("80")),
                            bint(7),
                            bdict("ip" to bstr("9.9.9.9"), "port" to bint(99)),
                        ),
                ),
            )
        assertEquals(listOf(PeerAddr("9.9.9.9", 99)), r.peers)
    }

    @Test
    fun noPeersKeyIsAnEmptyList() {
        assertEquals(emptyList(), ok(bdict("interval" to bint(1800))).peers)
        assertEquals(emptyList(), ok(bdict("peers" to bint(5))).peers)
        assertEquals(emptyList(), ok(bdict("peers" to bstr(ByteArray(0)))).peers)
    }

    @Test
    fun peersFromABigResponseAreCapped() {
        val many = concat(List(5_000) { ip(1, (it shr 8) and 0xFF, it and 0xFF, 1, 80) })
        assertEquals(MAX_ANNOUNCE_PEERS, ok(bdict("peers" to bstr(many))).peers.size)
    }

    @Test
    fun theIntervalsAreReadAndBounded() {
        assertEquals(1_800, ok(bdict("peers" to bstr(ByteArray(0)))).intervalS)
        assertEquals(1_800, ok(bdict("interval" to bint(0))).intervalS)
        assertEquals(1_800, ok(bdict("interval" to bint(-5))).intervalS)
        assertEquals(1_800, ok(bdict("interval" to bstr("900"))).intervalS)
        assertEquals(86_400, ok(bdict("interval" to bint(Long.MAX_VALUE))).intervalS)
        assertEquals(60, ok(bdict("interval" to bint(1_800), "min interval" to bint(60))).minIntervalS)
        assertNull(ok(bdict("interval" to bint(1_800), "min interval" to bint(0))).minIntervalS)
        assertNull(ok(bdict("interval" to bint(1_800), "min interval" to bint(-1))).minIntervalS)
        assertEquals(86_400, ok(bdict("min interval" to bint(Long.MAX_VALUE))).minIntervalS)
    }

    @Test
    fun aFailureReasonIsReportedAsTheTrackersOwn() {
        val f = failure(bdict("failure reason" to bstr("torrent not registered")))
        assertEquals("torrent not registered", f.reason)
        assertFalse(f.malformed)
    }

    @Test
    fun aFailureReasonWinsOverPeersInTheSameResponse() {
        assertEquals("nope", failure(bdict("failure reason" to bstr("nope"), "peers" to bstr(ip(1, 2, 3, 4, 80)))).reason)
    }

    @Test
    fun aFailureReasonIsCleanedBeforeItReachesTheScreen() {
        assertEquals("a b", failure(bdict("failure reason" to bstr("a\u0000\u0007\n b"))).reason)
        assertFalse(failure(bdict("failure reason" to bstr("x‮y\u0001"))).reason.any { it < ' ' || it == '‮' })
        assertEquals(MAX_FAILURE_REASON, failure(bdict("failure reason" to bstr("e".repeat(5_000)))).reason.length)
        assertEquals("Unknown reason", failure(bdict("failure reason" to bstr(""))).reason)
        assertEquals("Unknown reason", failure(bdict("failure reason" to bstr("\u0001\u0002"))).reason)
        assertTrue(failure(bdict("failure reason" to bstr(byteArrayOf(0xFF.toByte(), 'a'.code.toByte())))).reason.endsWith("a"))
    }

    @Test
    fun aFailureReasonIsNotCutInTheMiddleOfASurrogatePair() {
        val emoji = "\uD83D\uDE00"
        // 199 letters then a pair that would straddle the 200 character cap: the pair goes whole or not at all.
        val reason = failure(bdict("failure reason" to bstr("e".repeat(MAX_FAILURE_REASON - 1) + emoji + "tail"))).reason
        assertEquals("e".repeat(MAX_FAILURE_REASON - 1), reason)
        val fits = failure(bdict("failure reason" to bstr("e".repeat(MAX_FAILURE_REASON - 2) + emoji + "tail"))).reason
        assertEquals("e".repeat(MAX_FAILURE_REASON - 2) + emoji, fits)
        assertFalse(reason.last().isHighSurrogate())
    }

    @Test
    fun aFailureReasonLosesTheLineAndParagraphSeparators() {
        assertEquals("a b", failure(bdict("failure reason" to bstr("a\u2028 b"))).reason)
        assertEquals("ab", failure(bdict("failure reason" to bstr("a\u2029b"))).reason)
        assertEquals("Unknown reason", failure(bdict("failure reason" to bstr("\u2028\u2029"))).reason)
    }

    @Test
    fun aFailureReasonThatIsNotTextIsMalformed() {
        assertTrue(failure(bdict("failure reason" to bint(5), "interval" to bint(60))).malformed)
    }

    @Test
    fun aBodyThatIsNotBencodedOrNotADictionaryIsMalformed() {
        for (body in listOf(
            ByteArray(0),
            "<html>Bad gateway</html>".encodeToByteArray(),
            bint(5),
            blist(),
            "d5:peers".encodeToByteArray(),
        )) {
            val f = failure(body)
            assertTrue(f.malformed)
            assertTrue(f.reason.isNotEmpty())
        }
    }

    @Test
    fun aResponseOfExactlyOneMibIsReadAndOneByteMoreIsRefused() {
        // d4:junk<n>:<n bytes>5:peers0:e is 7 + digits + 1 + n + 10 bytes.
        fun bodyOf(size: Int): ByteArray {
            var n = size
            while (7 + "$n".length + 1 + n + 10 != size) n--
            return "d4:junk$n:".encodeToByteArray() + ByteArray(n) + "5:peers0:e".encodeToByteArray()
        }
        val exact = bodyOf(MAX_TRACKER_RESPONSE)
        assertEquals(MAX_TRACKER_RESPONSE, exact.size)
        assertIs<AnnounceResult>(parseHttpAnnounce(exact))
        val tooBig = failure(ByteArray(MAX_TRACKER_RESPONSE + 1))
        assertTrue(tooBig.malformed)
    }

    @Test
    fun aHostileNestedBodyIsMalformedNotACrash() {
        assertTrue(failure(nestedLists(10_000)).malformed)
        assertTrue(failure(nestedDicts(10_000)).malformed)
    }

    // --- UDP

    private fun be(
        v: Long,
        bytes: Int,
    ) = ByteArray(bytes) { (v ushr (8 * (bytes - 1 - it))).toByte() }

    @Test
    fun theUdpConnectRequestIsTheMagicConstantAction0AndTheTransactionId() {
        assertContentEquals(be(0x41727101980L, 8) + be(0, 4) + be(0x01020304, 4), connectRequest(0x01020304))
        assertEquals(16, connectRequest(-1).size)
        assertContentEquals(be(0xFFFFFFFFL, 4), connectRequest(-1).copyOfRange(12, 16))
    }

    @Test
    fun aUdpConnectReplyGivesTheConnectionId() {
        val reply = be(0, 4) + be(77, 4) + be(0x1122334455667788L, 8)
        assertEquals(0x1122334455667788L, assertIs<UdpReply.Ok<Long>>(parseConnect(reply, 77)).value)
        val negative = be(0, 4) + be(77, 4) + be(-2L, 8)
        assertEquals(-2L, assertIs<UdpReply.Ok<Long>>(parseConnect(negative, 77)).value)
    }

    @Test
    fun aUdpConnectReplyThatIsNotOursIsIgnored() {
        val good = be(0, 4) + be(77, 4) + be(5, 8)
        assertEquals(UdpReply.Ignored, parseConnect(good, 78))
        assertEquals(UdpReply.Ignored, parseConnect(good.copyOf(15), 77))
        assertEquals(UdpReply.Ignored, parseConnect(ByteArray(0), 77))
        assertEquals(UdpReply.Ignored, parseConnect(be(1, 4) + be(77, 4) + be(5, 8), 77), "an announce reply is not a connect reply")
        assertEquals(UdpReply.Ignored, parseConnect(be(2, 4) + be(77, 4) + be(5, 8), 77))
    }

    @Test
    fun aUdpErrorForOurTransactionIsAFailureWithItsMessage() {
        val err = be(3, 4) + be(77, 4) + "torrent unknown".encodeToByteArray()
        assertEquals("torrent unknown", assertIs<UdpReply.Failure>(parseConnect(err, 77)).reason)
        assertEquals("torrent unknown", assertIs<UdpReply.Failure>(parseUdpAnnounce(err, 77)).reason)
        assertEquals("Unknown reason", assertIs<UdpReply.Failure>(parseUdpAnnounce(be(3, 4) + be(77, 4), 77)).reason)
    }

    @Test
    fun aUdpErrorForAnotherTransactionIsIgnored() {
        val err = be(3, 4) + be(76, 4) + "x".encodeToByteArray()
        assertEquals(UdpReply.Ignored, parseConnect(err, 77))
        assertEquals(UdpReply.Ignored, parseUdpAnnounce(err, 77))
        assertEquals(UdpReply.Ignored, parseUdpAnnounce(be(3, 4), 77))
    }

    @Test
    fun theUdpErrorMessageIsCleanedAndCapped() {
        val err = be(3, 4) + be(77, 4) + ("a\u0000b" + "z".repeat(5_000)).encodeToByteArray()
        val reason = assertIs<UdpReply.Failure>(parseConnect(err, 77)).reason
        assertEquals(MAX_FAILURE_REASON, reason.length)
        assertFalse(reason.any { it < ' ' })
    }

    @Test
    fun theUdpAnnounceRequestHasTheBep15Layout() {
        val r =
            AnnounceRequest(hash, peerId, 6881, downloaded = 123, left = 456, event = AnnounceEvent.Started, numWant = 50, key = 0x0A0B0C0D)
        val b = announceRequest(0x1122334455667788L, 99, r)
        assertEquals(98, b.size)
        assertContentEquals(be(0x1122334455667788L, 8), b.copyOfRange(0, 8))
        assertContentEquals(be(1, 4), b.copyOfRange(8, 12))
        assertContentEquals(be(99, 4), b.copyOfRange(12, 16))
        assertContentEquals(hash, b.copyOfRange(16, 36))
        assertContentEquals(peerId, b.copyOfRange(36, 56))
        assertContentEquals(be(123, 8), b.copyOfRange(56, 64))
        assertContentEquals(be(456, 8), b.copyOfRange(64, 72))
        assertContentEquals(be(0, 8), b.copyOfRange(72, 80))
        assertContentEquals(be(2, 4), b.copyOfRange(80, 84))
        assertContentEquals(be(0, 4), b.copyOfRange(84, 88))
        assertContentEquals(be(0x0A0B0C0D, 4), b.copyOfRange(88, 92))
        assertContentEquals(be(50, 4), b.copyOfRange(92, 96))
        assertContentEquals(be(6881, 2), b.copyOfRange(96, 98))
    }

    @Test
    fun theUdpEventCodesAreNoneCompletedStartedStopped() {
        fun code(e: AnnounceEvent?) = announceRequest(1, 1, req(e)).copyOfRange(80, 84).let { it[3].toInt() }
        assertEquals(0, code(null))
        assertEquals(1, code(AnnounceEvent.Completed))
        assertEquals(2, code(AnnounceEvent.Started))
        assertEquals(3, code(AnnounceEvent.Stopped))
    }

    @Test
    fun aUdpAnnounceReplyGivesIntervalAndPeers() {
        val reply = be(1, 4) + be(5, 4) + be(1_200, 4) + be(3, 4) + be(9, 4) + ip(1, 2, 3, 4, 6881) + ip(5, 6, 7, 8, 80)
        val r = assertIs<UdpReply.Ok<AnnounceResult>>(parseUdpAnnounce(reply, 5)).value
        assertEquals(listOf(PeerAddr("1.2.3.4", 6881), PeerAddr("5.6.7.8", 80)), r.peers)
        assertEquals(1_200, r.intervalS)
        assertNull(r.minIntervalS)
    }

    @Test
    fun aUdpAnnounceReplyWithNoPeersIsStillAnAnswer() {
        val r = assertIs<UdpReply.Ok<AnnounceResult>>(parseUdpAnnounce(be(1, 4) + be(5, 4) + be(60, 4) + be(0, 4) + be(0, 4), 5)).value
        assertEquals(emptyList(), r.peers)
        assertEquals(60, r.intervalS)
    }

    @Test
    fun aUdpAnnounceReplyDropsPartialAndUnusableEntriesAndBoundsTheInterval() {
        val head = be(1, 4) + be(5, 4)
        val body = ip(0, 0, 0, 0, 80) + ip(1, 2, 3, 4, 0) + ip(9, 9, 9, 9, 99) + byteArrayOf(1, 2, 3)
        val r = assertIs<UdpReply.Ok<AnnounceResult>>(parseUdpAnnounce(head + be(0xFFFFFFFFL, 4) + be(0, 4) + be(0, 4) + body, 5)).value
        assertEquals(listOf(PeerAddr("9.9.9.9", 99)), r.peers)
        assertEquals(86_400, r.intervalS, "the interval is unsigned: 2^32 - 1 seconds is far over the cap")
        val big = assertIs<UdpReply.Ok<AnnounceResult>>(parseUdpAnnounce(head + be(0x7FFFFFFF, 4) + be(0, 8), 5)).value
        assertEquals(86_400, big.intervalS)
    }

    @Test
    fun aUdpAnnounceIntervalOfTwoToThe31OrMoreIsClampedNotTreatedAsMissing() {
        val head = be(1, 4) + be(5, 4)
        for (interval in listOf(0x80000000L, 0x80000001L, 0xFFFFFFFEL)) {
            val r = assertIs<UdpReply.Ok<AnnounceResult>>(parseUdpAnnounce(head + be(interval, 4) + be(0, 8), 5)).value
            assertEquals(86_400, r.intervalS, "interval $interval")
        }
        val none = assertIs<UdpReply.Ok<AnnounceResult>>(parseUdpAnnounce(head + be(0, 4) + be(0, 8), 5)).value
        assertEquals(1_800, none.intervalS, "zero still means the default")
    }

    @Test
    fun aUdpAnnounceReplyThatIsNotOursIsIgnored() {
        val good = be(1, 4) + be(5, 4) + be(60, 4) + be(0, 8)
        assertNotNull(parseUdpAnnounce(good, 5) as? UdpReply.Ok<*>)
        assertEquals(UdpReply.Ignored, parseUdpAnnounce(good, 6))
        assertEquals(UdpReply.Ignored, parseUdpAnnounce(good.copyOf(19), 5))
        assertEquals(UdpReply.Ignored, parseUdpAnnounce(ByteArray(0), 5))
        assertEquals(
            UdpReply.Ignored,
            parseUdpAnnounce(be(0, 4) + good.copyOfRange(4, good.size), 5),
            "a connect reply is not an announce reply",
        )
    }

    @Test
    fun aHugeUdpDatagramIsBoundedByTheCapOnPeers() {
        val body = concat(List(5_000) { ip(1, (it shr 8) and 0xFF, it and 0xFF, 1, 80) })
        val r = assertIs<UdpReply.Ok<AnnounceResult>>(parseUdpAnnounce(be(1, 4) + be(5, 4) + be(60, 4) + be(0, 8) + body, 5)).value
        assertEquals(MAX_ANNOUNCE_PEERS, r.peers.size)
    }
}
