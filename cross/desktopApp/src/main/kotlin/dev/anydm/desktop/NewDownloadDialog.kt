package dev.anydm.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val streamOptions = listOf("auto" to "Auto", "4" to "4", "8" to "8", "16" to "16", "32" to "32")

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun NewDownloadDialog(
    initialText: String,
    networks: List<Network>,
    onCancel: () -> Unit,
    onDownload: (Preview, Set<String>) -> Unit,
    onProxySave: (String, Proxy) -> Unit = { _, _ -> },
    startsAfter: Int? = null,
) {
    val c = colors
    var text by remember { mutableStateOf(initialText) }
    val preview = parsePreview(text)
    var ticked by remember(preview) { mutableStateOf(sintelFiles.map { it.name }.toSet()) }
    var streams by remember { mutableStateOf("auto") }
    val focus = remember { FocusRequester() }
    var proxyFor by remember { mutableStateOf<String?>(null) }
    val chips = remember { mutableStateMapOf<String, Rect>() }
    // Back to the link field whenever no popover is open (including at first show).
    LaunchedEffect(proxyFor) { if (proxyFor == null) focus.requestFocus() }
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
                    // The preview pass reaches this handler before the popover's, so it must yield to the popover.
                    if (proxyFor != null) proxyFor = null else onCancel()
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
                    if (startsAfter != null) {
                        Text(
                            startsAfterText(startsAfter),
                            color = c.textSecondary,
                            fontSize = 13.sp,
                            maxLines = 1,
                            modifier = Modifier.testTag("queue-hint"),
                        )
                    }
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
                        networks.forEach { n ->
                            NetworkPill(n, networkColor(n.kind, n.color, c), { chips[n.id] = it }) { proxyFor = n.id }
                        }
                    }
                }
                if (networks.size >= 2 && preview?.parallel == true) {
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
                PrimaryButton("Download", "download", valid) { if (preview != null) onDownload(preview, ticked) }
            }
        }
        val target = proxyFor?.let { id -> networks.firstOrNull { it.id == id } }
        val anchor = proxyFor?.let { chips[it] }
        if (target != null && anchor != null) {
            ProxyPopover(anchor, onClose = { proxyFor = null }) {
                Text("Proxy for ${target.name}", color = c.text, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                key(target.id) {
                    ProxyEditor(
                        target.proxy,
                        onSave = {
                            onProxySave(target.id, it)
                            proxyFor = null
                        },
                        onCancel = { proxyFor = null },
                        tagPrefix = "chip-${target.id}",
                    )
                }
            }
        }
    }
}

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
    n: Network,
    dot: Color,
    onChipBounds: (Rect) -> Unit,
    onProxy: () -> Unit,
) {
    val c = colors
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .testTag("network-${n.name}")
            .height(30.dp)
            .background(dot.copy(alpha = 0.12f), shape)
            .border(1.dp, dot.copy(alpha = 0.5f), shape)
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(7.dp).networkDot("pill-dot-${n.id}", n.color).background(dot, CircleShape))
        Text(n.name, color = c.text, fontSize = 13.sp, maxLines = 1)
        val on = n.proxy.type != ProxyType.None
        Box(
            Modifier
                .testTag("proxy-chip-${n.id}")
                .onGloballyPositioned { onChipBounds(it.boundsInRoot()) }
                .height(22.dp)
                .background(c.track, RoundedCornerShape(50))
                .clickable(role = Role.Button, onClick = onProxy)
                .semantics { contentDescription = "Proxy settings for ${n.name}" }
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (on) "Proxy on" else "\u270E Proxy",
                color = if (on) c.accent else c.textSecondary,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
            )
        }
    }
}

private val PopoverWidth = 340.dp
private val PopoverGap = 6.dp
private val PopoverMargin = 12.dp

/**
 * Anchored popover: left-aligned with [anchor], below it, or above when it does not fit below. It never overlaps the
 * anchor; when neither side fits, it takes the larger side and scrolls. A tap anywhere outside it calls [onClose].
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ProxyPopover(
    anchor: Rect,
    onClose: () -> Unit,
    content: @Composable () -> Unit,
) {
    val c = colors
    val shape = RoundedCornerShape(Radius)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Layout(
        {
            Column(
                Modifier
                    .testTag("proxy-popover")
                    .fillMaxWidth()
                    .background(c.surface, shape)
                    .border(1.dp, c.border, shape)
                    .pointerInput(Unit) { detectTapGestures {} }
                    .focusRequester(focus)
                    .focusProperties { exit = { FocusRequester.Cancel } }
                    .focusGroup()
                    .focusable()
                    .verticalScroll(rememberScrollState())
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) { content() }
        },
        Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { onClose() } },
    ) { measurables, constraints ->
        val gap = PopoverGap.roundToPx()
        val margin = PopoverMargin.roundToPx()
        val width = minOf(PopoverWidth.roundToPx(), constraints.maxWidth - 2 * margin).coerceAtLeast(0)
        val below = (constraints.maxHeight - anchor.bottom.toInt() - gap - margin).coerceAtLeast(0)
        val above = (anchor.top.toInt() - gap - margin).coerceAtLeast(0)

        fun measure(maxHeight: Int) = measurables.single().measure(Constraints(width, width, 0, maxHeight))
        var p = measure((constraints.maxHeight - 2 * margin).coerceAtLeast(0))
        val under =
            when {
                p.height <= below -> true
                p.height <= above -> false
                else -> below >= above
            }
        if (p.height > (if (under) below else above)) p = measure(if (under) below else above)
        val y = if (under) anchor.bottom.toInt() + gap else anchor.top.toInt() - gap - p.height
        val x = anchor.left.toInt().coerceIn(margin, (constraints.maxWidth - margin - p.width).coerceAtLeast(margin))
        layout(constraints.maxWidth, constraints.maxHeight) { p.place(x, y) }
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
