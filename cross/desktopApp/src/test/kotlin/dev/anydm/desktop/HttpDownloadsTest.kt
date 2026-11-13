package dev.anydm.desktop

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.anydm.engine.http.EngineEvent
import dev.anydm.engine.http.ProbeOutcome
import dev.anydm.engine.http.ProbeResult
import dev.anydm.engine.http.Span
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The Home screen driving a fake engine: what a queue change asks of the engine, and how its events show in the rows. */
@OptIn(ExperimentalTestApi::class)
class HttpDownloadsTest {
    private val guard = ThreadGuard()
    private val engine = FakeEngine(guard)
    private val prober = FakeProber(guard)
    private val opener = FakeOpener(guard)
    private val disk = MemoryDiskIo(guard)
    private val env = DownloadEnv(engine, prober, opener, disk)

    private fun ComposeUiTest.home(state: HomeState = emptySample()) =
        setContent {
            guard.forbidden.add(Thread.currentThread())
            HomeScreen(state, true, clipboardText = { null }, downloadEnv = env)
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

    /** Lets the app run for a while (frames and IO), for a check that something did NOT happen. */
    private fun ComposeUiTest.pump(ms: Int = 300) {
        repeat(ms / 10) {
            mainClock.advanceTimeByFrame()
            Thread.sleep(10)
        }
    }

    private fun ComposeUiTest.add(url: String) {
        onNodeWithTag("new-download").performClick()
        onNodeWithTag("url-field").performTextInput(url)
        onNodeWithTag("download").performClick()
    }

    private fun ComposeUiTest.speed(
        id: String,
        text: String,
    ) = onNodeWithTag("speed-$id").assertTextEquals(text)

    @Test
    fun anAddedLinkStartsAnEngineJobForItsFolderAndName() =
        runComposeUiTest {
            home()
            add("https://x.test/a.zip")
            await("start") { engine.starts.isNotEmpty() }
            val job = engine.starts.single()
            assertEquals(
                listOf("1", "https://x.test/a.zip", "/home/user/Downloads", "a.zip", 0L),
                listOf(job.id, job.url, job.folder, job.name, job.done),
            )
            onNodeWithText("a.zip").assertIsDisplayed()
        }

    @Test
    fun engineEventsMoveTheRow() =
        runComposeUiTest {
            home()
            add("https://x.test/a.zip")
            await("start") { engine.starts.isNotEmpty() }
            engine.emit(EngineEvent.Started("1", "a.zip", 1000, "\"e\"", null, 0))
            engine.emit(EngineEvent.Progress("1", 500, 1000, 2L * 1024 * 1024))
            await("speed") { runCatching { speed("1", "2.0 MB/s") }.isSuccess }
            onNodeWithText("50% · 500 B of 1000 B · 0s").assertIsDisplayed()
        }

    @Test
    fun anUnknownSizeShowsBytesAndSpeedWithoutAPercentage() =
        runComposeUiTest {
            home()
            add("https://x.test/a.zip")
            await("start") { engine.starts.isNotEmpty() }
            engine.emit(EngineEvent.Started("1", "a.zip", null, null, null, 0))
            engine.emit(EngineEvent.Progress("1", 1536, null, 1024L * 1024))
            await("speed") { runCatching { speed("1", "1.0 MB/s") }.isSuccess }
            onNodeWithText("1.5 KB · 1.0 MB/s").assertIsDisplayed()
        }

    @Test
    fun withOneSlotTheSecondDownloadWaitsAndStartsWhenTheFirstFinishes() =
        runComposeUiTest {
            home()
            onNodeWithTag("network-chip").performClick()
            onNodeWithTag("networks-limits-link").performClick()
            repeat(2) { onNodeWithTag("concurrency-minus").performClick() }
            onNodeWithTag("limits-save").performClick()
            onNodeWithTag("settings-back").performClick()
            add("https://x.test/a.zip")
            add("https://x.test/b.zip")
            await("first start") { engine.starts.size == 1 }
            speed("2", "#1 · Queued")
            engine.emit(EngineEvent.Finished("1", 1000))
            await("second start") { engine.starts.size == 2 }
            assertEquals("2", engine.starts.last().id)
            assertTrue("1" in engine.stops)
        }

    @Test
    fun aFailureShowsItsReasonFreesTheSlotAndRetryStartsTheJobAgain() =
        runComposeUiTest {
            home()
            add("https://x.test/a.zip")
            await("start") { engine.starts.size == 1 }
            engine.emit(EngineEvent.Failed("1", "HTTP 404", 0))
            await("failed row") { runCatching { speed("1", "HTTP 404") }.isSuccess }
            onNodeWithTag("pause-1").performClick()
            await("second start") { engine.starts.size == 2 }
            assertEquals("1", engine.starts.last().id)
        }

    @Test
    fun aResumeStartsFromTheSizeOfThePartFileOnDisk() =
        runComposeUiTest {
            home()
            add("https://x.test/a.zip")
            await("start") { engine.starts.size == 1 }
            engine.emit(EngineEvent.Started("1", "a.zip", 1000, null, null, 0))
            engine.emit(EngineEvent.Progress("1", 100, 1000, 10))
            onNodeWithTag("pause-1").performClick()
            await("stop") { "1" in engine.stops }
            // The engine wrote more than it last reported before it stopped.
            disk.put("/home/user/Downloads/a.zip.part", 130)
            onNodeWithTag("pause-1").performClick()
            await("second start") { engine.starts.size == 2 }
            assertEquals(130L, engine.starts.last().done)
        }

    @Test
    fun torrentAndMagnetRowsNeverReachTheEngine() =
        runComposeUiTest {
            home()
            add("magnet:?xt=urn:btih:08ada5a7a6183aae1e09d8")
            onNodeWithText("Sintel").assertIsDisplayed()
            onNodeWithTag("pause-1").performClick()
            onNodeWithTag("pause-1").performClick()
            Thread.sleep(200)
            assertEquals(emptyList(), engine.starts.toList())
            assertEquals(emptyList(), engine.stops.toList())
        }

    @Test
    fun noEngineProbeOrDiskCallRunsOnTheUiThread() =
        runComposeUiTest {
            home()
            prober.fallback = ProbeOutcome.Ok(ProbeResult("a.zip", 1000, true))
            onNodeWithTag("new-download").performClick()
            onNodeWithTag("url-field").performTextInput("https://x.test/a.zip")
            await("probe") { prober.probed.isNotEmpty() }
            await("ready") { runCatching { onNodeWithText("1000 B · Resumable · 8 connections").assertIsDisplayed() }.isSuccess }
            onNodeWithTag("download").performClick()
            await("start") { engine.starts.size == 1 }
            engine.emit(EngineEvent.Failed("1", "Network error", 0))
            await("failed row") { runCatching { speed("1", "Network error") }.isSuccess }
            onNodeWithTag("pause-1").performClick()
            await("second start") { engine.starts.size == 2 }
            assertTrue(prober.probed.isNotEmpty(), "the probe ran")
            assertTrue(engine.rates.isNotEmpty(), "the rate was set")
            assertEquals(0, guard.violations)
        }

    private fun ComposeUiTest.rightClickRow(id: String) =
        onNodeWithTag("row-$id").performMouseInput {
            rightClick(
                Offset(
                    width / 2f,
                    height / 2f,
                ),
            )
        }

    private fun ComposeUiTest.removeVia(
        id: String,
        ticked: Boolean,
    ) {
        onNodeWithTag("select-$id").performClick()
        onNodeWithTag("bar-remove").performClick()
        onNodeWithTag("remove-delete-label").assertIsDisplayed()
        if (ticked) onNodeWithTag("remove-delete").performClick()
        onNodeWithTag("remove-confirm").performClick()
    }

    private val folder = "/home/user/Downloads"

    private fun ComposeUiTest.startedRow() {
        home()
        add("https://x.test/a.zip")
        await("start") { engine.starts.size == 1 }
        disk.put("$folder/a.zip.part", 10)
    }

    private val part = Path.of("$folder/a.zip.part")
    private val final = Path.of("$folder/a.zip")

    @Test
    fun removeWithTheBoxTickedStopsTheJobAndMovesOnlyThePartFileOfARunningRowToTheTrash() =
        runComposeUiTest {
            startedRow()
            disk.put("$folder/a.zip", 5) // not this download's: a running row only ever wrote its `.part`
            removeVia("1", ticked = true)
            await("trash") { opener.trashed.isNotEmpty() }
            assertEquals(listOf(part), opener.trashed.toList())
            assertEquals(emptyList(), disk.deleted.toList())
            assertTrue(disk.files.containsKey(final), "another file of that name must stay")
            await("stop") { "1" in engine.stops }
        }

    @Test
    fun removeWithTheBoxTickedMovesOnlyTheFinalFileOfAFinishedRowToTheTrash() =
        runComposeUiTest {
            startedRow()
            disk.put("$folder/a.zip", 5)
            engine.emit(EngineEvent.Finished("1", 5))
            await("finished") { runCatching { onNodeWithText("FINISHED 1").assertIsDisplayed() }.isSuccess }
            removeVia("1", ticked = true)
            await("trash") { opener.trashed.isNotEmpty() }
            assertEquals(listOf(final), opener.trashed.toList())
        }

    @Test
    fun aRowNamedAfterAFileAlreadyOnDiskNeverDeletesThatFile() =
        runComposeUiTest {
            home()
            disk.put("$folder/a.zip", 5) // the user's own file
            add("https://x.test/a.zip")
            await("start") { engine.starts.size == 1 }
            disk.put("$folder/a (1).zip.part", 10)
            removeVia("1", ticked = true)
            await("trash") { opener.trashed.isNotEmpty() }
            assertEquals(listOf(Path.of("$folder/a (1).zip.part")), opener.trashed.toList())
            assertTrue(disk.files.containsKey(final))
        }

    @Test
    fun aJobThatFinishesJustAfterARemoveWithTheBoxTickedLosesItsFinalFileToo() =
        runComposeUiTest {
            startedRow()
            removeVia("1", ticked = true)
            await("the part file") { opener.trashed.size == 1 }
            // The job had already renamed its file when the cancel came; its Finished event still arrives.
            disk.put("$folder/a.zip", 5)
            engine.emit(EngineEvent.Finished("1", 5))
            await("the final file") { opener.trashed.size == 2 }
            assertEquals(listOf(part, final), opener.trashed.toList())
            engine.emit(EngineEvent.Finished("1", 5))
            Thread.sleep(150)
            assertEquals(2, opener.trashed.size, "the id is forgotten once its file went")
        }

    @Test
    fun theLateFinalFileIsTheOneUnderTheNameTheEngineChose() =
        runComposeUiTest {
            startedRow()
            removeVia("1", ticked = true)
            await("the part file") { opener.trashed.size == 1 }
            engine.emit(EngineEvent.Finished("1", 5, "a (1).zip"))
            await("the final file") { opener.trashed.size == 2 }
            assertEquals(Path.of("$folder/a (1).zip"), opener.trashed.last())
        }

    @Test
    fun aJobThatFinishesAfterARemoveWithTheBoxUntickedKeepsItsFile() =
        runComposeUiTest {
            startedRow()
            removeVia("1", ticked = false)
            await("stop") { "1" in engine.stops }
            disk.put("$folder/a.zip", 5)
            engine.emit(EngineEvent.Finished("1", 5))
            Thread.sleep(200)
            assertEquals(emptyList(), opener.trashed.toList())
            assertEquals(emptyList(), disk.deleted.toList())
        }

    @Test
    fun withNoTrashTheFilesAreDeletedForGood() =
        runComposeUiTest {
            startedRow()
            opener.canTrash = false
            removeVia("1", ticked = true)
            await("delete") { disk.deleted.isNotEmpty() }
            assertEquals(listOf(part), disk.deleted.toList())
            assertTrue(disk.files.isEmpty())
        }

    @Test
    fun theFilesAreOnlyRemovedAfterTheStopHasFinishedAndInTheOrderOfTheCommands() =
        runComposeUiTest {
            startedRow()
            val gate = CompletableDeferred<Unit>()
            engine.joinGate = gate
            removeVia("1", ticked = true)
            await("join") { "join:1" in engine.log }
            Thread.sleep(150)
            assertEquals(emptyList(), opener.trashed.toList(), "the file went while the job might still be writing it")
            assertEquals(listOf("start:1", "stop:1", "join:1"), engine.log.toList())
            gate.complete(Unit)
            await("trash") { opener.trashed.isNotEmpty() }
        }

    @Test
    fun removeWithTheBoxUntickedKeepsTheFilesButStopsTheJob() =
        runComposeUiTest {
            startedRow()
            removeVia("1", ticked = false)
            await("stop") { "1" in engine.stops }
            Thread.sleep(100)
            assertEquals(emptyList(), disk.deleted.toList())
            assertTrue(disk.files.containsKey(Path.of("$folder/a.zip.part")))
            onNodeWithText("a.zip").assertDoesNotExist()
        }

    @Test
    fun removeWithTheBoxUntickedStillMakesTheEngineForgetTheRowAfterItsJobHasEnded() =
        runComposeUiTest {
            startedRow()
            removeVia("1", ticked = false)
            await("forget") { "forget:1" in engine.log }
            assertEquals(listOf("start:1", "stop:1", "join:1", "forget:1"), engine.log.toList())
            assertEquals(emptyList(), disk.deleted.toList())
            assertEquals(emptyList(), opener.trashed.toList())
        }

    @Test
    fun theDeleteBoxStartsUnticked() =
        runComposeUiTest {
            startedRow()
            onNodeWithTag("select-1").performClick()
            onNodeWithTag("bar-remove").performClick()
            onNodeWithTag("remove-delete").assertIsOff()
            onNodeWithTag("remove-delete-label").assertTextEquals("Also delete the file")
        }

    @Test
    fun openOnAFinishedRowCallsTheOpenerWithItsPath() =
        runComposeUiTest {
            startedRow()
            engine.emit(EngineEvent.Finished("1", 100))
            await("finished") { runCatching { onNodeWithText("FINISHED 1").assertIsDisplayed() }.isSuccess }
            rightClickRow("1")
            onNodeWithTag("menu-open").performClick()
            await("open") { opener.opened.isNotEmpty() }
            assertEquals(Path.of("$folder/a.zip"), opener.opened.single())
        }

    @Test
    fun showInFolderRevealsThePartFileWhileTheDownloadRuns() =
        runComposeUiTest {
            startedRow()
            rightClickRow("1")
            onNodeWithTag("menu-folder").performClick()
            await("reveal") { opener.revealed.isNotEmpty() }
            assertEquals(Path.of("$folder/a.zip.part"), opener.revealed.single())
        }

    @Test
    fun aMissingFileShowsFileNotFound() =
        runComposeUiTest {
            startedRow()
            engine.emit(EngineEvent.Finished("1", 100))
            await("finished") { runCatching { onNodeWithText("FINISHED 1").assertIsDisplayed() }.isSuccess }
            opener.result = false
            rightClickRow("1")
            onNodeWithTag("menu-open").performClick()
            await("notice") { runCatching { onNodeWithText("File not found").assertIsDisplayed() }.isSuccess }
        }

    @Test
    fun aFreshStartHasNoRowsAndShowsTheEmptyState() =
        runComposeUiTest {
            home(startState(null))
            onNodeWithTag("empty-state").assertIsDisplayed()
            onNodeWithText("2 networks").assertIsDisplayed()
            assertEquals(emptyList(), engine.starts.toList())
        }

    @Test
    fun aNameThatIsAlreadyOnDiskIsSettledWhenTheRowIsAddedNotWhenTheJobStarts() =
        runComposeUiTest {
            home()
            disk.put("$folder/a.zip", 5)
            add("https://x.test/a.zip")
            await("start") { engine.starts.size == 1 }
            // The row and the job agree on the real name from the first moment; the engine never has to rename.
            assertEquals("a (1).zip", engine.starts.single().name)
            onNodeWithText("a (1).zip").assertIsDisplayed()
        }

    @Test
    fun theSameLinkAddedTwiceOverAFileOnDiskGivesTwoDifferentPartFiles() =
        runComposeUiTest {
            home()
            disk.put("$folder/a.zip", 5)
            add("https://x.test/a.zip")
            add("https://x.test/a.zip")
            await("two starts") { engine.starts.size == 2 }
            assertEquals(listOf("a (1).zip", "a (2).zip"), engine.starts.map { it.name })
        }

    @Test
    fun aQueuedRowKeepsItsNameReservedAgainstTheNextLink() =
        runComposeUiTest {
            home()
            onNodeWithTag("network-chip").performClick()
            onNodeWithTag("networks-limits-link").performClick()
            repeat(2) { onNodeWithTag("concurrency-minus").performClick() }
            onNodeWithTag("limits-save").performClick()
            onNodeWithTag("settings-back").performClick()
            add("https://x.test/a.zip")
            add("https://x.test/a.zip")
            add("https://x.test/a.zip")
            await("first start") { engine.starts.size == 1 }
            engine.emit(EngineEvent.Finished("1", 10))
            await("second") { engine.starts.size == 2 }
            engine.emit(EngineEvent.Finished("2", 10))
            await("third") { engine.starts.size == 3 }
            assertEquals(listOf("a.zip", "a (1).zip", "a (2).zip"), engine.starts.map { it.name })
        }

    @Test
    fun downloadPressedBeforeTheProbeAnswersTakesTheNameFromTheServer() =
        runComposeUiTest {
            home()
            val gate = CompletableDeferred<Unit>()
            prober.gate = { gate.await() }
            prober.answers["https://x.test/get?id=7"] = ProbeOutcome.Ok(ProbeResult("report.pdf", 2048, true))
            onNodeWithTag("new-download").performClick()
            onNodeWithTag("url-field").performTextInput("https://x.test/get?id=7")
            await("the probe is in flight") { prober.probed.isNotEmpty() }
            onNodeWithTag("download").performClick()
            Thread.sleep(150)
            assertEquals(emptyList(), engine.starts.toList(), "nothing may start under the link's name before the server's name is known")
            gate.complete(Unit)
            await("start") { engine.starts.size == 1 }
            val job = engine.starts.single()
            assertEquals(listOf("report.pdf", 2048L), listOf(job.name, job.total))
            onNodeWithText("report.pdf").assertIsDisplayed()
        }

    private fun ComposeUiTest.addProbed(
        probe: ProbeResult,
        ready: String,
    ) {
        home()
        prober.fallback = ProbeOutcome.Ok(probe)
        onNodeWithTag("new-download").performClick()
        onNodeWithTag("url-field").performTextInput("https://x.test/a.zip")
        await("ready") { runCatching { onNodeWithText(ready).assertIsDisplayed() }.isSuccess }
    }

    @Test
    fun theConnectionsChosenInTheDialogGoToTheRowAndTheEngineJob() =
        runComposeUiTest {
            addProbed(ProbeResult("a.zip", 5000, true), "4.9 KB · Resumable · 8 connections")
            repeat(5) { onNodeWithTag("connections-minus").performClick() }
            onNodeWithTag("download").performClick()
            await("start") { engine.starts.size == 1 }
            assertEquals(3, engine.starts.single().connections)
        }

    @Test
    fun aLinkWithoutRangeSupportStartsOnOneConnection() =
        runComposeUiTest {
            addProbed(ProbeResult("a.zip", 5000, false), "4.9 KB · Uses one connection. This server doesn't support parallel downloads.")
            onNodeWithTag("download").performClick()
            await("start") { engine.starts.size == 1 }
            assertEquals(1, engine.starts.single().connections)
        }

    @Test
    fun downloadPressedBeforeAProbeThatFindsNoRangeSupportStartsOnOneConnection() =
        runComposeUiTest {
            home()
            val gate = CompletableDeferred<Unit>()
            prober.gate = { gate.await() }
            prober.fallback = ProbeOutcome.Ok(ProbeResult("a.zip", 5000, false))
            onNodeWithTag("new-download").performClick()
            onNodeWithTag("url-field").performTextInput("https://x.test/a.zip")
            await("the probe is in flight") { prober.probed.isNotEmpty() }
            onNodeWithTag("download").performClick()
            gate.complete(Unit)
            await("start") { engine.starts.size == 1 }
            assertEquals(1, engine.starts.single().connections)
        }

    @Test
    fun theServersNameIsReservedAgainstTheDiskToo() =
        runComposeUiTest {
            home()
            disk.put("$folder/report.pdf", 5)
            val gate = CompletableDeferred<Unit>()
            prober.gate = { gate.await() }
            prober.answers["https://x.test/get?id=7"] = ProbeOutcome.Ok(ProbeResult("report.pdf", 2048, true))
            onNodeWithTag("new-download").performClick()
            onNodeWithTag("url-field").performTextInput("https://x.test/get?id=7")
            await("the probe is in flight") { prober.probed.isNotEmpty() }
            onNodeWithTag("download").performClick()
            gate.complete(Unit)
            await("start") { engine.starts.size == 1 }
            assertEquals("report (1).pdf", engine.starts.single().name)
        }

    @Test
    fun aProbeThatFailsWhileDownloadWaitsForItStillAddsTheRowUnderTheLinksName() =
        runComposeUiTest {
            home()
            val gate = CompletableDeferred<Unit>()
            prober.gate = { gate.await() }
            onNodeWithTag("new-download").performClick()
            onNodeWithTag("url-field").performTextInput("https://x.test/get.bin")
            await("the probe is in flight") { prober.probed.isNotEmpty() }
            onNodeWithTag("download").performClick()
            gate.complete(Unit)
            await("start") { engine.starts.size == 1 }
            assertEquals("get.bin", engine.starts.single().name)
        }

    @Test
    fun twoLinksThatDifferOnlyInCaseGetDifferentNames() =
        runComposeUiTest {
            home()
            add("https://x.test/Foo.zip")
            add("https://x.test/foo.zip")
            await("two starts") { engine.starts.size == 2 }
            // One file on a case-insensitive file system (macOS): they must not share a `.part`.
            assertEquals(listOf("Foo.zip", "foo (1).zip"), engine.starts.map { it.name })
        }

    @Test
    fun aProbeThatNeverAnswersAddsTheRowUnderTheLinksNameAfterTheWait() =
        runComposeUiTest {
            val slow = DownloadEnv(engine, prober, opener, disk, probeWaitMs = 300)
            setContent {
                guard.forbidden.add(Thread.currentThread())
                HomeScreen(emptySample(), true, clipboardText = { null }, downloadEnv = slow)
            }
            prober.gate = { awaitCancellation() }
            onNodeWithTag("new-download").performClick()
            onNodeWithTag("url-field").performTextInput("https://x.test/get.bin")
            await("the probe is in flight") { prober.probed.isNotEmpty() }
            onNodeWithTag("download").performClick()
            Thread.sleep(100)
            assertEquals(emptyList(), engine.starts.toList(), "the row waits for the probe first")
            await("start") { engine.starts.size == 1 }
            assertEquals("get.bin", engine.starts.single().name)
            await("the wait gave up on the probe") { prober.cancelled.isNotEmpty() }
        }

    @Test
    fun aFinishedEventCarriesTheNameTheEngineHadToChoose() =
        runComposeUiTest {
            startedRow()
            engine.emit(EngineEvent.Finished("1", 100, "a (1).zip"))
            await("renamed row") { runCatching { onNodeWithText("a (1).zip").assertIsDisplayed() }.isSuccess }
            onNodeWithText("a.zip").assertDoesNotExist()
        }

    @Test
    fun anEngineThatThrowsOnOneStartDoesNotStopTheNextCommands() =
        runComposeUiTest {
            home()
            engine.failStarts = 1
            add("https://x.test/a.zip")
            await("the failing start") { "start:1" in engine.log }
            add("https://x.test/b.zip")
            await("the next start") { engine.starts.size == 1 }
            assertEquals("2", engine.starts.single().id)
        }

    /**
     * Two clicks in one UI task, so the second runs from the screen as it was composed before the first changed anything: what a
     * click right after an engine event does (the event, like the first click, changes the list; no frame comes in between).
     * Called with the first click's tag, then the second's.
     */
    private fun ComposeUiTest.clickTwiceInOneTask(
        first: String,
        second: String,
    ) {
        val a = onNodeWithTag(first).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        val b = onNodeWithTag(second).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        runOnUiThread {
            a()
            b()
        }
    }

    @Test
    fun aClickOnTheSelectionBarJustAfterAnotherChangeDoesNotBringBackTheRowThatChanged() =
        runComposeUiTest {
            home()
            add("https://x.test/a.zip")
            add("https://x.test/b.zip")
            await("two starts") { engine.starts.size == 2 }
            onNodeWithTag("select-2").performClick()
            onNodeWithTag("selection-bar").assertIsDisplayed()
            clickTwiceInOneTask("pause-1", "bar-pause")
            await("row 2 paused") { runCatching { speed("2", "Paused") }.isSuccess }
            speed("1", "Paused")
            assertEquals(1, engine.starts.count { it.id == "1" }, "row 1 was started again")
        }

    @Test
    fun aResumeOnTheSelectionBarJustAfterAnotherChangeDoesNotBringBackThePausedRow() =
        runComposeUiTest {
            home()
            add("https://x.test/a.zip")
            add("https://x.test/b.zip")
            await("two starts") { engine.starts.size == 2 }
            onNodeWithTag("pause-2").performClick()
            onNodeWithTag("select-2").performClick()
            onNodeWithTag("selection-bar").assertIsDisplayed()
            clickTwiceInOneTask("pause-1", "bar-resume")
            await("row 2 resumed") { engine.starts.count { it.id == "2" } == 2 }
            speed("1", "Paused")
            assertEquals(1, engine.starts.count { it.id == "1" }, "row 1 was started again")
        }

    @Test
    fun aMenuActionJustAfterAnotherChangeUsesTheListAsItIsNow() =
        runComposeUiTest {
            home()
            add("https://x.test/a.zip")
            add("https://x.test/b.zip")
            await("two starts") { engine.starts.size == 2 }
            rightClickRow("2")
            onNodeWithTag("menu-pause").assertIsDisplayed()
            clickTwiceInOneTask("pause-1", "menu-pause")
            await("row 2 paused") { runCatching { speed("2", "Paused") }.isSuccess }
            speed("1", "Paused")
            assertEquals(1, engine.starts.count { it.id == "1" }, "row 1 was started again")
        }

    private fun ComposeUiTest.openDetail(id: String) = onNodeWithTag("chevron-$id").performClick()

    @Test
    fun theDetailOfARealRowChartsItsOwnSpeedsAndShowsNoPerNetworkBreakdown() =
        runComposeUiTest {
            startedRow()
            engine.emit(EngineEvent.Started("1", "a.zip", 1000, null, null, 0))
            engine.emit(EngineEvent.Progress("1", 100, 1000, 1L * 1024 * 1024))
            engine.emit(EngineEvent.Progress("1", 300, 1000, 3L * 1024 * 1024))
            await("speed") { runCatching { speed("1", "3.0 MB/s") }.isSuccess }
            openDetail("1")
            onNodeWithTag(
                "detail-graph",
            ).assert(
                SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Speed during this session, peak 3.0 MB/s")),
            )
            onNodeWithTag("detail-single-speed").assertTextEquals("3.0 MB/s")
            onNodeWithText("1 CONNECTION").assertIsDisplayed()
            onNodeWithTag("detail-table").assertDoesNotExist()
            onNodeWithTag("detail-diagram").assertIsDisplayed()
        }

    private val mib = 1024L * 1024

    private val fourSpans =
        listOf(
            Span(0, 8 * mib - 1, 4 * mib),
            Span(8 * mib, 16 * mib - 1, 0),
            Span(16 * mib, 24 * mib - 1, 8 * mib),
            Span(24 * mib, 32 * mib - 1, 2 * mib),
        )

    /** A 32 MiB row added over 4 connections and reporting [fourSpans] with [live] connections open, its detail open. */
    private fun ComposeUiTest.segmentedDetail(live: Int = 4) {
        addProbed(ProbeResult("a.zip", 32 * mib, true), "32.0 MB · Resumable · 8 connections")
        repeat(4) { onNodeWithTag("connections-minus").performClick() }
        onNodeWithTag("download").performClick()
        await("start") { engine.starts.size == 1 }
        assertEquals(4, engine.starts.single().connections)
        engine.emit(EngineEvent.Started("1", "a.zip", 32 * mib, null, null, 14 * mib, fourSpans))
        engine.emit(EngineEvent.Progress("1", 14 * mib, 32 * mib, 3 * mib, fourSpans, live))
        await("speed") { runCatching { speed("1", "3.0 MB/s") }.isSuccess }
        openDetail("1")
    }

    @Test
    fun theDetailOfARunningFourConnectionRowShowsItsLiveConnectionsAndTheMapOfItsRanges() =
        runComposeUiTest {
            segmentedDetail()
            onNodeWithText("4 CONNECTIONS").assertIsDisplayed()
            onNodeWithTag("detail-map").assertIsDisplayed()
            onNodeWithTag(
                "detail-map",
            ).assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Chunk map, 128 chunks, 56 done")))
            onNodeWithTag("detail-map-caption").assertTextEquals("128 chunks · 256.0 KB each")
            onNodeWithTag("detail-table").assertDoesNotExist()
        }

    @Test
    fun aConnectionThatDroppedShowsInTheLiveCount() =
        runComposeUiTest {
            segmentedDetail(live = 3)
            onNodeWithText("3 CONNECTIONS").assertIsDisplayed()
        }

    @Test
    fun aPausedFourConnectionRowShowsItsOwnCountAndNoCellInFlight() =
        runComposeUiTest {
            segmentedDetail()
            onNodeWithTag("detail-pause").performClick()
            await("paused") { "1" in engine.stops }
            onNodeWithText("4 CONNECTIONS").assertIsDisplayed()
            onNodeWithTag("detail-map").assertIsDisplayed()
        }

    @Test
    fun theFinishedDetailOfAFourConnectionRowStatesItsConnections() =
        runComposeUiTest {
            segmentedDetail()
            engine.emit(EngineEvent.Finished("1", 32 * mib))
            await("finished") { runCatching { onNodeWithTag("detail-stat-connections").assertTextEquals("4") }.isSuccess }
        }

    @Test
    fun theFinishedDetailOfARealRowShowsOneConnectionAndNoNetworkShares() =
        runComposeUiTest {
            finishedRow()
            onNodeWithTag("detail-stat-connections").assertTextEquals("1")
            onNodeWithTag("detail-stat-networks").assertDoesNotExist()
            onNodeWithTag("detail-contribution").assertDoesNotExist()
            onNodeWithTag("detail-footnote").assertDoesNotExist()
            onNodeWithTag("detail-graph").assertIsDisplayed()
        }

    @Test
    fun aRowAddedAfterARemovedOneStartsWithAnEmptySpeedChartAndTheRemovedOnesIdIsNotGivenAgain() =
        runComposeUiTest {
            startedRow()
            engine.emit(EngineEvent.Progress("1", 100, 1000, 3L * 1024 * 1024))
            await("speed") { runCatching { speed("1", "3.0 MB/s") }.isSuccess }
            removeVia("1", ticked = false)
            await("stop") { "1" in engine.stops }
            add("https://x.test/b.zip") // ids are not reused in a run: this is row 2
            await("restart") { engine.starts.size == 2 }
            assertEquals("2", engine.starts.last().id)
            openDetail("2")
            onNodeWithTag(
                "detail-graph",
            ).assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Speed during this session, peak 0 B/s")))
        }

    @Test
    fun anActiveRealDetailSaysRemoveFromListAndKeepsThePartFile() =
        runComposeUiTest {
            startedRow()
            openDetail("1")
            onNodeWithTag("detail-remove").assertTextEquals("Remove from list")
            onNodeWithTag("detail-remove").performClick()
            await("stop") { "1" in engine.stops }
            Thread.sleep(100)
            assertEquals(emptyList(), opener.trashed.toList())
            assertEquals(emptyList(), disk.deleted.toList())
            assertTrue(disk.files.containsKey(part))
            onNodeWithTag("row-1").assertDoesNotExist()
        }

    private fun ComposeUiTest.finishedRow() {
        startedRow()
        disk.put("$folder/a.zip", 5)
        engine.emit(EngineEvent.Finished("1", 5))
        await("finished") { runCatching { onNodeWithText("FINISHED 1").assertIsDisplayed() }.isSuccess }
        openDetail("1")
    }

    @Test
    fun moveFileToTrashInTheDetailOfAFinishedRowTrashesTheFileAndRemovesTheRow() =
        runComposeUiTest {
            finishedRow()
            onNodeWithTag("detail-trash").performClick()
            await("trash") { opener.trashed.isNotEmpty() }
            assertEquals(listOf(final), opener.trashed.toList())
            onNodeWithTag("detail-screen").assertDoesNotExist()
            onNodeWithTag("row-1").assertDoesNotExist()
        }

    @Test
    fun removeFromListInTheDetailOfAFinishedRowKeepsTheFile() =
        runComposeUiTest {
            finishedRow()
            onNodeWithTag("detail-remove").performClick()
            onNodeWithTag("row-1").assertDoesNotExist()
            Thread.sleep(100)
            assertEquals(emptyList(), opener.trashed.toList())
            assertEquals(emptyList(), disk.deleted.toList())
        }

    @Test
    fun noRemoveCallRunsOnTheUiThread() =
        runComposeUiTest {
            startedRow()
            removeVia("1", ticked = true)
            await("trash") { opener.trashed.isNotEmpty() }
            await("join") { "join:1" in engine.log }
            assertEquals(0, guard.violations)
        }

    /** A downloads file whose load waits for [gate], as a slow disk would. */
    private class GatedDownloadsStore(
        val inner: InMemoryDownloadsStore,
        val gate: CountDownLatch,
    ) : DownloadsStore {
        override fun load(): DownloadsLoad {
            gate.await()
            return inner.load()
        }

        override fun save(file: DownloadsFile) = inner.save(file)
    }

    @Test
    fun aLinkAddedBeforeTheSavedListLoadsIsKeptAfterTheRestoredRowsWithUniqueIds() =
        runComposeUiTest {
            val saved =
                DownloadsFile(
                    downloads =
                        listOf(
                            DownloadDto("1", "https://x.test/old.zip", "old.zip", folder, 100, 40, DownloadStatus.Paused, null, null, null),
                        ),
                )
            val store = GatedDownloadsStore(InMemoryDownloadsStore(saved), CountDownLatch(1))
            setContent {
                guard.forbidden.add(Thread.currentThread())
                HomeScreen(
                    emptySample(),
                    true,
                    clipboardText = { null },
                    stores = AppStores(InMemorySettingsStore(), InMemorySecretStore(), 500, store),
                    downloadEnv = env,
                )
            }
            add("https://x.test/new.zip") // the saved list has not loaded yet: the link waits for it, nothing is lost
            pump()
            assertEquals(emptyList(), engine.starts.toList(), "nothing may start before the saved list is known")
            store.gate.countDown()
            await("start") { engine.starts.size == 1 }
            await("the restored row") { runCatching { onNodeWithText("old.zip").assertIsDisplayed() }.isSuccess }
            onNodeWithText("new.zip").assertIsDisplayed()
            Thread.sleep(150)
            assertEquals(emptyList(), engine.stops.toList(), "the new download must keep running")
            assertEquals(1, engine.starts.size)
            await("the merged save") {
                store.inner.saves.isNotEmpty() && store.inner.saves
                    .last()
                    .downloads.size == 2
            }
            val rows =
                store.inner.saves
                    .last()
                    .downloads
            assertEquals(listOf("old.zip", "new.zip"), rows.map { it.name })
            assertEquals(2, rows.map { it.id }.toSet().size, "ids: ${rows.map { it.id }}")
            assertEquals(DownloadStatus.Downloading, rows.last().status)
            assertEquals(engine.starts.single().id, rows.last().id, "the running row keeps the id its job runs under")
        }

    @Test
    fun aLinkAddedBeforeTheSavedListLoadsNeverTakesTheNameOfARestoredRow() =
        runComposeUiTest {
            val saved =
                DownloadsFile(
                    downloads =
                        listOf(
                            DownloadDto(
                                "1",
                                "https://x.test/same.zip",
                                "same.zip",
                                folder,
                                100,
                                40,
                                DownloadStatus.Paused,
                                null,
                                null,
                                null,
                            ),
                        ),
                )
            val store = GatedDownloadsStore(InMemoryDownloadsStore(saved), CountDownLatch(1))
            setContent {
                guard.forbidden.add(Thread.currentThread())
                HomeScreen(
                    emptySample(),
                    true,
                    clipboardText = { null },
                    stores = AppStores(InMemorySettingsStore(), InMemorySecretStore(), 500, store),
                    downloadEnv = env,
                )
            }
            add("https://x.test/same.zip")
            pump() // the saved list has not loaded yet: the link waits for it
            store.gate.countDown()
            await("start") { engine.starts.size == 1 }
            assertEquals("same (1).zip", engine.starts.single().name)
            onNodeWithText("same.zip").assertIsDisplayed()
            onNodeWithText("same (1).zip").assertIsDisplayed()
        }

    @Test
    fun theSameLinkAddedTwiceGivesTheSecondRowASuffix() =
        runComposeUiTest {
            home()
            add("https://x.test/a.zip")
            add("https://x.test/a.zip")
            await("two starts") { engine.starts.size == 2 }
            assertEquals(listOf("a.zip", "a (1).zip"), engine.starts.map { it.name })
        }
}
