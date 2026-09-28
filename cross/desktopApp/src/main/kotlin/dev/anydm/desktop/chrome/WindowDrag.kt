package dev.anydm.desktop.chrome

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * How the toolbar's empty space moves the window. `Main` provides `WindowDraggableArea` (it needs the
 * window's scope); tests and previews get a plain wrapper.
 */
val LocalWindowDrag = staticCompositionLocalOf<@Composable (@Composable () -> Unit) -> Unit> { { content -> content() } }
