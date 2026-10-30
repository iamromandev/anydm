package dev.anydm.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.anydm.engine.store.Settings

/** The notifications, clipboard, hotkey and browser bridge section (#609). */
@Composable
internal fun OsPanel(
    model: OsModel,
    typing: Modifier,
) {
    val state by model.state.collectAsState()
    if (!state.loaded) {
        Text(state.message ?: "Reading the settings…", modifier = Modifier.testTag("os-unavailable"))
        return
    }
    Toggle("Tell me when a download finishes or fails", state.notifications, "os-notifications", model::setNotifications)
    Toggle("Watch the clipboard for a copied link and offer to download it", state.clipboardWatch, "os-clipboard", model::setClipboardWatch)
    Toggle("Global hotkey queues the copied link", state.globalHotkey, "os-hotkey", model::setGlobalHotkey)
    Text(
        hotkeyHelp(state.hotkeyStatus, state.globalHotkey),
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.testTag("os-hotkey-help"),
    )
    HorizontalDivider()
    Text("Browser extensions the bridge answers", style = MaterialTheme.typography.titleMedium)
    Text(
        "anydm's own Chrome extension is allowed by default. Firefox gives each install its own address: copy yours " +
            "(moz-extension://…) from about:debugging and add it here.",
        style = MaterialTheme.typography.bodySmall,
    )
    if (state.origins.isEmpty()) {
        Text(
            "No origin is listed, so the bridge answers any extension. Add the ones you use, or go back to the Chrome extension only.",
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("origin-any-warning"),
        )
    }
    state.origins.forEach { origin ->
        Row(Modifier.fillMaxWidth().testTag("origin-row-$origin"), verticalAlignment = Alignment.CenterVertically) {
            Text(
                origin + if (origin == Settings.CHROME_EXTENSION_ORIGIN) "  (anydm for Chrome)" else "",
                modifier = Modifier.weight(1f).testTag("origin-text-$origin"),
            )
            TextButton(onClick = { model.removeOrigin(origin) }, modifier = Modifier.testTag("origin-remove-$origin")) { Text("Remove") }
        }
    }
    var draft by remember { mutableStateOf("") }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Field("Add an origin", draft, "origin-new", state.originError, typing, width = 420) { draft = it }
        Button(
            onClick = { if (model.addOrigin(draft)) draft = "" },
            enabled = draft.isNotBlank(),
            modifier = Modifier.testTag("origin-add"),
        ) { Text("Add") }
    }
    OutlinedButton(onClick = model::resetOrigins, modifier = Modifier.testTag("origin-reset")) { Text("Back to the Chrome extension only") }
    state.message?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("os-message")) }
}
