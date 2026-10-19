package dev.anydm.engine.media

import dev.anydm.engine.core.Format
import dev.anydm.engine.core.SponsorBlockMode
import dev.anydm.engine.core.SubtitleMode
import kotlinx.io.files.Path

/** The command line that has yt-dlp download and merge a whole selection itself (MED-05, MED-07). */
internal object YtDlpCommand {
    /**
     * `yt-dlp --ignore-config -f <ids> ... -o <staging>/out.%(ext)s -- <url>`, printing progress as JSON lines. [limit] is `--limit-rate` in bytes per
     * second, null for none; [ffmpeg] is where yt-dlp finds ffmpeg to merge or convert, [deno] the runtime for YouTube.
     */
    fun download(
        ytDlp: Path,
        ffmpeg: Path?,
        deno: Path?,
        selection: Selection,
        staging: Path,
        url: String,
        limit: Long?,
        format: Format = Format(),
        extraArgs: List<String> = emptyList(),
    ): List<String> =
        buildList {
            add(ytDlp.toString())
            // Our command alone: a user's yt-dlp config (-k, --download-archive, ...) must not change what we get.
            add("--ignore-config")
            addAll(listOf("-f", selection.streams.joinToString("+") { it.formatId }))
            addAll(listOf("--no-playlist", "--no-warnings", "--newline", "--progress-template", "download:%(progress)j"))
            if (limit != null) addAll(listOf("--limit-rate", limit.toString()))
            val audioFormat = selection.audioFormat
            if (audioFormat != null) {
                addAll(listOf("-x", "--audio-format", audioFormat.extension))
            } else if (selection.streams.size > 1) {
                addAll(listOf("--merge-output-format", selection.outputExtension))
            } else if (selection.streams.singleOrNull()?.ext != selection.outputExtension) {
                addAll(listOf("--remux-video", selection.outputExtension))
            }
            addAll(mediaFlags(format))
            if (ffmpeg != null) addAll(listOf("--ffmpeg-location", ffmpeg.toString()))
            if (deno != null) addAll(listOf("--js-runtimes", "deno:$deno"))
            // Cookies and pacing.
            addAll(extraArgs)
            addAll(listOf("-o", staging.toString().replace("%", "%%") + "/out.%(ext)s", "--", url))
        }
}

/**
 * yt-dlp's flags for the choices of [format] (MED-13 to MED-15): subtitles, SponsorBlock, and embedding. Every list is one
 * argument, and the values were checked when the choice was made. On this route a subtitle choice of BOTH is EMBED: yt-dlp
 * removes the subtitle files it embeds.
 */
internal fun mediaFlags(format: Format): List<String> =
    buildList {
        format.subtitles?.let { subtitles ->
            add("--write-subs")
            if (subtitles.includeAuto) add("--write-auto-subs")
            addAll(listOf("--sub-langs", subtitles.languages.joinToString(",")))
            subtitles.convertTo?.let { addAll(listOf("--convert-subs", it.extension)) }
            if (subtitles.mode != SubtitleMode.SEPARATE) add("--embed-subs")
        }
        format.sponsorBlock?.let { sponsorBlock ->
            val flag = if (sponsorBlock.mode == SponsorBlockMode.CUT) "--sponsorblock-remove" else "--sponsorblock-mark"
            addAll(listOf(flag, sponsorBlock.categories.joinToString(",")))
        }
        if (format.embed.metadata) add("--embed-metadata")
        if (format.embed.thumbnail) add("--embed-thumbnail")
        if (format.embed.chapters) add("--embed-chapters")
    }
