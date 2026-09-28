package dev.anydm.desktop.list

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.anydm.desktop.chrome.Glyphs
import dev.anydm.desktop.chrome.ToolButton
import dev.anydm.desktop.theme.LocalTokens
import dev.anydm.desktop.ui.CardAction
import dev.anydm.desktop.ui.DetailTone
import dev.anydm.desktop.ui.Glyph
import dev.anydm.desktop.ui.IconKind
import dev.anydm.desktop.ui.RowView
import dev.anydm.desktop.ui.actionLabel

private fun iconColour(kind: IconKind): Color =
    when (kind) {
        IconKind.SITE -> Color(0xFFFF3B30)
        IconKind.TORRENT -> Color(0xFF34C759)
        IconKind.GROUP -> Color(0xFFAF52DE)
        IconKind.FILE -> Color(0xFF8E8E93)
    }

private fun glyphText(glyph: Glyph): String =
    when (glyph) {
        Glyph.DOWN -> "▶"
        Glyph.PAUSED -> "⏸"
        Glyph.DONE -> "✓"
        Glyph.RETRY -> "↻"
        Glyph.SEEDING -> "↑"
    }

fun glyphOf(action: CardAction): ImageVector =
    when (action) {
        CardAction.PAUSE -> Glyphs.Pause
        CardAction.RESUME, CardAction.PLAY -> Glyphs.Resume
        CardAction.RETRY -> Glyphs.Retry
        CardAction.SAVE -> Glyphs.Save
        CardAction.STOP_SEEDING, CardAction.REMOVE -> Glyphs.Remove
        CardAction.COPY_LINK -> Glyphs.Add
    }

/**
 * One download (spec: "Rows"): the icon marked with its status, the title, one detail line and a thin
 * bar. Hovering shows [RowView.hover] as icon buttons; a right-click offers [RowView.menu].
 */
@Composable
fun DownloadRow(
    view: RowView,
    selected: Boolean = false,
    saving: String? = null,
    indent: Dp = 0.dp,
    expanded: Boolean = false,
    onExpand: (() -> Unit)? = null,
    onAction: (CardAction) -> Unit,
) {
    val t = LocalTokens.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val text = if (selected) t.onSelection else t.text
    val secondary =
        when {
            selected -> t.onSelection.copy(alpha = 0.85f)
            view.tone == DetailTone.ERROR -> t.error
            view.tone == DetailTone.WARN -> t.warn
            else -> t.secondaryText
        }
    ContextMenuArea(items = {
        view.menu.map { action -> ContextMenuItem(actionLabel(action)) { onAction(action) } }
    }) {
        Row(
            Modifier
                .fillMaxWidth()
                .testTag("row")
                .padding(start = indent)
                .clip(RoundedCornerShape(6.dp))
                .background(
                    when {
                        selected -> t.selection
                        hovered -> t.hover
                        else -> Color.Transparent
                    },
                ).hoverable(interaction)
                .padding(horizontal = 8.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (onExpand != null) {
                Box(
                    Modifier
                        .size(16.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .clickable(role = Role.Button, onClick = onExpand)
                        .semantics { contentDescription = if (expanded) "Hide videos" else "Show videos" },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(if (expanded) Glyphs.ChevronDown else Glyphs.ChevronRight, null, Modifier.size(12.dp), tint = secondary)
                }
            }
            Box(
                Modifier.size(24.dp).clip(RoundedCornerShape(6.dp)).background(if (selected) t.onSelection else iconColour(view.icon)),
                contentAlignment = Alignment.Center,
            ) {
                Text(glyphText(view.glyph), fontSize = 11.sp, color = if (selected) t.selection else Color.White)
            }
            Column(Modifier.weight(1f)) {
                Text(view.title, color = text, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    saving ?: view.detail,
                    fontSize = 11.sp,
                    color =
                        if (saving != null &&
                            !selected
                        ) {
                            t.accent
                        } else {
                            secondary
                        },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                view.progress?.let { progress ->
                    Spacer(Modifier.height(4.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(
                                3.dp,
                            ).clip(RoundedCornerShape(2.dp))
                            .background(if (selected) t.onSelection.copy(alpha = 0.3f) else t.separator),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(progress.coerceIn(0f, 1f))
                                .height(3.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(if (selected) t.onSelection else t.accent),
                        )
                    }
                }
            }
            if (hovered) {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    view.hover.forEach { action -> ToolButton(glyphOf(action), actionLabel(action).removeSuffix("…")) { onAction(action) } }
                }
            } else {
                Spacer(Modifier.width(0.dp))
            }
        }
    }
}
