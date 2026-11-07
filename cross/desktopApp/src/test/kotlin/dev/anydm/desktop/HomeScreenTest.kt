package dev.anydm.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import dev.anydm.engine.EngineState
import dev.anydm.engine.SpeedWindow
import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadStatus.COMPLETED
import dev.anydm.engine.core.DownloadStatus.DOWNLOADING
import dev.anydm.engine.core.DownloadStatus.FAILED
import dev.anydm.engine.core.DownloadStatus.PAUSED
import dev.anydm.engine.core.DownloadStatus.SEEDING
import dev.anydm.engine.net.BindStatus
import dev.anydm.engine.net.BondedNetwork
import dev.anydm.engine.net.BondingState
import dev.anydm.engine.net.NetworkInfo
import dev.anydm.engine.net.SpeedLimit
import dev.anydm.engine.store.TransferSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class, androidx.compose.ui.InternalComposeUiApi::class)
internal class HomeScreenTest {
    private val mib = 1024L * 1024

    private class Shown(
        val source: FakeSource,
        val prefs: MemoryViewPrefs,
        val opened: MutableList<List<String>>,
        val backs: MutableList<Unit>,
        val scope: CoroutineScope,
        val window: FocusRequester,
        val keys: WindowKeys,
        val files: FakeFileActions,
        val routed: MutableList<DownloadId>,
        val limits: MutableList<String?> = mutableListOf(),
    )

    private fun ComposeUiTest.show(
        source: FakeSource = FakeSource(),
        prefs: MemoryViewPrefs = MemoryViewPrefs(),
        files: FakeFileActions = FakeFileActions(),
        exists: (java.nio.file.Path) -> Boolean = { true },
        configure: (FakeSource) -> Unit = {},
    ): Shown {
        configure(source)
        val scope = CoroutineScope(Dispatchers.Unconfined + Job())
        val model = HomeModel(source, scope, files, exists = exists)
        val routed = mutableListOf<DownloadId>()
        val footer = FooterModel(source, prefs, scope)
        val opened = mutableListOf<List<String>>()
        val backs = mutableListOf<Unit>()
        val limits = mutableListOf<String?>()
        val keys = WindowKeys()
        val window = FocusRequester()
        setContent {
            // Stands in for the window, which hands every key to the shortcuts before the focused part sees it.
            Box(Modifier.focusRequester(window).focusTarget().onPreviewKeyEvent { keys.dispatch(it) }) {
                PlexoTheme(prefs.theme) {
                    HomeScreen(
                        source,
                        model,
                        footer,
                        onNewDownload = { opened += it },
                        onOpen = { routed += it },
                        onBack = { backs += Unit },
                        keys = keys,
                        onLimits = { limits += it },
                    )
                }
            }
            LaunchedEffect(Unit) { window.requestFocus() }
        }
        return Shown(source, prefs, opened, backs, scope, window, keys, files, routed, limits)
    }

    private fun network(key: String) = BondedNetwork(NetworkInfo(key, key, true, emptyList()), true, BindStatus.ISOLATED, null, false)

    private fun two() =
        listOf(
            snapshot(1, DOWNLOADING, name = "Project.zip", total = 200 * mib, downloaded = 176 * mib, speed = 4 * mib),
            snapshot(2, COMPLETED, name = "download", size = 120 * 1024),
            snapshot(3, COMPLETED, name = "report.pdf", size = 5 * mib),
        )

    @Test
    fun `no downloads shows the empty state with its hint and button`() =
        runComposeUiTest {
            val shown = show()
            onNodeWithText("No downloads yet").assertIsDisplayed()
            onNodeWithText("Paste a link ($PasteShortcut) or drop a .torrent anywhere in this window.").assertIsDisplayed()
            onNodeWithText(NewShortcut).assertIsDisplayed()
            assertEquals(2, onAllNodesWithText("New download").fetchSemanticsNodes().size)
            shown.scope.cancel()
        }

    @Test
    fun `the new download button asks for the dialog`() =
        runComposeUiTest {
            val shown = show()
            onAllNodesWithText("New download")[0].performClick()
            assertEquals(listOf(emptyList<String>()), shown.opened)
            shown.scope.cancel()
        }

    @Test
    fun `rows appear in groups with a select box and a clear button for the finished`() =
        runComposeUiTest {
            val shown = show { it.downloads.value = two() }
            onNodeWithText("DOWNLOADING").assertIsDisplayed()
            onNodeWithText("FINISHED").assertIsDisplayed()
            onNodeWithText("Project.zip").assertIsDisplayed()
            onNodeWithText("88% · 176.0 MB of 200.0 MB · 6s").assertIsDisplayed()
            onNodeWithText("4.0 MB/s").assertIsDisplayed()
            onNodeWithText("ZIP").assertIsDisplayed()
            onNodeWithText("120.0 KB").assertIsDisplayed()
            onNodeWithText("Clear finished list").assertIsDisplayed()
            onNodeWithText("1 downloading").assertIsDisplayed()
            shown.scope.cancel()
        }

    @Test
    fun `the header and pill show the networks and their combined speed`() =
        runComposeUiTest {
            val shown =
                show {
                    it.downloads.value = two()
                    it.networks.value = BondingState(listOf(network("en0"), network("en7")))
                    it.totalSpeed.value = SpeedWindow(5 * mib, emptyList())
                }
            onNodeWithText("2 networks").assertIsDisplayed()
            onNodeWithText("5.0 MB/s").assertIsDisplayed()
            shown.scope.cancel()
        }

    @Test
    fun `a group box selects all its rows and the bar offers the bulk commands`() =
        runComposeUiTest {
            val shown =
                show {
                    it.downloads.value =
                        listOf(
                            snapshot(1, DOWNLOADING, name = "a.bin", total = 10, downloaded = 1),
                            snapshot(2, DOWNLOADING, name = "b.bin", total = 10, downloaded = 1),
                            snapshot(3, PAUSED, name = "c.bin", total = 10, downloaded = 1),
                            snapshot(4, FAILED, name = "d.bin"),
                        )
                }
            onNodeWithContentDescription("Select all downloading downloads").performClick()
            onNodeWithText("2 selected").assertIsDisplayed()
            onNodeWithText("Pause 2").assertIsDisplayed()
            onNodeWithContentDescription("Select a.bin").assertIsOn()
            onNodeWithContentDescription("Select c.bin").assertIsOff()
            onNodeWithText("Select all").performClick()
            onNodeWithText("4 selected").assertIsDisplayed()
            onNodeWithText("Resume").assertIsDisplayed()
            onNodeWithText("Retry").assertIsDisplayed()
            onNodeWithText("Remove 4").assertIsDisplayed()
            onNodeWithText("Pause 2").performClick()
            waitForIdle()
            assertEquals(listOf("pause 1", "pause 2"), shown.source.calls)
            onNodeWithContentDescription("Deselect all").performClick()
            onNodeWithText("No downloads yet").assertDoesNotExist()
            shown.scope.cancel()
        }

    @Test
    fun `clear finished asks first, then removes finished rows and keeps their files`() =
        runComposeUiTest {
            val shown = show { it.downloads.value = two() }
            onNodeWithText("Clear finished list").performClick()
            onNodeWithText("Clear finished list?").assertIsDisplayed()
            onNodeWithText("This takes 2 downloads off the list. The files stay where they are.").assertIsDisplayed()
            onNodeWithText("Cancel").performClick()
            onNodeWithText("Clear finished list?").assertDoesNotExist()
            assertTrue(shown.source.calls.isEmpty())
            onNodeWithText("Clear finished list").performClick()
            onNodeWithText("Clear list").performClick()
            waitForIdle()
            assertEquals(listOf("remove 2 KEEP", "remove 3 KEEP"), shown.source.calls)
            shown.scope.cancel()
        }

    @Test
    fun `clear finished counts the completed rows, not the seeding ones`() =
        runComposeUiTest {
            val shown =
                show {
                    it.downloads.value = two() + snapshot(4, SEEDING, name = "t.iso", size = 5)
                }
            onNodeWithText("Clear finished list").performClick()
            onNodeWithText("This takes 2 downloads off the list. The files stay where they are.").assertIsDisplayed()
            shown.scope.cancel()
        }

    @Test
    fun `without a completed row there is no clear button`() =
        runComposeUiTest {
            val shown = show { it.downloads.value = listOf(snapshot(1, SEEDING, name = "t.iso", size = 5)) }
            onNodeWithText("FINISHED").assertIsDisplayed()
            onNodeWithText("Clear finished list").assertDoesNotExist()
            shown.scope.cancel()
        }

    @Test
    fun `the bulk remove button asks before it removes a running row`() =
        runComposeUiTest {
            val shown = show { it.downloads.value = two() }
            onNodeWithContentDescription("Select all downloading downloads").performClick()
            onNodeWithText("Remove").performClick()
            onNodeWithText("Remove from list?").assertIsDisplayed()
            onNodeWithText("Cancel").performClick()
            assertTrue(shown.source.calls.isEmpty())
            onNodeWithText("Remove").performClick()
            onNodeWithText("Remove from list").performClick()
            waitForIdle()
            assertEquals(listOf("remove 1 KEEP"), shown.source.calls)
            shown.scope.cancel()
        }

    // A key pressed in the window: the harness takes the focus back first, as the real window gets keys whatever has focus.
    private fun ComposeUiTest.press(
        shown: Shown,
        key: Key,
        command: Boolean = false,
    ) {
        runOnIdle { shown.window.requestFocus() }
        onAllNodes(isRoot())[0].performKeyInput {
            if (command) withKeyDown(if (IsMac) Key.MetaLeft else Key.CtrlLeft) { pressKey(key) } else pressKey(key)
        }
    }

    @Test
    fun `the paste shortcut hands a link on the clipboard to the dialog`() =
        runComposeUiTest {
            val shown = show { it.clipboardText = "notes\nhttps://example.com/a.zip\n" }
            press(shown, Key.V, command = true)
            // The clipboard is read off the UI thread: the link arrives a moment later.
            waitUntil(timeoutMillis = 5_000) { shown.opened.isNotEmpty() }
            assertEquals(listOf(listOf("https://example.com/a.zip")), shown.opened)
            shown.scope.cancel()
        }

    @Test
    fun `a held paste shortcut still pastes on every press the window reports`() =
        runComposeUiTest {
            val shown = show { it.clipboardText = "https://example.com/a.zip" }

            // The test input can't press a key that is already down, so the window's events are made by hand.
            fun paste(type: KeyEventType) = shown.keys.dispatch(KeyEvent(Key.V, type, isCtrlPressed = !IsMac, isMetaPressed = IsMac))
            runOnIdle {
                paste(KeyEventType.KeyDown)
                paste(KeyEventType.KeyDown)
                paste(KeyEventType.KeyDown)
                paste(KeyEventType.KeyUp)
            }
            waitUntil(timeoutMillis = 5_000) { shown.opened.isNotEmpty() }
            waitForIdle()
            Thread.sleep(100)
            // The landing page skips a link that is already in its field; the screen itself never swallows a paste.
            waitUntil(timeoutMillis = 5_000) { shown.opened.size == 3 }
            assertEquals(3, shown.opened.size)
            shown.scope.cancel()
        }

    @Test
    fun `a paste goes to the handler the screen has now, not the one it was first given`() =
        runComposeUiTest {
            val source = FakeSource().apply { clipboardText = "https://example.com/a.zip" }
            val scope = CoroutineScope(Dispatchers.Unconfined + Job())
            val prefs = MemoryViewPrefs()
            val model = HomeModel(source, scope)
            val footer = FooterModel(source, prefs, scope)
            val keys = WindowKeys()
            val window = FocusRequester()
            val first = mutableListOf<List<String>>()
            val second = mutableListOf<List<String>>()
            var sink by mutableStateOf<(List<String>) -> Unit>({ first += it })
            setContent {
                Box(Modifier.focusRequester(window).focusTarget().onPreviewKeyEvent { keys.dispatch(it) }) {
                    PlexoTheme(prefs.theme) {
                        HomeScreen(source, model, footer, onNewDownload = sink, onOpen = {}, onBack = {}, keys = keys)
                    }
                }
                LaunchedEffect(Unit) { window.requestFocus() }
            }
            sink = { second += it }
            waitForIdle()
            onAllNodes(isRoot())[0].performKeyInput { withKeyDown(if (IsMac) Key.MetaLeft else Key.CtrlLeft) { pressKey(Key.V) } }
            waitUntil(timeoutMillis = 5_000) { second.isNotEmpty() }
            assertTrue(first.isEmpty())
            assertEquals(1, second.size)
            scope.cancel()
        }

    @Test
    fun `the paste shortcut ignores text that is no link`() =
        runComposeUiTest {
            val shown = show { it.clipboardText = "just words" }
            press(shown, Key.V, command = true)
            waitForIdle()
            assertTrue(shown.opened.isEmpty())
            shown.scope.cancel()
        }

    @Test
    fun `select all, Escape and Delete work from the keyboard`() =
        runComposeUiTest {
            val shown = show { it.downloads.value = two() }
            press(shown, Key.A, command = true)
            onNodeWithText("3 selected").assertIsDisplayed()
            press(shown, Key.Escape)
            onNodeWithText("3 selected").assertDoesNotExist()
            press(shown, Key.A, command = true)
            press(shown, Key.Delete)
            onNodeWithText("Remove from list?").assertIsDisplayed()
            assertTrue(shown.source.calls.isEmpty())
            shown.scope.cancel()
        }

    @Test
    fun `the back control asks to leave the list`() =
        runComposeUiTest {
            val shown = show()
            onNodeWithContentDescription("Back").performClick()
            assertEquals(1, shown.backs.size)
            shown.scope.cancel()
        }

    @Test
    fun `Escape with nothing selected leaves the list, with a selection it only clears it`() =
        runComposeUiTest {
            val shown = show { it.downloads.value = two() }
            press(shown, Key.A, command = true)
            press(shown, Key.Escape)
            assertTrue(shown.backs.isEmpty())
            press(shown, Key.Escape)
            assertEquals(1, shown.backs.size)
            shown.scope.cancel()
        }

    @Test
    fun `a held Escape leaves the list once, not again on every repeat`() =
        runComposeUiTest {
            val shown = show()

            fun esc() = shown.keys.dispatch(KeyEvent(Key.Escape, KeyEventType.KeyDown))
            runOnIdle {
                esc()
                esc()
                esc()
                shown.keys.dispatch(KeyEvent(Key.Escape, KeyEventType.KeyUp))
            }
            assertEquals(1, shown.backs.size)
            shown.scope.cancel()
        }

    @Test
    fun `Delete asks even for completed rows and shortcuts wait while a dialog is open`() =
        runComposeUiTest {
            val shown = show { it.downloads.value = listOf(snapshot(2, COMPLETED, name = "report.pdf", size = 5 * mib)) }
            onNodeWithText("Clear finished list").performClick()
            press(shown, Key.A, command = true)
            onNodeWithText("1 selected").assertDoesNotExist()
            onNodeWithText("Cancel").performClick()
            press(shown, Key.A, command = true)
            press(shown, Key.Backspace)
            waitForIdle()
            onNodeWithText("Remove from list?").assertIsDisplayed()
            assertTrue(shown.source.calls.isEmpty(), "nothing is removed before the answer")
            shown.scope.cancel()
        }

    private fun finished() = snapshot(2, COMPLETED, name = "report.pdf", size = 5 * mib, saveFolder = "/dl")

    private fun ComposeUiTest.rightClickRow(id: Long) = onNodeWithTag("row-$id").performMouseInput { rightClick() }

    @Test
    fun `right-click on a row opens its menu`() =
        runComposeUiTest {
            val shown = show { it.downloads.value = listOf(finished()) }
            onNodeWithTag("menu-OPEN").assertDoesNotExist()
            rightClickRow(2)
            onNodeWithTag("menu-OPEN").assertIsDisplayed().assertIsEnabled()
            onNodeWithTag("menu-REVEAL").assertIsDisplayed()
            onNodeWithTag("menu-REMOVE").assertIsDisplayed()
            assertTrue(shown.routed.isEmpty(), "the page did not open")
            assertTrue(shown.source.calls.isEmpty())
            assertTrue(shown.files.calls.isEmpty())
            shown.scope.cancel()
        }

    @Test
    fun `a menu item runs its engine command and closes the menu`() =
        runComposeUiTest {
            val shown = show { it.downloads.value = two() }
            rightClickRow(1)
            onNodeWithTag("menu-PAUSE").performClick()
            waitForIdle()
            assertEquals(listOf("pause 1"), shown.source.calls)
            onNodeWithTag("menu-PAUSE").assertDoesNotExist()
            shown.scope.cancel()
        }

    @Test
    fun `a menu item can run a file action`() =
        runComposeUiTest {
            val shown = show { it.downloads.value = listOf(finished()) }
            rightClickRow(2)
            onNodeWithTag("menu-OPEN").performClick()
            waitForIdle()
            assertEquals(1, shown.files.calls.count { it.startsWith("open ") })
            shown.scope.cancel()
        }

    @Test
    fun `an item that cannot work now is disabled`() =
        runComposeUiTest {
            val shown = show(exists = { false }) { it.downloads.value = listOf(finished()) }
            rightClickRow(2)
            onNodeWithTag("menu-OPEN").assertIsNotEnabled()
            onNodeWithTag("menu-REVEAL").assertIsNotEnabled()
            onNodeWithTag("menu-REMOVE").assertIsEnabled()
            onNodeWithTag("menu-OPEN").performClick()
            assertTrue(shown.files.calls.isEmpty())
            shown.scope.cancel()
        }

    @Test
    fun `double-click on a completed row opens its file and stays on the list`() =
        runComposeUiTest {
            val shown = show { it.downloads.value = listOf(finished()) }
            onNodeWithText("report.pdf").performMouseInput { doubleClick() }
            waitForIdle()
            assertEquals(1, shown.files.calls.count { it.startsWith("open ") })
            assertTrue(shown.routed.isEmpty(), "the page did not open")
            shown.scope.cancel()
        }

    @Test
    fun `double-click on a running row opens no file`() =
        runComposeUiTest {
            val shown = show { it.downloads.value = two() }
            onNodeWithText("Project.zip").performMouseInput { doubleClick() }
            waitForIdle()
            assertTrue(shown.files.calls.isEmpty())
            shown.scope.cancel()
        }

    @Test
    fun `a single click on the name still opens the page`() =
        runComposeUiTest {
            val shown = show { it.downloads.value = listOf(finished()) }
            onNodeWithText("report.pdf").performClick()
            // A single click waits out the double-click window.
            mainClock.advanceTimeBy(1_000)
            waitForIdle()
            assertEquals(listOf(DownloadId(2)), shown.routed)
            assertTrue(shown.files.calls.isEmpty())
            shown.scope.cancel()
        }

    @Test
    fun `the remove dialog has three answers and Trash reaches the engine`() =
        runComposeUiTest {
            val shown = show { it.downloads.value = listOf(finished()) }
            rightClickRow(2)
            onNodeWithTag("menu-REMOVE").performClick()
            onNodeWithText("Remove from list?").assertIsDisplayed()
            onNodeWithText("Cancel").assertIsDisplayed()
            onNodeWithText("Remove from list").assertIsDisplayed()
            onNodeWithText("Move files to Trash").performClick()
            waitForIdle()
            assertEquals(listOf("remove 2 TRASH"), shown.source.calls)
            shown.scope.cancel()
        }

    @Test
    fun `Remove from list in the dialog keeps the files`() =
        runComposeUiTest {
            val shown = show { it.downloads.value = listOf(finished()) }
            rightClickRow(2)
            onNodeWithTag("menu-REMOVE").performClick()
            onNodeWithText("Remove from list").performClick()
            waitForIdle()
            assertEquals(listOf("remove 2 KEEP"), shown.source.calls)
            shown.scope.cancel()
        }

    @Test
    fun `Escape closes the menu and does nothing else`() =
        runComposeUiTest {
            val shown = show { it.downloads.value = listOf(finished()) }
            rightClickRow(2)
            onNodeWithTag("menu-OPEN").assertIsDisplayed()
            press(shown, Key.Escape)
            waitForIdle()
            onNodeWithTag("menu-OPEN").assertDoesNotExist()
            onNodeWithText("1 selected").assertIsDisplayed()
            assertTrue(shown.backs.isEmpty())
            shown.scope.cancel()
        }

    @Test
    fun `the window's Escape key is ignored while the menu is open`() =
        runComposeUiTest {
            val shown = show { it.downloads.value = listOf(finished()) }
            rightClickRow(2)
            runOnIdle { shown.keys.dispatch(KeyEvent(Key.Escape, KeyEventType.KeyDown)) }
            waitForIdle()
            assertTrue(shown.backs.isEmpty())
            onNodeWithText("1 selected").assertIsDisplayed()
            shown.scope.cancel()
        }

    @Test
    fun `Escape sent to the popup itself closes the menu`() =
        runComposeUiTest {
            val shown = show { it.downloads.value = listOf(finished()) }
            rightClickRow(2)
            onNodeWithTag("menu-OPEN").performKeyInput { pressKey(Key.Escape) }
            waitForIdle()
            onNodeWithTag("menu-OPEN").assertDoesNotExist()
            assertTrue(shown.backs.isEmpty())
            shown.scope.cancel()
        }

    @Test
    fun `a click outside the menu closes it and keeps the selection`() =
        runComposeUiTest {
            val shown = show { it.downloads.value = listOf(finished()) }
            rightClickRow(2)
            onNodeWithTag("menu-OPEN").assertIsDisplayed()
            onAllNodes(isRoot())[0].performMouseInput {
                click(
                    androidx.compose.ui.geometry
                        .Offset(width - 2f, height - 2f),
                )
            }
            waitForIdle()
            onNodeWithTag("menu-OPEN").assertDoesNotExist()
            onNodeWithText("1 selected").assertIsDisplayed()
            shown.scope.cancel()
        }

    @Test
    fun `right-click inside a selection keeps it and Remove applies to all of it`() =
        runComposeUiTest {
            val shown =
                show { it.downloads.value = listOf(finished(), snapshot(3, COMPLETED, name = "b.pdf", size = 5, saveFolder = "/dl")) }
            press(shown, Key.A, command = true)
            onNodeWithText("2 selected").assertIsDisplayed()
            rightClickRow(2)
            onNodeWithText("2 selected").assertIsDisplayed()
            onNodeWithTag("menu-REMOVE").performClick()
            onNodeWithText("Move files to Trash").performClick()
            waitForIdle()
            assertEquals(listOf("remove 2 TRASH", "remove 3 TRASH"), shown.source.calls)
            shown.scope.cancel()
        }

    @Test
    fun `window shortcuts wait while the menu is open`() =
        runComposeUiTest {
            val shown = show { it.downloads.value = two() }
            rightClickRow(1)
            runOnIdle { shown.keys.dispatch(KeyEvent(Key.Delete, KeyEventType.KeyDown)) }
            waitForIdle()
            onNodeWithText("Remove from list?").assertDoesNotExist()
            shown.scope.cancel()
        }

    @Test
    fun `the filter menu narrows the list and counts each choice`() =
        runComposeUiTest {
            val shown = show { it.downloads.value = two() }
            onNodeWithText("All downloads").performClick()
            onNodeWithText("In progress").assertIsDisplayed()
            onNodeWithText("Needs attention").assertIsDisplayed()
            onNodeWithText("Finished").performClick()
            onNodeWithText("Project.zip").assertDoesNotExist()
            onNodeWithText("report.pdf").assertIsDisplayed()
            shown.scope.cancel()
        }

    @Test
    fun `a filter that matches nothing offers the whole list`() =
        runComposeUiTest {
            val shown = show { it.downloads.value = two() }
            onNodeWithText("All downloads").performClick()
            onNodeWithText("Needs attention").performClick()
            onNodeWithText("No downloads need attention").assertIsDisplayed()
            onNodeWithText("Show all downloads").performClick()
            onNodeWithText("Project.zip").assertIsDisplayed()
            shown.scope.cancel()
        }

    @Test
    fun `the footer shows free space and the switches act`() =
        runComposeUiTest {
            val shown =
                show {
                    it.transfer = TransferSettings(defaultSaveFolder = "/dl")
                    it.free["/dl"] = 78L * 1024 * 1024 * 1024
                }
            onNodeWithText("78.0 GB free").assertIsDisplayed()
            onNodeWithContentDescription("Slow mode").assertIsOff()
            onNodeWithContentDescription("Slow mode").performClick()
            waitForIdle()
            assertTrue(shown.source.transfer.slowMode)
            onNodeWithContentDescription("Slow mode").assertIsOn()
            onNodeWithText("Mbps").performClick()
            assertEquals(SpeedUnit.BITS, shown.prefs.speedUnit)
            onNodeWithContentDescription("Switch to light theme").performClick()
            assertEquals(ThemeChoice.LIGHT, shown.prefs.theme)
            shown.scope.cancel()
        }

    @Test
    fun `the unit switch changes how speeds read`() =
        runComposeUiTest {
            val shown =
                show {
                    it.downloads.value =
                        listOf(snapshot(1, DOWNLOADING, name = "a.bin", total = 100, downloaded = 1, speed = 1_000_000))
                }
            onNodeWithText("976.6 KB/s").assertIsDisplayed()
            onNodeWithText("Mbps").performClick()
            onNodeWithText("8.0 Mbps").assertIsDisplayed()
            shown.scope.cancel()
        }

    @Test
    fun `a downloading row names its limit and a fixable one opens Limits`() =
        runComposeUiTest {
            val shown =
                show {
                    it.downloads.value =
                        listOf(
                            snapshot(
                                1,
                                DOWNLOADING,
                                name = "a.bin",
                                total = 100,
                                downloaded = 1,
                                speed = 10,
                                limitedBy = SpeedLimit.SpeedCap,
                            ),
                            snapshot(
                                2,
                                DOWNLOADING,
                                name = "b.bin",
                                total = 100,
                                downloaded = 1,
                                speed = 10,
                                limitedBy = SpeedLimit.Unreachable("iPhone"),
                            ),
                            snapshot(3, DOWNLOADING, name = "c.bin", total = 100, downloaded = 1, speed = 10),
                        )
                }
            onNodeWithText("Limited by your speed limit").assertIsDisplayed()
            onNodeWithText("iPhone can't reach the server").assertIsDisplayed()
            assertEquals(1, onAllNodesWithText("Limited by", substring = true).fetchSemanticsNodes().size)
            onNodeWithText("Limited by your speed limit").performClick()
            assertEquals(listOf<String?>(null), shown.limits)
            assertEquals(emptyList(), shown.routed)
            onNodeWithText("iPhone can't reach the server").performClick()
            assertEquals(listOf<String?>(null), shown.limits)
            shown.scope.cancel()
        }

    @Test
    fun `a paused row shows no limit`() =
        runComposeUiTest {
            val shown =
                show {
                    it.downloads.value =
                        listOf(snapshot(1, PAUSED, name = "a.bin", total = 100, downloaded = 1, limitedBy = SpeedLimit.SpeedCap))
                }
            assertEquals(0, onAllNodesWithText("Limited by", substring = true).fetchSemanticsNodes().size)
            shown.scope.cancel()
        }

    @Test
    fun `a refused command shows its message`() =
        runComposeUiTest {
            val shown = show { it.downloads.value = listOf(snapshot(1, DOWNLOADING, name = "a.bin", total = 10, downloaded = 1)) }
            shown.source.refuseAll = true
            onNodeWithContentDescription("Pause a.bin").performClick()
            waitForIdle()
            onNodeWithText("The engine has stopped.").assertIsDisplayed()
            shown.scope.cancel()
        }

    @Test
    fun `the app shows why the engine failed instead of an empty list`() =
        runComposeUiTest {
            val source = FakeSource().apply { state.value = EngineState.Failed(IllegalStateException("the data folder can't be used")) }
            setContent { App(source, MemoryViewPrefs()) }
            onNodeWithText("anydm can't run").assertIsDisplayed()
            onNodeWithText("the data folder can't be used").assertIsDisplayed()
            onNodeWithText("No downloads yet").assertDoesNotExist()
        }

    @Test
    fun `the light theme paints with the light tokens`() =
        runComposeUiTest {
            val shown = show(prefs = MemoryViewPrefs(theme = ThemeChoice.LIGHT))
            onNodeWithContentDescription("Switch to dark theme").assertIsDisplayed()
            shown.scope.cancel()
        }
}
