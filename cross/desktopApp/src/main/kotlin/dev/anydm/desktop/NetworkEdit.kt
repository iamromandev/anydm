package dev.anydm.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * A pencil that opens the rename and color popover of one network under it. [anchor] says which part of the screen this is, so
 * the one popover [editor] has open is drawn by the pencil that opened it. [name] is what the network is called now.
 */
@Composable
fun NetworkEditPencil(
    editor: NetworkEditor,
    anchor: String,
    key: String,
    name: String,
    osName: String,
    keys: WindowKeys,
    size: Dp = 28.dp,
    glyphSize: Dp = 16.dp,
) {
    Box {
        IconButton(Glyph.PENCIL, "Edit $name", { editor.start(key, anchor, osName) }, size = size, glyphSize = glyphSize)
        NetworkEditPopupIf(editor, anchor, key, keys)
    }
}

/** The popover, when [editor] has one open for [key] from [anchor]; for a part that draws its own pencil. */
@Composable
fun NetworkEditPopupIf(
    editor: NetworkEditor,
    anchor: String,
    key: String,
    keys: WindowKeys,
) {
    val editing by editor.editing.collectAsState()
    editing?.takeIf { it.key == key && it.anchor == anchor }?.let { NetworkEditPopup(editor, it, keys) }
}

/**
 * Rename and recolor one network. Done, Enter or a click away saves (a click away with a name that can't be saved drops it);
 * Escape throws the draft away.
 */
@Composable
fun NetworkEditPopup(
    editor: NetworkEditor,
    edit: NetworkEdit,
    keys: WindowKeys,
) {
    val colors = Plexo.colors
    BlockWindowKeys(keys)
    // The file is written on a background thread; the popover closes when the write has ended.
    val scope = rememberCoroutineScope()
    val save: () -> Unit = { scope.launch(Dispatchers.IO) { editor.save() } }
    Popup(
        alignment = Alignment.TopStart,
        offset = IntOffset(0, with(LocalDensity.current) { 30.dp.roundToPx() }),
        onDismissRequest = { scope.launch(Dispatchers.IO) { if (!editor.save()) editor.cancel() } },
        // Escape is the popover's own: it throws the draft away, where a click away would save it.
        properties = PopupProperties(focusable = true, dismissOnBackPress = false),
    ) {
        val shape = RoundedCornerShape(12.dp)
        Column(
            Modifier
                .width(320.dp)
                .clip(shape)
                .background(colors.bgSecondary)
                .border(0.5.dp, colors.border, shape)
                .semantics { contentDescription = "Edit network" }
                .onPreviewKeyEvent {
                    if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) {
                        editor.cancel()
                        true
                    } else {
                        false
                    }
                }.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FieldLabel("NAME")
                PTextField(
                    edit.name,
                    editor::setName,
                    label = "Network name",
                    placeholder = edit.osName,
                    autoFocus = true,
                    onSubmit = save,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FieldLabel("COLOR")
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    for (swatch in colors.swatches) SwatchDot(swatch, selected = swatch.id == edit.colorId) { editor.setColor(swatch.id) }
                }
            }
            edit.error?.let { Txt(it, Plexo.type.hint, color = colors.danger, maxLines = 3) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                PButton("Done", save, kind = ButtonKind.PRIMARY)
            }
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Txt(
        text,
        Plexo.type.badge.copy(fontSize = Plexo.type.badge.fontSize * 0.9f, letterSpacing = TextUnit.Unspecified),
        color = Plexo.colors.textTertiary,
    )
}

@Composable
private fun SwatchDot(
    swatch: Swatch,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(24.dp)
            .semantics { contentDescription = swatch.label }
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .border(2.dp, if (selected) swatch.solid else Color.Transparent, CircleShape)
            .padding(4.dp)
            .background(swatch.solid, CircleShape),
    )
}
