package dev.anydm.engine.net

import dev.anydm.engine.http.HttpRequest
import dev.anydm.engine.http.HttpTransport

/**
 * Finds the public address by asking an outside server ([url], which answers with the caller's address as plain text), with
 * the request sent only through the network checked. It asks that server only when [lookup] is called.
 */
class TransportPublicIpLookup(
    private val transport: HttpTransport,
    private val url: String = DEFAULT_URL,
) : PublicIpLookup {
    override suspend fun lookup(network: NetworkInfo): String =
        withNetwork(network) {
            val response = transport.open(HttpRequest(url))
            try {
                check(response.status in 200..299) { "the address server answered ${response.status}" }
                val bytes = ByteArray(MAX_BYTES)
                var n = 0
                while (n < bytes.size) {
                    val read = response.body.read(bytes, n, bytes.size - n)
                    if (read < 0) break
                    n += read
                }
                val text = bytes.decodeToString(0, n).trim()
                checkNotNull(normalizeIp(text)) { "the address server did not answer with an address" }
            } finally {
                response.body.close()
            }
        }

    companion object {
        const val DEFAULT_URL = "https://api.ipify.org/"
        private const val MAX_BYTES = 64
    }
}

/**
 * The canonical form of an IP address in [text], or null when it is not one: IPv4 with ASCII digits and octets 0..255, IPv6
 * in hexadecimal groups (with "::" at most once, and an IPv4 tail allowed). Brackets and a zone ("%en0") are dropped and IPv6
 * is lower-cased, so the same address always compares equal.
 */
internal fun normalizeIp(text: String): String? {
    var t = text.trim()
    if (t.startsWith('[') && t.endsWith(']')) t = t.substring(1, t.length - 1)
    if (':' in t) t = t.substringBefore('%')
    if (t.isEmpty() || t.length > MAX_IP_CHARS) return null
    return if (':' in t) normalizeV6(t) else normalizeV4(t)
}

private const val MAX_IP_CHARS = 45

private fun isAsciiDigit(c: Char) = c in '0'..'9'

private fun isAsciiHex(c: Char) = isAsciiDigit(c) || c in 'a'..'f' || c in 'A'..'F'

private fun normalizeV4(t: String): String? {
    val parts = t.split('.')
    if (parts.size != 4) return null
    val octets =
        parts.map { p ->
            if (p.length !in 1..3 || !p.all(::isAsciiDigit)) return null
            p.toInt().takeIf { it <= 255 } ?: return null
        }
    return octets.joinToString(".")
}

private fun normalizeV6(t: String): String? {
    if (":::" in t) return null
    val gap = t.indexOf("::")
    if (gap >= 0 && gap != t.lastIndexOf("::")) return null
    val head = if (gap >= 0) t.substring(0, gap) else t
    val tail = if (gap >= 0) t.substring(gap + 2) else ""
    val groups = (if (head.isEmpty()) emptyList() else head.split(':')) + (if (tail.isEmpty()) emptyList() else tail.split(':'))
    var units = 0
    for ((i, g) in groups.withIndex()) {
        if (i == groups.lastIndex && '.' in g && (gap < 0 || tail.isNotEmpty())) {
            if (normalizeV4(g) == null) return null
            units += 2
        } else {
            if (g.length !in 1..4 || !g.all(::isAsciiHex)) return null
            units += 1
        }
    }
    if (if (gap >= 0) units > 7 else units != 8) return null
    return t.lowercase()
}
