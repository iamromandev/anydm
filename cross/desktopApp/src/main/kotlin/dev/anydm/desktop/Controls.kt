package dev.anydm.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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

@Composable
fun Divider() = Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))

@Composable
fun OutlineButton(
    label: String,
    tag: String,
    onClick: () -> Unit = {},
    icon: Boolean = false,
    enabled: Boolean = true,
) {
    val c = colors
    val shape = RoundedCornerShape(Radius)
    Row(
        Modifier
            .testTag(tag)
            .height(if (icon) 40.dp else 36.dp)
            .border(1.dp, c.border, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (icon) FolderIcon(c.text)
        Text(label, color = if (enabled) c.text else c.textSecondary.copy(alpha = 0.6f), fontSize = 14.sp, maxLines = 1)
    }
}

@Composable
fun PrimaryButton(
    label: String,
    tag: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val c = colors
    Box(
        Modifier
            .testTag(tag)
            .height(36.dp)
            .background(if (enabled) c.accent else c.track, RoundedCornerShape(Radius))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (enabled) c.onAccent else c.textSecondary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

@Composable
fun AnydmSwitch(
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    tag: String,
    label: String? = null,
) {
    val c = colors
    Switch(
        checked = checked,
        onCheckedChange = onChange,
        modifier = Modifier.testTag(tag).semantics { if (label != null) contentDescription = label },
        colors =
            SwitchDefaults.colors(
                checkedTrackColor = c.accent,
                checkedThumbColor = c.onAccent,
                checkedBorderColor = c.accent,
                uncheckedTrackColor = c.track,
                uncheckedThumbColor = c.text,
                uncheckedBorderColor = c.border,
            ),
    )
}

@Composable
fun RadioDot(
    selected: Boolean,
    onClick: () -> Unit,
    tag: String,
    label: String,
) {
    val c = colors
    Row(
        Modifier
            .testTag(tag)
            .selectable(selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier.size(16.dp).border(1.5.dp, if (selected) c.accent else c.border, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Box(Modifier.size(8.dp).background(c.accent, CircleShape))
        }
        Text(label, color = c.text, fontSize = 13.sp, maxLines = 1)
    }
}

@Composable
fun NumberField(
    text: String,
    onChange: (String) -> Unit,
    enabled: Boolean,
    error: Boolean,
    suffix: String,
    tag: String,
    label: String,
) {
    val c = colors
    val shape = RoundedCornerShape(Radius)
    Row(
        Modifier
            .height(32.dp)
            .width(120.dp)
            .background(if (enabled) Color.Transparent else c.track.copy(alpha = 0.5f), shape)
            .border(1.dp, if (error) c.danger else c.border, shape)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            text,
            onChange,
            enabled = enabled,
            singleLine = true,
            textStyle =
                TextStyle(
                    color = if (enabled) c.text else c.textSecondary.copy(alpha = 0.6f),
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                ),
            cursorBrush = SolidColor(c.text),
            modifier =
                Modifier
                    .weight(1f)
                    .testTag(tag)
                    .semantics {
                        contentDescription = label
                        if (error) error("Invalid value")
                    },
        )
        Text(suffix, color = c.textSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
    }
}

@Composable
fun Stepper(
    value: Int,
    min: Int,
    max: Int,
    onChange: (Int) -> Unit,
    tag: String,
) {
    val c = colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        StepButton("−", "Fewer", "$tag-minus", value > min) { onChange(value - 1) }
        Text(
            value.toString(),
            color = c.text,
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            modifier = Modifier.testTag("$tag-value").width(28.dp),
            textAlign = TextAlign.Center,
        )
        StepButton("+", "More", "$tag-plus", value < max) { onChange(value + 1) }
    }
}

@Composable
private fun StepButton(
    glyph: String,
    description: String,
    tag: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val c = colors
    val shape = RoundedCornerShape(Radius)
    Box(
        Modifier
            .testTag(tag)
            .size(32.dp)
            .border(1.dp, c.border, shape)
            .semantics { contentDescription = description }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, color = if (enabled) c.text else c.textSecondary.copy(alpha = 0.5f), fontSize = 16.sp, maxLines = 1)
    }
}

@Composable
fun <T> SegmentedChips(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    tagPrefix: (T) -> String,
) {
    val c = colors
    Row(
        Modifier.border(1.dp, c.border, RoundedCornerShape(Radius)).padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { (key, label) ->
            val on = key == selected
            Box(
                Modifier
                    .testTag(tagPrefix(key))
                    .background(if (on) c.accent else Color.Transparent, RoundedCornerShape(6.dp))
                    .selectable(on, role = Role.RadioButton, onClick = { onSelect(key) })
                    .semantics { contentDescription = label }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            ) {
                Text(
                    label,
                    color = if (on) c.onAccent else c.textSecondary,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
            }
        }
    }
}
