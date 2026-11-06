package dev.anydm.desktop

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.util.prefs.Preferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

internal class ThemeTest {
    private fun hex(value: Long) = Color(0xFF000000 or value)

    @Test
    fun `dark tokens equal the reference table`() {
        val c = DarkColors
        assertEquals(hex(0x1B1E20), c.bg)
        assertEquals(hex(0x202325), c.bgSecondary)
        assertEquals(hex(0x242729), c.inputBg)
        assertEquals(hex(0x262A2D), c.trackBg)
        assertEquals(hex(0xEAE7E2), c.text)
        assertEquals(hex(0xA9ADB2), c.textSecondary)
        assertEquals(hex(0x8D9196), c.textTertiary)
        assertEquals(hex(0x2B2F33), c.border)
        assertEquals(hex(0x33383C), c.borderStrong)
        assertEquals(hex(0xD8A44C), c.accent)
        assertEquals(hex(0x1B1408), c.accentOnSolid)
        assertEquals(hex(0xEDA3A3), c.danger)
        assertEquals(hex(0xF7F4EF), c.nodeAccent)
    }

    @Test
    fun `light tokens equal the reference table`() {
        val c = LightColors
        assertEquals(hex(0xFFFFFF), c.bg)
        assertEquals(hex(0xFAFAFA), c.bgSecondary)
        assertEquals(hex(0xF0F0F0), c.bgTertiary)
        assertEquals(hex(0xE4E4E6), c.trackBg)
        assertEquals(hex(0x1D1D1F), c.text)
        assertEquals(hex(0x6E6E73), c.textSecondary)
        assertEquals(hex(0x737377), c.textTertiary)
        assertEquals(hex(0xE0E0E2), c.border)
        assertEquals(hex(0xD8D8DA), c.footerBorder)
        assertEquals(hex(0xD97706), c.accent)
        assertEquals(hex(0xDC2626), c.danger)
        assertEquals(hex(0x5F6E78), c.nodeAccent)
    }

    @Test
    fun `network kinds carry the reference palette in both themes`() {
        assertEquals(hex(0x4EA89A), DarkColors.wifi.solid)
        assertEquals(hex(0xD8A44C), DarkColors.usb.solid)
        assertEquals(hex(0x7E93BD), DarkColors.ethernet.solid)
        assertEquals(hex(0x8D9196), DarkColors.neutral.solid)
        assertEquals(hex(0x1F8A70), LightColors.wifi.solid)
        assertEquals(hex(0xD97706), LightColors.usb.solid)
        assertEquals(hex(0x4F64A5), LightColors.ethernet.solid)
        assertEquals(hex(0x6B7280), LightColors.neutral.solid)
    }

    @Test
    fun `there are eight swatches in the reference order with a color per theme`() {
        val ids = listOf("teal", "amber", "steel", "rose", "violet", "lime", "cyan", "coral")
        assertEquals(ids, DarkColors.swatches.map { it.id })
        assertEquals(ids, LightColors.swatches.map { it.id })
        assertEquals(hex(0xC97B7B), DarkColors.swatches.first { it.id == "rose" }.solid)
        assertEquals(hex(0xB5525A), LightColors.swatches.first { it.id == "rose" }.solid)
        assertEquals(hex(0xD99168), DarkColors.swatches.first { it.id == "coral" }.solid)
    }

    @Test
    fun `the hero band and checkbox tokens follow the reference in both themes`() {
        assertEquals(hex(0x202325), DarkColors.heroTop)
        assertEquals(hex(0x1B1E20), DarkColors.heroBottom)
        assertEquals(hex(0x2B2F33), DarkColors.heroBorder)
        assertEquals(hex(0x3A3F44), DarkColors.iconMutedStrong)
        assertEquals(Color(0f, 0f, 0f, 0.4f), DarkColors.checkboxShadow)
        assertEquals(Color(0f, 0f, 0f, 0.3f), DarkColors.checkboxInsetShadow)
        assertEquals(hex(0xFBFBFC), LightColors.heroTop)
        assertEquals(hex(0xF4F5F7), LightColors.heroBottom)
        assertEquals(hex(0xE2E4E8), LightColors.heroBorder)
        assertEquals(hex(0xB0B0B2), LightColors.iconMutedStrong)
        assertEquals(Color(0f, 0f, 0f, 0.15f), LightColors.checkboxShadow)
        assertEquals(Color(0f, 0f, 0f, 0.04f), LightColors.checkboxInsetShadow)
    }

    @Test
    fun `radius and type follow the reference`() {
        assertEquals(8.dp, PlexoRadius)
        assertEquals("Manrope", PlexoFontNames.SANS)
        assertEquals("Roboto Mono", PlexoFontNames.MONO)
    }

    @Test
    fun `the theme choice round trips through the preferences store`() {
        val node = Preferences.userRoot().node("dev/anydm/desktop-test-${System.nanoTime()}")
        try {
            val prefs = PreferencesViewPrefs(node)
            assertEquals(ThemeChoice.DARK, prefs.theme)
            prefs.theme = ThemeChoice.LIGHT
            assertEquals(ThemeChoice.LIGHT, PreferencesViewPrefs(node).theme)
        } finally {
            node.removeNode()
        }
    }

    @Test
    fun `the speed unit round trips through the preferences store`() {
        val node = Preferences.userRoot().node("dev/anydm/desktop-test-${System.nanoTime()}")
        try {
            val prefs = PreferencesViewPrefs(node)
            assertEquals(SpeedUnit.BYTES, prefs.speedUnit)
            prefs.speedUnit = SpeedUnit.BITS
            assertEquals(SpeedUnit.BITS, PreferencesViewPrefs(node).speedUnit)
        } finally {
            node.removeNode()
        }
    }

    @Test
    fun `an unreadable stored choice falls back to the defaults`() {
        val node = Preferences.userRoot().node("dev/anydm/desktop-test-${System.nanoTime()}")
        try {
            node.put("theme", "sepia")
            node.put("speedUnit", "furlongs")
            val prefs = PreferencesViewPrefs(node)
            assertEquals(ThemeChoice.DARK, prefs.theme)
            assertEquals(SpeedUnit.BYTES, prefs.speedUnit)
        } finally {
            node.removeNode()
        }
    }

    @Test
    fun `the bundled fonts are there and the renderer reads them`() {
        for ((file, family) in listOf("Manrope-Variable.woff2" to "Manrope", "RobotoMono-Variable.woff2" to "Roboto Mono")) {
            val bytes =
                checkNotNull(
                    javaClass.classLoader.getResourceAsStream("fonts/$file"),
                ) { "fonts/$file is not bundled" }.use { it.readBytes() }
            val typeface =
                org.jetbrains.skia.FontMgr.default
                    .makeFromData(
                        org.jetbrains.skia.Data
                            .makeFromBytes(bytes),
                    )
            // The Linux CI renderer (fontconfig) cannot read woff2 from memory; check the bundle only there.
            if (typeface == null && System.getProperty("os.name").contains("Linux")) continue
            assertNotNull(typeface, "the renderer could not read $file")
            assertTrue(typeface.familyName.startsWith(family), "${'$'}file is ${'$'}{typeface.familyName}")
            // A variable font: the weight axis is there, so Medium and Bold are not faked.
            assertTrue(typeface.variationAxes?.any { it.tag == "wght" } == true, "${'$'}file has no weight axis")
        }
    }

    @Test
    fun `the two themes differ`() {
        assertNotEquals(DarkColors.bg, LightColors.bg)
    }
}
