package dev.anydm.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import dev.anydm.engine.EngineState
import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadStatus.DOWNLOADING
import dev.anydm.engine.net.BindStatus
import dev.anydm.engine.net.BondedNetwork
import dev.anydm.engine.net.BondingState
import dev.anydm.engine.net.NetworkInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalTestApi::class)
internal class AppNavigationTest {
    private val mib = 1024L * 1024

    private fun onDownloads() = Navigator().apply { downloads() }

    @Test
    fun `the window starts on the landing page`() {
        assertEquals(Route.Landing, Navigator().route.value)
    }

    @Test
    fun `back goes from a page to the list, from the list to the landing page, and stays on the landing page`() {
        val navigator = Navigator()
        navigator.downloads()
        assertEquals(Route.Downloads, navigator.route.value)
        navigator.open(DownloadId(1))
        assertEquals(Route.Detail(DownloadId(1)), navigator.route.value)
        navigator.back()
        assertEquals(Route.Downloads, navigator.route.value)
        navigator.back()
        assertEquals(Route.Landing, navigator.route.value)
        navigator.back()
        assertEquals(Route.Landing, navigator.route.value)
        navigator.downloads()
        navigator.landing()
        assertEquals(Route.Landing, navigator.route.value)
    }

    @Test
    fun `the landing page offers the downloads list with its count`() =
        runComposeUiTest {
            val source = FakeSource()
            source.downloads.value =
                listOf(snapshot(1, DOWNLOADING, name = "Project.zip", total = 200 * mib, downloaded = 100 * mib, speed = mib))
            setContent { App(source, MemoryViewPrefs(), files = FakeFileActions()) }
            onNodeWithText("DOWNLOADING").assertDoesNotExist()
            onNodeWithText("Downloads (1)").performClick()
            onNodeWithText("DOWNLOADING").assertIsDisplayed()
        }

    @Test
    fun `a row opens its page and back returns to the list`() =
        runComposeUiTest {
            val source = FakeSource()
            source.downloads.value =
                listOf(snapshot(1, DOWNLOADING, name = "Project.zip", total = 200 * mib, downloaded = 100 * mib, speed = mib))
            setContent { App(source, MemoryViewPrefs(), files = FakeFileActions(), navigator = onDownloads()) }
            onNodeWithText("DOWNLOADING").assertIsDisplayed()
            onNodeWithContentDescription("Open Project.zip").performClick()
            onNodeWithText("TOTAL SPEED").assertIsDisplayed()
            onNodeWithContentDescription("Back to downloads").performClick()
            onNodeWithText("DOWNLOADING").assertIsDisplayed()
        }

    @Test
    fun `the name opens the page too`() =
        runComposeUiTest {
            val source = FakeSource()
            source.downloads.value =
                listOf(snapshot(1, DOWNLOADING, name = "Project.zip", total = 200 * mib, downloaded = 100 * mib, speed = mib))
            setContent { App(source, MemoryViewPrefs(), files = FakeFileActions(), navigator = onDownloads()) }
            onNodeWithText("Project.zip").performClick()
            // A single click waits out the double-click window.
            mainClock.advanceTimeBy(1_000)
            onNodeWithText("TOTAL SPEED").assertIsDisplayed()
        }

    @Test
    fun `the paste shortcut on a download's page goes to the landing page with the link in the field`() =
        runComposeUiTest {
            val source = FakeSource()
            source.clipboardText = "https://example.com/files/Other.zip"
            source.downloads.value =
                listOf(snapshot(1, DOWNLOADING, name = "Project.zip", total = 200 * mib, downloaded = 100 * mib, speed = mib))
            val keys = WindowKeys()
            val window = FocusRequester()
            setContent {
                Box(Modifier.focusRequester(window).focusTarget().onPreviewKeyEvent { keys.dispatch(it) }) {
                    App(source, MemoryViewPrefs(), keys, files = FakeFileActions(), navigator = onDownloads())
                }
                LaunchedEffect(Unit) { window.requestFocus() }
            }
            onNodeWithContentDescription("Open Project.zip").performClick()
            onNodeWithText("TOTAL SPEED").assertIsDisplayed()
            runOnIdle { window.requestFocus() }
            onAllNodes(isRoot())[0].performKeyInput {
                withKeyDown(if (IsMac) Key.MetaLeft else Key.CtrlLeft) { pressKey(Key.V) }
            }
            waitUntil(timeoutMillis = 5_000) {
                onAllNodes(
                    androidx.compose.ui.test
                        .hasContentDescription("Link"),
                ).fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithContentDescription("Link").assertTextEquals("https://example.com/files/Other.zip")
        }

    @Test
    fun `an open that arrives on the downloads list lands on the landing page with the field filled`() =
        runComposeUiTest {
            val source = FakeSource()
            val opens = PendingOpens()
            val navigator = onDownloads()
            setContent { App(source, MemoryViewPrefs(), files = FakeFileActions(), navigator = navigator, opens = opens) }
            opens.offer(listOf("https://example.com/a.zip"))
            waitUntil(timeoutMillis = 5_000) { navigator.route.value == Route.Landing }
            waitUntil(timeoutMillis = 5_000) {
                runCatching { onNodeWithContentDescription("Link").assertTextEquals("https://example.com/a.zip") }.isSuccess
            }
        }

    @Test
    fun `the new download button on the list goes to the landing page`() =
        runComposeUiTest {
            val navigator = onDownloads()
            setContent { App(FakeSource(), MemoryViewPrefs(), files = FakeFileActions(), navigator = navigator) }
            onAllNodesWithText("New download")[0].performClick()
            assertEquals(Route.Landing, navigator.route.value)
        }

    @Test
    fun `an open that arrives while the engine has failed waits for a window that can show it`() =
        runComposeUiTest {
            val source = FakeSource().apply { state.value = EngineState.Failed(IllegalStateException("no database")) }
            val opens = PendingOpens()
            setContent { App(source, MemoryViewPrefs(), files = FakeFileActions(), opens = opens) }
            opens.offer(listOf("https://example.com/a.zip"))
            waitForIdle()
            Thread.sleep(200)
            waitForIdle()
            assertEquals(listOf("https://example.com/a.zip"), opens.requests.tryReceive().getOrNull())
            assertNull(opens.requests.tryReceive().getOrNull())
        }

    @Test
    fun `the binding notice shows once for unsupported networks and Got it removes it for good`() =
        runComposeUiTest {
            val source = FakeSource()

            fun net(key: String) = BondedNetwork(NetworkInfo(key, key, true, emptyList()), true, BindStatus.UNSUPPORTED, null, false)
            source.networks.value = BondingState(listOf(net("en0"), net("en1")))
            setContent { App(source, MemoryViewPrefs(), files = FakeFileActions()) }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("binding-dialog").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("binding-ok").performClick()
            onNodeWithTag("binding-dialog").assertDoesNotExist()

            source.networks.value = BondingState(listOf(net("en0")))
            source.networks.value = BondingState(listOf(net("en0"), net("en2")))
            waitForIdle()
            Thread.sleep(300)
            waitForIdle()
            onNodeWithTag("binding-dialog").assertDoesNotExist()
        }

    @Test
    fun `the downloads header shows the update button when the app has an updater`() =
        runComposeUiTest {
            val controls = FakeUpdateControls()
            setContent { App(FakeSource(), MemoryViewPrefs(), files = FakeFileActions(), navigator = onDownloads(), updates = controls) }
            onNodeWithTag("update-button").assertIsDisplayed()
        }

    @Test
    fun `the downloads header has no update button without an updater`() =
        runComposeUiTest {
            setContent { App(FakeSource(), MemoryViewPrefs(), files = FakeFileActions(), navigator = onDownloads()) }
            onNodeWithText("Browsers").assertIsDisplayed()
            onNodeWithTag("update-button").assertDoesNotExist()
        }

    @Test
    fun `a manual check's result shows on whatever page is open`() =
        runComposeUiTest {
            val controls = FakeUpdateControls()
            setContent { App(FakeSource(), MemoryViewPrefs(), files = FakeFileActions(), updates = controls) }
            controls.emit(UpdateEvent.UpToDate)
            onNodeWithText("You're up to date").assertIsDisplayed()
        }
}
