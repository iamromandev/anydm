package dev.anydm.engine.os

import dev.anydm.engine.log.Log
import dev.anydm.engine.log.LogLevel
import dev.anydm.engine.log.RecordingLogSink
import dev.anydm.engine.platform.ShiftedClock
import dev.anydm.engine.platform.virtualClock
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** The app's side of pairing a browser: codes, proofs, sessions and signed requests, on virtual time. */
class BridgePairingTest {
    private class Fixture(
        scope: TestScope,
        val store: PairStore = InMemoryPairStore(),
        seed: Int = 7,
        val sink: RecordingLogSink = RecordingLogSink(),
        equal: (ByteArray, ByteArray) -> Boolean = ::constantTimeEquals,
        /** The wall clock; a test may move it without moving the monotonic time. */
        val clock: Clock = scope.virtualClock(),
    ) {
        val pairing =
            BridgePairing(
                store,
                clock,
                scope.testScheduler.timeSource,
                Random(seed),
                Log(sink, clock, minLevel = LogLevel.DEBUG),
                equal,
            )

        /** Pairs [peer] with a fresh code; the reply to the right proof. */
        suspend fun pair(
            peer: PairingPeer = PairingPeer(),
            userAgent: String? = "Mozilla/5.0 (Macintosh) Chrome/130.0.0.0 Safari/537.36",
        ): PairReply.Paired {
            val code = pairing.startPairing().code
            val attempt = peer.attempt(code)
            val reply = pairing.pair(ORIGIN, userAgent, attempt.nonceE, attempt.proofE, peer.port).shouldBeInstanceOf<PairReply.Paired>()
            peer.accept(code, attempt, reply) shouldBe true
            return reply
        }

        /** A session for [peer], which has paired already. */
        suspend fun hello(peer: PairingPeer) {
            val request = peer.startHello()
            peer.acceptHello(
                pairing.hello(request.id, request.nonceE, request.proof, peer.port).shouldBeInstanceOf<HelloReply.Ok>(),
            ) shouldBe
                true
        }

        suspend fun send(
            peer: PairingPeer,
            path: String = "/add",
            body: String = """{"url":"https://example.com/a"}""",
            counter: Long? = null,
        ): AuthReply {
            val bytes = body.encodeToByteArray()
            val signed = if (counter == null) peer.sign(path, bytes) else peer.sign(path, bytes, counter)
            return pairing.authenticate(signed.id, signed.counter, signed.mac, path, bytes, peer.port)
        }
    }

    private fun pairingTest(
        seed: Int = 7,
        store: PairStore = InMemoryPairStore(),
        equal: (ByteArray, ByteArray) -> Boolean = ::constantTimeEquals,
        body: suspend TestScope.(Fixture) -> Unit,
    ) = runTest { body(Fixture(this, store, seed, equal = equal)) }

    // ---- the code ----

    @Test
    fun aCodeIsSixteenCrockfordCharactersThatRunOutAfterTwoMinutes() =
        pairingTest { f ->
            val offer = f.pairing.startPairing()

            offer.code.length shouldBe 16
            offer.code.all { it in PairingProtocol.ALPHABET } shouldBe true
            offer.expiresAt shouldBe f.clock.now() + 2.minutes
        }

    @Test
    fun aCodeIsShownInGroupsOfFourAndTheGroupedFormIsAcceptedBack() =
        pairingTest { f ->
            val offer = f.pairing.startPairing()

            offer.grouped shouldBe offer.code.chunked(4).joinToString("-")
            offer.grouped.split("-").map { it.length } shouldBe listOf(4, 4, 4, 4)
            PairingProtocol.normaliseCode(offer.grouped) shouldBe offer.code
            PairingProtocol.normaliseCode(offer.code.chunked(4).joinToString(" ")) shouldBe offer.code
        }

    @Test
    fun theGroupedCodeIsNotPrintedEither() =
        pairingTest { f ->
            val offer = f.pairing.startPairing()

            offer.toString() shouldNotContain offer.grouped
        }

    @Test
    fun everyCharacterOfTheAlphabetCanBeDrawn() =
        runTest {
            val pairing = BridgePairing(InMemoryPairStore(), virtualClock(), testScheduler.timeSource)

            val seen = (1..400).flatMap { pairing.startPairing().code.toList() }.toSet()

            seen shouldBe PairingProtocol.ALPHABET.toSet()
        }

    @Test
    fun theCodesDifferAndTheCryptographicSourceMakesWellFormedOnes() =
        runTest {
            val pairing = BridgePairing(InMemoryPairStore(), virtualClock(), testScheduler.timeSource)

            val codes = (1..20).map { pairing.startPairing().code }.toSet()

            codes.size shouldBe 20
            codes.forEach { code -> PairingProtocol.normaliseCode(code) shouldBe code }
            codes.forEach { it.length shouldBe 16 }
        }

    @Test
    fun aNewCodeMakesTheOldOneUseless() =
        pairingTest { f ->
            val peer = PairingPeer()
            val old = f.pairing.startPairing().code
            f.pairing.startPairing()
            val attempt = peer.attempt(old)

            f.pairing.pair(ORIGIN, UA, attempt.nonceE, attempt.proofE, PORT) shouldBe PairReply.WrongProof
        }

    @Test
    fun theOfferNeverPrintsItsCode() =
        pairingTest { f ->
            val offer = f.pairing.startPairing()

            offer.toString() shouldNotContain offer.code
        }

    // ---- pairing ----

    @Test
    fun theRightProofPairsAndBothSidesEndUpWithTheSameSecret() =
        pairingTest { f ->
            val peer = PairingPeer()
            val reply = f.pair(peer)

            reply.id.length shouldBe 32
            reply.nonceA.length shouldBe 32
            peer.id shouldBe reply.id
            f.pairing.listed().map { it.id } shouldContainExactly listOf(reply.id)
            // The stored secret is the one the extension derived.
            f.store
                .load()
                .single()
                .secret shouldBe peer.secret
        }

    @Test
    fun aPairedBrowserIsListedWithItsLabelAndTimes() =
        pairingTest { f ->
            val reply = f.pair(userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) Gecko/20100101 Firefox/131.0")

            val listed = f.pairing.listed().single()

            listed.id shouldBe reply.id
            listed.label shouldBe "Firefox 131 (macOS)"
            listed.createdAt shouldBe f.clock.now()
            listed.lastUsed shouldBe f.clock.now()
        }

    @Test
    fun aWrongProofIsRefusedAndPairsNothing() =
        pairingTest { f ->
            f.pairing.startPairing()
            val attempt = PairingPeer().attempt(WRONG_CODE)

            f.pairing.pair(ORIGIN, UA, attempt.nonceE, attempt.proofE, PORT) shouldBe PairReply.WrongProof

            f.pairing.listed() shouldBe emptyList()
            f.store.load() shouldBe emptyList()
        }

    @Test
    fun withoutACodeNothingPairs() =
        pairingTest { f ->
            val attempt = PairingPeer().attempt(WRONG_CODE)

            f.pairing.pair(ORIGIN, UA, attempt.nonceE, attempt.proofE, PORT) shouldBe PairReply.NoCode
        }

    @Test
    fun aCodeThatRanOutIsNoCode() =
        pairingTest { f ->
            val peer = PairingPeer()
            val code = f.pairing.startPairing().code
            val attempt = peer.attempt(code)
            advanceTimeBy(2.minutes)

            f.pairing.pair(ORIGIN, UA, attempt.nonceE, attempt.proofE, PORT) shouldBe PairReply.NoCode
            f.pairing.listed() shouldBe emptyList()
        }

    @Test
    fun aCodeStillWorksJustBeforeItRunsOut() =
        pairingTest { f ->
            val peer = PairingPeer()
            val code = f.pairing.startPairing().code
            val attempt = peer.attempt(code)
            advanceTimeBy(2.minutes - 1.seconds)

            f.pairing.pair(ORIGIN, UA, attempt.nonceE, attempt.proofE, PORT).shouldBeInstanceOf<PairReply.Paired>()
        }

    @Test
    fun aCodeWorksOnce() =
        pairingTest { f ->
            val peer = PairingPeer()
            val code = f.pairing.startPairing().code
            val attempt = peer.attempt(code)
            f.pairing.pair(ORIGIN, UA, attempt.nonceE, attempt.proofE, PORT).shouldBeInstanceOf<PairReply.Paired>()

            // The very same request again, and a new one made with the same code.
            f.pairing.pair(ORIGIN, UA, attempt.nonceE, attempt.proofE, PORT) shouldBe PairReply.NoCode
            val again = PairingPeer(2).attempt(code)
            f.pairing.pair(ORIGIN, UA, again.nonceE, again.proofE, PORT) shouldBe PairReply.NoCode
            f.pairing.listed().size shouldBe 1
        }

    @Test
    fun fiveWrongTriesBurnTheCode() =
        pairingTest { f ->
            val peer = PairingPeer()
            val code = f.pairing.startPairing().code
            repeat(5) {
                val wrong = peer.attempt(WRONG_CODE)
                f.pairing.pair(ORIGIN, UA, wrong.nonceE, wrong.proofE, PORT) shouldBe PairReply.WrongProof
                advanceTimeBy(7.seconds)
            }

            val right = peer.attempt(code)
            f.pairing.pair(ORIGIN, UA, right.nonceE, right.proofE, PORT) shouldBe PairReply.NoCode
            f.pairing.listed() shouldBe emptyList()
        }

    @Test
    fun fourWrongTriesDoNotBurnIt() =
        pairingTest { f ->
            val peer = PairingPeer()
            val code = f.pairing.startPairing().code
            repeat(4) {
                val wrong = peer.attempt(WRONG_CODE)
                f.pairing.pair(ORIGIN, UA, wrong.nonceE, wrong.proofE, PORT) shouldBe PairReply.WrongProof
            }

            val right = peer.attempt(code)
            f.pairing.pair(ORIGIN, UA, right.nonceE, right.proofE, PORT).shouldBeInstanceOf<PairReply.Paired>()
        }

    @Test
    fun aBurnedCodeDoesNotBurnTheNextOne() =
        pairingTest { f ->
            val peer = PairingPeer()
            f.pairing.startPairing()
            repeat(5) {
                val wrong = peer.attempt(WRONG_CODE)
                f.pairing.pair(ORIGIN, UA, wrong.nonceE, wrong.proofE, PORT)
                advanceTimeBy(7.seconds)
            }

            f.pair(peer)
        }

    @Test
    fun tenTriesAMinuteAreAllowedAndTheElevenththIsThrottledWhateverTheCode() =
        pairingTest { f ->
            val peer = PairingPeer()
            // Ten tries, each on a code of its own so that no code is burned.
            repeat(10) {
                f.pairing.startPairing()
                val wrong = peer.attempt(WRONG_CODE)
                f.pairing.pair(ORIGIN, UA, wrong.nonceE, wrong.proofE, PORT) shouldBe PairReply.WrongProof
            }

            val code = f.pairing.startPairing().code
            val right = peer.attempt(code)
            val refused = f.pairing.pair(ORIGIN, UA, right.nonceE, right.proofE, PORT).shouldBeInstanceOf<PairReply.Throttled>()

            refused.retryAfter shouldBeGreaterThan 0.seconds
            f.pairing.listed() shouldBe emptyList()
            // A throttled try does not count against the code.
            advanceTimeBy(1.minutes)
            f.pairing.pair(ORIGIN, UA, right.nonceE, right.proofE, PORT).shouldBeInstanceOf<PairReply.Paired>()
        }

    @Test
    fun triesWhileNoCodeIsWaitingDoNotCountTowardTheLimit() =
        pairingTest { f ->
            val flood = PairingPeer().attempt(WRONG_CODE)
            repeat(30) { f.pairing.pair(ORIGIN, UA, flood.nonceE, flood.proofE, PORT) shouldBe PairReply.NoCode }

            // Whoever starts a pairing right after such a flood is not locked out by it.
            f.pair()
        }

    @Test
    fun triesAfterACodeWasBurnedDoNotCountEither() =
        pairingTest { f ->
            val peer = PairingPeer()
            f.pairing.startPairing()
            repeat(5) {
                val wrong = peer.attempt(WRONG_CODE)
                f.pairing.pair(ORIGIN, UA, wrong.nonceE, wrong.proofE, PORT) shouldBe PairReply.WrongProof
            }
            val flood = peer.attempt(WRONG_CODE)
            repeat(30) { f.pairing.pair(ORIGIN, UA, flood.nonceE, flood.proofE, PORT) shouldBe PairReply.NoCode }

            f.pair(peer)
        }

    @Test
    fun theLimitIsAMovingMinute() =
        pairingTest { f ->
            val attempt = PairingPeer().attempt(WRONG_CODE)
            repeat(10) {
                f.pairing.startPairing()
                f.pairing.pair(ORIGIN, UA, attempt.nonceE, attempt.proofE, PORT) shouldBe PairReply.WrongProof
                advanceTimeBy(5.seconds)
            }
            f.pairing.startPairing()
            // 50 s passed: the first try is still inside the minute.
            f.pairing.pair(ORIGIN, UA, attempt.nonceE, attempt.proofE, PORT).shouldBeInstanceOf<PairReply.Throttled>()

            advanceTimeBy(10.seconds)

            f.pairing.pair(ORIGIN, UA, attempt.nonceE, attempt.proofE, PORT) shouldBe PairReply.WrongProof
        }

    @Test
    fun throttlingIsLoggedOncePerWindowNotOncePerRequest() =
        pairingTest { f ->
            val attempt = PairingPeer().attempt(WRONG_CODE)
            repeat(10) {
                f.pairing.startPairing()
                f.pairing.pair(ORIGIN, UA, attempt.nonceE, attempt.proofE, PORT)
            }
            f.pairing.startPairing()

            repeat(30) { f.pairing.pair(ORIGIN, UA, attempt.nonceE, attempt.proofE, PORT).shouldBeInstanceOf<PairReply.Throttled>() }

            f.sink.lines.count { "too fast" in it } shouldBe 1

            // The window runs out; a new flood is news again.
            advanceTimeBy(1.minutes)
            repeat(10) {
                f.pairing.startPairing()
                f.pairing.pair(ORIGIN, UA, attempt.nonceE, attempt.proofE, PORT)
            }
            repeat(30) { f.pairing.pair(ORIGIN, UA, attempt.nonceE, attempt.proofE, PORT).shouldBeInstanceOf<PairReply.Throttled>() }

            f.sink.lines.count { "too fast" in it } shouldBe 2
        }

    @Test
    fun aRequestThatIsNotAPairingRequestIsMalformed() =
        pairingTest { f ->
            f.pairing.startPairing()
            val good = PairingPeer().attempt(WRONG_CODE)
            val cases =
                listOf(
                    null to good.proofE,
                    good.nonceE to null,
                    "short" to good.proofE,
                    good.nonceE.uppercase() to good.proofE,
                    good.nonceE to "00",
                    good.nonceE to good.proofE.uppercase(),
                    good.nonceE to "zz".repeat(32),
                )
            for ((nonce, proof) in cases) {
                f.pairing.pair(ORIGIN, UA, nonce, proof, PORT) shouldBe PairReply.Malformed
            }
        }

    @Test
    fun aMalformedRequestIsNotCountedAsATry() =
        pairingTest { f ->
            repeat(30) { f.pairing.pair(ORIGIN, UA, "x", "y", PORT) shouldBe PairReply.Malformed }

            f.pair()
        }

    @Test
    fun aPairingThatCannotBeSavedIsNotConfirmedAndTheCodeStaysGood() =
        runTest {
            val store =
                object : PairStore {
                    var failing = true
                    private var records = emptyList<PairRecord>()

                    override fun load() = records

                    override fun save(records: List<PairRecord>) {
                        if (failing) throw IOException("disk full")
                        this.records = records
                    }
                }
            val f = Fixture(this, store)
            val peer = PairingPeer()
            val code = f.pairing.startPairing().code
            val attempt = peer.attempt(code)

            f.pairing.pair(ORIGIN, UA, attempt.nonceE, attempt.proofE, PORT) shouldBe PairReply.NotSaved
            f.pairing.listed() shouldBe emptyList()

            store.failing = false
            f.pairing.pair(ORIGIN, UA, attempt.nonceE, attempt.proofE, PORT).shouldBeInstanceOf<PairReply.Paired>()
        }

    @Test
    fun twoPairingsAtOnceWithOneCodeMakeOnePairing() =
        pairingTest { f ->
            val code = f.pairing.startPairing().code

            val replies =
                (1..8)
                    .map { n ->
                        async {
                            val attempt = PairingPeer(n).attempt(code)
                            f.pairing.pair(ORIGIN, UA, attempt.nonceE, attempt.proofE, PORT)
                        }
                    }.awaitAll()

            replies.count { it is PairReply.Paired } shouldBe 1
            f.pairing.listed().size shouldBe 1
        }

    // ---- a peer that does not have the code ----

    @Test
    fun anAppThatDidNotSeeTheCodeCannotMakeProofAAndSoTheExtensionRefusesIt() =
        pairingTest { f ->
            val peer = PairingPeer()
            val code = f.pairing.startPairing().code
            val attempt = peer.attempt(code)
            val real = f.pairing.pair(ORIGIN, UA, attempt.nonceE, attempt.proofE, PORT).shouldBeInstanceOf<PairReply.Paired>()

            // A spoofer on another port got the same request and answers with what it can make: a guess at the code.
            val spoof =
                PairReply.Paired(
                    real.id,
                    real.nonceA,
                    PairingProtocol.appProof(WRONG_CODE, attempt.nonceE, real.nonceA, PORT),
                )

            PairingPeer().accept(code, attempt, spoof) shouldBe false
            PairingPeer().accept(code, attempt, real) shouldBe true
        }

    @Test
    fun anAppThatDoesNotHoldTheSecretCannotAnswerHelloAndTheExtensionRefusesIt() =
        pairingTest { f ->
            val peer = PairingPeer()
            f.pair(peer)
            val request = peer.startHello()
            val spoof =
                HelloReply.Ok(
                    peer.nonce(),
                    PairingProtocol.helloProof("00".repeat(32), request.nonceE, "00".repeat(16), PORT),
                )

            peer.acceptHello(spoof) shouldBe false
            peer.acceptHello(f.pairing.hello(request.id, request.nonceE, request.proof, PORT).shouldBeInstanceOf<HelloReply.Ok>()) shouldBe
                true
        }

    // ---- the port is in every proof (C1) ----

    @Test
    fun aProofMadeForAnotherPortIsNotTheCodesProofHere() =
        pairingTest { f ->
            val code = f.pairing.startPairing().code
            val other = PairingPeer(port = PORT + 1).attempt(code)

            f.pairing.pair(ORIGIN, UA, other.nonceE, other.proofE, PORT) shouldBe PairReply.WrongProof
            f.pairing.listed() shouldBe emptyList()
        }

    @Test
    fun aRelayOnAnotherPortCannotForwardAPairingToTheRealApp() =
        pairingTest { f ->
            // The extension dials the relay's port and proves the code for that port; the relay passes the request on.
            val extension = PairingPeer(port = PORT + 1)
            val code = f.pairing.startPairing().code
            val attempt = extension.attempt(code)

            f.pairing.pair(ORIGIN, UA, attempt.nonceE, attempt.proofE, PORT) shouldBe PairReply.WrongProof

            // Nothing was paired and the relay holds nothing it could use: the real code still works, on the real port.
            f.pairing.listed() shouldBe emptyList()
            f.pair(PairingPeer(2))
        }

    @Test
    fun aPairedReplyMadeForOnePortIsRefusedByAnExtensionThatDialedAnother() =
        pairingTest { f ->
            val code = f.pairing.startPairing().code
            val onRealPort = PairingPeer(port = PORT).attempt(code)
            val real = f.pairing.pair(ORIGIN, UA, onRealPort.nonceE, onRealPort.proofE, PORT).shouldBeInstanceOf<PairReply.Paired>()

            // The same nonce on the relay's port: the proof A the real app made does not verify there.
            PairingPeer(port = PORT + 1).accept(code, onRealPort, real) shouldBe false
            PairingPeer(port = PORT).accept(code, onRealPort, real) shouldBe true
        }

    @Test
    fun aHelloProofMadeForOnePortIsRefusedByAnExtensionThatDialedAnother() =
        pairingTest { f ->
            val peer = PairingPeer(port = PORT)
            f.pair(peer)
            val request = peer.startHello()
            val reply = f.pairing.hello(request.id, request.nonceE, request.proof, PORT).shouldBeInstanceOf<HelloReply.Ok>()
            val secret =
                f.store
                    .load()
                    .single()
                    .secret

            // What an extension that dialed PORT + 1 would compute to check it: not what the app made.
            reply.proof shouldBe PairingProtocol.helloProof(secret, request.nonceE, reply.nonceA, PORT)
            reply.proof shouldNotBe PairingProtocol.helloProof(secret, request.nonceE, reply.nonceA, PORT + 1)
        }

    @Test
    fun aHelloMadeForAnotherPortIsUnpaired() =
        pairingTest { f ->
            val peer = PairingPeer(port = PORT)
            f.pair(peer)
            val request = peer.startHello()

            f.pairing.hello(request.id, request.nonceE, request.proof, PORT + 1) shouldBe HelloReply.Unpaired
            f.send(peer) shouldBe AuthReply.SessionExpired
        }

    @Test
    fun aRequestSignedForAnotherPortIsRefused() =
        pairingTest { f ->
            val peer = PairingPeer(port = PORT)
            f.pair(peer)
            f.hello(peer)
            val body = "{}".encodeToByteArray()
            val signed = peer.sign("/add", body)

            f.pairing.authenticate(signed.id, signed.counter, signed.mac, "/add", body, PORT + 1) shouldBe AuthReply.BadMac
            f.pairing.authenticate(signed.id, signed.counter, signed.mac, "/add", body, PORT) shouldBe AuthReply.Ok(peer.id)
        }

    @Test
    fun theSecretDoesNotDependOnThePort() =
        pairingTest { f ->
            val code = f.pairing.startPairing().code
            val attempt = PairingPeer().attempt(code)
            val real = f.pairing.pair(ORIGIN, UA, attempt.nonceE, attempt.proofE, PORT).shouldBeInstanceOf<PairReply.Paired>()

            PairingProtocol.secret(code, attempt.nonceE, real.nonceA) shouldBe
                f.store
                    .load()
                    .single()
                    .secret
        }

    // ---- hello and signed requests ----

    @Test
    fun helloStartsASessionThatTheExtensionCanVerify() =
        pairingTest { f ->
            val peer = PairingPeer()
            f.pair(peer)

            f.hello(peer)
        }

    @Test
    fun helloForAnIdThatIsNotPairedIsUnpaired() =
        pairingTest { f ->
            f.pair()
            val guess = PairingPeer(3)

            val request = guess.startHello("0".repeat(32))

            f.pairing.hello(request.id, request.nonceE, request.proof, PORT) shouldBe HelloReply.Unpaired
        }

    @Test
    fun helloThatIsNotAHelloIsMalformed() =
        pairingTest { f ->
            val peer = PairingPeer()
            f.pair(peer)
            val request = peer.startHello()

            f.pairing.hello(null, request.nonceE, request.proof, PORT) shouldBe HelloReply.Malformed
            f.pairing.hello(request.id, null, request.proof, PORT) shouldBe HelloReply.Malformed
            f.pairing.hello(request.id, "xyz", request.proof, PORT) shouldBe HelloReply.Malformed
            f.pairing.hello("../etc", request.nonceE, request.proof, PORT) shouldBe HelloReply.Malformed
            f.pairing.hello(request.id, request.nonceE, null, PORT) shouldBe HelloReply.Malformed
            f.pairing.hello(request.id, request.nonceE, "00", PORT) shouldBe HelloReply.Malformed
            f.pairing.hello(request.id, request.nonceE, request.proof.uppercase(), PORT) shouldBe HelloReply.Malformed
        }

    // ---- hello proves the extension holds the secret (S1) ----

    @Test
    fun helloWithAWrongProofIsUnpairedAndStartsNoSession() =
        pairingTest { f ->
            val peer = PairingPeer()
            f.pair(peer)
            val request = peer.startHello()
            val flipped = (if (request.proof.first() == '0') "1" else "0") + request.proof.drop(1)

            f.pairing.hello(request.id, request.nonceE, flipped, PORT) shouldBe HelloReply.Unpaired

            f.send(peer) shouldBe AuthReply.SessionExpired
        }

    @Test
    fun helloWithAProofForAnotherNonceOrAnotherIdIsUnpaired() =
        pairingTest { f ->
            val one = PairingPeer(1)
            val two = PairingPeer(2)
            f.pair(one)
            f.pair(two)
            val mine = one.startHello()
            val other = one.startHello()

            // A proof taken from another hello, and one made with another browser's secret.
            f.pairing.hello(mine.id, other.nonceE, mine.proof, PORT) shouldBe HelloReply.Unpaired
            f.pairing.hello(mine.id, mine.nonceE, two.startHello(mine.id).proof, PORT) shouldBe HelloReply.Unpaired
            f.pairing.hello(two.id, mine.nonceE, mine.proof, PORT) shouldBe HelloReply.Unpaired
        }

    @Test
    fun anUnknownIdAndABadProofAreOneAnswer() =
        pairingTest { f ->
            val peer = PairingPeer()
            f.pair(peer)
            val unknown = PairingPeer(9).startHello("0".repeat(32))
            val bad = peer.startHello().let { PairingPeer.Hello(it.id, it.nonceE, "0".repeat(64)) }

            f.pairing.hello(unknown.id, unknown.nonceE, unknown.proof, PORT) shouldBe
                f.pairing.hello(bad.id, bad.nonceE, bad.proof, PORT)
        }

    @Test
    fun aHelloWithoutAValidProofDoesNotTouchLastUsed() =
        pairingTest { f ->
            val peer = PairingPeer()
            f.pair(peer)
            val paired = f.clock.now()
            advanceTimeBy(5.minutes)
            val request = peer.startHello()

            f.pairing.hello(request.id, request.nonceE, "0".repeat(64), PORT) shouldBe HelloReply.Unpaired
            f.pairing
                .listed()
                .single()
                .lastUsed shouldBe paired
            f.store
                .load()
                .single()
                .lastUsed shouldBe paired

            f.pairing.hello(request.id, request.nonceE, request.proof, PORT).shouldBeInstanceOf<HelloReply.Ok>()
            f.pairing
                .listed()
                .single()
                .lastUsed shouldBe f.clock.now()
        }

    @Test
    fun aHelloThatReplacesNothingWhenItsProofIsWrongLeavesTheLiveSessionAlone() =
        pairingTest { f ->
            val peer = PairingPeer()
            f.pair(peer)
            f.hello(peer)
            val forged = PairingPeer(4).startHello(peer.id)

            f.pairing.hello(forged.id, forged.nonceE, forged.proof, PORT) shouldBe HelloReply.Unpaired

            f.send(peer) shouldBe AuthReply.Ok(peer.id)
        }

    @Test
    fun aSecondHelloEndsTheFirstSessionOfThatBrowser() =
        pairingTest { f ->
            // One session per id: the request headers carry the id and nothing else, so a browser has one session at a time.
            val peer = PairingPeer()
            f.pair(peer)
            f.hello(peer)
            val first = peer.sign("/add", "{}".encodeToByteArray())
            f.hello(peer)

            f.pairing.authenticate(first.id, first.counter, first.mac, "/add", "{}".encodeToByteArray(), PORT) shouldBe AuthReply.BadMac
            f.send(peer, counter = 1) shouldBe AuthReply.Ok(peer.id)
        }

    // ---- a replayed hello (F4): nonceE is not tracked, so the same hello can be sent again ----

    @Test
    fun aReplayedHelloIsOkWithAFreshNonceAndTheOldSessionsMacFails() =
        pairingTest { f ->
            val peer = PairingPeer()
            f.pair(peer)
            val request = peer.startHello()
            val first = f.pairing.hello(request.id, request.nonceE, request.proof, PORT).shouldBeInstanceOf<HelloReply.Ok>()
            peer.acceptHello(first) shouldBe true
            f.send(peer) shouldBe AuthReply.Ok(peer.id)
            val body = "{}".encodeToByteArray()
            // The old session's next request, signed before the replay and sent after it.
            val old = peer.sign("/add", body)

            // The very same request again (an eavesdropper's copy, or a retry): answered like any hello, with a nonce of its own.
            val replayed = f.pairing.hello(request.id, request.nonceE, request.proof, PORT).shouldBeInstanceOf<HelloReply.Ok>()

            replayed.nonceA shouldNotBe first.nonceA
            // Its proof is a proof for the new nonce: nobody but the app (which holds the secret) could have made it, and the
            // eavesdropper, who has no secret, can not use the session it opened: it can only have cut the live one.
            replayed.proof shouldBe PairingProtocol.helloProof(peer.secret, request.nonceE, replayed.nonceA, PORT)
            f.pairing.authenticate(old.id, old.counter, old.mac, "/add", body, PORT) shouldBe AuthReply.BadMac
            // The browser says hello again (a new nonceE) and is served.
            f.hello(peer)
            f.send(peer) shouldBe AuthReply.Ok(peer.id)
        }

    @Test
    fun aReplayedHelloGivesTheReplayerNoSessionItCanSignIn() =
        pairingTest { f ->
            val peer = PairingPeer()
            f.pair(peer)
            val request = peer.startHello()
            f.pairing.hello(request.id, request.nonceE, request.proof, PORT).shouldBeInstanceOf<HelloReply.Ok>()

            val replayed = f.pairing.hello(request.id, request.nonceE, request.proof, PORT).shouldBeInstanceOf<HelloReply.Ok>()

            // Whoever sent the copy knows the new nonceA (it is in the answer) but not the secret: every MAC it can make is wrong.
            val forged = PairingProtocol.requestMac("00".repeat(32), replayed.nonceA, 1, "/add", "{}".encodeToByteArray(), PORT)
            f.pairing.authenticate(peer.id, "1", forged, "/add", "{}".encodeToByteArray(), PORT) shouldBe AuthReply.BadMac
        }

    @Test
    fun aSignedRequestIsAccepted() =
        pairingTest { f ->
            val peer = PairingPeer()
            f.pair(peer)
            f.hello(peer)

            f.send(peer) shouldBe AuthReply.Ok(peer.id)
            f.send(peer, "/confirm", """{"token":"x"}""") shouldBe AuthReply.Ok(peer.id)
        }

    @Test
    fun aRequestWithNoCredentialsIsAnonymous() =
        pairingTest { f ->
            f.pairing.authenticate(null, null, null, "/add", "{}".encodeToByteArray(), PORT) shouldBe AuthReply.Anonymous
        }

    @Test
    fun partOfTheCredentialsIsNotAnonymousButMalformed() =
        pairingTest { f ->
            val peer = PairingPeer()
            f.pair(peer)
            f.hello(peer)
            val body = "{}".encodeToByteArray()
            val signed = peer.sign("/add", body)

            f.pairing.authenticate(signed.id, null, null, "/add", body, PORT) shouldBe AuthReply.Malformed
            f.pairing.authenticate(null, signed.counter, signed.mac, "/add", body, PORT) shouldBe AuthReply.Malformed
            f.pairing.authenticate(signed.id, signed.counter, null, "/add", body, PORT) shouldBe AuthReply.Malformed
        }

    @Test
    fun aWrongMacIsRefused() =
        pairingTest { f ->
            val peer = PairingPeer()
            f.pair(peer)
            f.hello(peer)
            val body = "{}".encodeToByteArray()
            val signed = peer.sign("/add", body)
            val flipped = (if (signed.mac.first() == '0') "1" else "0") + signed.mac.drop(1)

            f.pairing.authenticate(signed.id, signed.counter, flipped, "/add", body, PORT) shouldBe AuthReply.BadMac
        }

    @Test
    fun aMacMadeForAnotherBodyOrPathOrCounterIsRefused() =
        pairingTest { f ->
            val peer = PairingPeer()
            f.pair(peer)
            f.hello(peer)
            val body = """{"url":"https://example.com/a"}""".encodeToByteArray()
            val signed = peer.sign("/add", body, counter = 5)

            f.pairing.authenticate(
                signed.id,
                signed.counter,
                signed.mac,
                "/add",
                """{"url":"https://example.com/b"}""".encodeToByteArray(),
                PORT,
            ) shouldBe
                AuthReply.BadMac
            f.pairing.authenticate(signed.id, signed.counter, signed.mac, "/confirm", body, PORT) shouldBe AuthReply.BadMac
            f.pairing.authenticate(signed.id, "6", signed.mac, "/add", body, PORT) shouldBe AuthReply.BadMac
            f.pairing.authenticate(signed.id, signed.counter, signed.mac, "/add", body, PORT) shouldBe AuthReply.Ok(peer.id)
        }

    @Test
    fun aMacMadeWithAnotherSecretIsRefused() =
        pairingTest { f ->
            val peer = PairingPeer()
            f.pair(peer)
            f.hello(peer)
            val impostor = PairingPeer(9)
            f.pair(impostor)
            f.hello(impostor)
            val body = "{}".encodeToByteArray()
            val theirs = impostor.sign("/add", body)

            // The impostor's MAC under the first browser's id.
            f.pairing.authenticate(peer.id, theirs.counter, theirs.mac, "/add", body, PORT) shouldBe AuthReply.BadMac
        }

    @Test
    fun aRepeatedCounterIsAReplay() =
        pairingTest { f ->
            val peer = PairingPeer()
            f.pair(peer)
            f.hello(peer)
            val body = "{}".encodeToByteArray()
            val signed = peer.sign("/add", body)
            f.pairing.authenticate(signed.id, signed.counter, signed.mac, "/add", body, PORT) shouldBe AuthReply.Ok(peer.id)

            f.pairing.authenticate(signed.id, signed.counter, signed.mac, "/add", body, PORT) shouldBe AuthReply.Replay
        }

    @Test
    fun aCounterBelowTheLastIsAReplayAndAHigherOneWithAGapIsFine() =
        pairingTest { f ->
            val peer = PairingPeer()
            f.pair(peer)
            f.hello(peer)

            f.send(peer, counter = 5) shouldBe AuthReply.Ok(peer.id)
            f.send(peer, counter = 3) shouldBe AuthReply.Replay
            f.send(peer, counter = 5) shouldBe AuthReply.Replay
            f.send(peer, counter = 9) shouldBe AuthReply.Ok(peer.id)
        }

    @Test
    fun aRefusedRequestDoesNotUseUpItsCounter() =
        pairingTest { f ->
            val peer = PairingPeer()
            f.pair(peer)
            f.hello(peer)
            val body = "{}".encodeToByteArray()
            val signed = peer.sign("/add", body, counter = 4)

            // Someone sends counter 4 with a forged MAC; the real request with counter 4 still goes through.
            f.pairing.authenticate(signed.id, "4", "00".repeat(32), "/add", body, PORT) shouldBe AuthReply.BadMac
            f.pairing.authenticate(signed.id, signed.counter, signed.mac, "/add", body, PORT) shouldBe AuthReply.Ok(peer.id)
        }

    @Test
    fun twoRequestsWithOneCounterAtOnceAreOneRequest() =
        pairingTest { f ->
            val peer = PairingPeer()
            f.pair(peer)
            f.hello(peer)
            val body = "{}".encodeToByteArray()
            val signed = peer.sign("/add", body)

            val replies =
                (1..8)
                    .map {
                        async {
                            f.pairing.authenticate(
                                signed.id,
                                signed.counter,
                                signed.mac,
                                "/add",
                                body,
                                PORT,
                            )
                        }
                    }.awaitAll()

            replies.count { it is AuthReply.Ok } shouldBe 1
            replies.count { it == AuthReply.Replay } shouldBe 7
        }

    @Test
    fun counterThatIsNotACounterIsMalformed() =
        pairingTest { f ->
            val peer = PairingPeer()
            f.pair(peer)
            f.hello(peer)
            val body = "{}".encodeToByteArray()
            val mac = peer.sign("/add", body).mac

            for (counter in listOf("0", "01", "-1", "1.5", "abc", "", " 1", "9007199254740992", "99999999999999999999")) {
                f.pairing.authenticate(peer.id, counter, mac, "/add", body, PORT) shouldBe AuthReply.Malformed
            }
        }

    @Test
    fun anIdOrMacOfTheWrongShapeIsMalformed() =
        pairingTest { f ->
            val peer = PairingPeer()
            f.pair(peer)
            f.hello(peer)
            val body = "{}".encodeToByteArray()
            val signed = peer.sign("/add", body)

            f.pairing.authenticate("x", signed.counter, signed.mac, "/add", body, PORT) shouldBe AuthReply.Malformed
            f.pairing.authenticate(signed.id, signed.counter, "abc", "/add", body, PORT) shouldBe AuthReply.Malformed
            f.pairing.authenticate(signed.id, signed.counter, signed.mac.uppercase(), "/add", body, PORT) shouldBe AuthReply.Malformed
        }

    @Test
    fun anIdThatIsNotPairedIsUnpaired() =
        pairingTest { f ->
            val peer = PairingPeer()
            f.pair(peer)
            f.hello(peer)
            val body = "{}".encodeToByteArray()
            val signed = peer.sign("/add", body)

            f.pairing.authenticate("f".repeat(32), signed.counter, signed.mac, "/add", body, PORT) shouldBe AuthReply.Unpaired
        }

    @Test
    fun aPairedBrowserWithNoSessionIsToldToSayHello() =
        pairingTest { f ->
            val peer = PairingPeer()
            f.pair(peer)

            f.send(peer) shouldBe AuthReply.SessionExpired
        }

    @Test
    fun aSessionLivesSixtySeconds() =
        pairingTest { f ->
            val peer = PairingPeer()
            f.pair(peer)
            f.hello(peer)
            advanceTimeBy(59.seconds)
            f.send(peer) shouldBe AuthReply.Ok(peer.id)

            advanceTimeBy(1.seconds)

            f.send(peer) shouldBe AuthReply.SessionExpired
        }

    @Test
    fun aNewHelloStartsAFreshSessionAndTheOldOnesRequestsStopWorking() =
        pairingTest { f ->
            val peer = PairingPeer()
            f.pair(peer)
            f.hello(peer)
            val body = "{}".encodeToByteArray()
            val old = peer.sign("/add", body, counter = 7)
            f.pairing.authenticate(old.id, old.counter, old.mac, "/add", body, PORT) shouldBe AuthReply.Ok(peer.id)

            f.hello(peer)

            // The old MAC was made with the old session's nonce.
            f.pairing.authenticate(old.id, "8", old.mac, "/add", body, PORT) shouldBe AuthReply.BadMac
            // The counter starts again in the new session.
            f.send(peer, counter = 1) shouldBe AuthReply.Ok(peer.id)
        }

    @Test
    fun sessionsOfTwoBrowsersAreApart() =
        pairingTest { f ->
            val one = PairingPeer(1)
            val two = PairingPeer(2)
            f.pair(one)
            f.pair(two)
            f.hello(one)
            f.hello(two)

            f.send(one) shouldBe AuthReply.Ok(one.id)
            f.send(two) shouldBe AuthReply.Ok(two.id)
            f.pairing.listed().size shouldBe 2
        }

    // ---- revoking ----

    @Test
    fun aRevokedBrowserCanNotSayHelloAgain() =
        pairingTest { f ->
            val peer = PairingPeer()
            f.pair(peer)

            f.pairing.revoke(peer.id) shouldBe RevokeResult.REVOKED

            val request = peer.startHello()
            f.pairing.hello(request.id, request.nonceE, request.proof, PORT) shouldBe HelloReply.Unpaired
            f.pairing.listed() shouldBe emptyList()
            f.store.load() shouldBe emptyList()
        }

    @Test
    fun aRevokedBrowsersLiveSessionStopsAtOnce() =
        pairingTest { f ->
            val peer = PairingPeer()
            f.pair(peer)
            f.hello(peer)
            f.send(peer) shouldBe AuthReply.Ok(peer.id)

            f.pairing.revoke(peer.id)

            f.send(peer) shouldBe AuthReply.Unpaired
        }

    @Test
    fun revokingOneBrowserLeavesTheOther() =
        pairingTest { f ->
            val one = PairingPeer(1)
            val two = PairingPeer(2)
            f.pair(one)
            f.pair(two)
            f.hello(two)

            f.pairing.revoke(one.id)

            f.pairing.listed().map { it.id } shouldContainExactly listOf(two.id)
            f.send(two) shouldBe AuthReply.Ok(two.id)
        }

    @Test
    fun revokingAnIdThatIsNotPairedIsNotFound() =
        pairingTest { f ->
            f.pair()

            f.pairing.revoke("0".repeat(32)) shouldBe RevokeResult.NOT_FOUND
            f.pairing.revoke("") shouldBe RevokeResult.NOT_FOUND
            f.pairing.listed().size shouldBe 1
        }

    @Test
    fun aRevokeThatCannotBeWrittenSaysSoButTakesEffectNow() =
        runTest {
            val store =
                object : PairStore {
                    var failing = false
                    private var records = emptyList<PairRecord>()

                    override fun load() = records

                    override fun save(records: List<PairRecord>) {
                        if (failing) throw IOException("read-only")
                        this.records = records
                    }
                }
            val f = Fixture(this, store)
            val peer = PairingPeer()
            f.pair(peer)
            store.failing = true

            f.pairing.revoke(peer.id) shouldBe RevokeResult.NOT_SAVED

            val request = peer.startHello()
            f.pairing.hello(request.id, request.nonceE, request.proof, PORT) shouldBe HelloReply.Unpaired
        }

    // ---- a store that cannot be read (S2) ----

    private fun oldRecord(): PairRecord =
        PairRecord(
            id = "1".repeat(32),
            label = "Chrome 130 (macOS)",
            secret = "ab".repeat(32),
            createdAt = kotlin.time.Instant.parse("2026-11-01T10:00:00Z"),
            lastUsed = kotlin.time.Instant.parse("2026-11-01T10:00:00Z"),
        )

    @Test
    fun oldPairingsSurviveAReadErrorThatPassesAndThePairingThatFollows() =
        runTest {
            val old = oldRecord()
            val store = FlakyPairStore(listOf(old))
            val f = Fixture(this, store)
            val peer = PairingPeer()
            val code = f.pairing.startPairing().code
            val attempt = peer.attempt(code)

            // The file can't be read now: the right proof is not confirmed, nothing is written over the old pairings.
            f.pairing.pair(ORIGIN, UA, attempt.nonceE, attempt.proofE, PORT) shouldBe PairReply.NotSaved
            store.saves shouldBe 0
            store.records.map { it.id } shouldBe listOf(old.id)

            // It can be read again: the code still works and the old pairing is still there beside the new one.
            store.failLoad = false
            advanceTimeBy(1.seconds)
            val paired = f.pairing.pair(ORIGIN, UA, attempt.nonceE, attempt.proofE, PORT).shouldBeInstanceOf<PairReply.Paired>()

            store.records.map { it.id }.toSet() shouldBe setOf(old.id, paired.id)
            f.pairing
                .listed()
                .map { it.id }
                .toSet() shouldBe setOf(old.id, paired.id)
        }

    @Test
    fun aFailedLoadIsNotRememberedAsNoPairings() =
        runTest {
            val old = oldRecord()
            val store = FlakyPairStore(listOf(old))
            val f = Fixture(this, store)

            f.pairing.browsers() shouldBe PairedBrowsers.Unavailable
            advanceTimeBy(1.seconds)
            f.pairing.browsers() shouldBe PairedBrowsers.Unavailable
            // A look a second later tried the file again.
            store.loads shouldBe 2

            store.failLoad = false
            advanceTimeBy(1.seconds)

            f.pairing.listed().map { it.id } shouldBe listOf(old.id)
            // And once it was read it is not read again.
            f.pairing.listed()
            store.loads shouldBe 3
        }

    @Test
    fun noPairedBrowsersIsAnEmptyListAndNotUnavailable() =
        runTest {
            val store = FlakyPairStore(emptyList())
            store.failLoad = false
            val f = Fixture(this, store)

            f.pairing.browsers() shouldBe PairedBrowsers.Listed(emptyList())
        }

    @Test
    fun theListNeverCarriesAnythingWhenItIsUnavailable() =
        runTest {
            val f = Fixture(this, FlakyPairStore(listOf(oldRecord())))

            f.pairing.browsers().toString() shouldNotContain oldRecord().id
            f.pairing.browsers().toString() shouldNotContain oldRecord().secret
        }

    @Test
    fun aBrowserPairedBeforeIsToldTheStoreIsUnavailableNotUnpairedWhileTheFileCannotBeReadAndServedAgainAfter() =
        runTest {
            val peer = PairingPeer()
            val store = FlakyPairStore(emptyList())
            store.failLoad = false
            val f = Fixture(this, store)
            f.pair(peer)
            val restarted = Fixture(this, store, seed = 99)
            store.failLoad = true
            val request = peer.startHello()

            restarted.pairing.hello(request.id, request.nonceE, request.proof, PORT) shouldBe HelloReply.StoreUnavailable

            store.failLoad = false
            advanceTimeBy(1.seconds)
            restarted.pairing.hello(request.id, request.nonceE, request.proof, PORT).shouldBeInstanceOf<HelloReply.Ok>()
        }

    @Test
    fun helloWhileTheFileCannotBeReadIsTheSameAnswerForEveryIdSoItTellsNothingAboutWhoIsPaired() =
        runTest {
            val store = FlakyPairStore(listOf(oldRecord()))
            val f = Fixture(this, store)
            val known = PairingPeer(3).startHello(oldRecord().id)
            val unknown = PairingPeer(4).startHello("0".repeat(32))

            f.pairing.hello(known.id, known.nonceE, known.proof, PORT) shouldBe HelloReply.StoreUnavailable
            f.pairing.hello(unknown.id, unknown.nonceE, unknown.proof, PORT) shouldBe HelloReply.StoreUnavailable
        }

    @Test
    fun helloThatIsNotAHelloIsStillMalformedWhileTheFileCannotBeRead() =
        runTest {
            val f = Fixture(this, FlakyPairStore(listOf(oldRecord())))
            val request = PairingPeer().startHello(oldRecord().id)

            f.pairing.hello(null, request.nonceE, request.proof, PORT) shouldBe HelloReply.Malformed
            f.pairing.hello(request.id, "xyz", request.proof, PORT) shouldBe HelloReply.Malformed
        }

    @Test
    fun aSignedRequestWhileTheFileCannotBeReadIsStoreUnavailableNotUnpairedAndServedAgainAfter() =
        runTest {
            val peer = PairingPeer()
            val store = FlakyPairStore(emptyList())
            store.failLoad = false
            val f = Fixture(this, store)
            f.pair(peer)
            f.hello(peer)
            val restarted = Fixture(this, store, seed = 99)
            store.failLoad = true

            restarted.send(peer) shouldBe AuthReply.StoreUnavailable

            // Readable again: the browser is paired, and (the app having restarted) has no session, so it is told to say hello.
            store.failLoad = false
            advanceTimeBy(1.seconds)
            restarted.send(peer, counter = 2) shouldBe AuthReply.SessionExpired
        }

    @Test
    fun requestsThatNeedNoPairingsAreAnsweredAsBeforeWhileTheFileCannotBeRead() =
        runTest {
            val f = Fixture(this, FlakyPairStore(listOf(oldRecord())))
            val body = "{}".encodeToByteArray()

            f.pairing.authenticate(null, null, null, "/add", body, PORT) shouldBe AuthReply.Anonymous
            f.pairing.authenticate(oldRecord().id, null, null, "/add", body, PORT) shouldBe AuthReply.Malformed
            f.pairing.authenticate("x", "1", "00", "/add", body, PORT) shouldBe AuthReply.Malformed
        }

    @Test
    fun aRevokeWhileTheFileCannotBeReadIsStoreUnavailableNotNotFoundAndWritesNothing() =
        runTest {
            val store = FlakyPairStore(listOf(oldRecord()))
            val f = Fixture(this, store)

            f.pairing.revoke(oldRecord().id) shouldBe RevokeResult.STORE_UNAVAILABLE
            f.pairing.revoke("0".repeat(32)) shouldBe RevokeResult.STORE_UNAVAILABLE

            store.saves shouldBe 0
            store.records.size shouldBe 1
        }

    @Test
    fun aRevokeWorksAgainOnceTheFileCanBeRead() =
        runTest {
            val store = FlakyPairStore(listOf(oldRecord()))
            val f = Fixture(this, store)
            f.pairing.revoke(oldRecord().id) shouldBe RevokeResult.STORE_UNAVAILABLE

            store.failLoad = false
            advanceTimeBy(1.seconds)

            f.pairing.revoke(oldRecord().id) shouldBe RevokeResult.REVOKED
            store.records shouldBe emptyList()
            f.pairing.revoke(oldRecord().id) shouldBe RevokeResult.NOT_FOUND
        }

    @Test
    fun aPairingWhileTheFileCannotBeReadIsStillNotSavedAndItsCodeStaysGood() =
        runTest {
            val store = FlakyPairStore(listOf(oldRecord()))
            val f = Fixture(this, store)
            val peer = PairingPeer()
            val offer = f.pairing.startPairing()
            val attempt = peer.attempt(offer.code)

            f.pairing.pair(ORIGIN, UA, attempt.nonceE, attempt.proofE, PORT) shouldBe PairReply.NotSaved

            offer.state() shouldBe PairingState.WAITING
            store.saves shouldBe 0
        }

    @Test
    fun aReadErrorIsLoggedWithoutAnySecret() =
        runTest {
            val store = FlakyPairStore(listOf(oldRecord()))
            val f = Fixture(this, store)

            f.pairing.browsers()

            f.sink.lines.any { "could not be read" in it } shouldBe true
            f.sink.lines.joinToString("\n") shouldNotContain oldRecord().secret
        }

    @Test
    fun anUnreadableFileIsLookedAtOnceASecondNotOnEveryRequest() =
        runTest {
            val store = FlakyPairStore(listOf(oldRecord()))
            val f = Fixture(this, store)

            repeat(20) { f.pairing.browsers() shouldBe PairedBrowsers.Unavailable }
            store.loads shouldBe 1

            advanceTimeBy(500.milliseconds)
            f.pairing.browsers() shouldBe PairedBrowsers.Unavailable
            store.loads shouldBe 1

            advanceTimeBy(600.milliseconds)
            f.pairing.browsers() shouldBe PairedBrowsers.Unavailable
            store.loads shouldBe 2
        }

    @Test
    fun anUnreadableFileIsReadAgainAfterASecondAndServedOnceItCanBe() =
        runTest {
            val store = FlakyPairStore(listOf(oldRecord()))
            val f = Fixture(this, store)
            f.pairing.browsers() shouldBe PairedBrowsers.Unavailable

            store.failLoad = false
            f.pairing.browsers() shouldBe PairedBrowsers.Unavailable
            advanceTimeBy(1.seconds)

            f.pairing.browsers().shouldBeInstanceOf<PairedBrowsers.Listed>()
        }

    @Test
    fun theUnreadableWarningIsLoggedOncePerMinuteNotOncePerRead() =
        runTest {
            val store = FlakyPairStore(listOf(oldRecord()))
            val f = Fixture(this, store)

            repeat(30) {
                f.pairing.browsers()
                advanceTimeBy(1.seconds)
            }
            f.sink.lines.count { "could not be read" in it } shouldBe 1

            advanceTimeBy(1.minutes)
            f.pairing.browsers()
            f.sink.lines.count { "could not be read" in it } shouldBe 2
        }

    // ---- what the app tells the screen (S4) ----

    @Test
    fun aFreshCodeIsWaiting() =
        pairingTest { f ->
            f.pairing.startPairing().state() shouldBe PairingState.WAITING
        }

    @Test
    fun aCodeThatPairedIsPaired() =
        pairingTest { f ->
            val peer = PairingPeer()
            val offer = f.pairing.startPairing()
            val attempt = peer.attempt(offer.code)

            f.pairing.pair(ORIGIN, UA, attempt.nonceE, attempt.proofE, PORT).shouldBeInstanceOf<PairReply.Paired>()

            offer.state() shouldBe PairingState.PAIRED
        }

    @Test
    fun aCodeBurnedByWrongTriesIsBurned() =
        pairingTest { f ->
            val offer = f.pairing.startPairing()
            repeat(4) {
                val wrong = PairingPeer().attempt(WRONG_CODE)
                f.pairing.pair(ORIGIN, UA, wrong.nonceE, wrong.proofE, PORT)
            }
            offer.state() shouldBe PairingState.WAITING

            val last = PairingPeer().attempt(WRONG_CODE)
            f.pairing.pair(ORIGIN, UA, last.nonceE, last.proofE, PORT)

            offer.state() shouldBe PairingState.BURNED
        }

    @Test
    fun aCodeThatRanOutIsExpired() =
        pairingTest { f ->
            val offer = f.pairing.startPairing()
            advanceTimeBy(2.minutes - 1.seconds)
            offer.state() shouldBe PairingState.WAITING

            advanceTimeBy(1.seconds)

            offer.state() shouldBe PairingState.EXPIRED
        }

    @Test
    fun aCodeReplacedByANewOneIsExpired() =
        pairingTest { f ->
            val old = f.pairing.startPairing()
            val new = f.pairing.startPairing()

            old.state() shouldBe PairingState.EXPIRED
            new.state() shouldBe PairingState.WAITING
        }

    // ---- a code the user walked away from ----

    @Test
    fun aCancelledCodeIsExpiredAndPairingWithItIsNoCode() =
        pairingTest { f ->
            val peer = PairingPeer()
            val offer = f.pairing.startPairing()
            val attempt = peer.attempt(offer.code)

            f.pairing.cancelPairing()

            offer.state() shouldBe PairingState.EXPIRED
            f.pairing.pair(ORIGIN, UA, attempt.nonceE, attempt.proofE, peer.port) shouldBe PairReply.NoCode
            f.pairing.listed() shouldBe emptyList()
        }

    @Test
    fun cancellingAnOfferLeavesANewerCodeAlone() =
        pairingTest { f ->
            val old = f.pairing.startPairing()
            val newer = f.pairing.startPairing()

            f.pairing.cancelPairing(old)

            old.state() shouldBe PairingState.EXPIRED
            newer.state() shouldBe PairingState.WAITING
        }

    @Test
    fun cancellingTheCurrentOfferEndsIt() =
        pairingTest { f ->
            val offer = f.pairing.startPairing()

            f.pairing.cancelPairing(offer)

            offer.state() shouldBe PairingState.EXPIRED
        }

    @Test
    fun cancellingWhenNoCodeIsWaitingChangesNothing() =
        pairingTest { f ->
            f.pairing.cancelPairing()
            f.pairing.cancelPairing()

            // The next code is as good as ever.
            val offer = f.pairing.startPairing()
            offer.state() shouldBe PairingState.WAITING
            f.pairing.pair(ORIGIN, UA, "a".repeat(32), "b".repeat(64), PORT) shouldBe PairReply.WrongProof
        }

    @Test
    fun cancellingDoesNotUndoACodeThatPairedOrWasBurned() =
        pairingTest { f ->
            val paired = f.pairing.startPairing()
            val peer = PairingPeer()
            val attempt = peer.attempt(paired.code)
            f.pairing.pair(ORIGIN, UA, attempt.nonceE, attempt.proofE, peer.port).shouldBeInstanceOf<PairReply.Paired>()
            f.pairing.cancelPairing()
            paired.state() shouldBe PairingState.PAIRED
            f.pairing.listed().size shouldBe 1

            val burned = f.pairing.startPairing()
            repeat(5) {
                val wrong = PairingPeer().attempt(WRONG_CODE)
                f.pairing.pair(ORIGIN, UA, wrong.nonceE, wrong.proofE, PORT)
            }
            f.pairing.cancelPairing()
            burned.state() shouldBe PairingState.BURNED
        }

    @Test
    fun aCodeMadeAfterACancelWorks() =
        pairingTest { f ->
            f.pairing.startPairing()
            f.pairing.cancelPairing()

            f.pair()

            f.pairing.listed().size shouldBe 1
        }

    @Test
    fun aCodeWaitingWhileTheTriesAreUsedUpIsThrottledUntilTheMinuteRunsOut() =
        pairingTest { f ->
            val attempt = PairingPeer().attempt(WRONG_CODE)
            repeat(10) {
                f.pairing.startPairing()
                f.pairing.pair(ORIGIN, UA, attempt.nonceE, attempt.proofE, PORT)
            }
            val offer = f.pairing.startPairing()

            offer.state() shouldBe PairingState.THROTTLED

            advanceTimeBy(1.minutes)

            offer.state() shouldBe PairingState.WAITING
        }

    @Test
    fun theStateNeverCarriesTheCode() =
        pairingTest { f ->
            val offer = f.pairing.startPairing()

            offer.state().toString() shouldNotContain offer.code
        }

    // ---- time that only goes forward (nit) ----

    @Test
    fun aWallClockSetBackDoesNotGiveACodeMoreLife() =
        runTest {
            val wall = ShiftedClock(virtualClock())
            val f = Fixture(this, clock = wall)
            val peer = PairingPeer()
            val code = f.pairing.startPairing().code
            val attempt = peer.attempt(code)
            advanceTimeBy(2.minutes)
            wall.offset = (-1).hours

            f.pairing.pair(ORIGIN, UA, attempt.nonceE, attempt.proofE, PORT) shouldBe PairReply.NoCode
        }

    @Test
    fun aWallClockSetForwardDoesNotTakeACodeAwayEarly() =
        runTest {
            val wall = ShiftedClock(virtualClock())
            val f = Fixture(this, clock = wall)
            val peer = PairingPeer()
            val code = f.pairing.startPairing().code
            val attempt = peer.attempt(code)
            advanceTimeBy(30.seconds)
            wall.offset = 3.hours

            f.pairing.pair(ORIGIN, UA, attempt.nonceE, attempt.proofE, PORT).shouldBeInstanceOf<PairReply.Paired>()
        }

    @Test
    fun aWallClockSetBackDoesNotKeepASessionAlive() =
        runTest {
            val wall = ShiftedClock(virtualClock())
            val f = Fixture(this, clock = wall)
            val peer = PairingPeer()
            f.pair(peer)
            f.hello(peer)
            advanceTimeBy(60.seconds)
            wall.offset = (-1).hours

            f.send(peer) shouldBe AuthReply.SessionExpired
        }

    @Test
    fun aWallClockSetForwardDoesNotEndASessionEarly() =
        runTest {
            val wall = ShiftedClock(virtualClock())
            val f = Fixture(this, clock = wall)
            val peer = PairingPeer()
            f.pair(peer)
            f.hello(peer)
            advanceTimeBy(10.seconds)
            wall.offset = 3.hours

            f.send(peer) shouldBe AuthReply.Ok(peer.id)
        }

    @Test
    fun aWallClockMovedDoesNotResetTheTriesWindow() =
        runTest {
            val wall = ShiftedClock(virtualClock())
            val f = Fixture(this, clock = wall)
            val attempt = PairingPeer().attempt(WRONG_CODE)
            repeat(10) {
                f.pairing.startPairing()
                f.pairing.pair(ORIGIN, UA, attempt.nonceE, attempt.proofE, PORT)
            }
            f.pairing.startPairing()
            wall.offset = 3.hours

            f.pairing.pair(ORIGIN, UA, attempt.nonceE, attempt.proofE, PORT).shouldBeInstanceOf<PairReply.Throttled>()
        }

    // ---- between runs ----

    @Test
    fun aPairingSurvivesARestart() =
        pairingTest { f ->
            val peer = PairingPeer()
            f.pair(peer)

            val restarted = Fixture(this, f.store, seed = 99)

            restarted.pairing.listed().map { it.id } shouldContainExactly listOf(peer.id)
            restarted.hello(peer)
            restarted.send(peer) shouldBe AuthReply.Ok(peer.id)
        }

    @Test
    fun aRestartEndsTheSessionsAndTheCode() =
        pairingTest { f ->
            val peer = PairingPeer()
            f.pair(peer)
            f.hello(peer)
            val code = f.pairing.startPairing().code
            val restarted = Fixture(this, f.store, seed = 99)

            restarted.send(peer) shouldBe AuthReply.SessionExpired
            val attempt = PairingPeer(3).attempt(code)
            restarted.pairing.pair(ORIGIN, UA, attempt.nonceE, attempt.proofE, PORT) shouldBe PairReply.NoCode
        }

    @Test
    fun lastUsedFollowsHelloAtMostOncePerMinute() =
        pairingTest { f ->
            val peer = PairingPeer()
            f.pair(peer)
            val paired = f.clock.now()
            advanceTimeBy(30.seconds)
            f.hello(peer)
            f.pairing
                .listed()
                .single()
                .lastUsed shouldBe paired

            advanceTimeBy(40.seconds)
            f.hello(peer)

            f.pairing
                .listed()
                .single()
                .lastUsed shouldBe f.clock.now()
            f.store
                .load()
                .single()
                .lastUsed shouldBe f.clock.now()
        }

    @Test
    fun theListNeverCarriesTheSecretAndARecordNeverPrintsIt() =
        pairingTest { f ->
            val peer = PairingPeer()
            f.pair(peer)

            f.pairing.listed().toString() shouldNotContain peer.secret
            f.store
                .load()
                .single()
                .toString() shouldNotContain peer.secret
        }

    // ---- secrets and comparisons ----

    @Test
    fun noLogLineHoldsACodeASecretANonceOrAProof() =
        pairingTest { f ->
            val peer = PairingPeer()
            val code = f.pairing.startPairing().code
            val attempt = peer.attempt(code)
            val wrong = PairingPeer(5).attempt(WRONG_CODE)
            f.pairing.pair(ORIGIN, UA, wrong.nonceE, wrong.proofE, PORT)
            val reply = f.pairing.pair(ORIGIN, UA, attempt.nonceE, attempt.proofE, PORT).shouldBeInstanceOf<PairReply.Paired>()
            peer.accept(code, attempt, reply)
            f.hello(peer)
            val body = "{}".encodeToByteArray()
            val signed = peer.sign("/add", body)
            f.pairing.authenticate(signed.id, signed.counter, signed.mac, "/add", body, PORT)
            f.pairing.authenticate(signed.id, signed.counter, signed.mac, "/add", body, PORT)
            f.pairing.authenticate(signed.id, "9", "0".repeat(64), "/add", body, PORT)
            f.pairing.revoke(peer.id)

            val log = f.sink.lines.joinToString("\n")

            (log.isNotEmpty()) shouldBe true
            for (secret in listOf(
                code,
                peer.secret,
                attempt.nonceE,
                attempt.proofE,
                reply.nonceA,
                reply.proofA,
                signed.mac,
                wrong.proofE,
            )) {
                log shouldNotContain secret
            }
        }

    @Test
    fun everyProofAndMacIsComparedWithTheConstantTimeHelper() =
        runTest {
            var comparisons = 0
            val f =
                Fixture(
                    this,
                    equal = { a, b ->
                        comparisons++
                        constantTimeEquals(a, b)
                    },
                )
            val peer = PairingPeer()
            f.pair(peer)
            val afterPairing = comparisons
            f.hello(peer)
            val afterHello = comparisons
            f.send(peer)

            afterPairing shouldBe 1
            afterHello shouldBe 2
            comparisons shouldBe 3
        }

    @Test
    fun aHelloForAnUnknownIdIsComparedToo() =
        runTest {
            var comparisons = 0
            val f =
                Fixture(
                    this,
                    equal = { a, b ->
                        comparisons++
                        constantTimeEquals(a, b)
                    },
                )
            val unknown = PairingPeer(9).startHello("0".repeat(32))

            f.pairing.hello(unknown.id, unknown.nonceE, unknown.proof, PORT) shouldBe HelloReply.Unpaired

            // The same work as for a bad proof of a paired browser, so the answer does not come quicker.
            comparisons shouldBe 1
        }

    @Test
    fun theComparisonHelperSaysWhetherTwoByteStringsAreEqual() {
        constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 3)) shouldBe true
        constantTimeEquals(byteArrayOf(), byteArrayOf()) shouldBe true
        constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 4)) shouldBe false
        constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(0, 2, 3)) shouldBe false
        constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2)) shouldBe false
        constantTimeEquals(byteArrayOf(1, 2), byteArrayOf(1, 2, 3)) shouldBe false
    }

    private companion object {
        /** A code that is well formed and not the one any test waits for. */
        const val WRONG_CODE = "ABCDEFGHJKMNPQRS"
        const val PORT = TEST_PORT
        const val ORIGIN = "chrome-extension://abcdefghijklmnopabcdefghijklmnop"
        const val UA = "Mozilla/5.0 (Macintosh) Chrome/130.0.0.0 Safari/537.36"
    }
}
