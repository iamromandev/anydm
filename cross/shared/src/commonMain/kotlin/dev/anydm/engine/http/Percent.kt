package dev.anydm.engine.http

/** The bytes [text] stands for, other characters as their UTF-8; null if a `%` isn't followed by two hex digits. */
internal fun percentDecodedBytes(text: String): ByteArray? {
    val out = ArrayList<Byte>(text.length)
    var i = 0
    while (i < text.length) {
        if (text[i] == '%') {
            if (i + 2 >= text.length) return null
            val high = text[i + 1].digitToIntOrNull(16) ?: return null
            val low = text[i + 2].digitToIntOrNull(16) ?: return null
            out += ((high shl 4) or low).toByte()
            i += 3
        } else {
            // A whole run at once: a character outside the BMP is two UTF-16 units that only encode together.
            val end = text.indexOf('%', i).let { if (it < 0) text.length else it }
            text.substring(i, end).encodeToByteArray().forEach { out += it }
            i = end
        }
    }
    return out.toByteArray()
}
