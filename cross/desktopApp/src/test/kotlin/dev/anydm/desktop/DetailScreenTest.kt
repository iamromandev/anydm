package dev.anydm.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.anydm.engine.DownloadSnapshot
import dev.anydm.engine.FileSnapshot
import dev.anydm.engine.TorrentActionResult
import dev.anydm.engine.TorrentDetailResult
import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadStatus.COMPLETED
import dev.anydm.engine.core.DownloadStatus.DOWNLOADING
import dev.anydm.engine.core.DownloadStatus.FAILED
import dev.anydm.engine.core.DownloadStatus.PAUSED
import dev.anydm.engine.core.DownloadStatus.SEEDING
import dev.anydm.engine.core.File
import dev.anydm.engine.core.FileId
import dev.anydm.engine.core.Mirror
import dev.anydm.engine.core.MirrorId
import dev.anydm.engine.net.BindStatus
import dev.anydm.engine.net.BondedNetwork
import dev.anydm.engine.net.BondingState
import dev.anydm.engine.net.NetworkInfo
import dev.anydm.engine.torrent.PieceMap
import dev.anydm.engine.torrent.TorrentDetail
import dev.anydm.engine.torrent.TorrentPeer
import dev.anydm.engine.torrent.TorrentSummary
import dev.anydm.engine.torrent.TorrentTracker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
internal class DetailScreenTest {
    private val mib = 1024L * 1024
    private val id = DownloadId(1)

    private class Shown(
        val source: FakeSource,
        val files: FakeFileActions,
        val keys: WindowKeys,
        val scope: CoroutineScope,
        val window: FocusRequester,
        val backs: IntArray,
    )

    private fun ComposeUiTest.show(
        snapshot: DownloadSnapshot,
        configure: (FakeSource) -> Unit = {},
    ): Shown {
        val source = FakeSource()
        source.downloads.value = listOf(snapshot)
        configure(source)
        val files = FakeFileActions()
        val scope = CoroutineScope(Dispatchers.Unconfined + Job())
        val prefs = MemoryViewPrefs()
        val footer = FooterModel(source, prefs, scope)
        val model = DetailModel(source, files, id, scope, homeFolder = "/home/me", exists = { true })
        val keys = WindowKeys()
        val window = FocusRequester()
        val backs = IntArray(1)
        setContent {
            Box(Modifier.focusRequester(window).focusTarget().onPreviewKeyEvent { keys.dispatch(it) }) {
                PlexoTheme(prefs.theme) { DetailScreen(model, footer, { backs[0]++ }, keys) }
            }
            LaunchedEffect(Unit) { window.requestFocus() }
        }
        return Shown(source, files, keys, scope, window, backs)
    }

    private fun network(
        key: String,
        name: String,
    ) = BondedNetwork(NetworkInfo(key, name, true, emptyList()), true, BindStatus.ISOLATED, null, false)

    private fun mirror() = Mirror(MirrorId(9), id, "https://example.com/Project.zip", 0)

    private fun fileOf(
        index: Int,
        path: String,
        selected: Boolean = true,
    ) = FileSnapshot(File(FileId(index + 1L), id, index, path, 10, selected, null), emptyList())

    private fun active(speed: Long = 13 * mib) =
        snapshot(
            1,
            DOWNLOADING,
            name = "Project.zip",
            total = 400 * mib,
            downloaded = 275 * mib,
            speed = speed,
            networkShare = mapOf("en0" to 140 * mib, "en7" to 135 * mib),
            saveFolder = "/home/me/Downloads",
        )

    private val twoNetworks = { source: FakeSource ->
        source.networks.value = BondingState(listOf(network("en0", "Wi-Fi"), network("en7", "USB Tethering")))
    }

    @Test
    fun `an active download shows its speed, its networks and its actions`() =
        runComposeUiTest {
            val shown = show(active(), twoNetworks)
            onNodeWithText("Project.zip").assertIsDisplayed()
            onNodeWithText("TOTAL SPEED").assertIsDisplayed()
            onNodeWithText("13.0").assertIsDisplayed()
            // The unit shows beside the speed and again in the footer's switch.
            assertEquals(2, onAllNodesWithText("MB/s").fetchSemanticsNodes().size)
            onNodeWithText("275.0 MB of 400.0 MB").assertIsDisplayed()
            onNodeWithText("68%").assertIsDisplayed()
            onNodeWithText("~/Downloads").assertIsDisplayed()
            onNodeWithText("Cancel download").assertIsDisplayed()
            onNodeWithText("Pause").assertIsDisplayed()
            onNodeWithText(RevealLabel).assertIsDisplayed()
            onNodeWithText("NETWORK").assertIsDisplayed()
            // The name shows in capitals in the diagram, and in the chunk map's legend and the table.
            onNodeWithText("WI-FI").assertIsDisplayed()
            assertEquals(2, onAllNodesWithText("Wi-Fi").fetchSemanticsNodes().size)
            onNodeWithText("51%").assertIsDisplayed()
            onNodeWithText("49%").assertIsDisplayed()
            shown.scope.cancel()
        }

    @Test
    fun `pause and resume call the engine`() =
        runComposeUiTest {
            val shown = show(active())
            onNodeWithText("Pause").performClick()
            assertEquals(listOf("pause 1"), shown.source.calls)
            shown.source.downloads.value = listOf(snapshot(1, PAUSED, name = "Project.zip", total = 400 * mib, downloaded = 275 * mib))
            waitForIdle()
            onNodeWithText("PAUSED").assertIsDisplayed()
            onNodeWithText("Resume").performClick()
            assertEquals(listOf("pause 1", "resume 1"), shown.source.calls)
            shown.scope.cancel()
        }

    @Test
    fun `show in finder reveals the saved file`() =
        runComposeUiTest {
            val shown = show(active())
            onNodeWithText(RevealLabel).performClick()
            assertEquals(listOf("reveal /home/me/Downloads/Project.zip"), shown.files.calls)
            shown.scope.cancel()
        }

    @Test
    fun `cancel download asks and only the confirm cancels`() =
        runComposeUiTest {
            val shown = show(active())
            onNodeWithText("Cancel download").performClick()
            onNodeWithText("Cancel download?").assertIsDisplayed()
            onNodeWithText("The part already downloaded will be deleted.").assertIsDisplayed()
            assertTrue(shown.source.calls.isEmpty())
            onNodeWithText("Keep").performClick()
            assertTrue(shown.source.calls.isEmpty())
            onNodeWithText("Cancel download").performClick()
            // Now the dialog's own button is the second of two.
            onAllNodesWithText("Cancel download")[1].performClick()
            assertEquals(listOf("cancel 1"), shown.source.calls)
            shown.scope.cancel()
        }

    @Test
    fun `back returns to the list`() =
        runComposeUiTest {
            val shown = show(active())
            onNodeWithContentDescription("Back to downloads").performClick()
            assertEquals(1, shown.backs[0])
            shown.scope.cancel()
        }

    @Test
    fun `escape goes back but not while a dialog is open`() =
        runComposeUiTest {
            val shown = show(active())
            runOnIdle { shown.window.requestFocus() }
            onAllNodes(isRoot())[0].performKeyInput { pressKey(Key.Escape) }
            assertEquals(1, shown.backs[0])
            onNodeWithText("Cancel download").performClick()
            onNodeWithText("Cancel download?").assertIsDisplayed()
            // The dialog's own Escape closes it; the page stays.
            onAllNodes(isRoot())[0].performKeyInput { pressKey(Key.Escape) }
            assertEquals(1, shown.backs[0])
            shown.scope.cancel()
        }

    @Test
    fun `a torrent shows its peers and a file list with ticks`() =
        runComposeUiTest {
            val torrent = TorrentSummary(12, 3, 1.0, 0, 0, 0, null, 0)
            val shown =
                show(
                    snapshot(
                        1,
                        DOWNLOADING,
                        name = "Sintel",
                        total = 100,
                        downloaded = 40,
                        torrent = torrent,
                        files = listOf(fileOf(0, "Sintel/a.mkv"), fileOf(1, "Sintel/b.srt")),
                    ),
                )
            onNodeWithText("TORRENT").assertIsDisplayed()
            onNodeWithText("12 peers").assertIsDisplayed()
            onNodeWithText("2 files").assertIsDisplayed()
            onNodeWithText("2 files").performClick()
            onNodeWithText("a.mkv").assertIsDisplayed()
            onNodeWithContentDescription("Download Sintel/b.srt").performClick()
            assertEquals(listOf("torrentFiles 1 [4, 0]"), shown.source.calls.filter { it.startsWith("torrentFiles") })
            shown.scope.cancel()
        }

    @Test
    fun `a finished download shows its summary and the actions that follow`() =
        runComposeUiTest {
            val shown =
                show(
                    snapshot(
                        1,
                        COMPLETED,
                        name = "Project.zip",
                        size = 400 * mib,
                        downloaded = 400 * mib,
                        speedSamples =
                            listOf(
                                2 * mib,
                                10 * mib,
                            ),
                        networkShare = mapOf("en0" to 300 * mib, "en7" to 100 * mib),
                        saveFolder = "/home/me/Downloads",
                        files = listOf(fileOf(0, "Project.zip")),
                    ),
                    twoNetworks,
                )
            onNodeWithText("Project.zip").assertIsDisplayed()
            onNodeWithText("AVERAGE").assertIsDisplayed()
            onNodeWithText("SIZE").assertIsDisplayed()
            onNodeWithText("SPEED OVER THE DOWNLOAD").assertIsDisplayed()
            onNodeWithText("CONTRIBUTION BY NETWORK").assertIsDisplayed()
            onNodeWithText("300.0 MB · 75%").assertIsDisplayed()
            onNodeWithText("Remove from list").assertIsDisplayed()
            onNodeWithText(TrashLabel).assertIsDisplayed()
            onNodeWithText("Open").assertIsDisplayed()
            onNodeWithText(RevealLabel).assertIsDisplayed()
            onNodeWithText("Open").performClick()
            assertEquals(listOf("open /home/me/Downloads/Project.zip"), shown.files.calls)
            shown.scope.cancel()
        }

    @Test
    fun `move file to trash asks first and trashes only after the confirm`() =
        runComposeUiTest {
            val shown =
                show(
                    snapshot(
                        1,
                        COMPLETED,
                        name = "Project.zip",
                        size = 400 * mib,
                        saveFolder = "/home/me/Downloads",
                        files = listOf(fileOf(0, "Project.zip")),
                    ),
                )
            onNodeWithText(TrashLabel).performClick()
            onNodeWithText("Delete file?").assertIsDisplayed()
            assertTrue(shown.files.calls.isEmpty())
            onNodeWithText("Cancel").performClick()
            assertTrue(shown.files.calls.isEmpty())
            onNodeWithText(TrashLabel).performClick()
            onNodeWithText("Delete file").performClick()
            assertTrue(shown.files.calls.isEmpty())
            assertEquals(listOf("remove 1 TRASH"), shown.source.calls)
            shown.scope.cancel()
        }

    @Test
    fun `the trash question for a seeding torrent says that seeding stops`() =
        runComposeUiTest {
            val shown =
                show(
                    snapshot(
                        1,
                        SEEDING,
                        name = "Sintel",
                        size = 400 * mib,
                        saveFolder = "/home/me/Downloads",
                        files = listOf(fileOf(0, "Sintel/a.mkv")),
                    ),
                )
            onNodeWithText(TrashLabel).performClick()
            onNodeWithText(
                "The file will be moved to the ${if (IsMac) "Trash" else "Recycle Bin"}. This also stops seeding.",
            ).assertIsDisplayed()
            shown.scope.cancel()
        }

    @Test
    fun `the trash question for a finished file says nothing of seeding`() =
        runComposeUiTest {
            val shown =
                show(
                    snapshot(
                        1,
                        COMPLETED,
                        name = "a.zip",
                        size = 400 * mib,
                        saveFolder = "/home/me/Downloads",
                        files = listOf(fileOf(0, "a.zip")),
                    ),
                )
            onNodeWithText(TrashLabel).performClick()
            onNodeWithText("The file will be moved to the ${if (IsMac) "Trash" else "Recycle Bin"}.").assertIsDisplayed()
            shown.scope.cancel()
        }

    @Test
    fun `a failed download shows why, with retry and a way to give a new link`() =
        runComposeUiTest {
            val shown =
                show(snapshot(1, FAILED, name = "Project.zip", size = 400 * mib, downloaded = 100 * mib, mirrors = listOf(mirror())))
            onNodeWithText("Download failed").assertIsDisplayed()
            onNodeWithText("The download stopped unexpectedly. Try again.").assertIsDisplayed()
            onNodeWithText("100.0 MB of 400.0 MB (25%)").assertIsDisplayed()
            onNodeWithText("https://example.com/Project.zip").assertIsDisplayed()
            onNodeWithText("Copy link").performClick()
            assertEquals(listOf("copy https://example.com/Project.zip"), shown.files.calls)
            onNodeWithText("Retry").performClick()
            assertEquals(listOf("retry 1"), shown.source.calls)
            shown.scope.cancel()
        }

    @Test
    fun `fix link takes a new link and hands it to relink`() =
        runComposeUiTest {
            val shown = show(snapshot(1, FAILED, name = "Project.zip", size = 400 * mib, mirrors = listOf(mirror())))
            onNodeWithText("Fix link").performClick()
            onNodeWithText("Paste a new link").assertIsDisplayed()
            onNodeWithContentDescription("New link").performTextInput("https://mirror.example/new.zip")
            onNodeWithText("Continue download").performClick()
            assertEquals(listOf("relink 1 9 https://mirror.example/new.zip"), shown.source.calls)
            shown.scope.cancel()
        }

    @Test
    fun `the page's shortcuts leave a text field its keys`() =
        runComposeUiTest {
            val shown = show(snapshot(1, FAILED, name = "Project.zip", size = 400 * mib, mirrors = listOf(mirror())))
            onNodeWithText("Fix link").performClick()
            onNodeWithContentDescription("New link").performTextInput("abc")
            // Backspace and Escape go through the window first, as they do in the app: the field gets Backspace, the dialog Escape.
            onAllNodes(isRoot())[0].performKeyInput { pressKey(Key.Backspace) }
            onNodeWithContentDescription("New link").assertTextEquals("ab", includeEditableText = true)
            onAllNodes(isRoot())[0].performKeyInput { pressKey(Key.Escape) }
            onNodeWithText("Paste a new link").assertDoesNotExist()
            assertEquals(0, shown.backs[0])
            shown.scope.cancel()
        }

    @Test
    fun `the page goes back when its download is gone`() =
        runComposeUiTest {
            val shown = show(active())
            shown.source.downloads.value = emptyList()
            waitForIdle()
            assertEquals(1, shown.backs[0])
            shown.scope.cancel()
        }

    @Test
    fun `a message from a failed command shows and can be dismissed`() =
        runComposeUiTest {
            val shown = show(active())
            shown.files.problem = "That did not work."
            onNodeWithText(RevealLabel).performClick()
            onNodeWithText("That did not work.").assertIsDisplayed()
            shown.scope.cancel()
        }

    // Peers and trackers

    private fun torrentJob(status: dev.anydm.engine.core.DownloadStatus = DOWNLOADING) =
        snapshot(
            1,
            status,
            name = "Ubuntu.iso",
            total = 400 * mib,
            downloaded = 100 * mib,
            speed = 5 * mib,
            torrent = TorrentSummary(5, 2, 1.0, 0, 0, 0, null, 0),
            networkShare = mapOf("en0" to 100 * mib),
        )

    private fun peer(
        address: String,
        down: Long,
        seed: Boolean = false,
    ) = TorrentPeer(address, "qBittorrent 4.6", 0.5, down, 1024, 0, 0, seed)

    private fun tracker(
        url: String,
        custom: Boolean = false,
    ) = TorrentTracker(url, 0, verified = true, enabled = true, current = false, scrapeSeeds = 4, scrapePeers = 7, custom = custom)

    private fun detail(
        peers: List<TorrentPeer> = emptyList(),
        trackers: List<TorrentTracker> = emptyList(),
        private: Boolean = false,
    ) = TorrentDetailResult.Detail(TorrentDetail(peers, trackers, PieceMap.Empty, private))

    private fun ComposeUiTest.press(
        key: Key,
        command: Boolean = false,
    ) {
        val modifier = if (IsMac) Key.MetaLeft else Key.CtrlLeft
        onNodeWithTag("trackers-input").performKeyInput {
            if (command) keyDown(modifier)
            pressKey(key)
            if (command) keyUp(modifier)
        }
    }

    @Test
    fun `the peer count opens a table of its peers, fastest first`() =
        runComposeUiTest {
            val peers = listOf(peer("10.0.0.1:6881", 10), peer("10.0.0.2:6881", 900, seed = true), peer("10.0.0.3:6881", 50))
            val shown = show(torrentJob()) { it.torrent = detail(peers) }
            onNodeWithTag("peers-table").assertDoesNotExist()
            onNodeWithTag("peers-toggle").assertTextEquals("3 peers")
            onNodeWithTag("peers-toggle").performClick()
            onNodeWithTag("peers-table").assertIsDisplayed()
            val tops =
                peers.map {
                    onNodeWithTag("peer-${it.address}")
                        .assertIsDisplayed()
                        .fetchSemanticsNode()
                        .positionInRoot.y
                }
            assertTrue(tops[1] < tops[2] && tops[2] < tops[0], "fastest first: $tops")
            onNodeWithTag("peers-toggle").performClick()
            onNodeWithTag("peers-table").assertDoesNotExist()
            shown.scope.cancel()
        }

    @Test
    fun `a torrent with no peers says so`() =
        runComposeUiTest {
            val shown = show(torrentJob()) { it.torrent = detail() }
            onNodeWithTag("peers-toggle").assertTextEquals("0 peers")
            onNodeWithTag("peers-toggle").performClick()
            onNodeWithText("No peers connected.").assertIsDisplayed()
            shown.scope.cancel()
        }

    @Test
    fun `one peer reads as a peer`() =
        runComposeUiTest {
            val shown = show(torrentJob()) { it.torrent = detail(listOf(peer("10.0.0.1:6881", 1))) }
            onNodeWithTag("peers-toggle").assertTextEquals("1 peer")
            shown.scope.cancel()
        }

    @Test
    fun `only torrents have a Trackers button`() =
        runComposeUiTest {
            val torrent = show(torrentJob())
            onNodeWithTag("trackers-open").assertIsDisplayed()
            torrent.scope.cancel()
        }

    @Test
    fun `a plain download has no Trackers button`() =
        runComposeUiTest {
            val shown = show(active(), twoNetworks)
            onNodeWithTag("trackers-open").assertDoesNotExist()
            shown.scope.cancel()
        }

    @Test
    fun `a seeding torrent has a Trackers button`() =
        runComposeUiTest {
            val shown = show(snapshot(1, SEEDING, name = "t", size = 10 * mib, torrent = TorrentSummary(1, 1, 1.0, 0, 0, 0, null, 0)))
            onNodeWithTag("trackers-open").assertIsDisplayed()
            shown.scope.cancel()
        }

    @Test
    fun `the Trackers dialog lists the default trackers before the custom ones`() =
        runComposeUiTest {
            val shown =
                show(torrentJob()) {
                    it.torrent =
                        detail(
                            trackers =
                                listOf(
                                    tracker("udp://mine/announce", custom = true),
                                    tracker("udp://a/announce"),
                                    tracker("http://b/announce"),
                                ),
                        )
                }
            onNodeWithTag("trackers-open").performClick()
            onNodeWithTag("trackers-dialog").assertIsDisplayed()
            onNodeWithText("Trackers (3)").assertIsDisplayed()
            onNodeWithTag("tracker-0").assertIsDisplayed()
            onNodeWithTag("tracker-2").assertIsDisplayed()
            onNodeWithText("Custom").assertIsDisplayed()
            assertEquals(1, onAllNodesWithText("Custom").fetchSemanticsNodes().size)
            shown.scope.cancel()
        }

    @Test
    fun `the Trackers dialog with none says none are configured`() =
        runComposeUiTest {
            val shown = show(torrentJob()) { it.torrent = detail() }
            onNodeWithTag("trackers-open").performClick()
            onNodeWithText("No announce trackers configured.").assertIsDisplayed()
            shown.scope.cancel()
        }

    @Test
    fun `a paused torrent's dialog says to start it, and still takes trackers`() =
        runComposeUiTest {
            val shown = show(torrentJob(PAUSED)) { it.trackerAction = TorrentActionResult.NotInSession }
            onNodeWithTag("trackers-open").performClick()
            onNodeWithText("Not running. Trackers can be added now and apply when it starts.").assertIsDisplayed()
            onNodeWithTag("trackers-input").performTextInput("udp://a/announce")
            onNodeWithTag("trackers-add").performClick()
            waitForIdle()
            assertEquals(listOf("addTrackers 1 [udp://a/announce]"), shown.source.calls.filter { it.startsWith("addTrackers") })
            onNodeWithText("Saved. The trackers are added when the torrent starts.").assertIsDisplayed()
            shown.scope.cancel()
        }

    @Test
    fun `a private torrent shows the notice and no add field`() =
        runComposeUiTest {
            val shown = show(torrentJob()) { it.torrent = detail(trackers = listOf(tracker("udp://a/announce")), private = true) }
            onNodeWithTag("trackers-open").performClick()
            onNodeWithText("This is a private torrent. External trackers cannot be added to private swarms.").assertIsDisplayed()
            onNodeWithTag("trackers-input").assertDoesNotExist()
            onNodeWithTag("trackers-add").assertDoesNotExist()
            shown.scope.cancel()
        }

    @Test
    fun `the add button is off while the field is blank`() =
        runComposeUiTest {
            val shown = show(torrentJob()) { it.torrent = detail(trackers = listOf(tracker("udp://a/announce"))) }
            onNodeWithTag("trackers-open").performClick()
            onNodeWithTag("trackers-add").assertIsNotEnabled()
            onNodeWithTag("trackers-input").performTextInput("udp://b/announce")
            onNodeWithTag("trackers-add").assertIsEnabled()
            shown.scope.cancel()
        }

    @Test
    fun `the field is focused on open and the shortcut submits two valid lines and ignores one`() =
        runComposeUiTest {
            val shown = show(torrentJob()) { it.torrent = detail(trackers = listOf(tracker("udp://a/announce"))) }
            onNodeWithTag("trackers-open").performClick()
            onNodeWithTag("trackers-input").assertIsFocused()
            onNodeWithTag("trackers-input").performTextInput("udp://b/announce\nhttp://c/announce\nnonsense")
            press(Key.Enter, command = true)
            assertEquals(
                listOf("addTrackers 1 [udp://b/announce, http://c/announce]"),
                shown.source.calls.filter { it.startsWith("addTrackers") },
            )
            onNodeWithText("1 line(s) weren't tracker link(s) and were ignored.").assertIsDisplayed()
            assertEquals("", onNodeWithTag("trackers-input").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
            shown.scope.cancel()
        }

    @Test
    fun `plain enter makes a new line and does not submit`() =
        runComposeUiTest {
            val shown = show(torrentJob()) { it.torrent = detail() }
            onNodeWithTag("trackers-open").performClick()
            onNodeWithTag("trackers-input").performTextInput("udp://a/announce")
            press(Key.Enter)
            assertEquals(emptyList(), shown.source.calls.filter { it.startsWith("addTrackers") })
            assertEquals(
                "udp://a/announce\n",
                onNodeWithTag("trackers-input").fetchSemanticsNode().config[SemanticsProperties.EditableText].text,
            )
            shown.scope.cancel()
        }

    @Test
    fun `a stopped private torrent shows the notice and no add field`() =
        runComposeUiTest {
            val shown = show(torrentJob(PAUSED)) { it.torrent = TorrentDetailResult.PrivateNotInSession }
            onNodeWithTag("trackers-open").performClick()
            onNodeWithText("This is a private torrent. External trackers cannot be added to private swarms.").assertIsDisplayed()
            onNodeWithTag("trackers-input").assertDoesNotExist()
            onNodeWithTag("trackers-add").assertDoesNotExist()
            shown.scope.cancel()
        }

    @Test
    fun `the field is off while an add runs`() =
        runComposeUiTest {
            val shown = show(torrentJob()) { it.torrent = detail() }
            val gate = CompletableDeferred<Unit>()
            shown.source.gates["addTrackers 1 [udp://a/announce]"] = gate
            onNodeWithTag("trackers-open").performClick()
            onNodeWithTag("trackers-input").performTextInput("udp://a/announce")
            onNodeWithTag("trackers-add").performClick()
            waitForIdle()
            onNodeWithTag("trackers-input").assertIsNotEnabled()
            gate.complete(Unit)
            waitForIdle()
            onNodeWithTag("trackers-input").assertIsEnabled()
            shown.scope.cancel()
        }

    @Test
    fun `only invalid lines show a red error and keep the field`() =
        runComposeUiTest {
            val shown = show(torrentJob()) { it.torrent = detail() }
            onNodeWithTag("trackers-open").performClick()
            onNodeWithTag("trackers-input").performTextInput("nonsense")
            onNodeWithTag("trackers-add").performClick()
            onNodeWithText("1 line(s) weren't tracker link(s)").assertIsDisplayed()
            onNodeWithTag("trackers-input").assertTextEquals("nonsense", includeEditableText = true)
            assertEquals(emptyList(), shown.source.calls.filter { it.startsWith("addTrackers") })
            shown.scope.cancel()
        }

    @Test
    fun `reannounce asks the engine`() =
        runComposeUiTest {
            val shown = show(torrentJob()) { it.torrent = detail(trackers = listOf(tracker("udp://a/announce"))) }
            onNodeWithTag("trackers-open").performClick()
            onNodeWithTag("trackers-reannounce").performClick()
            waitForIdle()
            assertEquals(listOf("reannounce 1"), shown.source.calls.filter { it.startsWith("reannounce") })
            shown.scope.cancel()
        }

    @Test
    fun `escape and close shut the dialog, a click outside does not`() =
        runComposeUiTest {
            val shown = show(torrentJob()) { it.torrent = detail() }
            onNodeWithTag("trackers-open").performClick()
            onNodeWithTag("trackers-dialog").assertIsDisplayed()
            onNodeWithTag("trackers-scrim").performTouchInput { click(Offset(2f, 2f)) }
            onNodeWithTag("trackers-dialog").assertIsDisplayed()
            onAllNodes(isRoot())[0].performKeyInput { pressKey(Key.Escape) }
            onNodeWithTag("trackers-dialog").assertDoesNotExist()
            assertEquals(0, shown.backs[0])
            onNodeWithTag("trackers-open").performClick()
            onNodeWithText("Close").performClick()
            onNodeWithTag("trackers-dialog").assertDoesNotExist()
            shown.scope.cancel()
        }
}
