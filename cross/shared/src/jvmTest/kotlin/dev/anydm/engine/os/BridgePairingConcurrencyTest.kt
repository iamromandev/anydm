package dev.anydm.engine.os

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.time.Clock

/**
 * [BridgePairing] on real threads, not on one test scheduler's: the HTTP server calls it from its own threads, and what must
 * hold is that two requests at once see one picture. Each test starts its threads together at a barrier and then holds every
 * call inside the proof comparison until all of them are there (see [Rendezvous]), so a pairing that did not take turns would
 * let them all through; with the lock the answer is the same whatever the timing.
 */
class BridgePairingConcurrencyTest {
    /**
     * A comparison of proofs that, once armed ([rearm]), waits (briefly) until [parties] callers are inside it at the same
     * moment, and then lets them all go together. It is the one place between the start of a call and its effect that a test can
     * reach, and it makes the callers meet there: code that does not hold the pairing's lock across the check and the effect
     * lets all of them through together. Under the lock only one caller is ever inside, which waits out the patience alone, so
     * the test only gets slower, never different.
     */
    private class Rendezvous(
        private val parties: Int,
    ) {
        @Volatile
        private var armed = false
        private val inside = AtomicInteger()
        private val active = AtomicInteger()

        /** The most callers that were inside [equal] at the same moment, in every round: with the lock, one. */
        val peak = AtomicInteger()

        /** For a new round: nobody is inside yet. Call it while no caller is running. */
        fun rearm() {
            inside.set(0)
            armed = true
        }

        fun equal(
            expected: ByteArray,
            given: ByteArray,
        ): Boolean {
            peak.accumulateAndGet(active.incrementAndGet(), ::maxOf)
            try {
                if (armed) {
                    inside.incrementAndGet()
                    val deadline = System.nanoTime() + PATIENCE_NANOS
                    while (inside.get() < parties && System.nanoTime() < deadline) Thread.onSpinWait()
                }
                return constantTimeEquals(expected, given)
            } finally {
                active.decrementAndGet()
            }
        }
    }

    private val threads = 16
    private val rendezvous = Rendezvous(threads)
    private val store = InMemoryPairStore()
    private val pairing = BridgePairing(store, Clock.System, equal = rendezvous::equal)

    /** Runs [call] on [threads] real threads that all start at the same moment; what each returned, in thread order. */
    private fun <T> onThreads(call: (Int) -> T): List<T> {
        val start = CyclicBarrier(threads)
        val pool = Executors.newFixedThreadPool(threads)
        try {
            // A failure in any thread (an exception out of the pairing) fails the test here.
            return (0 until threads)
                .map { n ->
                    pool.submit(
                        Callable {
                            start.await(30, TimeUnit.SECONDS)
                            call(n)
                        },
                    )
                }.map { it.get(60, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun pairedPeerWithASession(seed: Int = 1): PairingPeer =
        runBlocking {
            val peer = PairingPeer(seed)
            val code = pairing.startPairing().code
            val attempt = peer.attempt(code)
            val reply = pairing.pair(ORIGIN, AGENT, attempt.nonceE, attempt.proofE, peer.port).shouldBeInstanceOf<PairReply.Paired>()
            peer.accept(code, attempt, reply) shouldBe true
            val hello = peer.startHello()
            peer.acceptHello(pairing.hello(hello.id, hello.nonceE, hello.proof, peer.port).shouldBeInstanceOf<HelloReply.Ok>()) shouldBe
                true
            peer
        }

    @Test
    fun sixteenRequestsWithOneCounterAndOneMacAreOneRequest() {
        val peer = pairedPeerWithASession()
        val body = """{"url":"https://example.com/a"}""".encodeToByteArray()

        // The window between "the counter is above the last" and "it is the last" is a few nanoseconds wide even with the callers
        // meeting at the comparison, so a pairing that did not take turns lets two through in some rounds and not in others:
        // many rounds, each with a counter of its own, make that certain, and cost the pairing that does take turns nothing.
        repeat(ROUNDS) {
            val signed = peer.sign("/add", body)
            rendezvous.rearm()

            val replies =
                onThreads {
                    runBlocking { pairing.authenticate(signed.id, signed.counter, signed.mac, "/add", body, peer.port) }
                }

            replies.count { it == AuthReply.Ok(peer.id) } shouldBe 1
            replies.count { it == AuthReply.Replay } shouldBe threads - 1
        }
        // The callers never met inside the comparison: the lock took them in turn.
        rendezvous.peak.get() shouldBe 1
    }

    @Test
    fun sixteenBrowsersUsingOneCodeAtOnceMakeOnePairing() {
        val code = runBlocking { pairing.startPairing().code }
        val peers = (1..threads).map { PairingPeer(it) }
        val attempts = peers.map { it.attempt(code) }
        rendezvous.rearm()

        val replies =
            onThreads { n ->
                runBlocking { pairing.pair(ORIGIN, AGENT, attempts[n].nonceE, attempts[n].proofE, peers[n].port) }
            }

        replies.count { it is PairReply.Paired } shouldBe 1
        replies.count { it == PairReply.NoCode } shouldBe threads - 1
        runBlocking { pairing.listed() }.size shouldBe 1
        rendezvous.peak.get() shouldBe 1
        store.load().size shouldBe 1
        // The one that got in is the one whose answer proves the app knew the code, and its record is the one that was kept.
        val winner = replies.indexOfFirst { it is PairReply.Paired }
        peers[winner].accept(code, attempts[winner], replies[winner] as PairReply.Paired) shouldBe true
        store.load().single().id shouldBe peers[winner].id
    }

    @Test
    fun sixteenBrowsersUsingOneCodeAndOneProofAtOnceMakeOnePairing() {
        val code = runBlocking { pairing.startPairing().code }
        val peer = PairingPeer()
        val attempt = peer.attempt(code)
        rendezvous.rearm()

        val replies =
            onThreads {
                runBlocking { pairing.pair(ORIGIN, AGENT, attempt.nonceE, attempt.proofE, peer.port) }
            }

        replies.count { it is PairReply.Paired } shouldBe 1
        replies.count { it == PairReply.NoCode } shouldBe threads - 1
        runBlocking { pairing.listed() }.size shouldBe 1
        rendezvous.peak.get() shouldBe 1
    }

    private companion object {
        // How long a caller waits for the others in [Rendezvous]: long enough for threads started together to meet.
        const val PATIENCE_NANOS = 2_000_000L
        const val ROUNDS = 100
        const val ORIGIN = "chrome-extension://abcdefghijklmnop"
        const val AGENT = "Mozilla/5.0 (X11; Linux x86_64; rv:131.0) Gecko/20100101 Firefox/131.0"
    }
}
