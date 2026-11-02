package dev.anydm.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

/**
 * The menu the networks pill opens: a line for each network (its color, name, a pencil to rename and recolor it, what kind of
 * link it is, its speed or Idle, and a chevron to its limits) and a link to the speed and data limits. Drawn as a popup under
 * the pill it sits in, and only while [model] says it is open.
 */
@Composable
fun NetworksMenu(
    model: NetworksModel,
    editor: NetworkEditor,
    unit: SpeedUnit,
    keys: WindowKeys,
) {
    val state by model.state.collectAsState()
    if (!state.open) return
    val colors = Plexo.colors
    val swatches = LocalNetworkLooks.current.swatches(colors)
    BlockWindowKeys(keys)
    Popup(
        alignment = Alignment.TopEnd,
        offset = IntOffset(0, with(LocalDensity.current) { 40.dp.roundToPx() }),
        onDismissRequest = model::close,
        properties = PopupProperties(focusable = true),
    ) {
        val shape = RoundedCornerShape(12.dp)
        Column(
            Modifier
                .width(340.dp)
                .clip(shape)
                .background(colors.bgSecondary)
                .border(0.5.dp, colors.border, shape)
                .semantics { contentDescription = "Networks menu" },
        ) {
            Txt("Networks", Plexo.type.menu, Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
            Rule()
            if (state.rows.isEmpty()) {
                Txt("No network", Plexo.type.body, Modifier.padding(16.dp), color = colors.danger)
                Rule()
            }
            for (row in state.rows) {
                val color = swatches[row.key]?.solid ?: colors.accent
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button) { model.openLimits(row.key) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(color))
                    Row(
                        Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Txt(
                            row.name,
                            Plexo.type.rowName.copy(fontSize = Plexo.type.rowName.fontSize * 0.97f),
                            Modifier.weight(1f, fill = false),
                        )
                        NetworkEditPencil(editor, "menu", row.key, row.name, row.osName, keys, size = 20.dp, glyphSize = 12.dp)
                        Box(
                            Modifier
                                .widthIn(max = 110.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(colors.bgTertiary)
                                .border(0.5.dp, colors.border, RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp),
                        ) {
                            Txt(
                                "${row.kind.tag} · ${row.key}",
                                Plexo.type.badge.copy(fontSize = Plexo.type.badge.fontSize * 1.05f),
                                color = colors.textTertiary,
                            )
                        }
                    }
                    Txt(
                        if (!row.up) "Down" else row.speed?.let { formatRate(it, unit) } ?: "Idle",
                        Plexo.type.speed.copy(textAlign = TextAlign.End),
                        color = if (row.speed != null) color else colors.textTertiary,
                    )
                    Box(
                        Modifier
                            .size(16.dp)
                            .semantics { contentDescription = "${row.name} limits" },
                    ) { GlyphIcon(Glyph.CHEVRON_RIGHT, colors.textTertiary, size = 16.dp) }
                }
                Rule()
            }
            Txt(
                "Speed & data limits",
                Plexo.type.button,
                Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) { model.openLimits(null) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                color = colors.accent,
            )
        }
    }
}

@Composable
private fun Rule() {
    Box(Modifier.fillMaxWidth().height(0.5.dp).background(Plexo.colors.border))
}
