package dev.anydm.engine.net

/**
 * Hosts that skip the proxy (NET-15). Loopback always does. A pattern is a host name (`intranet.example.com`), a domain
 * (`*.corp.test` or `.corp.test`: the domain and everything under it), `<local>` (a name with no dot), an IP address, or
 * a CIDR range (`10.0.0.0/8`, `fd00::/8`). Blank patterns are ignored; a pattern that is none of these is rejected.
 */
class BypassList private constructor(
    private val rules: List<Rule>,
) {
    fun matches(host: String): Boolean {
        val name =
            host
                .trim()
                .removePrefix("[")
                .removeSuffix("]")
                .lowercase()
        if (name.isEmpty()) return false
        val address = parseAddress(name)
        if (address != null) {
            // ::ffff:a.b.c.d is the IPv4 address a.b.c.d.
            val mapped =
                if (address.size == 16 && address.take(10).all { it == 0.toByte() } && address[10] == (-1).toByte() &&
                    address[11] == (-1).toByte()
                ) {
                    address.copyOfRange(12, 16)
                } else {
                    null
                }
            return listOfNotNull(address, mapped).any { a -> LOOPBACK.any { it.contains(a) } || rules.any { it.matches(name, a) } }
        }
        if (name == "localhost" || name.endsWith(".localhost")) return true
        return rules.any { it.matches(name, null) }
    }

    private sealed interface Rule {
        fun matches(
            name: String,
            address: ByteArray?,
        ): Boolean
    }

    private class Exact(
        private val host: String,
    ) : Rule {
        override fun matches(
            name: String,
            address: ByteArray?,
        ) = name == host
    }

    private class Domain(
        private val domain: String,
    ) : Rule {
        override fun matches(
            name: String,
            address: ByteArray?,
        ) = address == null && (name == domain || name.endsWith(".$domain"))
    }

    private data object Local : Rule {
        override fun matches(
            name: String,
            address: ByteArray?,
        ) = address == null && '.' !in name
    }

    private class Range(
        private val base: ByteArray,
        private val bits: Int,
    ) : Rule {
        override fun matches(
            name: String,
            address: ByteArray?,
        ) = address != null && contains(address)

        fun contains(address: ByteArray): Boolean {
            if (address.size != base.size) return false
            var left = bits
            for (i in base.indices) {
                if (left <= 0) break
                val mask = if (left >= 8) 0xFF else (0xFF shl (8 - left)) and 0xFF
                if ((address[i].toInt() and mask) != (base[i].toInt() and mask)) return false
                left -= 8
            }
            return true
        }
    }

    companion object {
        private val LOOPBACK = listOf(Range(byteArrayOf(127, 0, 0, 0), 8), Range(ipv6("::1"), 128))

        fun parse(patterns: List<String>): BypassList = BypassList(patterns.mapNotNull(::rule))

        private fun rule(raw: String): Rule? {
            val pattern = raw.trim().lowercase()
            if (pattern.isEmpty()) return null
            require(pattern.none { it.isWhitespace() }) { "a bypass pattern has no spaces: $raw" }
            if (pattern == "<local>") return Local
            if ('/' in pattern) {
                val base = pattern.substringBefore('/').removePrefix("[").removeSuffix("]")
                val bits = pattern.substringAfter('/').toIntOrNull()
                val address = parseAddress(base)
                require(address != null && bits != null && bits in 0..address.size * 8) { "not a CIDR range: $raw" }
                return Range(address, bits)
            }
            val bare = pattern.removePrefix("[").removeSuffix("]")
            val address = parseAddress(bare)
            if (address != null) return Range(address, address.size * 8)
            if (pattern.startsWith("*.")) return Domain(pattern.removePrefix("*."))
            if (pattern.startsWith(".")) return Domain(pattern.removePrefix("."))
            return Exact(pattern)
        }

        private fun ipv6(text: String): ByteArray = checkNotNull(parseAddress(text))
    }
}

/** 4 bytes for an IPv4 literal, 16 for an IPv6 one, null for anything else (a host name). */
internal fun parseAddress(text: String): ByteArray? = parseIpv4(text) ?: parseIpv6(text)

private fun parseIpv4(text: String): ByteArray? {
    val parts = text.split('.')
    if (parts.size != 4) return null
    val bytes = ByteArray(4)
    for ((i, part) in parts.withIndex()) {
        if (part.isEmpty() || part.length > 3 || part.any { it !in '0'..'9' }) return null
        val value = part.toInt()
        if (value > 255) return null
        bytes[i] = value.toByte()
    }
    return bytes
}

private fun parseIpv6(text: String): ByteArray? {
    if (':' !in text) return null
    // A dotted IPv4 tail (::ffff:127.0.0.1) stands for its last two groups.
    val tail4 = text.substringAfterLast(':').takeIf { '.' in it }?.let { parseIpv4(it) }
    if (tail4 != null) {
        val head =
            text.substringBeforeLast(':') + ":" + (((tail4[0].toInt() and 0xFF) shl 8) or (tail4[1].toInt() and 0xFF)).toString(16) + ":" +
                (((tail4[2].toInt() and 0xFF) shl 8) or (tail4[3].toInt() and 0xFF)).toString(16)
        return parseIpv6(head)
    }
    val halves = text.split("::")
    if (halves.size > 2) return null

    fun groups(part: String): List<Int>? {
        if (part.isEmpty()) return emptyList()
        return part.split(':').map { group ->
            if (group.isEmpty() || group.length > 4 || group.any { it !in "0123456789abcdefABCDEF" }) return null
            group.toInt(16)
        }
    }
    val head = groups(halves[0]) ?: return null
    val tail = if (halves.size == 2) groups(halves[1]) ?: return null else emptyList()
    val missing = 8 - head.size - tail.size
    if (halves.size == 1 && missing != 0) return null
    if (halves.size == 2 && missing < 1) return null
    val all = head + List(if (halves.size == 2) missing else 0) { 0 } + tail
    val bytes = ByteArray(16)
    for ((i, group) in all.withIndex()) {
        bytes[i * 2] = (group shr 8).toByte()
        bytes[i * 2 + 1] = group.toByte()
    }
    return bytes
}
