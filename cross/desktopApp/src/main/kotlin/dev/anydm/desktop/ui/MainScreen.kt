package dev.anydm.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.anydm.model.DiskDto
import dev.anydm.model.SummaryDto
import dev.anydm.model.Task
import dev.anydm.model.TaskKind
import dev.anydm.settings.SettingsStore
import dev.anydm.store.BulkAction
import dev.anydm.store.Connection
import dev.anydm.store.ListFilter
import dev.anydm.store.StoreEvent
import dev.anydm.store.TaskStore
import dev.anydm.store.count
import dev.anydm.store.matches
import dev.anydm.store.removePrompt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.util.Base64

private val FILTER_LABELS =
    listOf(ListFilter.ALL to "All", ListFilter.ACTIVE to "Active", ListFilter.SEEDING to "Seeding", ListFilter.COMPLETED to "Completed")

private fun videosOf(task: Task): Int? = if (task.kind == TaskKind.PLAYLIST) task.entryCounts?.total ?: 0 else null

/** The list: add, filter, act, remove, and see how the connection and the disk stand. */
@Composable
fun MainScreen(
    store: TaskStore,
    settings: SettingsStore,
    onSignOut: () -> Unit,
) {
    val state by store.state.collectAsState()
    val prefs by settings.settings.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var removing by remember { mutableStateOf<Task?>(null) }
    var clearing by remember { mutableStateOf(false) }
    // The clock retry countdowns read, ticked once a second (web: `store.now`).
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }
    LaunchedEffect(store) {
        store.events.collect { event -> if (event is StoreEvent.Said) snackbar.showSnackbar(event.notice.message) }
    }

    fun remove(task: Task) {
        if (prefs.confirmBeforeRemove) {
            removing = task
        } else {
            scope.launch { store.remove(task.id, deleteFiles = !removePrompt(task.status, videosOf(task)).canKeepFiles) }
        }
    }

    fun addTorrentFile(file: File) {
        scope.launch { store.addTorrent(Base64.getEncoder().encodeToString(file.readBytes())) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = { StatusBar(state.tasks, state.connection, state.disk) },
    ) { padding ->
        Row(Modifier.fillMaxSize().padding(padding).torrentDrop(::addTorrentFile)) {
            Sidebar(
                filter = state.filter,
                summary = state.summary,
                onFilter = store::setFilter,
                onBulk = { action -> if (action == BulkAction.CLEAR_FINISHED) clearing = true else scope.launch { store.bulk(action) } },
                onSignOut = onSignOut,
            )
            Column(Modifier.weight(1f).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AddBar(
                    defaultPreset = prefs.defaultPreset,
                    sort = state.sort,
                    onSort = store::setSort,
                    onAdd = { link, preset -> store.add(link, preset) },
                    onMagnet = { magnet -> store.addTorrent(magnet) },
                    onTorrentFile = ::addTorrentFile,
                    onPreset = { preset -> settings.update { it.copy(defaultPreset = preset) } },
                )
                val shown = state.tasks.filter { state.filter.matches(it) }
                if (shown.isEmpty()) {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { Text("No downloads here yet") }
                }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(shown, key = { it.id }) { task ->
                        TaskCard(cardView(task, now)) { action ->
                            when (action) {
                                CardAction.PAUSE -> {
                                    scope.launch { store.pause(task.id) }
                                }

                                CardAction.RESUME, CardAction.RETRY -> {
                                    scope.launch { store.resume(task.id) }
                                }

                                CardAction.STOP_SEEDING -> {
                                    scope.launch { store.stopSeeding(task.id) }
                                }

                                CardAction.PLAY, CardAction.SAVE -> {}

                                CardAction.REMOVE -> {
                                    remove(task)
                                }
                            }
                        }
                    }
                    if (state.page < state.totalPages) {
                        item {
                            OutlinedButton(onClick = store::loadMore, enabled = !state.loadingMore) {
                                Text(if (state.loadingMore) "Loading…" else "Load more")
                            }
                        }
                    }
                }
            }
        }
    }

    removing?.let { task ->
        RemoveDialog(task.title, removePrompt(task.status, videosOf(task)), onCancel = { removing = null }) { deleteFiles ->
            removing = null
            scope.launch { store.remove(task.id, deleteFiles) }
        }
    }
    if (clearing) {
        ClearFinishedDialog(onCancel = { clearing = false }) {
            clearing = false
            scope.launch { store.bulk(BulkAction.CLEAR_FINISHED) }
        }
    }
}

@Composable
private fun AddBar(
    defaultPreset: String,
    sort: String,
    onSort: (String) -> Unit,
    onAdd: suspend (link: String, preset: String) -> Boolean,
    onMagnet: suspend (magnet: String) -> Boolean,
    onTorrentFile: (File) -> Unit,
    onPreset: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var link by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    val submit = {
        val value = link.trim()
        if (value.isNotEmpty() && !adding) {
            adding = true
            scope.launch {
                // A magnet is a torrent; anything else is looked at first, as the web's add box does.
                val added = if (value.startsWith("magnet:")) onMagnet(value) else onAdd(value, defaultPreset)
                if (added) link = ""
                adding = false
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            link,
            { link = it },
            placeholder = { Text("A page, a direct link, or a magnet") },
            singleLine = true,
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier.weight(1f),
        )
        Menu(PRESET_OPTIONS, defaultPreset, onPreset)
        Button(onClick = submit, enabled = !adding) { Text(if (adding) "Adding…" else "Add") }
        OutlinedButton(onClick = {
            val dialog =
                FileDialog(null as Frame?, "Add a .torrent", FileDialog.LOAD).apply {
                    setFilenameFilter { _, name -> name.endsWith(".torrent") }
                }
            dialog.isVisible = true
            val name = dialog.file
            if (name != null) onTorrentFile(File(dialog.directory, name))
        }) { Text(".torrent…") }
        Menu(SORT_OPTIONS, sort, onSort)
    }
}

/** A dropdown over (value, label) pairs, showing the chosen label. */
@Composable
private fun Menu(
    options: List<Pair<String, String>>,
    chosen: String,
    onChoose: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text(options.firstOrNull { it.first == chosen }?.second ?: options.first().second) }
        DropdownMenu(open, { open = false }) {
            options.forEach { (value, label) ->
                DropdownMenuItem(text = { Text(label) }, onClick = {
                    open = false
                    onChoose(value)
                })
            }
        }
    }
}

@Composable
private fun Sidebar(
    filter: ListFilter,
    summary: SummaryDto?,
    onFilter: (ListFilter) -> Unit,
    onBulk: (BulkAction) -> Unit,
    onSignOut: () -> Unit,
) {
    Column(Modifier.width(200.dp).padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FILTER_LABELS.forEach { (value, label) ->
            val number =
                when (value) {
                    ListFilter.ALL -> summary?.all
                    ListFilter.ACTIVE -> summary?.downloading
                    ListFilter.SEEDING -> summary?.seeding
                    ListFilter.COMPLETED -> summary?.completed
                }
            NavigationDrawerItem(
                label = { Text(label) },
                badge = { number?.let { Text(count(it)) } },
                selected = value == filter,
                onClick = { onFilter(value) },
            )
        }
        HorizontalDivider()
        TextButton(onClick = { onBulk(BulkAction.PAUSE_ALL) }) { Text("Pause all") }
        TextButton(onClick = { onBulk(BulkAction.RESUME_ALL) }) { Text("Resume all") }
        TextButton(onClick = { onBulk(BulkAction.CLEAR_FINISHED) }) { Text("Clear finished") }
        HorizontalDivider()
        TextButton(onClick = onSignOut) { Text("Change server…") }
    }
}

@Composable
private fun StatusBar(
    tasks: List<Task>,
    connection: Connection,
    disk: DiskDto?,
) {
    val down = tasks.sumOf { it.downloadSpeed }
    val up = tasks.sumOf { it.uploadSpeed }
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("↓ ${formatSpeed(down)}", style = MaterialTheme.typography.bodySmall)
        Text("↑ ${formatSpeed(up)}", style = MaterialTheme.typography.bodySmall)
        Text(connectionLabel(connection), style = MaterialTheme.typography.bodySmall)
        disk?.let { Text("${formatBytes(it.freeBytes)} free", style = MaterialTheme.typography.bodySmall) }
    }
}
