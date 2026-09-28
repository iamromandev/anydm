package dev.anydm.desktop.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The native look: system font at 13 sp, 6 dp corners, the tokens for light or dark, and Material bent to match. */
@Composable
fun DesktopTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val t = if (dark) DarkTokens else LightTokens
    val base = if (dark) darkColorScheme() else lightColorScheme()
    val scheme =
        base.copy(
            primary = t.selection,
            onPrimary = t.onSelection,
            background = t.content,
            onBackground = t.text,
            surface = t.content,
            onSurface = t.text,
            surfaceVariant = t.bar,
            onSurfaceVariant = t.secondaryText,
            surfaceContainerHigh = t.bar,
            outline = t.separator,
            outlineVariant = t.separator,
            error = t.error,
        )
    val body = TextStyle(fontSize = 13.sp, color = Color.Unspecified)
    val typography =
        Typography(
            bodyLarge = body,
            bodyMedium = body,
            bodySmall = TextStyle(fontSize = 11.sp),
            titleSmall = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
            titleMedium = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
            labelLarge = TextStyle(fontSize = 13.sp),
            labelMedium = TextStyle(fontSize = 12.sp),
            labelSmall = TextStyle(fontSize = 11.sp),
        )
    val corner = RoundedCornerShape(6.dp)
    CompositionLocalProvider(LocalTokens provides t) {
        MaterialTheme(
            colorScheme = scheme,
            typography = typography,
            shapes = Shapes(extraSmall = corner, small = corner, medium = corner, large = RoundedCornerShape(10.dp)),
        ) {
            ProvideTextStyle(TextStyle(fontSize = 13.sp, color = t.text), content)
        }
    }
}
