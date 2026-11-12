package dev.anydm.engine.http

private const val FALLBACK = "download"
private const val MAX_LEN = 200

/** The file name for a response: Content-Disposition, else the last URL segment, else the host. Always safe to join to a folder. */
fun nameFrom(
    contentDisposition: String?,
    url: String,
): String {
    val fromHeader = contentDisposition?.let(::dispositionName)
    if (!fromHeader.isNullOrBlank()) return sanitize(fromHeader)
    val noFragment = url.substringBefore('#').substringBefore('?')
    val afterScheme = noFragment.substringAfter("://", noFragment)
    val host = afterScheme.substringBefore('/').substringAfterLast('@').substringBefore(':')
    val segment = if ('/' in afterScheme) afterScheme.substringAfter('/').substringAfterLast('/') else ""
    val decoded = percentDecode(segment)
    return sanitize(if (decoded.isBlank()) host else decoded)
}

fun sanitize(name: String): String {
    val replaced = buildString { for (c in name) append(if (c in "/\\:*?\"<>|" || c.code < 32 || c.code == 127) '_' else c) }
    val trimmed = replaced.trimStart('.', ' ').trimEnd()
    if (trimmed.isEmpty()) return FALLBACK
    if (trimmed.length <= MAX_LEN) return trimmed
    val dot = trimmed.lastIndexOf('.')
    val ext = if (dot > 0 && trimmed.length - dot <= 20) trimmed.substring(dot) else ""
    return trimmed.substring(0, MAX_LEN - ext.length) + ext
}

/** `a.zip` -> `a (1).zip`, `a (2).zip`, ... until neither the name nor its `.part` is taken. */
fun uniqueName(
    name: String,
    taken: (String) -> Boolean,
): String {
    fun free(n: String) = !taken(n) && !taken("$n.part")
    if (free(name)) return name
    val dot = name.lastIndexOf('.')
    val stem = if (dot > 0) name.substring(0, dot) else name
    val ext = if (dot > 0) name.substring(dot) else ""
    var i = 1
    while (true) {
        val candidate = "$stem ($i)$ext"
        if (free(candidate)) return candidate
        i++
    }
}

private fun dispositionName(header: String): String? {
    val params = splitParams(header)
    params.firstOrNull { it.first == "filename*" }?.second?.let { v ->
        val value = v.substringAfter("''", "")
        if (value.isNotEmpty() || "''" in v) return percentDecode(value)
    }
    return params.firstOrNull { it.first == "filename" }?.second?.removeSurrounding("\"")
}

private fun splitParams(header: String): List<Pair<String, String>> {
    val out = ArrayList<Pair<String, String>>()
    var i = 0
    val parts = ArrayList<String>()
    val cur = StringBuilder()
    var quoted = false
    while (i < header.length) {
        val c = header[i]
        if (c == '"') quoted = !quoted
        if (c == ';' && !quoted) {
            parts.add(cur.toString())
            cur.clear()
        } else {
            cur.append(c)
        }
        i++
    }
    parts.add(cur.toString())
    for (p in parts.drop(1)) {
        val eq = p.indexOf('=')
        if (eq > 0) out.add(p.substring(0, eq).trim().lowercase() to p.substring(eq + 1).trim())
    }
    return out
}

private fun percentDecode(s: String): String {
    if ('%' !in s) return s
    val bytes = ArrayList<Byte>()
    var i = 0
    while (i < s.length) {
        val c = s[i]
        val hex = if (c == '%' && i + 2 < s.length) s.substring(i + 1, i + 3).toIntOrNull(16) else null
        if (hex != null) {
            bytes.add(hex.toByte())
            i += 3
        } else {
            bytes.addAll(c.toString().encodeToByteArray().toList())
            i++
        }
    }
    return bytes.toByteArray().decodeToString()
}
