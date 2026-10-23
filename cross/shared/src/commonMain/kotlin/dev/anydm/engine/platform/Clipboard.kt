package dev.anydm.engine.platform

/** Reads the text on the system clipboard (OSI-05, OSI-06), desktop first. */
fun interface Clipboard {
    /**
     * The text on the clipboard now, or null when it holds none (an image, files, nothing). Throws when it can't be read right
     * now (another program holds it, the screen is locked): that is not the same as holding nothing, and a caller tries again.
     */
    suspend fun text(): String?

    companion object {
        val None: Clipboard = Clipboard { null }
    }
}
