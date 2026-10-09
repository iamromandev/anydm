package dev.anydm.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import dev.anydm.desktop.chrome.Glyphs
import dev.anydm.desktop.chrome.PushButton
import dev.anydm.desktop.chrome.hostOf
import dev.anydm.desktop.theme.DesktopTheme
import dev.anydm.desktop.theme.LocalTokens
import dev.anydm.settings.Player
import dev.anydm.settings.Settings
import dev.anydm.settings.SettingsStore
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/** Pick a player app or binary; `null` when the dialog is cancelled. */
fun choosePlayer(): String? {
    val dialog = FileDialog(null as Frame?, "Choose a player", FileDialog.LOAD)
    dialog.isVisible = true
    val name = dialog.file ?: return null
    return File(dialog.directory, name).path
}

/** The Settings window (spec: "Settings", ⌘,): each change is saved at once; Esc closes it. */
@Composable
fun SettingsWindow(
    settings: SettingsStore,
    onChangeServer: () -> Unit,
    onManageCategories: () -> Unit = {},
    onClose: () -> Unit,
) {
    val prefs by settings.settings.collectAsState()
    Window(
        onCloseRequest = onClose,
        title = "Settings",
        resizable = false,
        state = rememberWindowState(size = DpSize(460.dp, 420.dp)),
        onPreviewKeyEvent = { event ->
            if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                onClose()
                true
            } else {
                false
            }
        },
    ) {
        DesktopTheme {
            Box(Modifier.fillMaxSize().background(LocalTokens.current.content)) {
                SettingsContent(
                    prefs,
                    { change -> settings.update(change) },
                    ::choosePlayer,
                    {
                        onClose()
                        onChangeServer()
                    },
                    onManageCategories,
                )
            }
        }
    }
}

@Composable
fun SettingsContent(
    prefs: Settings,
    onChange: ((Settings) -> Settings) -> Unit,
    onChoosePlayer: () -> String?,
    onChangeServer: () -> Unit,
    onManageCategories: () -> Unit = {},
) {
    val t = LocalTokens.current
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Section("Playback")
        Choice("The system's default (usually the browser)", prefs.player == Player.System) {
            onChange { it.copy(player = Player.System) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Choice((prefs.player as? Player.Command)?.path ?: "A player you choose (VLC, IINA, mpv)", prefs.player is Player.Command) {
                (prefs.player as? Player.Command) ?: onChoosePlayer()?.let { path -> onChange { it.copy(player = Player.Command(path)) } }
            }
            PushButton("Choose…") { onChoosePlayer()?.let { path -> onChange { it.copy(player = Player.Command(path)) } } }
        }
        HorizontalDivider(color = t.separator)
        Section("Downloads")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Default quality", color = t.text)
            PresetPicker(prefs.defaultPreset) { preset -> onChange { it.copy(defaultPreset = preset) } }
        }
        Row(
            Modifier.toggleable(prefs.confirmBeforeRemove, role = Role.Checkbox) { on -> onChange { it.copy(confirmBeforeRemove = on) } },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(prefs.confirmBeforeRemove, null)
            Text("Ask before removing a download", color = t.text)
        }
        PushButton("Categories…", onClick = onManageCategories)
        HorizontalDivider(color = t.separator)
        Section("Server")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(hostOf(prefs.serverUrl), color = t.text)
            PushButton("Change server…", onClick = onChangeServer)
        }
    }
}

@Composable
private fun Section(text: String) {
    Text(text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = LocalTokens.current.secondaryText)
}

@Composable
internal fun Choice(
    label: String,
    selected: Boolean,
    onPick: () -> Unit,
) {
    Row(Modifier.selectable(selected, role = Role.RadioButton, onClick = onPick), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, null)
        Text(label, color = LocalTokens.current.text)
    }
}

@Composable
private fun PresetPicker(
    preset: String,
    onPreset: (String) -> Unit,
) {
    val t = LocalTokens.current
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.clip(RoundedCornerShape(5.dp)).clickable { open = true }.padding(horizontal = 6.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(PRESET_OPTIONS.firstOrNull { it.first == preset }?.second ?: PRESET_OPTIONS.first().second, color = t.text)
            Icon(Glyphs.ChevronDown, null, Modifier.size(12.dp), tint = t.secondaryText)
        }
        DropdownMenu(open, { open = false }) {
            PRESET_OPTIONS.forEach { (value, label) ->
                DropdownMenuItem(text = { Text(label) }, onClick = {
                    open = false
                    onPreset(value)
                })
            }
        }
    }
}
