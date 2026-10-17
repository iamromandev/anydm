package dev.anydm.engine.http

/**
 * The name a download gets (HTTP-02): `filename*` from [contentDisposition] (RFC 5987/6266), else `filename`, else the
 * last path segment of [finalUrl], else `download` with an extension for [contentType]. Path separators become `_`;
 * a name that ends up empty, `.` or `..` falls to the next rule. Full sanitising is FIL-03 (#539).
 */
fun fileNameFor(
    contentDisposition: String?,
    finalUrl: String,
    contentType: String?,
): String {
    val parameters = contentDisposition?.let(::dispositionParameters).orEmpty()
    return parameters["filename*"]?.let(::extendedValue)?.let(::cleaned)
        ?: parameters["filename"]?.let(::cleaned)
        ?: lastPathSegment(finalUrl)?.let(::cleaned)
        ?: ("download" + extensionFor(contentType))
}

private val extensions =
    mapOf(
        "application/zip" to ".zip",
        "application/pdf" to ".pdf",
        "application/x-tar" to ".tar",
        "application/gzip" to ".gz",
        "video/mp4" to ".mp4",
        "audio/mpeg" to ".mp3",
        "image/jpeg" to ".jpg",
        "image/png" to ".png",
        "text/plain" to ".txt",
    )

private fun extensionFor(contentType: String?): String =
    contentType
        ?.substringBefore(';')
        ?.trim()
        ?.lowercase()
        ?.let(extensions::get)
        .orEmpty()

private fun cleaned(name: String): String? =
    name.replace('/', '_').replace('\\', '_').trim().takeUnless {
        it.isEmpty() || it == "." ||
            it == ".."
    }

/** `name=value` pairs after the disposition type, names lowercased, quoted values unescaped; the first of a name wins. */
private fun dispositionParameters(header: String): Map<String, String> {
    val parameters = mutableMapOf<String, String>()
    for (part in splitOutsideQuotes(header).drop(1)) {
        val eq = part.indexOf('=')
        if (eq <= 0) continue
        val name = part.substring(0, eq).trim().lowercase()
        val raw = part.substring(eq + 1).trim()
        val value = if (raw.length >= 2 && raw.startsWith('"') && raw.endsWith('"')) unquoted(raw) else raw
        parameters.getOrPut(name) { value }
    }
    return parameters
}

private fun splitOutsideQuotes(header: String): List<String> {
    val parts = mutableListOf<String>()
    val current = StringBuilder()
    var quoted = false
    var escaped = false
    for (c in header) {
        when {
            escaped -> {
                escaped = false
            }

            quoted && c == '\\' -> {
                escaped = true
            }

            c == '"' -> {
                quoted = !quoted
            }

            !quoted && c == ';' -> {
                parts += current.toString()
                current.clear()
                continue
            }
        }
        current.append(c)
    }
    parts += current.toString()
    return parts
}

private fun unquoted(raw: String): String {
    val out = StringBuilder()
    var escaped = false
    for (c in raw.substring(1, raw.length - 1)) {
        if (!escaped && c == '\\') {
            escaped = true
        } else {
            out.append(c)
            escaped = false
        }
    }
    return out.toString()
}

/** `charset'language'percent-encoded`, for UTF-8 and ISO-8859-1; null for anything else. */
private fun extendedValue(value: String): String? {
    val parts = value.split('\'', limit = 3)
    if (parts.size != 3) return null
    val bytes = percentDecoded(parts[2]) ?: return null
    return when (parts[0].trim().uppercase()) {
        "UTF-8" -> bytes.decodeToString()
        "ISO-8859-1" -> bytes.map { (it.toInt() and 0xFF).toChar() }.joinToString("")
        else -> null
    }
}

private fun lastPathSegment(url: String): String? {
    val noFragment = url.substringBefore('#').substringBefore('?')
    val afterScheme = noFragment.substringAfter("://", noFragment)
    val slash = afterScheme.indexOf('/')
    if (slash < 0) return null
    val segment = afterScheme.substring(slash + 1).split('/').lastOrNull { it.isNotEmpty() } ?: return null
    return percentDecoded(segment)?.decodeToString() ?: segment
}

/** The bytes [text] stands for; null if a `%` isn't followed by two hex digits. */
private fun percentDecoded(text: String): ByteArray? {
    val out = ArrayList<Byte>(text.length)
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c == '%') {
            if (i + 2 >= text.length) return null
            val high = text[i + 1].digitToIntOrNull(16) ?: return null
            val low = text[i + 2].digitToIntOrNull(16) ?: return null
            out += ((high shl 4) or low).toByte()
            i += 3
        } else {
            out += c.toString().encodeToByteArray().toList()
            i++
        }
    }
    return out.toByteArray()
}
