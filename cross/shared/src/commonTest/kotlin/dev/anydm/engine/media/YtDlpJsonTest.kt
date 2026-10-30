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

    private fun stream(id: String) = ok(MediaFixtures.routesJson()).streams.single { it.formatId == id }

    @Test
    fun dashFragmentsAreResolvedAgainstTheBaseUrl() {
        val fragments = stream("dash-v").fragments

        fragments.map { it.url } shouldBe
            listOf("https://cdn.test/m/init.mp4", "https://cdn.test/m/seg-1.m4s", "https://other.test/seg-2.m4s")
        fragments.map { it.duration } shouldBe listOf(null, 4.0, 4.0)
    }

    @Test
    fun aDashStreamWithoutFragmentsIsRoutedToYtDlp() {
        stream("dash-nofrag").fragments shouldBe emptyList()
        stream("dash-nofrag").route shouldBe StreamRoute.YTDLP
    }

    @Test
    fun routesFollowTheProtocol() {
        stream("plain").route shouldBe StreamRoute.PLAIN
        stream("hls").route shouldBe StreamRoute.FRAGMENTS
        stream("hls-native").route shouldBe StreamRoute.FRAGMENTS
        stream("dash-v").route shouldBe StreamRoute.FRAGMENTS
        stream("rtmp").route shouldBe StreamRoute.YTDLP
    }

    @Test
    fun drmIsFlagged() {
        stream("drm").drm shouldBe true
        stream("plain").drm shouldBe false
    }

    private val extras get() = ok(MediaFixtures.extrasJson())

    @Test
    fun subtitleTracksCarryTheirKindAndFormat() {
        val manualEn = extras.subtitles.filter { it.lang == "en" && !it.auto }
        manualEn.map { it.ext } shouldBe listOf("vtt", "srt")
        manualEn.first().name shouldBe "English"
        manualEn.first().url shouldBe "https://cdn.test/s/en.vtt"

        val auto = extras.subtitles.filter { it.auto }
        auto.map { it.lang to it.ext } shouldBe listOf("en-orig" to "vtt", "en" to "vtt", "en" to "json3", "de" to "vtt")
    }

    @Test
    fun aTrackWithoutAUrlIsSkipped() {
        extras.subtitles.filter { it.lang == "fr" }.size shouldBe 1
    }

    @Test
    fun chaptersAreReadAndAnInvertedOneIsSkipped() {
        extras.chapters shouldBe listOf(Chapter(0.0, 60.0, "Intro"), Chapter(60.0, 125.5, "Main"))
    }

    @Test
    fun thumbnailsKeepSizeAndExtension() {
        extras.thumbnails.map { it.ext } shouldBe listOf("webp", "jpg", "jpg")
        extras.thumbnails.map { it.width } shouldBe listOf(1280, 1280, 320)
        extras.thumbnails.map { it.preference } shouldBe listOf(-1, 0, null)
        extras.thumbnails[1].url shouldBe "https://cdn.test/t/b.jpg?x=1"
    }

    @Test
    fun theRootThumbnailIsUsedWhenThereIsNoList() {
        val thumbnails = ok(MediaFixtures.rootThumbnailJson()).thumbnails

        thumbnails.map { it.url to it.ext } shouldBe listOf("https://cdn.test/t/root.png" to "png")
    }

    @Test
    fun channelsAreRead() {
        extras.streams.single().channels shouldBe 6
        ok(MediaFixtures.splitVideoJson()).streams.all { it.channels == null } shouldBe true
    }

    @Test
    fun pageFactsAreRead() {
        extras.duration shouldBe 125.5
        extras.description shouldBe "About it"
        extras.webpageUrl shouldBe "https://www.example.com/watch?v=e1"
        ok(MediaFixtures.splitVideoJson()).webpageUrl shouldBe null
    }
}
