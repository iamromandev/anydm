package dev.anydm.engine.media

import dev.anydm.engine.core.AudioFormat
import dev.anydm.engine.core.Container
import kotlinx.io.files.Path

/** The ffmpeg command lines that turn downloaded streams into the output (MED-04, MED-11). */
internal object Ffmpeg {
    /**
     * Writes [output] from [inputs] (the selection's streams in order) as [selection] says: video and audio joined
     * without re-encoding, one stream rewrapped, or sound converted. The muxer is named because [output] is a partial
     * whose name says nothing about its format.
     */
    fun args(
        ffmpeg: Path,
        inputs: List<Path>,
        output: Path,
        selection: Selection,
    ): List<String> =
        buildList {
            addAll(listOf(ffmpeg.toString(), "-nostdin", "-hide_banner", "-loglevel", "error", "-y"))
            for (input in inputs) addAll(listOf("-i", input.toString()))
            val audioFormat = selection.audioFormat
            if (audioFormat != null) {
                addAll(listOf("-vn", "-map", "0:a:0"))
                addAll(audioCodec(audioFormat, selection.transcode))
                if (!selection.transcode && audioFormat == AudioFormat.M4A && hlsAac(selection)) addAll(listOf("-bsf:a", "aac_adtstoasc"))
                addAll(listOf("-f", audioMuxer(audioFormat)))
            } else {
                if (selection.video != null && selection.audio != null) {
                    addAll(listOf("-map", "0:v:0", "-map", "1:a:0", "-c", "copy"))
                } else {
                    addAll(listOf("-map", "0", "-c", "copy"))
                }
                val container = selection.container
                if (container == Container.MP4 && hlsAac(selection)) addAll(listOf("-bsf:a", "aac_adtstoasc"))
                if (container == Container.MP4) addAll(listOf("-movflags", "+faststart"))
                addAll(listOf("-f", container?.let(::videoMuxer) ?: "matroska"))
            }
            add(output.toString())
        }

    /** HLS carries AAC as ADTS frames, which MP4 wants without their headers. */
    private fun hlsAac(selection: Selection): Boolean =
        selection.streams.any { it.protocol.startsWith("m3u8") && it.audioCodec.orEmpty().startsWith("mp4a", ignoreCase = true) }

    private fun audioCodec(
        format: AudioFormat,
        transcode: Boolean,
    ): List<String> =
        if (!transcode) {
            listOf("-c:a", "copy")
        } else {
            when (format) {
                AudioFormat.MP3 -> listOf("-c:a", "libmp3lame", "-q:a", "2")
                AudioFormat.M4A -> listOf("-c:a", "aac", "-b:a", "192k")
                AudioFormat.OPUS -> listOf("-c:a", "libopus", "-b:a", "160k")
                AudioFormat.WAV -> listOf("-c:a", "pcm_s16le")
            }
        }

    private fun audioMuxer(format: AudioFormat): String =
        when (format) {
            AudioFormat.MP3 -> "mp3"
            AudioFormat.M4A -> "ipod"
            AudioFormat.OPUS -> "opus"
            AudioFormat.WAV -> "wav"
        }

    private fun videoMuxer(container: Container): String =
        when (container) {
            Container.MP4 -> "mp4"
            Container.MKV -> "matroska"
            Container.WEBM -> "webm"
        }
}
