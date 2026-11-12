package dev.anydm.desktop

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun everySwatchHasContrastOnBgInBothThemes() {
        for (c in listOf(darkColors(), lightColors())) {
            for (k in NetworkColor.entries) {
                val r = contrastRatio(swatch(k, c), c.bg)
                assertTrue(r >= 3.0, "$k on bg (dark=${c.dark}) was $r")
            }
        }
    }

    @Test
    fun accentIsNotAmber() {
        for (c in listOf(darkColors(), lightColors())) {
            assertTrue(c.accent.blue > c.accent.red * 0.9f && c.accent.blue > c.accent.green, "indigo-ish")
        }
    }

    @Test
    fun contrastRatioMatchesWcagReferenceValues() {
        assertEquals(21.0, contrastRatio(Color.Black, Color.White), 0.01)
        assertEquals(1.0, contrastRatio(Color.White, Color.White), 0.001)
        assertEquals(contrastRatio(Color.Black, Color.White), contrastRatio(Color.White, Color.Black), 0.0001)
    }

    @Test
    fun everySwatchHasContrastOnSurfaceInBothThemes() {
        for (c in listOf(darkColors(), lightColors())) {
            for (k in NetworkColor.entries) {
                val r = contrastRatio(swatch(k, c), c.surface)
                assertTrue(r >= 3.0, "$k on surface (dark=${c.dark}) was $r")
            }
        }
    }

    @Test
    fun swatchesAreDistinctPerTheme() {
        for (c in listOf(darkColors(), lightColors())) {
            assertEquals(
                NetworkColor.entries.size,
                NetworkColor.entries
                    .map { swatch(it, c) }
                    .toSet()
                    .size,
            )
        }
    }

    @Test
    fun withoutAChoiceWifiIsSuccessAndOthersAreAccent() {
        for (c in listOf(darkColors(), lightColors())) {
            assertEquals(c.success, networkColor(NetworkKind.WiFi, null, c))
            assertEquals(c.accent, networkColor(NetworkKind.UsbTethering, null, c))
        }
    }

    @Test
    fun aChoiceOverridesTheKindDefault() {
        val c = darkColors()
        assertEquals(swatch(NetworkColor.Orange, c), networkColor(NetworkKind.WiFi, NetworkColor.Orange, c))
        assertEquals(swatch(NetworkColor.Pink, c), networkColor(NetworkKind.UsbTethering, NetworkColor.Pink, c))
    }
}
