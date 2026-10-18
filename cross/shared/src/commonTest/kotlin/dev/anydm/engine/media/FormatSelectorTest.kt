package dev.anydm.engine.media

import dev.anydm.engine.core.AudioFormat
import dev.anydm.engine.core.Container
import dev.anydm.engine.core.Format
import dev.anydm.engine.core.HdrChoice
import dev.anydm.engine.core.QualityPreset
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.test.Test

class FormatSelectorTest {
    private fun video(
        id: String,
        height: Int = 1080,
        fps: Double = 30.0,
        tbr: Double = 4000.0,
        codec: String = "avc1.640028",
        ext: String = "mp4",
        hdr: Boolean = false,
    ) = MediaStream(id, "https://cdn.test/v/$id", ext, "https", codec, null, height, fps, hdr, tbr, 1_000_000L, emptyList(), null)

    private fun audio(
        id: String,
        tbr: Double = 130.0,
        codec: String = "mp4a.40.2",
        ext: String = "m4a",
    ) = MediaStream(id, "https://cdn.test/v/$id", ext, "https", null, codec, null, null, false, tbr, 100_000L, emptyList(), null)

    private fun muxed(
        id: String,
        height: Int = 720,
        tbr: Double = 1500.0,
    ) = MediaStream(
        id,
        "https://cdn.test/v/$id",
        "mp4",
        "https",
        "avc1.64001F",
        "mp4a.40.2",
        height,
        30.0,
        false,
        tbr,
        1_000_000L,
        emptyList(),
        null,
    )

    private fun info(vararg streams: MediaStream) = MediaInfo("id", "Title", null, null, "Generic", streams.toList())

    private fun chosen(
        info: MediaInfo,
        format: Format = Format(),
    ): Selection = (FormatSelector.choose(info, format) as Choice.Ok).selection

    private fun noMatch(
        info: MediaInfo,
        format: Format = Format(),
    ): String = (FormatSelector.choose(info, format) as Choice.NoMatch).reason

    @Test
    fun bestPicksTheTallestThenFastestStreamPlusBestAudio() {
        val selection =
            chosen(
                info(
                    video("137"),
                    video("138", fps = 60.0, tbr = 6000.0),
                    video("139", height = 720),
                    audio("140"),
                    audio("251", tbr = 160.0, codec = "opus", ext = "webm"),
                ),
            )

        selection.video!!.formatId shouldBe "138"
        selection.audio!!.formatId shouldBe "251"
        selection.streams.map { it.formatId } shouldBe listOf("138", "251")
        selection.needsFfmpeg shouldBe true
    }

    @Test
    fun balancedCapsAtFullHdAndSkipsHdr() {
        val selection =
            chosen(
                info(
                    video("uhd", height = 2160),
                    video("hdr", fps = 60.0, hdr = true),
                    video("sdr"),
                    video("hd", height = 720),
                    audio("140"),
                ),
                Format(QualityPreset.BALANCED),
            )

        selection.video!!.formatId shouldBe "sdr"
    }

    @Test
    fun smallTakesTheTallestUnder480ThenTheLowestBitrate() {
        val selection =
            chosen(
                info(
                    video("720", height = 720),
                    video("a", height = 480, tbr = 900.0),
                    video("b", height = 480, tbr = 600.0),
                    video("360", height = 360, tbr = 100.0),
                    audio("140"),
                ),
                Format(QualityPreset.SMALL),
            )

        selection.video!!.formatId shouldBe "b"
    }

    @Test
    fun anExplicitHeightPicksTheTallestNotAbove() {
        val streams = info(video("1080"), video("720", height = 720), video("480", height = 480), audio("140"))

        chosen(streams, Format(height = 800)).video!!.formatId shouldBe "720"
        chosen(streams, Format(height = 480)).video!!.formatId shouldBe "480"
    }

    @Test
    fun anExplicitFpsCapsTheFrameRate() {
        val streams = info(video("60", fps = 60.0), video("30", fps = 30.0), audio("140"))

        chosen(streams, Format(fps = 30)).video!!.formatId shouldBe "30"
    }

    @Test
    fun anExplicitCodecMatchesByPrefix() {
        val streams = info(video("avc"), video("vp9", height = 720, codec = "vp9", ext = "webm"), audio("140"))

        chosen(streams, Format(videoCodec = "VP9")).video!!.formatId shouldBe "vp9"
    }

    @Test
    fun hdrYesNeedsAnHdrStream() {
        val withHdr = info(video("sdr"), video("hdr", hdr = true, codec = "vp9.2", ext = "webm"), audio("140"))

        chosen(withHdr, Format(hdr = HdrChoice.YES)).video!!.formatId shouldBe "hdr"
        noMatch(info(video("sdr"), audio("140")), Format(hdr = HdrChoice.YES)) shouldContain "1080p"
    }

    @Test
    fun aMuxedStreamWinsWhenItIsTheOnlyChoice() {
        val selection = chosen(info(muxed("22")))

        selection.video!!.formatId shouldBe "22"
        selection.audio shouldBe null
        selection.needsFfmpeg shouldBe false
        selection.outputExtension shouldBe "mp4"
    }

    @Test
    fun separateStreamsBeatALowerMuxedOne() {
        val selection = chosen(info(muxed("18", height = 360), video("137"), audio("140")))

        selection.streams.map { it.formatId } shouldBe listOf("137", "140")
    }

    @Test
    fun videoAloneWhenThereIsNoAudio() {
        val selection = chosen(info(video("137")))

        selection.streams.map { it.formatId } shouldBe listOf("137")
        selection.needsFfmpeg shouldBe false
        selection.outputExtension shouldBe "mp4"
    }

    @Test
    fun audioOnlyM4aCopiesTheMp4aStream() {
        val selection =
            chosen(info(audio("140"), audio("251", tbr = 160.0, codec = "opus", ext = "webm")), Format(audioOnly = AudioFormat.M4A))

        selection.audio!!.formatId shouldBe "140"
        selection.video shouldBe null
        selection.transcode shouldBe false
        selection.needsFfmpeg shouldBe false
        selection.outputExtension shouldBe "m4a"
    }

    @Test
    fun audioOnlyMp3Transcodes() {
        val selection =
            chosen(info(audio("140"), audio("251", tbr = 160.0, codec = "opus", ext = "webm")), Format(audioOnly = AudioFormat.MP3))

        selection.audio!!.formatId shouldBe "251"
        selection.transcode shouldBe true
        selection.needsFfmpeg shouldBe true
        selection.outputExtension shouldBe "mp3"
    }

    @Test
    fun opusFromAWebmRemuxesWithoutTranscoding() {
        val selection =
            chosen(info(audio("140"), audio("251", tbr = 160.0, codec = "opus", ext = "webm")), Format(audioOnly = AudioFormat.OPUS))

        selection.audio!!.formatId shouldBe "251"
        selection.transcode shouldBe false
        selection.needsFfmpeg shouldBe true
        selection.outputExtension shouldBe "opus"
    }

    @Test
    fun audioOnlyWithoutAudioStreamsIsNoMatch() {
        noMatch(info(video("137")), Format(audioOnly = AudioFormat.M4A)) shouldContain "audio"
    }

    @Test
    fun containerFollowsTheCodecs() {
        val opus = audio("251", codec = "opus", ext = "webm")
        val aac = audio("140")

        chosen(info(video("a"), aac)).container shouldBe Container.MP4
        chosen(info(video("b", codec = "vp9", ext = "webm"), opus)).container shouldBe Container.WEBM
        chosen(info(video("c"), opus)).container shouldBe Container.MKV
        chosen(info(video("d", codec = "av01.0.08M.08"), aac)).container shouldBe Container.MP4
    }

    @Test
    fun anExplicitContainerForcesFfmpeg() {
        val single = chosen(info(muxed("22")), Format(container = Container.MKV))
        single.needsFfmpeg shouldBe true
        single.outputExtension shouldBe "mkv"

        val pair = chosen(info(video("c"), audio("251", codec = "opus", ext = "webm")), Format(container = Container.MP4))
        pair.outputExtension shouldBe "mp4"
    }

    @Test
    fun noMatchListsWhatIsAvailable() {
        val reason = noMatch(info(video("1080"), video("720", height = 720, codec = "vp9"), audio("140")), Format(height = 100))

        reason shouldContain "1080p avc1"
        reason shouldContain "720p vp9"
    }

    @Test
    fun onlyHlsIsRefusedWithAReason() {
        val parsed = (YtDlpJson.parse(MediaFixtures.hlsOnlyJson()) as MediaParse.Ok).info

        noMatch(parsed) shouldContain "HLS"
    }

    @Test
    fun theRecordedPageGivesTheBestPlainPair() {
        val parsed = (YtDlpJson.parse(MediaFixtures.splitVideoJson()) as MediaParse.Ok).info

        chosen(parsed).streams.map { it.formatId } shouldBe listOf("137", "251")
    }
}
