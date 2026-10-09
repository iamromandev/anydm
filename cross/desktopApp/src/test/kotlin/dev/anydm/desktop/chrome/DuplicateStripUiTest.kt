package dev.anydm.desktop.chrome

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.anydm.api.Duplicate
import dev.anydm.desktop.theme.DesktopTheme
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class DuplicateStripUiTest {
    private val held = Duplicate("d1", "Old clip", "completed")

    @Test
    fun `it names the download and its status`() =
        runComposeUiTest {
            setContent { DesktopTheme(dark = false) { DuplicateStrip(held, onOpen = {}, onDismiss = {}, onAddAnyway = {}) } }
            onNodeWithText("Already in your list: Old clip (completed)").assertExists()
        }

    @Test
    fun `a video a playlist holds names its playlist`() =
        runComposeUiTest {
            val inList = held.copy(collectionId = "g1", collectionTitle = "Talks")
            setContent { DesktopTheme(dark = false) { DuplicateStrip(inList, onOpen = {}, onDismiss = {}, onAddAnyway = {}) } }
            onNodeWithText("Already in your list: Old clip (completed), in Talks").assertExists()
        }

    @Test
    fun `Open and Add anyway each run their own action`() =
        runComposeUiTest {
            var opened = 0
            var again = 0
            setContent {
                DesktopTheme(dark = false) { DuplicateStrip(held, onOpen = { opened += 1 }, onDismiss = {}, onAddAnyway = { again += 1 }) }
            }
            onNodeWithText("Open").performClick()
            assertEquals(1 to 0, opened to again)
            onNodeWithText("Add anyway").performClick()
            assertEquals(1 to 1, opened to again)
        }

    @Test
    fun `a torrent offers Open and no second copy`() =
        runComposeUiTest {
            setContent { DesktopTheme(dark = false) { DuplicateStrip(held, onOpen = {}, onDismiss = {}) } }
            onNodeWithText("Open").assertExists()
            onNodeWithText("Add anyway").assertDoesNotExist()
        }

    @Test
    fun `dismissing runs the dismiss action`() =
        runComposeUiTest {
            var dismissed = 0
            setContent { DesktopTheme(dark = false) { DuplicateStrip(held, onOpen = {}, onDismiss = { dismissed += 1 }) } }
            onNodeWithText("✕").performClick()
            assertEquals(1, dismissed)
        }
}
