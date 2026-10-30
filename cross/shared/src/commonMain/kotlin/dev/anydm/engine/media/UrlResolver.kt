package dev.anydm.engine.media

private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")

/**
 * [ref] as an address, relative to [base] the way a browser reads a playlist's lines: absolute and scheme-relative
 * forms, a path from the root, or one beside [base] (its query and fragment don't carry over). `.` and `..` are
 * folded, and `..` stops at the root.
 */
internal fun resolveUrl(
    base: String,
    ref: String,
): String {
    if (SCHEME.containsMatchIn(ref)) return ref
    val schemeEnd = base.indexOf("://")
    if (schemeEnd < 0) return ref
    val scheme = base.substring(0, schemeEnd)
    if (ref.startsWith("//")) return "$scheme:$ref"
    val afterScheme = base.substring(schemeEnd + 3)
    val pathStart = afterScheme.indexOfFirst { it == '/' || it == '?' || it == '#' }
    val authority = if (pathStart < 0) afterScheme else afterScheme.substring(0, pathStart)
    val basePath = if (pathStart < 0) "" else afterScheme.substring(pathStart).substringBefore('?').substringBefore('#')
    val origin = "$scheme://$authority"
    return when {
        ref.startsWith("/") -> origin + folded(ref)
        ref.startsWith("?") || ref.startsWith("#") -> origin + basePath + ref
        else -> origin + folded(basePath.substringBeforeLast('/', "") + "/" + ref)
    }
}

/** [pathAndQuery] with its `.` and `..` segments folded; the query stays as it is. */
private fun folded(pathAndQuery: String): String {
    val path = pathAndQuery.substringBefore('?').substringBefore('#')
    val tail = pathAndQuery.substring(path.length)
    val kept = mutableListOf<String>()
    for (segment in path.split('/')) {
        when (segment) {
            "", "." -> Unit
            ".." -> kept.removeLastOrNull()
            else -> kept += segment
        }
    }
    val slash = if (path.endsWith("/") && kept.isNotEmpty()) "/" else ""
    return "/" + kept.joinToString("/") + slash + tail
}
