package dev.anydm.engine.http

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

/**
 * Basic and Digest auth over [inner] (HTTP-11). A 401 with a challenge it supports is answered once, from [credentials]
 * for the origin that answered, else the request URL's own user and password if it is that origin; a Digest answer
 * whose nonce went stale gets one more. The login is then kept per origin, and later requests there carry it up front,
 * so block workers don't each pay a 401, as long as it is still the login the store or the URL gives. Origins and
 * Digest's `uri` come from the URL as [inner] sends it. A request that brings its own `Authorization` is left alone.
 */
internal class AuthTransport(
    private val inner: HttpTransport,
    private val credentials: CredentialStore,
    private val random: Random = Random.Default,
) : HttpTransport by inner {
    /** [fromStore]: [credentials] came from the store, so they stop being used once the store drops them. */
    private class Login(
        val challenge: Challenge,
        val credentials: Credentials,
        val fromStore: Boolean,
    ) {
        /** Requests sent with this nonce so far; under [lock]. */
        var nc = 0L
    }

    private val lock = Mutex()
    private val logins = mutableMapOf<String, Login>()

    override suspend fun open(request: HttpRequest): HttpResponse {
        if (request.headers.any { it.first.equals(AUTHORIZATION, ignoreCase = true) }) return inner.open(request)
        val url = inner.canonicalUrl(request.url)
        val known =
            lock.withLock { logins[originOf(url)] }?.takeIf { login ->
                loginFor(originOf(url), url, login)?.first == login.credentials
            }
        var response = inner.open(known?.let { request.authorized(it, url) } ?: request)
        var answered = 0
        while (response.status == 401 && answered < 2) {
            val challenge = chooseChallenge(challengesOf(response.headers)) ?: return response
            if (answered == 1 && !challenge.params["stale"].equals("true", ignoreCase = true)) return response
            val finalUrl = inner.canonicalUrl(response.finalUrl)
            val origin = originOf(finalUrl)
            val cached = lock.withLock { logins[origin] }
            val (login, fromStore) = loginFor(origin, url.takeIf { originOf(it) == origin }, cached) ?: return response
            val next = lock.withLock { loginAt(origin, challenge, login, fromStore) }
            response.body.close()
            response = inner.open(HttpRequest(finalUrl, request.method, request.headers).authorized(next, finalUrl))
            answered++
        }
        return response
    }

    /** The store's login for [origin], else [url]'s own (when it is that origin), else a cached one that came from a URL. */
    private suspend fun loginFor(
        origin: String,
        url: String?,
        cached: Login?,
    ): Pair<Credentials, Boolean>? =
        credentials.credentialsFor(origin)?.let { it to true }
            ?: url?.let(::userInfoOf)?.let { it to false }
            ?: cached?.takeIf { !it.fromStore && (url == null || !hasUserInfo(url)) }?.let { it.credentials to false }

    /** The login answering [challenge]; one for the same challenge and credentials is kept, so its nonce count goes on. */
    private fun loginAt(
        origin: String,
        challenge: Challenge,
        credentials: Credentials,
        fromStore: Boolean,
    ): Login {
        val existing = logins[origin]
        if (existing != null &&
            existing.credentials == credentials &&
            existing.challenge.scheme == challenge.scheme &&
            existing.challenge.params - "stale" == challenge.params - "stale"
        ) {
            return existing
        }
        return Login(challenge, credentials, fromStore).also { logins[origin] = it }
    }

    private suspend fun HttpRequest.authorized(
        login: Login,
        url: String,
    ): HttpRequest {
        val value =
            if (login.challenge.scheme == "basic") {
                basicAuthorization(login.credentials)
            } else {
                val (nc, cnonce) = lock.withLock { ++login.nc to random.nextBytes(16).toHex() }
                digestAuthorization(login.challenge, login.credentials, method, requestTarget(url), nc, cnonce)
            }
        return copy(headers = headers + (AUTHORIZATION to value))
    }

    private companion object {
        const val AUTHORIZATION = "Authorization"

        fun ByteArray.toHex() = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

        fun hasUserInfo(url: String) =
            '@' in
                url
                    .substringAfter("://", "")
                    .substringBefore('/')
                    .substringBefore('?')
                    .substringBefore('#')
    }
}

/** The path and query [url] asks for, as Digest's `uri` names it: `/` when it has none. */
internal fun requestTarget(url: String): String {
    val rest = url.substringAfter("://", url)
    val start = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
    val target = if (start < 0) "" else rest.substring(start).substringBefore('#')
    return when {
        target.isEmpty() -> "/"
        target.startsWith("?") -> "/$target"
        else -> target
    }
}
