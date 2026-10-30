package dev.anydm.desktop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// The app's own tokens: a calm blue on neutral surfaces. Screens use MaterialTheme roles, never these directly.
private val LightColors =
    lightColorScheme(
        primary = Color(0xFF2F6FED),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFDCE6FD),
        onPrimaryContainer = Color(0xFF0B2A6B),
        background = Color(0xFFF7F8FA),
        onBackground = Color(0xFF1B1D21),
        surface = Color(0xFFFFFFFF),
        onSurface = Color(0xFF1B1D21),
        surfaceVariant = Color(0xFFECEEF2),
        onSurfaceVariant = Color(0xFF555B66),
        error = Color(0xFFC62828),
        errorContainer = Color(0xFFFDE3E1),
        onErrorContainer = Color(0xFF5B1010),
    )

private val DarkColors =
    darkColorScheme(
        primary = Color(0xFF8AB0FF),
        onPrimary = Color(0xFF0B2A6B),
        primaryContainer = Color(0xFF1F3F86),
        onPrimaryContainer = Color(0xFFDCE6FD),
        background = Color(0xFF14161A),
        onBackground = Color(0xFFE6E8EC),
        surface = Color(0xFF1B1E23),
        onSurface = Color(0xFFE6E8EC),
        surfaceVariant = Color(0xFF262A31),
        onSurfaceVariant = Color(0xFFA6ACB8),
        error = Color(0xFFFF8A80),
        errorContainer = Color(0xFF5B1A17),
        onErrorContainer = Color(0xFFFDE3E1),
    )

private val AnydmShapes =
    Shapes(
        small = RoundedCornerShape(6.dp),
        medium = RoundedCornerShape(10.dp),
        large = RoundedCornerShape(14.dp),
    )

@Composable
fun AnydmTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, shapes = AnydmShapes, content = content)
}
