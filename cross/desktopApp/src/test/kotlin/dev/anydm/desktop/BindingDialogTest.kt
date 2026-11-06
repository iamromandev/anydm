package dev.anydm.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class, androidx.compose.ui.InternalComposeUiApi::class)
internal class BindingDialogTest {
    private fun ComposeUiTest.show(
        theme: ThemeChoice = ThemeChoice.DARK,
        keys: WindowKeys = WindowKeys(),
        block: (IntArray) -> Unit,
    ) {
        val closed = intArrayOf(0)
        setContent { PlexoTheme(theme) { BindingDialog(keys) { closed[0]++ } } }
        block(closed)
    }

    @Test
    fun `it says only the default network can be used`() =
        runComposeUiTest {
            show {
                onNodeWithTag("binding-dialog").assertIsDisplayed()
                onNodeWithText("Only your default network can be used").assertIsDisplayed()
                onNodeWithText("Got it").assertIsDisplayed()
            }
        }

    @Test
    fun `Got it dismisses`() =
        runComposeUiTest {
            show { closed ->
                onNodeWithTag("binding-ok").performClick()
                assertEquals(1, closed[0])
            }
        }

    @Test
    fun `Escape dismisses`() =
        runComposeUiTest {
            show { closed ->
                onNodeWithTag("binding-dialog").performKeyInput { pressKey(Key.Escape) }
                assertEquals(1, closed[0])
            }
        }

    @Test
    fun `Enter dismisses`() =
        runComposeUiTest {
            show { closed ->
                onNodeWithTag("binding-dialog").performKeyInput { pressKey(Key.Enter) }
                assertEquals(1, closed[0])
            }
        }

    @Test
    fun `it is shown in the light theme too`() =
        runComposeUiTest {
            show(theme = ThemeChoice.LIGHT) {
                onNodeWithTag("binding-dialog").assertIsDisplayed()
                onNodeWithText("Got it").assertIsDisplayed()
            }
        }

    @Test
    fun `a click outside the card does not dismiss`() =
        runComposeUiTest {
            show { closed ->
                onAllNodes(isRoot())[0].performMouseInput { click(Offset(2f, 2f)) }
                assertEquals(0, closed[0])
                onNodeWithTag("binding-dialog").assertIsDisplayed()
            }
        }

    @Test
    fun `another key does not dismiss`() =
        runComposeUiTest {
            show { closed ->
                onNodeWithTag("binding-dialog").performKeyInput { pressKey(Key.A) }
                assertEquals(0, closed[0])
            }
        }

    @Test
    fun `the window's keys are blocked while it is shown and released once it is gone`() =
        runComposeUiTest {
            val keys = WindowKeys()
            keys.handler = { true }
            var shown by mutableStateOf(true)
            setContent { PlexoTheme(ThemeChoice.DARK) { if (shown) BindingDialog(keys) {} } }
            waitForIdle()
            assertFalse(keys.dispatch(KeyEvent(Key.A, KeyEventType.KeyDown)))
            shown = false
            waitForIdle()
            assertTrue(keys.dispatch(KeyEvent(Key.A, KeyEventType.KeyDown)))
        }
}
