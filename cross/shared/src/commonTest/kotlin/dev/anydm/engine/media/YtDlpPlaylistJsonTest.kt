package dev.anydm.engine.media

import dev.anydm.engine.core.CollectionKind
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test

class YtDlpPlaylistJsonTest {
    private val source = "https://www.youtube.com/playlist?list=PL1"

    private fun ok(json: String) = YtDlpJson.parsePlaylist(json, source).shouldBeInstanceOf<PlaylistParse.Ok>().listing

    @Test
    fun playlistEntriesAreRead() {
        val listing = ok(MediaFixtures.flatPlaylistJson())

        listing.kind shouldBe CollectionKind.PLAYLIST
        listing.title shouldBe "My List"
        listing.uploader shouldBe "Chan"
        listing.sourceUrl shouldBe source
        listing.entries shouldBe
            listOf(
                PlaylistEntry("youtube", "a1", "https://www.youtube.com/watch?v=a1", "One", 61, 1),
                PlaylistEntry("youtube", "a2", "https://www.youtube.com/watch?v=a2", "Two", null, 2),
                PlaylistEntry("youtubetab", "a4", "https://www.youtube.com/watch?v=a4", "Four", 125, 7),
            )
    }

    @Test
    fun entriesWithoutIdOrAddressAreCounted() {
        // private (no address), the repeat of a1, the null, and the file:// address
        ok(MediaFixtures.flatPlaylistJson()).dropped shouldBe 4
    }

    @Test
    fun duplicatesKeepTheFirst() {
        ok(MediaFixtures.flatPlaylistJson()).entries.filter { it.id == "a1" }.map { it.title } shouldBe listOf("One")
    }

    @Test
    fun aSingleVideoIsNotAPlaylist() {
        YtDlpJson.parsePlaylist(MediaFixtures.splitVideoJson(), source) shouldBe PlaylistParse.NotAPlaylist
    }

    @Test
    fun channelIsRecognised() {
        for (page in listOf(
            "https://www.youtube.com/@chan/videos",
            "https://www.youtube.com/channel/UC1",
            "https://www.youtube.com/c/Chan",
            "https://www.youtube.com/user/chan",
        )) {
            ok(MediaFixtures.flatPlaylistJson(page)).kind shouldBe CollectionKind.CHANNEL
        }
        ok(MediaFixtures.flatPlaylistJson("https://www.youtube.com/playlist?list=PL1")).kind shouldBe CollectionKind.PLAYLIST
    }

    @Test
    fun aListingBoundedByTheCapIsRead() {
        ok(MediaFixtures.hugePlaylistJson(5001)).entries.size shouldBe 5001
    }

    @Test
    fun aListingFarOverTheCapIsRefused() {
        val rejected = YtDlpJson.parsePlaylist(MediaFixtures.hugePlaylistJson(5002), source).shouldBeInstanceOf<PlaylistParse.Rejected>()

        rejected.reason shouldContain "5002"
    }

    @Test
    fun positionsCountEveryListedEntry() {
        ok(MediaFixtures.flatPlaylistJson()).entries.map { it.position } shouldBe listOf(1, 2, 7)
    }

    @Test
    fun aChannelsTabsAreNotVideos() {
        val rejected = YtDlpJson.parsePlaylist(MediaFixtures.channelTabsJson(), source).shouldBeInstanceOf<PlaylistParse.Rejected>()

        rejected.reason shouldContain "other playlists"
        rejected.reason shouldContain "/videos"
    }

    @Test
    fun tabsBesideARealVideoAreDropped() {
        val listing = ok(MediaFixtures.channelTabsJson(withVideo = true))

        listing.entries.map { it.id } shouldBe listOf("v9")
        listing.dropped shouldBe 2
        listing.entries.single().position shouldBe 3
    }

    @Test
    fun aBareIdInUrlFallsBackToTheWebpageAddress() {
        ok(MediaFixtures.bareUrlPlaylistJson()).entries.single().url shouldBe "https://soundcloud.com/a/s1"
    }

    @Test
    fun aMissingTitleIsRejected() {
        YtDlpJson.parsePlaylist("""{"_type":"playlist","entries":[]}""", source).shouldBeInstanceOf<PlaylistParse.Rejected>()
    }

    @Test
    fun garbageIsRejected() {
        YtDlpJson.parsePlaylist("not json", source).shouldBeInstanceOf<PlaylistParse.Rejected>().reason shouldContain "JSON"
    }
}
