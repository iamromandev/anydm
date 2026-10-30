package dev.anydm.engine.media

import kotlinx.io.files.Path

private const val MAX_CHAPTER_TITLE = 200

/** A subtitle file to put inside the output (MED-13): the raw [path], its language code and its file [extension] (srt, vtt, ass, ssa). */
data class EmbeddedSubtitle(
    val path: Path,
    val lang: String,
    val extension: String,
)

/**
 * What the merge puts into the output besides the streams (MED-13, MED-15): subtitle tracks, a file of chapters in
 * FFMETADATA form, a cover picture (JPEG or PNG), and `-metadata` pairs in the order to write them.
 */
data class MergeExtras(
    val subtitles: List<EmbeddedSubtitle> = emptyList(),
    val chaptersFile: Path? = null,
    val thumbnail: Path? = null,
    val metadata: List<Pair<String, String>> = emptyList(),
) {
    companion object {
        val None = MergeExtras()
    }
}

/**
 * [chapters] as an FFMETADATA1 file (`-i chapters.txt -map_chapters N`): times in milliseconds, titles cut to
 * [MAX_CHAPTER_TITLE] characters. `=`, `;`, `#` and `\` in a title, and a line break, are escaped with a backslash so a
 * title can't end its line or start another.
 */
fun ffmetadataOf(chapters: List<Chapter>): String =
    buildString {
        append(";FFMETADATA1\n")
        for (chapter in chapters) {
            append("[CHAPTER]\nTIMEBASE=1/1000\n")
            append("START=").append((chapter.start * 1000).toLong()).append('\n')
            append("END=").append((chapter.end * 1000).toLong()).append('\n')
            append("title=").append(escapeFfmetadata(chapter.title.take(MAX_CHAPTER_TITLE))).append('\n')
        }
    }

private fun escapeFfmetadata(text: String): String =
    buildString {
        for (c in text) {
            when (c) {
                '=', ';', '#', '\\', '\n' -> append('\\').append(c)
                '\r' -> Unit
                else -> append(c)
            }
        }
    }
