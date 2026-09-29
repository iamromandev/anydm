package dev.anydm.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.anydm.desktop.chrome.Banner
import dev.anydm.desktop.chrome.BannerHost
import dev.anydm.desktop.chrome.BannerQueue
import dev.anydm.desktop.chrome.Glyphs
import dev.anydm.desktop.chrome.SourceList
import dev.anydm.desktop.chrome.StatusBar
import dev.anydm.desktop.chrome.Toolbar
import dev.anydm.desktop.chrome.hostOf
import dev.anydm.desktop.chrome.sourceItems
import dev.anydm.desktop.chrome.toolbarInset
import dev.anydm.desktop.files.Saver
import dev.anydm.desktop.files.downloadsDir
import dev.anydm.desktop.files.isMedia
import dev.anydm.desktop.files.play
import dev.anydm.desktop.files.revealFile
import dev.anydm.desktop.files.saveTargets
import dev.anydm.desktop.list.Command
import dev.anydm.desktop.list.DownloadList
import dev.anydm.desktop.list.Selection
import dev.anydm.desktop.list.commandFor
import dev.anydm.desktop.list.visibleOrder
import dev.anydm.desktop.theme.LocalTokens
import dev.anydm.desktop.theme.isMac
import dev.anydm.model.Task
import dev.anydm.model.TaskKind
import dev.anydm.model.TaskStatus
import dev.anydm.settings.SettingsStore
import dev.anydm.store.BulkAction
import dev.anydm.store.StoreEvent
import dev.anydm.store.TaskStore
import dev.anydm.store.Tone
import dev.anydm.store.removePrompt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.nio.file.Path
import java.util.Base64

private fun videosOf(task: Task): Int? = if (task.kind == TaskKind.PLAYLIST) task.entryCounts?.total ?: 0 else null

/** The list: add, filter, act, remove, and see how the connection and the disk stand. */
@Composable
fun MainScreen(
    store: TaskStore,
    settings: SettingsStore,
    fileUrl: (String, Int?) -> String,
    commands: Flow<Command> = emptyFlow(),
    onSignOut: () -> Unit,
) {
    val state by store.state.collectAsState()
    val prefs by settings.settings.collectAsState()
    val scope = rememberCoroutineScope()
    val banners = remember { BannerQueue(System::currentTimeMillis) }
    var link by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    val linkFocus = remember { FocusRequester() }
    var sidebarWidth by remember { mutableStateOf(190.dp) }
    var removing by remember { mutableStateOf<List<Task>>(emptyList()) }
    var selection by remember { mutableStateOf(Selection()) }
    var linkFocused by remember { mutableStateOf(false) }
    val keys = remember { FocusRequester() }
    var clearing by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    val saver = remember { Saver() }
    // A card's save line while it runs: "Saving 2 of 3… 40%".
    var saving by remember { mutableStateOf(mapOf<String, String>()) }
    // The clock retry countdowns read, ticked once a second (web: `store.now`).
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }
    LaunchedEffect(store) {
        store.events.collect { event -> if (event is StoreEvent.Said) banners.push(Banner(event.notice.tone, event.notice.message)) }
    }

    // Read fresh each time: commands arrive from a flow collected once.
    fun order() = visibleOrder(state.tasks, state.entries, state.filter)

    fun chosen(): List<Task> {
        val byId = (state.tasks + state.entries.values.flatten()).associateBy { it.id }
        return order().filter { it in selection.ids }.mapNotNull(byId::get)
    }

    val order = order()
    LaunchedEffect(order) { selection = selection.prune(order) }
    LaunchedEffect(Unit) { keys.requestFocus() }
    LaunchedEffect(removing, clearing) { if (removing.isEmpty() && !clearing) keys.requestFocus() }

    fun remove(tasks: List<Task>) {
        if (tasks.isEmpty()) return
        if (prefs.confirmBeforeRemove) {
            removing = tasks
        } else {
            scope.launch {
                tasks.forEach { store.remove(it.id, deleteFiles = !removePrompt(it.status, videosOf(it)).canKeepFiles) }
            }
        }
    }

    fun save(task: Task) {
        val targets = saveTargets(task, fileUrl)
        scope.launch {
            var last: Path? = null
            try {
                targets.forEachIndexed { i, target ->
                    last =
                        saver.save(target, downloadsDir()) { done, total ->
                            val percent = total?.takeIf { it > 0 }?.let { " ${done * 100 / it}%" } ?: ""
                            val which = if (targets.size > 1) " ${i + 1} of ${targets.size}" else ""
                            saving = saving + (task.id to "Saving$which…$percent")
                        }
                }
                saving = saving - task.id
                val where = last
                val shown = if (targets.size == 1) "Saved ${where?.fileName}" else "Saved ${targets.size} files"
                banners.push(Banner(Tone.SUCCESS, shown, action = "Reveal") { where?.let(::revealFile) })
            } catch (error: Exception) {
                saving = saving - task.id
                banners.push(Banner(Tone.ERROR, "Couldn't save ${task.title}: ${error.message ?: "the transfer failed"}"))
            }
        }
    }

    fun addTorrentFile(file: File) {
        scope.launch { store.addTorrent(Base64.getEncoder().encodeToString(file.readBytes())) }
    }

    fun submitLink() {
        val value = link.trim()
        if (value.isEmpty() || adding) return
        adding = true
        scope.launch {
            // A magnet is a torrent; anything else is looked at first, as the web's add box does.
            val added = if (value.startsWith("magnet:")) store.addTorrent(value) else store.add(value, prefs.defaultPreset)
            if (added) link = ""
            adding = false
        }
    }

    fun chooseTorrent() {
        val dialog =
            FileDialog(null as Frame?, "Add a .torrent", FileDialog.LOAD).apply {
                setFilenameFilter { _, name -> name.endsWith(".torrent") }
            }
        dialog.isVisible = true
        val name = dialog.file
        if (name != null) addTorrentFile(File(dialog.directory, name))
    }

    fun act(
        task: Task,
        action: CardAction,
    ) {
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

            CardAction.PLAY -> {
                val index = task.files?.firstOrNull { it.selected && isMedia(it.path) }?.index
                play(prefs.player, fileUrl(task.id, index))?.let { message ->
                    banners.push(Banner(Tone.ERROR, message))
                }
            }

            CardAction.SAVE -> {
                save(task)
            }

            CardAction.COPY_LINK -> {
                copyText(task.url)
            }

            CardAction.REMOVE -> {
                remove(listOf(task))
            }
        }
    }

    /** A menu or hover action: on every selected row that offers it when this row is selected, else on this row. */
    fun actOn(
        task: Task,
        action: CardAction,
    ) {
        val targets = if (task.id in selection.ids) chosen() else listOf(task)
        if (action == CardAction.REMOVE) {
            remove(targets.filter { CardAction.REMOVE in rowView(it, now).menu })
        } else {
            targets.filter { action in rowView(it, now).menu }.forEach { act(it, action) }
        }
    }

    fun run(command: Command) {
        val picked = chosen()
        val order = order()
        when (command) {
            Command.PAUSE_RESUME -> {
                bulkIntent(picked)?.let { intent -> intent.targets.forEach { act(it, intent.action) } }
            }

            Command.OPEN -> {
                picked.firstOrNull { CardAction.PLAY in rowView(it, now).menu }?.let { act(it, CardAction.PLAY) }
            }

            Command.SAVE -> {
                picked.filter { CardAction.SAVE in rowView(it, now).menu }.forEach { act(it, CardAction.SAVE) }
            }

            Command.REMOVE -> {
                remove(picked)
            }

            Command.COPY -> {
                if (picked.isNotEmpty()) copyText(picked.joinToString("\n") { it.url })
            }

            Command.PASTE -> {
                pastedText()?.trim()?.takeIf { it.startsWith("http") || it.startsWith("magnet:") }?.let {
                    link = it
                    submitLink()
                }
            }

            Command.FOCUS_LINK -> {
                linkFocus.requestFocus()
            }

            Command.OPEN_TORRENT -> {
                chooseTorrent()
            }

            Command.SETTINGS -> {
                showSettings = true
            }

            Command.RETRY -> {
                picked.filter { it.status == TaskStatus.FAILED }.forEach { act(it, CardAction.RETRY) }
            }

            Command.SELECT_ALL -> {
                selection = selection.all(order)
            }

            Command.CLEAR -> {
                selection = Selection()
            }

            Command.UP -> {
                selection = selection.move(-1, order, extend = false)
            }

            Command.DOWN -> {
                selection = selection.move(1, order, extend = false)
            }

            Command.EXTEND_UP -> {
                selection = selection.move(-1, order, extend = true)
            }

            Command.EXTEND_DOWN -> {
                selection = selection.move(1, order, extend = true)
            }
        }
    }

    LaunchedEffect(commands) { commands.collect { run(it) } }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(LocalTokens.current.content)
                .focusRequester(keys)
                .focusable()
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val command =
                        commandFor(event.key, event.isMetaPressed, event.isCtrlPressed, event.isShiftPressed, isMac(), linkFocused)
                    command?.let(::run)
                    command != null
                },
        ) {
            Column(Modifier.fillMaxSize()) {
                Toolbar(
                    inset = toolbarInset(),
                    link = link,
                    onLink = { link = it },
                    onSubmit = ::submitLink,
                    adding = adding,
                    preset = prefs.defaultPreset,
                    presets = PRESET_OPTIONS,
                    onPreset = { preset -> settings.update { it.copy(defaultPreset = preset) } },
                    focus = linkFocus,
                    onPauseAll = { scope.launch { store.bulk(BulkAction.PAUSE_ALL) } },
                    onResumeAll = { scope.launch { store.bulk(BulkAction.RESUME_ALL) } },
                    onTorrent = ::chooseTorrent,
                    onSettings = { showSettings = true },
                    onLinkFocus = { linkFocused = it },
                )
                Row(Modifier.weight(1f).torrentDrop(::addTorrentFile)) {
                    SourceList(
                        items = sourceItems(state.summary),
                        selected = state.filter,
                        onSelect = store::setFilter,
                        host = hostOf(prefs.serverUrl),
                        connection = state.connection,
                        onChangeServer = onSignOut,
                        onSettings = { showSettings = true },
                        onClearFinished = { clearing = true },
                        width = sidebarWidth,
                        onWidth = { sidebarWidth = it },
                    )
                    Column(
                        Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SortMenu(state.sort, store::setSort)
                        DownloadList(
                            tasks = state.tasks,
                            entries = state.entries,
                            now = now,
                            saving = saving,
                            filter = state.filter,
                            page = state.page,
                            totalPages = state.totalPages,
                            loadingMore = state.loadingMore,
                            onLoadMore = store::loadMore,
                            onExpand = store::expand,
                            onCollapse = store::collapse,
                            selection = selection.ids,
                            lead = selection.lead,
                            onPress = { id, gesture ->
                                selection = selection.apply(gesture, id, order())
                                keys.requestFocus()
                            },
                            onAction = ::actOn,
                        )
                    }
                }
                StatusBar(state.tasks, state.connection, state.disk, saving.values.lastOrNull())
            }
            BannerHost(banners, Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 32.dp))
        }

        if (removing.isNotEmpty()) {
            val prompt = removeManyPrompt(removing.map { removePrompt(it.status, videosOf(it)) })
            RemoveDialog(removeTitle(removing.map { it.title }), prompt, onCancel = { removing = emptyList() }) { deleteFiles ->
                val gone = removing
                removing = emptyList()
                selection = Selection()
                scope.launch { gone.forEach { store.remove(it.id, deleteFiles) } }
            }
        }
        if (showSettings) SettingsDialog(settings) { showSettings = false }
        if (clearing) {
            ClearFinishedDialog(onCancel = { clearing = false }) {
                clearing = false
                scope.launch { store.bulk(BulkAction.CLEAR_FINISHED) }
            }
        }
    }
}

@Composable
private fun SortMenu(
    sort: String,
    onSort: (String) -> Unit,
) {
    val t = LocalTokens.current
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.clip(RoundedCornerShape(5.dp)).clickable { open = true }.padding(horizontal = 6.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Sort by: ", fontSize = 12.sp, color = t.secondaryText)
            Text(SORT_OPTIONS.firstOrNull { it.first == sort }?.second ?: SORT_OPTIONS.first().second, fontSize = 12.sp, color = t.text)
            Icon(Glyphs.ChevronDown, null, Modifier.size(12.dp), tint = t.secondaryText)
        }
        DropdownMenu(open, { open = false }) {
            SORT_OPTIONS.forEach { (value, label) ->
                DropdownMenuItem(text = { Text(label) }, onClick = {
                    open = false
                    onSort(value)
                })
            }
        }
    }
}

private fun copyText(text: String) {
    java.awt.Toolkit
        .getDefaultToolkit()
        .systemClipboard
        .setContents(java.awt.datatransfer.StringSelection(text), null)
}

private fun pastedText(): String? =
    runCatching {
        java.awt.Toolkit
            .getDefaultToolkit()
            .systemClipboard
            .getData(java.awt.datatransfer.DataFlavor.stringFlavor) as? String
    }.getOrNull()
