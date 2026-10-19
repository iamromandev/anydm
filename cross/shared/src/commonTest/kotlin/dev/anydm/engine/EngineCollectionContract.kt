package dev.anydm.engine

import dev.anydm.engine.core.CollectionKind
import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.DownloadStatus
import dev.anydm.engine.core.ItemCounts
import dev.anydm.engine.core.ItemSelection
import dev.anydm.engine.core.stepRoot
import dev.anydm.engine.media.FakePlaylistSource
import dev.anydm.engine.media.PlaylistEntry
import dev.anydm.engine.media.PlaylistListing
import dev.anydm.engine.media.PlaylistParse
import dev.anydm.engine.protocol.FakeFailure
import dev.anydm.engine.protocol.StepResult
import dev.anydm.engine.queue.CommandResult
import dev.anydm.engine.queue.RemoveFiles
import dev.anydm.engine.store.NewDownload
import dev.anydm.engine.store.StoreTestBase
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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

    // The fake serves one file name for every job: children that are not checked for duplicates can all finish.
    private val runnable = NewDownload(DownloadKind.SITE, allowDuplicate = true)

    private fun AddPlaylistResult.added() = shouldBeInstanceOf<AddPlaylistResult.Added>()

    @Test
    fun addPlaylistCreatesParentAndChildren() =
        runTest {
            val h = started(source(listing()))
            try {
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
            } finally {
                h.engine.stop()
            }
        }

    @Test
    fun rangeAndLimitSelectItems() =
        runTest {
            val h = started(source(listing(count = 6)))
            try {
                val added = h.engine.addPlaylist(playlistUrl, ItemSelection("2:5", 2)).added()

                added.children.map { it.playlistIndex } shouldBe listOf(2, 3)
                added.collection.selection shouldBe ItemSelection("2:5", 2)
            } finally {
                h.engine.stop()
            }
        }

    @Test
    fun aTitleWithDotsStaysInsideTheBaseFolder() =
        runTest {
            val h = started(source(listing(title = "../x")))
            try {
                val added = h.engine.addPlaylist(playlistUrl).added()

                Path(added.collection.saveFolder!!).parent shouldBe stepRoot
            } finally {
                h.engine.stop()
            }
        }

    @Test
    fun theTemplateSaveFolderIsTheBase() =
        runTest {
            val h = started(source(listing()))
            try {
                val added =
                    h.engine
                        .addPlaylist(
                            playlistUrl,
                            template = NewDownload(DownloadKind.SITE, saveFolder = "/media/music"),
                        ).added()

                added.collection.saveFolder shouldBe Path("/media/music", "My List").toString()
            } finally {
                h.engine.stop()
            }
        }

    @Test
    fun anEmptySelectionCreatesNothing() =
        runTest {
            val h = started(source(listing()))
            try {
                h.engine.addPlaylist(playlistUrl, ItemSelection("50:60")) shouldBe AddPlaylistResult.EmptySelection

                h.engine.downloads.value shouldBe emptyList()
            } finally {
                h.engine.stop()
            }
        }

    @Test
    fun aSingleVideoIsNotAPlaylist() =
        runTest {
            val h = started(FakePlaylistSource(StepResult.Ok(PlaylistParse.NotAPlaylist)))
            try {
                h.engine.addPlaylist(playlistUrl) shouldBe AddPlaylistResult.NotAPlaylist
            } finally {
                h.engine.stop()
            }
        }

    @Test
    fun aListingFailureIsReturned() =
        runTest {
            val failure = StepResult.Failed(null, "yt-dlp took too long")
            val h = started(FakePlaylistSource(failure))
            try {
                h.engine.addPlaylist(playlistUrl) shouldBe AddPlaylistResult.Failed(failure)
            } finally {
                h.engine.stop()
            }
        }

    @Test
    fun aRejectedListingIsAFailure() =
        runTest {
            val h = started(FakePlaylistSource(StepResult.Ok(PlaylistParse.Rejected("the playlist has 6000 items; the limit is 5000"))))
            try {
                h.engine
                    .addPlaylist(playlistUrl)
                    .shouldBeInstanceOf<AddPlaylistResult.Failed>()
                    .failure.message shouldBe "the playlist has 6000 items; the limit is 5000"
            } finally {
                h.engine.stop()
            }
        }

    @Test
    fun droppedEntriesArePassedOn() =
        runTest {
            val h = started(source(listing(dropped = 2)))
            try {
                h.engine
                    .addPlaylist(playlistUrl)
                    .added()
                    .dropped shouldBe 2
            } finally {
                h.engine.stop()
            }
        }

    @Test
    fun duplicateChildrenAreSkippedNotFatal() =
        runTest {
            val h = started(source(listing()))
            try {
                h.engine.add(NewDownload(DownloadKind.SITE, mirrors = listOf("https://www.example.com/watch?v=v2")))

                val added = h.engine.addPlaylist(playlistUrl).added()

                added.skipped shouldBe 1
                added.children.map { it.playlistIndex } shouldBe listOf(1, 3)
            } finally {
                h.engine.stop()
            }
        }

    @Test
    fun noSourceIsUnavailable() =
        runTest {
            val h = started(null)
            try {
                h.engine.addPlaylist(playlistUrl) shouldBe AddPlaylistResult.Unavailable
            } finally {
                h.engine.stop()
            }
        }

    @Test
    fun theParentNeverRuns() =
        runTest {
            val h = started(source(listing()))
            try {
                val added = h.engine.addPlaylist(playlistUrl).added()
                settle()

                h.protocol.calls.count { it == "probe" } shouldBe 3
                h.store.downloads
                    .get(added.collection.id)!!
                    .status shouldBe DownloadStatus.PENDING
            } finally {
                h.engine.stop()
            }
        }

    @Test
    fun theSnapshotSumsTheChildren() =
        runTest {
            val h = started(source(listing()))
            try {
                val added = h.engine.addPlaylist(playlistUrl, template = runnable).added()
                settle()

                val snapshot = h.snapshot(added.collection.id)!!
                snapshot.download.status shouldBe DownloadStatus.COMPLETED
                snapshot.downloaded shouldBe 3 * 64L * 1024
                snapshot.total shouldBe 3 * 64L * 1024
                snapshot.items shouldBe ItemCounts(total = 3, pending = 0, active = 0, paused = 0, completed = 3, failed = 0)
                h.snapshot(added.children.first().id)!!.items shouldBe null
            } finally {
                h.engine.stop()
            }
        }

    @Test
    fun aPausedChildMakesTheCollectionPaused() =
        runTest {
            val h = started(source(listing()))
            try {
                val added = h.engine.addPlaylist(playlistUrl, template = runnable).added()
                h.engine.pause(added.children.last().id)

                settle()

                val snapshot = h.snapshot(added.collection.id)!!
                snapshot.download.status shouldBe DownloadStatus.PAUSED
                snapshot.items shouldBe ItemCounts(total = 3, pending = 0, active = 0, paused = 1, completed = 2, failed = 0)
            } finally {
                h.engine.stop()
            }
        }

    @Test
    fun pauseAllAndResumeAllLeaveTheParentAlone() =
        runTest {
            val h = started(source(listing()))
            try {
                val added = h.engine.addPlaylist(playlistUrl, template = runnable).added()

                h.engine.pauseAll()
                h.store.downloads
                    .get(added.collection.id)!!
                    .status shouldBe DownloadStatus.PENDING
                added.children.forEach {
                    h.store.downloads
                        .get(it.id)!!
                        .status shouldBe DownloadStatus.PAUSED
                }
                h.engine.resumeAll()
                settle()

                h.store.downloads
                    .get(added.collection.id)!!
                    .status shouldBe DownloadStatus.PENDING
                h.snapshot(added.collection.id)!!.download.status shouldBe DownloadStatus.COMPLETED
            } finally {
                h.engine.stop()
            }
        }

    private suspend fun TestScope.withEngine(
        playlists: FakePlaylistSource?,
        block: suspend (EngineHarness) -> Unit,
    ) {
        val h = started(playlists)
        try {
            block(h)
        } finally {
            h.engine.stop()
        }
    }

    private suspend fun EngineHarness.stored(id: DownloadId) = store.downloads.get(id)!!.status

    private suspend fun EngineHarness.added(url: String = playlistUrl) = engine.addPlaylist(url, template = runnable).added()

    @Test
    fun pausingTheParentPausesTheChildren() =
        runTest {
            withEngine(source(listing())) { h ->
                val added = h.added()
                runCurrent()

                h.engine.pause(added.collection.id).shouldBeInstanceOf<CommandResult.Done>()

                added.children.forEach { h.stored(it.id) shouldBe DownloadStatus.PAUSED }
                h.stored(added.collection.id) shouldBe DownloadStatus.PENDING
                runCurrent()
                h.snapshot(added.collection.id)!!.download.status shouldBe DownloadStatus.PAUSED
            }
        }

    @Test
    fun resumingTheParentQueuesPausedChildrenAgain() =
        runTest {
            withEngine(source(listing())) { h ->
                val added = h.added()
                h.engine.pause(added.collection.id)

                h.engine.resume(added.collection.id).shouldBeInstanceOf<CommandResult.Done>()
                settle()

                added.children.forEach { h.stored(it.id) shouldBe DownloadStatus.COMPLETED }
            }
        }

    @Test
    fun cancellingTheParentCancelsRunningAndQueuedChildren() =
        runTest {
            withEngine(source(listing())) { h ->
                val added = h.added()
                runCurrent()

                h.engine.cancel(added.collection.id).shouldBeInstanceOf<CommandResult.Done>()

                added.children.forEach { h.stored(it.id) shouldBe DownloadStatus.CANCELLED }
                h.stored(added.collection.id) shouldBe DownloadStatus.PENDING
            }
        }

    @Test
    fun retryingTheParentRequeuesItsFailedChildren() =
        runTest {
            withEngine(source(listing())) { h ->
                h.protocol.failure = FakeFailure.AtProbe(StepResult.Failed(null, "boom"))
                val added = h.added()
                settle()
                added.children.forEach { h.stored(it.id) shouldBe DownloadStatus.FAILED }
                h.protocol.failure = null

                h.engine.retry(added.collection.id).shouldBeInstanceOf<CommandResult.Done>()
                settle()

                added.children.forEach { h.stored(it.id) shouldBe DownloadStatus.COMPLETED }
            }
        }

    @Test
    fun priorityAndSpeedLimitReachEveryChildAndTheParent() =
        runTest {
            withEngine(source(listing())) { h ->
                val added = h.added()
                runCurrent()

                h.engine.reprioritize(added.collection.id, 5)
                h.engine.setSpeedLimit(added.collection.id, 1000)

                for (id in added.children.map { it.id } + added.collection.id) {
                    h.store.downloads
                        .get(id)!!
                        .priority shouldBe 5
                    h.store.downloads
                        .get(id)!!
                        .speedLimit shouldBe 1000
                }
            }
        }

    @Test
    fun removeTrashesEveryChildsFiles() =
        runTest {
            withEngine(source(listing())) { h ->
                val added = h.added()
                settle()
                h.fs.trashAvailable = true

                h.engine.remove(added.collection.id, RemoveFiles.TRASH) shouldBe CommandResult.Done(null)

                h.fs.trashed.size shouldBe 3
                h.store.downloads.list() shouldBe emptyList()
            }
        }

    @Test
    fun removeWithAChildFileAlreadyGoneSucceeds() =
        runTest {
            withEngine(source(listing())) { h ->
                val added = h.added()
                settle()
                h.fs.trashAvailable = true
                val gone =
                    h.store.downloads
                        .files(added.children.first().id)
                        .single()
                        .placedPath!!
                h.fs.remove(Path(added.collection.saveFolder!!, gone))

                h.engine.remove(added.collection.id, RemoveFiles.TRASH) shouldBe CommandResult.Done(null)

                h.fs.trashed.size shouldBe 2
                h.store.downloads.list() shouldBe emptyList()
            }
        }

    @Test
    fun removeKeepLeavesTheFiles() =
        runTest {
            withEngine(source(listing())) { h ->
                val added = h.added()
                settle()
                val before = h.fs.paths()

                h.engine.remove(added.collection.id, RemoveFiles.KEEP) shouldBe CommandResult.Done(null)

                h.fs.paths() shouldBe before
                h.store.downloads.list() shouldBe emptyList()
            }
        }

    @Test
    fun aTrashFailureStopsTheRemove() =
        runTest {
            withEngine(source(listing())) { h ->
                val added = h.added()
                settle()
                h.fs.trashAvailable = false

                h.engine.remove(added.collection.id, RemoveFiles.TRASH) shouldBe CommandResult.TrashUnavailable

                h.store.downloads.get(added.collection.id) shouldNotBe null
                h.store.downloads
                    .children(added.collection.id)
                    .size shouldBe 3
            }
        }

    @Test
    fun finishedChildrenAreMarkedDoneAndListedInThePlaylistFile() =
        runTest {
            withEngine(source(listing())) { h ->
                val added = h.added()
                settle()

                h.store.collectionItems
                    .items(added.collection.id)
                    .map { it.done } shouldBe listOf(true, true, true)
                val file = Path(added.collection.saveFolder!!, "My List.m3u8")
                val lines =
                    h.fs
                        .read(file)!!
                        .decodeToString()
                        .lines()
                lines.first() shouldBe "#EXTM3U"
                lines.count { it.startsWith("#EXTINF:60,Video") } shouldBe 3
                lines.count { it.isNotEmpty() && !it.startsWith("#") } shouldBe 3
            }
        }

    private suspend fun EngineHarness.sync(id: DownloadId) = engine.syncCollection(id)

    private fun SyncOutcome.done() = shouldBeInstanceOf<SyncOutcome.Done>().result

    @Test
    fun syncAddsOnlyNewItems() =
        runTest {
            val playlist = source(listing(count = 3))
            withEngine(playlist) { h ->
                val added = h.added()
                settle()
                playlist.result = StepResult.Ok(PlaylistParse.Ok(listing(count = 4)))

                val result = h.sync(added.collection.id).done()

                result shouldBe SyncResult(added = 1, skipped = 0, unavailable = 0)
                h.store.downloads
                    .children(added.collection.id)
                    .map { it.playlistIndex } shouldBe listOf(1, 2, 3, 4)
                playlist.requested shouldBe listOf(playlistUrl, playlistUrl)
            }
        }

    @Test
    fun aDoneItemIsNotFetchedAgain() =
        runTest {
            withEngine(source(listing())) { h ->
                val added = h.added()
                settle()

                h.sync(added.collection.id).done().added shouldBe 0

                h.store.downloads
                    .children(added.collection.id)
                    .size shouldBe 3
            }
        }

    @Test
    fun aChildStillDownloadingIsNotDuplicated() =
        runTest {
            withEngine(source(listing())) { h ->
                val added = h.added()
                runCurrent()

                h.sync(added.collection.id).done().added shouldBe 0

                h.store.downloads
                    .children(added.collection.id)
                    .size shouldBe 3
            }
        }

    @Test
    fun aRemovedChildThatNeverFinishedIsRecreated() =
        runTest {
            withEngine(source(listing())) { h ->
                val added = h.added()
                h.engine.pause(added.children[1].id)
                h.engine.remove(added.children[1].id, RemoveFiles.KEEP)

                h.sync(added.collection.id).done().added shouldBe 1

                val children = h.store.downloads.children(added.collection.id)
                children.map { it.playlistIndex } shouldBe listOf(1, 2, 3)
                h.store.collectionItems
                    .items(added.collection.id)
                    .map { it.childId } shouldBe children.map { it.id }
            }
        }

    @Test
    fun aFailedChildIsNotDuplicated() =
        runTest {
            withEngine(source(listing())) { h ->
                h.protocol.failure = FakeFailure.AtProbe(StepResult.Failed(null, "boom"))
                val added = h.added()
                settle()

                h.sync(added.collection.id).done().added shouldBe 0

                h.store.downloads
                    .children(added.collection.id)
                    .size shouldBe 3
            }
        }

    @Test
    fun twoSyncsAtOnceCreateOneChild() =
        runTest {
            val playlist = source(listing(count = 3))
            withEngine(playlist) { h ->
                val added = h.added()
                settle()
                playlist.result = StepResult.Ok(PlaylistParse.Ok(listing(count = 4)))

                val both = listOf(async { h.sync(added.collection.id) }, async { h.sync(added.collection.id) }).awaitAll()

                both.map { it.done().added }.sorted() shouldBe listOf(0, 1)
                h.store.downloads
                    .children(added.collection.id)
                    .size shouldBe 4
            }
        }

    @Test
    fun forgettingAnItemMakesTheNextSyncFetchIt() =
        runTest {
            withEngine(source(listing())) { h ->
                val added = h.added()
                settle()

                h.engine.forgetItem(added.collection.id, "youtube", "v2") shouldBe true
                h.engine.forgetItem(added.collection.id, "youtube", "v2") shouldBe false
                h.sync(added.collection.id).done().added shouldBe 1
            }
        }

    @Test
    fun syncOnAJobThatIsNotACollectionSaysSo() =
        runTest {
            withEngine(source(listing())) { h ->
                val loose = h.addJob()

                h.sync(loose) shouldBe SyncOutcome.NotACollection
                h.sync(DownloadId(9999)) shouldBe SyncOutcome.NotACollection
            }
        }

    @Test
    fun theSelectionIsAppliedAgainAtSync() =
        runTest {
            val playlist = source(listing(count = 3))
            withEngine(playlist) { h ->
                val added = h.engine.addPlaylist(playlistUrl, ItemSelection("1:3"), runnable).added()
                settle()
                playlist.result = StepResult.Ok(PlaylistParse.Ok(listing(count = 6)))

                h.sync(added.collection.id).done().added shouldBe 0
            }
        }

    @Test
    fun aListingFailureIsReturnedBySync() =
        runTest {
            val playlist = source(listing())
            withEngine(playlist) { h ->
                val added = h.added()
                val failure = StepResult.Failed(null, "yt-dlp took too long")
                playlist.result = failure

                h.sync(added.collection.id) shouldBe SyncOutcome.Failed(failure)
            }
        }

    @Test
    fun syncWithNoSourceIsUnavailable() =
        runTest {
            val store = newStore()
            val (collection, _) =
                store.downloads.addCollection(
                    NewDownload(DownloadKind.SITE, collection = CollectionKind.PLAYLIST, sourceUrl = playlistUrl),
                    emptyList(),
                    emptyList(),
                )
            val h = EngineHarness(this, store, playlists = null).also { it.engine.start() }
            try {
                h.sync(collection.id) shouldBe SyncOutcome.Unavailable
            } finally {
                h.engine.stop()
            }
        }

    @Test
    fun entriesWithNoAddressAreCountedUnavailable() =
        runTest {
            val playlist = source(listing())
            withEngine(playlist) { h ->
                val added = h.added()
                playlist.result = StepResult.Ok(PlaylistParse.Ok(listing(dropped = 2)))

                h.sync(added.collection.id).done().unavailable shouldBe 2
            }
        }
}
