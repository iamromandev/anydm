package dev.anydm.desktop.list

import androidx.compose.ui.input.key.Key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CommandTest {
    private fun mac(
        key: Key,
        meta: Boolean = false,
        shift: Boolean = false,
        inText: Boolean = false,
    ) = commandFor(key, meta = meta, ctrl = false, shift = shift, mac = true, inText = inText)

    @Test
    fun `the list's keys, on a Mac`() {
        assertEquals(Command.PAUSE_RESUME, mac(Key.Spacebar))
        assertEquals(Command.OPEN, mac(Key.Enter))
        assertEquals(Command.REMOVE, mac(Key.Backspace))
        assertEquals(Command.REMOVE, mac(Key.Delete))
        assertEquals(Command.SELECT_ALL, mac(Key.A, meta = true))
        assertEquals(Command.COPY, mac(Key.C, meta = true))
        assertEquals(Command.PASTE, mac(Key.V, meta = true))
        assertEquals(Command.CLEAR, mac(Key.Escape))
        assertEquals(Command.DOWN, mac(Key.DirectionDown))
        assertEquals(Command.EXTEND_UP, mac(Key.DirectionUp, shift = true))
        assertNull(mac(Key.A))
    }

    @Test
    fun `Ctrl is the modifier elsewhere, and ⌘ doesn't count there`() {
        assertEquals(Command.COPY, commandFor(Key.C, meta = false, ctrl = true, shift = false, mac = false, inText = false))
        assertNull(commandFor(Key.C, meta = true, ctrl = false, shift = false, mac = false, inText = false))
    }

    @Test
    fun `a text field keeps its own keys, and the menu bar's keys aren't handled twice`() {
        assertNull(mac(Key.Spacebar, inText = true))
        assertNull(mac(Key.Backspace, inText = true))
        assertNull(mac(Key.C, meta = true, inText = true))
        assertNull(mac(Key.S, meta = true))
        assertNull(mac(Key.L, meta = true))
    }
}
