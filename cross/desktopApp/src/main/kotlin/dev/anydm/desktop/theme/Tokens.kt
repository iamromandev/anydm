package dev.anydm.desktop.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/** The desktop look's colours (spec: "The look"). Status colours never fill a whole row. */
data class DesktopTokens(
    val name: String,
    val dark: Boolean,
    val accent: Color,
    val selection: Color,
    val onSelection: Color,
    val content: Color,
    val sidebar: Color,
    val bar: Color,
    val hover: Color,
    val separator: Color,
    val text: Color,
    val secondaryText: Color,
    val ok: Color,
    val warn: Color,
    val error: Color,
)

val LightTokens =
    DesktopTokens(
        name = "light",
        dark = false,
        accent = Color(0xFF0A64D6),
        selection = Color(0xFF0A64D6),
        onSelection = Color.White,
        content = Color.White,
        sidebar = Color(0xFFF0F0F2),
        bar = Color(0xFFF6F6F6),
        hover = Color(0xFFF2F2F4),
        separator = Color(0xFFE0E0E0),
        text = Color(0xFF1D1D1F),
        secondaryText = Color(0xFF6A6A6F),
        ok = Color(0xFF28A745),
        warn = Color(0xFFE0A100),
        error = Color(0xFFD93025),
    )

val DarkTokens =
    DesktopTokens(
        name = "dark",
        dark = true,
        accent = Color(0xFF0A84FF),
        // White on #0A84FF is 3.6:1; the light accent carries the selection in both modes.
        selection = Color(0xFF0A64D6),
        onSelection = Color.White,
        content = Color(0xFF1E1E1E),
        sidebar = Color(0xFF262628),
        bar = Color(0xFF2A2A2C),
        hover = Color(0xFF2C2C2E),
        separator = Color(0xFF3A3A3C),
        text = Color(0xFFE6E6E6),
        secondaryText = Color(0xFF98989D),
        ok = Color(0xFF32D74B),
        warn = Color(0xFFFFD60A),
        error = Color(0xFFFF453A),
    )

val LocalTokens = staticCompositionLocalOf { LightTokens }

/** WCAG contrast ratio between two opaque colours, 1 to 21. */
fun contrast(
    a: Color,
    b: Color,
): Double {
    val (hi, lo) = listOf(a.luminance(), b.luminance()).sortedDescending()
    return (hi + 0.05) / (lo + 0.05)
}
