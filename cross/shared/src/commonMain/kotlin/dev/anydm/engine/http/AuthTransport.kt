package dev.anydm.engine.http

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

/**
 * Basic and Digest auth over [inner] (HTTP-11). A 401 with a challenge it supports is answered once, from [credentials]
 * for the origin that answered, else the request URL's own user and password if it is that origin; a Digest answer
 * whose nonce went stale gets one more. The login is then kept per origin, and later requests there carry it up front,
 * so block workers don't each pay a 401. A request that brings its own `Authorization` is left alone.
 */
internal class AuthTransport(
    private val inner: HttpTransport,
    private val credentials: CredentialStore,
    private val random: Random = Random.Default,
) : HttpTransport {
    private class Login(
        val challenge: Challenge,
        val credentials: Credentials,
    ) {
        /** Requests sent with this nonce so far; under [lock]. */
        var nc = 0L
    }

    private val lock = Mutex()
    private val logins = mutableMapOf<String, Login>()

    override suspend fun open(request: HttpRequest): HttpResponse {
        if (request.headers.any { it.first.equals(AUTHORIZATION, ignoreCase = true) }) return inner.open(request)
        val known = lock.withLock { logins[originOf(request.url)] }
        var response = inner.open(known?.let { request.authorized(it, request.url) } ?: request)
        var answered = 0
        while (response.status == 401 && answered < 2) {
            val challenge = chooseChallenge(challengesOf(response.headers)) ?: return response
            if (answered == 1 && !challenge.params["stale"].equals("true", ignoreCase = true)) return response
            val origin = originOf(response.finalUrl)
            val login =
                credentials.credentialsFor(origin)
                    ?: userInfoOf(request.url)?.takeIf { originOf(request.url) == origin }
                    ?: lock.withLock { logins[origin]?.credentials }
                    ?: return response
            val next = Login(challenge, login)
            lock.withLock { logins[origin] = next }
            val finalUrl = response.finalUrl
            response.body.close()
            response = inner.open(HttpRequest(finalUrl, request.method, request.headers).authorized(next, finalUrl))
            answered++
        }
        return response
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
