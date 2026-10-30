package dev.anydm.desktop

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.anydm.engine.core.DownloadStatus

/** The commands the toolbar offers for a selection; the context menu has all that apply. */
private val BulkCommands = listOf(JobCommand.PAUSE, JobCommand.RESUME, JobCommand.CANCEL, JobCommand.RETRY, JobCommand.REMOVE)

/** The downloads section: the list's toolbar, the list, and the selected job's details beside it. */
@Composable
internal fun Downloads(
    state: UiState,
    filter: Filter,
    model: AppModel,
    focusList: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        ListToolbar(state, model)
        HorizontalDivider()
        Row(Modifier.weight(1f)) {
            BoxWithConstraints(Modifier.weight(1f).fillMaxSize()) {
                // A narrow list (a small window, or the detail pane open) keeps the name readable by leaving out speed, ETA and priority.
                val compact = maxWidth < CompactBelow
                Column(Modifier.fillMaxSize()) {
                    Header(state.options, compact, model::setSort)
                    HorizontalDivider()
                    when {
                        state.rows.isNotEmpty() -> List(state, compact, model, focusList)
                        state.counts.of(filter) == 0 -> Placeholder("Nothing here yet.", "empty")
                        else -> Placeholder("No downloads match.", "no-match")
                    }
                }
            }
            state.detail?.let { DetailPane(it, model) }
        }
        Dialogs(state.dialog, model)
    }
}

@Composable
private fun ListToolbar(
    state: UiState,
    model: AppModel,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = state.options.query,
            onValueChange = model::setQuery,
            placeholder = { Text("Filter by name or address") },
            singleLine = true,
            modifier = Modifier.width(260.dp).testTag("query").onFocusChanged { model.typing = it.isFocused },
        )
        if (state.selection.isNotEmpty()) {
            BulkCommands.forEach { command ->
                OutlinedButton(
                    onClick = { model.run(command) },
                    enabled = state.enabled(command),
                    modifier = Modifier.testTag("cmd-${command.tag}"),
                ) { Text(command.label) }
            }
        }
        Box(Modifier.weight(1f))
        TextButton(onClick = { model.pauseAll() }, modifier = Modifier.testTag("pause-all")) { Text("Pause all") }
        TextButton(onClick = { model.resumeAll() }, modifier = Modifier.testTag("resume-all")) { Text("Resume all") }
    }
}

private val SizeWidth = 80.dp
private val ProgressWidth = 120.dp
private val SpeedWidth = 80.dp
private val EtaWidth = 64.dp
private val StatusWidth = 110.dp
private val PriorityWidth = 56.dp
private val CompactBelow = 760.dp

@Composable
private fun Header(
    options: ListOptions,
    compact: Boolean,
    onSort: (SortKey) -> Unit,
) {
    @Composable
    fun RowScope.Column(
        key: SortKey?,
        label: String,
        modifier: Modifier,
    ) {
        val arrow = if (key != null && options.sort == key) (if (options.descending) " ↓" else " ↑") else ""
        Text(
            label + arrow,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier =
                modifier
                    .then(if (key != null) Modifier.clickable { onSort(key) }.testTag("sort-${key.name.lowercase()}") else Modifier)
                    .padding(vertical = 6.dp),
        )
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Column(SortKey.NAME, "Name", Modifier.weight(1f))
        Column(SortKey.SIZE, "Size", Modifier.width(SizeWidth))
        Column(SortKey.PROGRESS, "Progress", Modifier.width(ProgressWidth))
        if (!compact) {
            Column(null, "Speed", Modifier.width(SpeedWidth))
            Column(null, "ETA", Modifier.width(EtaWidth))
        }
        Column(SortKey.STATUS, "Status", Modifier.width(StatusWidth))
        if (!compact) Column(SortKey.PRIORITY, "Priority", Modifier.width(PriorityWidth))
    }
}

@Composable
private fun List(
    state: UiState,
    compact: Boolean,
    model: AppModel,
    focusList: () -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize().testTag("list")) {
        items(state.rows, key = { it.id.value }) { row ->
            ListRow(row, row.id in state.selection, compact, state, model, focusList)
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
private fun ListRow(
    row: Row,
    selected: Boolean,
    compact: Boolean,
    state: UiState,
    model: AppModel,
    focusList: () -> Unit,
) {
    val s = row.snapshot
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)
                // One press handler for every button, so Shift and Command/Control are read from the same event.
                .onPointerEvent(PointerEventType.Press) { event ->
                    // The expand arrow took this press.
                    if (event.changes.any { it.isConsumed }) return@onPointerEvent
                    val keys = event.keyboardModifiers
                    focusList()
                    when {
                        event.buttons.isSecondaryPressed -> {
                            if (!selected) model.select(row.id)
                            menu = true
                        }

                        keys.isShiftPressed -> {
                            model.selectRange(row.id)
                        }

                        if (IsMac) keys.isMetaPressed else keys.isCtrlPressed -> {
                            model.select(row.id, extend = true)
                        }

                        else -> {
                            model.select(row.id)
                        }
                    }
                }.padding(start = 12.dp + 20.dp * row.depth, end = 12.dp, top = 6.dp, bottom = 6.dp)
                .testTag("row-${row.id.value}"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                if (row.expandable) {
                    Text(
                        if (row.expanded) "▾ " else "▸ ",
                        modifier =
                            Modifier
                                .onPointerEvent(PointerEventType.Press) { event ->
                                    // A right-click goes on to the row: select and menu.
                                    if (!event.buttons.isPrimaryPressed) return@onPointerEvent
                                    event.changes.forEach { it.consume() }
                                    model.toggleExpanded(row.id)
                                }.testTag("expand-${row.id.value}"),
                    )
                }
                Column {
                    Text(
                        displayName(s),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("name-${row.id.value}"),
                    )
                    s.items?.let { items ->
                        Text(
                            "${items.completed}/${items.total} done",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.testTag("items-${row.id.value}"),
                        )
                    }
                    s.lastFailure?.message?.takeIf { s.download.status == DownloadStatus.FAILED }?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("error-${row.id.value}"),
                        )
                    }
                }
            }
            Text((s.total ?: s.download.size)?.let(::formatBytes) ?: "—", Modifier.width(SizeWidth))
            Row(Modifier.width(ProgressWidth), verticalAlignment = Alignment.CenterVertically) {
                val done = progress(s)
                if (done != null) {
                    LinearProgressIndicator(progress = { done }, modifier = Modifier.weight(1f))
                    Text(" ${(done * 100).toInt()}%", modifier = Modifier.testTag("progress-${row.id.value}"))
                } else {
                    LinearProgressIndicator(modifier = Modifier.weight(1f))
                }
            }
            if (!compact) {
                Text(if (s.speed > 0) formatSpeed(s.speed) else "", Modifier.width(SpeedWidth))
                Text(eta(s) ?: "", Modifier.width(EtaWidth).testTag("eta-${row.id.value}"))
            }
            Text(statusLabel(s), Modifier.width(StatusWidth).testTag("status-${row.id.value}"))
            if (!compact) Text(s.download.priority.toString(), Modifier.width(PriorityWidth))
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            JobCommand.entries.filter { state.enabled(it) }.forEach { command ->
                DropdownMenuItem(
                    text = { Text(command.label) },
                    onClick = {
                        menu = false
                        model.run(command)
                    },
                    modifier = Modifier.testTag("menu-${command.tag}"),
                )
            }
        }
    }
}

@Composable
internal fun Placeholder(
    text: String,
    tag: String,
) {
    Box(Modifier.fillMaxSize().testTag(tag), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
