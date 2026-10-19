package dev.anydm.engine.media

import dev.anydm.engine.core.AudioFormat
import dev.anydm.engine.core.EmbedChoice
import dev.anydm.engine.core.Format
import dev.anydm.engine.core.SponsorBlockChoice
import dev.anydm.engine.core.SponsorBlockMode
import dev.anydm.engine.core.SubtitleChoice
import dev.anydm.engine.core.SubtitleFormat
import dev.anydm.engine.core.SubtitleMode
import io.kotest.matchers.shouldBe
import kotlinx.io.files.Path
import kotlin.test.Test

class YtDlpCommandTest {
    private val ytDlp = Path("/tools/yt-dlp")
    private val ffmpeg = Path("/tools/ffmpeg")
    private val staging = Path("/dl/.s")
    private val url = "https://www.example.com/watch?v=abc"
    private val template = listOf("--newline", "--progress-template", "download:%(progress)j")

    private fun selection(
        json: String,
        format: Format,
    ): Selection = (FormatSelector.choose((YtDlpJson.parse(json) as MediaParse.Ok).info, format) as Choice.Ok).selection

    @Test
    fun twoStreamsMergeIntoTheContainer() {
        val s = selection(MediaFixtures.splitVideoJson(), Format(audioCodec = "mp4a"))

        YtDlpCommand.download(ytDlp, ffmpeg, null, s, staging, url, 4000) shouldBe
            listOf("/tools/yt-dlp", "--ignore-config", "-f", "137+140", "--no-playlist", "--no-warnings") + template +
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

        YtDlpCommand.download(ytDlp, ffmpeg, null, s, staging, url, null) shouldBe
            listOf("/tools/yt-dlp", "--ignore-config", "-f", "251", "--no-playlist", "--no-warnings") + template +
            listOf("-x", "--audio-format", "mp3", "--ffmpeg-location", "/tools/ffmpeg", "-o", "/dl/.s/out.%(ext)s", "--", url)
    }

    @Test
    fun aSingleStreamHasNoMergeFlagAndNoLimit() {
        val s = selection(MediaFixtures.rtmpMuxedJson(), Format())

        YtDlpCommand.download(ytDlp, null, null, s, staging, url, null) shouldBe
            listOf("/tools/yt-dlp", "--ignore-config", "-f", "rtmp-1", "--no-playlist", "--no-warnings") + template +
            listOf("-o", "/dl/.s/out.%(ext)s", "--", url)
    }

    @Test
    fun denoGoesBeforeTheOutput() {
        val s = selection(MediaFixtures.rtmpMuxedJson(), Format())

        YtDlpCommand.download(ytDlp, null, Path("/tools/deno"), s, staging, url, null).takeLast(6) shouldBe
            listOf("--js-runtimes", "deno:/tools/deno", "-o", "/dl/.s/out.%(ext)s", "--", url).takeLast(6)
    }

    @Test
    fun aContainerChangeOfOneStreamRemuxes() {
        val s = selection(MediaFixtures.rtmpMuxedJson(), Format(container = dev.anydm.engine.core.Container.MP4))

        YtDlpCommand.download(ytDlp, ffmpeg, null, s, staging, url, null) shouldBe
            listOf("/tools/yt-dlp", "--ignore-config", "-f", "rtmp-1", "--no-playlist", "--no-warnings") + template +
            listOf("--remux-video", "mp4", "--ffmpeg-location", "/tools/ffmpeg", "-o", "/dl/.s/out.%(ext)s", "--", url)
    }

    @Test
    fun aPercentSignInTheFolderIsEscaped() {
        val s = selection(MediaFixtures.rtmpMuxedJson(), Format())

        YtDlpCommand
            .download(
                ytDlp,
                null,
                null,
                s,
                Path("/dl/.100%(x).1.ytdlp"),
                url,
                null,
            ).contains("/dl/.100%%(x).1.ytdlp/out.%(ext)s") shouldBe
            true
    }

    @Test
    fun aSubtitleChoiceBecomesFlags() {
        val separate = Format(subtitles = SubtitleChoice(listOf("en", "fr")))
        val embedded =
            Format(subtitles = SubtitleChoice(listOf("en"), includeAuto = true, mode = SubtitleMode.EMBED, convertTo = SubtitleFormat.SRT))
        val both =
            Format(subtitles = SubtitleChoice(listOf("en"), includeAuto = true, mode = SubtitleMode.BOTH, convertTo = SubtitleFormat.SRT))

        mediaFlags(separate) shouldBe listOf("--write-subs", "--sub-langs", "en,fr")
        mediaFlags(embedded) shouldBe
            listOf("--write-subs", "--write-auto-subs", "--sub-langs", "en", "--convert-subs", "srt", "--embed-subs")
        mediaFlags(both) shouldBe mediaFlags(embedded)
    }

    @Test
    fun sponsorBlockCutAndMarkFlags() {
        mediaFlags(Format(sponsorBlock = SponsorBlockChoice(SponsorBlockMode.CUT))) shouldBe
            listOf("--sponsorblock-remove", "sponsor,intro,outro,selfpromo,interaction,music_offtopic")
        mediaFlags(Format(sponsorBlock = SponsorBlockChoice(SponsorBlockMode.MARK, listOf("sponsor")))) shouldBe
            listOf("--sponsorblock-mark", "sponsor")
    }

    @Test
    fun embedFlags() {
        mediaFlags(Format(embed = EmbedChoice(metadata = true, thumbnail = true, chapters = true))) shouldBe
            listOf("--embed-metadata", "--embed-thumbnail", "--embed-chapters")
        mediaFlags(Format()) shouldBe emptyList()
    }

    @Test
    fun cookieAndPacingFlagsSitBeforeTheOutput() {
        val s = selection(MediaFixtures.rtmpMuxedJson(), Format())
        val extra = listOf("--cookies", "/c/cookies.txt", "--sleep-requests", "1.0")

        val command = YtDlpCommand.download(ytDlp, null, null, s, staging, url, null, Format(), extra)

        command.windowed(extra.size + 1).contains(extra + "-o") shouldBe true
    }

    @Test
    fun theOptionFlagsGoIntoTheCommandOnce() {
        val format =
            Format(sponsorBlock = SponsorBlockChoice(SponsorBlockMode.CUT, listOf("sponsor")), embed = EmbedChoice(chapters = true))
        val s = selection(MediaFixtures.rtmpMuxedJson(), format)

        val command = YtDlpCommand.download(ytDlp, ffmpeg, null, s, staging, url, null, format)

        command.count { it == "--sponsorblock-remove" } shouldBe 1
        command.windowed(2).contains(listOf("--sponsorblock-remove", "sponsor")) shouldBe true
        command.contains("--embed-chapters") shouldBe true
    }
}
