package dev.anydm.engine.files

/** One line of a playlist file: what to call it, its length in seconds if known, and the [path] a player opens. */
data class PlaylistFileEntry(
    val title: String,
    val durationSeconds: Int?,
    val path: String,
)

/** The `.m3u8` of a collection (MED-17): extended M3U, UTF-8, `\n` line ends. */
object PlaylistFile {
    fun render(entries: List<PlaylistFileEntry>): String =
        buildString {
            append("#EXTM3U\n")
            for (entry in entries) {
                if (entry.path.any { it == '\n' || it == '\r' || it == '\u0000' }) continue
                append("#EXTINF:")
                    .append(entry.durationSeconds ?: -1)
                    .append(',')
                    .append(oneLine(entry.title))
                    .append('\n')
                // A name that starts with # would be read as a comment.
                append(if (entry.path.startsWith("#")) "./" else "").append(entry.path).append('\n')
            }
        }

    private fun oneLine(title: String): String = title.map { if (it < ' ' || it == ' ' || it == ' ') ' ' else it }.joinToString("").trim()
}
