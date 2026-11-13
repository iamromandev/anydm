package dev.anydm.desktop

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.anydm.engine.http.EngineEvent
import dev.anydm.engine.http.Span
import dev.anydm.engine.http.TRAILER_BYTES
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** An in-memory downloads store that counts calls from the UI thread (the guard's forbidden threads). */
class GuardedDownloadsStore(
    private val guard: ThreadGuard,
    private val inner: InMemoryDownloadsStore = InMemoryDownloadsStore(),
) : DownloadsStore {
    val saves: CopyOnWriteArrayList<DownloadsFile> get() = inner.saves

    override fun load(): DownloadsLoad {
        guard.check()
        return inner.load()
    }

    override fun save(file: DownloadsFile): SaveResult {
        guard.check()
        return inner.save(file)
    }
}

/** The Home screen restarted on the same stores and the same (fake) folder: AC13, AC14 and the thread rule for the downloads file. */
@OptIn(ExperimentalTestApi::class)
class DownloadsPersistenceTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val guard = ThreadGuard()
    private val disk = MemoryDiskIo(guard)
    private val downloads = GuardedDownloadsStore(guard)
    private val folder = "/home/user/Downloads"
    private val part = Path.of("$folder/a.zip.part")

    private fun stores(store: DownloadsStore = downloads) = AppStores(InMemorySettingsStore(), InMemorySecretStore(), 500, store)

    /** One app session: its own engine, the shared disk. */
    private inner class Session(
        val stores: AppStores = stores(),
    ) {
        val engine = FakeEngine(guard)
        val env = DownloadEnv(engine, FakeProber(guard), FakeOpener(guard), disk)
    }

    private fun ComposeUiTest.home(s: Session) =
        setContent {
            guard.forbidden.add(Thread.currentThread())
            HomeScreen(emptySample(), true, clipboardText = { null }, stores = s.stores, downloadEnv = s.env)
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

    private fun ComposeUiTest.add(url: String) {
        onNodeWithTag("new-download").performClick()
        onNodeWithTag("url-field").performTextInput(url)
        onNodeWithTag("download").performClick()
    }

    private fun ComposeUiTest.shown(tag: String) = runCatching { onNodeWithTag(tag).assertIsDisplayed() }.isSuccess

    private fun last() = downloads.saves.last().downloads

    /** A run that pauses a download at 40% (the engine reported 400 of 1000; the file has 400). */
    private fun pausedAtFortyPercent() =
        runComposeUiTest {
            val s = Session()
            home(s)
            add("https://x.test/a.zip")
            await("start") { s.engine.starts.isNotEmpty() }
            s.engine.emit(EngineEvent.Started("1", "a.zip", 1000, "\"e\"", null, 0))
            s.engine.emit(EngineEvent.Progress("1", 400, 1000, 2L * 1024 * 1024))
            disk.put(part.toString(), 400)
            await("progress") { runCatching { onNodeWithTag("speed-1").assertTextEquals("2.0 MB/s") }.isSuccess }
            onNodeWithTag("pause-1").performClick()
            await("the paused save") { downloads.saves.isNotEmpty() && last().singleOrNull()?.status == DownloadStatus.Paused }
            assertEquals(400L, last().single().done)
        }

    @Test
    fun aDownloadPausedAtFortyPercentComesBackPausedWithTheSizeOfThePartFileAndResumesFromIt() {
        pausedAtFortyPercent()
        // The file grew a little after the last save: the part file is the truth, not the stored number.
        disk.put(part.toString(), 410)
        runComposeUiTest {
            val s = Session()
            home(s)
            await("the restored row") { shown("row-1") }
            onNodeWithTag("speed-1").assertTextEquals("Paused")
            onNodeWithText("41% · 410 B of 1000 B").assertIsDisplayed()
            Thread.sleep(150)
            assertTrue(s.engine.starts.isEmpty(), "a restored row must not start by itself")
            onNodeWithTag("pause-1").performClick()
            await("the resume") { s.engine.starts.isNotEmpty() }
            val job = s.engine.starts.single()
            assertEquals(410L, job.done)
            assertEquals("\"e\"", job.etag)
            assertEquals("https://x.test/a.zip", job.url)
            s.engine.emit(EngineEvent.Finished("1", 1000))
            await("the finished save") { last().single().status == DownloadStatus.Finished }
        }
        assertEquals(0, guard.violations)
    }

    @Test
    fun downloadingAndQueuedRowsComeBackPausedAndNothingStarts() {
        runComposeUiTest {
            val s = Session()
            home(s)
            onNodeWithTag("network-chip").performClick()
            onNodeWithTag("networks-limits-link").performClick()
            repeat(2) { onNodeWithTag("concurrency-minus").performClick() }
            onNodeWithTag("limits-save").performClick()
            onNodeWithTag("settings-back").performClick()
            add("https://x.test/a.zip")
            add("https://x.test/b.zip")
            await("start") { s.engine.starts.size == 1 }
            await("the save") { downloads.saves.isNotEmpty() && last().size == 2 }
            assertEquals(listOf(DownloadStatus.Downloading, DownloadStatus.Queued), last().map { it.status })
        }
        runComposeUiTest {
            val s = Session()
            home(s)
            await("the rows") { shown("row-1") && shown("row-2") }
            onNodeWithTag("speed-1").assertTextEquals("Paused")
            onNodeWithTag("speed-2").assertTextEquals("Paused")
            Thread.sleep(150)
            assertTrue(s.engine.starts.isEmpty())
        }
    }

    @Test
    fun aMissingPartFileRestartsFromZeroWhenResumed() {
        pausedAtFortyPercent()
        disk.files.remove(part)
        runComposeUiTest {
            val s = Session()
            home(s)
            await("the restored row") { shown("row-1") }
            onNodeWithText("0% · 0 B of 1000 B").assertIsDisplayed()
            onNodeWithTag("pause-1").performClick()
            await("the resume") { s.engine.starts.isNotEmpty() }
            assertEquals(
                0L,
                s.engine.starts
                    .single()
                    .done,
            )
        }
    }

    @Test
    fun aFinishedRowStaysFinishedWhenItsFileIsMissing() {
        runComposeUiTest {
            val s = Session()
            home(s)
            add("https://x.test/a.zip")
            await("start") { s.engine.starts.isNotEmpty() }
            s.engine.emit(EngineEvent.Finished("1", 100))
            await("the finished save") { downloads.saves.isNotEmpty() && last().singleOrNull()?.status == DownloadStatus.Finished }
        }
        runComposeUiTest {
            val s = Session()
            home(s)
            await("the restored row") { shown("row-1") }
            onNodeWithTag("row-1").assertIsDisplayed()
            assertEquals(DownloadStatus.Finished, last().single().status)
            assertTrue(disk.files.isEmpty())
        }
    }

    @Test
    fun theExitFlushWritesAChangeThatIsStillWaitingForItsDebounce() =
        runComposeUiTest {
            val s = Session()
            home(s)
            add("https://x.test/a.zip")
            await("start") { s.engine.starts.isNotEmpty() }
            s.stores.flush()
            assertEquals(listOf("a.zip"), last().map { it.name })
        }

    @Test
    fun aChangeOfBytesDoneAloneIsNotSavedButTheNextRealChangeCarriesTheLatestOnes() =
        runComposeUiTest {
            val s = Session()
            home(s)
            add("https://x.test/a.zip")
            await("start") { s.engine.starts.isNotEmpty() }
            s.engine.emit(EngineEvent.Started("1", "a.zip", 1000, null, null, 0))
            await("the save of the start") { downloads.saves.isNotEmpty() && last().single().total == 1000L }
            val before = downloads.saves.size
            repeat(40) {
                s.engine.emit(EngineEvent.Progress("1", 10L + it, 1000, 2L * 1024 * 1024))
                mainClock.advanceTimeBy(20)
            }
            await("the last progress") { runCatching { onNodeWithTag("speed-1").assertTextEquals("2.0 MB/s") }.isSuccess }
            mainClock.advanceTimeBy(2_500) // more than the save interval: a progress save would have happened by now
            Thread.sleep(100)
            assertEquals(before, downloads.saves.size, "bytes done alone must not rewrite the file")
            onNodeWithTag("pause-1").performClick()
            await("the paused save") { last().single().status == DownloadStatus.Paused }
            assertEquals(49L, last().single().done)
        }

    @Test
    fun aCorruptFileBecomesBadTheListIsEmptyAndTheNoticeShowsOnce() {
        val dir = tmp.root.toPath().resolve("anydm")
        Files.createDirectories(dir)
        dir.resolve("downloads.json").writeText("{ not json")
        runComposeUiTest {
            val s = Session(stores(FileDownloadsStore(dir)))
            home(s)
            await("the notice") { shown("notice") }
            onNodeWithTag("notice").assertTextEquals("Downloads couldn't be read and were reset")
            onNodeWithTag("row-1").assertDoesNotExist()
            assertTrue(dir.resolve("downloads.json.bad").exists())
            assertTrue(!dir.resolve("downloads.json").exists())
        }
        runComposeUiTest {
            val s = Session(stores(FileDownloadsStore(dir)))
            home(s)
            Thread.sleep(200)
            mainClock.advanceTimeBy(500)
            assertTrue(!shown("notice"))
        }
    }

    @Test
    fun noDownloadsFileCallRunsOnTheUiThread() {
        pausedAtFortyPercent()
        runComposeUiTest {
            val s = Session()
            home(s)
            await("the restored row") { shown("row-1") }
            s.stores.flush()
        }
        assertEquals(0, guard.violations)
    }

    private val halves = listOf(Span(0, 499, 0), Span(500, 999, 0))

    private fun ranges(spans: List<Span>) = spans.map { RangeDto(it.start, it.end, it.done) }

    /** Waits on the real clock only: the virtual one stays where the test put it. */
    private fun pollReal(
        what: String,
        cond: () -> Boolean,
    ) {
        val end = System.nanoTime() + 3_000_000_000L
        while (!cond()) {
            check(System.nanoTime() < end) { "timed out waiting for $what" }
            Thread.sleep(5)
        }
    }

    private fun ComposeUiTest.startSegmented(s: Session) {
        add("https://x.test/a.zip")
        await("start") { s.engine.starts.isNotEmpty() }
        s.engine.emit(EngineEvent.Started("1", "a.zip", 1000, "\"e\"", null, 0, halves, "AA"))
    }

    @Test
    fun theSpansOfAStartedSegmentedJobAreSavedWithinTheStateInterval() =
        runComposeUiTest {
            val s = Session()
            home(s)
            startSegmented(s)
            mainClock.advanceTimeBy(1_100)
            pollReal("the ranges") { downloads.saves.isNotEmpty() && last().singleOrNull()?.ranges != null }
            assertEquals(ranges(halves), last().single().ranges)
        }

    @Test
    fun rangeProgressIsSavedWithinFiveSecondsAndNotOnEveryReport() =
        runComposeUiTest {
            val s = Session()
            home(s)
            startSegmented(s)
            mainClock.advanceTimeBy(1_100)
            pollReal("the ranges") { downloads.saves.isNotEmpty() && last().singleOrNull()?.ranges != null }
            val before = downloads.saves.size
            var latest = halves
            repeat(40) {
                latest = listOf(Span(0, 499, 5L + it), Span(500, 999, 3L + it))
                s.engine.emit(EngineEvent.Progress("1", 8L + 2 * it, 1000, 2L * 1024 * 1024, latest, 2, "AA"))
                mainClock.advanceTimeBy(20)
            }
            await("the last progress") { runCatching { onNodeWithTag("speed-1").assertTextEquals("2.0 MB/s") }.isSuccess }
            mainClock.advanceTimeBy(2_500)
            Thread.sleep(100)
            assertEquals(before, downloads.saves.size, "range progress alone must not be saved every second")
            mainClock.advanceTimeBy(3_000) // more than five seconds since the last save
            pollReal("the range progress") { last().single().ranges == ranges(latest) }
        }

    /** Segmented and running, with sixty percent of the ranges and the token AA saved (the ticker's save is the last one). */
    private fun ComposeUiTest.runningWithSavedProgress(s: Session): List<Span> {
        startSegmented(s)
        mainClock.advanceTimeBy(1_100)
        pollReal("the ranges") { downloads.saves.isNotEmpty() && last().singleOrNull()?.ranges != null }
        val sixty = listOf(Span(0, 499, 300), Span(500, 999, 300))
        s.engine.emit(EngineEvent.Progress("1", 600, 1000, 2L * 1024 * 1024, sixty, 2, "AA"))
        await("the progress") { runCatching { onNodeWithTag("speed-1").assertTextEquals("2.0 MB/s") }.isSuccess }
        mainClock.advanceTimeBy(2_500)
        mainClock.advanceTimeBy(3_000)
        pollReal("the saved progress") { last().single().ranges == ranges(sixty) }
        return sixty
    }

    // M1b: fresh ranges reach the disk at once, even when they differ from the saved ones only in what is done
    @Test
    fun aStartedWithFreshRangesDifferingOnlyInDoneIsSavedWithinTheStateInterval() =
        runComposeUiTest {
            val s = Session()
            home(s)
            runningWithSavedProgress(s)
            s.engine.emit(EngineEvent.Started("1", "a.zip", 1000, "\"e\"", null, 0, halves, "AA")) // the file was started over
            mainClock.advanceTimeBy(1_100)
            pollReal("the fresh ranges") { last().single().ranges == ranges(halves) }
        }

    @Test
    fun aStartedWithAnotherPartIdIsSavedWithinTheStateInterval() =
        runComposeUiTest {
            val s = Session()
            home(s)
            val sixty = runningWithSavedProgress(s)
            s.engine.emit(EngineEvent.Started("1", "a.zip", 1000, "\"e\"", null, 600, sixty, "BB"))
            mainClock.advanceTimeBy(1_100)
            pollReal("the new identity") { last().single().partId == "BB" }
        }

    @Test
    fun theProgressTickerSavesNothingWhileOnlyRowsWithoutRangesAreRunning() =
        runComposeUiTest {
            val s = Session()
            home(s)
            add("https://x.test/a.zip")
            await("start") { s.engine.starts.isNotEmpty() }
            s.engine.emit(EngineEvent.Started("1", "a.zip", 1000, null, null, 0)) // one connection: no ranges
            await("the save of the start") { downloads.saves.isNotEmpty() && last().single().total == 1000L }
            val before = downloads.saves.size
            repeat(40) {
                s.engine.emit(EngineEvent.Progress("1", 10L + it, 1000, 2L * 1024 * 1024))
                mainClock.advanceTimeBy(20)
            }
            await("the last progress") { runCatching { onNodeWithTag("speed-1").assertTextEquals("2.0 MB/s") }.isSuccess }
            repeat(3) {
                // well past the five seconds of the ticker, and its save interval
                mainClock.advanceTimeBy(3_000)
                Thread.sleep(100)
            }
            assertEquals(before, downloads.saves.size, "a row without ranges is read from its .part file: nothing to tick for")
        }

    @Test
    fun theStoppedSpansOfAPausedJobAreSavedWithinTheStateInterval() =
        runComposeUiTest {
            val s = Session()
            home(s)
            startSegmented(s)
            s.engine.emit(EngineEvent.Progress("1", 10, 1000, 2L * 1024 * 1024, listOf(Span(0, 499, 6), Span(500, 999, 4)), 2, "AA"))
            await("progress") { runCatching { onNodeWithTag("speed-1").assertTextEquals("2.0 MB/s") }.isSuccess }
            onNodeWithTag("pause-1").performClick()
            await("the paused save") { downloads.saves.isNotEmpty() && last().singleOrNull()?.status == DownloadStatus.Paused }
            val exact = listOf(Span(0, 499, 9), Span(500, 999, 8))
            s.engine.emit(EngineEvent.Stopped("1", 17, exact, "AA"))
            mainClock.advanceTimeBy(1_100)
            pollReal("the stopped ranges") { last().single().ranges == ranges(exact) }
            assertEquals(17L, last().single().done)
        }

    @Test
    fun theExitFlushWritesTheLatestRanges() =
        runComposeUiTest {
            val s = Session()
            home(s)
            startSegmented(s)
            mainClock.advanceTimeBy(1_100)
            pollReal("the ranges") { downloads.saves.isNotEmpty() && last().singleOrNull()?.ranges != null }
            val latest = listOf(Span(0, 499, 77), Span(500, 999, 66))
            s.engine.emit(EngineEvent.Progress("1", 143, 1000, 2L * 1024 * 1024, latest, 2, "AA"))
            await("the progress") { runCatching { onNodeWithTag("speed-1").assertTextEquals("2.0 MB/s") }.isSuccess }
            s.stores.flush()
            assertEquals(ranges(latest), last().single().ranges)
        }

    private fun saved(
        connections: Int,
        spans: List<Span>?,
        done: Long = 7,
        partId: String? = "AA",
    ) = DownloadsFile(
        downloads =
            listOf(
                DownloadDto(
                    "1",
                    "https://x.test/a.zip",
                    "a.zip",
                    folder,
                    1000,
                    done,
                    DownloadStatus.Paused,
                    connections = connections,
                    ranges = spans?.let { ranges(it) },
                    partId = partId,
                ),
            ),
    )

    @Test
    fun aRestoredSegmentedRowTakesItsDoneFromItsRangesNotFromTheFullSizePartFile() {
        disk.put(part.toString(), 1000 + TRAILER_BYTES) // the data, and the identity after it
        val row = restoredRows(saved(4, listOf(Span(0, 499, 120), Span(500, 999, 30))), disk, folder).single()
        assertEquals(150L, row.doneBytes)
        assertEquals(listOf(Span(0, 499, 120), Span(500, 999, 30)), row.spans)
    }

    @Test
    fun aRestoredSegmentedRowWhosePartFileIsGoneStartsOverWithoutSpans() {
        val row = restoredRows(saved(4, listOf(Span(0, 499, 120), Span(500, 999, 30))), disk, folder).single()
        assertEquals(0L, row.doneBytes)
        assertEquals(null, row.spans)
    }

    // m5: a part file of another length than the ranges were made for says nothing of what they call done
    @Test
    fun aRestoredSegmentedRowWhosePartFileHasAnotherLengthThanTotalPlusTrailerStartsOver() {
        val spans = listOf(Span(0, 499, 120), Span(500, 999, 30))
        for (size in listOf(1000, 1000 + TRAILER_BYTES - 1, 1000 + TRAILER_BYTES + 1, 40)) {
            disk.put(part.toString(), size)
            val row = restoredRows(saved(4, spans), disk, folder).single()
            assertEquals(0L, row.doneBytes, "size $size")
            assertEquals(null, row.spans, "size $size")
            assertEquals(null, row.partId, "size $size")
        }
    }

    @Test
    fun aRestoredSegmentedRowWithRangesButNoPartIdIsNotTrusted() {
        disk.put(part.toString(), 1000 + TRAILER_BYTES)
        val row = restoredRows(saved(4, listOf(Span(0, 499, 120), Span(500, 999, 30)), partId = null), disk, folder).single()
        assertEquals(0L, row.doneBytes)
        assertEquals(null, row.spans)
    }

    @Test
    fun aRestoredRowOnSeveralConnectionsWithoutRangesShowsZeroWhateverThePartFileHolds() {
        disk.put(part.toString(), 1000)
        assertEquals(0L, restoredRows(saved(4, null), disk, folder).single().doneBytes)
    }

    @Test
    fun aRestoredRowOnOneConnectionStillTakesTheSizeOfThePartFile() {
        disk.put(part.toString(), 410)
        assertEquals(410L, restoredRows(saved(1, null), disk, folder).single().doneBytes)
    }
}
