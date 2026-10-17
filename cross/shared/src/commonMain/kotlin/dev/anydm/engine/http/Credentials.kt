package dev.anydm.engine.http

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A login for HTTP auth (HTTP-11). Kept in memory only; [toString] never shows the password. */
data class Credentials(
    val user: String,
    val password: String,
) {
    init {
        require(':' !in user) { "a user name can't hold ':'" }
        require(sendable(user) && sendable(password)) { "credentials can't hold CR, LF or NUL" }
    }

    override fun toString(): String = "Credentials(user=$user, password=***)"

    private companion object {
        fun sendable(value: String) = value.none { it == '\r' || it == '\n' || it == '\u0000' }
    }
}

/** Where HTTP auth finds a login for an origin (`originOf(url)`); #542's keychain implements it later. */
interface CredentialStore {
    suspend fun credentialsFor(origin: String): Credentials?
}

/** Logins per origin, held in memory only (HTTP-11); the app adds them and passes the store to HttpProtocol. */
class InMemoryCredentials : CredentialStore {
    private val lock = Mutex()
    private val logins = mutableMapOf<String, Credentials>()

    /** The login for [url]'s origin: scheme, host and port, so `https://h` and `https://h:443/f` share one. */
    suspend fun set(
        url: String,
        credentials: Credentials,
    ) = lock.withLock { logins[originOf(url)] = credentials }

    suspend fun remove(url: String) {
        lock.withLock { logins.remove(originOf(url)) }
    }

    override suspend fun credentialsFor(origin: String): Credentials? = lock.withLock { logins[origin] }
}

/** The percent-decoded user and password in [url] (`https://user:password@host/`); null without one or if unusable. */
internal fun userInfoOf(url: String): Credentials? {
    val authority =
        url
            .substringAfter("://", "")
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
    if ('@' !in authority) return null
    val info = authority.substringBeforeLast('@')
    val user = percentDecodedBytes(info.substringBefore(':'))?.decodeToString()
    val password = percentDecodedBytes(info.substringAfter(':', ""))?.decodeToString()
    if (user == null || password == null) return null
    return runCatching { Credentials(user, password) }.getOrNull()
}
