package dev.anydm.engine.os

import kotlin.random.Random

/** A port the tests' bridge listens on (the first the real one tries). */
internal const val TEST_PORT = 18400

/**
 * The extension's side of the protocol, as a test double: what the extension must do to pair, to start a session and to sign
 * a request. It is written from the protocol's text (and checked against the shared vectors by [PairingVectorsTest]), so a
 * mistake in the app's side shows up as a disagreement.
 *
 * [port] is the port the extension dialed: it goes into every proof, as the protocol says. A peer whose port is not the one
 * of the server it talks to (a relay stands in between) makes proofs the server rejects, and rejects the server's.
 */
internal class PairingPeer(
    seed: Int = 1,
    val port: Int = TEST_PORT,
) {
    private val random = Random(seed)

    var id: String = ""
        private set
    var secret: String = ""
        private set
    private var helloNonceE = ""
    private var sessionNonce = ""
    private var counter = 0L

    class Attempt(
        val nonceE: String,
        val proofE: String,
    )

    /** The three fields of a `/hello` request. */
    class Hello(
        val id: String,
        val nonceE: String,
        val proof: String,
    )

    class Signed(
        val id: String,
        val counter: String,
        val mac: String,
    )

    fun nonce(): String = random.nextBytes(16).toHex()

    /** The two fields of a `/pair` request made with [code]. */
    fun attempt(code: String): Attempt {
        val nonceE = nonce()
        return Attempt(nonceE, PairingProtocol.pairProof(code, nonceE, port))
    }

    /** Checks the app's answer as the extension must: only an app that knew the code can have made [PairReply.Paired.proofA]. */
    fun accept(
        code: String,
        attempt: Attempt,
        reply: PairReply.Paired,
    ): Boolean {
        if (reply.proofA != PairingProtocol.appProof(code, attempt.nonceE, reply.nonceA, port)) return false
        id = reply.id
        secret = PairingProtocol.secret(code, attempt.nonceE, reply.nonceA)
        return true
    }

    /**
     * The fields of a `/hello` request for [id] (by default this peer's own): a nonce, and the proof that the sender holds the
     * secret. A peer that was never paired proves with a secret of zeros, as a guesser would.
     */
    fun startHello(id: String = this.id): Hello {
        val nonceE = nonce()
        helloNonceE = nonceE
        return Hello(id, nonceE, PairingProtocol.helloRequestProof(secret.ifEmpty { NO_SECRET }, id, nonceE, port))
    }

    /** Checks the app's answer to `/hello`: only an app that holds the secret can have made [HelloReply.Ok.proof]. */
    fun acceptHello(reply: HelloReply.Ok): Boolean {
        if (reply.proof != PairingProtocol.helloProof(secret, helloNonceE, reply.nonceA, port)) return false
        sessionNonce = reply.nonceA
        counter = 0
        return true
    }

    /** The headers of a request to [path] with [body]; [counter] is the next one unless a test says otherwise. */
    fun sign(
        path: String,
        body: ByteArray,
        counter: Long = ++this.counter,
    ): Signed {
        this.counter = maxOf(this.counter, counter)
        return Signed(id, counter.toString(), PairingProtocol.requestMac(secret, sessionNonce, counter, path, body, port))
    }

    private companion object {
        val NO_SECRET = "00".repeat(32)
    }
}
