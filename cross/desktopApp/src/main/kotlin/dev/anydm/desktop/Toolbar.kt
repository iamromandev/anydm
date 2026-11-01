package dev.anydm.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** The commands the toolbar always shows; each is disabled when nothing is selected or it doesn't apply. */
private val SelectionCommands = listOf(JobCommand.PAUSE, JobCommand.RESUME, JobCommand.CANCEL, JobCommand.RETRY, JobCommand.REMOVE)

/**
 * The top bar: the app and section name, then, in the downloads section, the actions, the search field and the sort
 * control. The bar wraps onto more lines when the window is narrow, so no action is ever hidden.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Toolbar(
    state: UiState,
    model: AppModel,
) {
    FlowRow(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = Space.lg, vertical = Space.sm),
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
        verticalArrangement = Arrangement.spacedBy(Space.xs),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        // One item, so the app name and the section can't land on different lines.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.semantics(mergeDescendants = true) {}.testTag("toolbar-title"),
        ) {
            Text("anydm", style = MaterialTheme.typography.titleMedium)
            Text(
                "  ·  " + title(state.route, state.options.protocol),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.route !is Route.Downloads) return@FlowRow
        Button(onClick = { model.openAdd() }, modifier = Modifier.testTag("add-open")) { Text("Add…") }
        SelectionCommands.forEach { command ->
            OutlinedButton(
                onClick = { model.run(command) },
                enabled = state.enabled(command),
                modifier = Modifier.testTag("cmd-${command.tag}"),
            ) { Text(command.label) }
        }
        TextButton(onClick = { model.pauseAll() }, modifier = Modifier.testTag("pause-all")) { Text("Pause all") }
        TextButton(onClick = { model.resumeAll() }, modifier = Modifier.testTag("resume-all")) { Text("Resume all") }
        OutlinedTextField(
            value = state.options.query,
            onValueChange = model::setQuery,
            placeholder = { Text("Filter by name or address") },
            singleLine = true,
            modifier = Modifier.width(240.dp).testTag("query").onFocusChanged { model.typing = it.isFocused },
        )
        SortMenu(state.options, model::setSort)
    }
}

@Composable
private fun SortMenu(
    options: ListOptions,
    onSort: (SortKey) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.testTag("sort-menu")) {
            Text("Sort" + (options.sort?.let { ": ${it.label}" } ?: "") + arrow(options, options.sort))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SortKey.entries.forEach { key ->
                DropdownMenuItem(
                    text = { Text(key.label + arrow(options, key)) },
                    onClick = {
                        open = false
                        onSort(key)
                    },
                    modifier = Modifier.testTag("sort-${key.name.lowercase()}"),
                )
            }
        }
    }
}

/** The direction mark for [key] when it is the active sort. */
private fun arrow(
    options: ListOptions,
    key: SortKey?,
): String = if (key != null && options.sort == key) (if (options.descending) " ↓" else " ↑") else ""
