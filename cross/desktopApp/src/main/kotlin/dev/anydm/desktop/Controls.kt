package dev.anydm.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
fun CheckBox(
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    tag: String,
    description: String,
) {
    val c = colors
    Box(
        modifier =
            Modifier
                .size(18.dp)
                .testTag(tag)
                .semantics { contentDescription = description }
                .background(if (checked) c.accent else Color.Transparent, RoundedCornerShape(4.dp))
                .border(1.dp, if (checked) c.accent else c.border, RoundedCornerShape(4.dp))
                .toggleable(checked, role = Role.Checkbox, onValueChange = onChange),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Canvas(Modifier.size(12.dp)) {
                val s = Stroke(width = 2f * density, cap = StrokeCap.Round)
                drawLine(
                    c.onAccent,
                    Offset(size.width * .15f, size.height * .55f),
                    Offset(size.width * .4f, size.height * .8f),
                    s.width,
                    s.cap,
                )
                drawLine(
                    c.onAccent,
                    Offset(size.width * .4f, size.height * .8f),
                    Offset(size.width * .88f, size.height * .22f),
                    s.width,
                    s.cap,
                )
            }
        }
    }
}

@Composable
fun IconBox(
    tag: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    bordered: Boolean = false,
    icon: @Composable (Color) -> Unit,
) {
    val c = colors
    val shape = RoundedCornerShape(Radius)
    Box(
        modifier =
            modifier
                .size(32.dp)
                .testTag(tag)
                .semantics { contentDescription = description }
                .then(if (bordered) Modifier.border(1.dp, c.border, shape) else Modifier)
                .background(Color.Transparent, shape)
                .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { icon(c.textSecondary) }
}

@Composable
fun ChevronIcon(
    color: Color,
    down: Boolean = false,
    size: Int = 14,
) {
    Canvas(Modifier.size(size.dp)) {
        val w = this.size.width
        val st = 1.5f * density
        if (down) {
            drawLine(color, Offset(w * .2f, w * .38f), Offset(w * .5f, w * .66f), st, StrokeCap.Round)
            drawLine(color, Offset(w * .5f, w * .66f), Offset(w * .8f, w * .38f), st, StrokeCap.Round)
        } else {
            drawLine(color, Offset(w * .38f, w * .2f), Offset(w * .66f, w * .5f), st, StrokeCap.Round)
            drawLine(color, Offset(w * .66f, w * .5f), Offset(w * .38f, w * .8f), st, StrokeCap.Round)
        }
    }
}

@Composable
fun PauseIcon(color: Color) {
    Canvas(Modifier.size(14.dp)) {
        val w = size.width
        drawRect(color, Offset(w * .22f, w * .15f), Size(w * .18f, w * .7f), style = Fill)
        drawRect(color, Offset(w * .6f, w * .15f), Size(w * .18f, w * .7f), style = Fill)
    }
}

@Composable
fun PlayIcon(color: Color) {
    Canvas(Modifier.size(14.dp)) {
        val w = size.width
        val p =
            androidx.compose.ui.graphics.Path().apply {
                moveTo(w * .28f, w * .15f)
                lineTo(w * .82f, w * .5f)
                lineTo(w * .28f, w * .85f)
                close()
            }
        drawPath(p, color)
    }
}

@Composable
fun PlusIcon(color: Color) {
    Canvas(Modifier.size(12.dp)) {
        val w = size.width
        val st = 1.6f * density
        drawLine(color, Offset(w * .5f, w * .1f), Offset(w * .5f, w * .9f), st, StrokeCap.Round)
        drawLine(color, Offset(w * .1f, w * .5f), Offset(w * .9f, w * .5f), st, StrokeCap.Round)
    }
}

@Composable
fun ThemeIcon(
    color: Color,
    dark: Boolean,
) {
    Canvas(Modifier.size(14.dp)) {
        val w = size.width
        if (dark) {
            // Moon: a disc with a background-coloured bite is avoided; draw a sun-less crescent via two circles.
            val path =
                androidx.compose.ui.graphics.Path().apply {
                    addOval(
                        androidx.compose.ui.geometry
                            .Rect(Offset(w * .1f, w * .1f), Size(w * .8f, w * .8f)),
                    )
                }
            val bite =
                androidx.compose.ui.graphics.Path().apply {
                    addOval(
                        androidx.compose.ui.geometry
                            .Rect(Offset(w * .38f, w * .0f), Size(w * .8f, w * .8f)),
                    )
                }
            val crescent =
                androidx.compose.ui.graphics.Path
                    .combine(androidx.compose.ui.graphics.PathOperation.Difference, path, bite)
            drawPath(crescent, color)
        } else {
            drawCircle(color, radius = w * .2f)
            for (i in 0 until 8) {
                val a = Math.toRadians(i * 45.0)
                val dx = Math.cos(a).toFloat()
                val dy = Math.sin(a).toFloat()
                drawLine(
                    color,
                    Offset(w * .5f + dx * w * .32f, w * .5f + dy * w * .32f),
                    Offset(
                        w * .5f + dx * w * .46f,
                        w * .5f + dy * w * .46f,
                    ),
                    1.3f * density,
                    StrokeCap.Round,
                )
            }
        }
    }
}

@Composable
fun CloseIcon(color: Color) {
    Canvas(Modifier.size(12.dp)) {
        val w = size.width
        val st = 1.6f * density
        drawLine(color, Offset(w * .15f, w * .15f), Offset(w * .85f, w * .85f), st, StrokeCap.Round)
        drawLine(color, Offset(w * .85f, w * .15f), Offset(w * .15f, w * .85f), st, StrokeCap.Round)
    }
}

@Composable
fun FolderIcon(color: Color) {
    Canvas(Modifier.size(16.dp)) {
        val w = size.width
        val p =
            androidx.compose.ui.graphics.Path().apply {
                moveTo(w * .1f, w * .25f)
                lineTo(w * .4f, w * .25f)
                lineTo(w * .5f, w * .36f)
                lineTo(w * .9f, w * .36f)
                lineTo(w * .9f, w * .8f)
                lineTo(w * .1f, w * .8f)
                close()
            }
        drawPath(p, color, style = Stroke(width = 1.3f * density, cap = StrokeCap.Round))
    }
}
