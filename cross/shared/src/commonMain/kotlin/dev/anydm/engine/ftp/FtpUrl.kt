package dev.anydm.engine.ftp

import dev.anydm.engine.http.Credentials
import dev.anydm.engine.http.percentDecodedBytes
import dev.anydm.engine.http.userInfoOf

/** The ways a remote file is reached, each with the port its URL scheme means (FTP-01, FTP-04). */
enum class FtpScheme(
    val port: Int,
) {
    /** Plain FTP. */
    FTP(21),

    /** FTP over implicit TLS. */
    FTPS(990),

    /** FTP that upgrades to TLS with `AUTH TLS`. */
    FTPES(21),

    /** SFTP over SSH. */
    SFTP(22),
    ;

    companion object {
        fun of(name: String): FtpScheme? = entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}

/**
 * A remote file's address (FTP-01, FTP-04): [path] is percent-decoded and starts with `/`; [login] is the user and
 * password the URL carried, if it carried a usable pair. [host] keeps the brackets of an IPv6 address.
 */
data class FtpUrl(
    val scheme: FtpScheme,
    val host: String,
    val port: Int,
    val path: String,
    val login: Credentials?,
) {
    /** Scheme, host and port: where a stored login is looked up. Never holds a user or password. */
    val origin: String get() = "${scheme.name.lowercase()}://$host:$port"

    /** The last name in [path], what the file is saved as. */
    val fileName: String get() = path.substringAfterLast('/')

    // The password stays out of anything that prints this.
    override fun toString(): String = "FtpUrl($origin$path, login=${if (login == null) "none" else "***"})"

    companion object {
        /** The address in [url], or null when it isn't an ftp, ftps, ftpes or sftp URL with a host, a good port and a file path. */
        fun parse(url: String): FtpUrl? {
            val scheme = FtpScheme.of(url.substringBefore("://", "")) ?: return null
            val rest = url.substringAfter("://")
            val slash = rest.indexOf('/')
            if (slash < 0) return null
            val authority = rest.substring(0, slash)
            val rawPath = rest.substring(slash)
            if (rawPath == "/") return null

            val hostPort = authority.substringAfterLast('@')
            val (host, portText) =
                if (hostPort.startsWith("[")) {
                    val close = hostPort.indexOf(']')
                    if (close < 0) return null
                    hostPort.substring(0, close + 1) to
                        hostPort.substring(close + 1).removePrefix(":").takeIf { hostPort.length > close + 1 }
                } else {
                    hostPort.substringBefore(':') to hostPort.substringAfter(':', "").takeIf { ':' in hostPort }
                }
            if (host.isEmpty() || host == "[]") return null
            val port =
                if (portText == null) {
                    scheme.port
                } else {
                    portText.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
                }

            val path = percentDecodedBytes(rawPath)?.decodeToString() ?: return null
            return FtpUrl(scheme, host.lowercase(), port, path, userInfoOf(url))
        }
    }
}

/** [url] without the `user:password@` in its authority, whatever the scheme; a URL with none comes back unchanged. */
internal fun stripUserInfo(url: String): String {
    val schemeEnd = url.indexOf("://")
    if (schemeEnd < 0) return url
    val start = schemeEnd + 3
    val end = url.indexOfAny(charArrayOf('/', '?', '#'), start).let { if (it < 0) url.length else it }
    val at = url.lastIndexOf('@', end - 1)
    if (at < start) return url
    return url.substring(0, start) + url.substring(at + 1)
}
