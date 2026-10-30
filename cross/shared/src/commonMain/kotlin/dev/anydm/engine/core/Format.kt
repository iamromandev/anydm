package dev.anydm.engine.core

import kotlinx.serialization.Serializable

/** How good a video the user wants when they name no resolution (MED-09). */
enum class QualityPreset { BEST, BALANCED, SMALL }

/** Whether a high-dynamic-range video is wanted. */
enum class HdrChoice { ANY, YES, NO }

/** The file kinds an audio-only download can end as (MED-11). */
enum class AudioFormat(
    val extension: String,
) {
    MP3("mp3"),
    M4A("m4a"),
    OPUS("opus"),
    WAV("wav"),
}

/** The file a merged video ends in. */
enum class Container(
    val extension: String,
) {
    MP4("mp4"),
    MKV("mkv"),
    WEBM("webm"),
}

/** How many sound channels the user wants (MED-12): surround is six or more. */
enum class AudioChannels { ANY, STEREO, SURROUND }

/** Where subtitles go (MED-13): files beside the video, inside it, or both. */
enum class SubtitleMode { SEPARATE, EMBED, BOTH }

/** A subtitle file format subtitles can be converted to (MED-13). */
enum class SubtitleFormat(
    val extension: String,
) {
    SRT("srt"),
    VTT("vtt"),
    ASS("ass"),
}

/** What SponsorBlock does with the segments it finds (MED-14): mark them as chapters or cut them out. */
enum class SponsorBlockMode { MARK, CUT }

val DEFAULT_SPONSORBLOCK_CATEGORIES: List<String> = listOf("sponsor", "intro", "outro", "selfpromo", "interaction", "music_offtopic")

private val LANGUAGE = Regex("[A-Za-z0-9-]{2,12}")
private val AUDIO_LANGUAGE = Regex("[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})?")
private val CATEGORY = Regex("[a-z_]{3,24}")

/** The subtitles wanted (MED-13): [languages] in order, [includeAuto] for auto-generated ones, where they [mode] go, and a format to [convertTo]. */
@Serializable
data class SubtitleChoice(
    val languages: List<String>,
    val includeAuto: Boolean = false,
    val mode: SubtitleMode = SubtitleMode.SEPARATE,
    val convertTo: SubtitleFormat? = null,
) {
    init {
        require(languages.isNotEmpty()) { "a subtitle choice needs at least one language" }
        require(
            languages.all { LANGUAGE.matches(it) && !it.startsWith("-") },
        ) { "subtitle languages are codes like en or pt-BR: $languages" }
    }
}

/** What SponsorBlock does (MED-14): [mode] for the [categories] of segment. */
@Serializable
data class SponsorBlockChoice(
    val mode: SponsorBlockMode,
    val categories: List<String> = DEFAULT_SPONSORBLOCK_CATEGORIES,
) {
    init {
        require(categories.isNotEmpty()) { "SponsorBlock needs at least one category" }
        require(categories.all { CATEGORY.matches(it) }) { "SponsorBlock categories are words like sponsor: $categories" }
    }
}

/** What to put inside the finished file (MED-15). */
@Serializable
data class EmbedChoice(
    val metadata: Boolean = false,
    val thumbnail: Boolean = false,
    val chapters: Boolean = false,
) {
    val any: Boolean get() = metadata || thumbnail || chapters
}

/**
 * What the user wants from a media page (MED-09 to MED-15): a [preset], narrowed by an explicit [height] (the tallest
 * not above it), [fps] (not above it), [hdr], codec prefixes and a [container], or audio only. Stored with the download.
 */
@Serializable
data class Format(
    val preset: QualityPreset = QualityPreset.BEST,
    val height: Int? = null,
    val fps: Int? = null,
    val hdr: HdrChoice? = null,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val audioOnly: AudioFormat? = null,
    val container: Container? = null,
    /** The audio track's language, matched by prefix (`en` takes `en-US`); null takes the best (MED-12). */
    val audioLanguage: String? = null,
    val audioChannels: AudioChannels = AudioChannels.ANY,
    val subtitles: SubtitleChoice? = null,
    val sponsorBlock: SponsorBlockChoice? = null,
    val embed: EmbedChoice = EmbedChoice(),
) {
    init {
        require(audioLanguage == null || AUDIO_LANGUAGE.matches(audioLanguage)) { "audioLanguage must be a language code: $audioLanguage" }
        require(
            audioOnly == null || (subtitles == null && sponsorBlock == null),
        ) { "an audio-only format has no subtitles or SponsorBlock" }
        require(height == null || height > 0) { "height must be > 0: $height" }
        require(fps == null || fps > 0) { "fps must be > 0: $fps" }
        require(videoCodec == null || videoCodec.isNotBlank()) { "videoCodec must not be blank" }
        require(audioCodec == null || audioCodec.isNotBlank()) { "audioCodec must not be blank" }
        require(audioOnly == null || (height == null && fps == null && hdr == null && videoCodec == null && container == null)) {
            "an audio-only format has no video choices"
        }
    }
}
