package dev.anydm.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// The app's own tokens: a calm blue on neutral surfaces. Screens use MaterialTheme roles, never these directly.
private val LightColors =
    lightColorScheme(
        primary = Color(0xFF2F6FED),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFDCE6FD),
        onPrimaryContainer = Color(0xFF0B2A6B),
        secondary = Color(0xFF4F6A9A),
        onSecondary = Color(0xFFFAFBFD),
        secondaryContainer = Color(0xFFE6EDFB),
        onSecondaryContainer = Color(0xFF16284A),
        background = Color(0xFFF7F8FA),
        onBackground = Color(0xFF1B1D21),
        surface = Color(0xFFFFFFFF),
        onSurface = Color(0xFF1B1D21),
        surfaceVariant = Color(0xFFECEEF2),
        onSurfaceVariant = Color(0xFF555B66),
        surfaceContainerLowest = Color(0xFFFCFDFE),
        surfaceContainerLow = Color(0xFFF3F5F8),
        surfaceContainer = Color(0xFFEFF1F5),
        surfaceContainerHigh = Color(0xFFE9ECF1),
        surfaceContainerHighest = Color(0xFFE3E6EC),
        outline = Color(0xFF8A919E),
        outlineVariant = Color(0xFFD5D9E0),
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
        secondary = Color(0xFFA9BEE6),
        onSecondary = Color(0xFF16284A),
        secondaryContainer = Color(0xFF2A3B5E),
        onSecondaryContainer = Color(0xFFDDE6F8),
        background = Color(0xFF14161A),
        onBackground = Color(0xFFE6E8EC),
        surface = Color(0xFF1B1E23),
        onSurface = Color(0xFFE6E8EC),
        surfaceVariant = Color(0xFF262A31),
        onSurfaceVariant = Color(0xFFA6ACB8),
        surfaceContainerLowest = Color(0xFF101216),
        surfaceContainerLow = Color(0xFF181B20),
        surfaceContainer = Color(0xFF1D2025),
        surfaceContainerHigh = Color(0xFF23272D),
        surfaceContainerHighest = Color(0xFF2A2E35),
        outline = Color(0xFF6F7685),
        outlineVariant = Color(0xFF3A3F48),
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

/** Colours for the segments of the networks split bar and the dots on its chips, one per network in order; none is the error colour. */
object SplitPalette {
    const val SIZE = 8
    private val Light =
        listOf(
            Color(0xFF2F6FED),
            Color(0xFF14937F),
            Color(0xFFE0861A),
            Color(0xFF8B5CD6),
            Color(0xFF3F9E3F),
            Color(0xFF3A8FB8),
            Color(0xFFD1569A),
            Color(0xFF7C8594),
        )
    private val Dark =
        listOf(
            Color(0xFF8AB0FF),
            Color(0xFF4FD1BC),
            Color(0xFFFFB454),
            Color(0xFFB799F0),
            Color(0xFF7ED67E),
            Color(0xFF7CC4E8),
            Color(0xFFF08CC0),
            Color(0xFFA6ACB8),
        )

    fun of(dark: Boolean): List<Color> = if (dark) Dark else Light
}

/** Spacing steps for padding and gaps. */
object Space {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
}

/** A small muted heading above a group of entries or fields. */
@Composable
fun SectionHeader(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = Space.sm, end = Space.sm, top = Space.md, bottom = Space.xs),
    )
}

/** A panel title inside a settings section or dialog. */
@Composable
fun PanelTitle(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = modifier)
}

/** A thin strip across the window (status bar, banner, notice) in [container] with [content] text on top. */
@Composable
fun Strip(
    container: Color,
    modifier: Modifier = Modifier,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.SpaceBetween,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier.fillMaxWidth().background(container).padding(horizontal = Space.lg, vertical = StripTokens.verticalPadding),
        horizontalArrangement = horizontalArrangement,
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** The card surface for dialogs: the large shape on the surface role, with the card elevation. */
@Composable
fun DialogCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = CardTokens.dialogElevation),
        content = content,
    )
}

/** Card look for the list rows and panels. */
object CardTokens {
    val shape = RoundedCornerShape(10.dp)
    val elevation = 1.dp
    val dialogElevation = 4.dp
}

/** Sizes of the thin strips (status bar, banners). */
object StripTokens {
    val verticalPadding = 6.dp
}

/** For a color parameter that should keep the component's own default, which for a text button is no fill. */
val NoFill = Color.Unspecified

/** Badge colors for a protocol: [container] behind, [content] on top. */
data class BadgeColors(
    val container: Color,
    val content: Color,
)

/** Badge colors per protocol, and the status hues, for the two schemes; Material has no role for them. */
object ProtocolColors {
    fun badge(
        protocol: Protocol,
        dark: Boolean,
    ): BadgeColors =
        if (dark) {
            when (protocol) {
                Protocol.HTTP -> BadgeColors(Color(0xFF1F3F86), Color(0xFFDCE6FD))
                Protocol.SITE -> BadgeColors(Color(0xFF5A2A6B), Color(0xFFF1D9FA))
                Protocol.TORRENT -> BadgeColors(Color(0xFF1E5A3A), Color(0xFFD3F3E0))
                Protocol.FTP -> BadgeColors(Color(0xFF6B4A12), Color(0xFFFBE8C2))
                Protocol.TOOL -> BadgeColors(Color(0xFF3B4250), Color(0xFFDDE1E8))
            }
        } else {
            when (protocol) {
                Protocol.HTTP -> BadgeColors(Color(0xFFDCE6FD), Color(0xFF0B2A6B))
                Protocol.SITE -> BadgeColors(Color(0xFFF1D9FA), Color(0xFF4A1B5C))
                Protocol.TORRENT -> BadgeColors(Color(0xFFD3F3E0), Color(0xFF0F4A2B))
                Protocol.FTP -> BadgeColors(Color(0xFFFBE8C2), Color(0xFF5A3C06))
                Protocol.TOOL -> BadgeColors(Color(0xFFE3E6EC), Color(0xFF2B313C))
            }
        }

    /** The hue of a status group's label: calm for waiting, green for done, red for failed. */
    fun status(
        group: StatusGroup,
        dark: Boolean,
    ): Color =
        when (group) {
            StatusGroup.ACTIVE -> if (dark) Color(0xFF8AB0FF) else Color(0xFF2F6FED)
            StatusGroup.COMPLETED -> if (dark) Color(0xFF7BD6A2) else Color(0xFF1B7F4B)
            StatusGroup.FAILED -> if (dark) Color(0xFFFF8A80) else Color(0xFFC62828)
            StatusGroup.PAUSED, StatusGroup.QUEUED -> if (dark) Color(0xFFA6ACB8) else Color(0xFF555B66)
        }
}

/** Whether the theme in effect is the dark one; set by [AnydmTheme]. */
val LocalDark = staticCompositionLocalOf { false }

@Composable
fun AnydmTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalDark provides dark) {
        MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, shapes = AnydmShapes, content = content)
    }
}
