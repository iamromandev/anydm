package dev.anydm.engine.os

import dev.anydm.engine.log.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * A code for the user to type into the browser extension, and when it stops working. The code is a secret: not printed. It is
 * 16 characters of Crockford's base32 ([code]); people read it in groups of four ([grouped]) and the extension accepts it with
 * or without the hyphens and spaces. [expiresAt] is for the countdown on screen; what ends a code is [state].
 */
class PairingOffer internal constructor(
    val code: String,
    val expiresAt: Instant,
    private val current: suspend () -> PairingState,
) {
    /** [code] in groups of four, joined with hyphens: what the screen shows and the copy button copies. */
    val grouped: String get() = PairingProtocol.grouped(code)

    /** What became of this code, so the screen can say so; asking changes nothing. */
    suspend fun state(): PairingState = current()

    override fun toString(): String = "PairingOffer(code=***, expiresAt=$expiresAt)"
}

/** What became of a code the user was shown. Only [WAITING] and [THROTTLED] are not the end of it. */
enum class PairingState {
    /** The code can be used now. */
    WAITING,

    /** The code can't be tried at the moment: too many pairing requests in the last minute. It works again when they thin out. */
    THROTTLED,

    /** A browser used the code and is paired. */
    PAIRED,

    /** Wrong proofs used the code up ([BridgePairing.MAX_WRONG] of them). */
    BURNED,

    /** The code ran out of time ([BridgePairing.CODE_LIFE]), was replaced by a newer one, or was cancelled ([BridgePairing.cancelPairing]). */
    EXPIRED,
}

/** A browser the user paired, for the list in the app. */
data class PairedBrowser(
    val id: String,
    val label: String,
    val createdAt: Instant,
    val lastUsed: Instant,
)

/**
 * The browsers paired, or the news that they can't be told: [BridgePairing.browsers] and `Engine.pairedBrowsers`. An empty
 * [Listed] means nothing is paired; [Unavailable] means that nobody knows, and must not be shown as "nothing is paired".
 */
sealed interface PairedBrowsers {
    /** The browsers paired, oldest first. */
    data class Listed(
        val browsers: List<PairedBrowser>,
    ) : PairedBrowsers

    /**
     * The pairings can't be read now (the file is there but unreadable: a read error, a folder that can't be entered, a file
     * of a version this anydm does not know). They are not lost, and are not gone: asking again may work, and no browser is
     * served or changed meanwhile.
     */
    data object Unavailable : PairedBrowsers
}

/** What [BridgePairing.revoke] did. */
enum class RevokeResult {
    /** The browser is gone: it can't start a session or use one. */
    REVOKED,

    /** There was no such browser. */
    NOT_FOUND,

    /** The browser is gone for now, but the file could not be written, so it may come back at the next start. */
    NOT_SAVED,

    /**
     * The pairings can't be read now, so nothing was changed: the browser may be paired, and is not revoked. Try again later.
     * (Not [NOT_FOUND]: there is no telling whether it is there.)
     */
    STORE_UNAVAILABLE,
}

sealed interface PairReply {
    class Paired(
        val id: String,
        val nonceA: String,
        val proofA: String,
    ) : PairReply {
        override fun toString(): String = "Paired(id=$id)"
    }

    /** The request is not what a pairing request looks like. */
    data object Malformed : PairReply

    /** No code is waiting: none was started, it ran out, was used, or was burned by wrong tries. */
    data object NoCode : PairReply

    /** The proof was not made with the code. */
    data object WrongProof : PairReply

    /** Too many tries in the last minute (any code); try again after [retryAfter]. */
    data class Throttled(
        val retryAfter: Duration,
    ) : PairReply

    /** The proof was right but the pairing could not be written down; the code still works. */
    data object NotSaved : PairReply
}

sealed interface HelloReply {
    class Ok(
        val nonceA: String,
        val proof: String,
    ) : HelloReply {
        override fun toString(): String = "Ok"
    }

    data object Malformed : HelloReply

    /** No such browser: never paired, or revoked. */
    data object Unpaired : HelloReply

    /** The pairings can't be read now, so there is no telling whether the browser is paired: the extension tries again later. */
    data object StoreUnavailable : HelloReply
}

sealed interface AuthReply {
    /** The request carried no credentials at all. */
    data object Anonymous : AuthReply

    data class Ok(
        val id: String,
    ) : AuthReply

    /** Credentials that are not complete or not in form. */
    data object Malformed : AuthReply

    data object Unpaired : AuthReply

    /** The browser is paired but has no session now: it never said hello, or the session ran out. */
    data object SessionExpired : AuthReply

    data object BadMac : AuthReply

    /** The MAC is right but the counter is not above the last one of the session. */
    data object Replay : AuthReply

    /** The pairings can't be read now, so there is no telling whether the browser is paired: the extension tries again later. */
    data object StoreUnavailable : AuthReply
}

/**
 * Who may talk to the browser bridge as a paired browser: the app side of the pairing protocol. The user starts a
 * pairing in the app ([startPairing]) and types the code into the extension; the code never goes over the wire, only proofs
 * made with it do ([pair]). Both sides end up with a secret that only they hold. A session ([hello]) lasts [SESSION_LIFE] and
 * signs every request with a counter that only goes up ([authenticate]). [revoke] takes a browser away.
 *
 * Every proof is bound to the TCP port of the server it was made for (see [PairingProtocol]); the caller passes the port the
 * request arrived on, which is the one the app listens on, and not what the request says.
 *
 * While the pairings can't be read (the store throws: [PairStore]) nobody knows who is paired. [hello] and [authenticate]
 * answer "store unavailable", which is not "unpaired" (the browser keeps its secret and tries again), [revoke] says so and
 * changes nothing, [browsers] says so instead of listing nobody, and [pair] is "not saved". Everything is served again as soon
 * as the store can be read, and nothing is written over what could not be read.
 *
 * A browser has one session at a time: the request headers carry its id and nothing else, so a new [hello] ends the last
 * session of that id, and a second tab or worker of the same browser that says hello takes the session from the first. The
 * extension keeps one owner of the session (its background worker) for this reason.
 *
 * Everything here is one critical section, so two requests at once see one picture. Codes, secrets, nonces and proofs are
 * never logged, and every comparison of one goes through [equal], a constant-time comparison. How long a code, a session or a
 * window of tries lasts is measured on [monotonic], which a change of the wall [clock] (a person setting it, a time sync)
 * cannot stretch or shorten; the wall clock only dates what the screen shows.
 */
class BridgePairing(
    private val store: PairStore,
    private val clock: Clock,
    /** Where elapsed time is read: lives of codes and sessions and the window of tries. Tests pass the test scheduler's. */
    private val monotonic: TimeSource = TimeSource.Monotonic,
    /** Where codes, ids and nonces come from: pass a cryptographic source (the default is one). */
    private val random: Random = secureRandom(),
    private val log: Log = Log.None,
    private val equal: (ByteArray, ByteArray) -> Boolean = ::constantTimeEquals,
) {
    private enum class End { PAIRED, BURNED, REPLACED, CANCELLED }

    private class Offer(
        val code: String,
        val expiresAt: Instant,
        val born: TimeMark,
    ) {
        var wrong = 0
        var ended: End? = null

        fun ranOut(): Boolean = born.elapsedNow() >= CODE_LIFE
    }

    private class Session(
        val nonceA: String,
        val born: TimeMark,
    ) {
        var counter = 0L

        fun ranOut(): Boolean = born.elapsedNow() >= SESSION_LIFE
    }

    private val lock = Mutex()

    // Under the lock, all of it.
    private var loaded: MutableMap<String, PairRecord>? = null

    // The last look at the store failed: what is in memory is not what is on disk, so nothing is written over it.
    private var loadFailed = false

    // When the store was last looked at while it could not be read, and when that was last logged: a failing store is tried
    // again at most once a [LOAD_RETRY] and logged at most once a [TRIES_WINDOW], not once per request.
    private var lastLoadTry: TimeMark? = null
    private var unreadableLogged: TimeMark? = null
    private var offer: Offer? = null
    private val tries = ArrayDeque<TimeMark>()
    private var throttleLogged: TimeMark? = null
    private val sessions = HashMap<String, Session>()

    /** Makes a code, valid for [CODE_LIFE] and until it is used or burned, and ends any earlier one. */
    suspend fun startPairing(): PairingOffer =
        lock.withLock {
            val now = clock.now()
            val code =
                buildString { repeat(PairingProtocol.CODE_LENGTH) { append(PairingProtocol.ALPHABET[random.nextInt(ALPHABET_SIZE)]) } }
            val made = Offer(code, now + CODE_LIFE, monotonic.markNow())
            offer?.let { if (it.ended == null) it.ended = End.REPLACED }
            offer = made
            log.info("bridge") { "pairing started" }
            PairingOffer(made.code, made.expiresAt) { lock.withLock { stateOf(made) } }
        }

    /**
     * Ends the code that is waiting, if one is: the user closed the screen that shows it, so nobody is meant to use it. From now
     * on the code is [PairingState.EXPIRED], and a pairing request made with it is [PairReply.NoCode]. A code that was used or
     * burned already stays what it was, and with no code waiting this does nothing. With an [only] offer given, that offer is
     * the one cancelled: a newer code made since (by another screen) is left alone.
     */
    suspend fun cancelPairing(only: PairingOffer? = null) {
        lock.withLock {
            val waiting = offer ?: return@withLock
            if (only != null && only.code != waiting.code) return@withLock
            if (waiting.ended == null) waiting.ended = End.CANCELLED
            offer = null
            log.info("bridge") { "pairing cancelled" }
        }
    }

    // Under the lock.
    private fun stateOf(made: Offer): PairingState {
        made.ended?.let {
            return when (it) {
                End.PAIRED -> PairingState.PAIRED
                End.BURNED -> PairingState.BURNED
                End.REPLACED, End.CANCELLED -> PairingState.EXPIRED
            }
        }
        if (made.ranOut()) return PairingState.EXPIRED
        dropOldTries()
        return if (tries.size >= MAX_TRIES) PairingState.THROTTLED else PairingState.WAITING
    }

    /**
     * A browser's pairing request on [port], from [origin] and [userAgent] (the request's own headers, for the label): [nonceE]
     * and [proofE] as the protocol makes them. A request counts toward [MAX_TRIES] a minute, whatever the code, but only while a
     * code is waiting: a flood of requests when the user has not asked to pair anything uses up nothing.
     */
    suspend fun pair(
        origin: String?,
        userAgent: String?,
        nonceE: String?,
        proofE: String?,
        port: Int,
    ): PairReply {
        val nonce = nonceE?.takeIf { NONCE.matches(it) } ?: return PairReply.Malformed
        val proof = proofE?.takeIf { PROOF.matches(it) }?.hexToBytes() ?: return PairReply.Malformed
        return lock.withLock {
            val now = clock.now()
            val current = offer?.takeIf { it.ended == null && !it.ranOut() }
            if (current == null) {
                offer = null
                return@withLock PairReply.NoCode
            }
            dropOldTries()
            if (tries.size >= MAX_TRIES) {
                // Once a window, not once a request: a flood must not fill the log.
                if (throttleLogged?.let { it.elapsedNow() >= TRIES_WINDOW } != false) {
                    log.warn("bridge") { "pairing tries are coming too fast; refused for now" }
                    throttleLogged = monotonic.markNow()
                }
                return@withLock PairReply.Throttled(TRIES_WINDOW - tries.first().elapsedNow())
            }
            tries.addLast(monotonic.markNow())

            val expected = PairingProtocol.pairProof(current.code, nonce, port).hexToBytes()!!
            if (!equal(expected, proof)) {
                current.wrong++
                if (current.wrong >= MAX_WRONG) {
                    current.ended = End.BURNED
                    offer = null
                    log.warn("bridge") { "pairing code burned after $MAX_WRONG wrong tries" }
                } else {
                    log.warn("bridge") { "a wrong pairing proof was refused" }
                }
                return@withLock PairReply.WrongProof
            }

            val nonceA = random.nextBytes(NONCE_BYTES).toHex()
            val id = random.nextBytes(NONCE_BYTES).toHex()
            val record =
                PairRecord(
                    id = id,
                    label = browserLabel(origin, userAgent),
                    secret = PairingProtocol.secret(current.code, nonce, nonceA),
                    createdAt = now,
                    lastUsed = now,
                )
            val all = records()
            // Written first: a browser is never told it is paired when the app would forget it at the next start.
            if (!persist(all.values + record)) return@withLock PairReply.NotSaved
            all[id] = record
            current.ended = End.PAIRED
            offer = null
            log.info("bridge") { "paired a browser: ${record.label}" }
            PairReply.Paired(id, nonceA, PairingProtocol.appProof(current.code, nonce, nonceA, port))
        }
    }

    // Under the lock.
    private fun dropOldTries() {
        while (tries.isNotEmpty() && tries.first().elapsedNow() >= TRIES_WINDOW) tries.removeFirst()
    }

    /**
     * Starts a session for a paired browser on [port]: [proof] shows that the browser holds its secret (without it anyone who
     * knows an id could replace the session of the browser it belongs to), and the app answers with a nonce of its own and a
     * proof that it holds the secret too, which the browser checks before it trusts the app with anything. A session replaces
     * the browser's last one. An id that is not paired and a proof that is wrong are the same answer, [HelloReply.Unpaired], and
     * the work is the same for both.
     */
    suspend fun hello(
        id: String?,
        nonceE: String?,
        proof: String?,
        port: Int,
    ): HelloReply {
        val who = id?.takeIf { ID.matches(it) } ?: return HelloReply.Malformed
        val nonce = nonceE?.takeIf { NONCE.matches(it) } ?: return HelloReply.Malformed
        val given = proof?.takeIf { PROOF.matches(it) }?.hexToBytes() ?: return HelloReply.Malformed
        return lock.withLock {
            val now = clock.now()
            val all = records()
            // Not "unpaired": while the pairings can't be read nobody knows who is paired, and every id gets the same answer.
            if (loadFailed) return@withLock HelloReply.StoreUnavailable
            val record = all[who]
            // An id that is not paired is checked against a secret of zeros, so it costs the same as a wrong proof.
            val expected = PairingProtocol.helloRequestProof(record?.secret ?: NO_SECRET, who, nonce, port).hexToBytes()!!
            val genuine = equal(expected, given)
            if (record == null || !genuine) return@withLock HelloReply.Unpaired
            val nonceA = random.nextBytes(NONCE_BYTES).toHex()
            sessions.entries.removeAll { it.value.ranOut() }
            sessions[who] = Session(nonceA, monotonic.markNow())
            touch(record, now)
            HelloReply.Ok(nonceA, PairingProtocol.helloProof(record.secret, nonce, nonceA, port))
        }
    }

    /**
     * Checks the credentials of a request to [path] with [body] (the exact bytes) that arrived on [port]: the three header
     * values as they came. A request with none of them is [AuthReply.Anonymous]; with some, or with any that fail, it is never
     * taken for anonymous. A counter is used up only by a request whose MAC is right.
     */
    suspend fun authenticate(
        id: String?,
        counter: String?,
        mac: String?,
        path: String,
        body: ByteArray,
        port: Int,
    ): AuthReply {
        if (id == null && counter == null && mac == null) return AuthReply.Anonymous
        val who = id?.takeIf { ID.matches(it) } ?: return AuthReply.Malformed
        val number = counter?.let(::parseCounter) ?: return AuthReply.Malformed
        val given = mac?.takeIf { PROOF.matches(it) }?.hexToBytes() ?: return AuthReply.Malformed
        return lock.withLock {
            val now = clock.now()
            val all = records()
            if (loadFailed) return@withLock AuthReply.StoreUnavailable
            val record = all[who] ?: return@withLock AuthReply.Unpaired
            val session = sessions[who]
            if (session == null || session.ranOut()) {
                sessions.remove(who)
                return@withLock AuthReply.SessionExpired
            }
            val expected = PairingProtocol.requestMac(record.secret, session.nonceA, number, path, body, port).hexToBytes()!!
            if (!equal(expected, given)) return@withLock AuthReply.BadMac
            if (number <= session.counter) return@withLock AuthReply.Replay
            session.counter = number
            touch(record, now)
            AuthReply.Ok(who)
        }
    }

    /** The browsers paired, oldest first; [PairedBrowsers.Unavailable] while the pairings can't be read. */
    suspend fun browsers(): PairedBrowsers =
        lock.withLock {
            val all = records()
            if (loadFailed) {
                PairedBrowsers.Unavailable
            } else {
                PairedBrowsers.Listed(
                    all.values.sortedBy { it.createdAt }.map { PairedBrowser(it.id, it.label, it.createdAt, it.lastUsed) },
                )
            }
        }

    /**
     * Takes a browser away: its session ends now and it can't start another. While the pairings can't be read nothing is
     * known of who is paired, so nothing is removed and the answer is [RevokeResult.STORE_UNAVAILABLE], not "not found".
     */
    suspend fun revoke(id: String): RevokeResult =
        lock.withLock {
            val all = records()
            if (loadFailed) return@withLock RevokeResult.STORE_UNAVAILABLE
            val gone = all.remove(id) ?: return@withLock RevokeResult.NOT_FOUND
            sessions.remove(id)
            log.info("bridge") { "revoked a browser: ${gone.label}" }
            if (persist(all.values)) RevokeResult.REVOKED else RevokeResult.NOT_SAVED
        }

    // Under the lock. When the store can't be read, this look returns an empty map and sets loadFailed, and the next look tries
    // again: "could not read" is not remembered as "none". Whoever answers about who is paired checks loadFailed after the look
    // (hello, authenticate, revoke, browsers), and nothing is written while it stands (see persist).
    private fun records(): MutableMap<String, PairRecord> {
        loaded?.let { return it }
        // Looked at not long ago and not readable: still not, until the retry time has passed.
        if (loadFailed && lastLoadTry?.let { it.elapsedNow() < LOAD_RETRY } == true) return LinkedHashMap()
        lastLoadTry = monotonic.markNow()
        return try {
            val read = store.load().associateByTo(LinkedHashMap()) { it.id }
            loaded = read
            loadFailed = false
            unreadableLogged = null
            read
        } catch (e: Exception) {
            loadFailed = true
            if (unreadableDue()) log.warn("bridge", e) { "the pairings could not be read; none are used for now" }
            LinkedHashMap()
        }
    }

    // Under the lock. True when a warning about the unreadable store is due (the first, then one per window); it starts the window.
    private fun unreadableDue(): Boolean {
        if (unreadableLogged?.let { it.elapsedNow() < TRIES_WINDOW } == true) return false
        unreadableLogged = monotonic.markNow()
        return true
    }

    // Under the lock. False if the store could not keep them, or if what is in memory is not what the store holds because the
    // store could not be read: writing then would replace the pairings that are there with the ones this run knows of.
    private fun persist(records: Collection<PairRecord>): Boolean {
        if (loadFailed) {
            if (unreadableDue()) log.warn("bridge") { "the pairings are not saved: they could not be read, and what is there is kept" }
            return false
        }
        return try {
            store.save(records.toList())
            true
        } catch (e: Exception) {
            log.warn("bridge", e) { "the pairings could not be saved" }
            false
        }
    }

    // Under the lock. Written down at most once a minute, so a busy browser does not rewrite the file for each request.
    private fun touch(
        record: PairRecord,
        now: Instant,
    ) {
        if (now - record.lastUsed < TOUCH_EVERY) return
        val all = records()
        all[record.id] = record.copy(lastUsed = now)
        persist(all.values)
    }

    // A counter is a whole number from 1 to 2^53 - 1 (what a script can count exactly), in its plain decimal form.
    private fun parseCounter(text: String): Long? {
        if (!COUNTER.matches(text)) return null
        return text.toLongOrNull()?.takeIf { it <= MAX_COUNTER }
    }

    companion object {
        /** A code works this long. */
        val CODE_LIFE = 2.minutes

        /** A code is burned by this many wrong proofs. */
        const val MAX_WRONG = 5

        /** Pairing tries allowed in [TRIES_WINDOW], all codes and browsers together, while a code is waiting. */
        const val MAX_TRIES = 10
        val TRIES_WINDOW = 1.minutes

        /** The least time between two looks at a store that could not be read. */
        val LOAD_RETRY = 1.seconds

        /** A session lasts this long from its hello. */
        val SESSION_LIFE = 60.seconds

        private val TOUCH_EVERY = 1.minutes
        private const val ALPHABET_SIZE = 32
        private const val NONCE_BYTES = 16
        private const val MAX_COUNTER = 9_007_199_254_740_991L
        private val NONCE = Regex("[0-9a-f]{32}")
        private val ID = Regex("[0-9a-f]{32}")
        private val PROOF = Regex("[0-9a-f]{64}")
        private val COUNTER = Regex("[1-9][0-9]{0,15}")

        // What an id that is not paired is checked against.
        private val NO_SECRET = "00".repeat(32)
    }
}
