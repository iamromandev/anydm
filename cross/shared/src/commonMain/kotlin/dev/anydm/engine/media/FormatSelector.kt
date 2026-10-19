package dev.anydm.engine.media

import dev.anydm.engine.core.AudioFormat
import dev.anydm.engine.core.Container
import dev.anydm.engine.core.Format
import dev.anydm.engine.core.HdrChoice
import dev.anydm.engine.core.QualityPreset

/**
 * The streams to download for one choice (MED-09 to MED-11). [video] and [audio] are what to fetch: a muxed stream is
 * [video] alone. [needsFfmpeg] is whether the fetched files must be merged, converted or re-wrapped to become the
 * output, whose extension is [outputExtension]; [transcode] is whether the sound is re-encoded.
 */
data class Selection(
    val video: MediaStream?,
    val audio: MediaStream?,
    val container: Container?,
    val audioFormat: AudioFormat?,
    val outputExtension: String,
    val transcode: Boolean,
    val needsFfmpeg: Boolean,
    /** Who downloads it: the most demanding route among [streams] (yt-dlp's own over our fragments over one HTTP file). */
    val route: StreamRoute = StreamRoute.PLAIN,
) {
    /** Video first, then audio. */
    val streams: List<MediaStream> get() = listOfNotNull(video, audio)
}

sealed interface Choice {
    data class Ok(
        val selection: Selection,
    ) : Choice

    /** Nothing fits; [reason] says why and what there was. */
    data class NoMatch(
        val reason: String,
    ) : Choice
}

/** Picks streams from what yt-dlp listed, by the user's [Format]. Pure: the same page and choice give the same streams. */
object FormatSelector {
    /**
     * The streams for [format]. [pinned] are format ids chosen on an earlier extraction of the page: while they are all
     * still offered, and still make a whole choice, they are kept, so a page whose ranking changed meanwhile doesn't
     * swap streams under a download that already holds some.
     */
    fun choose(
        info: MediaInfo,
        format: Format,
        pinned: List<String> = emptyList(),
    ): Choice {
        // A DRM stream can't be played back or downloaded by us or by yt-dlp; everything else has a route.
        val usable = info.streams.filter { !it.drm }
        if (usable.isEmpty()) return Choice.NoMatch("this page offers no stream we can download")
        if (pinned.isNotEmpty()) pinnedChoice(usable, format, pinned)?.let { return it }
        return if (format.audioOnly != null) audioOnly(usable, format, format.audioOnly) else withVideo(info, usable, format)
    }

    private fun pinnedChoice(
        plain: List<MediaStream>,
        format: Format,
        pinned: List<String>,
    ): Choice? {
        val streams = pinned.map { id -> plain.firstOrNull { it.formatId == id } ?: return null }
        val target = format.audioOnly
        return when {
            target != null -> {
                streams.singleOrNull()?.takeIf { it.hasAudio && !it.hasVideo }?.let { Choice.Ok(audioSelection(it, target)) }
            }

            streams.size == 1 && streams[0].hasVideo -> {
                Choice.Ok(videoSelection(streams[0], null, format))
            }

            streams.size == 2 && streams[0].hasVideo && !streams[0].hasAudio && streams[1].hasAudio && !streams[1].hasVideo -> {
                Choice.Ok(videoSelection(streams[0], streams[1], format))
            }

            else -> {
                null
            }
        }
    }

    private fun audioOnly(
        plain: List<MediaStream>,
        format: Format,
        target: AudioFormat,
    ): Choice {
        val matching = plain.filter { it.hasAudio && matches(it.audioCodec, format.audioCodec) }
        // A page that only offers sound inside its videos still has the sound: ffmpeg takes it out.
        val sources = matching.filter { !it.hasVideo }.ifEmpty { matching }
        if (sources.isEmpty()) return Choice.NoMatch("this page has no audio stream that matches")
        val wanted =
            when (target) {
                AudioFormat.M4A -> "mp4a"
                AudioFormat.OPUS -> "opus"
                else -> null
            }
        val preferred = wanted?.let { w -> sources.filter { it.audioCodec.orEmpty().startsWith(w, ignoreCase = true) } }.orEmpty()
        return Choice.Ok(audioSelection((preferred.ifEmpty { sources }).maxBy { it.bitrate ?: 0.0 }, target))
    }

    private fun audioSelection(
        stream: MediaStream,
        target: AudioFormat,
    ): Selection {
        val transcode =
            when (target) {
                AudioFormat.MP3, AudioFormat.WAV -> true
                AudioFormat.M4A -> !stream.audioCodec.orEmpty().startsWith("mp4a", ignoreCase = true)
                AudioFormat.OPUS -> !stream.audioCodec.orEmpty().startsWith("opus", ignoreCase = true)
            }
        return Selection(
            video = null,
            audio = stream,
            container = null,
            audioFormat = target,
            outputExtension = target.extension,
            transcode = transcode,
            needsFfmpeg = transcode || stream.hasVideo || stream.ext != target.extension || stream.route == StreamRoute.FRAGMENTS,
            route = stream.route,
        )
    }

    private fun withVideo(
        info: MediaInfo,
        plain: List<MediaStream>,
        format: Format,
    ): Choice {
        val cap = format.height ?: capOf(format.preset)
        val candidates =
            plain.filter { stream ->
                stream.hasVideo &&
                    (cap == null || (stream.height ?: 0) <= cap) &&
                    (format.fps == null || (stream.fps ?: 0.0) <= format.fps) &&
                    hdrAllowed(stream, format) &&
                    matches(stream.videoCodec, format.videoCodec) &&
                    (!stream.hasAudio || matches(stream.audioCodec, format.audioCodec))
            }
        if (candidates.isEmpty()) {
            val seen = plain.filter { it.hasVideo }.sortedByDescending { it.height ?: 0 }
            val available = seen.map { "${it.height ?: "?"}p ${it.videoCodec.orEmpty().substringBefore('.')}" }.distinct()
            return Choice.NoMatch("no video stream matches this choice; available: ${available.joinToString()}")
        }
        val ordered =
            if (format.preset == QualityPreset.SMALL) {
                compareByDescending<MediaStream> { it.height ?: 0 }.thenBy { it.route.ordinal }.thenBy { it.bitrate ?: 0.0 }
            } else {
                compareByDescending<MediaStream> { it.height ?: 0 }
                    .thenByDescending { it.fps ?: 0.0 }
                    .thenBy { it.route.ordinal }
                    .thenByDescending { it.bitrate ?: 0.0 }
            }
        val sorted = candidates.sortedWith(ordered)
        val best = sorted.first()
        if (best.hasAudio) return Choice.Ok(videoSelection(best, null, format))
        val audio =
            plain.filter { it.hasAudio && !it.hasVideo && matches(it.audioCodec, format.audioCodec) }.maxByOrNull {
                it.bitrate
                    ?: 0.0
            }
        if (audio != null) return Choice.Ok(videoSelection(best, audio, format))
        // No usable sound to pair with. A video is never handed over silent when the page has sound somewhere.
        if (info.streams.none { it.hasAudio }) return Choice.Ok(videoSelection(best, null, format))
        sorted.firstOrNull { it.hasAudio }?.let { return Choice.Ok(videoSelection(it, null, format)) }
        return Choice.NoMatch("no audio stream of this video fits the choice")
    }

    private fun videoSelection(
        video: MediaStream,
        audio: MediaStream?,
        format: Format,
    ): Selection {
        val container = format.container ?: if (audio != null) containerFor(video, audio) else containerOfExtension(video.ext)
        val outputExtension = container?.extension ?: video.ext
        return Selection(
            video = video,
            audio = audio,
            container = container,
            audioFormat = null,
            outputExtension = outputExtension,
            transcode = false,
            // Fragments are joined as they came (TS or fMP4 pieces): ffmpeg rewraps them into the container.
            needsFfmpeg = audio != null || outputExtension != video.ext || routeOf(video, audio) == StreamRoute.FRAGMENTS,
            route = routeOf(video, audio),
        )
    }

    private fun routeOf(
        video: MediaStream,
        audio: MediaStream?,
    ): StreamRoute = listOfNotNull(video, audio).maxOf { it.route }

    private fun capOf(preset: QualityPreset): Int? =
        when (preset) {
            QualityPreset.BEST -> null
            QualityPreset.BALANCED -> 1080
            QualityPreset.SMALL -> 480
        }

    private fun hdrAllowed(
        stream: MediaStream,
        format: Format,
    ): Boolean =
        when (format.hdr) {
            HdrChoice.YES -> stream.hdr
            HdrChoice.NO -> !stream.hdr
            HdrChoice.ANY -> true
            null -> format.preset != QualityPreset.BALANCED || !stream.hdr
        }

    private fun matches(
        codec: String?,
        prefix: String?,
    ): Boolean = prefix == null || (codec != null && codec.startsWith(prefix, ignoreCase = true))

    private fun containerOfExtension(ext: String): Container? = Container.entries.firstOrNull { it.extension == ext }

    /** MP4 holds H.264, H.265 and AV1 with AAC or AC-3; WebM holds VP9 or AV1 with Opus or Vorbis; Matroska holds anything. */
    private fun containerFor(
        video: MediaStream,
        audio: MediaStream,
    ): Container {
        val v = video.videoCodec.orEmpty().lowercase()
        val a = audio.audioCodec.orEmpty().lowercase()
        val mp4Video = listOf("avc", "hvc", "hev", "av01").any { v.startsWith(it) }
        val mp4Audio = listOf("mp4a", "ac-3", "ec-3").any { a.startsWith(it) }
        val webmVideo = listOf("vp9", "vp09", "av01").any { v.startsWith(it) }
        val webmAudio = listOf("opus", "vorbis").any { a.startsWith(it) }
        return when {
            mp4Video && mp4Audio -> Container.MP4
            webmVideo && webmAudio -> Container.WEBM
            else -> Container.MKV
        }
    }
}
