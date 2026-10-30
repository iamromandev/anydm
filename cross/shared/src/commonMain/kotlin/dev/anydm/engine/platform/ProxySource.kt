package dev.anydm.engine.platform

import dev.anydm.engine.net.SystemProxy

/** Asks the OS which proxy it wants for an address (NET-15). No PAC scripts, and no logins: the OS doesn't tell them. */
fun interface ProxySource {
    /**
     * The proxy for [url], or null for a direct connection. Throws [ProxySourceFailure] when the system's settings can't be
     * read for the address: the route is then blocked, not sent direct.
     */
    suspend fun select(url: String): SystemProxy?

    companion object {
        val None: ProxySource = ProxySource { null }
    }
}

/** The operating system's proxy settings could not be read for an address (NET-15). */
class ProxySourceFailure(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
