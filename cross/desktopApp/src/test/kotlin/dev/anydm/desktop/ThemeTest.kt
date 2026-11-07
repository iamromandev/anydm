package dev.anydm.desktop

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.test.Test
import kotlin.test.assertTrue

class ThemeTest {
    private fun contrast(
        a: Color,
        b: Color,
    ): Float {
        val hi = maxOf(a.luminance(), b.luminance())
        val lo = minOf(a.luminance(), b.luminance())
        return (hi + 0.05f) / (lo + 0.05f)
    }

    private fun check(c: AnydmColors) {
        assertTrue(contrast(c.text, c.bg) >= 4.5f, "text on bg")
        assertTrue(contrast(c.text, c.surface) >= 4.5f, "text on surface")
        assertTrue(contrast(c.textSecondary, c.bg) >= 4.5f, "secondary on bg")
        assertTrue(contrast(c.onAccent, c.accent) >= 4.5f, "onAccent on accent")
    }

    @Test
    fun darkPaletteIsLegible() = check(darkColors())

    @Test
    fun lightPaletteIsLegible() = check(lightColors())

    @Test
    fun accentIsNotAmber() {
        for (c in listOf(darkColors(), lightColors())) {
            assertTrue(c.accent.blue > c.accent.red * 0.9f && c.accent.blue > c.accent.green, "indigo-ish")
        }
    }
}
