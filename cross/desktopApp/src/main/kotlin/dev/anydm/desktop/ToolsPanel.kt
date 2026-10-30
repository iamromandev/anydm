package dev.anydm.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.anydm.engine.tools.YtDlpChannel

private fun originText(row: ToolRow): String =
    when (row.origin) {
        ToolOrigin.MISSING -> {
            "Not installed. It is fetched when a download needs it, or now with Update."
        }

        ToolOrigin.MANAGED -> {
            "Fetched and kept up to date by anydm"
        }

        ToolOrigin.SYSTEM -> {
            "A copy already on this computer, kept up to date by whoever installed it. " +
                "To let anydm fetch its own, turn off copies on this computer below."
        }

        ToolOrigin.FOLDER -> {
            "From the folder you chose. Clear the folder to let anydm fetch its own."
        }
    }

/** The runtime tools section (#609). */
@Composable
internal fun ToolsPanel(
    model: ToolsModel,
    pickers: Pickers,
    typing: Modifier,
) {
    val state by model.state.collectAsState()
    if (!state.loaded) {
        Text(state.message ?: "Looking for the tools…", modifier = Modifier.testTag("tools-unavailable"))
        return
    }
    state.tools.forEach { row ->
        val id = row.tool.id
        Column(Modifier.fillMaxWidth().testTag("tool-row-$id"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    row.tool.label + (row.version?.let { " $it" } ?: ""),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f).testTag("tool-title-$id"),
                )
                OutlinedButton(
                    onClick = { model.update(row.tool) },
                    enabled = row.origin == ToolOrigin.MISSING || row.origin == ToolOrigin.MANAGED,
                    modifier = Modifier.testTag("tool-update-$id"),
                ) {
                    Text(if (row.origin == ToolOrigin.MISSING) "Download" else "Update")
                }
            }
            Text(originText(row), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("tool-origin-$id"))
            row.path?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("tool-path-$id")) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val shown = state.folderDrafts[row.tool] ?: row.folder
                Field(
                    "Use this folder instead (empty: search as usual)",
                    shown,
                    "tool-folder-$id",
                    state.folderErrors[row.tool],
                    typing,
                    width = 400,
                ) { model.editFolder(row.tool, it) }
                Button(
                    onClick = { model.applyFolder(row.tool) },
                    enabled = shown != row.folder,
                    modifier = Modifier.testTag("tool-apply-$id"),
                ) { Text("Apply") }
                OutlinedButton(
                    onClick = { pickers.folder()?.let { model.setFolder(row.tool, it.toString()) } },
                    modifier = Modifier.testTag("tool-choose-$id"),
                ) { Text("Choose…") }
            }
        }
        HorizontalDivider()
    }
    Toggle("Use a copy already on this computer when it is new enough", state.systemTools, "tools-system") { model.setSystemTools(it) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("yt-dlp builds to follow", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            YtDlpChannel.entries.forEach { channel ->
                FilterChip(
                    selected = channel == state.channel,
                    onClick = { model.setChannel(channel) },
                    label = { Text(if (channel == YtDlpChannel.STABLE) "Stable" else "Nightly") },
                    modifier = Modifier.testTag("tools-channel-${channel.name.lowercase()}"),
                )
            }
        }
    }
    state.message?.let { Text(it, modifier = Modifier.testTag("tools-message")) }
}
