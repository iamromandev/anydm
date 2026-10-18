package dev.anydm.engine.files

import dev.anydm.engine.files.OsProfile.MACOS
import dev.anydm.engine.files.OsProfile.WINDOWS

/**
 * Turns any text into a file name the OS can create (FIL-03): forbidden characters become `_`, reserved Windows names
 * get a `_` prefix, Windows trailing dots and spaces go, and the length limit is met by shortening the stem so the
 * extension stays. A name that is empty, `.`, `..` or only `_` becomes `download`.
 */
class NameCleaner(
    val os: OsProfile,
) {
    fun clean(name: String): String {
        val replaced = name.map { if (isForbidden(it)) '_' else it }.joinToString("")
        var text = if (os == WINDOWS) replaced.trimEnd('.', ' ') else replaced
        if (text.isEmpty() || text == "." || text == ".." || text.all { it == '_' }) return DEFAULT_NAME
        if (os == WINDOWS && text.substringBefore('.').uppercase() in RESERVED) text = "_$text"
        return fitted(text)
    }

    private fun isForbidden(c: Char): Boolean =
        c < ' ' ||
            c == DELETE ||
            c == '/' ||
            when (os) {
                WINDOWS -> c in WINDOWS_FORBIDDEN
                MACOS -> c == ':'
                else -> false
            }

    /** Shortens the stem, never the extension, and never splits a surrogate pair. */
    private fun fitted(text: String): String {
        val dot = text.lastIndexOf('.')
        val extension = if (dot > 0 && text.length - dot <= MAX_EXTENSION) text.substring(dot) else ""
        val stem = text.removeSuffix(extension)
        var room = MAX_NAME - cost(extension)
        val out = StringBuilder()
        var i = 0
        while (i < stem.length) {
            val width = if (stem[i].isHighSurrogate() && i + 1 < stem.length && stem[i + 1].isLowSurrogate()) 2 else 1
            val part = stem.substring(i, i + width)
            room -= cost(part)
            if (room < 0) break
            out.append(part)
            i += width
        }
        val shortened = if (os == WINDOWS && extension.isEmpty()) out.toString().trimEnd('.', ' ') else out.toString()
        return if (shortened.isEmpty() && extension.isEmpty()) DEFAULT_NAME else shortened + extension
    }

    /** UTF-16 units on Windows, UTF-8 bytes elsewhere. */
    private fun cost(text: String): Int = if (os == WINDOWS) text.length else text.encodeToByteArray().size

    private companion object {
        const val DEFAULT_NAME = "download"
        const val DELETE = '\u007F'
        const val MAX_NAME = 255
        const val MAX_EXTENSION = 20
        const val WINDOWS_FORBIDDEN = "<>:\"\\|?*"
        val RESERVED =
            setOf("CON", "PRN", "AUX", "NUL") +
                (1..9).map { "COM$it" } +
                (1..9).map { "LPT$it" }
    }
}
