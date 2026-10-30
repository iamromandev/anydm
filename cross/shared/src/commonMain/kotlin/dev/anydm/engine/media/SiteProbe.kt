package dev.anydm.engine.media

import dev.anydm.engine.core.Format
import dev.anydm.engine.core.RequestContext
import dev.anydm.engine.core.SubtitleMode
import dev.anydm.engine.files.NameFields
import dev.anydm.engine.files.TemplateField
import dev.anydm.engine.http.HttpProbe
import dev.anydm.engine.protocol.ProbeResult

private const val MAX_STEM = 120
private val UNSAFE = setOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')

/**
 * What probing a media page learned (MED-01): the page's [info], the [selection] made for [format], and each chosen
 * stream's HTTP probe by format id. [name] is the output's: the title and the output extension.
 */
class SiteProbe(
    val info: MediaInfo,
    val selection: Selection,
    val format: Format,
    /** The page's address, for extracting again. */
    val url: String,
    val context: RequestContext?,
    val probes: Map<String, HttpProbe>,
    override val name: String,
    override val size: Long?,
    override val validator: String?,
    override val nameFields: NameFields,
    /** Each fragmented stream's fragments by format id, the init segment first; empty for a plain-only job. */
    val fragments: Map<String, List<FragmentRef>> = emptyMap(),
    /** Who downloads the job: the selection's route, or YTDLP when a playlist turned out to be encrypted. */
    val route: StreamRoute = selection.route,
    /** The subtitle files, cover, chapters and metadata this job brings along (MED-13, MED-15). */
    val extras: MediaExtras = MediaExtras.None,
    /** The page was read with the cookie source (false when there is none, or the site withheld formats from it, MED-19). */
    val withCookies: Boolean = false,
) : ProbeResult {
    /**
     * Whether the main output has to go through ffmpeg: the selection says so, or something is embedded in it (a subtitle
     * track, the cover, chapters, metadata). Subtitles written as separate files need ffmpeg too, but not the video.
     */
    val mainNeedsFfmpeg: Boolean
        get() {
            val mode = format.subtitles?.mode
            val embedsSubtitles = (mode == SubtitleMode.EMBED || mode == SubtitleMode.BOTH) && extras.subtitles.isNotEmpty()
            return selection.needsFfmpeg || embedsSubtitles || extras.thumbnail != null || extras.chapters.isNotEmpty() ||
                extras.metadata.isNotEmpty()
        }
}

/** What a media job brings besides its streams: the subtitle tracks chosen, the cover, the chapters, and the metadata pairs to embed. */
class MediaExtras(
    val subtitles: List<SubtitleTrack>,
    val thumbnail: ChosenThumbnail?,
    val chapters: List<Chapter>,
    val metadata: List<Pair<String, String>>,
) {
    /** Anything for ffmpeg to do beyond the streams. */
    val any: Boolean get() = subtitles.isNotEmpty() || thumbnail != null || chapters.isNotEmpty() || metadata.isNotEmpty()

    companion object {
        val None = MediaExtras(emptyList(), null, emptyList(), emptyList())
    }
}

/** What [Format.embed] and [Format.subtitles] ask for, taken from what the page lists. */
internal fun extrasOf(
    info: MediaInfo,
    format: Format,
): MediaExtras =
    MediaExtras(
        subtitles = format.subtitles?.let { SubtitleSelector.choose(info.subtitles, it) }.orEmpty(),
        thumbnail = if (format.embed.thumbnail) ThumbnailSelector.choose(info.thumbnails) else null,
        chapters = if (format.embed.chapters) info.chapters else emptyList(),
        metadata = if (format.embed.metadata) metadataOf(info) else emptyList(),
    )

private fun metadataOf(info: MediaInfo): List<Pair<String, String>> =
    listOfNotNull(
        "title" to info.title,
        info.uploader?.let { "artist" to it },
        info.date?.takeIf { it.length == 8 && it.all(Char::isDigit) }?.let {
            "date" to
                "${it.substring(0, 4)}-${it.substring(4, 6)}-${it.substring(6)}"
        },
        info.webpageUrl?.let { "comment" to it },
        info.description?.let { "description" to it.take(MAX_DESCRIPTION) },
    ).filter { it.second.isNotBlank() }

private const val MAX_DESCRIPTION = 5000

/** The streams' sizes added up, or null when any is unknown. */
internal fun totalSize(probes: List<HttpProbe>): Long? {
    var total = 0L
    for (probe in probes) total += probe.size ?: return null
    return total
}

internal fun nameFieldsOf(
    info: MediaInfo,
    selection: Selection,
    /** A collection item's place in its playlist (MED-16). */
    playlistIndex: Int? = null,
): NameFields =
    NameFields(
        buildMap {
            // A field goes into a file name, so it is cleaned like a title is: no separators, no control characters.
            cleanStem(info.title).takeIf { it.isNotBlank() }?.let { put(TemplateField.TITLE, it) }
            info.uploader
                ?.let { cleanStem(it) }
                ?.takeIf { it.isNotBlank() }
                ?.let { put(TemplateField.UPLOADER, it) }
            cleanStem(info.id).takeIf { it.isNotBlank() }?.let { put(TemplateField.ID, it) }
            info.date?.takeIf { it.length == 8 && it.all(Char::isDigit) }?.let {
                put(TemplateField.DATE, "${it.substring(0, 4)}-${it.substring(4, 6)}-${it.substring(6)}")
            }
            selection.video?.height?.let { put(TemplateField.RESOLUTION, "${it}p") }
            playlistIndex?.let { put(TemplateField.PLAYLIST_INDEX, it.toString()) }
        },
    )

/**
 * [title] as a file name stem: path separators, characters Windows refuses, control characters and unpaired surrogates
 * become `_`, whitespace runs one space, dots and spaces at the ends go, and it is cut to [MAX_STEM] characters without
 * splitting a pair. Falls back to [fallback], then "media". The placement's name cleaner still has the last word.
 */
internal fun fileStem(
    title: String,
    fallback: String,
): String = cleanStem(title).ifBlank { cleanStem(fallback) }.ifBlank { "media" }

private fun cleanStem(text: String): String {
    val spaced = text.replace(Regex("\\s+"), " ")
    val out = StringBuilder()
    var i = 0
    while (i < spaced.length) {
        val c = spaced[i]
        when {
            c.isHighSurrogate() && i + 1 < spaced.length && spaced[i + 1].isLowSurrogate() -> {
                out.append(c).append(spaced[i + 1])
                i++
            }

            c.isSurrogate() || c in UNSAFE || c.isISOControl() -> {
                out.append('_')
            }

            else -> {
                out.append(c)
            }
        }
        i++
    }
    var stem = out.toString().trim().trim('.', ' ')
    if (stem.length > MAX_STEM) {
        stem = stem.take(MAX_STEM)
        if (stem.last().isHighSurrogate()) stem = stem.dropLast(1)
        stem = stem.trim().trim('.', ' ')
    }
    return stem
}
