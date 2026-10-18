package dev.anydm.engine.core

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

/**
 * What the user wants from a media page (MED-09 to MED-11): a [preset], narrowed by an explicit [height] (the tallest
 * not above it), [fps] (not above it), [hdr], codec prefixes and a [container], or audio only. Stored with the download.
 */
data class Format(
    val preset: QualityPreset = QualityPreset.BEST,
    val height: Int? = null,
    val fps: Int? = null,
    val hdr: HdrChoice? = null,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val audioOnly: AudioFormat? = null,
    val container: Container? = null,
) {
    init {
        require(height == null || height > 0) { "height must be > 0: $height" }
        require(fps == null || fps > 0) { "fps must be > 0: $fps" }
        require(videoCodec == null || videoCodec.isNotBlank()) { "videoCodec must not be blank" }
        require(audioCodec == null || audioCodec.isNotBlank()) { "audioCodec must not be blank" }
        require(audioOnly == null || (height == null && fps == null && hdr == null && videoCodec == null && container == null)) {
            "an audio-only format has no video choices"
        }
    }
}
