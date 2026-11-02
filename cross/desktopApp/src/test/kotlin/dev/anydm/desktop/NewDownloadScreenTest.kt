package dev.anydm.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.anydm.engine.InspectResult
import dev.anydm.engine.core.DownloadStatus.DOWNLOADING
import dev.anydm.engine.net.BindStatus
import dev.anydm.engine.net.BondedNetwork
import dev.anydm.engine.net.BondingState
import dev.anydm.engine.net.NetworkInfo
import dev.anydm.engine.os.LinkProbeResult
import dev.anydm.engine.protocol.StepResult
import dev.anydm.engine.store.TransferSettings
import dev.anydm.engine.torrent.TorrentFile
import dev.anydm.engine.torrent.TorrentInfo
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
internal class NewDownloadScreenTest {
    private val mib = 1024L * 1024
    private val link = "https://example.com/files/Project.zip"
    private val magnet = "magnet:?xt=urn:btih:08ada5a7a6183aae1e09d831df6748d566095a10"

    private class Shown(
        val source: FakeSource,
        val keys: WindowKeys,
        val opens: PendingOpens,
        val pickers: FakePickers,
        val visuals: NetworkVisuals,
        val limits: MutableList<String?>,
    )

    private fun network(
        key: String,
        name: String,
        address: String,
        speed: Double? = null,
    ) = BondedNetwork(NetworkInfo(key, name, true, listOf(address)), true, BindStatus.ISOLATED, speed, false)

    private fun sintel() =
        TorrentInfo(
            "Sintel",
            "08ada5a7a6183aae1e09d831df6748d566095a10",
            listOf(
                TorrentFile(0, "Sintel/Sintel.de.srt", 1_600),
                TorrentFile(1, "Sintel/Sintel.en.srt", 1_500),
                TorrentFile(2, "Sintel/Sintel.mp4", 123 * mib),
            ),
            byteArrayOf(1),
        )

    private fun ComposeUiTest.showApp(
        visuals: NetworkVisuals = NetworkVisuals(null),
        configure: (FakeSource) -> Unit = {},
    ): Shown {
        val source = FakeSource()
        source.transfer = TransferSettings(defaultSaveFolder = "/home/me/Downloads")
        source.networks.value =
            BondingState(listOf(network("en0", "Wi-Fi", "192.168.1.5"), network("usb0", "USB Tethering", "172.20.10.2")))
        configure(source)
        val keys = WindowKeys()
        val opens = PendingOpens()
        val pickers = FakePickers()
        val limits = mutableListOf<String?>()
        val window = FocusRequester()
        setContent {
            // Stands in for the window, which hands every key to the shortcuts before the focused part sees it.
            Box(Modifier.focusRequester(window).focusTarget().onPreviewKeyEvent { keys.dispatch(it) }) {
                App(
                    source,
                    MemoryViewPrefs(),
                    keys,
                    files = FakeFileActions(),
                    opens = opens,
                    pickers = pickers,
                    visuals = visuals,
                    onOpenLimits = { limits += it },
                )
            }
            LaunchedEffect(Unit) { window.requestFocus() }
        }
        return Shown(source, keys, opens, pickers, visuals, limits)
    }

    private fun ComposeUiTest.until(what: () -> Boolean) = waitUntil(timeoutMillis = 5_000) { what() }

    // The models run on their own threads: what a click changes shows a moment later.
    private fun ComposeUiTest.eventually(check: () -> Unit) = until { runCatching(check).isSuccess }

    private fun ComposeUiTest.has(text: String) =
        onAllNodes(
            androidx.compose.ui.test
                .hasText(text),
        ).fetchSemanticsNodes().isNotEmpty()

    // The dialog

    @Test
    fun `the header button opens a dialog with the link on the clipboard filled in and resolved`() =
        runComposeUiTest {
            showApp { it.clipboardText = link }
            onAllNodes(
                androidx.compose.ui.test
                    .hasText("New download"),
            )[1].performClick()
            until { has("1000 B") || has("1000 B · ") }
            onNodeWithContentDescription("Link").assertTextEquals(link)
            onNodeWithContentDescription("File name").assertTextEquals("Project.zip")
            onNodeWithText("/home/me/Downloads").assertIsDisplayed()
        }

    @Test
    fun `a pending open shows the dialog with its link`() =
        runComposeUiTest {
            val shown = showApp()
            shown.opens.offer(listOf(link))
            until { has("New download") && has("1000 B") }
            onNodeWithContentDescription("Link").assertTextEquals(link)
        }

    @Test
    fun `File Add opens a blank dialog whose Download button waits for a link`() =
        runComposeUiTest {
            val shown = showApp()
            shown.opens.openBlank()
            until {
                onAllNodes(
                    androidx.compose.ui.test
                        .hasContentDescription("Link"),
                ).fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithText("Paste a link: https:// or magnet:").assertIsDisplayed()
            onNodeWithText("Download").assertIsNotEnabled()
        }

    @Test
    fun `a torrent shows its files as a tree and the ticks decide what is added`() =
        runComposeUiTest {
            val shown = showApp { it.inspects[magnet] = InspectResult.Inspected(sintel()) }
            shown.opens.offer(listOf(magnet))
            until { has("Sintel.mp4") }
            onNodeWithText("3 files · 123.0 MB").assertIsDisplayed()
            onNodeWithContentDescription("Include Sintel.de.srt").assertIsOn()
            onNodeWithContentDescription("Include Sintel.de.srt").performClick()
            eventually { onNodeWithContentDescription("Include Sintel.de.srt").assertIsOff() }
            onNodeWithText("2 of 3 files · 123.0 MB").assertIsDisplayed()
            onNodeWithText("Download").performClick()
            until { shown.source.torrentsAdded.isNotEmpty() }
            assertEquals(
                setOf("Sintel/Sintel.en.srt", "Sintel/Sintel.mp4"),
                shown.source.torrentsAdded
                    .single()
                    .files,
            )
            until { !has("Save to") }
        }

    @Test
    fun `the folder's box ticks and clears every file`() =
        runComposeUiTest {
            val shown = showApp { it.inspects[magnet] = InspectResult.Inspected(sintel()) }
            shown.opens.offer(listOf(magnet))
            until { has("Sintel.mp4") }
            onNodeWithContentDescription("All files").performClick()
            eventually { onNodeWithContentDescription("Include Sintel.mp4").assertIsOff() }
            eventually { onNodeWithText("Download").assertIsNotEnabled() }
            onNodeWithContentDescription("All files").performClick()
            eventually { onNodeWithContentDescription("Include Sintel.mp4").assertIsOn() }
        }

    @Test
    fun `Open torrent fills the field from the picker`() =
        runComposeUiTest {
            val shown = showApp()
            val file = Files.createTempFile("anydm-open", ".torrent")
            shown.pickers.torrent = file
            shown.opens.openBlank()
            until { has("Open .torrent") }
            onNodeWithText("Open .torrent").performClick()
            until { shown.pickers.torrentAsked == 1 }
            until {
                onAllNodes(
                    androidx.compose.ui.test
                        .hasContentDescription("Link"),
                ).fetchSemanticsNodes().isNotEmpty()
            }
            waitForIdle()
            until {
                runCatching { onNodeWithContentDescription("Link").assertTextEquals(file.toString()) }.isSuccess
            }
        }

    @Test
    fun `Change picks another folder and a cancelled picker changes nothing`() =
        runComposeUiTest {
            val shown = showApp()
            shown.opens.openBlank()
            until { has("Change") }
            onNodeWithText("Change").performClick()
            until { shown.pickers.folderAsked == 1 }
            onNodeWithText("/home/me/Downloads").assertIsDisplayed()
            shown.pickers.folder = Path.of("/data/dl")
            onNodeWithText("Change").performClick()
            until { has("/data/dl") }
        }

    @Test
    fun `the clear button empties the field`() =
        runComposeUiTest {
            val shown = showApp()
            shown.opens.offer(listOf(link))
            until { has("1000 B") }
            onNodeWithContentDescription("Clear link").performClick()
            until { !has("1000 B") }
            onNodeWithText("Paste a link: https:// or magnet:").assertIsDisplayed()
        }

    @Test
    fun `a link that can't be read shows why`() =
        runComposeUiTest {
            val shown = showApp()
            shown.opens.offer(listOf("https://example.com/missing"))
            shown.source.probes["https://example.com/missing"] =
                dev.anydm.engine.os.LinkProbeResult
                    .Failed(
                        dev.anydm.engine.protocol.StepResult
                            .Failed(null, "the server answered 404"),
                    )
            until { has("the server answered 404") }
            onNodeWithText("Download").assertIsNotEnabled()
        }

    @Test
    fun `Cancel and Escape close the dialog without adding`() =
        runComposeUiTest {
            val shown = showApp()
            shown.opens.offer(listOf(link))
            until { has("1000 B") }
            onNodeWithText("Cancel").performClick()
            until { !has("Save to") }
            shown.opens.offer(listOf(link))
            until { has("Save to") }
            onAllNodes(isRoot())[0].performKeyInput { pressKey(Key.Escape) }
            until { !has("Save to") }
            assertTrue(shown.source.added.isEmpty())
        }

    @Test
    fun `Download adds the file and closes the dialog`() =
        runComposeUiTest {
            val shown = showApp()
            shown.opens.offer(listOf(link))
            until { has("1000 B") }
            onNodeWithText("Download").performClick()
            until { shown.source.added.isNotEmpty() }
            until { !has("Save to") }
            assertEquals(
                listOf(link),
                shown.source.added
                    .single()
                    .mirrors,
            )
        }

    @Test
    fun `several links stack with a counter and arrows`() =
        runComposeUiTest {
            val shown = showApp()
            shown.opens.offer(listOf(link, "https://example.com/other.zip"))
            until { has("1 of 2") }
            onNodeWithContentDescription("Next download").performClick()
            onNodeWithText("2 of 2").assertIsDisplayed()
            onNodeWithContentDescription("Link").assertTextEquals("https://example.com/other.zip")
            onNodeWithContentDescription("Previous download").performClick()
            onNodeWithContentDescription("Link").assertTextEquals(link)
        }

    // Networks in the dialog

    @Test
    fun `networks show as chips with a note, and two on one subnet bring the warning`() =
        runComposeUiTest {
            val shown =
                showApp {
                    it.networks.value =
                        BondingState(listOf(network("en0", "Wi-Fi", "192.168.1.5"), network("usb0", "USB Tethering", "192.168.1.9")))
                }
            shown.opens.openBlank()
            until { has("Networks are switched for all downloads in the Networks menu.") }
            onNodeWithContentDescription("Wi-Fi, in use").assertIsDisplayed()
            onNodeWithContentDescription("USB Tethering, in use").assertIsDisplayed()
            until { has("Wi-Fi and USB Tethering share a subnet", substring = true) }
        }

    @Test
    fun `renaming a network from its chip shows in the chip`() =
        runComposeUiTest {
            val shown = showApp()
            shown.opens.openBlank()
            until { has("Save to") }
            onAllNodes(
                androidx.compose.ui.test
                    .hasContentDescription("Edit USB Tethering"),
            )[0].performClick()
            until { has("NAME") }
            onNodeWithContentDescription("Network name").performClick()
            onNodeWithContentDescription("Rose").performClick()
            onNodeWithText("Done").performClick()
            // The save runs off the UI thread.
            eventually {
                assertEquals(
                    "rose",
                    shown.visuals.prefs.value["usb0"]
                        ?.color,
                )
            }
            assertNull(
                shown.visuals.prefs.value["usb0"]
                    ?.name,
            )
        }

    // Keys

    @Test
    fun `backspace reaches the link field while the dialog is open, though the window takes it for removing rows`() =
        runComposeUiTest {
            val shown = showApp { it.downloads.value = listOf(snapshot(1, DOWNLOADING, name = "a.bin", total = 10, downloaded = 1)) }
            // A selected row makes the window's Backspace a shortcut ("remove the selected").
            onNodeWithContentDescription("Select a.bin").performClick()
            shown.opens.offer(listOf("https://example.com/a"))
            until { has("Save to") }
            // A click in the field puts the cursor at the end of the link.
            onNodeWithContentDescription("Link").performClick()
            onAllNodes(isRoot())[0].performKeyInput { pressKey(Key.Backspace) }
            eventually { onNodeWithContentDescription("Link").assertTextEquals("https://example.com/") }
            onNodeWithText("Remove from list?").assertDoesNotExist()
        }

    private fun ComposeUiTest.has(
        text: String,
        substring: Boolean,
    ) = onAllNodes(
        androidx.compose.ui.test
            .hasText(text, substring = substring),
    ).fetchSemanticsNodes().isNotEmpty()
}
