package dev.anydm.engine.media

import kotlinx.io.files.Path

/** The command line that has yt-dlp download and merge a whole selection itself (MED-05, MED-07). */
internal object YtDlpCommand {
    /**
     * `yt-dlp -f <ids> ... -o <output> -- <url>`, printing progress as JSON lines. [limit] is `--limit-rate` in bytes per
     * second, null for none; [ffmpeg] is where yt-dlp finds ffmpeg to merge or convert, [deno] the runtime for YouTube.
     */
    fun download(
        ytDlp: Path,
        ffmpeg: Path?,
        deno: Path?,
        selection: Selection,
        output: Path,
        url: String,
        limit: Long?,
    ): List<String> =
        buildList {
            add(ytDlp.toString())
            addAll(listOf("-f", selection.streams.joinToString("+") { it.formatId }))
            addAll(listOf("--no-playlist", "--no-warnings", "--newline", "--progress-template", "download:%(progress)j"))
            if (limit != null) addAll(listOf("--limit-rate", limit.toString()))
            val audioFormat = selection.audioFormat
            if (audioFormat != null) {
                addAll(listOf("-x", "--audio-format", audioFormat.extension))
            } else if (selection.streams.size > 1) {
                addAll(listOf("--merge-output-format", selection.outputExtension))
            }
            if (ffmpeg != null) addAll(listOf("--ffmpeg-location", ffmpeg.toString()))
            if (deno != null) addAll(listOf("--js-runtimes", "deno:$deno"))
            addAll(listOf("-o", output.toString(), "--", url))
        }
}
