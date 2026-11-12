package dev.anydm.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.NativeClipboard
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.anydm.engine.http.LinkProber
import dev.anydm.engine.http.ProbeOutcome
import dev.anydm.engine.http.ProbeResult
import java.awt.datatransfer.StringSelection
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class NewDownloadDialogTest {
    private val magnet = "magnet:?xt=urn:btih:08ada5a7a6183aae1e09d8"
    private val unknownNote = "size unknown · Uses one connection. This server doesn't support parallel downloads."

    private fun ComposeUiTest.home(
        state: HomeState = populatedSample(),
        clipboard: String? = null,
        prober: LinkProber? = null,
    ) = setContent {
        val env = remember { DownloadEnv.inert().let { e -> if (prober == null) e else e.copy(prober = prober) } }
        HomeScreen(state, true, clipboardText = { clipboard }, downloadEnv = env)
    }

    private fun answering(
        name: String,
        total: Long?,
        resumable: Boolean,
    ) = FakeProber(fallback = ProbeOutcome.Ok(ProbeResult(name, total, resumable)))

    /** Frames and real time pass until the preview line reads [text]: the probe answers on an IO thread after its debounce. */
    private fun ComposeUiTest.awaitDetail(text: String) {
        val end = System.nanoTime() + 5_000_000_000L
        while (runCatching { onNodeWithTag("preview-detail").assertTextEquals(text) }.isFailure) {
            check(System.nanoTime() < end) { "timed out waiting for preview detail: $text" }
            mainClock.advanceTimeByFrame()
            Thread.sleep(5)
        }
    }

    private fun ComposeUiTest.open(text: String? = null) {
        onNodeWithTag("new-download").performClick()
        if (text != null) onNodeWithTag("url-field").performTextInput(text)
    }

    private fun ComposeUiTest.paste() =
        onRoot().performKeyInput {
            // The text field maps paste to Cmd+V on macOS and Ctrl+V elsewhere; Home accepts both.
            val mod = if (System.getProperty("os.name").startsWith("Mac")) Key.MetaLeft else Key.CtrlLeft
            keyDown(mod)
            pressKey(Key.V)
            keyUp(mod)
        }

    private fun ComposeUiTest.dialogGone() = onNodeWithTag("new-download-dialog").assertDoesNotExist()

    @Test
    fun clickOpensDialogWithDownloadDisabled() =
        runComposeUiTest {
            home()
            onNodeWithTag("new-download-dialog").assertDoesNotExist()
            open()
            onNodeWithTag("new-download-dialog").assertIsDisplayed()
            onNodeWithTag("dialog-title").assertTextEquals("New download")
            onNodeWithText("Paste a link or magnet").assertIsDisplayed()
            onNodeWithTag("download").assertIsNotEnabled()
            onNodeWithTag("url-clear").assertDoesNotExist()
            onNodeWithTag("open-torrent").assertIsDisplayed()
            onNodeWithTag("save-change").assertIsDisplayed()
            onNodeWithText("~/Downloads").assertIsDisplayed()
        }

    @Test
    fun cancelClosesWithoutChangingList() =
        runComposeUiTest {
            home()
            open("https://x.test/a.zip")
            onNodeWithTag("cancel").performClick()
            dialogGone()
            onNodeWithText("DOWNLOADING 1").assertIsDisplayed()
            onNodeWithText("a.zip").assertDoesNotExist()
        }

    @Test
    fun escapeClosesWithoutChangingList() =
        runComposeUiTest {
            home()
            open("https://x.test/a.zip")
            onRoot().performKeyInput { pressKey(Key.Escape) }
            dialogGone()
            onNodeWithText("DOWNLOADING 1").assertIsDisplayed()
        }

    @Test
    fun directLinkShowsPreviewWithoutStreams() =
        runComposeUiTest {
            home(prober = answering("archive.zip", null, false))
            open("https://example.com/files/archive.zip")
            onNodeWithTag("preview-badge").assertTextEquals("ZIP")
            onNodeWithTag("preview-name").assertTextEquals("archive.zip")
            awaitDetail(unknownNote)
            onNodeWithTag("streams-auto").assertDoesNotExist()
            onNodeWithTag("download").assertIsEnabled()
            onNodeWithTag("network-Wi-Fi").assertIsDisplayed()
            onNodeWithTag("network-USB Tethering").assertIsDisplayed()
        }

    @Test
    fun resumableLinkShowsStreamsWithSingleSelection() =
        runComposeUiTest {
            home(prober = answering("Project.zip", 400L * 1024 * 1024, true))
            open("http://127.0.0.1:8765/Project.zip")
            awaitDetail("400.0 MB · Resumable")
            onNodeWithTag("streams-auto").assertIsSelected()
            onNodeWithTag("streams-8").performClick()
            onNodeWithTag("streams-8").assertIsSelected()
            for (s in listOf("auto", "4", "16", "32")) onNodeWithTag("streams-$s").assertIsNotSelected()
            onNodeWithText("per network").assertIsDisplayed()
        }

    @Test
    fun streamsHiddenWithOneNetworkEvenWhenResumable() =
        runComposeUiTest {
            home(emptySample(), prober = answering("Project.zip", 400L * 1024 * 1024, true))
            open("http://localhost/Project.zip")
            awaitDetail("400.0 MB · Resumable")
            onNodeWithTag("streams-auto").assertDoesNotExist()
            onNodeWithTag("network-Wi-Fi").assertIsDisplayed()
            onNodeWithTag("network-USB Tethering").assertDoesNotExist()
        }

    @Test
    fun magnetShowsTreeAndTicksUpdateSummary() =
        runComposeUiTest {
            home()
            open(magnet)
            onNodeWithTag("preview-name").assertTextEquals("Sintel")
            onNodeWithTag("preview-badge").assertTextEquals("DIR")
            onNodeWithTag("preview-detail").assertTextEquals("11 files · 123.3 MB")
            onNodeWithTag("folder-toggle").assertIsOn()
            onNodeWithTag("file-Sintel.mp4").assertIsOn()
            onNodeWithTag("streams-auto").assertDoesNotExist()
            onNodeWithTag("file-Sintel.mp4").performClick()
            onNodeWithTag("file-Sintel.mp4").assertIsOff()
            onNodeWithTag("folder-toggle").assertIsOff()
            onNodeWithTag("preview-detail").assertTextEquals("10 files · 15.1 KB")
            onNodeWithTag("download").assertIsEnabled()
        }

    @Test
    fun folderToggleUnticksAllThenTicksAll() =
        runComposeUiTest {
            home()
            open(magnet)
            onNodeWithTag("folder-toggle").performClick()
            onNodeWithTag("file-Sintel.mp4").assertIsOff()
            onNodeWithTag("preview-detail").assertTextEquals("0 files · 0 B")
            onNodeWithTag("download").assertIsNotEnabled()
            onNodeWithTag("folder-toggle").performClick()
            onNodeWithTag("file-Sintel.mp4").assertIsOn()
            onNodeWithTag("preview-detail").assertTextEquals("11 files · 123.3 MB")
            onNodeWithTag("download").assertIsEnabled()
        }

    @Test
    fun invalidTextHasNoPreviewAndClearEmptiesField() =
        runComposeUiTest {
            home()
            open("hello world")
            onNodeWithTag("preview-name").assertDoesNotExist()
            onNodeWithTag("download").assertIsNotEnabled()
            onNodeWithTag("url-clear").performClick()
            onNodeWithTag("url-clear").assertDoesNotExist()
            onNodeWithText("Paste a link or magnet").assertIsDisplayed()
        }

    @Test
    fun downloadClosesAndAddsRowUnderDownloading() =
        runComposeUiTest {
            home()
            open("https://example.com/files/archive.zip")
            onNodeWithTag("download").performClick()
            dialogGone()
            onNodeWithText("DOWNLOADING 2").assertIsDisplayed()
            onNodeWithText("archive.zip").assertIsDisplayed()
            onNodeWithText("2 downloading").assertIsDisplayed()
            onNodeWithTag("row-3").assertIsDisplayed()
        }

    @Test
    fun downloadFromEmptyStateReplacesEmptyMessage() =
        runComposeUiTest {
            home(emptySample())
            onNodeWithText("No downloads yet").assertIsDisplayed()
            open("https://example.com/a.iso")
            onNodeWithTag("download").performClick()
            onNodeWithText("No downloads yet").assertDoesNotExist()
            onNodeWithText("a.iso").assertIsDisplayed()
            onNodeWithText("DOWNLOADING 1").assertIsDisplayed()
            onNodeWithText("1 downloading").assertIsDisplayed()
        }

    @Test
    fun sameLinkTwiceCreatesTwoRows() =
        runComposeUiTest {
            home(emptySample())
            repeat(2) {
                open("https://example.com/a.iso")
                onNodeWithTag("download").performClick()
            }
            onNodeWithText("DOWNLOADING 2").assertIsDisplayed()
            onNodeWithTag("row-1").assertIsDisplayed()
            onNodeWithTag("row-2").assertIsDisplayed()
        }

    @Test
    fun magnetDownloadAddsOneRowNamedSintel() =
        runComposeUiTest {
            home(emptySample())
            open(magnet)
            onNodeWithTag("download").performClick()
            onNodeWithText("DOWNLOADING 1").assertIsDisplayed()
            onNodeWithText("Sintel").assertIsDisplayed()
        }

    @Test
    fun pasteShortcutOpensDialogPrefilled() =
        runComposeUiTest {
            home(clipboard = "  https://x.test/a.iso ")
            paste()
            onNodeWithTag("new-download-dialog").assertIsDisplayed()
            onNodeWithTag("preview-name").assertTextEquals("a.iso")
            onNodeWithTag("url-field").assertTextEquals("https://x.test/a.iso")
        }

    @Test
    fun pasteShortcutAcceptsMagnet() =
        runComposeUiTest {
            home(clipboard = magnet)
            paste()
            onNodeWithTag("preview-detail").assertTextEquals("11 files · 123.3 MB")
        }

    @Test
    fun pasteShortcutIgnoresOtherText() =
        runComposeUiTest {
            home(clipboard = "hello")
            paste()
            waitForIdle()
            dialogGone()
        }

    @Test
    fun pasteShortcutWorksAgainAfterClose() =
        runComposeUiTest {
            home(clipboard = "https://x.test/a.iso")
            paste()
            onNodeWithTag("cancel").performClick()
            dialogGone()
            paste()
            onNodeWithTag("new-download-dialog").assertIsDisplayed()
        }

    @Test
    fun pasteWhileDialogOpenReachesTheTextField() =
        runComposeUiTest {
            setContent {
                CompositionLocalProvider(LocalClipboard provides FakeClipboard("PASTED")) {
                    HomeScreen(populatedSample(), true, clipboardText = { "https://other.test/b.zip" })
                }
            }
            open("abc")
            paste()
            waitForIdle()
            onAllNodesWithTag("new-download-dialog").assertCountEquals(1)
            onNodeWithTag("url-field").assertTextEquals("abcPASTED")
        }

    @Test
    fun scrimBlocksClicksOnControlsBehindIt() =
        runComposeUiTest {
            home()
            open()
            onNodeWithTag("pause-1").performClick()
            onNodeWithTag("cancel").performClick()
            onNodeWithTag("speed-1").assertTextEquals("4.0 MB/s")
        }

    @Test
    fun controlsBehindTheDialogCannotTakeFocus() =
        runComposeUiTest {
            home()
            open()
            repeat(20) {
                onRoot().performKeyInput { pressKey(Key.Tab) }
                for (tag in listOf("pause-1", "new-download", "filter", "theme-toggle")) {
                    onNodeWithTag(tag).assertIsNotFocused()
                }
            }
        }

    @Test
    fun escapeClosesAfterClickingACheckbox() =
        runComposeUiTest {
            home()
            open(magnet)
            onNodeWithTag("file-Sintel.mp4").performClick()
            onRoot().performKeyInput { pressKey(Key.Escape) }
            dialogGone()
        }

    @Test
    fun downloadResetsFilterSoTheNewRowIsVisible() =
        runComposeUiTest {
            home()
            onNodeWithTag("filter").performClick()
            onNodeWithTag("filter-finished").performClick()
            open("https://example.com/a.iso")
            onNodeWithTag("download").performClick()
            onNodeWithText("a.iso").assertIsDisplayed()
            onNodeWithTag("filter").assertTextEquals("All downloads")
        }

    @Test
    fun footerStaysVisibleInShortWindow() =
        runDesktopComposeUiTest(900, 400) {
            setContent { HomeScreen(populatedSample(), true, clipboardText = { null }) }
            onNodeWithTag("new-download").performClick()
            onNodeWithTag("url-field").performTextInput(magnet)
            onNodeWithTag("dialog-title").assertIsDisplayed()
            onNodeWithTag("cancel").assertIsDisplayed()
            onNodeWithTag("download").assertIsDisplayed()
        }

    @Test
    fun theProbeShowsCheckingThenTheServersNameSizeAndResumable() =
        runComposeUiTest {
            val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
            val prober =
                FakeProber(fallback = ProbeOutcome.Ok(ProbeResult("ubuntu.iso", 6_100_000_000, true)), gate = { gate.await() })
            home(prober = prober)
            open("https://example.com/dl?id=7")
            onNodeWithTag("preview-detail").assertTextEquals("Checking link…")
            awaitDetail("Checking link…")
            val end = System.nanoTime() + 5_000_000_000L
            while (prober.probed.isEmpty() && System.nanoTime() < end) {
                mainClock.advanceTimeByFrame()
                Thread.sleep(5)
            }
            onNodeWithTag("preview-detail").assertTextEquals("Checking link…")
            onNodeWithTag("download").assertIsEnabled()
            gate.complete(Unit)
            awaitDetail("5.7 GB · Resumable")
            onNodeWithTag("preview-name").assertTextEquals("ubuntu.iso")
            onNodeWithTag("preview-badge").assertTextEquals("ISO")
        }

    @Test
    fun aFailedProbeShowsTheReasonAndDownloadStaysEnabled() =
        runComposeUiTest {
            home(prober = FakeProber(fallback = ProbeOutcome.Failed("HTTP 404")))
            open("https://example.com/missing.zip")
            awaitDetail("HTTP 404")
            onNodeWithTag("download").assertIsEnabled()
        }

    @Test
    fun changingTheLinkCancelsTheEarlierProbeAndOnlyTheNewOneAnswers() =
        runComposeUiTest {
            val prober =
                FakeProber(
                    answers =
                        java.util.concurrent.ConcurrentHashMap(
                            mapOf("https://example.com/b.zip" to ProbeOutcome.Ok(ProbeResult("b.zip", 2048, true))),
                        ),
                    gate = { url -> if (url.endsWith("a.zip")) kotlinx.coroutines.awaitCancellation() },
                )
            home(prober = prober)
            open("https://example.com/a.zip")
            val end = System.nanoTime() + 5_000_000_000L
            while (prober.probed.isEmpty() && System.nanoTime() < end) {
                mainClock.advanceTimeByFrame()
                Thread.sleep(5)
            }
            onNodeWithTag("url-field").performTextReplacement("https://example.com/b.zip")
            awaitDetail("2.0 KB · Resumable")
            check(prober.cancelled == listOf("https://example.com/a.zip")) { "cancelled: ${prober.cancelled}" }
        }

    @Test
    fun typingFastProbesOnlyTheFinalLink() =
        runComposeUiTest {
            val prober = answering("c.zip", 10, true)
            home(prober = prober)
            open("https://example.com/a.zip")
            onNodeWithTag("url-field").performTextReplacement("https://example.com/b.zip")
            onNodeWithTag("url-field").performTextReplacement("https://example.com/c.zip")
            awaitDetail("10 B · Resumable")
            check(prober.probed.toList() == listOf("https://example.com/c.zip")) { "probed: ${prober.probed}" }
        }

    @Test
    fun aMagnetLinkIsNeverProbed() =
        runComposeUiTest {
            val prober = answering("x", 1, true)
            home(prober = prober)
            open(magnet)
            onNodeWithTag("preview-detail").assertTextEquals("11 files · 123.3 MB")
            Thread.sleep(100)
            mainClock.advanceTimeBy(1_000)
            check(prober.probed.isEmpty())
        }
}

@OptIn(ExperimentalComposeUiApi::class)
private class FakeClipboard(
    private val text: String,
) : Clipboard {
    override val nativeClipboard: NativeClipboard get() = error("unused")

    override suspend fun getClipEntry(): ClipEntry = ClipEntry(StringSelection(text))

    override suspend fun setClipEntry(clipEntry: ClipEntry?) {}
}
