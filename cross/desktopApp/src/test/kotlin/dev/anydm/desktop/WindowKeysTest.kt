package dev.anydm.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class, androidx.compose.ui.InternalComposeUiApi::class)
internal class WindowKeysTest {
    private fun down(key: Key) = KeyEvent(key, KeyEventType.KeyDown)

    private fun up(key: Key) = KeyEvent(key, KeyEventType.KeyUp)

    @Test
    fun `an unblocked window hands keys to the handler`() {
        val keys = WindowKeys()
        keys.handler = { true }
        assertTrue(keys.dispatch(down(Key.A)))
    }

    @Test
    fun `a blocked window leaves every key alone until the last block is released`() {
        val keys = WindowKeys()
        var seen = 0
        keys.handler = {
            seen++
            true
        }
        val first = keys.block()
        val second = keys.block()
        assertFalse(keys.dispatch(down(Key.A)))
        first.close()
        assertFalse(keys.dispatch(down(Key.A)))
        second.close()
        assertEquals(0, seen)
        assertTrue(keys.dispatch(down(Key.A)))
        assertEquals(1, seen)
    }

    @Test
    fun `releasing a block twice releases it once`() {
        val keys = WindowKeys()
        keys.handler = { true }
        val first = keys.block()
        val second = keys.block()
        first.close()
        first.close()
        assertFalse(keys.dispatch(down(Key.A)))
        second.close()
        assertTrue(keys.dispatch(down(Key.A)))
    }

    @Test
    fun `a key held down is a repeat from its second press until it is released`() {
        val keys = WindowKeys()
        val repeats = mutableListOf<Boolean>()
        keys.handler = {
            if (it.type == KeyEventType.KeyDown) repeats += keys.repeating
            false
        }
        keys.dispatch(down(Key.V))
        keys.dispatch(down(Key.V))
        keys.dispatch(down(Key.V))
        keys.dispatch(up(Key.V))
        keys.dispatch(down(Key.V))
        assertEquals(listOf(false, true, true, false), repeats)
    }

    @Test
    fun `a repeat is still counted while the window is blocked`() {
        val keys = WindowKeys()
        keys.handler = { false }
        val block = keys.block()
        keys.dispatch(down(Key.V))
        block.close()
        var repeat: Boolean? = null
        keys.handler = {
            repeat = keys.repeating
            false
        }
        keys.dispatch(down(Key.V))
        assertEquals(true, repeat)
    }

    @Test
    fun `cmd v three times in a row is three presses even when macOS loses the v key-up`() {
        val keys = WindowKeys()
        val repeats = mutableListOf<Boolean>()
        keys.handler = {
            if (it.type == KeyEventType.KeyDown && it.key == Key.V) repeats += keys.repeating
            false
        }
        repeat(3) {
            keys.dispatch(down(Key.MetaLeft))
            keys.dispatch(down(Key.V))
            // The V key-up never arrives while Cmd is held; only Cmd's own release does.
            keys.dispatch(up(Key.MetaLeft))
        }
        assertEquals(listOf(false, false, false), repeats)
    }

    @Test
    fun `releasing ctrl also forgets the keys held`() {
        val keys = WindowKeys()
        val repeats = mutableListOf<Boolean>()
        keys.handler = {
            if (it.type == KeyEventType.KeyDown && it.key == Key.V) repeats += keys.repeating
            false
        }
        keys.dispatch(down(Key.CtrlRight))
        keys.dispatch(down(Key.V))
        keys.dispatch(up(Key.CtrlRight))
        keys.dispatch(down(Key.V))
        assertEquals(listOf(false, false), repeats)
    }

    @Test
    fun `a window that loses focus forgets the keys held, whose key-ups it will not see`() {
        val keys = WindowKeys()
        var repeat: Boolean? = null
        keys.handler = {
            repeat = keys.repeating
            false
        }
        keys.dispatch(down(Key.V))
        keys.releaseAll()
        keys.dispatch(down(Key.V))
        assertEquals(false, repeat)
    }

    // The window's shortcuts take Backspace and Select all; a text field in a dialog must still get them.
    private fun androidx.compose.ui.test.ComposeUiTest.field(
        keys: WindowKeys,
        blocked: Boolean,
    ) {
        keys.handler = { it.type == KeyEventType.KeyDown }
        setContent {
            var text by remember { mutableStateOf("hello") }
            if (blocked) BlockWindowKeys(keys)
            Box(Modifier.onPreviewKeyEvent { keys.dispatch(it) }) {
                BasicTextField(text, { text = it }, Modifier.semantics { contentDescription = "field" })
            }
        }
    }

    @Test
    fun `backspace and select all reach a focused text field while a surface blocks the window`() =
        runComposeUiTest {
            field(WindowKeys(), blocked = true)
            onNodeWithContentDescription("field").performClick()
            onNodeWithContentDescription("field").performKeyInput { pressKey(Key.Backspace) }
            onNodeWithContentDescription("field").assertTextEquals("hell")
            onNodeWithContentDescription("field").performKeyInput {
                withKeyDown(if (IsMac) Key.MetaLeft else Key.CtrlLeft) { pressKey(Key.A) }
                pressKey(Key.Backspace)
            }
            onNodeWithContentDescription("field").assertTextEquals("")
        }

    @Test
    fun `without a block the window's shortcut takes backspace from the field`() =
        runComposeUiTest {
            field(WindowKeys(), blocked = false)
            onNodeWithContentDescription("field").performClick()
            onNodeWithContentDescription("field").performKeyInput { pressKey(Key.Backspace) }
            onNodeWithContentDescription("field").assertTextEquals("hello")
        }

    @Test
    fun `the window losing focus makes the next press of a held key a new press`() =
        runComposeUiTest {
            val keys = WindowKeys()
            var focused by mutableStateOf(true)
            val info =
                object : WindowInfo {
                    override val isWindowFocused: Boolean get() = focused
                }
            setContent { CompositionLocalProvider(LocalWindowInfo provides info) { ReleaseKeysOnBlur(keys) } }
            keys.dispatch(down(Key.V))
            keys.dispatch(down(Key.V))
            assertTrue(keys.repeating)

            focused = false
            waitForIdle()
            keys.dispatch(down(Key.V))

            assertFalse(keys.repeating)
        }

    @Test
    fun `a window that keeps focus keeps the keys held`() =
        runComposeUiTest {
            val keys = WindowKeys()
            val info =
                object : WindowInfo {
                    override val isWindowFocused: Boolean get() = true
                }
            setContent { CompositionLocalProvider(LocalWindowInfo provides info) { ReleaseKeysOnBlur(keys) } }
            keys.dispatch(down(Key.V))
            waitForIdle()
            keys.dispatch(down(Key.V))

            assertTrue(keys.repeating)
        }

    // An update dialog is a surface the window's shortcuts must stand aside for, and must give back when it is gone.
    @Test
    fun `an update notice blocks the window's shortcuts while it is shown and gives them back when dismissed`() =
        runComposeUiTest {
            val keys = WindowKeys()
            keys.handler = { true }
            val controls = FakeUpdateControls()
            val session = UpdateSession(controls, FakeFileActions(), CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined)
            setContent {
                PlexoTheme(ThemeChoice.DARK) {
                    LaunchedEffect(session) { session.collectEvents() }
                    UpdateDialogs(session, keys)
                }
            }
            assertFalse(keys.blocked)
            assertTrue(keys.dispatch(down(Key.A)))

            controls.emit(UpdateEvent.UpToDate)
            onNodeWithTag("update-notice-dialog").assertIsDisplayed()
            assertTrue(keys.blocked)
            assertFalse(keys.dispatch(down(Key.Backspace)))

            onNodeWithTag("update-notice-ok").performClick()
            onNodeWithTag("update-notice-dialog").assertDoesNotExist()
            assertFalse(keys.blocked)
            assertTrue(keys.dispatch(down(Key.A)))
        }

    @Test
    fun `the restart question blocks the window's shortcuts until it is answered`() =
        runComposeUiTest {
            val keys = WindowKeys()
            keys.handler = { true }
            val controls = FakeUpdateControls(UpdateState.Ready(Version.parse("1.2.0")!!, Path.of("anydm-1.2.0.dmg")))
            controls.unconfirmed = RestartResult.NeedsConfirmation(2)
            val session = UpdateSession(controls, FakeFileActions(), CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined)
            setContent { PlexoTheme(ThemeChoice.DARK) { UpdateDialogs(session, keys) } }

            session.restart()
            onNodeWithTag("update-confirm-dialog").assertIsDisplayed()
            assertTrue(keys.blocked)
            assertFalse(keys.dispatch(down(Key.A)))

            session.dismiss()
            onNodeWithTag("update-confirm-dialog").assertDoesNotExist()
            assertFalse(keys.blocked)
        }
}
