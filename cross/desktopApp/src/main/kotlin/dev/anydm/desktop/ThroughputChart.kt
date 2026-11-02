package dev.anydm.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.anydm.engine.SpeedWindow

/** Where the minute the chart shows ends: now, where it paused, or where the download finished. */
enum class ChartEnd(
    val label: String,
) {
    NOW("now"),
    PAUSE("pause"),
    END("end"),
}

/** One network's layer: its color and its speeds in bytes per second, oldest first. */
class ChartLayer(
    val color: Color,
    val values: List<Long>,
)

private val PlotHeight = 104.dp

/**
 * Recent throughput as stacked areas, one layer per network, the total as a line over them. A fixed minute with the newest
 * second at the right edge: a second is always the same width, so a young download sits at the right with the rest empty.
 */
@Composable
fun ThroughputChart(
    layers: List<ChartLayer>,
    unit: SpeedUnit,
    ends: ChartEnd,
    modifier: Modifier = Modifier,
) {
    val colors = Plexo.colors
    val length = layers.maxOfOrNull { it.values.size } ?: 0
    val totals = List(length) { i -> layers.sumOf { it.values.getOrElse(i) { 0L } } }
    val axis = speedAxis(totals.maxOrNull() ?: 0, unit)
    val labelStyle = Plexo.type.detail.copy(fontSize = Plexo.type.detail.fontSize * 0.8f)
    val ago = if (ends == ChartEnd.NOW) "ago" else "before the ${ends.label}"
    val summary =
        "Combined throughput over the last minute. Currently ${formatRate(totals.lastOrNull() ?: 0, unit)}, " +
            "fastest ${formatRate(totals.maxOrNull() ?: 0, unit)}."
    Row(modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // The scale beside the plot, each number with its unit.
        Box(Modifier.height(PlotHeight)) {
            Txt(axis.ticks.lastOrNull()?.label ?: "", labelStyle, Modifier.alpha(0f))
            for (tick in axis.ticks) {
                val fraction = (1 - tick.value / axis.top).toFloat()
                Txt(
                    tick.label,
                    labelStyle,
                    Modifier.align(Alignment.TopEnd).offset(y = PlotHeight * fraction - 6.dp),
                    color = colors.textTertiary,
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Canvas(Modifier.fillMaxWidth().height(PlotHeight).semantics { contentDescription = summary }) {
                val w = size.width
                val h = size.height
                val xStep = w / (SpeedWindow.SIZE - 1)
                val firstX = (SpeedWindow.SIZE - length) * xStep

                fun x(i: Int) = firstX + i * xStep

                fun y(value: Double) = (h - value / axis.top * h).toFloat()
                for (tick in axis.ticks) {
                    val ty = y(tick.value)
                    drawLine(colors.textTertiary.copy(alpha = 0.3f), Offset(0f, ty), Offset(w, ty), strokeWidth = 1.dp.toPx())
                }
                if (length < 2) return@Canvas
                var below = DoubleArray(length)
                for (layer in layers) {
                    val above = DoubleArray(length) { below[it] + layer.values.getOrElse(it) { 0L } }
                    val path = Path()
                    for (i in 0 until length) if (i == 0) path.moveTo(x(i), y(above[i])) else path.lineTo(x(i), y(above[i]))
                    for (i in length - 1 downTo 0) path.lineTo(x(i), y(below[i]))
                    path.close()
                    drawPath(path, layer.color.copy(alpha = 0.62f))
                    below = above
                }
                val line = Path()
                for (i in 0 until length) {
                    if (i ==
                        0
                    ) {
                        line.moveTo(x(i), y(totals[i].toDouble()))
                    } else {
                        line.lineTo(x(i), y(totals[i].toDouble()))
                    }
                }
                drawPath(
                    line,
                    colors.nodeAccent.copy(alpha = 0.85f),
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Txt("${SpeedWindow.SIZE}s $ago", labelStyle, color = colors.textTertiary)
                Box(Modifier.weight(1f))
                Txt(ends.label, labelStyle, color = colors.textTertiary)
            }
        }
    }
}
