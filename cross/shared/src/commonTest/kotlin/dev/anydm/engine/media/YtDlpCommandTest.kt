package dev.anydm.engine.media

import dev.anydm.engine.core.AudioFormat
import dev.anydm.engine.core.Format
import io.kotest.matchers.shouldBe
import kotlinx.io.files.Path
import kotlin.test.Test

class YtDlpCommandTest {
    private val ytDlp = Path("/tools/yt-dlp")
    private val ffmpeg = Path("/tools/ffmpeg")
    private val out = Path("/dl/.s/out.%(ext)s")
    private val url = "https://www.example.com/watch?v=abc"
    private val template = listOf("--newline", "--progress-template", "download:%(progress)j")

    private fun selection(
        json: String,
        format: Format,
    ): Selection = (FormatSelector.choose((YtDlpJson.parse(json) as MediaParse.Ok).info, format) as Choice.Ok).selection

    @Test
    fun twoStreamsMergeIntoTheContainer() {
        val s = selection(MediaFixtures.splitVideoJson(), Format(audioCodec = "mp4a"))

        YtDlpCommand.download(ytDlp, ffmpeg, null, s, out, url, 4000) shouldBe
            listOf("/tools/yt-dlp", "-f", "137+140", "--no-playlist", "--no-warnings") + template +
            listOf(
                "--limit-rate",
                "4000",
                "--merge-output-format",
                "mp4",
                "--ffmpeg-location",
                "/tools/ffmpeg",
                "-o",
                "/dl/.s/out.%(ext)s",
                "--",
                url,
            )
    }

    @Test
    fun audioOnlyExtractsTheSound() {
        val s = selection(MediaFixtures.audioOnlyJson(), Format(audioOnly = AudioFormat.MP3))

        YtDlpCommand.download(ytDlp, ffmpeg, null, s, out, url, null) shouldBe
            listOf("/tools/yt-dlp", "-f", "251", "--no-playlist", "--no-warnings") + template +
            listOf("-x", "--audio-format", "mp3", "--ffmpeg-location", "/tools/ffmpeg", "-o", "/dl/.s/out.%(ext)s", "--", url)
    }

    @Test
    fun aSingleStreamHasNoMergeFlagAndNoLimit() {
        val s = selection(MediaFixtures.rtmpMuxedJson(), Format())

        YtDlpCommand.download(ytDlp, null, null, s, out, url, null) shouldBe
            listOf("/tools/yt-dlp", "-f", "rtmp-1", "--no-playlist", "--no-warnings") + template +
            listOf("-o", "/dl/.s/out.%(ext)s", "--", url)
    }

    @Test
    fun denoGoesBeforeTheOutput() {
        val s = selection(MediaFixtures.rtmpMuxedJson(), Format())

        YtDlpCommand.download(ytDlp, null, Path("/tools/deno"), s, out, url, null).takeLast(6) shouldBe
            listOf("--js-runtimes", "deno:/tools/deno", "-o", "/dl/.s/out.%(ext)s", "--", url).takeLast(6)
    }
}
