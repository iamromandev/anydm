package dev.anydm.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** The bar every screen ends on: free space where downloads are saved, Slow mode, the speed unit and the theme. */
@Composable
fun Footer(
    state: FooterState,
    model: FooterModel,
    /** Controls a screen adds before Slow mode, such as Browsers and the update button. */
    extras: @Composable RowScope.() -> Unit = {},
) {
    val colors = Plexo.colors
    Box(Modifier.fillMaxWidth().height(0.5.dp).background(colors.footerBorder))
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.bgSecondary)
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .testTag("footer"),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Txt(state.freeSpace?.let { "${formatBytes(it)} free" } ?: "", Plexo.type.footer, Modifier.weight(1f), color = colors.textTertiary)
        state.message?.let { Txt(it, Plexo.type.footer, color = colors.danger) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            extras()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Txt("Slow mode", Plexo.type.footer, color = colors.textTertiary)
                PSwitch(state.slowMode, { model.toggleSlowMode() }, "Slow mode")
            }
            Segmented(
                listOf(SpeedUnit.BYTES to "MB/s", SpeedUnit.BITS to "Mbps"),
                state.unit,
                model::setUnit,
                label = "Speed units",
            )
            ThemeToggle(state.theme, model::toggleTheme)
        }
    }
}

@Composable
private fun ThemeToggle(
    theme: ThemeChoice,
    onToggle: () -> Unit,
) {
    val colors = Plexo.colors
    val dark = theme == ThemeChoice.DARK
    val label = if (dark) "Switch to light theme" else "Switch to dark theme"
    val shape = RoundedCornerShape(6.dp)
    Box(
        Modifier
            .size(26.dp)
            .clip(shape)
            .background(colors.bgSecondary)
            .border(0.5.dp, colors.border, shape)
            .semantics { contentDescription = label }
            .clickable(role = Role.Button, onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        // The icon shows the theme that is on now, as the reference does.
        GlyphIcon(if (dark) Glyph.MOON else Glyph.SUN, colors.textSecondary, size = 15.dp)
    }
}
