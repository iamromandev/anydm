package dev.anydm.desktop

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.anydm.engine.http.EngineEvent
import dev.anydm.engine.torrent.CANT_RESUME
import dev.anydm.engine.torrent.PieceSet
import dev.anydm.engine.torrent.TorrentResult
import dev.anydm.engine.torrent.readTorrent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class TorrentFiles(
    val guard: ThreadGuard,
) : FilePicker,
    TorrentLoader {
    val bytes = ConcurrentHashMap<Path, ByteArray>()

    @Volatile var next: Path? = null

    override fun pickTorrent(): Path? = next

    override fun pickFolder(current: String): String? = null

    // Like the real loader, the disk is read on an IO thread.
    override suspend fun load(path: Path): TorrentResult =
        withContext(Dispatchers.IO) {
            guard.check()
            bytes[path]?.let(::readTorrent) ?: TorrentResult.Err("Can't read file")
        }

    override suspend fun bytes(path: Path): ByteArray? =
        withContext(Dispatchers.IO) {
            guard.check()
            bytes[path]
        }
}

/** The Home screen with a fake torrent engine: adding a `.torrent`, what each event does to the row, what is saved, and a restart. */
@OptIn(ExperimentalTestApi::class)
class TorrentDownloadsTest {
    private val guard = ThreadGuard()
    private val engine = FakeEngine(guard)
    private val torrents = FakeTorrentEngine(guard)
    private val copies = FakeTorrentFiles(guard)
    private val opener = FakeOpener(guard)
    private val disk = MemoryDiskIo(guard)
    private val downloads = GuardedDownloadsStore(guard)
    private val picked = TorrentFiles(guard)
    private val files = FilesEnv(picked, picked, "/home/user")
    private val folder = "/home/user/Downloads"

    // 100 pieces of 16 KiB
    private val total = 100L * 16_384
    private val bytes = TestTorrents.bytes("Show", listOf("a.bin" to total / 2, "d/b.bin" to total / 2))
    private val meta = TestTorrents.meta(bytes)
    private val hex = meta.infoHashHex
    private val show = Path.of("/t/show.torrent")

    private fun env(store: FakeTorrentFiles = copies) =
        DownloadEnv(engine, FakeProber(guard), opener, disk, torrents = torrents, torrentFiles = store)

    private fun stores() = AppStores(InMemorySettingsStore(), InMemorySecretStore(), 500, downloads)

    private fun ComposeUiTest.home(
        e: DownloadEnv = env(),
        s: AppStores = stores(),
    ) = setContent {
        guard.forbidden.add(Thread.currentThread())
        HomeScreen(emptySample(), true, clipboardText = { null }, files = files, stores = s, downloadEnv = e)
    }

    private fun ComposeUiTest.await(
        what: String,
        cond: () -> Boolean,
    ) {
        val end = System.nanoTime() + 5_000_000_000L
        while (!cond()) {
            check(System.nanoTime() < end) { "timed out waiting for $what" }
            mainClock.advanceTimeByFrame()
            Thread.sleep(5)
        }
    }

    private fun ComposeUiTest.pump(ms: Int = 300) {
        repeat(ms / 10) {
            mainClock.advanceTimeByFrame()
            Thread.sleep(10)
        }
    }

    private fun ComposeUiTest.shown(tag: String) = runCatching { onNodeWithTag(tag).assertIsDisplayed() }.isSuccess

    private fun ComposeUiTest.addTorrent(path: Path = show) {
        picked.next = path
        onNodeWithTag("new-download").performClick()
        onNodeWithTag("open-torrent").performClick()
        waitForIdle()
        onNodeWithTag("download").performClick()
    }

    private fun last() = downloads.saves.last().downloads

    private fun set(vararg i: Int) = PieceSet.of(100, i.toList())

    private val firstTen = set(*IntArray(10) { it })

    private fun doneOf(s: PieceSet) = s.count * 16_384L

    @Test
    fun downloadingATorrentKeepsACopyByInfoHashAndStartsItsJobFromThatCopy() =
        runComposeUiTest {
            picked.bytes[show] = bytes
            home()
            addTorrent()
            await("start") { torrents.starts.isNotEmpty() }
            assertContentEquals(bytes, copies.saved[hex], "the app's copy is the file as it was")
            val job = torrents.starts.single()
            assertEquals(listOf("1", "/app/torrents/$hex.torrent", folder, "Show"), listOf(job.id, job.torrentPath, job.folder, job.name))
            assertNull(job.pieces, "a row that never ran asks for a free name")
            assertEquals(0, engine.starts.size)
            assertTrue(shown("row-1"))
            assertEquals(0, guard.violations)
        }

    @Test
    fun theCopyOutlivesTheOriginalFile() =
        runComposeUiTest {
            picked.bytes[show] = bytes
            home()
            addTorrent()
            await("copy") { copies.saved.containsKey(hex) }
            picked.bytes.remove(show) // the user moved or deleted the file
            onNodeWithTag("pause-1").performClick()
            onNodeWithTag("pause-1").performClick()
            await("the resume") { torrents.starts.size == 2 }
            assertEquals("/app/torrents/$hex.torrent", torrents.starts.last().torrentPath)
            assertTrue(copies.saved.containsKey(hex))
        }

    @Test
    fun addingTheSameTorrentAgainKeepsOneRowSelectsItAndCopiesNothingNew() =
        runComposeUiTest {
            picked.bytes[show] = bytes
            home()
            addTorrent()
            await("start") { torrents.starts.isNotEmpty() }
            val saved = copies.saved.size
            addTorrent()
            pump()
            assertTrue(!shown("row-2"), "no second row")
            onNodeWithTag("select-1").assertIsOn()
            assertEquals(saved, copies.saved.size)
            assertEquals(1, torrents.starts.size)
            assertEquals(listOf("1"), last().map { it.id })
        }

    @Test
    fun aCopyThatCannotBeWrittenAddsNoRowAndSaysSo() =
        runComposeUiTest {
            picked.bytes[show] = bytes
            copies.failSave = true
            home()
            addTorrent()
            await("the notice") { shown("notice") }
            assertTrue(!shown("row-1"))
            assertEquals(emptyList(), torrents.starts.toList())
        }

    @Test
    fun aFileThatChangedAfterItWasShownIsNotAdded() =
        runComposeUiTest {
            picked.bytes[show] = bytes
            home()
            picked.next = show
            onNodeWithTag("new-download").performClick()
            onNodeWithTag("open-torrent").performClick()
            waitForIdle()
            picked.bytes[show] = TestTorrents.bytes("Other", listOf("x" to 5L)) // another torrent under the same name
            onNodeWithTag("download").performClick()
            await("the notice") { shown("notice") }
            assertTrue(!shown("row-1"))
            assertTrue(copies.saved.isEmpty())
        }

    @Test
    fun removingARowDeletesItsCopyAndStopsAndForgetsItsJob() =
        runComposeUiTest {
            picked.bytes[show] = bytes
            home()
            addTorrent()
            await("start") { torrents.starts.isNotEmpty() }
            onNodeWithTag("select-1").performClick()
            onNodeWithTag("bar-remove").performClick()
            onNodeWithTag("remove-confirm").performClick()
            await("the copy goes") { copies.deleted.isNotEmpty() }
            assertEquals(listOf(hex), copies.deleted.toList())
            assertTrue("1" in torrents.stops)
            assertTrue("forget:1" in torrents.log)
            assertEquals(emptyList(), opener.trashed.toList(), "no files without the box ticked")
        }

    @Test
    fun removingARowThatRanWithItsFilesTrashesItsFolderAndItsPartFileButARowThatNeverRanLeavesTheUsersFilesAlone() =
        runComposeUiTest {
            picked.bytes[show] = bytes
            home()
            addTorrent()
            await("start") { torrents.starts.isNotEmpty() }
            // never ran: its name is the torrent's, which may be the user's own folder, so nothing is trashed
            onNodeWithTag("select-1").performClick()
            onNodeWithTag("bar-remove").performClick()
            onNodeWithTag("remove-delete").performClick()
            onNodeWithTag("remove-confirm").performClick()
            await("the copy goes") { copies.deleted.isNotEmpty() }
            pump()
            assertEquals(emptyList(), opener.trashed.toList())
        }

    /** A running torrent that was told its folder is "Show (2)", removed with "also delete the files". */
    private fun ComposeUiTest.removeRunningShowWithFiles(): Path {
        picked.bytes[show] = bytes
        home()
        addTorrent()
        await("start") { torrents.starts.isNotEmpty() }
        torrents.emit(EngineEvent.Started("1", "Show (2)", total, null, null, 0, pieces = set()))
        await("the name") { downloads.saves.isNotEmpty() && last().singleOrNull()?.name == "Show (2)" }
        return Path.of("$folder/Show (2)")
    }

    private fun ComposeUiTest.removeWithFiles() {
        onNodeWithTag("select-1").performClick()
        onNodeWithTag("bar-remove").performClick()
        onNodeWithTag("remove-delete").performClick()
        onNodeWithTag("remove-confirm").performClick()
    }

    @Test
    fun aRunningTorrentRemovedWithItsFilesTrashesItsFolderWhenItHoldsNothingElse() =
        runComposeUiTest {
            val root = removeRunningShowWithFiles()
            disk.put("$root/a.bin.part", 5)
            disk.put("$root/d/b.bin.part", 5)
            removeWithFiles()
            await("trash") { opener.trashed.isNotEmpty() }
            pump()
            assertEquals(listOf(root), opener.trashed.toList())
        }

    @Test
    fun aRunningTorrentRemovedWithItsFilesLeavesAForeignFileInItsFolderAndTrashesOnlyItsOwn() =
        runComposeUiTest {
            val root = removeRunningShowWithFiles()
            disk.put("$root/a.bin.part", 5)
            disk.put("$root/d/b.bin.part", 5)
            disk.put("$root/notes.txt", 5)
            removeWithFiles()
            await("trash") { opener.trashed.size == 2 }
            pump()
            assertEquals(setOf(root.resolve("a.bin.part"), root.resolve("d/b.bin.part")), opener.trashed.toSet())
            assertTrue(disk.files.containsKey(root.resolve("notes.txt")))
        }

    @Test
    fun aFinishedThatArrivesAfterTheRowWasRemovedWithItsFilesDoesNotTrashTheWholeFolderLater() =
        runComposeUiTest {
            val root = removeRunningShowWithFiles()
            disk.put("$root/a.bin.part", 5)
            disk.put("$root/d/b.bin.part", 5)
            disk.put("$root/notes.txt", 5)
            removeWithFiles()
            await("trash") { opener.trashed.size == 2 }
            torrents.emit(EngineEvent.Finished("1", total)) // the job beat the cancel
            pump()
            assertEquals(2, opener.trashed.size, "nothing more: the folder holds a file of the user's")
            assertTrue(root !in opener.trashed)
        }

    // What an engine that claimed pieces before it started would have said: the row must not take it for a run that settled its name.
    @Test
    fun aRowThatFailedBeforeItEverStartedDeletesNothingEvenWithAnEmptyClaimFromTheEngine() =
        runComposeUiTest {
            picked.bytes[show] = bytes
            home()
            addTorrent()
            await("start") { torrents.starts.isNotEmpty() }
            // the user's own folder of the torrent's name, with a file of just the size of one of the torrent's files
            disk.put("$folder/Show/a.bin", (total / 2).toInt())
            torrents.emit(EngineEvent.Failed("1", "Disk full", 0, pieces = set()))
            await("failed") { runCatching { onNodeWithTag("speed-1").assertTextEquals("Disk full") }.isSuccess }
            removeWithFiles()
            await("the copy goes") { copies.deleted.isNotEmpty() }
            pump()
            assertEquals(emptyList(), opener.trashed.toList())
            assertEquals(emptyList(), disk.deleted.toList())
            assertTrue(disk.files.containsKey(Path.of("$folder/Show/a.bin")), "the user's file")
        }

    @Test
    fun aRowPausedBeforeItsStartedWasSeenIsNotTakenForOneWhoseNameIsSettled() =
        runComposeUiTest {
            picked.bytes[show] = bytes
            home()
            addTorrent()
            await("start") { torrents.starts.isNotEmpty() }
            onNodeWithTag("pause-1").performClick()
            await("stop") { "1" in torrents.stops }
            // the engine had started: Started came after the pause (ignored by a row that is not running), then Stopped
            torrents.emit(EngineEvent.Started("1", "Show (1)", total, null, null, 0, pieces = set()))
            torrents.emit(EngineEvent.Stopped("1", 0, pieces = set()))
            pump()
            assertNull(last().single().pieces, "no claim: the next start picks a free name again")
        }

    @Test
    fun theEventsMoveTheRowAndTheRowIsSavedWithItsInfoHashAndPieces() =
        runComposeUiTest {
            picked.bytes[show] = bytes
            home()
            addTorrent()
            await("start") { torrents.starts.isNotEmpty() }
            torrents.emit(EngineEvent.Started("1", "Show", total, null, null, 0, pieces = set()))
            torrents.emit(
                EngineEvent.Progress(
                    "1",
                    doneOf(firstTen),
                    total,
                    2L * 1024 * 1024,
                    pieces = firstTen,
                    inFlight = setOf(10, 11),
                    peers = 7,
                ),
            )
            await("speed") { runCatching { onNodeWithTag("speed-1").assertTextEquals("2.0 MB/s") }.isSuccess }
            onNodeWithTag("pause-1").performClick()
            await("the paused save") { downloads.saves.isNotEmpty() && last().singleOrNull()?.status == DownloadStatus.Paused }
            val row = last().single()
            assertEquals(
                listOf<Any?>(hex, firstTen.toBase64(), 100, DownloadKind.Torrent),
                listOf(row.infoHash, row.pieces, row.pieceCount, row.kind),
            )
            await("stop") { "1" in torrents.stops }
        }

    @Test
    fun theStoppedEventsPiecesAreSavedAtOnce() =
        runComposeUiTest {
            picked.bytes[show] = bytes
            home()
            addTorrent()
            await("start") { torrents.starts.isNotEmpty() }
            torrents.emit(EngineEvent.Started("1", "Show", total, null, null, 0, pieces = set()))
            onNodeWithTag("pause-1").performClick()
            await("stop") { "1" in torrents.stops }
            val more = set(*IntArray(20) { it })
            torrents.emit(EngineEvent.Stopped("1", doneOf(more), pieces = more))
            await("the save with the pieces") { last().single().pieces == more.toBase64() }
            assertEquals(doneOf(more), last().single().done)
        }

    /** One session that ran a torrent to ten pieces and paused it, then (after the block) a new one on the same stores. */
    private fun pausedWithTenPieces() =
        runComposeUiTest {
            picked.bytes[show] = bytes
            home()
            addTorrent()
            await("start") { torrents.starts.isNotEmpty() }
            torrents.emit(EngineEvent.Started("1", "Show", total, null, null, 0, pieces = set()))
            torrents.emit(EngineEvent.Progress("1", doneOf(firstTen), total, 2L * 1024 * 1024, pieces = firstTen, peers = 3))
            await("progress") { runCatching { onNodeWithTag("speed-1").assertTextEquals("2.0 MB/s") }.isSuccess }
            onNodeWithTag("pause-1").performClick()
            await("stop") { "1" in torrents.stops }
            torrents.emit(EngineEvent.Stopped("1", doneOf(firstTen), pieces = firstTen))
            await("the save") { downloads.saves.isNotEmpty() && last().single().pieces == firstTen.toBase64() }
        }

    @Test
    fun aTorrentPausedAtTenPiecesComesBackPausedWithThemAndResumesFromThem() {
        pausedWithTenPieces()
        val again = FakeTorrentEngine(guard)
        runComposeUiTest {
            home(DownloadEnv(FakeEngine(guard), FakeProber(guard), opener, disk, torrents = again, torrentFiles = copies))
            await("the restored row") { shown("row-1") }
            onNodeWithTag("speed-1").assertTextEquals("Paused")
            Thread.sleep(150)
            assertTrue(again.starts.isEmpty(), "a restored row must not start by itself")
            onNodeWithTag("pause-1").performClick()
            await("the resume") { again.starts.isNotEmpty() }
            val job = again.starts.single()
            assertEquals(firstTen, job.pieces, "the stored claim goes to the engine, which checks it before it trusts it")
            assertEquals(doneOf(firstTen), job.doneBytes)
            assertEquals("Show", job.name)
        }
    }

    @Test
    fun theRecheckCountingFromZeroNeitherLowersTheRowNorWhatIsSavedAndStartedSaysWhatHeld() {
        pausedWithTenPieces()
        val again = FakeTorrentEngine(guard)
        runComposeUiTest {
            val s = stores()
            home(DownloadEnv(FakeEngine(guard), FakeProber(guard), opener, disk, torrents = again, torrentFiles = copies), s)
            await("the restored row") { shown("row-1") }
            onNodeWithTag("pause-1").performClick()
            await("the resume") { again.starts.isNotEmpty() }
            val saves = downloads.saves.size
            again.emit(EngineEvent.Progress("1", 0, total, 0, connections = 0))
            again.emit(EngineEvent.Progress("1", 3L * 16_384, total, 0, connections = 0))
            pump(1_500)
            s.flush()
            assertEquals(firstTen.toBase64(), last().single().pieces, "the claim is not lowered or dropped")
            assertEquals(doneOf(firstTen), last().single().done, "what is saved as done is not lowered")
            // the check ends: seven of the ten pieces held
            val held = set(*IntArray(7) { it })
            again.emit(EngineEvent.Started("1", "Show", total, null, null, doneOf(held), pieces = held))
            await("the lower claim is saved") { last().single().pieces == held.toBase64() }
            assertEquals(doneOf(held), last().single().done)
            assertTrue(downloads.saves.size > saves)
        }
    }

    @Test
    fun aFailedTorrentKeepsItsPiecesAndRetryStartsItAgainFromThem() =
        runComposeUiTest {
            picked.bytes[show] = bytes
            home()
            addTorrent()
            await("start") { torrents.starts.isNotEmpty() }
            torrents.emit(EngineEvent.Started("1", "Show", total, null, null, 0, pieces = set()))
            torrents.emit(EngineEvent.Failed("1", "No peers", doneOf(firstTen), pieces = firstTen))
            await("failed") { runCatching { onNodeWithTag("speed-1").assertTextEquals("No peers") }.isSuccess }
            onNodeWithTag("pause-1").performClick()
            await("the retry") { torrents.starts.size == 2 }
            assertEquals(firstTen, torrents.starts.last().pieces)
        }

    @Test
    fun aFinishedTorrentIsSavedFinishedWithItsInfoHashAndEveryPiece() =
        runComposeUiTest {
            picked.bytes[show] = bytes
            home()
            addTorrent()
            await("start") { torrents.starts.isNotEmpty() }
            torrents.emit(EngineEvent.Started("1", "Show", total, null, null, 0, pieces = set()))
            torrents.emit(EngineEvent.Finished("1", total))
            await("the finished save") { downloads.saves.isNotEmpty() && last().single().status == DownloadStatus.Finished }
            val row = last().single()
            assertEquals(listOf<Any?>(hex, PieceSet.of(100, 0 until 100).toBase64()), listOf(row.infoHash, row.pieces))
        }

    @Test
    fun theCopyOfATorrentAddedAgainWhileItsOldRowWasBeingDiscardedIsNotDeleted() =
        runComposeUiTest {
            picked.bytes[show] = bytes
            home()
            addTorrent()
            await("start") { torrents.starts.isNotEmpty() }
            val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
            torrents.joinGate = gate // the old job is slow to wind down: the discard waits for it
            onNodeWithTag("select-1").performClick()
            onNodeWithTag("bar-remove").performClick()
            onNodeWithTag("remove-confirm").performClick()
            await("the discard waits") { "join:1" in torrents.log }
            addTorrent() // the same torrent again, meanwhile: a new row and a new copy
            await("the second row") { shown("row-2") }
            gate.complete(Unit)
            await("the discard ends") { "forget:1" in torrents.log }
            pump()
            assertEquals(emptyList(), copies.deleted.toList(), "the new row runs from that copy")
            assertTrue(copies.saved.containsKey(hex))
        }

    @Test
    fun aRemovedRowsIdIsNotGivenToTheNextRowSoItsLateEventsNeverReachIt() =
        runComposeUiTest {
            picked.bytes[show] = bytes
            home()
            addTorrent()
            await("start") { torrents.starts.isNotEmpty() }
            val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
            torrents.joinGate = gate
            onNodeWithTag("select-1").performClick()
            onNodeWithTag("bar-remove").performClick()
            onNodeWithTag("remove-confirm").performClick()
            await("the discard waits") { "join:1" in torrents.log }
            addTorrent()
            await("the second row") { shown("row-2") || shown("row-1") }
            assertTrue(shown("row-2") && !shown("row-1"), "the new row has a new id")
            // the old job's last words, arriving when the new row exists
            torrents.emit(EngineEvent.Failed("1", "Network error", 0, pieces = set()))
            gate.complete(Unit)
            await("the new job starts") { torrents.starts.size == 2 }
            pump()
            assertEquals("2", torrents.starts.last().id)
            assertTrue(!runCatching { onNodeWithTag("speed-2").assertTextEquals("Network error") }.isSuccess, "row 2 did not fail")
        }

    private fun magnetSaved(): DownloadsFile {
        var saved: DownloadsFile? = null
        runComposeUiTest {
            home()
            onNodeWithTag("new-download").performClick()
            onNodeWithTag("url-field").performTextInput("magnet:?xt=urn:btih:08ada5a7a6183aae1e09d8")
            onNodeWithTag("download").performClick()
            await("the row is saved") { downloads.saves.isNotEmpty() && last().singleOrNull()?.kind == DownloadKind.Torrent }
            saved = downloads.saves.last()
        }
        return checkNotNull(saved)
    }

    @Test
    fun aMagnetRowOfAnEarlierSessionStillPausesAndResumesAsTheSimulationItIs() {
        val saved = magnetSaved()
        assertEquals(true, saved.downloads.single().sim, "the row is saved as simulated")
        runComposeUiTest {
            val s =
                AppStores(InMemorySettingsStore(), InMemorySecretStore(), 500, GuardedDownloadsStore(guard, InMemoryDownloadsStore(saved)))
            home(s = s)
            await("the row") { shown("row-1") }
            onNodeWithTag("pause-1").performClick()
            pump()
            assertTrue(
                !runCatching { onNodeWithTag("speed-1").assertTextEquals(CANT_RESUME) }.isSuccess,
                "a magnet row made by this build does not fail with Can't resume",
            )
            assertEquals(emptyList(), torrents.starts.toList())
        }
    }

    // AC20: rows written before torrents were real
    private val legacy =
        """
        {"version":2,"downloads":[
          {"id":"1","name":"Old show","total":100,"done":40,"status":"Paused","kind":"Torrent","files":[{"name":"a","bytes":100}]},
          {"id":"2","name":"Sintel","total":100,"done":0,"status":"Downloading","kind":"Torrent","files":[{"name":"b","bytes":100}]},
          {"id":"3","name":"a.zip","url":"https://x.test/a.zip","total":100,"done":0,"status":"Paused"}
        ]}
        """.trimIndent()

    @Test
    fun aRowFromBeforeRealTorrentsLoadsAndWhenResumedFailsWithCantResumeAndReachesNoEngine() =
        runComposeUiTest {
            val old = downloadsJson.decodeFromString(DownloadsFile.serializer(), legacy)
            val s =
                AppStores(InMemorySettingsStore(), InMemorySecretStore(), 500, GuardedDownloadsStore(guard, InMemoryDownloadsStore(old)))
            home(s = s)
            await("the rows") { shown("row-1") && shown("row-2") && shown("row-3") }
            onNodeWithTag("speed-1").assertTextEquals("Paused")
            onNodeWithTag("pause-1").performClick()
            await("the failure") { runCatching { onNodeWithTag("speed-1").assertTextEquals(CANT_RESUME) }.isSuccess }
            onNodeWithTag("pause-3").performClick() // the http row still resumes
            await("the http start") { engine.starts.size == 1 }
            pump()
            assertEquals(emptyList(), torrents.starts.toList())
            assertEquals(0, guard.violations)
        }
}
