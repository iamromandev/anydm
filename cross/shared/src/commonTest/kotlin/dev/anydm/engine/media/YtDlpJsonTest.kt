package dev.anydm.engine.media

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.test.Test

class YtDlpJsonTest {
    private fun ok(json: String): MediaInfo = (YtDlpJson.parse(json) as MediaParse.Ok).info

    private fun rejected(json: String): String = (YtDlpJson.parse(json) as MediaParse.Rejected).reason

    @Test
    fun parsesTitleUploaderIdDateAndExtractor() {
        val info = ok(MediaFixtures.splitVideoJson())

        info.id shouldBe "abc123"
        info.title shouldBe "Test Video"
        info.uploader shouldBe "Some Channel"
        info.date shouldBe "20260131"
        info.extractor shouldBe "Youtube"
    }

    @Test
    fun audioOnlyStreamsHaveNoVideoCodec() {
        val audio = ok(MediaFixtures.splitVideoJson()).streams.single { it.formatId == "140" }

        audio.videoCodec shouldBe null
        audio.audioCodec shouldBe "mp4a.40.2"
        audio.hasVideo shouldBe false
        audio.hasAudio shouldBe true
        audio.ext shouldBe "m4a"
        audio.language shouldBe "en"
        audio.size shouldBe 3_000_000L
    }

    @Test
    fun aVideoStreamKeepsItsPictureFacts() {
        val video = ok(MediaFixtures.splitVideoJson()).streams.single { it.formatId == "137" }

        video.height shouldBe 1080
        video.fps shouldBe 30.0
        video.bitrate shouldBe 4000.0
        video.audioCodec shouldBe null
        video.url shouldBe "https://cdn.test/v/137"
        video.headers shouldBe listOf("User-Agent" to "UA-137")
        video.isPlain shouldBe true
        video.hdr shouldBe false
    }

    @Test
    fun sizeFallsBackToFilesizeApprox() {
        ok(MediaFixtures.approximateSize()).streams.single().size shouldBe 777L
    }

    @Test
    fun hdrIsAnythingButSdr() {
        ok(MediaFixtures.hdrStream()).streams.single().hdr shouldBe true
        ok(MediaFixtures.splitVideoJson()).streams.single { it.formatId == "137" }.hdr shouldBe false
    }

    @Test
    fun storyboardsAndHlsAreNotPlain() {
        val streams = ok(MediaFixtures.splitVideoJson()).streams

        streams.single { it.formatId == "95" }.isPlain shouldBe false
        streams.single { it.formatId == "sb0" }.isPlain shouldBe false
        streams.single { it.formatId == "22" || it.formatId == "18" }.isPlain shouldBe true
    }

    @Test
    fun aDirectMediaPageBecomesOneStream() {
        val stream = ok(MediaFixtures.directJson()).streams.single()

        stream.url shouldBe "https://cdn.test/v/direct"
        stream.ext shouldBe "mp4"
        stream.hasVideo shouldBe true
        stream.hasAudio shouldBe true
    }

    @Test
    fun aPlaylistIsRejected() {
        rejected(MediaFixtures.playlistJson()) shouldContain "playlist"
    }

    @Test
    fun aLiveStreamIsRejected() {
        rejected(MediaFixtures.liveJson()) shouldContain "live"
    }

    @Test
    fun junkIsRejected() {
        rejected("not json at all") shouldContain "JSON"
        rejected("""{"id":"x"}""") shouldContain "title"
    }

    @Test
    fun aFormatWithoutAUrlIsSkipped() {
        ok(MediaFixtures.withoutUrl()).streams.map { it.formatId } shouldBe listOf("22")
    }
}
