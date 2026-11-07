package dev.anydm.desktop

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.anydm.engine.net.SpeedLimit

/**
 * One download as a row: select box, type badge, name, progress, detail line, speed, its button and the way into its page.
 * A right-click asks for the menu ([onMenu], with the pointer's position in the window); a double-click on the name asks to
 * open the file ([onOpenFile]); a single click opens the page ([onOpen]).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DownloadRow(
    row: HomeRow,
    unit: SpeedUnit,
    swatches: Map<String, Swatch>,
    onSelect: () -> Unit,
    onOpen: () -> Unit,
    onAction: () -> Unit,
    onMenu: (Offset) -> Unit = {},
    onOpenFile: () -> Unit = {},
    onLimits: (String?) -> Unit = {},
) {
    val colors = Plexo.colors
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val shape = RoundedCornerShape(PlexoRadius)
    var frame by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val menu by rememberUpdatedState(onMenu)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(shape)
            .background(
                when {
                    row.selected -> colors.accent.copy(alpha = if (hovered) 0.15f else 0.10f)
                    hovered -> colors.bgTertiary
                    else -> Color.Transparent
                },
            ).hoverable(source)
            .semantics { testTag = "row-${row.id.value}" }
            .onGloballyPositioned { frame = it }
            .pointerInput(Unit) {
                // Watches without taking the press, so the row's other clicks still work.
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                            val at = event.changes.first().position
                            menu(frame?.takeIf { it.isAttached }?.localToWindow(at) ?: at)
                        }
                    }
                }
            }.padding(horizontal = 8.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PCheckbox(row.selected, { onSelect() }, "Select ${row.name}")
        Row(
            Modifier
                .weight(1f)
                .combinedClickable(role = Role.Button, onDoubleClick = onOpenFile, onClick = onOpen),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(height = 40.dp, width = 40.dp)
                    .widthIn(min = 40.dp)
                    .clip(shape)
                    .background(colors.bgSecondary)
                    .border(0.5.dp, colors.border, shape),
                contentAlignment = Alignment.Center,
            ) {
                // A long badge (TORRENT) gets smaller type to fit.
                val badgeType = Plexo.type.badge.let { if (row.badge.length > 4) it.copy(fontSize = it.fontSize * 0.8f) else it }
                Txt(row.badge, badgeType, color = colors.textTertiary, maxLines = 1)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Txt(row.name, Plexo.type.rowName, Modifier.weight(1f, fill = false))
                    Box(Modifier.weight(1f))
                    row.speed?.let { Txt(formatRate(it, unit), Plexo.type.speed.copy(fontWeight = FontWeight.Medium)) }
                }
                if (row.segments.isNotEmpty()) ProgressBar(row, swatches)
                Txt(row.detail, Plexo.type.detail, color = if (row.failed) colors.danger else colors.textTertiary)
                row.limit?.let { LimitLine(it, onLimits, Plexo.type.detail) }
            }
        }
        row.action?.let { action ->
            val (glyph, verb) =
                when (action) {
                    RowAction.PAUSE -> Glyph.PAUSE to "Pause"
                    RowAction.RESUME -> Glyph.PLAY to "Resume"
                    RowAction.RETRY -> Glyph.RETRY to "Retry"
                }
            IconButton(glyph, "$verb ${row.name}", onAction, tint = colors.textTertiary)
        }
        IconButton(Glyph.CHEVRON_RIGHT, "Open ${row.name}", onOpen)
    }
}

// Each network's share of the file in its color; a failed row is red; with no share told, the accent.
@Composable
private fun ProgressBar(
    row: HomeRow,
    swatches: Map<String, Swatch>,
) {
    val colors = Plexo.colors
    Row(
        Modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(CircleShape)
            .background(colors.trackBg)
            .alpha(if (row.idle) 0.4f else 1f),
    ) {
        for (segment in row.segments) {
            if (segment.fraction <= 0f) continue
            val color =
                when {
                    row.failed -> colors.danger
                    segment.key != null -> swatches[segment.key]?.solid ?: colors.accent
                    else -> colors.accent
                }
            Box(Modifier.weight(segment.fraction).fillMaxHeight().background(color))
        }
        val rest = 1f - row.segments.sumOf { it.fraction.toDouble() }.toFloat()
        if (rest > 0.0001f) Box(Modifier.weight(rest))
    }
}

/**
 * The line that names what limits a download's speed (#634). Where the Limits dialog can change the limit the line is a
 * button that opens it ([onLimits], with the key of the network a data limit is about, else null); otherwise it is only text.
 */
@Composable
internal fun LimitLine(
    limit: SpeedLimit,
    onLimits: (String?) -> Unit,
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    val colors = Plexo.colors
    val text = limitText(limit)
    if (limit.fixable) {
        val key = (limit as? SpeedLimit.DataLimit)?.key
        Txt(text, style, modifier.clickable(onClickLabel = "Open Limits", role = Role.Button) { onLimits(key) }, color = colors.accent)
    } else {
        Txt(text, style, modifier, color = colors.textTertiary)
    }
}
