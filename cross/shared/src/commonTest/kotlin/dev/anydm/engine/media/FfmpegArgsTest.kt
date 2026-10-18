package dev.anydm.engine.media

import dev.anydm.engine.core.AudioFormat
import dev.anydm.engine.core.Container
import dev.anydm.engine.core.Format
import io.kotest.matchers.shouldBe
import kotlinx.io.files.Path
import kotlin.test.Test

class FfmpegArgsTest {
    private val ffmpeg = Path("/tools/ffmpeg")
    private val out = Path("/dl/out.part")
    private val a = Path("/dl/a.part")
    private val b = Path("/dl/b.part")
    private val head = listOf("/tools/ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error", "-y")

    private fun selection(
        json: String,
        format: Format,
    ): Selection {
        val info = (YtDlpJson.parse(json) as MediaParse.Ok).info
        return (FormatSelector.choose(info, format) as Choice.Ok).selection
    }

    private val split get() = MediaFixtures.splitVideoJson()

    @Test
    fun videoAndAudioIntoMp4() {
        val s = selection(split, Format(audioCodec = "mp4a"))

        Ffmpeg.args(ffmpeg, listOf(a, b), out, s) shouldBe
            head +
            listOf(
                "-i",
                "/dl/a.part",
                "-i",
                "/dl/b.part",
                "-map",
                "0:v:0",
                "-map",
                "1:a:0",
                "-c",
                "copy",
                "-movflags",
                "+faststart",
                "-f",
                "mp4",
                "/dl/out.part",
            )
    }

    @Test
    fun videoAndAudioIntoWebm() {
        val s = selection(split, Format(videoCodec = "vp9", audioCodec = "opus"))

        Ffmpeg.args(ffmpeg, listOf(a, b), out, s) shouldBe
            head +
            listOf("-i", "/dl/a.part", "-i", "/dl/b.part", "-map", "0:v:0", "-map", "1:a:0", "-c", "copy", "-f", "webm", "/dl/out.part")
    }

    @Test
    fun videoAndAudioIntoMkv() {
        val s = selection(split, Format(videoCodec = "avc1", audioCodec = "opus"))

        Ffmpeg.args(ffmpeg, listOf(a, b), out, s) shouldBe
            head +
            listOf(
                "-i",
                "/dl/a.part",
                "-i",
                "/dl/b.part",
                "-map",
                "0:v:0",
                "-map",
                "1:a:0",
                "-c",
                "copy",
                "-f",
                "matroska",
                "/dl/out.part",
            )
    }

    @Test
    fun aSingleStreamIsRewrapped() {
        val s = selection(MediaFixtures.mixedOnlyJson(), Format(container = Container.MKV))

        Ffmpeg.args(ffmpeg, listOf(a), out, s) shouldBe
            head + listOf("-i", "/dl/a.part", "-map", "0", "-c", "copy", "-f", "matroska", "/dl/out.part")
    }

    @Test
    fun opusIsRemuxedWithoutTranscoding() {
        val s = selection(MediaFixtures.audioOnlyJson(), Format(audioOnly = AudioFormat.OPUS))

        Ffmpeg.args(ffmpeg, listOf(a), out, s) shouldBe
            head + listOf("-i", "/dl/a.part", "-vn", "-map", "0:a:0", "-c:a", "copy", "-f", "opus", "/dl/out.part")
    }

    @Test
    fun mp3Transcodes() {
        val s = selection(MediaFixtures.audioOnlyJson(), Format(audioOnly = AudioFormat.MP3))

        Ffmpeg.args(ffmpeg, listOf(a), out, s) shouldBe
            head + listOf("-i", "/dl/a.part", "-vn", "-map", "0:a:0", "-c:a", "libmp3lame", "-q:a", "2", "-f", "mp3", "/dl/out.part")
    }

    @Test
    fun m4aFromAnotherCodecTranscodes() {
        val s = selection(MediaFixtures.audioOnlyJson(), Format(audioOnly = AudioFormat.M4A, audioCodec = "opus"))

        Ffmpeg.args(ffmpeg, listOf(a), out, s) shouldBe
            head + listOf("-i", "/dl/a.part", "-vn", "-map", "0:a:0", "-c:a", "aac", "-b:a", "192k", "-f", "ipod", "/dl/out.part")
    }

    @Test
    fun opusFromAnotherCodecTranscodes() {
        val s = selection(MediaFixtures.audioOnlyJson(), Format(audioOnly = AudioFormat.OPUS, audioCodec = "mp4a"))

        Ffmpeg.args(ffmpeg, listOf(a), out, s) shouldBe
            head + listOf("-i", "/dl/a.part", "-vn", "-map", "0:a:0", "-c:a", "libopus", "-b:a", "160k", "-f", "opus", "/dl/out.part")
    }

    @Test
    fun wavTranscodes() {
        val s = selection(MediaFixtures.audioOnlyJson(), Format(audioOnly = AudioFormat.WAV))

        Ffmpeg.args(ffmpeg, listOf(a), out, s) shouldBe
            head + listOf("-i", "/dl/a.part", "-vn", "-map", "0:a:0", "-c:a", "pcm_s16le", "-f", "wav", "/dl/out.part")
    }
}
