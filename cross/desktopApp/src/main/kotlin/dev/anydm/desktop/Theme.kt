package dev.anydm.desktop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

val Radius = 8.dp

data class AnydmColors(
    val bg: Color,
    val surface: Color,
    val border: Color,
    val text: Color,
    val textSecondary: Color,
    val track: Color,
    val accent: Color,
    val onAccent: Color,
    val success: Color,
    val danger: Color,
    val dark: Boolean,
)

fun darkColors() =
    AnydmColors(
        bg = Color(0xFF16181D),
        surface = Color(0xFF1F2229),
        border = Color(0xFF2E323B),
        text = Color(0xFFEDEEF1),
        textSecondary = Color(0xFF9BA1AD),
        track = Color(0xFF2A2E37),
        accent = Color(0xFF5B5EEF),
        onAccent = Color(0xFFFFFFFF),
        success = Color(0xFF4ADE80),
        danger = Color(0xFFF87171),
        dark = true,
    )

fun lightColors() =
    AnydmColors(
        bg = Color(0xFFF7F8FA),
        surface = Color(0xFFFFFFFF),
        border = Color(0xFFDDE0E6),
        text = Color(0xFF16181D),
        textSecondary = Color(0xFF596070),
        track = Color(0xFFE4E7EC),
        accent = Color(0xFF4F46E5),
        onAccent = Color(0xFFFFFFFF),
        success = Color(0xFF15803D),
        danger = Color(0xFFB91C1C),
        dark = false,
    )

val LocalAnydmColors = staticCompositionLocalOf { lightColors() }

@Composable
fun AnydmTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (dark) darkColors() else lightColors()
    val scheme =
        if (dark) {
            darkColorScheme(primary = colors.accent, background = colors.bg, surface = colors.surface)
        } else {
            lightColorScheme(primary = colors.accent, background = colors.bg, surface = colors.surface)
        }
    CompositionLocalProvider(LocalAnydmColors provides colors) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

val colors: AnydmColors
    @Composable get() = LocalAnydmColors.current
