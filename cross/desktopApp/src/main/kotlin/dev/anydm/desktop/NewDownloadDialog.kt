package dev.anydm.desktop

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The New download dialogs, laid over the window while any is open: the one on show, with "1 of 3" to move between several.
 * It holds the window's key shortcuts (the paste shortcut must paste into the field, not open another dialog) for as long as
 * it is shown.
 */
@Composable
fun NewDownloadHost(
    host: NewDownloads,
    pickers: Pickers,
    editor: NetworkEditor,
    keys: WindowKeys,
) {
    val stack by host.state.collectAsState()
    val entry = stack.current ?: return
    BlockWindowKeys(keys)
    // One dialog at a time on show, each its own state: switching shows another dialog's field and files.
    androidx.compose.runtime.key(entry.id) {
        val state by entry.model.state.collectAsState()
        LaunchedEffect(state.done) { if (state.done) host.close(entry.id) }
        NewDownloadDialog(
            entry.model,
            state,
            pickers,
            editor,
            keys,
            position = stack.shown,
            count = stack.entries.size,
            onShow = host::show,
            onClose = { host.close(entry.id) },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun NewDownloadDialog(
    model: NewDownloadModel,
    state: NewDownloadState,
    pickers: Pickers,
    editor: NetworkEditor,
    keys: WindowKeys,
    position: Int,
    count: Int,
    onShow: (Int) -> Unit,
    onClose: () -> Unit,
) {
    val colors = Plexo.colors
    val scope = rememberCoroutineScope()
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            // The window behind is out of reach: a click on the scrim goes nowhere (the dialog is closed by its own buttons).
            .pointerInput(Unit) { detectTapGestures { } }
            .onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) {
                    onClose()
                    true
                } else {
                    false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(560.dp)
                .heightIn(max = 620.dp)
                .clip(shape)
                .background(colors.bgSecondary)
                .border(0.5.dp, colors.border, shape)
                .pointerInput(Unit) { detectTapGestures { } }
                .semantics { contentDescription = "New download" },
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Txt("New download", Plexo.type.title, Modifier.weight(1f).padding(vertical = 4.dp))
                if (count > 1) {
                    IconButton(Glyph.CHEVRON_LEFT, "Previous download", { if (position > 0) onShow(position - 1) })
                    Txt("${position + 1} of $count", Plexo.type.detail, Modifier.width(56.dp), color = colors.textSecondary)
                    IconButton(Glyph.CHEVRON_RIGHT, "Next download", { if (position < count - 1) onShow(position + 1) })
                }
            }
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(colors.border))
            Column(
                Modifier.weight(1f, fill = false).padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    LinkField(state, model, Modifier.weight(1f))
                    PButton(
                        "Open .torrent",
                        {
                            scope.launch {
                                val chosen = withContext(Dispatchers.IO) { pickers.torrentFile() }
                                if (chosen != null) model.fill(chosen.toString())
                            }
                        },
                        glyph = Glyph.FOLDER,
                        modifier = Modifier.height(36.dp),
                    )
                }
                when (val link = state.link) {
                    LinkState.Checking -> {
                        Txt("Checking the link…", Plexo.type.detail, color = colors.textSecondary)
                    }

                    is LinkState.Failed -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            GlyphIcon(Glyph.ALERT, colors.danger, size = 16.dp)
                            Txt(link.message, Plexo.type.hint, color = colors.danger, maxLines = 3)
                        }
                    }

                    is LinkState.Ready -> {
                        ReadyRow(state, link, model)
                    }

                    LinkState.Idle -> {
                        // Nothing to say before there is a link.
                    }
                }
                if (state.files.isNotEmpty()) FileTree(state, model)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    FormLabel("Save to")
                    Txt(state.saveFolderShown, Plexo.type.speed.copy(fontWeight = null), Modifier.weight(1f))
                    PButton("Change", {
                        scope.launch {
                            val chosen = withContext(Dispatchers.IO) { pickers.folder() }
                            if (chosen != null) model.setSaveFolder(chosen.toString())
                        }
                    })
                }
                if (state.chips.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        FormLabel("Networks", Modifier.padding(top = 6.dp))
                        FlowRow(
                            Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            for (chip in state.chips) Chip(chip, editor, keys)
                        }
                    }
                    state.networksNote?.let { Txt(it, Plexo.type.detail, color = colors.textTertiary, maxLines = 2) }
                }
                state.connectionNote?.let { Txt(it, Plexo.type.hint, color = colors.textSecondary, maxLines = 2) }
                state.subnetWarning?.let { Warning(it) }
                if (state.duplicate && state.error == null) Warning("This is already in your list or was downloaded before.")
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(colors.bgSecondary)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) { state.error?.let { Txt(it, Plexo.type.hint, color = colors.danger, maxLines = 2) } }
                PButton("Cancel", onClose)
                PButton(state.startLabel, model::start, kind = ButtonKind.PRIMARY, enabled = state.canStart)
            }
        }
    }
}

@Composable
private fun FormLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    Txt(text, Plexo.type.hint, modifier.width(64.dp), color = Plexo.colors.textSecondary)
}

@Composable
private fun Warning(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        GlyphIcon(Glyph.ALERT, Plexo.colors.usb.text, Modifier.padding(top = 2.dp), size = 14.dp)
        Txt(text, Plexo.type.hint, color = Plexo.colors.usb.text, maxLines = 3)
    }
}

// The link, with a clear button once there is one; Enter starts the download.
@Composable
private fun LinkField(
    state: NewDownloadState,
    model: NewDownloadModel,
    modifier: Modifier,
) {
    val colors = Plexo.colors
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val shape = RoundedCornerShape(9.dp)
    val failed = state.link is LinkState.Failed
    Row(
        modifier
            .height(36.dp)
            .clip(shape)
            .background(colors.inputBg)
            .border(0.5.dp, if (failed) colors.danger else colors.borderStrong, shape)
            .padding(start = 12.dp, end = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (state.url.isEmpty()) {
                Txt(
                    "Paste a link: https:// or magnet:",
                    Plexo.type.speed.copy(fontWeight = null),
                    color = colors.textTertiary,
                )
            }
            BasicTextField(
                value = state.url,
                onValueChange = model::setUrl,
                singleLine = true,
                textStyle = Plexo.type.speed.copy(color = colors.text, fontWeight = null),
                cursorBrush = SolidColor(colors.accent),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .focusRequester(focus)
                        .semantics { contentDescription = "Link" }
                        .onPreviewKeyEvent {
                            if (it.type == KeyEventType.KeyDown && it.key == Key.Enter) {
                                model.start()
                                true
                            } else {
                                false
                            }
                        },
            )
        }
        if (state.url.isNotEmpty()) {
            IconButton(Glyph.CLOSE, "Clear link", {
                model.clear()
                runCatching { focus.requestFocus() }
            }, size = 24.dp, glyphSize = 14.dp)
        }
    }
}

// What the link turned out to be: a badge, the name (editable for a file) and its size and how it will be fetched.
@Composable
private fun ReadyRow(
    state: NewDownloadState,
    ready: LinkState.Ready,
    model: NewDownloadModel,
) {
    val colors = Plexo.colors
    val torrent = ready.torrent
    val badge =
        if (torrent != null && torrent.files.size > 1) {
            "DIR"
        } else if (torrent != null) {
            "TORRENT"
        } else {
            extensionBadge(state.fileName)
        }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(colors.bgTertiary)
                .border(0.5.dp, colors.border, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) { Txt(badge, Plexo.type.badge, color = colors.textSecondary) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (torrent != null) {
                Txt(ready.name, Plexo.type.rowName)
            } else {
                BasicTextField(
                    value = state.fileName,
                    onValueChange = model::setFileName,
                    singleLine = true,
                    textStyle = Plexo.type.rowName.copy(color = colors.text),
                    cursorBrush = SolidColor(colors.accent),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "File name" },
                )
            }
            val size = state.selectedSize?.let { formatBytes(it) } ?: "size unknown"
            val meta = listOfNotNull(torrent?.let { fileCount(state.selectedCount, it.files.size) }, size).joinToString(" · ")
            Txt(meta, Plexo.type.detail, color = colors.textSecondary)
        }
    }
}

private fun fileCount(
    chosen: Int,
    total: Int,
): String = if (chosen == total) (if (total == 1) "1 file" else "$total files") else "$chosen of $total files"

private fun extensionBadge(name: String): String =
    name.substringAfterLast('.', "").takeIf { it.length in 1..4 && it.all(Char::isLetterOrDigit) }?.uppercase() ?: "FILE"

// A torrent's files, each ticked to be downloaded; the folder they come in ticks all of them.
@Composable
private fun FileTree(
    state: NewDownloadState,
    model: NewDownloadModel,
) {
    val colors = Plexo.colors
    val shape = RoundedCornerShape(9.dp)
    LazyColumn(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 176.dp)
            .clip(shape)
            .border(0.5.dp, colors.border, shape)
            .semantics { contentDescription = "Files" },
        contentPadding =
            androidx.compose.foundation.layout
                .PaddingValues(horizontal = 12.dp, vertical = 6.dp),
    ) {
        state.folder?.let { folder ->
            item(key = "folder") {
                Row(
                    Modifier.padding(vertical = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PCheckbox(
                        checked = state.selectedCount == state.files.size,
                        indeterminate = state.selectedCount in 1 until state.files.size,
                        onChange = { model.toggleAllFiles() },
                        label = "All files",
                    )
                    GlyphIcon(Glyph.FOLDER, colors.textTertiary, size = 14.dp)
                    Txt(folder, Plexo.type.detail.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium), Modifier.weight(1f))
                }
            }
        }
        items(state.files, key = { it.index }) { file ->
            Row(
                Modifier.padding(vertical = 3.dp).padding(start = if (state.folder != null) 24.dp else 0.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PCheckbox(file.selected, { model.toggleFile(file.index) }, "Include ${file.shown}")
                Txt(file.shown, Plexo.type.detail, Modifier.weight(1f))
                Txt(formatBytes(file.size), Plexo.type.detail.copy(textAlign = TextAlign.End), color = colors.textTertiary)
            }
        }
    }
}

// A network the download would use (or not), in its own color, with a pencil to rename and recolor it.
@Composable
private fun Chip(
    chip: NetworkChip,
    editor: NetworkEditor,
    keys: WindowKeys,
) {
    val colors = Plexo.colors
    val solid = LocalNetworkLooks.current.swatches(colors)[chip.key]?.solid ?: colors.accent
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .height(28.dp)
            .clip(shape)
            .background(if (chip.on) solid.copy(alpha = 0.16f) else Color.Transparent)
            .border(0.5.dp, if (chip.on) solid.copy(alpha = 0.42f) else colors.border, shape)
            .semantics { contentDescription = "${chip.name}, ${if (chip.on) "in use" else "not in use"}" }
            .padding(start = 10.dp, end = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(solid))
        Txt(chip.name, Plexo.type.hint, Modifier.widthIn(max = 160.dp), color = if (chip.on) colors.text else colors.textSecondary)
        NetworkEditPencil(editor, "chip", chip.key, chip.name, chip.name, keys, size = 20.dp, glyphSize = 12.dp)
    }
}
