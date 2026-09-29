package dev.anydm.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import dev.anydm.desktop.list.DownloadList
import dev.anydm.desktop.theme.LocalTokens
import dev.anydm.model.Task
import dev.anydm.model.TaskKind
import dev.anydm.settings.SettingsStore
import dev.anydm.store.BulkAction
import dev.anydm.store.StoreEvent
import dev.anydm.store.TaskStore
import dev.anydm.store.Tone
import dev.anydm.store.removePrompt
import kotlinx.coroutines.delay
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
    var removing by remember { mutableStateOf<Task?>(null) }
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

    fun remove(task: Task) {
        if (prefs.confirmBeforeRemove) {
            removing = task
        } else {
            scope.launch { store.remove(task.id, deleteFiles = !removePrompt(task.status, videosOf(task)).canKeepFiles) }
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

    Box(Modifier.fillMaxSize().background(LocalTokens.current.content)) {
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
                Column(Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    ) { task, action ->
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
                                remove(task)
                            }
                        }
                    }
                }
            }
            StatusBar(state.tasks, state.connection, state.disk, saving.values.lastOrNull())
        }
        BannerHost(banners, Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 32.dp))
    }

    removing?.let { task ->
        RemoveDialog(task.title, removePrompt(task.status, videosOf(task)), onCancel = { removing = null }) { deleteFiles ->
            removing = null
            scope.launch { store.remove(task.id, deleteFiles) }
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
