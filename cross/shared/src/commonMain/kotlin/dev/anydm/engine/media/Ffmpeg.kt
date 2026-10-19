package dev.anydm.engine.media

import dev.anydm.engine.core.AudioFormat
import dev.anydm.engine.core.Container
import dev.anydm.engine.core.SubtitleFormat
import kotlinx.io.files.Path

private val TWO_TO_THREE =
    mapOf(
        "en" to "eng",
        "fr" to "fra",
        "de" to "deu",
        "es" to "spa",
        "it" to "ita",
        "pt" to "por",
        "ru" to "rus",
        "ja" to "jpn",
        "ko" to "kor",
        "zh" to "zho",
        "nl" to "nld",
        "pl" to "pol",
        "tr" to "tur",
        "ar" to "ara",
        "hi" to "hin",
    )

/** The ffmpeg command lines that turn downloaded streams into the output (MED-04, MED-11, MED-13, MED-15). */
internal object Ffmpeg {
    private fun head(ffmpeg: Path) = listOf(ffmpeg.toString(), "-nostdin", "-hide_banner", "-loglevel", "error", "-y")

    fun args(
        ffmpeg: Path,
        inputs: List<Path>,
        output: Path,
        selection: Selection,
    ): List<String> = args(ffmpeg, inputs, output, selection, MergeExtras.None)

    /**
     * Writes [output] from [inputs] (the selection's streams in order) as [selection] says: video and audio joined
     * without re-encoding, one stream rewrapped, or sound converted; with [extras] the subtitle tracks, chapters, cover
     * and metadata the output can hold go in too (the rest is left out). The muxer is named because [output] is a partial
     * whose name says nothing about its format. The inputs come in this order: the streams, the subtitles, the chapters
     * file, the cover.
     */
    fun args(
        ffmpeg: Path,
        inputs: List<Path>,
        output: Path,
        selection: Selection,
        extras: MergeExtras,
    ): List<String> =
        buildList {
            addAll(head(ffmpeg))
            val audioFormat = selection.audioFormat
            val container = selection.container ?: Container.MKV
            val subtitles = if (audioFormat == null) extras.subtitles else emptyList()
            val chapters = extras.chaptersFile?.takeIf { audioFormat == null || audioFormat == AudioFormat.M4A }
            val coverInput =
                extras.thumbnail?.takeIf {
                    if (audioFormat ==
                        null
                    ) {
                        container == Container.MP4
                    } else {
                        audioFormat == AudioFormat.M4A || audioFormat == AudioFormat.MP3
                    }
                }
            val coverAttached = extras.thumbnail?.takeIf { audioFormat == null && container == Container.MKV }
            for (input in inputs) addAll(listOf("-i", input.toString()))
            var next = inputs.size
            val subtitleIndexes = subtitles.map { next++ }
            subtitles.forEach { addAll(listOf("-i", it.path.toString())) }
            val chaptersIndex = chapters?.let { next++ }
            chapters?.let { addAll(listOf("-i", it.toString())) }
            val coverIndex = coverInput?.let { next++ }
            coverInput?.let { addAll(listOf("-i", it.toString())) }
            if (audioFormat != null) {
                if (coverIndex == null) add("-vn")
                addAll(listOf("-map", "0:a:0"))
                coverIndex?.let { addAll(listOf("-map", "$it:0")) }
                chaptersIndex?.let { addAll(listOf("-map_chapters", it.toString())) }
                addAll(audioCodec(audioFormat, selection.transcode))
                if (coverIndex != null) addAll(listOf("-c:v", "copy", "-disposition:v:0", "attached_pic"))
                if (coverIndex != null && audioFormat == AudioFormat.MP3) addAll(listOf("-id3v2_version", "3"))
                if (!selection.transcode && audioFormat == AudioFormat.M4A && hlsAac(selection)) addAll(listOf("-bsf:a", "aac_adtstoasc"))
                for ((key, value) in extras.metadata) addAll(listOf("-metadata", "$key=$value"))
                addAll(listOf("-f", audioMuxer(audioFormat)))
            } else {
                if (selection.video != null && selection.audio != null) {
                    addAll(listOf("-map", "0:v:0", "-map", "1:a:0"))
                } else {
                    addAll(listOf("-map", "0"))
                }
                subtitleIndexes.forEach { addAll(listOf("-map", "$it:0")) }
                coverIndex?.let { addAll(listOf("-map", "$it:0")) }
                chaptersIndex?.let { addAll(listOf("-map_chapters", it.toString())) }
                addAll(listOf("-c", "copy"))
                addAll(subtitleCodecs(container, subtitles))
                if (coverIndex != null) addAll(listOf("-disposition:v:1", "attached_pic"))
                coverAttached?.let { path ->
                    val extension =
                        path.name
                            .substringAfterLast('.', "jpg")
                            .lowercase()
                            .let { if (it == "jpeg") "jpg" else it }
                    addAll(
                        listOf("-attach", path.toString(), "-metadata:s:t", "mimetype=image/${if (extension == "png") "png" else "jpeg"}"),
                    )
                    addAll(listOf("-metadata:s:t", "filename=cover.$extension"))
                }
                subtitles.forEachIndexed { index, subtitle ->
                    languageTag(subtitle.lang)?.let { addAll(listOf("-metadata:s:s:$index", "language=$it")) }
                }
                for ((key, value) in extras.metadata) addAll(listOf("-metadata", "$key=$value"))
                if (container == Container.MP4 && hlsAac(selection)) addAll(listOf("-bsf:a", "aac_adtstoasc"))
                if (container == Container.MP4) addAll(listOf("-movflags", "+faststart"))
                addAll(listOf("-f", videoMuxer(container)))
            }
            add(output.toString())
        }

    /** Turns the subtitle file [input] into [output] in [format] (MED-13); no [format], or the same one, copies it. */
    fun subtitleArgs(
        ffmpeg: Path,
        input: Path,
        output: Path,
        format: SubtitleFormat?,
        sourceExtension: String,
    ): List<String> =
        buildList {
            addAll(head(ffmpeg))
            addAll(listOf("-i", input.toString()))
            val target = format?.extension ?: sourceExtension
            if (format == null || format.extension == sourceExtension) addAll(listOf("-c:s", "copy"))
            addAll(listOf("-f", subtitleMuxer(target), output.toString()))
        }

    /** Turns the picture [input] into a JPEG at [output] (MED-15), for a format the containers can't hold. */
    fun thumbnailArgs(
        ffmpeg: Path,
        input: Path,
        output: Path,
    ): List<String> = head(ffmpeg) + listOf("-i", input.toString(), "-frames:v", "1", "-q:v", "2", "-f", "image2", output.toString())

    /** One codec for every subtitle track when they agree, else one per track. */
    private fun subtitleCodecs(
        container: Container,
        subtitles: List<EmbeddedSubtitle>,
    ): List<String> {
        if (subtitles.isEmpty()) return emptyList()
        val codecs = subtitles.map { subtitleCodec(container, it.extension) }
        return if (codecs.distinct().size == 1) {
            listOf("-c:s", codecs.first())
        } else {
            codecs.withIndex().flatMap { (index, codec) -> listOf("-c:s:$index", codec) }
        }
    }

    private fun subtitleCodec(
        container: Container,
        extension: String,
    ): String =
        when (container) {
            Container.MP4 -> "mov_text"
            Container.WEBM -> "webvtt"
            Container.MKV -> if (extension.lowercase() in setOf("ass", "ssa")) "ass" else "srt"
        }

    private fun subtitleMuxer(extension: String): String =
        when (extension.lowercase()) {
            "vtt" -> "webvtt"
            "ass", "ssa" -> "ass"
            else -> "srt"
        }

    /** ffmpeg wants a three-letter code in a track's language tag; the common two-letter ones are mapped, others left out. */
    private fun languageTag(lang: String): String? {
        val primary = lang.substringBefore('-').lowercase()
        return if (primary.length == 3 && primary.all { it in 'a'..'z' }) primary else TWO_TO_THREE[primary]
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
