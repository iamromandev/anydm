package dev.anydm.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.PacingPreset
import dev.anydm.engine.files.ClashPolicy
import dev.anydm.engine.net.ProxyChoice

/** The Settings section (#608): a list of sections on the left, the chosen one on the right. */
@Composable
internal fun SettingsScreen(
    model: SettingsModel,
    panels: SettingsPanels,
    pickers: Pickers,
    onTyping: (Boolean) -> Unit,
) {
    val state by model.state.collectAsState()
    LaunchedEffect(Unit) { model.load() }
    LaunchedEffect(state.section) { panels.load(state.section) }
    Row(Modifier.fillMaxSize().testTag("settings")) {
        Column(
            Modifier
                .width(200.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(8.dp),
        ) {
            Section.entries.forEach { section ->
                TextButton(
                    onClick = { model.select(section) },
                    modifier = Modifier.fillMaxWidth().testTag("settings-section-${section.name.lowercase()}"),
                ) {
                    Text(
                        section.label,
                        modifier = Modifier.fillMaxWidth(),
                        color =
                            if (section == state.section) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(state.section.label, style = MaterialTheme.typography.titleLarge)
                if (!state.loaded) {
                    Text(state.message ?: "Reading the settings…", modifier = Modifier.testTag("settings-unavailable"))
                    return@Column
                }
                val typing = Modifier.onFocusChanged { onTyping(it.isFocused) }
                when (state.section) {
                    Section.FILES -> Files(state, model, pickers, typing)
                    Section.SPEED -> Speed(state, model, typing)
                    Section.QUEUE -> Queue(state, model, typing)
                    Section.CATEGORIES -> Categories(state, model, pickers, typing)
                    Section.PROFILES -> Profiles(state, model, pickers, typing)
                    Section.HISTORY -> History(state, model)
                    Section.BACKUP -> Backup(model, pickers)
                    Section.PROXIES -> ProxiesPanel(panels.proxies, typing)
                    Section.RULES -> RulesPanel(panels.rules, typing)
                    Section.NETWORKS -> NetworksPanel(panels.networks)
                    Section.SYSTEM -> OsPanel(panels.os, panels.window, typing)
                    Section.TOOLS -> ToolsPanel(panels.tools, pickers, typing)
                    Section.DIAGNOSTICS -> DiagnosticsPanel(panels.diagnostics, pickers)
                }
            }
            Footer(state, model)
        }
    }
    state.confirm?.let { Confirm(it, model) }
}

@Composable
private fun Footer(
    state: SettingsState,
    model: SettingsModel,
) {
    if (!state.section.transfer && state.message == null) return
    HorizontalDivider()
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(state.message.orEmpty(), modifier = Modifier.weight(1f).testTag("settings-message"))
        if (state.section.transfer) {
            OutlinedButton(onClick = model::revert, enabled = state.dirty && !state.busy, modifier = Modifier.testTag("settings-revert")) {
                Text("Revert")
            }
            Button(onClick = model::save, enabled = state.canSave, modifier = Modifier.testTag("settings-save")) { Text("Save") }
        }
    }
}

@Composable
private fun Files(
    state: SettingsState,
    model: SettingsModel,
    pickers: Pickers,
    typing: Modifier,
) {
    val form = state.draft
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Field("Default folder (empty: Downloads)", form.folder, "set-folder", state.errors[SettingField.FOLDER], typing) { v ->
            model.edit { it.copy(folder = v) }
        }
        OutlinedButton(onClick = { pickers.folder()?.let { p -> model.edit { it.copy(folder = p.toString()) } } }) { Text("Choose…") }
    }
    if (state.folderMissing && form.folder == state.saved.folder) {
        Text(
            "This folder isn't there now, so downloads go to Downloads until it is back.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("set-folder-missing"),
        )
    }
    Choice("When the name is taken", ClashPolicy.entries, form.clash, "set-clash", ::clashLabel) { v -> model.edit { it.copy(clash = v) } }
    Field("Keep free on the disk", form.reserveMb, "set-reserve", state.errors[SettingField.RESERVE], typing, "MB") { v ->
        model.edit { it.copy(reserveMb = v) }
    }
    Toggle("Keep a playlist file (.m3u8) with each collection", form.playlistFile, "set-playlist") { v ->
        model.edit { it.copy(playlistFile = v) }
    }
    Field("Retries before a download fails", form.retries, "set-retries", state.errors[SettingField.RETRIES], typing) { v ->
        model.edit { it.copy(retries = v) }
    }
    Field("Finished downloads the history keeps", form.historyLimit, "set-history", state.errors[SettingField.HISTORY], typing) { v ->
        model.edit { it.copy(historyLimit = v) }
    }
    Choice("Pace requests to media sites", PacingPreset.entries, form.pacing, "set-pacing", ::pacingLabel) { v ->
        model.edit { it.copy(pacing = v) }
    }
    if (form.pacing == PacingPreset.CUSTOM) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("Between requests", form.pacingSleep, "set-pacing-sleep", null, typing, "s", 180) { v ->
                model.edit { it.copy(pacingSleep = v) }
            }
            Field("Shortest wait", form.pacingMin, "set-pacing-min", null, typing, "s", 180) { v -> model.edit { it.copy(pacingMin = v) } }
            Field("Longest wait", form.pacingMax, "set-pacing-max", null, typing, "s", 180) { v -> model.edit { it.copy(pacingMax = v) } }
        }
        state.errors[SettingField.PACING]?.let { ErrorText(it, "set-pacing-error") }
    }
}

@Composable
private fun Speed(
    state: SettingsState,
    model: SettingsModel,
    typing: Modifier,
) {
    val form = state.draft
    Field("Connections per download", form.connections, "set-connections", state.errors[SettingField.CONNECTIONS], typing) { v ->
        model.edit { it.copy(connections = v) }
    }
    Field("Download limit (empty: none)", form.downKbps, "set-down", state.errors[SettingField.DOWN_LIMIT], typing, "KB/s") { v ->
        model.edit { it.copy(downKbps = v) }
    }
    Field("Upload limit (empty: none)", form.upKbps, "set-up", state.errors[SettingField.UP_LIMIT], typing, "KB/s") { v ->
        model.edit { it.copy(upKbps = v) }
    }
    Toggle("Slow mode", form.slowMode, "set-slow") { v -> model.edit { it.copy(slowMode = v) } }
    Field("Slow mode limit, each way", form.slowKbps, "set-slow-limit", state.errors[SettingField.SLOW_LIMIT], typing, "KB/s") { v ->
        model.edit { it.copy(slowKbps = v) }
    }
    Field(
        "Seed torrents to this ratio (empty: no limit)",
        form.seedRatio,
        "set-seed-ratio",
        state.errors[SettingField.SEED_RATIO],
        typing,
    ) { v ->
        model.edit { it.copy(seedRatio = v) }
    }
    Field(
        "Seed torrents this long (empty: no limit)",
        form.seedMinutes,
        "set-seed-minutes",
        state.errors[SettingField.SEED_MINUTES],
        typing,
        "min",
    ) { v -> model.edit { it.copy(seedMinutes = v) } }
}

@Composable
private fun Queue(
    state: SettingsState,
    model: SettingsModel,
    typing: Modifier,
) {
    val form = state.draft
    Field("Downloads at once", form.concurrency, "set-concurrency", state.errors[SettingField.CONCURRENCY], typing) { v ->
        model.edit { it.copy(concurrency = v) }
    }
    Toggle("Pause everything", form.globalPause, "set-pause") { v -> model.edit { it.copy(globalPause = v) } }
    Toggle("Keep the computer awake while downloading", form.keepAwake, "set-awake") { v -> model.edit { it.copy(keepAwake = v) } }
    Choice("When the queue is done", AfterQueueChoice.entries, form.afterQueue, "set-after", { it.label }) { v ->
        model.edit { it.copy(afterQueue = v) }
    }
    if (form.afterQueue == AfterQueueChoice.QUIT) Text("Only until anydm is next started.", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun Categories(
    state: SettingsState,
    model: SettingsModel,
    pickers: Pickers,
    typing: Modifier,
) {
    Text("A download goes to the first category whose rules it matches.", style = MaterialTheme.typography.bodySmall)
    state.categories.forEachIndexed { index, category ->
        Row(
            Modifier.fillMaxWidth().testTag("category-${category.name}"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(category.name)
                Text(category.folder, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(
                onClick = { model.moveCategory(category, up = true) },
                enabled = index > 0,
                modifier = Modifier.testTag("category-up-$index"),
            ) {
                Text("Up")
            }
            TextButton(
                onClick = { model.moveCategory(category, up = false) },
                enabled = index < state.categories.lastIndex,
                modifier = Modifier.testTag("category-down-$index"),
            ) { Text("Down") }
            TextButton(onClick = { model.editCategory(category) }, modifier = Modifier.testTag("category-edit-$index")) { Text("Edit") }
            TextButton(onClick = { model.askDeleteCategory(category) }, modifier = Modifier.testTag("category-delete-$index")) {
                Text("Delete")
            }
        }
    }
    val editing = state.category
    if (editing == null) {
        OutlinedButton(onClick = model::newCategory, modifier = Modifier.testTag("category-new")) { Text("Add a category") }
        return
    }
    HorizontalDivider()
    Text(if (editing.id == null) "New category" else "Edit category", style = MaterialTheme.typography.titleMedium)
    val form = editing.form
    Field(
        "Name",
        form.name,
        "category-name",
        editing.errors[CategoryField.NAME],
        typing,
    ) { v -> model.changeCategory { it.copy(name = v) } }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Field("Folder", form.folder, "category-folder", editing.errors[CategoryField.FOLDER], typing) { v ->
            model.changeCategory { it.copy(folder = v) }
        }
        OutlinedButton(onClick = { pickers.folder()?.let { p -> model.changeCategory { it.copy(folder = p.toString()) } } }) {
            Text("Choose…")
        }
    }
    Field(
        "Naming template (empty: the file's own name)",
        form.template,
        "category-template",
        editing.errors[CategoryField.TEMPLATE],
        typing,
    ) { v ->
        model.changeCategory { it.copy(template = v) }
    }
    Text("Fields: $templateFields", style = MaterialTheme.typography.bodySmall)
    Field("Extensions, comma-separated", form.extensions, "category-extensions", null, typing) { v ->
        model.changeCategory { it.copy(extensions = v) }
    }
    Field("MIME types, comma-separated (video/*)", form.mimes, "category-mimes", null, typing) { v ->
        model.changeCategory { it.copy(mimes = v) }
    }
    Field("Sites, comma-separated", form.domains, "category-domains", null, typing) { v -> model.changeCategory { it.copy(domains = v) } }
    Text("Kinds", style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DownloadKind.entries.forEach { kind ->
            FilterChip(
                selected = kind in form.kinds,
                onClick = { model.changeCategory { it.copy(kinds = if (kind in it.kinds) it.kinds - kind else it.kinds + kind) } },
                label = { Text(kind.name.lowercase()) },
                modifier = Modifier.testTag("category-kind-${kind.name.lowercase()}"),
            )
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = model::saveCategory,
            enabled = editing.errors.isEmpty(),
            modifier = Modifier.testTag("category-save"),
        ) { Text("Save") }
        TextButton(onClick = model::cancelCategory, modifier = Modifier.testTag("category-cancel")) { Text("Cancel") }
    }
}

@Composable
private fun Profiles(
    state: SettingsState,
    model: SettingsModel,
    pickers: Pickers,
    typing: Modifier,
) {
    Text(
        "A profile is a set of options a download can take; the default one goes to downloads that name none.",
        style = MaterialTheme.typography.bodySmall,
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        FilterChip(
            selected = state.defaultProfile == null,
            onClick = { model.setDefaultProfile(null) },
            label = { Text("No default") },
            modifier = Modifier.testTag("profile-default-none"),
        )
    }
    state.profiles.forEachIndexed { index, profile ->
        Row(
            Modifier.fillMaxWidth().testTag("profile-${profile.name}"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(profile.name, modifier = Modifier.weight(1f))
            FilterChip(
                selected = state.defaultProfile == profile.id,
                onClick = { model.setDefaultProfile(profile.id) },
                label = { Text("Default") },
                modifier = Modifier.testTag("profile-default-$index"),
            )
            TextButton(onClick = { model.editProfile(profile) }, modifier = Modifier.testTag("profile-edit-$index")) { Text("Edit") }
            TextButton(
                onClick = { model.askDeleteProfile(profile) },
                modifier = Modifier.testTag("profile-delete-$index"),
            ) { Text("Delete") }
        }
    }
    val editing = state.profile
    if (editing == null) {
        OutlinedButton(onClick = model::newProfile, modifier = Modifier.testTag("profile-new")) { Text("Add a profile") }
        return
    }
    HorizontalDivider()
    Text(if (editing.id == null) "New profile" else "Edit profile", style = MaterialTheme.typography.titleMedium)
    val form = editing.form
    Field("Name", form.name, "profile-name", editing.errors[ProfileField.NAME], typing) { v -> model.changeProfile { it.copy(name = v) } }
    Text("Category", style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = form.category == null,
            onClick = { model.changeProfile { it.copy(category = null) } },
            label = { Text("None") },
            modifier = Modifier.testTag("profile-category-none"),
        )
        state.categories.forEach { category ->
            FilterChip(
                selected = form.category == category.id,
                onClick = { model.changeProfile { it.copy(category = category.id) } },
                label = { Text(category.name) },
                modifier = Modifier.testTag("profile-category-${category.name}"),
            )
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Field(
            "Folder (empty: the category's or the default)",
            form.folder,
            "profile-folder",
            editing.errors[ProfileField.FOLDER],
            typing,
        ) { v ->
            model.changeProfile { it.copy(folder = v) }
        }
        OutlinedButton(onClick = { pickers.folder()?.let { p -> model.changeProfile { it.copy(folder = p.toString()) } } }) {
            Text("Choose…")
        }
    }
    Field("Naming template", form.template, "profile-template", editing.errors[ProfileField.TEMPLATE], typing) { v ->
        model.changeProfile { it.copy(template = v) }
    }
    Field("Speed limit (empty: none)", form.speedKbps, "profile-speed", editing.errors[ProfileField.SPEED], typing, "KB/s") { v ->
        model.changeProfile { it.copy(speedKbps = v) }
    }
    Choice("Media", ProfileMedia.entries, form.media, "profile-media", { it.label }) { v -> model.changeProfile { it.copy(media = v) } }
    Text("Proxy", style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("Default" to ProxyChoice.Inherit, "None" to ProxyChoice.Direct, "System" to ProxyChoice.System).forEach { (label, choice) ->
            FilterChip(
                selected = form.proxy == choice,
                onClick = { model.changeProfile { it.copy(proxy = choice) } },
                label = { Text(label) },
                modifier = Modifier.testTag("profile-proxy-${choice.encode().lowercase()}"),
            )
        }
        state.proxies.forEach { proxy ->
            FilterChip(
                selected = form.proxy == ProxyChoice.Named(proxy.id),
                onClick = { model.changeProfile { it.copy(proxy = ProxyChoice.Named(proxy.id)) } },
                label = { Text(proxy.name) },
                modifier = Modifier.testTag("profile-proxy-${proxy.id.value}"),
            )
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = model::saveProfile,
            enabled = editing.errors.isEmpty(),
            modifier = Modifier.testTag("profile-save"),
        ) { Text("Save") }
        TextButton(onClick = model::cancelProfile, modifier = Modifier.testTag("profile-cancel")) { Text("Cancel") }
    }
}

@Composable
private fun History(
    state: SettingsState,
    model: SettingsModel,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Finished downloads", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        OutlinedButton(
            onClick = model::askClearHistory,
            enabled = state.history.isNotEmpty(),
            modifier = Modifier.testTag("history-clear"),
        ) {
            Text("Clear history")
        }
    }
    if (state.history.isEmpty()) Text("Nothing yet.", modifier = Modifier.testTag("history-empty"))
    if (state.history.size == HISTORY_SHOWN) Text("The newest $HISTORY_SHOWN are shown.", style = MaterialTheme.typography.bodySmall)
    state.history.forEachIndexed { index, entry ->
        Row(Modifier.fillMaxWidth().testTag("history-$index"), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(entry.name)
                Text(entry.path, style = MaterialTheme.typography.bodySmall)
            }
            Text(entry.size?.let(::formatBytes) ?: "—")
            Text(
                entry.finishedAt
                    .toString()
                    .take(16)
                    .replace('T', ' '),
            )
        }
    }
    HorizontalDivider()
    Text("Traffic, last 30 days", style = MaterialTheme.typography.titleMedium)
    if (state.traffic.isEmpty()) Text("Nothing yet.", modifier = Modifier.testTag("traffic-empty"))
    state.traffic.forEach { day ->
        Row(Modifier.fillMaxWidth().testTag("traffic-${day.day}"), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(day.day, modifier = Modifier.weight(1f))
            Text("↓ " + formatBytes(day.down))
            Text("↑ " + formatBytes(day.up))
        }
    }
    if (state.networks.isNotEmpty()) {
        Text("By network", style = MaterialTheme.typography.titleSmall)
        state.networks.forEach { (network, bytes) ->
            Row(Modifier.fillMaxWidth().testTag("network-$network"), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(network, modifier = Modifier.weight(1f))
                Text("↓ " + formatBytes(bytes.first))
                Text("↑ " + formatBytes(bytes.second))
            }
        }
    }
}

@Composable
private fun Backup(
    model: SettingsModel,
    pickers: Pickers,
) {
    Text("The transfer and file settings, categories and profiles. Proxies, cookies and logins are never in the file.")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = { pickers.saveSettingsFile("anydm-settings.json")?.let(model::export) },
            modifier = Modifier.testTag("backup-export"),
        ) { Text("Export…") }
        OutlinedButton(onClick = { pickers.settingsFile()?.let(model::import) }, modifier = Modifier.testTag("backup-import")) {
            Text("Import…")
        }
    }
    HorizontalDivider()
    Text("Put the transfer and file settings back to their defaults. Categories, profiles and history stay.")
    OutlinedButton(onClick = model::askReset, modifier = Modifier.testTag("backup-reset")) { Text("Reset to defaults") }
}

@Composable
private fun Confirm(
    confirm: SettingsConfirm,
    model: SettingsModel,
) {
    val (title, action) =
        when (confirm) {
            is SettingsConfirm.Discard -> {
                "Drop the changes you haven't saved?" to "Drop them"
            }

            SettingsConfirm.ClearHistory -> {
                "Clear the history? Downloads and their files stay." to "Clear"
            }

            SettingsConfirm.Reset -> {
                "Put the transfer and file settings back to their defaults?" to "Reset"
            }

            is SettingsConfirm.DeleteCategory -> {
                "Delete the category ${confirm.category.name}? Files already saved stay where they are." to
                    "Delete"
            }

            is SettingsConfirm.DeleteProfile -> {
                "Delete the profile ${confirm.profile.name}?" to "Delete"
            }
        }
    AlertDialog(
        onDismissRequest = model::dismissConfirm,
        text = { Text(title) },
        confirmButton = { Button(onClick = model::confirm, modifier = Modifier.testTag("settings-confirm-ok")) { Text(action) } },
        dismissButton = {
            TextButton(
                onClick = model::dismissConfirm,
                modifier = Modifier.testTag("settings-confirm-cancel"),
            ) { Text("Cancel") }
        },
        modifier = Modifier.testTag("settings-confirm"),
    )
}

@Composable
internal fun Field(
    label: String,
    value: String,
    tag: String,
    error: String?,
    typing: Modifier,
    unit: String? = null,
    width: Int = 420,
    secret: Boolean = false,
    singleLine: Boolean = true,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = singleLine,
        isError = error != null,
        suffix = unit?.let { { Text(it) } },
        supportingText = error?.let { { Text(it, modifier = Modifier.testTag("$tag-error")) } },
        modifier = typing.width(width.dp).testTag(tag),
    )
}

@Composable
internal fun Toggle(
    label: String,
    checked: Boolean,
    tag: String,
    onChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onChange, Modifier.testTag(tag))
        Text(label)
    }
}

@Composable
internal fun <T> Choice(
    label: String,
    options: List<T>,
    selected: T,
    tag: String,
    name: (T) -> String,
    onPick: (T) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { option ->
                FilterChip(
                    selected = option == selected,
                    onClick = { onPick(option) },
                    label = { Text(name(option)) },
                    modifier = Modifier.testTag("$tag-${option.toString().lowercase()}"),
                )
            }
        }
    }
}

@Composable
internal fun ErrorText(
    text: String,
    tag: String,
) = Text(text, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(tag))

private fun clashLabel(policy: ClashPolicy): String =
    when (policy) {
        ClashPolicy.RENAME -> "Keep both"
        ClashPolicy.OVERWRITE -> "Replace"
        ClashPolicy.SKIP -> "Skip the new one"
    }

private fun pacingLabel(preset: PacingPreset): String =
    when (preset) {
        PacingPreset.OFF -> "Off"
        PacingPreset.BALANCED -> "Balanced"
        PacingPreset.CAREFUL -> "Careful"
        PacingPreset.CUSTOM -> "Custom"
    }
