package dev.anydm.engine.media

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test

class HlsPlaylistTest {
    private val base = "https://a.test/v/index.m3u8"

    private fun media(text: String): List<FragmentRef> = HlsPlaylist.parse(text, base).shouldBeInstanceOf<HlsParse.Media>().fragments

    private fun lines(vararg l: String) = l.joinToString("\n")

    @Test
    fun aMediaPlaylistListsItsFragmentsWithDurations() {
        val fragments =
            media(
                lines(
                    "#EXTM3U",
                    "#EXT-X-VERSION:3",
                    "#EXT-X-TARGETDURATION:10",
                    "#EXTINF:9.009,",
                    "seg0.ts",
                    "#EXTINF:3.0,title",
                    "seg1.ts",
                    "#EXT-X-ENDLIST",
                ),
            )

        fragments.map { it.url } shouldBe listOf("https://a.test/v/seg0.ts", "https://a.test/v/seg1.ts")
        fragments.map { it.duration } shouldBe listOf(9.009, 3.0)
    }

    @Test
    fun relativeUrlsResolveAgainstTheBase() {
        val fragments =
            media(
                lines(
                    "#EXTM3U",
                    "#EXTINF:1,",
                    "../up.ts?t=1",
                    "#EXTINF:1,",
                    "//cdn.test/x.ts",
                    "#EXTINF:1,",
                    "https://other.test/z.ts",
                    "#EXT-X-ENDLIST",
                ),
            )

        fragments.map { it.url } shouldBe listOf("https://a.test/up.ts?t=1", "https://cdn.test/x.ts", "https://other.test/z.ts")
    }

    @Test
    fun theInitSegmentComesFirst() {
        val fragments =
            media(lines("#EXTM3U", "#EXT-X-MAP:URI=\"init.mp4\",BYTERANGE=\"720@0\"", "#EXTINF:4,", "s1.m4s", "#EXT-X-ENDLIST"))

        fragments.map { it.url } shouldBe listOf("https://a.test/v/init.mp4", "https://a.test/v/s1.m4s")
        fragments[0].rangeStart shouldBe 0L
        fragments[0].rangeEnd shouldBe 720L
        fragments[0].duration shouldBe null
    }

    @Test
    fun byteRangesChainWhenTheOffsetIsOmitted() {
        val fragments =
            media(
                lines(
                    "#EXTM3U",
                    "#EXTINF:4,",
                    "#EXT-X-BYTERANGE:1000@0",
                    "big.ts",
                    "#EXTINF:4,",
                    "#EXT-X-BYTERANGE:500",
                    "big.ts",
                    "#EXT-X-BYTERANGE:500@5000",
                    "#EXTINF:4,",
                    "big.ts",
                    "#EXT-X-ENDLIST",
                ),
            )

        fragments.map { it.rangeStart to it.rangeEnd } shouldBe listOf(0L to 1000L, 1000L to 1500L, 5000L to 5500L)
    }

    @Test
    fun anAesKeyIsEncrypted() {
        HlsPlaylist
            .parse(lines("#EXTM3U", "#EXT-X-KEY:METHOD=AES-128,URI=\"k.key\"", "#EXTINF:1,", "a.ts", "#EXT-X-ENDLIST"), base)
            .shouldBeInstanceOf<HlsParse.Encrypted>()
            .method shouldBe "AES-128"
    }

    @Test
    fun methodNoneIsNotEncrypted() {
        media(lines("#EXTM3U", "#EXT-X-KEY:METHOD=NONE", "#EXTINF:1,", "a.ts", "#EXT-X-ENDLIST")).size shouldBe 1
    }

    @Test
    fun aPlaylistWithoutEndlistIsLive() {
        HlsPlaylist.parse(lines("#EXTM3U", "#EXTINF:1,", "a.ts"), base) shouldBe HlsParse.Live
    }

    @Test
    fun aMasterPlaylistIsMaster() {
        HlsPlaylist.parse(lines("#EXTM3U", "#EXT-X-STREAM-INF:BANDWIDTH=1000", "low.m3u8"), base) shouldBe HlsParse.Master
    }

    @Test
    fun noExtm3uIsInvalid() {
        HlsPlaylist.parse("<html>nope</html>", base).shouldBeInstanceOf<HlsParse.Invalid>()
        HlsPlaylist.parse("", base).shouldBeInstanceOf<HlsParse.Invalid>()
    }

    @Test
    fun noFragmentsIsInvalid() {
        HlsPlaylist.parse(lines("#EXTM3U", "#EXT-X-ENDLIST"), base).shouldBeInstanceOf<HlsParse.Invalid>().reason shouldBe "no fragments"
    }

    @Test
    fun garbageLinesAndUnknownTagsAreIgnored() {
        val fragments =
            media(
                lines(
                    "#EXTM3U",
                    "",
                    "# a comment",
                    "#EXT-X-UNKNOWN:1",
                    "#EXT-X-DISCONTINUITY",
                    "#EXTINF:1,",
                    "a.ts",
                    "   ",
                    "#EXT-X-ENDLIST",
                ),
            )

        fragments.map { it.url } shouldBe listOf("https://a.test/v/a.ts")
    }

    @Test
    fun crlfIsAccepted() {
        media("#EXTM3U\r\n#EXTINF:1,\r\na.ts\r\n#EXT-X-ENDLIST\r\n").map { it.url } shouldBe listOf("https://a.test/v/a.ts")
    }

    @Test
    fun aByteOrderMarkBeforeTheHeaderIsAccepted() {
        media("#EXTM3U\n#EXTINF:1,\na.ts\n#EXT-X-ENDLIST").size shouldBe 1
    }
}
