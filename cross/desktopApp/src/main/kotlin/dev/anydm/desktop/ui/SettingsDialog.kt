package dev.anydm.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import dev.anydm.settings.Player
import dev.anydm.settings.SettingsStore
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/** The player Play uses, and whether Remove asks first. Each change is saved at once. */
@Composable
fun SettingsDialog(
    settings: SettingsStore,
    onClose: () -> Unit,
) {
    val prefs by settings.settings.collectAsState()
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Play finished files with")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(prefs.player == Player.System, { settings.update { it.copy(player = Player.System) } })
                    Text("The system's default (usually the browser)")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(prefs.player is Player.Command, {
                        val dialog = FileDialog(null as Frame?, "Choose a player", FileDialog.LOAD)
                        dialog.isVisible = true
                        val name = dialog.file
                        if (name != null) settings.update { it.copy(player = Player.Command(File(dialog.directory, name).path)) }
                    })
                    Text((prefs.player as? Player.Command)?.path ?: "A player you choose (VLC, IINA, mpv)…")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(prefs.confirmBeforeRemove, { on -> settings.update { it.copy(confirmBeforeRemove = on) } })
                    Text("Ask before removing a download")
                }
            }
        },
        confirmButton = { TextButton(onClose) { Text("Done") } },
    )
}
