package dev.anydm.engine.net

import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.ProfileId
import dev.anydm.engine.core.ProxyId

/** What a proxy speaks (NET-15). HTTPS is an HTTP proxy reached over TLS. */
enum class ProxyType { HTTP, HTTPS, SOCKS5 }

private const val MAX_NAME = 80

private fun check(
    name: String,
    host: String,
    port: Int,
    username: String?,
    bypass: List<String>,
) {
    require(name.isNotBlank() && name.length <= MAX_NAME) { "name must be 1 to $MAX_NAME characters" }
    require(name.none { it < ' ' }) { "name must be on one line" }
    require(host.isNotBlank() && host.none { it.isWhitespace() || it == '/' || it == '@' }) { "host isn't a host name: $host" }
    require(port in 1..65535) { "port must be 1..65535: $port" }
    require(username == null || username.isNotBlank()) { "username must not be blank, or null for none" }
    BypassList.parse(bypass)
}

/**
 * A saved proxy (NET-15). It never holds its password: that is in the keychain under [account], so nothing here can be
 * logged or exported with a secret in it. [bypass] are the [BypassList] patterns of hosts that skip this proxy.
 */
data class ProxyEntry(
    val id: ProxyId,
    val name: String,
    val type: ProxyType,
    val host: String,
    val port: Int,
    val username: String?,
    val bypass: List<String>,
) {
    init {
        check(name, host, port, username, bypass)
    }

    val account: String get() = proxyAccount(id)
}

/** A [ProxyEntry] to add; the store assigns its id. Same rules as [ProxyEntry]. */
data class NewProxy(
    val name: String,
    val type: ProxyType,
    val host: String,
    val port: Int,
    val username: String? = null,
    val bypass: List<String> = emptyList(),
) {
    init {
        check(name, host, port, username, bypass)
    }
}

/** The keychain account a proxy's password is kept under. */
fun proxyAccount(id: ProxyId): String = "anydm-proxy:${id.value}"

/**
 * Which proxy a download, a profile or the global setting asks for (NET-15). The first one that isn't [Inherit] wins:
 * the download's, then its profile's, then the global one (which is never [Inherit]).
 */
sealed interface ProxyChoice {
    /** Leaves the choice to the next level. */
    data object Inherit : ProxyChoice

    /** No proxy at all. */
    data object Direct : ProxyChoice

    /** Whatever the operating system is set to use for the address. */
    data object System : ProxyChoice

    /** The saved proxy [id]. */
    data class Named(
        val id: ProxyId,
    ) : ProxyChoice

    /** The stored text of this choice: `INHERIT`, `DIRECT`, `SYSTEM` or `PROXY:<id>`. */
    fun encode(): String =
        when (this) {
            Inherit -> "INHERIT"
            Direct -> "DIRECT"
            System -> "SYSTEM"
            is Named -> "PROXY:${id.value}"
        }

    companion object {
        /** The choice [encode] wrote, or null for text that is none. */
        fun decode(text: String): ProxyChoice? =
            when (text) {
                "INHERIT" -> {
                    Inherit
                }

                "DIRECT" -> {
                    Direct
                }

                "SYSTEM" -> {
                    System
                }

                else -> {
                    text
                        .removePrefix("PROXY:")
                        .takeIf { it != text }
                        ?.toLongOrNull()
                        ?.let { Named(ProxyId(it)) }
                }
            }
    }
}

/** The proxy a connection goes through, with its password: short-lived, never stored, and its [toString] hides the secret. */
class ProxyEndpoint(
    val type: ProxyType,
    val host: String,
    val port: Int,
    val username: String? = null,
    val password: String? = null,
) {
    init {
        require(port in 1..65535) { "port must be 1..65535: $port" }
        require(password == null || username != null) { "a password needs a username" }
    }

    override fun equals(other: Any?): Boolean =
        other is ProxyEndpoint &&
            type == other.type &&
            host == other.host &&
            port == other.port &&
            username == other.username &&
            password == other.password

    override fun hashCode(): Int = listOf(type, host, port, username, password).hashCode()

    override fun toString(): String =
        "$type $host:$port" + (username?.let { " as $it" } ?: "") + (if (password != null) " (password hidden)" else "")
}

/** What a connection to one address does about proxies (NET-15). */
sealed interface ProxyRoute {
    /** Connect straight to the address. */
    data object Direct : ProxyRoute

    /** Connect through [endpoint]. */
    data class Via(
        val endpoint: ProxyEndpoint,
    ) : ProxyRoute

    /** Don't connect at all, so nothing leaks: the chosen proxy can't be used, and [reason] says why. */
    data class Blocked(
        val reason: String,
    ) : ProxyRoute
}

/** Asks which route a download's connections to [url] take (NET-15); the protocols call it before they connect. */
fun interface ProxyRouter {
    suspend fun route(
        downloadId: DownloadId,
        url: String,
    ): ProxyRoute

    /** The route for work that has no download yet, such as listing a playlist: [choice] and [profile] as a new download would name them. */
    suspend fun routeNew(
        choice: ProxyChoice,
        profile: ProfileId?,
        url: String,
    ): ProxyRoute = ProxyRoute.Direct

    companion object {
        /** No proxies: every connection is direct. */
        val Direct: ProxyRouter = ProxyRouter { _, _ -> ProxyRoute.Direct }
    }
}
