package dev.anydm.desktop.theme

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TokensTest {
    @Test
    fun `contrast is the WCAG ratio`() {
        assertEquals(21.0, contrast(Color.Black, Color.White), 0.01)
        assertEquals(1.0, contrast(Color(0xFF777777), Color(0xFF777777)), 0.01)
    }

    @Test
    fun `every text colour reads on every surface, light and dark`() {
        for (t in listOf(LightTokens, DarkTokens)) {
            for (surface in listOf(t.content, t.sidebar, t.bar, t.hover)) {
                assertTrue(contrast(t.text, surface) >= 4.5, "text on $surface in ${t.name}")
                assertTrue(contrast(t.secondaryText, surface) >= 4.5, "secondary on $surface in ${t.name}")
            }
            assertTrue(contrast(t.onSelection, t.selection) >= 4.5, "selection in ${t.name}")
            assertTrue(contrast(Color.White, t.destructive) >= 4.5, "destructive in ${t.name}")
        }
    }
}
