package dev.anydm.desktop.chrome

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.anydm.desktop.theme.LocalTokens
import dev.anydm.desktop.theme.isMac
import dev.anydm.model.CategoryDto
import dev.anydm.model.DOWNLOADS_CATEGORY_ID

/** Room the macOS traffic lights need when the toolbar is drawn into the title bar. */
fun toolbarInset(os: String = System.getProperty("os.name")): Dp = if (isMac(os)) 78.dp else 0.dp

/** A 28 dp icon button with a hover fill and a tooltip; [tip] is also its accessible name. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ToolButton(
    glyph: ImageVector,
    tip: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val t = LocalTokens.current
    TooltipArea(
        tooltip = {
            Surface(shape = RoundedCornerShape(4.dp), color = t.bar, border = BorderStroke(1.dp, t.separator), shadowElevation = 4.dp) {
                Text(tip, Modifier.padding(horizontal = 6.dp, vertical = 3.dp), fontSize = 11.sp, color = t.text)
            }
        },
        delayMillis = 600,
    ) {
        val interaction = remember { MutableInteractionSource() }
        val hovered by interaction.collectIsHoveredAsState()
        Box(
            Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (hovered && enabled) t.hover else Color.Transparent)
                .hoverable(interaction)
                .clickable(interaction, null, enabled = enabled, role = Role.Button, onClick = onClick)
                .semantics { contentDescription = tip },
            contentAlignment = Alignment.Center,
        ) {
            Icon(glyph, null, Modifier.size(16.dp), tint = if (enabled) t.text else t.secondaryText)
        }
    }
}

/**
 * The window's top bar: add, pause all, resume all, the link field with its preset menu, `.torrent`,
 * settings. On macOS it sits in the title bar, [inset] clear of the traffic lights; its empty space drags
 * the window through [LocalWindowDrag].
 */
@Composable
fun Toolbar(
    inset: Dp,
    link: String,
    onLink: (String) -> Unit,
    onSubmit: () -> Unit,
    adding: Boolean,
    preset: String,
    presets: List<Pair<String, String>>,
    onPreset: (String) -> Unit,
    focus: FocusRequester,
    onPauseAll: () -> Unit,
    onResumeAll: () -> Unit,
    onTorrent: () -> Unit,
    onSettings: () -> Unit,
    onLinkFocus: (Boolean) -> Unit = {},
    onAddMany: () -> Unit = {},
    categories: List<CategoryDto> = emptyList(),
    addCategory: String = DOWNLOADS_CATEGORY_ID,
    onAddCategory: (String) -> Unit = {},
) {
    val t = LocalTokens.current
    LocalWindowDrag.current {
        Row(
            Modifier
                .fillMaxWidth()
                .height(40.dp)
                .background(t.bar)
                .padding(start = inset + 8.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ToolButton(Glyphs.Add, "Add a link") { focus.requestFocus() }
            ToolButton(Glyphs.Many, "Add many links…", onClick = onAddMany)
            ToolButton(Glyphs.Pause, "Pause all", onClick = onPauseAll)
            ToolButton(Glyphs.Resume, "Resume all", onClick = onResumeAll)
            LinkField(
                link,
                onLink,
                onSubmit,
                adding,
                preset,
                presets,
                onPreset,
                focus,
                onLinkFocus,
                categories,
                addCategory,
                onAddCategory,
                Modifier.weight(1f).widthIn(max = 640.dp),
            )
            ToolButton(Glyphs.Torrent, "Open a .torrent…", onClick = onTorrent)
            ToolButton(Glyphs.Settings, "Settings", onClick = onSettings)
        }
    }
    HorizontalDivider(color = t.separator)
}

@Composable
private fun LinkField(
    link: String,
    onLink: (String) -> Unit,
    onSubmit: () -> Unit,
    adding: Boolean,
    preset: String,
    presets: List<Pair<String, String>>,
    onPreset: (String) -> Unit,
    focus: FocusRequester,
    onLinkFocus: (Boolean) -> Unit,
    categories: List<CategoryDto>,
    addCategory: String,
    onAddCategory: (String) -> Unit,
    modifier: Modifier,
) {
    val t = LocalTokens.current
    val field = if (t.dark) Color(0xFF3A3A3C) else Color(0xFFE6E6E8)
    Row(
        modifier
            .height(26.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(field)
            .padding(start = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (link.isEmpty()) Text(if (adding) "Adding…" else "Paste a link or magnet…", color = t.secondaryText)
            BasicTextField(
                link,
                onLink,
                enabled = !adding,
                singleLine = true,
                textStyle = TextStyle(fontSize = 13.sp, color = t.text),
                cursorBrush = SolidColor(t.accent),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .focusRequester(focus)
                        .onFocusChanged { onLinkFocus(it.isFocused) }
                        .testTag("link")
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && event.key == Key.Enter) {
                                onSubmit()
                                true
                            } else {
                                false
                            }
                        },
            )
        }
        CategoryMenu(addCategory, categories, onAddCategory)
        PresetMenu(preset, presets, onPreset)
    }
}

/** Where the next link saves, beside the quality it downloads at. The chosen category's name shows. */
@Composable
private fun CategoryMenu(
    addCategory: String,
    categories: List<CategoryDto>,
    onAddCategory: (String) -> Unit,
) {
    val t = LocalTokens.current
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.clip(RoundedCornerShape(5.dp)).clickable { open = true }.padding(horizontal = 6.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(categories.firstOrNull { it.id == addCategory }?.name ?: "Downloads", fontSize = 12.sp, color = t.secondaryText)
            Icon(Glyphs.ChevronDown, null, Modifier.size(12.dp), tint = t.secondaryText)
        }
        DropdownMenu(open, { open = false }) {
            categories.forEach { category ->
                DropdownMenuItem(text = { Text(category.name) }, onClick = {
                    open = false
                    onAddCategory(category.id)
                })
            }
        }
    }
}

@Composable
private fun PresetMenu(
    preset: String,
    presets: List<Pair<String, String>>,
    onPreset: (String) -> Unit,
) {
    val t = LocalTokens.current
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.clip(RoundedCornerShape(5.dp)).clickable { open = true }.padding(horizontal = 6.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(presets.firstOrNull { it.first == preset }?.second ?: presets.first().second, fontSize = 12.sp, color = t.secondaryText)
            Icon(Glyphs.ChevronDown, null, Modifier.size(12.dp), tint = t.secondaryText)
        }
        DropdownMenu(open, { open = false }) {
            presets.forEach { (value, label) ->
                DropdownMenuItem(text = { Text(label) }, onClick = {
                    open = false
                    onPreset(value)
                })
            }
        }
    }
}
