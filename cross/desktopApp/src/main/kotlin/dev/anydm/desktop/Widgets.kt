package dev.anydm.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class ButtonKind { PRIMARY, SECONDARY, GHOST, DESTRUCTIVE }

/** A button as the reference draws it: 32 dp high, 8 dp corners. */
@Composable
fun PButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.SECONDARY,
    glyph: Glyph? = null,
    hint: String? = null,
    enabled: Boolean = true,
) {
    val colors = Plexo.colors
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val (fill, ink, edge) =
        when (kind) {
            ButtonKind.PRIMARY -> Triple(colors.accent, colors.accentOnSolid, null)
            ButtonKind.SECONDARY -> Triple(if (hovered) colors.border else colors.bgTertiary, colors.text, colors.border)
            ButtonKind.GHOST -> Triple(if (hovered) colors.bgTertiary else Color.Transparent, colors.textSecondary, null)
            ButtonKind.DESTRUCTIVE -> Triple(colors.dangerBg, colors.danger, colors.dangerBorder)
        }
    val shape = RoundedCornerShape(PlexoRadius)
    Row(
        modifier
            .height(32.dp)
            .clip(shape)
            .background(fill)
            .let { if (edge != null) it.border(BorderStroke(0.5.dp, edge), shape) else it }
            .hoverable(source)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (glyph != null) GlyphIcon(glyph, ink, size = 14.dp)
        Txt(text, Plexo.type.button, color = ink)
        if (hint != null) Txt(hint, Plexo.type.badge.copy(fontWeight = null), color = ink.copy(alpha = 0.7f))
    }
}

/** A square button with just an icon, described for the screen reader by [label]. */
@Composable
fun IconButton(
    glyph: Glyph,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Plexo.colors.textTertiary,
    size: Dp = 28.dp,
    glyphSize: Dp = 16.dp,
) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(6.dp))
            .background(if (hovered) Plexo.colors.bgTertiary else Color.Transparent)
            .hoverable(source)
            .semantics { contentDescription = label }
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        GlyphIcon(glyph, if (hovered) Plexo.colors.text else tint, size = glyphSize)
    }
}

/** A tick box for a row or a group: checked, partly checked ([indeterminate]) or not. */
@Composable
fun PCheckbox(
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    indeterminate: Boolean = false,
) {
    val colors = Plexo.colors
    val on = checked || indeterminate
    val shape = RoundedCornerShape(4.dp)
    Box(
        modifier
            .size(16.dp)
            .clip(shape)
            .background(if (on) colors.accent else colors.inputBg)
            .border(1.dp, if (on) colors.accent else colors.borderStrong, shape)
            .semantics { contentDescription = label }
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange),
        contentAlignment = Alignment.Center,
    ) {
        when {
            checked -> GlyphIcon(Glyph.CHECK, colors.accentOnSolid, size = 12.dp)
            indeterminate -> GlyphIcon(Glyph.DASH, colors.accentOnSolid, size = 12.dp)
        }
    }
}

/** An on/off switch. */
@Composable
fun PSwitch(
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    val colors = Plexo.colors
    Box(
        modifier
            .size(width = 36.dp, height = 20.dp)
            .clip(CircleShape)
            .background(
                if (checked) {
                    colors.accent
                } else if (colors.dark) {
                    colors.trackBg
                } else {
                    colors.borderStrong
                },
            ).semantics { contentDescription = label }
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(2.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(Modifier.size(16.dp).clip(CircleShape).background(if (checked) colors.accentOnSolid else Color.White))
    }
}

/** A small group of mutually exclusive choices, one pressed. */
@Composable
fun <T> Segmented(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    val colors = Plexo.colors
    val shape = RoundedCornerShape(6.dp)
    Row(
        modifier
            .height(26.dp)
            .border(0.5.dp, colors.border, shape)
            .padding(2.dp)
            .semantics { contentDescription = label },
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for ((value, text) in options) {
            val on = value == selected
            Box(
                Modifier
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (on) colors.bg else Color.Transparent)
                    .selectable(selected = on, role = Role.RadioButton, onClick = { onSelect(value) })
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Txt(
                    text,
                    Plexo.type.detail.copy(fontSize = Plexo.type.detail.fontSize * 0.96f),
                    color = if (on) colors.text else colors.textTertiary,
                )
            }
        }
    }
}

/**
 * A question laid over the whole window: [title], [body], and two choices, or three with [secondaryText] (shown between the
 * dismiss and the confirm button). Escape or a click outside answers no. The card eats clicks so they don't fall through to
 * the scrim.
 */
@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    destructive: Boolean = false,
    dismissText: String = "Cancel",
    maxBodyLines: Int = 4,
    secondaryText: String? = null,
    onSecondary: () -> Unit = {},
    cardWidth: Dp = 400.dp,
) {
    val colors = Plexo.colors
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .focusRequester(focus)
            .focusTarget()
            .onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) {
                    onCancel()
                    true
                } else {
                    false
                }
            }.pointerInput(Unit) { detectTapGestures { onCancel() } },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(cardWidth)
                .clip(RoundedCornerShape(12.dp))
                .background(colors.bgSecondary)
                .border(0.5.dp, colors.border, RoundedCornerShape(12.dp))
                .pointerInput(Unit) { detectTapGestures { } }
                .semantics { contentDescription = title }
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Txt(title, Plexo.type.menu)
            Txt(body, Plexo.type.hint, color = colors.textSecondary, maxLines = maxBodyLines)
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                PButton(dismissText, onCancel)
                if (secondaryText != null) PButton(secondaryText, onSecondary)
                PButton(confirmText, onConfirm, kind = if (destructive) ButtonKind.DESTRUCTIVE else ButtonKind.PRIMARY)
            }
        }
    }
}

/**
 * A one-line text field in the reference's look. [onSubmit] runs on Enter. It takes keys like any text field: the window's
 * shortcuts leave them alone while a dialog holding one is open.
 */
@Composable
fun PTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    autoFocus: Boolean = false,
    onSubmit: () -> Unit = {},
) {
    val colors = Plexo.colors
    val focus = remember { FocusRequester() }
    if (autoFocus) LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val shape = RoundedCornerShape(PlexoRadius)
    androidx.compose.foundation.text.BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = Plexo.type.speed.copy(color = colors.text),
        cursorBrush =
            androidx.compose.ui.graphics
                .SolidColor(colors.accent),
        modifier =
            modifier
                .fillMaxWidth()
                .height(36.dp)
                .clip(shape)
                .background(colors.inputBg)
                .border(0.5.dp, colors.borderStrong, shape)
                .focusRequester(focus)
                .semantics { contentDescription = label }
                .onPreviewKeyEvent {
                    if (it.type == KeyEventType.KeyDown && it.key == Key.Enter) {
                        onSubmit()
                        true
                    } else {
                        false
                    }
                },
        decorationBox = { inner ->
            Box(Modifier.padding(horizontal = 10.dp), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) Txt(placeholder, Plexo.type.speed, color = colors.textTertiary)
                inner()
            }
        },
    )
}
