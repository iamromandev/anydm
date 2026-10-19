package dev.anydm.engine

import dev.anydm.engine.core.CollectionKind
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.DownloadStatus
import dev.anydm.engine.core.ItemSelection
import dev.anydm.engine.core.stepRoot
import dev.anydm.engine.media.FakePlaylistSource
import dev.anydm.engine.media.PlaylistEntry
import dev.anydm.engine.media.PlaylistListing
import dev.anydm.engine.media.PlaylistParse
import dev.anydm.engine.protocol.StepResult
import dev.anydm.engine.store.NewDownload
import dev.anydm.engine.store.StoreTestBase
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes

/** Playlists as collections (MED-16), on a new database from [newDriver] for each test. */
abstract class EngineCollectionContract : StoreTestBase() {
    private val playlistUrl = "https://www.example.com/playlist?list=PL1"

    private fun listing(
        count: Int = 3,
        title: String = "My List",
        kind: CollectionKind = CollectionKind.PLAYLIST,
        dropped: Int = 0,
    ) = PlaylistListing(
        kind,
        title,
        "Chan",
        playlistUrl,
        (1..count).map { PlaylistEntry("youtube", "v$it", "https://www.example.com/watch?v=v$it", "Video $it", 60) },
        dropped,
    )

    private fun source(listing: PlaylistListing) = FakePlaylistSource(StepResult.Ok(PlaylistParse.Ok(listing)))

    private fun TestScope.settle() {
        advanceTimeBy(10.minutes)
        runCurrent()
    }

    private suspend fun TestScope.started(playlists: FakePlaylistSource?): EngineHarness =
        EngineHarness(this, newStore(), playlists = playlists).also { it.engine.start() }

    private fun AddPlaylistResult.added() = shouldBeInstanceOf<AddPlaylistResult.Added>()

    @Test
    fun addPlaylistCreatesParentAndChildren() =
        runTest {
            val h = started(source(listing()))

            val added = h.engine.addPlaylist(playlistUrl).added()

            added.collection.collection shouldBe CollectionKind.PLAYLIST
            added.collection.sourceUrl shouldBe playlistUrl
            added.collection.saveFolder shouldBe Path(stepRoot, "My List").toString()
            added.children.size shouldBe 3
            added.children.map { it.parentId } shouldBe List(3) { added.collection.id }
            added.children.map { it.playlistIndex } shouldBe listOf(1, 2, 3)
            added.children.map { it.saveFolder }.toSet() shouldBe setOf(added.collection.saveFolder)
            added.children.forEachIndexed { i, child ->
                h.store.downloads
                    .mirrors(child.id)
                    .single()
                    .url shouldBe "https://www.example.com/watch?v=v${i + 1}"
            }
            h.engine.downloads.value.size shouldBe 4
            h.engine.stop()
        }

    @Test
    fun rangeAndLimitSelectItems() =
        runTest {
            val h = started(source(listing(count = 6)))

            val added = h.engine.addPlaylist(playlistUrl, ItemSelection("2:5", 2)).added()

            added.children.map { it.playlistIndex } shouldBe listOf(2, 3)
            added.collection.selection shouldBe ItemSelection("2:5", 2)
            h.engine.stop()
        }

    @Test
    fun aTitleWithDotsStaysInsideTheBaseFolder() =
        runTest {
            val h = started(source(listing(title = "../x")))

            val added = h.engine.addPlaylist(playlistUrl).added()

            Path(added.collection.saveFolder!!).parent shouldBe stepRoot
            h.engine.stop()
        }

    @Test
    fun theTemplateSaveFolderIsTheBase() =
        runTest {
            val h = started(source(listing()))

            val added = h.engine.addPlaylist(playlistUrl, template = NewDownload(DownloadKind.SITE, saveFolder = "/media/music")).added()

            added.collection.saveFolder shouldBe Path("/media/music", "My List").toString()
            h.engine.stop()
        }

    @Test
    fun anEmptySelectionCreatesNothing() =
        runTest {
            val h = started(source(listing()))

            h.engine.addPlaylist(playlistUrl, ItemSelection("50:60")) shouldBe AddPlaylistResult.EmptySelection

            h.engine.downloads.value shouldBe emptyList()
            h.engine.stop()
        }

    @Test
    fun aSingleVideoIsNotAPlaylist() =
        runTest {
            val h = started(FakePlaylistSource(StepResult.Ok(PlaylistParse.NotAPlaylist)))

            h.engine.addPlaylist(playlistUrl) shouldBe AddPlaylistResult.NotAPlaylist
            h.engine.stop()
        }

    @Test
    fun aListingFailureIsReturned() =
        runTest {
            val failure = StepResult.Failed(null, "yt-dlp took too long")
            val h = started(FakePlaylistSource(failure))

            h.engine.addPlaylist(playlistUrl) shouldBe AddPlaylistResult.Failed(failure)
            h.engine.stop()
        }

    @Test
    fun aRejectedListingIsAFailure() =
        runTest {
            val h = started(FakePlaylistSource(StepResult.Ok(PlaylistParse.Rejected("the playlist has 6000 items; the limit is 5000"))))

            h.engine
                .addPlaylist(playlistUrl)
                .shouldBeInstanceOf<AddPlaylistResult.Failed>()
                .failure.message shouldBe "the playlist has 6000 items; the limit is 5000"
            h.engine.stop()
        }

    @Test
    fun droppedEntriesArePassedOn() =
        runTest {
            val h = started(source(listing(dropped = 2)))

            h.engine
                .addPlaylist(playlistUrl)
                .added()
                .dropped shouldBe 2
            h.engine.stop()
        }

    @Test
    fun duplicateChildrenAreSkippedNotFatal() =
        runTest {
            val h = started(source(listing()))
            h.engine.add(NewDownload(DownloadKind.SITE, mirrors = listOf("https://www.example.com/watch?v=v2")))

            val added = h.engine.addPlaylist(playlistUrl).added()

            added.skipped shouldBe 1
            added.children.map { it.playlistIndex } shouldBe listOf(1, 3)
            h.engine.stop()
        }

    @Test
    fun noSourceIsUnavailable() =
        runTest {
            val h = started(null)

            h.engine.addPlaylist(playlistUrl) shouldBe AddPlaylistResult.Unavailable
            h.engine.stop()
        }

    @Test
    fun theParentNeverRuns() =
        runTest {
            val h = started(source(listing()))

            val added = h.engine.addPlaylist(playlistUrl).added()
            settle()

            h.protocol.calls.count { it == "probe" } shouldBe 3
            h.status(added.collection.id) shouldBe DownloadStatus.PENDING
            h.engine.stop()
        }
}
