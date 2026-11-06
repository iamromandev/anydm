package dev.anydm.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.anydm.engine.core.DownloadKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The form of a new download: the link field with "Open .torrent", what the link turned out to be, a torrent's files, the folder,
 * the networks (each switchable, with a link to the limits) and the warnings. [onFieldFocus] tells whether a text field of the form (the link or the file name) has the
 * focus (the paste shortcut is the field's own while one has).
 */
@Composable
fun NewDownloadForm(
    model: NewDownloadModel,
    state: NewDownloadState,
    pickers: Pickers,
    editor: NetworkEditor,
    keys: WindowKeys,
    modifier: Modifier = Modifier,
    onFieldFocus: (Boolean) -> Unit = {},
    unit: SpeedUnit = SpeedUnit.BYTES,
    onLimits: () -> Unit = {},
) {
    val colors = Plexo.colors
    val scope = rememberCoroutineScope()
    // Either text field having the focus counts; the name field goes with its row, so its focus is cleared when it does.
    var linkFocused by remember { mutableStateOf(false) }
    var nameFocused by remember { mutableStateOf(false) }
    LaunchedEffect(linkFocused, nameFocused) { onFieldFocus(linkFocused || nameFocused) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            LinkField(state, model, Modifier.weight(1f)) { linkFocused = it }
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
                ReadyRow(state, link, model) { nameFocused = it }
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
        if (state.chips.isNotEmpty()) NetworkPicker(state, model, editor, keys, unit, onLimits)
        state.connectionNote?.let { Txt(it, Plexo.type.hint, color = colors.textSecondary, maxLines = 2) }
        state.subnetWarning?.let { Warning(it) }
        if (state.duplicate && state.error == null) Warning("This is already in your list or was downloaded before.")
        state.error?.let { Txt(it, Plexo.type.hint, color = colors.danger, maxLines = 2) }
    }
}

/** What the kind of link is called in the badge. */
internal fun kindLabel(kind: DownloadKind): String =
    when (kind) {
        DownloadKind.DIRECT -> "HTTP file"
        DownloadKind.SITE -> "Site"
        DownloadKind.TORRENT -> "Torrent"
        DownloadKind.FTP -> "FTP file"
        DownloadKind.TOOL -> "Tool"
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
    onFocus: (Boolean) -> Unit,
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
                        .onFocusChanged { onFocus(it.isFocused) }
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
    onFocus: (Boolean) -> Unit,
) {
    val colors = Plexo.colors
    DisposableEffect(Unit) { onDispose { onFocus(false) } }
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
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .onFocusChanged { onFocus(it.isFocused) }
                            .semantics { contentDescription = "File name" },
                )
            }
            val size = state.selectedSize?.let { formatBytes(it) } ?: "size unknown"
            val meta = listOfNotNull(torrent?.let { fileCount(state.selectedCount, it.files.size) }, size).joinToString(" · ")
            Txt(meta, Plexo.type.detail, color = colors.textSecondary)
        }
        Txt(kindLabel(ready.kind), Plexo.type.hint, color = colors.textSecondary)
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

// What a row says under the name: down, switched off, its speed while it carries bytes, else Idle.
internal fun networkStatus(
    chip: NetworkChip,
    unit: SpeedUnit,
): String =
    when {
        !chip.up -> "Not connected"
        !chip.enabled -> "Off"
        else -> chip.speed?.let { formatRate(it, unit) } ?: "Idle"
    }

// Four rows and the three rules between them.
private val PickerRowsMax = 4 * 44 + 3

// The networks, one switchable row each (the engine's switch, for all downloads), with the note, what went wrong, the warning
// when none is on and the link to the limits. A long list scrolls inside its box.
@Composable
private fun NetworkPicker(
    state: NewDownloadState,
    model: NewDownloadModel,
    editor: NetworkEditor,
    keys: WindowKeys,
    unit: SpeedUnit,
    onLimits: () -> Unit,
) {
    val colors = Plexo.colors
    val shape = RoundedCornerShape(10.dp)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Txt("Networks", Plexo.type.hint, color = colors.textSecondary)
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = PickerRowsMax.dp)
                .clip(shape)
                .background(colors.bgSecondary)
                .border(0.5.dp, colors.border, shape)
                .verticalScroll(rememberScrollState()),
        ) {
            state.chips.forEachIndexed { index, chip ->
                if (index > 0) Box(Modifier.fillMaxWidth().height(0.5.dp).background(colors.border))
                NetworkRow(chip, model, editor, keys, unit)
            }
        }
        state.networksNote?.let { Txt(it, Plexo.type.detail, color = colors.textTertiary, maxLines = 2) }
        state.networkError?.let { Txt(it, Plexo.type.hint, color = colors.danger, maxLines = 2) }
        if (state.noNetworkOn) Warning("No network is on. Downloads wait until one is switched on.")
        Txt(
            "Speed & data limits",
            Plexo.type.button,
            Modifier.clickable(role = Role.Button, onClick = onLimits),
            color = colors.accent,
        )
    }
}

@Composable
private fun NetworkRow(
    chip: NetworkChip,
    model: NewDownloadModel,
    editor: NetworkEditor,
    keys: WindowKeys,
    unit: SpeedUnit,
) {
    val colors = Plexo.colors
    val solid = LocalNetworkLooks.current.swatches(colors)[chip.key]?.solid ?: colors.accent
    val tagShape = RoundedCornerShape(4.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .padding(horizontal = 14.dp)
            .semantics { testTag = "network-row-${chip.key}" },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (chip.on) solid else colors.iconMutedStrong))
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Txt(chip.name, Plexo.type.rowName, Modifier.weight(1f, fill = false))
            NetworkEditPencil(editor, "picker", chip.key, chip.name, chip.osName, keys, size = 20.dp, glyphSize = 12.dp)
            Box(
                Modifier
                    .widthIn(max = 110.dp)
                    .clip(tagShape)
                    .background(colors.bgTertiary)
                    .border(0.5.dp, colors.border, tagShape)
                    .padding(horizontal = 6.dp),
            ) {
                Txt("${chip.kind.tag} · ${chip.key}", Plexo.type.badge, color = colors.textTertiary)
            }
        }
        Txt(
            networkStatus(chip, unit),
            Plexo.type.speed.copy(textAlign = TextAlign.End),
            color = if (chip.on && chip.speed != null) solid else colors.textTertiary,
        )
        PSwitch(
            chip.enabled,
            { model.setNetworkEnabled(chip.key, it) },
            "Use ${chip.name}",
            Modifier.semantics { testTag = "network-switch-${chip.key}" },
        )
    }
}
