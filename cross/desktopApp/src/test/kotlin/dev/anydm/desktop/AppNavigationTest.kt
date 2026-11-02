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
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import dev.anydm.engine.EngineState
import dev.anydm.engine.core.DownloadStatus.DOWNLOADING
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalTestApi::class)
internal class AppNavigationTest {
    private val mib = 1024L * 1024

    @Test
    fun `a row opens its page and back returns to the list`() =
        runComposeUiTest {
            val source = FakeSource()
            source.downloads.value =
                listOf(snapshot(1, DOWNLOADING, name = "Project.zip", total = 200 * mib, downloaded = 100 * mib, speed = mib))
            setContent { App(source, MemoryViewPrefs(), files = FakeFileActions()) }
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
            setContent { App(source, MemoryViewPrefs(), files = FakeFileActions()) }
            onNodeWithText("Project.zip").performClick()
            onNodeWithText("TOTAL SPEED").assertIsDisplayed()
        }

    @Test
    fun `the paste shortcut on a download's page opens the new download dialog`() =
        runComposeUiTest {
            val source = FakeSource()
            source.clipboardText = "https://example.com/files/Other.zip"
            source.downloads.value =
                listOf(snapshot(1, DOWNLOADING, name = "Project.zip", total = 200 * mib, downloaded = 100 * mib, speed = mib))
            val keys = WindowKeys()
            val window = FocusRequester()
            setContent {
                Box(Modifier.focusRequester(window).focusTarget().onPreviewKeyEvent { keys.dispatch(it) }) {
                    App(source, MemoryViewPrefs(), keys, files = FakeFileActions())
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
            onNodeWithContentDescription("Link").assertIsDisplayed()
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
}
