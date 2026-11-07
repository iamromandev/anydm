package dev.anydm.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val streamOptions = listOf("auto" to "Auto", "4" to "4", "8" to "8", "16" to "16", "32" to "32")

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun NewDownloadDialog(
    initialText: String,
    networkNames: List<String>,
    onCancel: () -> Unit,
    onDownload: (Preview, Set<String>) -> Unit,
) {
    val c = colors
    var text by remember { mutableStateOf(initialText) }
    val preview = parsePreview(text)
    var ticked by remember(preview) { mutableStateOf(sintelFiles.map { it.name }.toSet()) }
    var streams by remember { mutableStateOf("auto") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val valid = preview != null && (preview !is Preview.Torrent || ticked.isNotEmpty())

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            // Keyboard modality: Tab cannot leave the dialog for the Home controls under the scrim.
            .focusProperties { exit = { FocusRequester.Cancel } }
            .focusGroup()
            .background(Color.Black.copy(alpha = if (c.dark) 0.6f else 0.4f))
            .pointerInput(Unit) { detectTapGestures {} }
            .onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) {
                    onCancel()
                    true
                } else {
                    false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .testTag("new-download-dialog")
                .padding(24.dp)
                .widthIn(max = 560.dp)
                .heightIn(max = maxHeight - 48.dp)
                .fillMaxWidth()
                .background(c.surface, RoundedCornerShape(Radius))
                .border(1.dp, c.border, RoundedCornerShape(Radius))
                .pointerInput(Unit) { detectTapGestures {} },
        ) {
            Text(
                "New download",
                color = c.text,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.testTag("dialog-title").padding(horizontal = 20.dp, vertical = 16.dp),
            )
            Divider()
            Column(Modifier.weight(1f, fill = false).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                InputRow(text, { text = it }, focus)
                if (preview != null) {
                    PreviewBlock(preview, ticked)
                    if (preview is Preview.Torrent) {
                        TorrentTree(preview, ticked, Modifier.weight(1f, fill = false)) { ticked = it }
                    }
                }
                OptionRow("Save to") {
                    Text("~/Downloads", color = c.text, fontSize = 13.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
                    Spacer(Modifier.weight(1f))
                    OutlineButton("Change", "save-change")
                }
                OptionRow("Networks") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        networkNames.forEachIndexed { i, n -> NetworkPill(n, if (i == 0) c.success else c.accent) }
                    }
                }
                if (networkNames.size >= 2 && preview?.parallel == true) {
                    OptionRow("Streams") {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            streamOptions.forEach { (key, label) -> StreamButton(key, label, streams == key) { streams = key } }
                            Text(
                                "per network",
                                color = c.textSecondary,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                modifier = Modifier.padding(start = 6.dp),
                            )
                        }
                    }
                }
            }
            Divider()
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
            ) {
                OutlineButton("Cancel", "cancel", onCancel)
                val shape = RoundedCornerShape(Radius)
                Box(
                    Modifier
                        .testTag("download")
                        .height(36.dp)
                        .background(if (valid) c.accent else c.track, shape)
                        .clickable(enabled = valid, role = Role.Button) { if (preview != null) onDownload(preview, ticked) }
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Download",
                        color = if (valid) c.onAccent else c.textSecondary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun Divider() = Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))

@Composable
private fun InputRow(
    text: String,
    onText: (String) -> Unit,
    focus: FocusRequester,
) {
    val c = colors
    val shape = RoundedCornerShape(Radius)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier
                .weight(1f)
                .height(40.dp)
                .border(1.dp, c.border, shape)
                .padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (text.isEmpty()) {
                    Text(
                        "Paste a link or magnet",
                        color = c.textSecondary,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                    )
                }
                BasicTextField(
                    text,
                    onText,
                    singleLine = true,
                    textStyle = TextStyle(color = c.text, fontSize = 13.sp, fontFamily = FontFamily.Monospace),
                    cursorBrush = SolidColor(c.text),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("url-field"),
                )
            }
            if (text.isNotEmpty()) {
                Box(
                    Modifier
                        .testTag("url-clear")
                        .size(32.dp)
                        .clickable(role = Role.Button) { onText("") },
                    contentAlignment = Alignment.Center,
                ) { CloseIcon(c.textSecondary) }
            }
        }
        OutlineButton("Open .torrent", "open-torrent", icon = true)
    }
}

@Composable
private fun OutlineButton(
    label: String,
    tag: String,
    onClick: () -> Unit = {},
    icon: Boolean = false,
) {
    val c = colors
    val shape = RoundedCornerShape(Radius)
    Row(
        Modifier
            .testTag(tag)
            .height(if (icon) 40.dp else 36.dp)
            .border(1.dp, c.border, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (icon) FolderIcon(c.text)
        Text(label, color = c.text, fontSize = 14.sp, maxLines = 1)
    }
}

@Composable
private fun PreviewBlock(
    preview: Preview,
    ticked: Set<String>,
) {
    val c = colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(44.dp)
                .background(c.bg, RoundedCornerShape(Radius))
                .border(1.dp, c.border, RoundedCornerShape(Radius)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                preview.badge,
                color = c.textSecondary,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                modifier = Modifier.testTag("preview-badge"),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                preview.name,
                color = c.text,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("preview-name"),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                previewDetail(preview, ticked),
                color = c.textSecondary,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.testTag("preview-detail"),
            )
        }
    }
}

@Composable
private fun TorrentTree(
    preview: Preview.Torrent,
    ticked: Set<String>,
    modifier: Modifier,
    onTicked: (Set<String>) -> Unit,
) {
    val c = colors
    val shape = RoundedCornerShape(Radius)
    val allOn = preview.files.all { it.name in ticked }
    LazyColumn(
        modifier
            .fillMaxWidth()
            .heightIn(min = 80.dp, max = 170.dp)
            .border(1.dp, c.border, shape)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        item(key = "folder") {
            Row(Modifier.height(28.dp), verticalAlignment = Alignment.CenterVertically) {
                CheckBox(allOn, {
                    onTicked(
                        if (it) {
                            preview.files
                                .map { f ->
                                    f.name
                                }.toSet()
                        } else {
                            emptySet()
                        },
                    )
                }, "folder-toggle", "Select all files")
                Spacer(Modifier.width(8.dp))
                FolderIcon(c.textSecondary)
                Spacer(Modifier.width(8.dp))
                Text(
                    preview.name,
                    color = c.text,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
            }
        }
        items(preview.files, key = { it.name }) { f ->
            Row(Modifier.height(28.dp).padding(start = 26.dp), verticalAlignment = Alignment.CenterVertically) {
                CheckBox(
                    f.name in ticked,
                    { on -> onTicked(if (on) ticked + f.name else ticked - f.name) },
                    "file-${f.name}",
                    "Select ${f.name}",
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    f.name,
                    color = c.text,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(formatBytes(f.bytes), color = c.textSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
            }
        }
    }
}

@Composable
private fun OptionRow(
    label: String,
    content: @Composable RowScope.() -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = colors.textSecondary, fontSize = 13.sp, maxLines = 1, modifier = Modifier.width(76.dp))
        content()
    }
}

@Composable
private fun NetworkPill(
    name: String,
    dot: Color,
) {
    val c = colors
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .testTag("network-$name")
            .height(30.dp)
            .background(dot.copy(alpha = 0.12f), shape)
            .border(1.dp, dot.copy(alpha = 0.5f), shape)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(7.dp).background(dot, CircleShape))
        Text(name, color = c.text, fontSize = 13.sp, maxLines = 1)
    }
}

@Composable
private fun StreamButton(
    key: String,
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    val c = colors
    val shape = RoundedCornerShape(6.dp)
    Box(
        Modifier
            .testTag("streams-$key")
            .height(26.dp)
            .background(if (selected) c.accent else c.track, shape)
            .selectable(selected, role = Role.RadioButton, onClick = onSelect)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (selected) c.onAccent else c.textSecondary,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}
