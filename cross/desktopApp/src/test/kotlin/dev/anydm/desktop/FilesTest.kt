package dev.anydm.desktop

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.anydm.engine.torrent.TorrentFile
import dev.anydm.engine.torrent.TorrentMeta
import dev.anydm.engine.torrent.TorrentResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/** Never opens an OS dialog: every test passes fakes through [FilesEnv]. */
private class FakePicker(
    var torrent: Path? = null,
    var folder: String? = null,
) : FilePicker {
    var torrentPicks = 0
    var folderPicks = 0
    var lastCurrent: String? = null

    override fun pickTorrent(): Path? {
        torrentPicks++
        return torrent
    }

    override fun pickFolder(current: String): String? {
        folderPicks++
        lastCurrent = current
        return folder
    }
}

private class FakeLoader(
    val results: MutableMap<Path, TorrentResult> = mutableMapOf(),
    var gate: CompletableDeferred<Unit>? = null,
    /** A real disk read can't be interrupted, so a cancelled read may still finish with a result; this models that. */
    var ignoreCancel: Boolean = false,
) : TorrentLoader {
    val loaded = mutableListOf<Path>()

    /** The file each torrent that was loaded came from, as bytes: what the app copies when Download is pressed. */
    val built = java.util.concurrent.ConcurrentHashMap<Path, ByteArray>()

    override suspend fun load(path: Path): TorrentResult {
        loaded.add(path)
        if (ignoreCancel) withContext(NonCancellable) { gate?.await() } else gate?.await()
        val r = results[path] ?: return TorrentResult.Err("Can't read file")
        if (r !is TorrentResult.Ok) return r
        // The torrent the test describes, as a real file: its info hash is the one of those bytes.
        val bytes = TestTorrents.bytes(r.meta.name, r.meta.files.map { it.path to it.size }, r.meta.isSingle, r.meta.trackers)
        built[path] = bytes
        return TorrentResult.Ok(TestTorrents.meta(bytes))
    }

    override suspend fun bytes(path: Path): ByteArray? = built[path]
}

private const val HOME = "/home/test"

private val tr = listOf(listOf("http://tracker.test/announce"))
private const val MAGNET_LINK =
    "magnet:?xt=urn:btih:08ada5a7a6183aae1e09d831df6748d566095a10&dn=Sintel&tr=udp%3A%2F%2Ftracker.example%3A1337"

@OptIn(ExperimentalTestApi::class)
class FilesTest {
    private val picker = FakePicker()
    private val loader = FakeLoader()
    private val env = FilesEnv(picker, loader, HOME)
    private val show = Path.of("/t/show.torrent")
    private val other = Path.of("/t/other.torrent")
    private val bad = Path.of("/t/bad.torrent")

    private val showMeta =
        TorrentMeta(
            "Show",
            listOf(
                TorrentFile("Season 1/E01.mkv", 3_000_000),
                TorrentFile("Season 1/E02.mkv", 4_000_000),
                TorrentFile("notes.txt", 0),
            ),
            trackers = tr,
        )

    private fun ComposeUiTest.home(state: HomeState = emptySample()) =
        setContent {
            HomeScreen(state, true, clipboardText = { null }, files = env)
        }

    private fun ComposeUiTest.openDialog() = onNodeWithTag("new-download").performClick()

    private fun ComposeUiTest.pickTorrent(
        path: Path = show,
        result: TorrentResult = TorrentResult.Ok(showMeta),
    ) {
        loader.results[path] = result
        picker.torrent = path
        onNodeWithTag("open-torrent").performClick()
        waitForIdle()
    }

    @Test
    fun cancellingTheTorrentPickerLeavesTheDialogUnchanged() =
        runComposeUiTest {
            home()
            openDialog()
            onNodeWithTag("open-torrent").performClick()
            assertEquals(1, picker.torrentPicks)
            assertEquals(emptyList(), loader.loaded)
            onNodeWithText("Paste a link or magnet").assertIsDisplayed()
            onNodeWithTag("torrent-file").assertDoesNotExist()
            onNodeWithTag("preview-name").assertDoesNotExist()
            onNodeWithTag("download").assertIsNotEnabled()
        }

    @Test
    fun aValidTorrentShowsItsNameFilesAndEverythingTicked() =
        runComposeUiTest {
            home()
            openDialog()
            pickTorrent()
            assertEquals(listOf(show), loader.loaded)
            onNodeWithTag("torrent-file").assertTextEquals("show.torrent")
            onNodeWithTag("url-field").assertDoesNotExist()
            onNodeWithTag("preview-badge").assertTextEquals("DIR")
            onNodeWithTag("preview-name").assertTextEquals("Show")
            onNodeWithTag("preview-detail").assertTextEquals("3 files · 6.7 MB")
            onNodeWithText("Season 1/E01.mkv").assertIsDisplayed()
            onNodeWithText("Season 1/E02.mkv").assertIsDisplayed()
            onNodeWithText("notes.txt").assertIsDisplayed()
            for (f in showMeta.files) onNodeWithTag("file-${f.path}").assertIsOn()
            onNodeWithTag("folder-toggle").assertIsOn()
            onNodeWithTag("download").assertIsEnabled()
            onNodeWithTag("torrent-error").assertDoesNotExist()
        }

    @Test
    fun theTreeOfARealTorrentIsLockedAllTickedWithTheHintAndItsSummaryNeverChanges() =
        runComposeUiTest {
            home()
            openDialog()
            pickTorrent()
            onNodeWithTag("torrent-hint").assertTextEquals("Choosing files comes in a later version")
            for (f in showMeta.files) {
                onNodeWithTag("file-${f.path}").assertIsNotEnabled()
                onNodeWithTag("file-${f.path}").performClick()
                onNodeWithTag("file-${f.path}").assertIsOn()
            }
            onNodeWithTag("folder-toggle").assertIsNotEnabled()
            onNodeWithTag("folder-toggle").performClick()
            onNodeWithTag("folder-toggle").assertIsOn()
            onNodeWithTag("preview-detail").assertTextEquals("3 files · 6.7 MB")
            onNodeWithTag("download").assertIsEnabled()
        }

    @Test
    fun aTorrentWithNoTrackersIsRefusedWithTheTextAndNothingToDownload() =
        runComposeUiTest {
            home()
            openDialog()
            pickTorrent(result = TorrentResult.Ok(TorrentMeta("Show", listOf(TorrentFile("a.bin", 5)))))
            onNodeWithTag("torrent-refused")
                .assertTextEquals("This torrent has no trackers. Trackerless torrents come in a later version.")
            onNodeWithTag("torrent-error").assertDoesNotExist()
            onNodeWithTag("preview-name").assertDoesNotExist()
            onNodeWithTag("torrent-file").assertDoesNotExist()
            onNodeWithTag("download").assertIsNotEnabled()
        }

    @Test
    fun theRefusalGoesAwayOnTheNextGoodTorrentAndOnTyping() =
        runComposeUiTest {
            home()
            openDialog()
            pickTorrent(result = TorrentResult.Ok(TorrentMeta("Show", listOf(TorrentFile("a.bin", 5)))))
            pickTorrent()
            onNodeWithTag("torrent-refused").assertDoesNotExist()
            onNodeWithTag("preview-name").assertTextEquals("Show")
            pickTorrent(other, TorrentResult.Ok(TorrentMeta("Bare", listOf(TorrentFile("a.bin", 5)))))
            onNodeWithTag("torrent-refused").assertIsDisplayed()
            onNodeWithTag("url-field").performTextInput("x")
            onNodeWithTag("torrent-refused").assertDoesNotExist()
        }

    @Test
    fun aTorrentThatIsRefusedAfterAGoodOneTakesTheGoodOneOutSoDownloadCannotAddIt() =
        runComposeUiTest {
            home()
            openDialog()
            pickTorrent()
            onNodeWithTag("download").assertIsEnabled()
            pickTorrent(other, TorrentResult.Ok(TorrentMeta("Bare", listOf(TorrentFile("a.bin", 5)))))
            onNodeWithTag("torrent-refused").assertIsDisplayed()
            onNodeWithTag("preview-name").assertDoesNotExist()
            onNodeWithTag("torrent-file").assertDoesNotExist()
            onNodeWithTag("download").assertIsNotEnabled()
        }

    @Test
    fun aMagnetShowsNoTreeAndNoHintBeforeItsMetadataIsKnown() =
        runComposeUiTest {
            home()
            openDialog()
            onNodeWithTag("url-field").performTextInput(MAGNET_LINK)
            onNodeWithTag("preview-name").assertTextEquals("Sintel")
            onNodeWithTag("torrent-tree").assertDoesNotExist()
            onNodeWithTag("file-Sintel.mp4").assertDoesNotExist()
            onNodeWithTag("torrent-hint").assertDoesNotExist()
        }

    @Test
    fun aSingleFileTorrentShowsItsOwnBadge() =
        runComposeUiTest {
            home()
            openDialog()
            pickTorrent(
                result =
                    TorrentResult.Ok(
                        TorrentMeta("ubuntu.iso", listOf(TorrentFile("ubuntu.iso", 5_000_000_000)), isSingle = true, trackers = tr),
                    ),
            )
            onNodeWithTag("preview-badge").assertTextEquals("ISO")
            onNodeWithTag("preview-detail").assertTextEquals("1 file · 4.7 GB")
            onNodeWithTag("download").assertIsEnabled()
        }

    @Test
    fun aFolderTorrentWithOneEntryKeepsTheDirBadge() =
        runComposeUiTest {
            home()
            openDialog()
            pickTorrent(result = TorrentResult.Ok(TorrentMeta("Show", listOf(TorrentFile("episode.mkv", 5_000_000)), trackers = tr)))
            onNodeWithTag("preview-badge").assertTextEquals("DIR")
        }

    @Test
    fun downloadingATorrentAddsATorrentRowWithAllItsBytes() =
        runComposeUiTest {
            home()
            openDialog()
            pickTorrent()
            onNodeWithTag("download").performClick()
            onNodeWithTag("new-download-dialog").assertDoesNotExist()
            waitUntilRow()
            onNodeWithTag("row-1").assertIsDisplayed()
            onNodeWithText("Show").assertIsDisplayed()
            onNodeWithTag("badge-1").assertTextEquals("TOR")
            // The row is added once the app has its copy of the file; the bytes are the whole torrent's.
            onNodeWithText("0% · 0 B of 6.7 MB", substring = true).assertIsDisplayed()
        }

    /** The row appears after the copy of the `.torrent` was written on an IO thread. */
    private fun ComposeUiTest.waitUntilRow() {
        val end = System.nanoTime() + 5_000_000_000L
        while (runCatching { onNodeWithTag("row-1").assertIsDisplayed() }.isFailure) {
            check(System.nanoTime() < end) { "timed out waiting for the row" }
            mainClock.advanceTimeByFrame()
            Thread.sleep(5)
        }
    }

    @Test
    fun anInvalidTorrentShowsTheErrorAndKeepsThePreviousContent() =
        runComposeUiTest {
            home()
            openDialog()
            onNodeWithTag("url-field").performTextInput("https://example.com/files/archive.zip")
            pickTorrent(bad, TorrentResult.Err("Too large"))
            onNodeWithTag("torrent-error").assertTextEquals("Not a valid .torrent file")
            onNodeWithTag("torrent-error-reason").assertTextEquals("Too large")
            onNodeWithTag("preview-name").assertTextEquals("archive.zip")
            onNodeWithTag("torrent-file").assertDoesNotExist()
            onNodeWithTag("download").assertIsEnabled()
        }

    @Test
    fun anInvalidTorrentTakesAPreviouslyLoadedOneOutSoDownloadCannotAddIt() =
        runComposeUiTest {
            home()
            openDialog()
            pickTorrent()
            onNodeWithTag("download").assertIsEnabled()
            pickTorrent(bad, TorrentResult.Err("Not bencoded"))
            onNodeWithTag("torrent-error-reason").assertTextEquals("Not bencoded")
            onNodeWithTag("torrent-file").assertDoesNotExist()
            onNodeWithTag("preview-name").assertDoesNotExist()
            onNodeWithTag("download").assertIsNotEnabled()
        }

    @Test
    fun theErrorGoesAwayOnTheNextGoodTorrentOnClearAndOnTyping() =
        runComposeUiTest {
            home()
            openDialog()
            pickTorrent(bad, TorrentResult.Err("Too large"))
            pickTorrent()
            onNodeWithTag("torrent-error").assertDoesNotExist()
            onNodeWithTag("torrent-clear").performClick()
            onNodeWithTag("torrent-error").assertDoesNotExist()
            pickTorrent(bad, TorrentResult.Err("Too large"))
            onNodeWithTag("url-field").performTextInput("x")
            onNodeWithTag("torrent-error").assertDoesNotExist()
        }

    @Test
    fun clearingTheTorrentReturnsToTheEmptyDialog() =
        runComposeUiTest {
            home()
            openDialog()
            pickTorrent()
            onNodeWithTag("torrent-clear").performClick()
            onNodeWithTag("torrent-file").assertDoesNotExist()
            onNodeWithTag("preview-name").assertDoesNotExist()
            onNodeWithText("Paste a link or magnet").assertIsDisplayed()
            onNodeWithTag("download").assertIsNotEnabled()
        }

    @Test
    fun aSecondTorrentReplacesTheFirstWithAllItsFilesTicked() =
        runComposeUiTest {
            home()
            openDialog()
            pickTorrent()
            pickTorrent(
                other,
                TorrentResult.Ok(TorrentMeta("Other", listOf(TorrentFile("x/y.bin", 10), TorrentFile("z.bin", 20)), trackers = tr)),
            )
            onNodeWithTag("torrent-file").assertTextEquals("other.torrent")
            onNodeWithTag("preview-name").assertTextEquals("Other")
            onNodeWithTag("file-x/y.bin").assertIsOn()
            onNodeWithTag("file-z.bin").assertIsOn()
            onNodeWithTag("file-notes.txt").assertDoesNotExist()
        }

    @Test
    fun aTenThousandFileTorrentOpensAndScrollsToTheEnd() =
        runComposeUiTest {
            home()
            openDialog()
            val files = List(10_000) { TorrentFile("dir${it % 50}/file-$it.bin", 1_000L + it) }
            pickTorrent(result = TorrentResult.Ok(TorrentMeta("Big", files, trackers = tr)))
            onNodeWithTag("preview-detail").assertTextEquals("10000 files · ${formatBytes(files.sumOf { it.size })}")
            onNodeWithTag("torrent-tree").performScrollToIndex(10_000)
            onNodeWithTag("file-dir49/file-9999.bin").assertIsDisplayed().assertIsOn()
            onNodeWithTag("download").assertIsEnabled()
        }

    @Test
    fun aSlowReadShowsReadingUntilItFinishes() =
        runComposeUiTest {
            mainClock.autoAdvance = false
            val gate = CompletableDeferred<Unit>()
            loader.gate = gate
            home()
            mainClock.advanceTimeByFrame()
            openDialog()
            mainClock.advanceTimeByFrame()
            loader.results[show] = TorrentResult.Ok(showMeta)
            picker.torrent = show
            onNodeWithTag("open-torrent").performClick()
            mainClock.advanceTimeBy(100)
            onNodeWithTag("torrent-reading").assertDoesNotExist()
            mainClock.advanceTimeBy(150)
            onNodeWithTag("torrent-reading").assertTextEquals("Reading…")
            onNodeWithTag("preview-name").assertDoesNotExist()
            gate.complete(Unit)
            mainClock.advanceTimeBy(50)
            onNodeWithTag("torrent-reading").assertDoesNotExist()
            onNodeWithTag("preview-name").assertTextEquals("Show")
        }

    @Test
    fun escapeStillClosesTheDialogOnceATorrentIsLoaded() =
        runComposeUiTest {
            home()
            openDialog()
            pickTorrent()
            onRoot().performKeyInput { pressKey(Key.Escape) }
            onNodeWithTag("new-download-dialog").assertDoesNotExist()
        }

    @Test
    fun closingAProxyPopoverWithATorrentLoadedKeepsTheDialogUsable() =
        runComposeUiTest {
            home()
            openDialog()
            pickTorrent()
            onNodeWithTag("proxy-chip-wifi").performClick()
            onNodeWithTag("proxy-popover").assertIsDisplayed()
            onRoot().performKeyInput { pressKey(Key.Escape) }
            onNodeWithTag("proxy-popover").assertDoesNotExist()
            onNodeWithTag("torrent-file").assertTextEquals("show.torrent")
            onRoot().performKeyInput { pressKey(Key.Escape) }
            onNodeWithTag("new-download-dialog").assertDoesNotExist()
        }

    @Test
    fun aFastReadNeverShowsReading() =
        runComposeUiTest {
            home()
            openDialog()
            pickTorrent()
            mainClock.advanceTimeBy(1_000)
            onNodeWithTag("torrent-reading").assertDoesNotExist()
            onNodeWithTag("preview-name").assertTextEquals("Show")
        }

    private fun ComposeUiTest.slowPick(
        path: Path,
        result: TorrentResult,
        gate: CompletableDeferred<Unit>,
    ) {
        loader.gate = gate
        loader.ignoreCancel = true
        loader.results[path] = result
        picker.torrent = path
        onNodeWithTag("open-torrent").performClick()
        mainClock.advanceTimeBy(50)
    }

    @Test
    fun clearingTheTorrentWhileANewReadIsInFlightDiscardsTheLateResult() =
        runComposeUiTest {
            mainClock.autoAdvance = false
            home()
            mainClock.advanceTimeByFrame()
            openDialog()
            mainClock.advanceTimeByFrame()
            loader.results[show] = TorrentResult.Ok(showMeta)
            picker.torrent = show
            onNodeWithTag("open-torrent").performClick()
            mainClock.advanceTimeBy(50)
            onNodeWithTag("torrent-file").assertTextEquals("show.torrent")
            val gate = CompletableDeferred<Unit>()
            slowPick(other, TorrentResult.Ok(TorrentMeta("Other", listOf(TorrentFile("z.bin", 20)), trackers = tr)), gate)
            onNodeWithTag("torrent-clear").performClick()
            mainClock.advanceTimeBy(300)
            onNodeWithTag("torrent-reading").assertDoesNotExist()
            gate.complete(Unit)
            mainClock.advanceTimeBy(100)
            onNodeWithTag("torrent-file").assertDoesNotExist()
            onNodeWithTag("preview-name").assertDoesNotExist()
            onNodeWithText("Paste a link or magnet").assertIsDisplayed()
        }

    @Test
    fun typingALinkWhileAReadIsInFlightKeepsTheLink() =
        runComposeUiTest {
            mainClock.autoAdvance = false
            home()
            mainClock.advanceTimeByFrame()
            openDialog()
            mainClock.advanceTimeByFrame()
            val gate = CompletableDeferred<Unit>()
            slowPick(show, TorrentResult.Ok(showMeta), gate)
            onNodeWithTag("url-field").performTextInput("https://example.com/files/archive.zip")
            mainClock.advanceTimeBy(300)
            onNodeWithTag("torrent-reading").assertDoesNotExist()
            gate.complete(Unit)
            mainClock.advanceTimeBy(100)
            onNodeWithTag("torrent-file").assertDoesNotExist()
            onNodeWithTag("preview-name").assertTextEquals("archive.zip")
            onNodeWithTag("url-field").assertTextEquals("https://example.com/files/archive.zip")
        }

    @Test
    fun aLateErrorDoesNotAppearAfterTypingALink() =
        runComposeUiTest {
            mainClock.autoAdvance = false
            home()
            mainClock.advanceTimeByFrame()
            openDialog()
            mainClock.advanceTimeByFrame()
            val gate = CompletableDeferred<Unit>()
            slowPick(bad, TorrentResult.Err("Too large"), gate)
            onNodeWithTag("url-field").performTextInput("x")
            gate.complete(Unit)
            mainClock.advanceTimeBy(100)
            onNodeWithTag("torrent-error").assertDoesNotExist()
        }

    @Test
    fun replacingASlowReadKeepsReadingShownAndOnlyTheNewestResultWins() =
        runComposeUiTest {
            mainClock.autoAdvance = false
            home()
            mainClock.advanceTimeByFrame()
            openDialog()
            mainClock.advanceTimeByFrame()
            val first = CompletableDeferred<Unit>()
            slowPick(show, TorrentResult.Ok(showMeta), first)
            mainClock.advanceTimeBy(200)
            onNodeWithTag("torrent-reading").assertTextEquals("Reading…")
            val second = CompletableDeferred<Unit>()
            slowPick(other, TorrentResult.Ok(TorrentMeta("Other", listOf(TorrentFile("z.bin", 20)), trackers = tr)), second)
            mainClock.advanceTimeBy(50)
            // The cancelled first read must not hide the second read's state.
            onNodeWithTag("torrent-reading").assertTextEquals("Reading…")
            first.complete(Unit)
            mainClock.advanceTimeBy(50)
            onNodeWithTag("torrent-file").assertDoesNotExist()
            onNodeWithTag("torrent-reading").assertTextEquals("Reading…")
            second.complete(Unit)
            mainClock.advanceTimeBy(50)
            onNodeWithTag("torrent-reading").assertDoesNotExist()
            onNodeWithTag("torrent-file").assertTextEquals("other.torrent")
        }

    private val fiveFiles =
        TorrentMeta(
            "Five",
            listOf(
                TorrentFile("a/one.bin", 1_000_000),
                TorrentFile("a/two.bin", 2_000_000),
                TorrentFile("b/three.bin", 3_000_000),
                TorrentFile("four.bin", 4_000_000),
                TorrentFile("five.bin", 5_000_000),
            ),
            trackers = tr,
        )

    @Test
    fun theDetailOfATorrentShowsAllItsFiles() =
        runComposeUiTest {
            home()
            openDialog()
            pickTorrent(result = TorrentResult.Ok(fiveFiles))
            onNodeWithTag("download").performClick()
            waitUntilRow()
            onNodeWithTag("chevron-1").performClick()
            onNodeWithTag("detail-files").assertTextEquals("5 files")
            onNodeWithTag("detail-files").performClick()
            onNodeWithTag("detail-file-0").assert(hasText("a/one.bin", substring = true))
            onNodeWithTag("detail-file-4").assert(hasText("five.bin", substring = true))
            onNodeWithText("Sintel.mp4").assertDoesNotExist()
        }

    @Test
    fun theDetailOfASingleFileTorrentSaysOneFile() =
        runComposeUiTest {
            home()
            openDialog()
            pickTorrent(
                result =
                    TorrentResult.Ok(
                        TorrentMeta("ubuntu.iso", listOf(TorrentFile("ubuntu.iso", 5_000_000)), isSingle = true, trackers = tr),
                    ),
            )
            onNodeWithTag("download").performClick()
            waitUntilRow()
            onNodeWithTag("chevron-1").performClick()
            onNodeWithTag("detail-files").assertTextEquals("1 file")
        }

    @Test
    fun theSaveFolderStartsAsDownloadsUnderHome() =
        runComposeUiTest {
            home()
            openDialog()
            onNodeWithTag("save-folder").assertTextEquals("~/Downloads")
        }

    @Test
    fun changeShowsThePickedFolderWithHomeAsTilde() =
        runComposeUiTest {
            home()
            openDialog()
            picker.folder = "$HOME/Movies"
            onNodeWithTag("save-change").performClick()
            assertEquals("$HOME/Downloads", picker.lastCurrent)
            onNodeWithTag("save-folder").assertTextEquals("~/Movies")
        }

    @Test
    fun aFolderOutsideHomeIsShownInFull() =
        runComposeUiTest {
            home()
            openDialog()
            picker.folder = "/Volumes/Media/Shows"
            onNodeWithTag("save-change").performClick()
            onNodeWithTag("save-folder").assertTextEquals("/Volumes/Media/Shows")
        }

    @Test
    fun cancellingTheFolderPickerKeepsTheFolder() =
        runComposeUiTest {
            home()
            openDialog()
            picker.folder = "$HOME/Movies"
            onNodeWithTag("save-change").performClick()
            picker.folder = null
            onNodeWithTag("save-change").performClick()
            assertEquals(2, picker.folderPicks)
            assertEquals("$HOME/Movies", picker.lastCurrent)
            onNodeWithTag("save-folder").assertTextEquals("~/Movies")
        }

    @Test
    fun theChosenFolderCarriesIntoTheNextDialogAndTheRowsDetail() =
        runComposeUiTest {
            home()
            openDialog()
            picker.folder = "$HOME/Movies"
            onNodeWithTag("save-change").performClick()
            onNodeWithTag("url-field").performTextInput("https://example.com/a.zip")
            onNodeWithTag("download").performClick()
            openDialog()
            onNodeWithTag("save-folder").assertTextEquals("~/Movies")
            onNodeWithTag("cancel").performClick()
            onNodeWithTag("chevron-1").performClick()
            onNodeWithText("~/Movies").assertIsDisplayed()
            onNodeWithText("~/Downloads").assertDoesNotExist()
        }

    @Test
    fun aFolderPickedThenCancelledLeavesTheNextDialogsDefaultUnchanged() =
        runComposeUiTest {
            home()
            openDialog()
            picker.folder = "$HOME/Movies"
            onNodeWithTag("save-change").performClick()
            onNodeWithTag("save-folder").assertTextEquals("~/Movies")
            onNodeWithTag("cancel").performClick()
            openDialog()
            onNodeWithTag("save-folder").assertTextEquals("~/Downloads")
        }

    @Test
    fun aFolderPickedThenDownloadBecomesTheNextDialogsDefault() =
        runComposeUiTest {
            home()
            openDialog()
            picker.folder = "$HOME/Movies"
            onNodeWithTag("save-change").performClick()
            onNodeWithTag("url-field").performTextInput("https://example.com/a.zip")
            onNodeWithTag("download").performClick()
            openDialog()
            onNodeWithTag("save-folder").assertTextEquals("~/Movies")
        }

    @Test
    fun aRowAddedWithTheDefaultFolderShowsDownloadsInItsDetail() =
        runComposeUiTest {
            home()
            openDialog()
            onNodeWithTag("url-field").performTextInput("https://example.com/a.zip")
            onNodeWithTag("download").performClick()
            onNodeWithTag("chevron-1").performClick()
            onNodeWithText("~/Downloads").assertIsDisplayed()
        }

    @Test
    fun aFinishedRowsHeaderShowsItsFolder() =
        runComposeUiTest {
            val base = detailSample()
            val state = base.copy(downloads = base.downloads.map { if (it.id == "2") it.copy(folder = "$HOME/Docs/Reports") else it })
            setContent { HomeScreen(state, true, clipboardText = { null }, files = env) }
            onNodeWithTag("chevron-2").performClick()
            onNodeWithTag("detail-subtitle").assertTextEquals("5.0 MB · ~/Docs/Reports")
        }

    @Test
    fun aFinishedRowWithoutAFolderShowsTheDefault() =
        runComposeUiTest {
            setContent { HomeScreen(detailSample(), true, clipboardText = { null }, files = env) }
            onNodeWithTag("chevron-2").performClick()
            onNodeWithTag("detail-subtitle").assertTextEquals("5.0 MB · ~/Downloads")
        }

    @Test
    fun eachRowKeepsTheFolderItWasAddedWith() =
        runComposeUiTest {
            home()
            openDialog()
            onNodeWithTag("url-field").performTextInput("https://example.com/a.zip")
            onNodeWithTag("download").performClick()
            openDialog()
            picker.folder = "$HOME/Movies"
            onNodeWithTag("save-change").performClick()
            onNodeWithTag("url-field").performTextInput("https://example.com/b.zip")
            onNodeWithTag("download").performClick()
            onNodeWithTag("chevron-1").performClick()
            onNodeWithText("~/Downloads").assertIsDisplayed()
            onNodeWithText("~/Movies").assertDoesNotExist()
            onNodeWithTag("detail-back").performClick()
            onNodeWithTag("chevron-2").performClick()
            onNodeWithText("~/Movies").assertIsDisplayed()
            onNodeWithText("~/Downloads").assertDoesNotExist()
        }

    @Test
    fun aMagnetCanBeDownloadedWithNoFileToTick() =
        runComposeUiTest {
            home()
            openDialog()
            onNodeWithTag("url-field").performTextInput(MAGNET_LINK)
            onNodeWithTag("download").assertIsEnabled()
        }

    @Test
    fun theDialogCanStartFromATorrentPath() =
        runComposeUiTest {
            loader.results[show] = TorrentResult.Ok(showMeta)
            setContent {
                NewDownloadDialog(
                    initialText = "",
                    networks = emptySample().networks,
                    onCancel = {},
                    onDownload = { _, _, _, _ -> },
                    files = env,
                    saveFolder = "$HOME/Downloads",
                    initialTorrentPath = show,
                )
            }
            waitForIdle()
            onNodeWithTag("torrent-file").assertTextEquals("show.torrent")
            onNodeWithTag("preview-name").assertTextEquals("Show")
        }

    // Drag and drop. The window's real drop target can't be driven here; tests push events into the controller.

    private fun ComposeUiTest.hover(on: Boolean) {
        runOnIdle { env.drops.hover(on) }
        waitForIdle()
    }

    private fun ComposeUiTest.drop(items: DropItems) {
        runOnIdle { env.drops.drop(items) }
        waitForIdle()
    }

    private fun ComposeUiTest.dropFiles(vararg paths: Path) = drop(DropItems(paths.toList(), null))

    private fun ComposeUiTest.dropText(text: String) = drop(DropItems(emptyList(), text))

    private val tooManyOrWrong = "Only links and .torrent files can be added"

    @Test
    fun theOverlayShowsWhileADragIsOverTheWindowAndHidesOnLeave() =
        runComposeUiTest {
            home()
            onNodeWithTag("drop-overlay").assertDoesNotExist()
            hover(true)
            onNodeWithTag("drop-overlay").assertIsDisplayed()
            onNodeWithText("Drop to add").assertIsDisplayed()
            hover(false)
            onNodeWithTag("drop-overlay").assertDoesNotExist()
        }

    @Test
    fun theOverlayHidesOnDrop() =
        runComposeUiTest {
            home()
            hover(true)
            dropText("hello")
            onNodeWithTag("drop-overlay").assertDoesNotExist()
        }

    @Test
    fun aDroppedMagnetOpensNewDownloadFilledIn() =
        runComposeUiTest {
            home()
            dropText(MAGNET_LINK)
            onNodeWithTag("new-download-dialog").assertIsDisplayed()
            onNodeWithTag("url-field").assertTextEquals(MAGNET_LINK)
            onNodeWithTag("preview-name").assertTextEquals("Sintel")
            onNodeWithTag("notice").assertDoesNotExist()
        }

    @Test
    fun aDroppedLinkOpensNewDownloadFilledIn() =
        runComposeUiTest {
            home()
            dropText("https://example.com/a.zip")
            onNodeWithTag("url-field").assertTextEquals("https://example.com/a.zip")
            onNodeWithTag("preview-name").assertTextEquals("a.zip")
        }

    @Test
    fun aDroppedTorrentOpensNewDownloadWithThePreview() =
        runComposeUiTest {
            loader.results[show] = TorrentResult.Ok(showMeta)
            home()
            dropFiles(show)
            assertEquals(listOf(show), loader.loaded)
            onNodeWithTag("torrent-file").assertTextEquals("show.torrent")
            onNodeWithTag("preview-name").assertTextEquals("Show")
            onNodeWithTag("file-Season 1/E01.mkv").assertIsOn()
            onNodeWithTag("notice").assertDoesNotExist()
        }

    @Test
    fun anInvalidDroppedTorrentOpensNewDownloadWithTheError() =
        runComposeUiTest {
            loader.results[bad] = TorrentResult.Err("Not bencoded")
            home()
            dropFiles(bad)
            onNodeWithTag("new-download-dialog").assertIsDisplayed()
            onNodeWithTag("torrent-error").assertTextEquals("Not a valid .torrent file")
            onNodeWithTag("torrent-error-reason").assertTextEquals("Not bencoded")
        }

    @Test
    fun twoDroppedTorrentsUseTheFirstAndSayOnlyTheFirstWasAdded() =
        runComposeUiTest {
            loader.results[show] = TorrentResult.Ok(showMeta)
            home()
            dropFiles(show, other)
            assertEquals(listOf(show), loader.loaded)
            onNodeWithTag("torrent-file").assertTextEquals("show.torrent")
            onNodeWithTag("notice").assertTextEquals("Only the first item was added")
        }

    @Test
    fun aTorrentPlusLinkTextUsesTheTorrentAndSaysOnlyTheFirstWasAdded() =
        runComposeUiTest {
            loader.results[show] = TorrentResult.Ok(showMeta)
            home()
            drop(DropItems(listOf(show), "https://example.com/a.zip"))
            onNodeWithTag("torrent-file").assertTextEquals("show.torrent")
            onNodeWithTag("notice").assertTextEquals("Only the first item was added")
        }

    @Test
    fun severalDroppedLinksUseTheFirstAndSayOnlyTheFirstWasAdded() =
        runComposeUiTest {
            home()
            dropText("https://a.example/one.zip\nhttps://b.example/two.zip")
            onNodeWithTag("url-field").assertTextEquals("https://a.example/one.zip")
            onNodeWithTag("notice").assertTextEquals("Only the first item was added")
        }

    @Test
    fun aDroppedPngShowsTheMessageAndOpensNothing() =
        runComposeUiTest {
            home()
            dropFiles(Path.of("/t/pic.png"))
            onNodeWithTag("notice").assertTextEquals(tooManyOrWrong)
            onNodeWithTag("new-download-dialog").assertDoesNotExist()
            assertEquals(emptyList(), loader.loaded)
        }

    @Test
    fun droppedPlainTextShowsTheMessageAndOpensNothing() =
        runComposeUiTest {
            home()
            dropText("just some words")
            onNodeWithTag("notice").assertTextEquals(tooManyOrWrong)
            onNodeWithTag("new-download-dialog").assertDoesNotExist()
        }

    @Test
    fun theNoticeHidesAfterThreeSeconds() =
        runComposeUiTest {
            mainClock.autoAdvance = false
            home()
            mainClock.advanceTimeByFrame()
            runOnIdle { env.drops.drop(DropItems(listOf(Path.of("/t/pic.png")), null)) }
            mainClock.advanceTimeBy(100)
            onNodeWithTag("notice").assertTextEquals(tooManyOrWrong)
            mainClock.advanceTimeBy(2_800)
            onNodeWithTag("notice").assertIsDisplayed()
            mainClock.advanceTimeBy(300)
            onNodeWithTag("notice").assertDoesNotExist()
        }

    @Test
    fun aNewMessageRestartsTheThreeSecondTimer() =
        runComposeUiTest {
            mainClock.autoAdvance = false
            home()
            mainClock.advanceTimeByFrame()
            runOnIdle { env.drops.drop(DropItems(listOf(Path.of("/t/pic.png")), null)) }
            mainClock.advanceTimeBy(2_000)
            runOnIdle { env.drops.drop(DropItems(listOf(Path.of("/t/pic2.png")), null)) }
            mainClock.advanceTimeBy(2_000)
            onNodeWithTag("notice").assertIsDisplayed()
            mainClock.advanceTimeBy(1_200)
            onNodeWithTag("notice").assertDoesNotExist()
        }

    @Test
    fun aDropWithTheDialogOpenIsIgnored() =
        runComposeUiTest {
            home()
            openDialog()
            onNodeWithTag("url-field").performTextInput("https://example.com/mine.zip")
            hover(true)
            onNodeWithTag("drop-overlay").assertDoesNotExist()
            dropText("https://example.com/other.zip")
            dropFiles(Path.of("/t/pic.png"))
            onNodeWithTag("url-field").assertTextEquals("https://example.com/mine.zip")
            onNodeWithTag("notice").assertDoesNotExist()
            onNodeWithTag("drop-overlay").assertDoesNotExist()
        }

    @Test
    fun aDropWithSettingsOpenIsIgnored() =
        runComposeUiTest {
            home()
            onNodeWithTag("network-chip").performClick()
            onNodeWithTag("networks-limits-link").performClick()
            onNodeWithTag("settings-page").assertIsDisplayed()
            hover(true)
            onNodeWithTag("drop-overlay").assertDoesNotExist()
            dropText("https://example.com/other.zip")
            onNodeWithTag("new-download-dialog").assertDoesNotExist()
            onNodeWithTag("notice").assertDoesNotExist()
        }

    @Test
    fun aDropWithTheRemoveDialogOpenIsIgnored() =
        runComposeUiTest {
            home(detailSample())
            onNodeWithTag("select-1").performClick()
            onNodeWithTag("bar-remove").performClick()
            onNodeWithTag("remove-dialog").assertIsDisplayed()
            hover(true)
            onNodeWithTag("drop-overlay").assertDoesNotExist()
            dropText("https://example.com/other.zip")
            onNodeWithTag("new-download-dialog").assertDoesNotExist()
            onNodeWithTag("notice").assertDoesNotExist()
        }

    @Test
    fun aDropWhileTheDetailViewIsShownIsIgnored() =
        runComposeUiTest {
            home(detailSample())
            onNodeWithTag("chevron-1").performClick()
            hover(true)
            onNodeWithTag("drop-overlay").assertDoesNotExist()
            dropText("https://example.com/other.zip")
            dropFiles(Path.of("/t/pic.png"))
            onNodeWithTag("new-download-dialog").assertDoesNotExist()
            onNodeWithTag("notice").assertDoesNotExist()
            onNodeWithTag("detail-back").assertIsDisplayed()
        }

    @Test
    fun dropsWorkAgainAfterTheDialogCloses() =
        runComposeUiTest {
            home()
            openDialog()
            onNodeWithTag("cancel").performClick()
            hover(true)
            onNodeWithTag("drop-overlay").assertIsDisplayed()
            dropText("https://example.com/a.zip")
            onNodeWithTag("url-field").assertTextEquals("https://example.com/a.zip")
        }

    @Test
    fun theEmptyStateInvitesADropAndHasAButtonThatOpensTheDialog() =
        runComposeUiTest {
            home()
            onNodeWithText("No downloads yet").assertIsDisplayed()
            onNodeWithText("Paste a link, drop a .torrent, or press New download").assertIsDisplayed()
            onNodeWithTag("empty-new-download")
                .assertIsDisplayed()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            onNodeWithTag("empty-new-download").performClick()
            onNodeWithTag("new-download-dialog").assertIsDisplayed()
        }

    @Test
    fun theFilteredEmptyStateHasNoButton() =
        runComposeUiTest {
            home(populatedSample())
            onNodeWithTag("clear-finished").performClick()
            onNodeWithTag("filter").performClick()
            onNodeWithTag("filter-finished").performClick()
            onNodeWithText("Nothing here").assertIsDisplayed()
            onNodeWithTag("empty-new-download").assertDoesNotExist()
        }
}
