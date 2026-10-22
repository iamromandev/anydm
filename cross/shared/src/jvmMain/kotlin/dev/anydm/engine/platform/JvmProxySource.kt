package dev.anydm.engine.platform

import dev.anydm.engine.net.ProxyType
import dev.anydm.engine.net.SystemProxy
import dev.anydm.engine.net.proxyHostOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.URI

/**
 * The proxy the operating system asks for, as the JVM's [ProxySelector] reports it (NET-15): the system settings of macOS,
 * Windows and GNOME, once the application has called [useSystemProxies]. No PAC scripts, no logins, and no environment
 * variables. Addresses that aren't web or FTP ask as plain sockets, which is how the JVM names SOCKS-only setups; one with
 * no host (a magnet link) asks about a placeholder host that no exclusion list names. A selector that fails or an address
 * it can't be asked about is a [ProxySourceFailure], not "no proxy". [selector] is replaceable in tests.
 */
class JvmProxySource(
    private val selector: () -> ProxySelector? = { ProxySelector.getDefault() },
) : ProxySource {
    override suspend fun select(url: String): SystemProxy? =
        withContext(Dispatchers.IO) {
            val uri = uriOf(url) ?: throw ProxySourceFailure("not an address the system can be asked about")
            val proxy =
                try {
                    selector()?.select(uri)?.firstOrNull { it.type() != Proxy.Type.DIRECT }
                } catch (e: RuntimeException) {
                    throw ProxySourceFailure("the system proxy selector failed", e)
                } ?: return@withContext null
            val address = proxy.address() as? InetSocketAddress ?: throw ProxySourceFailure("the system proxy has no address")
            val type = if (proxy.type() == Proxy.Type.SOCKS) ProxyType.SOCKS5 else ProxyType.HTTP
            SystemProxy(type, address.hostString, address.port)
        }

    // A URI the selector can match: web and FTP addresses as they are, anything else as a socket to its host and port. A
    // host the URI syntax rejects (an underscore) is asked about with that character swapped for a hyphen.
    private fun uriOf(url: String): URI? {
        val scheme = url.substringBefore(':', "").lowercase().takeIf { it.isNotEmpty() && url.contains(':') } ?: return null
        val asked = if (scheme in SCHEMES) scheme else "socket"
        val parsed = runCatching { URI(url) }.getOrNull()
        val host =
            parsed?.host ?: proxyHostOf(url)?.map { if (it.isLetterOrDigit() || it in ".-:") it else '-' }?.joinToString("") ?: PLACEHOLDER
        val port = parsed?.takeIf { it.host != null }?.port ?: -1
        if (parsed != null && asked == scheme && parsed.host != null) return parsed
        return runCatching { URI(asked, null, host, port, "/", null, null) }.getOrNull()
    }

    companion object {
        private val SCHEMES = setOf("http", "https", "ftp", "ftps")
        private const val PLACEHOLDER = "peer.invalid"

        /**
         * Makes the JVM read the system's proxy settings. It takes effect only if it runs before the JVM's first connection or
         * proxy lookup, so the application calls it first thing; an explicit setting of the property is left alone.
         */
        fun useSystemProxies() {
            if (System.getProperty("java.net.useSystemProxies") == null) System.setProperty("java.net.useSystemProxies", "true")
        }
    }
}
