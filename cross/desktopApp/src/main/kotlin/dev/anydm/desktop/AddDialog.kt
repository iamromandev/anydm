package dev.anydm.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.files.Category

/** The add dialog over the window: links in, a preview of each, the choices for all of them, and Add. */
@Composable
internal fun AddDialog(
    add: AddModel,
    categories: List<Category>,
    pickers: Pickers,
    onClose: () -> Unit,
    onShow: (DownloadId) -> Unit,
) {
    val state by add.state.collectAsState()
    val linksField = remember { FocusRequester() }
    LaunchedEffect(Unit) { linksField.requestFocus() }
    Box(
        // The pointer handler keeps clicks from reaching the window underneath.
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.85f))
            .pointerInput(Unit) {}
            .testTag("add-dialog"),
        contentAlignment = Alignment.Center,
    ) {
        Card(Modifier.width(760.dp).padding(16.dp)) {
            Column(
                Modifier.padding(20.dp).heightIn(max = 680.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Add downloads", style = MaterialTheme.typography.headlineSmall)
                OutlinedTextField(
                    value = state.text,
                    onValueChange = add::setText,
                    label = { Text("Links, one per line; [1-10] makes a batch") },
                    minLines = 3,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth().focusRequester(linksField).testTag("add-text"),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = {
                            pickers.torrentFile()?.let { path ->
                                add.setText(listOf(state.text, path.toUri().toString()).filter { it.isNotBlank() }.joinToString("\n"))
                            }
                        },
                        modifier = Modifier.testTag("add-open-torrent"),
                    ) { Text("Open .torrent…") }
                    Text("Web links as:", style = MaterialTheme.typography.labelLarge)
                    Chip("File", state.options.mode == AddMode.FILE, "add-mode-file") { add.setOptions { it.copy(mode = AddMode.FILE) } }
                    Chip(
                        "Media",
                        state.options.mode == AddMode.MEDIA,
                        "add-mode-media",
                    ) { add.setOptions { it.copy(mode = AddMode.MEDIA) } }
                }
                Preview(state, add, onShow)
                state.torrent?.let { TorrentFiles(it, add) }
                HorizontalDivider()
                Placement(state, categories, add, pickers)
                if (state.options.mode == AddMode.MEDIA) MediaOptions(state, add)
                if (state.needsLogin) Login(state, add, pickers)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    TextButton(onClick = onClose, modifier = Modifier.testTag("add-cancel")) { Text("Cancel") }
                    Button(onClick = add::add, enabled = state.canAdd, modifier = Modifier.testTag("add-submit")) {
                        val count = state.rows.count { it.addable }
                        Text(if (count > 1) "Add $count" else "Add")
                    }
                }
            }
        }
    }
}

@Composable
private fun Chip(
    label: String,
    selected: Boolean,
    tag: String,
    onClick: () -> Unit,
) = FilterChip(selected = selected, onClick = onClick, label = { Text(label) }, modifier = Modifier.testTag(tag))

@Composable
private fun Preview(
    state: AddState,
    add: AddModel,
    onShow: (DownloadId) -> Unit,
) {
    if (state.rows.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        state.rows.forEachIndexed { i, row ->
            Row(Modifier.fillMaxWidth().testTag("add-row-$i"), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    statusText(row.status),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (row.status in Problems) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(110.dp).testTag("add-status-$i"),
                )
                Column(Modifier.weight(1f)) {
                    Text(row.name ?: withoutLogin(row.text), maxLines = 1)
                    val detail = listOfNotNull(row.kind?.let(::kindText), row.size?.let(::formatBytes), row.message).joinToString(" · ")
                    if (detail.isNotEmpty()) {
                        Text(
                            detail,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.testTag("add-detail-$i"),
                        )
                    }
                }
                if (row.status == RowStatus.DUPLICATE) {
                    row.existing?.let { id ->
                        TextButton(onClick = { onShow(id) }, modifier = Modifier.testTag("add-show-$i")) { Text("Show") }
                    }
                    Checkbox(row.addAnyway, { add.setAddAnyway(row.text, it) }, Modifier.testTag("add-anyway-$i"))
                    Text("Add anyway", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

private val Problems =
    setOf(RowStatus.NOT_A_LINK, RowStatus.INVALID, RowStatus.FAILED, RowStatus.UNAVAILABLE, RowStatus.ADD_FAILED, RowStatus.DUPLICATE)

internal fun statusText(status: RowStatus): String =
    when (status) {
        RowStatus.CHECKING -> "Checking…"
        RowStatus.READY -> "New"
        RowStatus.MEDIA -> "Media"
        RowStatus.DUPLICATE -> "Duplicate"
        RowStatus.NOT_A_LINK -> "Not a link"
        RowStatus.INVALID -> "Bad pattern"
        RowStatus.FAILED -> "Failed"
        RowStatus.UNAVAILABLE -> "Unavailable"
        RowStatus.ADDED -> "Added"
        RowStatus.ADD_FAILED -> "Not added"
    }

private fun kindText(kind: dev.anydm.engine.core.DownloadKind): String =
    when (kind) {
        dev.anydm.engine.core.DownloadKind.DIRECT -> "File"
        dev.anydm.engine.core.DownloadKind.SITE -> "Media"
        dev.anydm.engine.core.DownloadKind.TORRENT -> "Torrent"
        dev.anydm.engine.core.DownloadKind.FTP -> "FTP"
        else -> kind.name.lowercase()
    }

@Composable
private fun TorrentFiles(
    pick: TorrentPick,
    add: AddModel,
) {
    Column(Modifier.testTag("add-torrent")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Files in ${pick.info.name}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Checkbox(pick.sequential, add::setSequential, Modifier.testTag("add-sequential"))
            Text("In order (for playing while it downloads)", style = MaterialTheme.typography.labelMedium)
        }
        pick.info.files.forEach { file ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(file.path in pick.selected, { add.toggleTorrentFile(file.path) }, Modifier.testTag("add-file-${file.index}"))
                Text("${file.path} — ${formatBytes(file.size)}", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (pick.selected.isEmpty()) Error("Choose at least one file.", "add-error-files")
    }
}

@Composable
private fun Error(
    text: String,
    tag: String,
) = Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall, modifier = Modifier.testTag(tag))

@Composable
private fun ColumnScope.FieldError(
    state: AddState,
    field: Field,
) {
    state.errors[field]?.let { Error(it, "add-error-${field.name.lowercase()}") }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Placement(
    state: AddState,
    categories: List<Category>,
    add: AddModel,
    pickers: Pickers,
) {
    val options = state.options
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Picker(
                "Category",
                categories.firstOrNull { it.id == options.categoryId }?.name ?: "None",
                listOf<Pair<String, () -> Unit>>("None" to { add.setOptions { it.copy(categoryId = null) } }) +
                    categories.map { c -> c.name to { add.setOptions { it.copy(categoryId = c.id) } } },
                "add-category",
            )
            Picker(
                "Profile",
                state.profiles.firstOrNull { it.id == options.profileId }?.name ?: "Default",
                listOf<Pair<String, () -> Unit>>("Default" to { add.setOptions { it.copy(profileId = null) } }) +
                    state.profiles.map { p -> p.name to { add.setOptions { it.copy(profileId = p.id) } } },
                "add-profile",
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = options.folder,
                onValueChange = { v -> add.setOptions { it.copy(folder = v) } },
                label = { Text("Save in (empty: the category's or Downloads)") },
                singleLine = true,
                modifier = Modifier.weight(1f).testTag("add-folder"),
            )
            OutlinedButton(onClick = {
                pickers.folder()?.let { p ->
                    add.setOptions { it.copy(folder = p.toString()) }
                }
            }) { Text("Choose…") }
        }
        FieldError(state, Field.FOLDER)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Start:", style = MaterialTheme.typography.labelLarge)
            StartChoice.entries.forEach { choice ->
                Chip(
                    choice.label,
                    options.start == choice,
                    "add-start-${choice.name.lowercase()}",
                ) { add.setOptions { it.copy(start = choice) } }
            }
            if (options.start == StartChoice.AT) {
                OutlinedTextField(
                    value = options.startAt,
                    onValueChange = { v -> add.setOptions { it.copy(startAt = v) } },
                    placeholder = { Text("2026-10-31 08:00") },
                    singleLine = true,
                    modifier = Modifier.width(200.dp).testTag("add-start-time"),
                )
            }
        }
        FieldError(state, Field.START_AT)
    }
}

@Composable
private fun Picker(
    label: String,
    current: String,
    choices: List<Pair<String, () -> Unit>>,
    tag: String,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.testTag(tag)) { Text("$label: $current") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            choices.forEachIndexed { i, (name, pick) ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = {
                        open = false
                        pick()
                    },
                    modifier = Modifier.testTag("$tag-$i"),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MediaOptions(
    state: AddState,
    add: AddModel,
) {
    val options = state.options
    Column(Modifier.testTag("add-media"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Media", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MediaPreset.entries.forEach { preset ->
                Chip(
                    preset.label,
                    options.preset == preset,
                    "add-preset-${preset.name.lowercase()}",
                ) { add.setOptions { it.copy(preset = preset) } }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = options.subtitles,
                onValueChange = { v -> add.setOptions { it.copy(subtitles = v) } },
                label = { Text("Subtitles (en, de…)") },
                singleLine = true,
                modifier = Modifier.width(220.dp).testTag("add-sub-langs"),
            )
            Checkbox(options.autoSubtitles, { on -> add.setOptions { it.copy(autoSubtitles = on) } }, Modifier.testTag("add-sub-auto"))
            Text("Include auto-generated", style = MaterialTheme.typography.labelMedium)
        }
        FieldError(state, Field.SUBTITLES)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("SponsorBlock:", style = MaterialTheme.typography.labelLarge)
            SponsorChoice.entries.forEach { choice ->
                Chip(
                    choice.label,
                    options.sponsor == choice,
                    "add-sponsor-${choice.name.lowercase()}",
                ) { add.setOptions { it.copy(sponsor = choice) } }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(options.playlist, { on -> add.setOptions { it.copy(playlist = on) } }, Modifier.testTag("add-playlist"))
            Text("Playlist or channel", style = MaterialTheme.typography.labelMedium)
            if (options.playlist) {
                OutlinedTextField(
                    value = options.range,
                    onValueChange = { v -> add.setOptions { it.copy(range = v) } },
                    label = { Text("Items (1:5,8)") },
                    singleLine = true,
                    modifier = Modifier.width(160.dp).testTag("add-range"),
                )
                OutlinedTextField(
                    value = options.limit,
                    onValueChange = { v -> add.setOptions { it.copy(limit = v) } },
                    label = { Text("At most") },
                    singleLine = true,
                    modifier = Modifier.width(110.dp).testTag("add-limit"),
                )
            }
        }
        FieldError(state, Field.RANGE)
        FieldError(state, Field.LIMIT)
    }
}

@Composable
private fun Login(
    state: AddState,
    add: AddModel,
    pickers: Pickers,
) {
    val options = state.options
    Column(Modifier.testTag("add-login"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Login for FTP and SFTP (kept in the system keychain)", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = options.user,
                onValueChange = { v -> add.setOptions { it.copy(user = v) } },
                label = { Text("User") },
                singleLine = true,
                modifier = Modifier.width(200.dp).testTag("add-user"),
            )
            OutlinedTextField(
                value = options.password,
                onValueChange = { v -> add.setOptions { it.copy(password = v) } },
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.width(200.dp).testTag("add-password"),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = options.keyPath,
                onValueChange = { v -> add.setOptions { it.copy(keyPath = v) } },
                label = { Text("SFTP key file (instead of a password)") },
                singleLine = true,
                modifier = Modifier.weight(1f).testTag("add-key"),
            )
            OutlinedButton(onClick = {
                pickers.keyFile()?.let { p ->
                    add.setOptions { it.copy(keyPath = p.toString()) }
                }
            }) { Text("Choose…") }
            if (options.keyPath.isNotBlank()) {
                OutlinedTextField(
                    value = options.passphrase,
                    onValueChange = { v -> add.setOptions { it.copy(passphrase = v) } },
                    label = { Text("Passphrase") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.width(180.dp).testTag("add-passphrase"),
                )
            }
        }
    }
}
