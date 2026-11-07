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
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import dev.anydm.engine.InspectResult
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.DownloadStatus.DOWNLOADING
import dev.anydm.engine.net.BindStatus
import dev.anydm.engine.net.BondedNetwork
import dev.anydm.engine.net.BondingState
import dev.anydm.engine.net.NetworkInfo
import dev.anydm.engine.os.LinkProbeResult
import dev.anydm.engine.protocol.StepResult
import dev.anydm.engine.queue.CommandResult
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
internal class LandingScreenTest {
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
        val navigator: Navigator,
        val window: FocusRequester,
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
        // The real window has nothing to take the focus from the field when the page opens.
        windowFocus: Boolean = true,
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
        val navigator = Navigator()
        val window = FocusRequester()
        setContent {
            // Stands in for the window, which hands every key to the shortcuts before the focused part sees it.
            Box(Modifier.focusRequester(window).focusTarget().onPreviewKeyEvent { keys.dispatch(it) }) {
                App(
                    source,
                    MemoryViewPrefs(),
                    keys,
                    files = FakeFileActions(),
                    navigator = navigator,
                    opens = opens,
                    pickers = pickers,
                    visuals = visuals,
                    onOpenLimits = { limits += it },
                )
            }
            LaunchedEffect(Unit) { if (windowFocus) window.requestFocus() }
        }
        return Shown(source, keys, opens, pickers, visuals, limits, navigator, window)
    }

    private fun ComposeUiTest.until(what: () -> Boolean) = waitUntil(timeoutMillis = 5_000) { what() }

    // The models run on their own threads: what a click changes shows a moment later.
    private fun ComposeUiTest.eventually(check: () -> Unit) = until { runCatching(check).isSuccess }

    private fun ComposeUiTest.has(text: String) =
        onAllNodes(
            androidx.compose.ui.test
                .hasText(text),
        ).fetchSemanticsNodes().isNotEmpty()

    // What is in a text field.
    private fun ComposeUiTest.typed(description: String) =
        onNodeWithContentDescription(description).fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    // The landing form

    @Test
    fun `the window opens on the landing page with the field focused and nothing to choose`() =
        runComposeUiTest {
            val shown = showApp(windowFocus = false)
            assertEquals(Route.Landing, shown.navigator.route.value)
            onNodeWithText("Paste a link: https:// or magnet:").assertIsDisplayed()
            eventually { onNodeWithContentDescription("Link").assertIsFocused() }
            onNodeWithText("Download").assertIsNotEnabled()
            onNodeWithText("Open .torrent").assertIsDisplayed()
        }

    @Test
    fun `a link on the clipboard is filled in, resolved and shown with its kind`() =
        runComposeUiTest {
            showApp { it.clipboardText = link }
            until { has("1000 B") }
            onNodeWithContentDescription("Link").assertTextEquals(link)
            onNodeWithContentDescription("File name").assertTextEquals("Project.zip")
            onNodeWithText("HTTP file").assertIsDisplayed()
            onNodeWithText("/home/me/Downloads").assertIsDisplayed()
        }

    @Test
    fun `a pending open fills the field`() =
        runComposeUiTest {
            val shown = showApp()
            shown.opens.offer(listOf(link))
            until { has("1000 B") }
            onNodeWithContentDescription("Link").assertTextEquals(link)
        }

    @Test
    fun `File Add leaves a blank field whose Download button waits for a link`() =
        runComposeUiTest {
            val shown = showApp()
            shown.opens.openBlank()
            waitForIdle()
            onNodeWithText("Paste a link: https:// or magnet:").assertIsDisplayed()
            onNodeWithText("Download").assertIsNotEnabled()
        }

    @Test
    fun `a magnet is shown as a torrent with its files as a tree and the ticks decide what is added`() =
        runComposeUiTest {
            val shown = showApp { it.inspects[magnet] = InspectResult.Inspected(sintel()) }
            shown.opens.offer(listOf(magnet))
            until { has("Sintel.mp4") }
            onNodeWithText("Torrent").assertIsDisplayed()
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
            until { shown.navigator.route.value == Route.Downloads }
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
            onNodeWithText("Open .torrent").performClick()
            until { shown.pickers.torrentAsked == 1 }
            until {
                runCatching { onNodeWithContentDescription("Link").assertTextEquals(file.toString()) }.isSuccess
            }
        }

    @Test
    fun `Change picks another folder and a cancelled picker changes nothing`() =
        runComposeUiTest {
            val shown = showApp()
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
    fun `a link that can't be read shows why and keeps Download off`() =
        runComposeUiTest {
            val shown = showApp()
            shown.source.probes["https://example.com/missing"] =
                LinkProbeResult.Failed(StepResult.Failed(null, "the server answered 404"))
            shown.opens.offer(listOf("https://example.com/missing"))
            until { has("the server answered 404") }
            onNodeWithText("Download").assertIsNotEnabled()
        }

    @Test
    fun `a link already in the list brings the duplicate warning`() =
        runComposeUiTest {
            val shown = showApp()
            shown.source.probes[link] =
                LinkProbeResult.Probed(link, DownloadKind.DIRECT, "Project.zip", 1000, true, true)
            shown.opens.offer(listOf(link))
            until { has("This is already in your list or was downloaded before.") }
        }

    @Test
    fun `Download adds the file, goes to the downloads list and leaves a blank field`() =
        runComposeUiTest {
            val shown = showApp()
            shown.opens.offer(listOf(link))
            until { has("1000 B") }
            onNodeWithText("Download").performClick()
            until { shown.source.added.isNotEmpty() }
            until { shown.navigator.route.value == Route.Downloads }
            assertEquals(
                listOf(link),
                shown.source.added
                    .single()
                    .mirrors,
            )
            shown.navigator.landing()
            until { has("Paste a link: https:// or magnet:") }
            assertEquals(1, shown.source.added.size)
        }

    @Test
    fun `Downloads on the landing page leads to the list`() =
        runComposeUiTest {
            val shown = showApp()
            onNodeWithText("Downloads (0)").performClick()
            assertEquals(Route.Downloads, shown.navigator.route.value)
        }

    // The paste shortcut

    private fun ComposeUiTest.pasteShortcut() =
        onAllNodes(isRoot())[0].performKeyInput {
            withKeyDown(if (IsMac) Key.MetaLeft else Key.CtrlLeft) { pressKey(Key.V) }
        }

    @Test
    fun `the paste shortcut fills the field from the clipboard when the field is not focused`() =
        runComposeUiTest {
            val shown = showApp()
            shown.source.clipboardText = link
            // Focus leaves the field for the window.
            runOnIdle { shown.window.requestFocus() }
            pasteShortcut()
            until { has("1000 B") }
            onNodeWithContentDescription("Link").assertTextEquals(link)
        }

    @Test
    fun `the paste shortcut does not fill the field a second time while the field has the focus`() =
        runComposeUiTest {
            val other = "https://example.com/files/Other.zip"
            val shown = showApp { it.clipboardText = link }
            until { has("1000 B") }
            onNodeWithContentDescription("Link").performClick()
            shown.source.clipboardText = other
            pasteShortcut()
            // The field takes the paste itself (from the system clipboard, empty in a test): the window's handler stood aside.
            Thread.sleep(300)
            waitForIdle()
            assertTrue(typed("Link").contains("Project.zip"))
            assertEquals(0, synchronized(shown.source.probed) { shown.source.probed.count { it == other } })
        }

    @Test
    fun `the paste shortcut leaves the link and the edited name alone while the file name has the focus`() =
        runComposeUiTest {
            val other = "https://example.com/files/Other.zip"
            val shown = showApp { it.clipboardText = link }
            until { has("1000 B") }
            onNodeWithContentDescription("File name").performTextReplacement("mine.zip")
            shown.source.clipboardText = other
            pasteShortcut()
            Thread.sleep(300)
            waitForIdle()
            // The field takes the system clipboard itself; the fake clipboard's link must not have been opened.
            onNodeWithContentDescription("Link").assertTextEquals(link)
            assertTrue(typed("File name").startsWith("mine.zip"))
            assertEquals(0, synchronized(shown.source.probed) { shown.source.probed.count { it == other } })
        }

    @Test
    fun `after a start the field is blank when the page is shown again`() =
        runComposeUiTest {
            val shown = showApp { it.clipboardText = link }
            until { has("1000 B") }
            onNodeWithText("Download").performClick()
            until { shown.navigator.route.value == Route.Downloads }
            shown.navigator.landing()
            until { has("Paste a link: https:// or magnet:") }
            Thread.sleep(300)
            waitForIdle()
            onNodeWithText("Paste a link: https:// or magnet:").assertIsDisplayed()
            onNodeWithContentDescription("Link").assertTextEquals("")
        }

    // Networks in the form

    @Test
    fun `two networks show as rows with their speed or Idle, a switch each and the note, and one subnet brings the warning`() =
        runComposeUiTest {
            val shown =
                showApp {
                    it.networks.value =
                        BondingState(
                            listOf(
                                network("en0", "Wi-Fi", "192.168.1.5", speed = 2_000_000.0),
                                network("usb0", "USB Tethering", "192.168.1.9"),
                            ),
                        )
                }
            shown.opens.openBlank()
            until { has(NETWORK_SWITCH_HINT) }
            onNodeWithTag("network-row-en0").assertIsDisplayed()
            onNodeWithTag("network-row-usb0").assertIsDisplayed()
            onNodeWithTag("network-switch-en0").assertIsOn()
            onNodeWithTag("network-switch-usb0").assertIsOn()
            onNodeWithText("Idle").assertIsDisplayed()
            onNodeWithText("WIFI · en0").assertIsDisplayed()
            until { has("Wi-Fi and USB share a subnet", substring = true) }
        }

    @Test
    fun `switching a row off sends the command and the row then says Off`() =
        runComposeUiTest {
            val shown = showApp()
            until { has(NETWORK_SWITCH_HINT) }
            onNodeWithTag("network-switch-usb0").performClick()
            until { shown.source.calls.contains("setNetworkEnabled usb0 false") }
            eventually { onNodeWithTag("network-switch-usb0").assertIsOff() }
            eventually { onNodeWithText("Off").assertIsDisplayed() }
        }

    @Test
    fun `switching an Off row back on makes it On again`() =
        runComposeUiTest {
            val shown =
                showApp {
                    it.networks.value =
                        BondingState(
                            listOf(
                                network("en0", "Wi-Fi", "192.168.1.5"),
                                BondedNetwork(
                                    NetworkInfo("usb0", "USB Tethering", true, listOf("172.20.10.2")),
                                    false,
                                    BindStatus.ISOLATED,
                                    null,
                                    false,
                                ),
                            ),
                        )
                }
            until { has("Off") }
            onNodeWithTag("network-switch-usb0").assertIsOff()
            onNodeWithTag("network-switch-usb0").performClick()
            until { shown.source.calls.contains("setNetworkEnabled usb0 true") }
            eventually { onNodeWithTag("network-switch-usb0").assertIsOn() }
            eventually { assertTrue(!has("Off")) }
        }

    @Test
    fun `a network the OS lists down says Not connected`() =
        runComposeUiTest {
            showApp {
                it.networks.value =
                    BondingState(
                        listOf(
                            network("en0", "Wi-Fi", "192.168.1.5"),
                            BondedNetwork(
                                NetworkInfo("usb0", "USB Tethering", false, listOf("172.20.10.2")),
                                true,
                                BindStatus.ISOLATED,
                                null,
                                false,
                            ),
                        ),
                    )
            }
            until { has("Not connected") }
        }

    @Test
    fun `a single network still shows its row`() =
        runComposeUiTest {
            showApp { it.networks.value = BondingState(listOf(network("en0", "Wi-Fi", "192.168.1.5"))) }
            until { has(NETWORK_SWITCH_HINT) }
            onAllNodes(hasTestTag("network-row-en0")).assertCountEquals(1)
        }

    @Test
    fun `no networks shows no networks section`() =
        runComposeUiTest {
            showApp { it.networks.value = BondingState(emptyList()) }
            until { has("Save to") }
            assertTrue(!has(NETWORK_SWITCH_HINT))
        }

    @Test
    fun `a switch the engine refuses shows the message and the switch stays`() =
        runComposeUiTest {
            val shown = showApp { it.results["setNetworkEnabled usb0 false"] = CommandResult.NotFound }
            until { has(NETWORK_SWITCH_HINT) }
            onNodeWithTag("network-switch-usb0").performClick()
            until { has("The network could not be switched.") }
            onNodeWithTag("network-switch-usb0").assertIsOn()
            assertTrue(shown.source.calls.contains("setNetworkEnabled usb0 false"))
        }

    @Test
    fun `with every network off the form says downloads use the system's default connection`() =
        runComposeUiTest {
            showApp()
            until { has(NETWORK_SWITCH_HINT) }
            assertTrue(!has("No network is on, so downloads use your system's default connection."))
            onNodeWithTag("network-switch-en0").performClick()
            onNodeWithTag("network-switch-usb0").performClick()
            until { has("No network is on, so downloads use your system's default connection.") }
        }

    @Test
    fun `the Speed and data limits link asks for the general limits page`() =
        runComposeUiTest {
            val shown = showApp()
            until { has(NETWORK_SWITCH_HINT) }
            onNodeWithText("Speed & data limits").performClick()
            until { shown.limits == listOf<String?>(null) }
        }

    @Test
    fun `renaming a network from its row`() =
        runComposeUiTest {
            val shown = showApp()
            shown.opens.openBlank()
            until { has("Save to") }
            onAllNodes(
                androidx.compose.ui.test
                    .hasContentDescription("Edit USB"),
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
    fun `backspace reaches the link field`() =
        runComposeUiTest {
            val shown = showApp()
            shown.opens.offer(listOf("https://example.com/a"))
            until { has("Save to") }
            onNodeWithContentDescription("Link").performClick()
            onAllNodes(isRoot())[0].performKeyInput { pressKey(Key.Backspace) }
            eventually { onNodeWithContentDescription("Link").assertTextEquals("https://example.com/") }
        }

    private fun ComposeUiTest.has(
        text: String,
        substring: Boolean,
    ) = onAllNodes(
        androidx.compose.ui.test
            .hasText(text, substring = substring),
    ).fetchSemanticsNodes().isNotEmpty()
}
