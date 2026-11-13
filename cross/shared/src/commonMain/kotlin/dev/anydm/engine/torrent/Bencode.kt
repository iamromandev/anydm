package dev.anydm.engine.torrent

const val MAX_DEPTH = 64

/** The most values (integers, strings, lists, dicts) one document may hold. Bounds memory for hostile input. */
const val MAX_NODES = 2_000_000

sealed interface BValue {
    data class BInt(
        val v: Long,
    ) : BValue

    class BBytes(
        val b: ByteArray,
    ) : BValue {
        override fun equals(other: Any?): Boolean = other is BBytes && b.contentEquals(other.b)

        override fun hashCode(): Int = b.contentHashCode()
    }

    data class BList(
        val items: List<BValue>,
    ) : BValue

    data class BDict(
        val entries: Map<String, BValue>,
    ) : BValue
}

sealed interface DecodeResult {
    data class Ok(
        val value: BValue,
        /** Where the top-level `info` value sits in the input, exactly as written there; null when the root is no dict or has no `info`. */
        val infoRange: IntRange? = null,
    ) : DecodeResult

    data class Err(
        val reason: String,
    ) : DecodeResult
}

private const val NOT_BENCODED = "Not bencoded"
private const val TOO_DEEP = "Too deeply nested"
private const val TOO_COMPLEX = "Too complex"

/** Decodes [bytes] as exactly one bencoded value. Never throws; trailing bytes are an error. */
fun decodeBencode(
    bytes: ByteArray,
    maxDepth: Int = MAX_DEPTH,
    maxNodes: Int = MAX_NODES,
): DecodeResult {
    val parser = Parser(bytes, maxDepth, maxNodes)
    val value = parser.value(0)
    return when {
        value == null -> DecodeResult.Err(parser.error ?: NOT_BENCODED)
        parser.at != bytes.size -> DecodeResult.Err(NOT_BENCODED)
        else -> DecodeResult.Ok(value, parser.infoRange)
    }
}

/**
 * Index-based recursive parser. Recursion is bounded by [maxDepth], so hostile nesting cannot overflow the stack, and the
 * total number of values by [maxNodes], so a few megabytes of `le` or `0:` cannot allocate millions of objects.
 * Failures are reported by returning null and setting [error]; nothing is thrown.
 */
private class Parser(
    private val src: ByteArray,
    private val maxDepth: Int,
    private val maxNodes: Int,
) {
    var at = 0
    var error: String? = null

    /** Where the top-level dict's `info` value starts and ends in [src]; set while parsing it. */
    var infoRange: IntRange? = null
    private var nodes = 0

    private fun fail(reason: String = NOT_BENCODED): BValue? {
        if (error == null) error = reason
        return null
    }

    fun value(depth: Int): BValue? {
        if (at >= src.size) return fail()
        if (++nodes > maxNodes) return fail(TOO_COMPLEX)
        return when (src[at]) {
            I -> integer()
            L -> list(depth + 1)
            D -> dict(depth + 1)
            in DIGITS -> bytes()?.let { BValue.BBytes(it) }
            else -> fail()
        }
    }

    private fun integer(): BValue? {
        at++ // 'i'
        val negative = at < src.size && src[at] == MINUS
        if (negative) at++
        val start = at
        var acc = 0L
        while (at < src.size && src[at] in DIGITS) {
            val d = src[at] - ZERO
            if (acc > (Long.MAX_VALUE - d) / 10) return fail()
            acc = acc * 10 + d
            at++
        }
        val digits = at - start
        val leadingZero = digits > 1 && src[start] == ZERO
        val negativeZero = negative && acc == 0L
        if (digits == 0 || leadingZero || negativeZero) return fail()
        if (at >= src.size || src[at] != E) return fail()
        at++
        return BValue.BInt(if (negative) -acc else acc)
    }

    /** Reads `<len>:<bytes>`. The length is checked against the remaining input before anything is allocated. */
    private fun bytes(): ByteArray? {
        var len = 0L
        val start = at
        while (at < src.size && src[at] in DIGITS) {
            len = len * 10 + (src[at] - ZERO)
            at++
            if (len > src.size) {
                fail()
                return null
            }
        }
        val digits = at - start
        if (digits == 0 || (digits > 1 && src[start] == ZERO)) {
            fail()
            return null
        }
        if (at >= src.size || src[at] != COLON) {
            fail()
            return null
        }
        at++
        if (len > src.size - at) {
            fail()
            return null
        }
        val out = src.copyOfRange(at, at + len.toInt())
        at += len.toInt()
        return out
    }

    private fun list(depth: Int): BValue? {
        if (depth > maxDepth) return fail(TOO_DEEP)
        at++ // 'l'
        val items = ArrayList<BValue>()
        while (true) {
            if (at >= src.size) return fail()
            if (src[at] == E) break
            items += value(depth) ?: return null
        }
        at++
        return BValue.BList(items)
    }

    private fun dict(depth: Int): BValue? {
        if (depth > maxDepth) return fail(TOO_DEEP)
        at++ // 'd'
        val entries = LinkedHashMap<String, BValue>()
        while (true) {
            if (at >= src.size) return fail()
            if (src[at] == E) break
            if (src[at] !in DIGITS) return fail()
            val key = bytes()?.decodeToString() ?: return null
            if (key in entries) return fail()
            val start = at
            entries[key] = value(depth) ?: return null
            if (depth == 1 && key == INFO) infoRange = start until at
        }
        at++
        return BValue.BDict(entries)
    }

    private companion object {
        const val INFO = "info"
        val I = 'i'.code.toByte()
        val L = 'l'.code.toByte()
        val D = 'd'.code.toByte()
        val E = 'e'.code.toByte()
        val COLON = ':'.code.toByte()
        val MINUS = '-'.code.toByte()
        val ZERO = '0'.code.toByte()
        val DIGITS = ZERO..'9'.code.toByte()
    }
}

/** Encodes [value] canonically: dictionary keys in byte order, no whitespace. The inverse of [decodeBencode] for canonical input. */
fun encodeBencode(value: BValue): ByteArray {
    val out = Sink()
    out.write(value)
    return out.toByteArray()
}

/** A growing byte buffer; common code has no `ByteArrayOutputStream`. */
private class Sink {
    private var buf = ByteArray(256)
    private var size = 0

    fun toByteArray(): ByteArray = buf.copyOf(size)

    private fun put(b: ByteArray) {
        if (size + b.size > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, size + b.size))
        b.copyInto(buf, size)
        size += b.size
    }

    private fun put(text: String) = put(text.encodeToByteArray())

    fun write(v: BValue) {
        when (v) {
            is BValue.BInt -> {
                put("i${v.v}e")
            }

            is BValue.BBytes -> {
                bytes(v.b)
            }

            is BValue.BList -> {
                put("l")
                v.items.forEach { write(it) }
                put("e")
            }

            is BValue.BDict -> {
                put("d")
                v.entries.entries
                    .map { it.key.encodeToByteArray() to it.value }
                    .sortedWith { a, b -> compareUnsigned(a.first, b.first) }
                    .forEach { (k, item) ->
                        bytes(k)
                        write(item)
                    }
                put("e")
            }
        }
    }

    private fun bytes(b: ByteArray) {
        put("${b.size}:")
        put(b)
    }

    private fun compareUnsigned(
        a: ByteArray,
        b: ByteArray,
    ): Int {
        for (i in 0 until minOf(a.size, b.size)) {
            val c = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            if (c != 0) return c
        }
        return a.size - b.size
    }
}
