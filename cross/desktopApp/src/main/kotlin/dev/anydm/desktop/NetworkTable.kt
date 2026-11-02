package dev.anydm.desktop

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

private val StatusWidth = 30.dp
private val ShareWidth = 48.dp
private val SpeedWidth = 104.dp
private val BytesWidth = 110.dp

/**
 * The networks of a download, one row each: whether it is used, its name, how much of the file it carried, its share, its
 * speed now and what it carried. [onEdit] adds the pencil that renames a network (none: no pencil).
 */
@Composable
fun NetworkTable(
    rows: List<DetailNetwork>,
    swatches: Map<String, Swatch>,
    unit: SpeedUnit,
    torrent: Boolean,
    onToggle: (key: String, enabled: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onEdit: ((key: String) -> Unit)? = null,
    /** What the pencil of [key] opens, drawn beside it (the rename popover). */
    editor: @Composable (key: String) -> Unit = {},
) {
    val colors = Plexo.colors
    val head =
        Plexo.type.badge.copy(
            fontSize = Plexo.type.badge.fontSize * 0.95f,
            letterSpacing = androidx.compose.ui.unit.TextUnit.Unspecified,
        )
    Column(modifier.fillMaxWidth().semantics { contentDescription = "Networks" }) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 10.dp, bottom = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(StatusWidth))
            Txt("NETWORK", head, Modifier.weight(1.2f), color = colors.textTertiary)
            Txt("PROGRESS", head, Modifier.weight(1f), color = colors.textTertiary)
            Txt("SHARE", head.copy(textAlign = TextAlign.End), Modifier.width(ShareWidth), color = colors.textTertiary)
            Txt("SPEED", head.copy(textAlign = TextAlign.End), Modifier.width(SpeedWidth), color = colors.textTertiary)
            Txt(
                if (torrent) "TRANSFERRED" else "DOWNLOADED",
                head.copy(textAlign = TextAlign.End),
                Modifier.width(BytesWidth),
                color = colors.textTertiary,
            )
        }
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(colors.border))
        for (row in rows) {
            val color = swatches[row.key]?.solid ?: colors.accent
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .padding(horizontal = 20.dp)
                    .semantics { testTag = "network-${row.key}" },
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.width(StatusWidth), contentAlignment = Alignment.CenterStart) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(if (row.active) color else colors.iconMutedStrong))
                }
                Row(
                    Modifier.weight(1.2f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PCheckbox(row.enabled, { onToggle(row.key, it) }, "Use ${row.name}")
                    Txt(row.name, Plexo.type.rowName, Modifier.weight(1f, fill = false))
                    if (row.disconnected) Txt("disconnected", Plexo.type.badge, color = colors.textTertiary)
                    if (onEdit != null) {
                        Box {
                            IconButton(Glyph.PENCIL, "Edit ${row.name}", { onEdit(row.key) })
                            editor(row.key)
                        }
                    }
                }
                Box(Modifier.weight(1f)) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(CircleShape)
                            .background(colors.trackBg),
                    ) {
                        if (row.progress > 0f) Box(Modifier.weight(row.progress).fillMaxHeight().background(color))
                        if (row.progress < 1f) Box(Modifier.weight(1f - row.progress))
                    }
                }
                Txt("${row.share}%", Plexo.type.speed.copy(textAlign = TextAlign.End), Modifier.width(ShareWidth))
                Txt(
                    if (row.speed > 0) formatRate(row.speed, unit) else "—",
                    Plexo.type.speed.copy(textAlign = TextAlign.End),
                    Modifier.width(SpeedWidth),
                    color = if (row.speed > 0) color else colors.textTertiary,
                )
                Txt(
                    formatBytes(row.bytes),
                    Plexo.type.speed.copy(textAlign = TextAlign.End, fontWeight = null),
                    Modifier.width(BytesWidth),
                    color = colors.textSecondary,
                )
            }
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(colors.borderSubtle))
        }
        Txt(NETWORK_SWITCH_HINT, Plexo.type.badge, Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = colors.textTertiary)
    }
}
