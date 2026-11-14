package dev.anydm.engine.torrent

/** A magnet link, read: the info hash, the display name from `dn` and the usable trackers from `tr`. */
class MagnetLink(
    /** The 20-byte SHA-1 info hash. */
    val infoHash: ByteArray,
    val name: String?,
    /** Tracker URLs (http, https, udp only), without duplicates, at most [MAX_TRACKERS]. */
    val trackers: List<String>,
) {
    /** The info hash as 40 lowercase hex digits. */
    val infoHashHex: String get() = infoHash.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    override fun equals(other: Any?): Boolean =
        other is MagnetLink && infoHash.contentEquals(other.infoHash) && name == other.name && trackers == other.trackers

    override fun hashCode(): Int = 31 * (31 * infoHash.contentHashCode() + (name?.hashCode() ?: 0)) + trackers.hashCode()

    override fun toString(): String = "MagnetLink($infoHashHex, $name, $trackers)"
}

sealed interface MagnetResult {
    data class Ok(
        val link: MagnetLink,
    ) : MagnetResult

    /** No `xt=urn:btih:` with a 40-hex or 32-base32 hash: not a magnet link this client can use. */
    data object Invalid : MagnetResult

    /** A good magnet with no tracker that can be announced to (there is no DHT to fall back on). */
    data object NoTrackers : MagnetResult
}

private const val SCHEME = "magnet:?"
private const val BTIH = "urn:btih:"
private const val HASH_BYTES = 20
private const val HEX_LENGTH = 40
private const val BASE32_LENGTH = 32
private const val BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

/**
 * Reads the magnet link [text] (BEP 9). Keys, the scheme and `urn:btih:` are case-insensitive; `dn` and `tr` are percent-decoded
 * (and a `+` in `dn` is a space). The first `xt` that is a `urn:btih:` with 40 hex digits or 32 base32 characters (RFC 4648, no
 * padding) is the hash; every other parameter is ignored. Untrusted input: never throws.
 */
fun parseMagnet(text: String): MagnetResult {
    val trimmed = text.trim()
    if (!trimmed.startsWith(SCHEME, ignoreCase = true)) return MagnetResult.Invalid
    var hash: ByteArray? = null
    var name: String? = null
    val trackers = LinkedHashSet<String>()
    for (pair in trimmed.substring(SCHEME.length).split('&')) {
        val eq = pair.indexOf('=')
        if (eq <= 0) continue
        val key = pair.substring(0, eq).lowercase()
        val value = pair.substring(eq + 1)
        when {
            key == "xt" || key.startsWith("xt.") -> {
                if (hash == null) hash = btih(percentDecode(value, plusIsSpace = false))
            }

            key == "dn" -> {
                if (name == null) name = cleanName(percentDecode(value, plusIsSpace = true))
            }

            key == "tr" || key.startsWith("tr.") -> {
                if (trackers.size <
                    MAX_TRACKERS
                ) {
                    usableTracker(percentDecode(value, plusIsSpace = false))?.let { trackers.add(it) }
                }
            }
        }
    }
    val found = hash ?: return MagnetResult.Invalid
    if (trackers.isEmpty()) return MagnetResult.NoTrackers
    return MagnetResult.Ok(MagnetLink(found, name, trackers.toList()))
}

/** The hash of an `urn:btih:` value, or null when it is not one or the hash is neither 40 hex digits nor 32 base32 characters. */
private fun btih(xt: String): ByteArray? {
    if (!xt.startsWith(BTIH, ignoreCase = true)) return null
    val digits = xt.substring(BTIH.length)
    return when (digits.length) {
        HEX_LENGTH -> fromHex(digits)
        BASE32_LENGTH -> fromBase32(digits)
        else -> null
    }
}

/** The value of an ASCII hex digit, else -1: `digitToIntOrNull` takes the digits and full-width letters of other scripts too. */
private fun asciiHex(c: Char): Int =
    when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }

private fun fromHex(s: String): ByteArray? {
    val out = ByteArray(HASH_BYTES)
    for (i in out.indices) {
        val hi = asciiHex(s[2 * i])
        val lo = asciiHex(s[2 * i + 1])
        if (hi < 0 || lo < 0) return null
        out[i] = ((hi shl 4) or lo).toByte()
    }
    return out
}

private fun fromBase32(s: String): ByteArray? {
    // upper-casing turns some non-ASCII letters (`ſ`, `ı`) into ASCII ones: only ASCII is looked at
    if (s.any { it.code > 0x7F }) return null
    val out = ByteArray(HASH_BYTES)
    var acc = 0
    var bits = 0
    var at = 0
    for (c in s.uppercase()) {
        val v = BASE32_ALPHABET.indexOf(c)
        if (v < 0) return null
        acc = (acc shl 5) or v
        bits += 5
        if (bits >= 8) {
            bits -= 8
            out[at++] = (acc ushr bits).toByte()
            acc = acc and ((1 shl bits) - 1)
        }
    }
    return out
}

/** [url] when it is an http, https or udp URL with a host, in printable ASCII and not longer than [MAX_TRACKER_URL_BYTES]. */
private fun usableTracker(url: String): String? {
    val u = url.trim()
    if (u.length > MAX_TRACKER_URL_BYTES || u.any { it < '!' || it > '~' }) return null
    val schemeEnd = u.indexOf("://")
    if (schemeEnd <= 0) return null
    if (u.substring(0, schemeEnd).lowercase() !in TRACKER_SCHEMES) return null
    val host = u.substring(schemeEnd + 3).takeWhile { it != '/' && it != '?' && it != '#' }
    return u.takeIf { host.isNotEmpty() }
}

private val TRACKER_SCHEMES = setOf("http", "https", "udp")

/** [s] with `%XX` sequences turned into the UTF-8 bytes they stand for; a `%` not followed by two hex digits is kept as written. */
private fun percentDecode(
    s: String,
    plusIsSpace: Boolean,
): String {
    if ('%' !in s && !(plusIsSpace && '+' in s)) return s
    val raw = s.encodeToByteArray()
    val out = ByteArray(raw.size)
    var n = 0
    var i = 0
    while (i < raw.size) {
        val b = raw[i].toInt()
        val hi = if (b == '%'.code && i + 2 < raw.size) hexValue(raw[i + 1]) else -1
        val lo = if (hi >= 0) hexValue(raw[i + 2]) else -1
        when {
            hi >= 0 && lo >= 0 -> {
                out[n++] = ((hi shl 4) or lo).toByte()
                i += 3
            }

            plusIsSpace && b == '+'.code -> {
                out[n++] = ' '.code.toByte()
                i++
            }

            else -> {
                out[n++] = raw[i]
                i++
            }
        }
    }
    return out.decodeToString(0, n)
}

private fun hexValue(b: Byte): Int = asciiHex((b.toInt() and 0xFF).toChar())

/** The most characters of a display name that are kept. */
private const val MAX_NAME_CHARS = 255

/** The characters that change the direction of the text around them (they can make a name read as another). */
private fun isBidiControl(c: Char): Boolean =
    c == '\u061C' || c == '\u200E' || c == '\u200F' || c in '\u202A'..'\u202E' || c in '\u2066'..'\u2069'

/**
 * [raw] as a name to show: control and direction-changing characters taken out, trimmed, at most [MAX_NAME_CHARS] characters (never
 * cut inside a surrogate pair); null when nothing is left.
 */
private fun cleanName(raw: String): String? {
    val kept = raw.filterNot { it.isISOControl() || isBidiControl(it) }.trim()
    val cut = if (kept.length > MAX_NAME_CHARS) kept.take(MAX_NAME_CHARS).dropLastWhile { it.isHighSurrogate() } else kept
    return cut.trim().takeIf { it.isNotEmpty() }
}
