package dev.anydm.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The small line icons the screens use, drawn on a 16-unit grid. */
enum class Glyph {
    PLUS,
    CHEVRON_RIGHT,
    CHEVRON_DOWN,
    PAUSE,
    PLAY,
    RETRY,
    CLOSE,
    CHECK,
    DASH,
    SUN,
    MOON,
    CHEVRON_LEFT,
    FOLDER,
    PENCIL,
    ALERT,
}

@Composable
fun GlyphIcon(
    glyph: Glyph,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
) {
    Canvas(modifier.size(size)) { draw(glyph, tint) }
}

private fun DrawScope.draw(
    glyph: Glyph,
    tint: Color,
) {
    val unit = size.minDimension / 16f
    val stroke = Stroke(width = 1.4f * unit, cap = StrokeCap.Round, join = StrokeJoin.Round)

    fun p(
        x: Float,
        y: Float,
    ) = Offset(x * unit, y * unit)

    fun line(
        points: List<Offset>,
        closed: Boolean = false,
    ) {
        val path = Path()
        points.forEachIndexed { i, o -> if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y) }
        if (closed) path.close()
        drawPath(path, tint, style = stroke)
    }
    when (glyph) {
        Glyph.PLUS -> {
            line(listOf(p(8f, 3f), p(8f, 13f)))
            line(listOf(p(3f, 8f), p(13f, 8f)))
        }

        Glyph.CHEVRON_RIGHT -> {
            line(listOf(p(6f, 3.5f), p(10.5f, 8f), p(6f, 12.5f)))
        }

        Glyph.CHEVRON_LEFT -> {
            line(listOf(p(10f, 3.5f), p(5.5f, 8f), p(10f, 12.5f)))
        }

        Glyph.FOLDER -> {
            line(
                listOf(p(2f, 4.5f), p(6f, 4.5f), p(7.5f, 6f), p(14f, 6f), p(14f, 12.5f), p(2f, 12.5f)),
                closed = true,
            )
        }

        Glyph.PENCIL -> {
            line(listOf(p(3f, 13f), p(3.6f, 10.2f), p(10.8f, 3f), p(13f, 5.2f), p(5.8f, 12.4f)), closed = true)
            line(listOf(p(9.4f, 4.4f), p(11.6f, 6.6f)))
        }

        Glyph.CHEVRON_DOWN -> {
            line(listOf(p(3.5f, 6f), p(8f, 10.5f), p(12.5f, 6f)))
        }

        Glyph.PAUSE -> {
            line(listOf(p(5.5f, 3.5f), p(5.5f, 12.5f)))
            line(listOf(p(10.5f, 3.5f), p(10.5f, 12.5f)))
        }

        Glyph.PLAY -> {
            line(listOf(p(5f, 3.2f), p(12f, 8f), p(5f, 12.8f)), closed = true)
        }

        Glyph.RETRY -> {
            drawArc(
                tint,
                startAngle = -60f,
                sweepAngle = 300f,
                useCenter = false,
                topLeft = p(3f, 3f),
                size =
                    androidx.compose.ui.geometry
                        .Size(10f * unit, 10f * unit),
                style = stroke,
            )
            line(listOf(p(8.6f, 1.6f), p(11.4f, 3.2f), p(9.4f, 5.6f)))
        }

        Glyph.ALERT -> {
            line(listOf(p(8f, 2.2f), p(14.2f, 13.2f), p(1.8f, 13.2f)), closed = true)
            line(listOf(p(8f, 6.4f), p(8f, 9.4f)))
            drawCircle(tint, radius = 0.7f * unit, center = p(8f, 11.4f))
        }

        Glyph.CLOSE -> {
            line(listOf(p(4f, 4f), p(12f, 12f)))
            line(listOf(p(12f, 4f), p(4f, 12f)))
        }

        Glyph.CHECK -> {
            line(listOf(p(3.5f, 8.5f), p(6.5f, 11.5f), p(12.5f, 4.5f)))
        }

        Glyph.DASH -> {
            line(listOf(p(4f, 8f), p(12f, 8f)))
        }

        Glyph.SUN -> {
            drawCircle(tint, radius = 3.2f * unit, center = p(8f, 8f), style = stroke)
            for (k in 0 until 8) {
                val angle = Math.toRadians(k * 45.0)
                val dx = Math.cos(angle).toFloat()
                val dy = Math.sin(angle).toFloat()
                line(listOf(p(8f + dx * 5.2f, 8f + dy * 5.2f), p(8f + dx * 6.8f, 8f + dy * 6.8f)))
            }
        }

        Glyph.MOON -> {
            val path = Path()
            path.moveTo(13.5f * unit, 9.85f * unit)
            path.cubicTo(11.2f * unit, 11.6f * unit, 7.9f * unit, 11.4f * unit, 5.9f * unit, 9.2f * unit)
            path.cubicTo(3.9f * unit, 7.0f * unit, 4.0f * unit, 4.6f * unit, 6.15f * unit, 2.5f * unit)
            path.cubicTo(3.2f * unit, 3.0f * unit, 1.6f * unit, 6.0f * unit, 2.8f * unit, 9.0f * unit)
            path.cubicTo(4.2f * unit, 12.4f * unit, 8.6f * unit, 14.0f * unit, 11.6f * unit, 12.0f * unit)
            path.cubicTo(12.4f * unit, 11.5f * unit, 13.1f * unit, 10.8f * unit, 13.5f * unit, 9.85f * unit)
            drawPath(path, tint, style = stroke)
        }
    }
}
