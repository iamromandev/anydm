package dev.anydm.desktop.theme

import kotlin.test.Test
import kotlin.test.assertEquals

class PlatformTest {
    @Test
    fun `shortcuts read as the OS writes them`() {
        assertEquals(true, isMac("Mac OS X"))
        assertEquals(false, isMac("Windows 11"))
        assertEquals("⌘S", shortcutLabel("S", "Mac OS X"))
        assertEquals("Ctrl+S", shortcutLabel("S", "Linux"))
    }
}
