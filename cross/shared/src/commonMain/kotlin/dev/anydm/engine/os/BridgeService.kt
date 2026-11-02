package dev.anydm.engine.os

import dev.anydm.engine.Engine
import dev.anydm.engine.core.RequestContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * What the browser bridge does for the extension (OSI-02), apart from HTTP: adding from a page takes two steps. [add] reads
 * the link without adding it and gives back what it found with a token; [confirm] redeems the token, once, within
 * [tokenLife], and adds the download exactly as it was read, with the headers, referrer and cookies of the first step. The
 * clock is the engine's, so tests run on virtual time. Cookies are sealed in the keychain when the download is added; a
 * refusal never repeats them. Cookies are taken only from a paired browser ([Bridge.add]'s `authenticated`).
 */
class BridgeService(
    private val engine: Engine,
    private val clock: Clock,
    private val tokenLife: Duration = 15.seconds,
    /** Where tokens come from. A token is a bearer capability: pass a cryptographic source (`secureBridgeService` does). */
    private val random: Random,
) : Bridge {
    private class Pending(
        val link: String,
        val context: RequestContext?,
        val expires: Instant,
        /** Whether a paired browser asked for it: then only a paired browser redeems it. */
        val authenticated: Boolean,
    )

    private val lock = Mutex()
    private val pending = LinkedHashMap<String, Pending>()

    override suspend fun add(
        request: Bridge.AddRequest,
        authenticated: Boolean,
    ): Bridge.AddReply {
        // Cookies are a login: they are taken only from a browser that proved it is paired, and a refusal asks nothing of the link.
        if (!authenticated && carriesCookies(request)) {
            return Bridge.AddReply.Refused(Bridge.Problem.UNAUTHORIZED, "cookies are taken only from a paired browser")
        }
        // A page can't name a file on this machine for the app to open.
        LinkIntake.parse(request.url)?.let { if (it.link.startsWith("file:", ignoreCase = true)) return refusedAsNotALink() }
        val context =
            try {
                contextOf(request)
            } catch (_: IllegalArgumentException) {
                // The message names the header and never holds its value; still, the reply says only what was wrong.
                return Bridge.AddReply.Refused(
                    Bridge.Problem.BAD_REQUEST,
                    "a header, the referrer or the cookies hold something HTTP can't send",
                )
            }
        return when (val probed = engine.probeLink(request.url, context)) {
            LinkProbeResult.NotALink -> {
                Bridge.AddReply.Refused(Bridge.Problem.NOT_A_LINK, "not a link anydm takes")
            }

            LinkProbeResult.Unavailable -> {
                Bridge.AddReply.Refused(Bridge.Problem.UNAVAILABLE, "anydm can't read this kind of link")
            }

            is LinkProbeResult.Failed -> {
                Bridge.AddReply.Refused(Bridge.Problem.UNREADABLE, probed.failure.message)
            }

            is LinkProbeResult.Probed -> {
                val token = newToken()
                lock.withLock {
                    val now = clock.now()
                    purge(now)
                    // The link as it was sent: a login in it goes to the keychain when it is added, not before.
                    pending[token] = Pending(request.url, context, now + tokenLife, authenticated)
                    while (pending.size > MAX_PENDING) pending.remove(pending.keys.first())
                }
                Bridge.AddReply.Probed(token, probed.name, probed.size, probed.kind, probed.rangesSupported, probed.duplicate)
            }
        }
    }

    override suspend fun confirm(
        token: String,
        authenticated: Boolean,
    ): Bridge.ConfirmReply {
        val found =
            lock.withLock {
                purge(clock.now())
                val candidate = pending[token]
                when {
                    candidate == null -> null

                    // A paired browser's token is not for anyone else to redeem, nor to use up.
                    candidate.authenticated && !authenticated -> null

                    else -> pending.remove(token)
                }
            } ?: return Bridge.ConfirmReply.UnknownToken
        return when (val added = engine.addLink(found.link, EntryPoint.BRIDGE, found.context)) {
            is AddLinkResult.Added -> Bridge.ConfirmReply.Added(added.download.id)
            AddLinkResult.NotALink -> Bridge.ConfirmReply.Refused(Bridge.Problem.NOT_A_LINK, "not a link anydm takes")
            is AddLinkResult.Duplicate -> Bridge.ConfirmReply.Refused(Bridge.Problem.DUPLICATE, "already in the list")
            is AddLinkResult.Failed -> Bridge.ConfirmReply.Refused(Bridge.Problem.UNAVAILABLE, added.reason)
        }
    }

    // A login of the page's (Authorization) is not the bridge's to take: it would be stored with the download in the clear. A
    // Cookie header is: the store seals it with the cookies.
    private fun contextOf(request: Bridge.AddRequest): RequestContext? {
        val referrer = request.referrer ?: request.pageUrl
        val headers = request.headers.filterNot { it.first.lowercase() in LOGIN_HEADERS }
        val cookies = request.cookies?.takeIf { it.isNotBlank() }
        if (headers.isEmpty() && referrer == null && cookies == null) return null
        return RequestContext(headers, referrer = referrer, cookies = cookies)
    }

    // Cookies as a field or as a Cookie header: the store seals both alike.
    private fun carriesCookies(request: Bridge.AddRequest): Boolean =
        !request.cookies.isNullOrBlank() ||
            request.headers.any { (name, value) -> name.trim().equals("cookie", ignoreCase = true) && value.isNotBlank() }

    private fun refusedAsNotALink() = Bridge.AddReply.Refused(Bridge.Problem.NOT_A_LINK, "not a link anydm takes")

    // Under the lock.
    private fun purge(now: Instant) {
        pending.entries.removeAll { it.value.expires <= now }
    }

    private fun newToken(): String = random.nextBytes(16).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    companion object {
        /** Tokens held at once; the oldest go first, so a page that asks without end can't grow the book. */
        const val MAX_PENDING = 64

        private val LOGIN_HEADERS = setOf("authorization", "proxy-authorization")
    }
}
