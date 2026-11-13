package dev.anydm.engine.torrent

/** A peer to connect to: a dotted-quad IPv4 [host] and a [port] from 1 to 65535. Build one with [peerAddr]. */
data class PeerAddr(
    val host: String,
    val port: Int,
)

private const val DEFAULT_INTERVAL_S = 1_800
private const val MAX_INTERVAL_S = 86_400
private const val UNKNOWN_REASON = "Unknown reason"

/**
 * The [PeerAddr] for [host] and [port], or null when it is not one we may connect to: [host] must be a plain dotted-quad IPv4
 * address (no names, so a tracker cannot make us look anything up; no IPv6 in this version) that is not 0.0.0.0, multicast
 * (224.0.0.0/4) or the broadcast address, and [port] must be 1 to 65535.
 */
fun peerAddr(
    host: String,
    port: Int,
): PeerAddr? {
    if (port !in 1..65_535) return null
    val parts = host.split('.')
    if (parts.size != 4) return null
    val octets = parts.map { octet(it) ?: return null }
    val unusable = octets.all { it == 0 } || octets[0] in 224..239 || octets.all { it == 255 }
    return if (unusable) null else PeerAddr(host, port)
}

/** One decimal octet of 1 to 3 ASCII digits, no leading zero, 0 to 255. */
private fun octet(text: String): Int? {
    if (text.isEmpty() || text.length > 3 || text.any { it !in '0'..'9' } || (text.length > 1 && text[0] == '0')) return null
    return text.toInt().takeIf { it <= 255 }
}

/** Adds the usable peers of the 6-byte compact entries (four bytes of IPv4, a big-endian port) in [bytes] from [from] to [out], up to the cap. */
private fun compactPeers(
    bytes: ByteArray,
    from: Int,
    out: MutableCollection<PeerAddr>,
) {
    var at = from
    while (at + 6 <= bytes.size && out.size < MAX_ANNOUNCE_PEERS) {
        val host = (0 until 4).joinToString(".") { (bytes[at + it].toInt() and 0xFF).toString() }
        peerAddr(host, ((bytes[at + 4].toInt() and 0xFF) shl 8) or (bytes[at + 5].toInt() and 0xFF))?.let { out += it }
        at += 6
    }
}

/**
 * [text] fit to show: no control, line-separator or bidirectional characters, trimmed, at most [MAX_FAILURE_REASON] characters
 * (never cutting a surrogate pair in two), never empty.
 */
private fun cleanReason(text: String): String {
    val clean =
        text.filter {
            it >= ' ' && it !in '\u007F'..'\u009F' && it !in '\u2028'..'\u2029' && it !in '\u202A'..'\u202E' && it !in '\u2066'..'\u2069'
        }
    var cut = clean.trim().take(MAX_FAILURE_REASON)
    if (cut.isNotEmpty() && cut.last().isHighSurrogate()) cut = cut.dropLast(1)
    return cut.trim().ifEmpty { UNKNOWN_REASON }
}

/** What happened on an announce; sent as the `event` parameter. A periodic announce has none (null). */
enum class AnnounceEvent { Started, Stopped, Completed }

/** What we tell a tracker. Upload is always 0 in this version. */
class AnnounceRequest(
    val infoHash: ByteArray,
    val peerId: ByteArray,
    val port: Int,
    val downloaded: Long,
    val left: Long,
    val event: AnnounceEvent? = null,
    val numWant: Int = NUM_WANT,
    /** UDP only: a random id that lets the tracker tell us apart when our address changes. */
    val key: Int = 0,
)

const val NUM_WANT = 50

/** The most peers taken from one announce answer; the rest are ignored. */
const val MAX_ANNOUNCE_PEERS = 500

/** The longest failure text kept from a tracker, in characters. */
const val MAX_FAILURE_REASON = 200

/** The longest HTTP tracker response read; a longer one is refused. */
const val MAX_TRACKER_RESPONSE = 1 shl 20

/** What a tracker answers with a usable announce. */
sealed interface TrackerReply

class AnnounceResult(
    val peers: List<PeerAddr>,
    /** Seconds until the next announce, as the tracker wants it; 1800 when it does not say. */
    val intervalS: Int,
    /** The tracker's `min interval` in seconds, or null. */
    val minIntervalS: Int? = null,
) : TrackerReply

/** The tracker said no ([malformed] false: its own `failure reason`) or answered with something unusable ([malformed] true). */
class TrackerFailure(
    val reason: String,
    val malformed: Boolean = false,
) : TrackerReply

private fun isUnreserved(c: Int) =
    c in 'A'.code..'Z'.code || c in 'a'.code..'z'.code || c in '0'.code..'9'.code || c == '.'.code || c == '-'.code || c == '_'.code ||
        c == '~'.code

/** [bytes] percent-encoded for a URL query: letters, digits and `.-_~` as they are, every other byte as `%XX` in capitals. */
fun percentEncode(bytes: ByteArray): String {
    val out = StringBuilder(bytes.size * 3)
    for (b in bytes) {
        val c = b.toInt() and 0xFF
        if (isUnreserved(c)) {
            out.append(c.toChar())
        } else {
            out.append('%').append(HEX[c shr 4]).append(HEX[c and 15])
        }
    }
    return out.toString()
}

private const val HEX = "0123456789ABCDEF"

/** The announce URL for [base] with [req]'s parameters; [base]'s own query is kept and any `#fragment` dropped. */
fun httpAnnounceUrl(
    base: String,
    req: AnnounceRequest,
): String {
    val url = base.substringBefore('#')
    val separator =
        when {
            '?' !in url -> "?"
            url.endsWith('?') || url.endsWith('&') -> ""
            else -> "&"
        }
    val event =
        when (req.event) {
            AnnounceEvent.Started -> "&event=started"
            AnnounceEvent.Stopped -> "&event=stopped"
            AnnounceEvent.Completed -> "&event=completed"
            null -> ""
        }
    return "$url${separator}info_hash=${percentEncode(req.infoHash)}&peer_id=${percentEncode(req.peerId)}&port=${req.port}" +
        "&uploaded=0&downloaded=${req.downloaded}&left=${req.left}&compact=1&numwant=${req.numWant}$event"
}

private fun seconds(
    value: BValue?,
    default: Int?,
): Int? {
    val v = (value as? BValue.BInt)?.v ?: return default
    return if (v <= 0) default else minOf(v, MAX_INTERVAL_S.toLong()).toInt()
}

/**
 * Reads an HTTP tracker's body: peers (compact or dictionary form) and intervals, or the failure. Refuses bodies over
 * [MAX_TRACKER_RESPONSE] bytes and anything that is not a bencoded dictionary. Text from the tracker is cleaned before it is returned.
 */
fun parseHttpAnnounce(bytes: ByteArray): TrackerReply {
    if (bytes.size > MAX_TRACKER_RESPONSE) return TrackerFailure("Response too large", malformed = true)
    val root =
        (decodeBencode(bytes) as? DecodeResult.Ok)?.value as? BValue.BDict
            ?: return TrackerFailure("Bad tracker response", malformed = true)
    root.entries["failure reason"]?.let { failure ->
        val text = (failure as? BValue.BBytes)?.b ?: return TrackerFailure("Bad tracker response", malformed = true)
        return TrackerFailure(cleanReason(text.decodeToString()))
    }
    val peers = LinkedHashSet<PeerAddr>()
    when (val list = root.entries["peers"]) {
        is BValue.BBytes -> compactPeers(list.b, 0, peers)
        is BValue.BList -> for (item in list.items) if (peers.size < MAX_ANNOUNCE_PEERS) dictionaryPeer(item)?.let { peers += it }
        else -> Unit
    }
    return AnnounceResult(
        peers = peers.toList(),
        intervalS = seconds(root.entries["interval"], DEFAULT_INTERVAL_S) ?: DEFAULT_INTERVAL_S,
        minIntervalS = seconds(root.entries["min interval"], null),
    )
}

private fun dictionaryPeer(item: BValue): PeerAddr? {
    val dict = (item as? BValue.BDict)?.entries ?: return null
    val host = (dict["ip"] as? BValue.BBytes)?.b?.decodeToString() ?: return null
    val port = (dict["port"] as? BValue.BInt)?.v ?: return null
    return if (port in 1..65_535) peerAddr(host, port.toInt()) else null
}

/** The outcome of reading one UDP datagram while waiting for the answer to our request. */
sealed interface UdpReply<out T> {
    class Ok<T>(
        val value: T,
    ) : UdpReply<T>

    /** The tracker answered our request with an error message. */
    class Failure(
        val reason: String,
    ) : UdpReply<Nothing>

    /** Not an answer to our request (wrong transaction id, wrong action, too short): keep waiting. */
    data object Ignored : UdpReply<Nothing>
}

private const val UDP_MAGIC = 0x41727101980L
private const val ACTION_CONNECT = 0
private const val ACTION_ANNOUNCE = 1
private const val ACTION_ERROR = 3

private fun ByteArray.put(
    at: Int,
    v: Long,
    size: Int,
) {
    for (i in 0 until size) this[at + i] = (v ushr (8 * (size - 1 - i))).toByte()
}

private fun ByteArray.u32(at: Int): Int =
    ((this[at].toInt() and 0xFF) shl 24) or ((this[at + 1].toInt() and 0xFF) shl 16) or
        ((this[at + 2].toInt() and 0xFF) shl 8) or (this[at + 3].toInt() and 0xFF)

/** The 16-byte BEP 15 connect request. */
fun connectRequest(transactionId: Int): ByteArray {
    val out = ByteArray(16)
    out.put(0, UDP_MAGIC, 8)
    out.put(8, ACTION_CONNECT.toLong(), 4)
    out.put(12, transactionId.toLong(), 4)
    return out
}

/**
 * Reads [bytes] as the answer to the request with [transactionId]: [UdpReply.Ok] for [action] with at least [minLength] bytes,
 * [UdpReply.Failure] for an error message, [UdpReply.Ignored] for anything else (a late answer to an older request, junk).
 */
private fun <T> udpReply(
    bytes: ByteArray,
    transactionId: Int,
    action: Int,
    minLength: Int,
    read: (ByteArray) -> T,
): UdpReply<T> {
    if (bytes.size < 8 || bytes.u32(4) != transactionId) return UdpReply.Ignored
    val got = bytes.u32(0)
    if (got == ACTION_ERROR) return UdpReply.Failure(cleanReason(bytes.decodeToString(8, minOf(bytes.size, 8 + 4 * MAX_FAILURE_REASON))))
    if (got != action || bytes.size < minLength) return UdpReply.Ignored
    return UdpReply.Ok(read(bytes))
}

/** The connection id from a connect reply to [transactionId]. */
fun parseConnect(
    bytes: ByteArray,
    transactionId: Int,
): UdpReply<Long> =
    udpReply(bytes, transactionId, ACTION_CONNECT, 16) {
        (it.u32(8).toLong() shl 32) or (it.u32(12).toLong() and 0xFFFFFFFFL)
    }

/** The 98-byte BEP 15 announce request: our address left to the tracker (0), upload 0. */
fun announceRequest(
    connectionId: Long,
    transactionId: Int,
    req: AnnounceRequest,
): ByteArray {
    require(req.infoHash.size == 20 && req.peerId.size == 20) { "info hash and peer id are 20 bytes each" }
    val out = ByteArray(98)
    out.put(0, connectionId, 8)
    out.put(8, ACTION_ANNOUNCE.toLong(), 4)
    out.put(12, transactionId.toLong(), 4)
    req.infoHash.copyInto(out, 16)
    req.peerId.copyInto(out, 36)
    out.put(56, req.downloaded, 8)
    out.put(64, req.left, 8)
    out.put(72, 0, 8)
    val event =
        when (req.event) {
            null -> 0
            AnnounceEvent.Completed -> 1
            AnnounceEvent.Started -> 2
            AnnounceEvent.Stopped -> 3
        }
    out.put(80, event.toLong(), 4)
    out.put(84, 0, 4)
    out.put(88, req.key.toLong(), 4)
    out.put(92, req.numWant.toLong(), 4)
    out.put(96, req.port.toLong(), 2)
    return out
}

/** The interval and peers from an announce reply to [transactionId]. */
fun parseUdpAnnounce(
    bytes: ByteArray,
    transactionId: Int,
): UdpReply<AnnounceResult> =
    udpReply(bytes, transactionId, ACTION_ANNOUNCE, 20) {
        val interval = it.u32(8).toLong() and 0xFFFFFFFFL
        val peers = LinkedHashSet<PeerAddr>()
        compactPeers(it, 20, peers)
        AnnounceResult(peers.toList(), seconds(BValue.BInt(interval), DEFAULT_INTERVAL_S) ?: DEFAULT_INTERVAL_S)
    }
