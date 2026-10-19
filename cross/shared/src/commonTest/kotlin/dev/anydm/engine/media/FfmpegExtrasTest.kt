package dev.anydm.engine.media

import dev.anydm.engine.core.AudioFormat
import dev.anydm.engine.core.Format
import dev.anydm.engine.core.SubtitleFormat
import io.kotest.matchers.shouldBe
import kotlinx.io.files.Path
import kotlin.test.Test

class FfmpegExtrasTest {
    private val ffmpeg = Path("/tools/ffmpeg")
    private val out = Path("/dl/out.part")
    private val v = Path("/dl/v.part")
    private val a = Path("/dl/a.part")
    private val sub0 = Path("/dl/sub0.srt")
    private val meta = Path("/dl/meta.txt")
    private val thumb = Path("/dl/thumb.jpg")
    private val head = listOf("/tools/ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error", "-y")

    private fun selection(
        json: String,
        format: Format,
    ): Selection = (FormatSelector.choose((YtDlpJson.parse(json) as MediaParse.Ok).info, format) as Choice.Ok).selection

    private fun sub(
        lang: String,
        ext: String = "srt",
        path: Path = sub0,
    ) = EmbeddedSubtitle(path, lang, ext)

    private val title = listOf("title" to "T", "artist" to "U")

    @Test
    fun mp4WithEverything() {
        val s = selection(MediaFixtures.splitVideoJson(), Format(audioCodec = "mp4a"))
        val extras = MergeExtras(listOf(sub("en")), meta, thumb, title)

        Ffmpeg.args(ffmpeg, listOf(v, a), out, s, extras) shouldBe
            head +
            listOf("-i", "/dl/v.part", "-i", "/dl/a.part", "-i", "/dl/sub0.srt", "-i", "/dl/meta.txt", "-i", "/dl/thumb.jpg") +
            listOf("-map", "0:v:0", "-map", "1:a:0", "-map", "2:0", "-map", "4:0", "-map_chapters", "3") +
            listOf("-c", "copy", "-c:s", "mov_text", "-disposition:v:1", "attached_pic", "-metadata:s:s:0", "language=eng") +
            listOf("-metadata", "title=T", "-metadata", "artist=U", "-movflags", "+faststart", "-f", "mp4", "/dl/out.part")
    }

    @Test
    fun mkvWithSubtitleAndAttachedThumbnail() {
        val s = selection(MediaFixtures.splitVideoJson(), Format(videoCodec = "avc1", audioCodec = "opus"))
        val extras = MergeExtras(listOf(sub("fr")), meta, thumb, emptyList())

        Ffmpeg.args(ffmpeg, listOf(v, a), out, s, extras) shouldBe
            head + listOf("-i", "/dl/v.part", "-i", "/dl/a.part", "-i", "/dl/sub0.srt", "-i", "/dl/meta.txt") +
            listOf("-map", "0:v:0", "-map", "1:a:0", "-map", "2:0", "-map_chapters", "3", "-c", "copy", "-c:s", "srt") +
            listOf("-attach", "/dl/thumb.jpg", "-metadata:s:t", "mimetype=image/jpeg", "-metadata:s:t", "filename=cover.jpg") +
            listOf("-metadata:s:s:0", "language=fra", "-f", "matroska", "/dl/out.part")
    }

    @Test
    fun mkvKeepsAssAsAssAndMixesPerTrack() {
        val s = selection(MediaFixtures.splitVideoJson(), Format(videoCodec = "avc1", audioCodec = "opus"))
        val one = Ffmpeg.args(ffmpeg, listOf(v, a), out, s, MergeExtras(listOf(sub("en", "ass")), null, null, emptyList()))
        val two =
            Ffmpeg.args(
                ffmpeg,
                listOf(v, a),
                out,
                s,
                MergeExtras(listOf(sub("en"), sub("fr", "ass", Path("/dl/sub1.ass"))), null, null, emptyList()),
            )

        one.windowed(2).contains(listOf("-c:s", "ass")) shouldBe true
        two.windowed(2).contains(listOf("-c:s:0", "srt")) shouldBe true
        two.windowed(2).contains(listOf("-c:s:1", "ass")) shouldBe true
    }

    @Test
    fun webmTakesWebvttAndSkipsTheThumbnail() {
        val s = selection(MediaFixtures.splitVideoJson(), Format(videoCodec = "vp9", audioCodec = "opus"))
        val extras = MergeExtras(listOf(sub("en", "vtt")), null, thumb, emptyList())

        Ffmpeg.args(ffmpeg, listOf(v, a), out, s, extras) shouldBe
            head + listOf("-i", "/dl/v.part", "-i", "/dl/a.part", "-i", "/dl/sub0.srt") +
            listOf("-map", "0:v:0", "-map", "1:a:0", "-map", "2:0", "-c", "copy", "-c:s", "webvtt") +
            listOf("-metadata:s:s:0", "language=eng", "-f", "webm", "/dl/out.part")
    }

    @Test
    fun m4aWithCoverChaptersAndMetadata() {
        val s = selection(MediaFixtures.audioOnlyJson(), Format(audioOnly = AudioFormat.M4A))
        val extras = MergeExtras(emptyList(), meta, thumb, listOf("title" to "T"))

        Ffmpeg.args(ffmpeg, listOf(a), out, s, extras) shouldBe
            head + listOf("-i", "/dl/a.part", "-i", "/dl/meta.txt", "-i", "/dl/thumb.jpg") +
            listOf(
                "-map",
                "0:a:0",
                "-map",
                "2:0",
                "-map_chapters",
                "1",
                "-c:a",
                "copy",
                "-c:v",
                "copy",
                "-disposition:v:0",
                "attached_pic",
            ) +
            listOf("-metadata", "title=T", "-f", "ipod", "/dl/out.part")
    }

    @Test
    fun mp3WithCoverIgnoresChapters() {
        val s = selection(MediaFixtures.audioOnlyJson(), Format(audioOnly = AudioFormat.MP3))
        val extras = MergeExtras(emptyList(), meta, thumb, emptyList())

        Ffmpeg.args(ffmpeg, listOf(a), out, s, extras) shouldBe
            head + listOf("-i", "/dl/a.part", "-i", "/dl/thumb.jpg", "-map", "0:a:0", "-map", "1:0") +
            listOf("-c:a", "libmp3lame", "-q:a", "2", "-c:v", "copy", "-disposition:v:0", "attached_pic", "-id3v2_version", "3") +
            listOf("-f", "mp3", "/dl/out.part")
    }

    @Test
    fun opusIgnoresTheThumbnail() {
        val s = selection(MediaFixtures.audioOnlyJson(), Format(audioOnly = AudioFormat.OPUS))
        val extras = MergeExtras(emptyList(), null, thumb, listOf("title" to "T"))

        Ffmpeg.args(ffmpeg, listOf(a), out, s, extras) shouldBe
            head + listOf("-i", "/dl/a.part", "-vn", "-map", "0:a:0", "-c:a", "copy", "-metadata", "title=T", "-f", "opus", "/dl/out.part")
    }

    @Test
    fun noExtrasMeansTodaysLists() {
        val s = selection(MediaFixtures.splitVideoJson(), Format(audioCodec = "mp4a"))

        Ffmpeg.args(ffmpeg, listOf(v, a), out, s, MergeExtras.None) shouldBe Ffmpeg.args(ffmpeg, listOf(v, a), out, s)
    }

    @Test
    fun twoLetterLanguageBecomesThreeLetters() {
        val s = selection(MediaFixtures.splitVideoJson(), Format(audioCodec = "mp4a"))
        for ((given, wanted) in listOf("de-orig" to "deu", "pt-BR" to "por", "ja" to "jpn", "ENG" to "eng")) {
            Ffmpeg.args(ffmpeg, listOf(v, a), out, s, MergeExtras(listOf(sub(given)), null, null, emptyList())).windowed(2).contains(
                listOf("-metadata:s:s:0", "language=$wanted"),
            ) shouldBe true
        }
    }

    @Test
    fun anUnknownLanguageIsLeftOut() {
        val s = selection(MediaFixtures.splitVideoJson(), Format(audioCodec = "mp4a"))

        Ffmpeg
            .args(
                ffmpeg,
                listOf(v, a),
                out,
                s,
                MergeExtras(listOf(sub("xx")), null, null, emptyList()),
            ).contains("-metadata:s:s:0") shouldBe
            false
    }

    @Test
    fun metadataValuesStayOneArgumentEach() {
        val s = selection(MediaFixtures.splitVideoJson(), Format(audioCodec = "mp4a"))
        val awkward = "title" to "Say \"hi\" = a\n-b; \$(rm -rf)"

        val args = Ffmpeg.args(ffmpeg, listOf(v, a), out, s, MergeExtras(emptyList(), null, null, listOf(awkward)))

        // A double quote becomes a single one: Windows would end the argument at it and read the rest as options.
        args.contains("title=Say 'hi' = a\n-b; \$(rm -rf)") shouldBe true
        args[args.indexOf("title=Say 'hi' = a\n-b; \$(rm -rf)") - 1] shouldBe "-metadata"
    }

    @Test
    fun metadataValuesLoseWhatCannotSafelyReachAProcess() {
        val s = selection(MediaFixtures.splitVideoJson(), Format(audioCodec = "mp4a"))
        val pairs = listOf("title" to "a\u0000b\u0007c", "comment" to "path C:\\dir\\", "artist" to "x\ty")

        val args = Ffmpeg.args(ffmpeg, listOf(v, a), out, s, MergeExtras(emptyList(), null, null, pairs))

        args.contains("title=abc") shouldBe true
        args.contains("comment=path C:\\dir") shouldBe true
        args.contains("artist=x\ty") shouldBe true
    }

    @Test
    fun ffmetadataEscapesItsSpecialCharacters() {
        val text = ffmetadataOf(listOf(Chapter(0.0, 1.0, "a=b;c#d\\e\nf")))

        text shouldBe ";FFMETADATA1\n[CHAPTER]\nTIMEBASE=1/1000\nSTART=0\nEND=1000\ntitle=a\\=b\\;c\\#d\\\\e\\\nf\n"
    }

    @Test
    fun ffmetadataCutsLongTitlesAndCountsMilliseconds() {
        val text = ffmetadataOf(listOf(Chapter(1.5, 62.25, "x".repeat(300)), Chapter(62.25, 70.0, "Two")))

        text.contains("START=1500\nEND=62250\ntitle=${"x".repeat(200)}\n") shouldBe true
        text.contains("START=62250\nEND=70000\ntitle=Two\n") shouldBe true
    }

    @Test
    fun subtitleConversionArgs() {
        Ffmpeg.subtitleArgs(ffmpeg, Path("/dl/in.vtt"), out, SubtitleFormat.SRT, "vtt") shouldBe
            head + listOf("-i", "/dl/in.vtt", "-f", "srt", "/dl/out.part")
        Ffmpeg.subtitleArgs(ffmpeg, Path("/dl/in.vtt"), out, null, "vtt") shouldBe
            head + listOf("-i", "/dl/in.vtt", "-c:s", "copy", "-f", "webvtt", "/dl/out.part")
        Ffmpeg.subtitleArgs(ffmpeg, Path("/dl/in.srt"), out, SubtitleFormat.SRT, "srt") shouldBe
            head + listOf("-i", "/dl/in.srt", "-c:s", "copy", "-f", "srt", "/dl/out.part")
        Ffmpeg.subtitleArgs(ffmpeg, Path("/dl/in.ssa"), out, SubtitleFormat.ASS, "ssa") shouldBe
            head + listOf("-i", "/dl/in.ssa", "-f", "ass", "/dl/out.part")
    }

    @Test
    fun thumbnailConversionArgs() {
        Ffmpeg.thumbnailArgs(ffmpeg, Path("/dl/t.webp"), out) shouldBe
            head + listOf("-i", "/dl/t.webp", "-frames:v", "1", "-q:v", "2", "-f", "image2", "/dl/out.part")
    }
}
