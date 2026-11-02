package dev.anydm.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

internal val PasteShortcut = if (IsMac) "⌘V" else "Ctrl+V"
internal val NewShortcut = if (IsMac) "⌘N" else "Ctrl+N"

/** Nothing listed yet: how to start one. */
@Composable
fun EmptyState(onNewDownload: () -> Unit) {
    val colors = Plexo.colors
    Column(
        Modifier.fillMaxWidth().padding(top = 64.dp, bottom = 40.dp, start = 20.dp, end = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        CombineSketch()
        Txt("No downloads yet", Plexo.type.title)
        Txt(
            "Paste a link ($PasteShortcut) or drop a .torrent anywhere in this window.",
            Plexo.type.hint,
            Modifier.widthIn(max = 380.dp),
            color = colors.textSecondary,
            maxLines = 3,
        )
        PButton(
            "New download",
            onNewDownload,
            Modifier.padding(top = 4.dp),
            kind = ButtonKind.PRIMARY,
            glyph = Glyph.PLUS,
            hint = NewShortcut,
        )
    }
}

/** A filter that matches nothing: say so and offer the whole list. */
@Composable
fun NothingForFilter(
    filter: HomeFilter,
    onShowAll: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 64.dp, horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Txt(
            when (filter) {
                HomeFilter.IN_PROGRESS -> "No downloads in progress"
                HomeFilter.FINISHED -> "No finished downloads"
                else -> "No downloads need attention"
            },
            Plexo.type.menu.copy(textAlign = TextAlign.Center),
        )
        PButton("Show all downloads", onShowAll)
    }
}

// Three networks merging into one stream, drawn muted: the app's idea in one picture.
@Composable
private fun CombineSketch() {
    val colors = Plexo.colors
    Box(Modifier.size(width = 190.dp, height = 100.dp)) {
        Canvas(Modifier.size(width = 190.dp, height = 100.dp)) {
            val dash = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx()))
            val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, pathEffect = dash)
            val join = Offset(size.width * 0.62f, size.height * 0.5f)
            for (y in listOf(0.2f, 0.5f, 0.8f)) {
                val start = Offset(size.width * 0.3f, size.height * y)
                val path = Path()
                path.moveTo(start.x, start.y)
                path.cubicTo(start.x + 40.dp.toPx(), start.y, join.x - 40.dp.toPx(), join.y, join.x, join.y)
                drawPath(path, colors.iconMuted, style = stroke)
                drawCircle(colors.iconMuted, radius = 3.dp.toPx(), center = start)
            }
            drawLine(colors.iconMuted, join, Offset(size.width * 0.95f, join.y), strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
        }
        for ((index, label) in listOf("WI-FI", "USB", "ETHERNET").withIndex()) {
            Txt(
                label,
                Plexo.type.badge.copy(fontSize = Plexo.type.badge.fontSize * 0.8f),
                Modifier.padding(top = (13 + index * 30).dp),
                color = colors.iconMuted,
            )
        }
    }
}
