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
    /** DASH's fragments as yt-dlp lists them, init segment first; empty for other protocols (an HLS playlist is read later). */
    val fragments: List<FragmentRef> = emptyList(),
    /** The site protects this stream with DRM, which we can't play back or download. */
    val drm: Boolean = false,
) {
    val hasVideo: Boolean get() = videoCodec != null
    val hasAudio: Boolean get() = audioCodec != null

    /** Fetched as one file over HTTP, which the HTTP engine can do; HLS, DASH and storyboards are not. */
    val isPlain: Boolean get() = protocol == "https" || protocol == "http"

    /** Who downloads this stream: the HTTP engine as one file, our fragment downloader, or yt-dlp's own. */
    val route: StreamRoute
        get() =
            when {
                isPlain -> StreamRoute.PLAIN
                protocol == "m3u8" || protocol == "m3u8_native" -> StreamRoute.FRAGMENTS
                protocol == "http_dash_segments" && fragments.isNotEmpty() -> StreamRoute.FRAGMENTS
                else -> StreamRoute.YTDLP
            }
}

/** How a stream is downloaded (MED-03, MED-05). */
enum class StreamRoute { PLAIN, FRAGMENTS, YTDLP }

/** One piece of a fragmented stream: its [url], and when only part of the resource is wanted, the byte range [rangeStart] until [rangeEnd]. */
data class FragmentRef(
    val url: String,
    val duration: Double? = null,
    val rangeStart: Long? = null,
    val rangeEnd: Long? = null,
)

/** What yt-dlp knows about one media page. [date] is `yyyyMMdd`. */
data class MediaInfo(
    val id: String,
    val title: String,
    val uploader: String?,
    val date: String?,
    val extractor: String,
    val streams: List<MediaStream>,
)
