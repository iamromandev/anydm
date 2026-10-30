package dev.anydm.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.anydm.engine.net.ProxyChoice
import dev.anydm.engine.net.ProxyEntry
import dev.anydm.engine.net.ProxyType

/** The proxies section (#609): the global choice, the saved proxies, and the form to add or change one. */
@Composable
internal fun ProxiesPanel(
    model: ProxyModel,
    typing: Modifier,
) {
    val state by model.state.collectAsState()
    if (!state.loaded) {
        Text(state.message ?: "Reading the proxies…", modifier = Modifier.testTag("proxy-unavailable"))
        return
    }
    Text("Which proxy downloads use when neither the download nor its profile names one.")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ChoiceChip("System", ProxyChoice.System, state.choice, "proxy-choice-system", model)
        ChoiceChip("Direct", ProxyChoice.Direct, state.choice, "proxy-choice-direct", model)
        state.proxies.forEach { ChoiceChip(it.name, ProxyChoice.Named(it.id), state.choice, "proxy-choice-${it.id.value}", model) }
    }
    HorizontalDivider()
    if (state.proxies.isEmpty()) Text("No proxies saved yet.", modifier = Modifier.testTag("proxy-empty"))
    state.proxies.forEach { ProxyRow(it, model) }
    if (state.form == null) {
        OutlinedButton(onClick = model::add, modifier = Modifier.testTag("proxy-add")) { Text("Add a proxy") }
    } else {
        ProxyEditor(state, model, typing)
    }
    state.message?.let { Text(it, modifier = Modifier.testTag("proxy-message")) }
    state.removing?.let { proxy ->
        AlertDialog(
            onDismissRequest = model::cancelRemove,
            title = { Text("Remove ${proxy.name}?") },
            text = { Text("Its saved password goes from the keychain too. A proxy that downloads or profiles use is kept.") },
            confirmButton = {
                Button(
                    onClick = model::confirmRemove,
                    modifier = Modifier.testTag("proxy-remove-confirm"),
                ) { Text("Remove") }
            },
            dismissButton = {
                TextButton(
                    onClick = model::cancelRemove,
                    modifier = Modifier.testTag("proxy-remove-cancel"),
                ) { Text("Cancel") }
            },
            modifier = Modifier.testTag("proxy-remove-dialog"),
        )
    }
}

@Composable
private fun ChoiceChip(
    label: String,
    choice: ProxyChoice,
    selected: ProxyChoice,
    tag: String,
    model: ProxyModel,
) = FilterChip(
    selected = choice == selected,
    onClick = { model.choose(choice) },
    label = { Text(label) },
    modifier = Modifier.testTag(tag),
)

@Composable
private fun ProxyRow(
    proxy: ProxyEntry,
    model: ProxyModel,
) {
    Row(Modifier.fillMaxWidth().testTag("proxy-row-${proxy.id.value}"), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(proxy.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag("proxy-name-${proxy.id.value}"))
            Text(
                "${proxy.type.label} · ${proxy.host}:${proxy.port}" + (proxy.username?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("proxy-detail-${proxy.id.value}"),
            )
        }
        TextButton(onClick = { model.edit(proxy) }, modifier = Modifier.testTag("proxy-edit-${proxy.id.value}")) { Text("Edit") }
        TextButton(onClick = { model.askRemove(proxy) }, modifier = Modifier.testTag("proxy-remove-${proxy.id.value}")) { Text("Remove") }
    }
}

@Composable
private fun ProxyEditor(
    state: ProxyState,
    model: ProxyModel,
    typing: Modifier,
) {
    val form = state.form ?: return
    HorizontalDivider()
    Text(if (form.id == null) "New proxy" else "Change proxy", style = MaterialTheme.typography.titleMedium)
    Field("Name", form.name, "proxy-name", state.errors[ProxyField.NAME], typing) { v -> model.edit { it.copy(name = v) } }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ProxyType.entries.forEach { type ->
            FilterChip(
                selected = type == form.type,
                onClick = { model.edit { it.copy(type = type) } },
                label = { Text(type.label) },
                modifier = Modifier.testTag("proxy-type-${type.name.lowercase()}"),
            )
        }
    }
    Text(form.type.note, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("proxy-type-note"))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Field("Host", form.host, "proxy-host", state.errors[ProxyField.HOST], typing, width = 300) { v -> model.edit { it.copy(host = v) } }
        Field("Port", form.port, "proxy-port", state.errors[ProxyField.PORT], typing, width = 110) { v -> model.edit { it.copy(port = v) } }
    }
    Field("Username (optional)", form.username, "proxy-username", state.errors[ProxyField.USERNAME], typing) { v ->
        model.edit { it.copy(username = v) }
    }
    Field(
        if (form.id == null) "Password (optional)" else "Password (empty keeps the saved one)",
        form.password,
        "proxy-password",
        state.errors[ProxyField.PASSWORD],
        typing,
        secret = true,
    ) { v -> model.edit { it.copy(password = v) } }
    if (form.id != null) {
        Toggle("Remove the saved password", form.clearPassword, "proxy-clear-password") { on -> model.edit { it.copy(clearPassword = on) } }
    }
    Field(
        "Hosts that skip this proxy (one per line or comma)",
        form.bypass,
        "proxy-bypass",
        state.errors[ProxyField.BYPASS],
        typing,
        singleLine = false,
    ) { v -> model.edit { it.copy(bypass = v) } }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = model::cancel, modifier = Modifier.testTag("proxy-cancel")) { Text("Cancel") }
        Button(onClick = model::save, enabled = state.canSave, modifier = Modifier.testTag("proxy-save")) { Text("Save") }
    }
}
