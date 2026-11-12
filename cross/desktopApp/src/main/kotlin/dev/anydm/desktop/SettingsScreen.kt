package dev.anydm.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The Settings page: it takes the place of the list (like the detail view) with a sidebar of sections. */
@Composable
fun SettingsScreen(
    section: SettingsSection,
    onSection: (SettingsSection) -> Unit,
    onBack: () -> Unit,
    folder: String,
    onChangeFolder: () -> Unit,
    themeChoice: ThemeChoice,
    onTheme: (ThemeChoice) -> Unit,
    unit: SpeedUnit,
    onUnit: (SpeedUnit) -> Unit,
    limits: Limits,
    slow: Boolean,
    limitsPane: String,
    onLimitsPane: (String) -> Unit,
    onSaveLimits: (Limits, Boolean) -> Unit,
    networks: List<Network>,
    onNetworksChange: (List<Network>) -> Unit,
    onEditingChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    keychainNotes: Map<String, String> = emptyMap(),
) {
    val c = colors
    // The Speed & data draft lives here, above the section switch: it survives a visit to another section and is dropped
    // with the page, so Back and Esc discard it, as they did with the old dialog.
    val limitsDraft = remember { LimitsDraftHolder() }
    Column(modifier.testTag("settings-page")) {
        Row(
            Modifier
                .testTag("settings-back")
                .clickable(role = Role.Button, onClick = onBack)
                .padding(horizontal = 20.dp, vertical = 14.dp),
        ) {
            Text("‹ Downloads", color = c.textSecondary, fontSize = 15.sp, maxLines = 1)
        }
        Divider()
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.width(200.dp).padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SettingsSection.entries.forEach { s -> SectionRow(s, s == section) { onSection(s) } }
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(c.border))
            Box(Modifier.weight(1f).fillMaxHeight()) {
                when (section) {
                    SettingsSection.General -> {
                        GeneralSection(folder, onChangeFolder)
                    }

                    SettingsSection.Appearance -> {
                        AppearanceSection(themeChoice, onTheme, unit, onUnit)
                    }

                    SettingsSection.SpeedData -> {
                        SpeedDataSection(
                            limits,
                            networks,
                            unit,
                            slow,
                            limitsPane,
                            onLimitsPane,
                            onSaveLimits,
                            limitsDraft,
                        )
                    }

                    SettingsSection.Networks -> {
                        NetworksSection(networks, onNetworksChange, onEditingChange, keychainNotes)
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionRow(
    section: SettingsSection,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val c = colors
    Row(
        Modifier
            .testTag("settings-nav-${section.tag}")
            .fillMaxWidth()
            .background(if (selected) c.track else Color.Transparent, RoundedCornerShape(Radius))
            .selectable(selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
    ) {
        Text(section.label, color = if (selected) c.text else c.textSecondary, fontSize = 13.sp, maxLines = 1)
    }
}

@Composable
private fun Heading(text: String) {
    Text(text, color = colors.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1)
}

@Composable
private fun Group(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        content()
    }
}

@Composable
private fun ScrollColumn(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) { content() }
}

@Composable
private fun GeneralSection(
    folder: String,
    onChange: () -> Unit,
) {
    val c = colors
    ScrollColumn {
        Heading("General")
        Group("Default save folder") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    folder,
                    color = c.textSecondary,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false).testTag("settings-folder"),
                )
                OutlineButton("Change", "settings-folder-change", onChange)
            }
        }
    }
}

@Composable
private fun AppearanceSection(
    theme: ThemeChoice,
    onTheme: (ThemeChoice) -> Unit,
    unit: SpeedUnit,
    onUnit: (SpeedUnit) -> Unit,
) {
    ScrollColumn {
        Heading("Appearance")
        Group("Theme") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeChoice.entries.forEach { t ->
                    RadioDot(theme == t, { onTheme(t) }, "theme-${t.name.lowercase()}", if (t == ThemeChoice.System) "System" else t.name)
                }
            }
        }
        Group("Speed unit") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RadioDot(unit == SpeedUnit.MBps, { onUnit(SpeedUnit.MBps) }, "settings-unit-mbps-bytes", "MB/s")
                RadioDot(unit == SpeedUnit.Mbps, { onUnit(SpeedUnit.Mbps) }, "settings-unit-mbps", "Mbps")
            }
        }
    }
}

@Composable
private fun SpeedDataSection(
    limits: Limits,
    networks: List<Network>,
    unit: SpeedUnit,
    slow: Boolean,
    pane: String,
    onPane: (String) -> Unit,
    onSave: (Limits, Boolean) -> Unit,
    draft: LimitsDraftHolder,
) {
    // Cancel throws the draft away and builds the editor again. A change of unit or of the footer's slow switch does not:
    // the editor converts its draft itself.
    var reset by remember { mutableIntStateOf(0) }
    key(reset) {
        LimitsEditor(
            limits,
            networks,
            unit,
            slow,
            onCancel = {
                draft.discard()
                reset++
            },
            onSave = onSave,
            initialPane = pane,
            onPaneChange = onPane,
            draftHolder = draft,
        )
    }
}

/** Networks: a list to pick from and the same page the Networks menu shows, without its back row and limits link. */
@Composable
private fun NetworksSection(
    networks: List<Network>,
    onNetworksChange: (List<Network>) -> Unit,
    onEditingChange: (Boolean) -> Unit,
    keychainNotes: Map<String, String>,
) {
    val c = colors
    var selectedId by remember { mutableStateOf(networks.firstOrNull()?.id) }
    var editing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf(TextFieldValue("")) }
    var rejected by remember { mutableStateOf<String?>(null) }
    var proxyEditing by remember { mutableStateOf(false) }
    var proxyFocused by remember { mutableStateOf(false) }
    // A focused text input keeps Home's Esc and paste shortcuts out of its way, as in the Networks menu.
    LaunchedEffect(editing, proxyFocused) { onEditingChange(editing || proxyFocused) }
    DisposableEffect(Unit) { onDispose { onEditingChange(false) } }

    fun cancelEdit() {
        editing = false
        rejected = null
    }

    fun select(id: String) {
        cancelEdit()
        proxyEditing = false
        selectedId = id
    }

    val selected = networks.firstOrNull { it.id == selectedId }
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(200.dp).padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            networks.forEach { n ->
                val on = n.id == selectedId
                Row(
                    Modifier
                        .testTag("settings-network-${n.id}")
                        .fillMaxWidth()
                        .background(if (on) c.track else Color.Transparent, RoundedCornerShape(Radius))
                        .selectable(on, role = Role.Tab, onClick = { select(n.id) })
                        .semantics { contentDescription = n.name }
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                ) {
                    Text(
                        n.name,
                        color = if (n.enabled) c.text else c.textSecondary,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(c.border))
        if (selected != null) {
            NetworkSettingsPage(
                selected,
                editing = editing,
                draft = draft,
                error = if (editing) rejected else null,
                onDraft = {
                    draft = it
                    rejected = null
                },
                onStartRename = {
                    draft = TextFieldValue(selected.name, TextRange(0, selected.name.length))
                    rejected = null
                    editing = true
                },
                onCommit = {
                    if (editing) {
                        when (val result = rename(networks, selected.id, draft.text)) {
                            is RenameResult.Ok -> {
                                cancelEdit()
                                onNetworksChange(result.networks)
                            }

                            is RenameResult.Rejected -> {
                                rejected = result.reason
                            }
                        }
                    }
                },
                onCancel = ::cancelEdit,
                onColor = { onNetworksChange(setColor(networks, selected.id, it)) },
                onProxySave = { onNetworksChange(setProxy(networks, selected.id, it)) },
                onBack = null,
                onOpenLimits = null,
                proxyEditing = proxyEditing,
                onProxyEditing = { proxyEditing = it },
                onTextFocus = { proxyFocused = it },
                modifier = Modifier.weight(1f).fillMaxHeight().padding(vertical = 8.dp),
                keychainNote = keychainNotes[selected.id],
            )
        }
    }
}
