package dev.anydm.desktop

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import dev.anydm.engine.EngineEvent
import dev.anydm.engine.EngineState
import dev.anydm.engine.SpeedWindow
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.DownloadStatus.COMPLETED
import dev.anydm.engine.core.DownloadStatus.DOWNLOADING
import dev.anydm.engine.core.DownloadStatus.FAILED
import dev.anydm.engine.core.DownloadStatus.PAUSED
import dev.anydm.engine.core.DownloadStatus.QUEUED
import dev.anydm.engine.core.PauseReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class ShellTest {
    @get:Rule
    val rule = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val source = FakeSource()
    private val files = FakeFiles()
    private var welcomeDone = true
    private val firstRun =
        object : FirstRun {
            override fun isDone() = welcomeDone

            override fun markDone() {
                welcomeDone = true
            }
        }
    private lateinit var model: AppModel

    @AfterTest
    fun close() = scope.cancel()

    private fun show(dark: Boolean = false) {
        model = AppModel(source, firstRun, scope, files)
        model.start()
        rule.setContent { AnydmTheme(dark = dark) { Shell(model) } }
    }

    @Test
    fun `the sidebar shows a count for every status`() {
        source.downloads.value =
            listOf(
                snapshot(1, DOWNLOADING),
                snapshot(2, DOWNLOADING),
                snapshot(3, DOWNLOADING),
                snapshot(4, QUEUED),
                snapshot(5, QUEUED),
                snapshot(6, FAILED),
            )
        show()
        rule.onNodeWithTag("count-all", useUnmergedTree = true).assertTextEquals("6")
        rule.onNodeWithTag("count-active", useUnmergedTree = true).assertTextEquals("3")
        rule.onNodeWithTag("count-queued", useUnmergedTree = true).assertTextEquals("2")
        rule.onNodeWithTag("count-paused", useUnmergedTree = true).assertTextEquals("0")
        rule.onNodeWithTag("count-completed", useUnmergedTree = true).assertTextEquals("0")
        rule.onNodeWithTag("count-failed", useUnmergedTree = true).assertTextEquals("1")
    }

    @Test
    fun `counts change when a download finishes`() {
        source.downloads.value = listOf(snapshot(1, DOWNLOADING))
        show()
        rule.onNodeWithTag("count-active", useUnmergedTree = true).assertTextEquals("1")
        source.downloads.value = listOf(snapshot(1, COMPLETED))
        rule.waitForIdle()
        rule.onNodeWithTag("count-active", useUnmergedTree = true).assertTextEquals("0")
        rule.onNodeWithTag("count-completed", useUnmergedTree = true).assertTextEquals("1")
    }

    @Test
    fun `an empty section says so`() {
        show()
        rule.onNodeWithTag("empty").assertIsDisplayed()
    }

    @Test
    fun `clicking a sidebar item changes the route`() {
        show()
        rule.onNodeWithTag("nav-failed").performClick()
        assertEquals(Route.Downloads(Filter.Status(StatusGroup.FAILED)), model.state.value.route)
        rule.onNodeWithTag("nav-settings").performClick()
        assertEquals(Route.Settings, model.state.value.route)
        rule.onNodeWithTag("settings").assertIsDisplayed()
    }

    @Test
    fun `the chosen protocol is selected and another protocol is not`() {
        show()
        rule.onNodeWithTag("nav-protocol-torrent").performClick()
        rule.onNodeWithTag("nav-protocol-torrent").assertIsSelected()
        rule.onNodeWithTag("nav-protocol-ftp").assertIsNotSelected()
    }

    @Test
    fun `no protocol shows as selected while settings is open`() {
        show()
        rule.onNodeWithTag("nav-protocol-torrent").performClick()
        rule.onNodeWithTag("nav-settings").performClick()
        rule.onNodeWithTag("nav-protocol-torrent").assertIsNotSelected()
        rule.onNodeWithTag("nav-settings").assertIsSelected()
    }

    @Test
    fun `clicking a protocol in settings leaves settings for the downloads`() {
        show()
        rule.onNodeWithTag("nav-settings").performClick()
        assertEquals(Route.Settings, model.state.value.route)
        rule.onNodeWithTag("nav-protocol-torrent").performClick()
        assertEquals(Route.Downloads(Filter.All), model.state.value.route)
        assertEquals(Protocol.TORRENT, model.state.value.options.protocol)
    }

    @Test
    fun `the title names the active protocol`() {
        assertEquals("All downloads", title(Route.Downloads(Filter.All)))
        assertEquals("All downloads · Torrent", title(Route.Downloads(Filter.All), Protocol.TORRENT))
        assertEquals("Settings", title(Route.Settings, Protocol.TORRENT))
        show()
        rule.onNodeWithTag("nav-protocol-torrent").performClick()
        rule.onNodeWithTag("toolbar-title").assertTextContains("All downloads · Torrent", substring = true)
    }

    // Intended: the protocol is a second filter beside the status, so it stays when the status changes.
    @Test
    fun `the protocol stays when another status filter is chosen`() {
        show()
        rule.onNodeWithTag("nav-protocol-torrent").performClick()
        rule.onNodeWithTag("nav-failed").performClick()
        assertEquals(Protocol.TORRENT, model.state.value.options.protocol)
        rule.onNodeWithTag("toolbar-title").assertTextContains("Torrent", substring = true)
    }

    @Test
    fun `the status bar shows the engine and the speed`() {
        show()
        rule.onNodeWithTag("status-engine").assertTextEquals("Running")
        source.totalSpeed.value = SpeedWindow(2_500_000, emptyList())
        rule.waitForIdle()
        rule.onNodeWithTag("status-speed").assertTextEquals("2.4 MB/s")
    }

    private val command = if (IsMac) Key.MetaLeft else Key.CtrlLeft

    @Test
    fun `keyboard shortcuts switch sections`() {
        show()
        rule.onRoot().performKeyInput {
            keyDown(command)
            pressKey(Key.Four)
            keyUp(command)
        }
        assertEquals(Route.Downloads(Filter.Status(StatusGroup.PAUSED)), model.state.value.route)
        rule.onRoot().performKeyInput {
            keyDown(command)
            pressKey(Key.Comma)
            keyUp(command)
        }
        assertEquals(Route.Settings, model.state.value.route)
    }

    @Test
    fun `shift or alt with the command key is not a shortcut`() {
        show()
        rule.onRoot().performKeyInput {
            keyDown(command)
            keyDown(Key.ShiftLeft)
            pressKey(Key.Four)
            keyUp(Key.ShiftLeft)
            keyDown(Key.AltLeft)
            pressKey(Key.Four)
            keyUp(Key.AltLeft)
            keyUp(command)
        }
        assertEquals(Route.Downloads(Filter.All), model.state.value.route)
    }

    @Test
    fun `shortcuts do nothing under the welcome`() {
        welcomeDone = false
        show()
        rule.onRoot().performKeyInput {
            keyDown(command)
            pressKey(Key.Four)
            keyUp(command)
        }
        assertEquals(Route.Downloads(Filter.All), model.state.value.route)
    }

    @Test
    fun `a failed engine shows a banner that can be dismissed`() {
        show()
        source.state.value = EngineState.Failed(IllegalStateException("db gone"))
        rule.waitForIdle()
        rule.onNodeWithTag("banner-engine").assertIsDisplayed()
        rule.onNodeWithText("db gone", substring = true).assertIsDisplayed()
        rule.onNodeWithTag("banner-dismiss").performClick()
        rule.onNodeWithTag("banner-engine").assertDoesNotExist()
    }

    @Test
    fun `a full disk shows a banner`() {
        show()
        source.downloads.value = listOf(snapshot(1, PAUSED, pauseReasons = setOf(PauseReason.LOW_DISK)))
        rule.waitForIdle()
        rule.onNodeWithTag("banner-disk").assertIsDisplayed()
    }

    @Test
    fun `an error event shows a banner`() {
        show()
        runBlocking { source.events.emit(EngineEvent.CookiesNotSaved) }
        rule.waitForIdle()
        rule.onNodeWithTag("banner-error").assertIsDisplayed()
    }

    @Test
    fun `the welcome shows once`() {
        welcomeDone = false
        show()
        rule.onNodeWithTag("welcome").assertIsDisplayed()
        rule.onNodeWithTag("welcome-start").performClick()
        rule.onNodeWithTag("welcome").assertDoesNotExist()
        assertEquals(true, welcomeDone)
    }

    private fun protocolMix() {
        source.downloads.value =
            listOf(
                snapshot(1, DOWNLOADING, name = "a-http"),
                snapshot(2, COMPLETED, name = "b-http"),
                snapshot(3, DOWNLOADING, name = "c-http"),
                snapshot(4, DOWNLOADING, name = "d-tor", kind = DownloadKind.TORRENT),
                snapshot(5, COMPLETED, name = "e-tor", kind = DownloadKind.TORRENT),
                snapshot(6, QUEUED, name = "f-ftp", kind = DownloadKind.FTP),
            )
    }

    @Test
    fun `the sidebar lists the protocols with their counts`() {
        protocolMix()
        show()
        rule.onNodeWithTag("count-protocol-http", useUnmergedTree = true).assertTextEquals("3")
        rule.onNodeWithTag("count-protocol-site", useUnmergedTree = true).assertTextEquals("0")
        rule.onNodeWithTag("count-protocol-torrent", useUnmergedTree = true).assertTextEquals("2")
        rule.onNodeWithTag("count-protocol-ftp", useUnmergedTree = true).assertTextEquals("1")
        rule.onNodeWithTag("count-protocol-tool", useUnmergedTree = true).assertTextEquals("0")
    }

    @Test
    fun `clicking a protocol filters the list and clicking it again clears it`() {
        protocolMix()
        show()
        rule.onNodeWithTag("nav-protocol-torrent").performClick()
        assertEquals(Protocol.TORRENT, model.state.value.options.protocol)
        assertEquals(
            listOf(4L, 5L),
            model.state.value.rows
                .map { it.snapshot.download.id.value }
                .sorted(),
        )
        rule.onNodeWithTag("nav-protocol-torrent").performClick()
        assertEquals(null, model.state.value.options.protocol)
        assertEquals(6, model.state.value.rows.size)
    }

    @Test
    fun `clicking another protocol switches the selection`() {
        protocolMix()
        show()
        rule.onNodeWithTag("nav-protocol-torrent").performClick()
        rule.onNodeWithTag("nav-protocol-ftp").performClick()
        assertEquals(Protocol.FTP, model.state.value.options.protocol)
        assertEquals(
            listOf(6L),
            model.state.value.rows
                .map { it.snapshot.download.id.value },
        )
    }

    @Test
    fun `a protocol combines with a status filter and the query`() {
        protocolMix()
        show()
        rule.onNodeWithTag("nav-protocol-torrent").performClick()
        rule.onNodeWithTag("nav-completed").performClick()
        assertEquals(
            listOf(5L),
            model.state.value.rows
                .map { it.snapshot.download.id.value },
        )
        assertEquals(Protocol.TORRENT, model.state.value.options.protocol)
        rule.onNodeWithTag("nav-all").performClick()
        model.setQuery("d-")
        assertEquals(
            listOf(4L),
            model.state.value.rows
                .map { it.snapshot.download.id.value },
        )
        // Counts stay over all downloads.
        rule.onNodeWithTag("count-protocol-http", useUnmergedTree = true).assertTextEquals("3")
    }

    @Test
    fun `a protocol with nothing in it shows the no-match placeholder`() {
        protocolMix()
        show()
        rule.onNodeWithTag("nav-protocol-tool").performClick()
        rule.onNodeWithTag("no-match").assertIsDisplayed()
    }

    @Test
    fun `it renders in dark`() {
        show(dark = true)
        rule.onNodeWithTag("count-all", useUnmergedTree = true).assertIsDisplayed()
    }
}
