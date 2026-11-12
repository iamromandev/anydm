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
}
