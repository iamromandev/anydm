package dev.anydm.engine.os

/**
 * What of the clipboard's text counts as a link (OSI-05, OSI-06), the same for the watch and the hotkey: one line of at most
 * [MAX_LENGTH] characters, that the intake takes, with no space in it (prose that starts with a link is not a link). A path or
 * a `file:` address on this machine counts only where [allowFiles], which is for the hotkey: a press is the user's own act,
 * and a copied path is not something that came from elsewhere.
 */
internal object ClipboardLinks {
    const val MAX_LENGTH = 2048

    fun parse(
        text: String,
        maxLength: Int = MAX_LENGTH,
        allowFiles: Boolean,
    ): ParsedLink? {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.length > maxLength || '\n' in trimmed || '\r' in trimmed) return null
        val parsed = LinkIntake.parse(trimmed) ?: return null
        val isFile = parsed.link.startsWith("file:", ignoreCase = true)
        if (isFile && !allowFiles) return null
        if (!isFile && trimmed.any { it.isWhitespace() }) return null
        return parsed
    }
}
