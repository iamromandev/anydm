package dev.anydm.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** One network feeding the diagram. */
class CombineSource(
    val name: String,
    val color: Color,
    val speed: Long,
    val active: Boolean,
)

private val DiagramWidth = 232.dp
private val DiagramHeight = 118.dp
private val LabelWidth = 112.dp

/** Each network's name and speed on the left, curves that merge into one node and the combined arrow to the right of it. */
@Composable
fun CombineDiagram(
    sources: List<CombineSource>,
    unit: SpeedUnit,
    paused: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = Plexo.colors
    Box(modifier.size(DiagramWidth, DiagramHeight)) {
        Canvas(Modifier.size(DiagramWidth, DiagramHeight)) {
            val startX = LabelWidth.toPx() + 6.dp.toPx()
            val mergeX = size.width * 0.74f
            val midY = size.height / 2
            sources.forEachIndexed { i, source ->
                val y = size.height * (i + 1) / (sources.size + 1)
                val color = if (paused || !source.active) source.color.copy(alpha = 0.4f) else source.color
                val curve = Path()
                curve.moveTo(startX, y)
                curve.cubicTo(startX + (mergeX - startX) * 0.55f, y, mergeX - (mergeX - startX) * 0.45f, midY, mergeX, midY)
                drawPath(
                    curve,
                    color,
                    style = Stroke(width = 1.6.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))),
                )
                drawCircle(color, radius = 3.dp.toPx(), center = Offset(startX, y))
            }
            val node = if (paused) colors.textTertiary else colors.nodeAccent
            drawCircle(colors.bg, radius = 8.dp.toPx(), center = Offset(mergeX, midY))
            drawCircle(node, radius = 8.dp.toPx(), center = Offset(mergeX, midY), style = Stroke(width = 1.4.dp.toPx()))
            val tail = mergeX + 10.dp.toPx()
            val tip = size.width - 2.dp.toPx()
            drawLine(node, Offset(tail, midY), Offset(tip - 5.dp.toPx(), midY), strokeWidth = 5.dp.toPx())
            val head = Path()
            head.moveTo(tip, midY)
            head.lineTo(tip - 8.dp.toPx(), midY - 5.dp.toPx())
            head.lineTo(tip - 8.dp.toPx(), midY + 5.dp.toPx())
            head.close()
            drawPath(head, node)
        }
        sources.forEachIndexed { i, source ->
            val y = DiagramHeight * (i + 1) / (sources.size + 1)
            Column(
                Modifier.width(LabelWidth).offset(y = y - 15.dp),
                horizontalAlignment = Alignment.End,
            ) {
                Txt(
                    source.name.uppercase(),
                    Plexo.type.badge.copy(fontSize = Plexo.type.badge.fontSize * 0.9f),
                    color = colors.textTertiary,
                )
                Txt(
                    if (paused) "—" else formatRate(source.speed, unit),
                    Plexo.type.speed.copy(textAlign = TextAlign.End),
                    color = if (paused) colors.textTertiary else source.color,
                )
            }
        }
        Txt(
            "COMBINED",
            Plexo.type.badge.copy(fontSize = Plexo.type.badge.fontSize * 0.9f),
            Modifier.offset(x = DiagramWidth * 0.62f, y = DiagramHeight / 2 - 26.dp),
            color = colors.textTertiary,
        )
    }
}
