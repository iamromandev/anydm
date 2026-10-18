package dev.anydm.engine.media

/** One downloadable stream of a media page, as yt-dlp lists it (MED-01). */
data class MediaStream(
    val formatId: String,
    val url: String,
    val ext: String,
    val protocol: String,
    /** Null when the stream has no picture. */
    val videoCodec: String?,
    /** Null when the stream has no sound. */
    val audioCodec: String?,
    val height: Int?,
    val fps: Double?,
    val hdr: Boolean,
    /** Kilobits per second. */
    val bitrate: Double?,
    val size: Long?,
    /** What the site wants sent with requests for this stream. */
    val headers: List<Pair<String, String>>,
    val language: String?,
) {
    val hasVideo: Boolean get() = videoCodec != null
    val hasAudio: Boolean get() = audioCodec != null

    /** Fetched as one file over HTTP, which the HTTP engine can do; HLS, DASH and storyboards are not. */
    val isPlain: Boolean get() = protocol == "https" || protocol == "http"
}

/** What yt-dlp knows about one media page. [date] is `yyyyMMdd`. */
data class MediaInfo(
    val id: String,
    val title: String,
    val uploader: String?,
    val date: String?,
    val extractor: String,
    val streams: List<MediaStream>,
)
