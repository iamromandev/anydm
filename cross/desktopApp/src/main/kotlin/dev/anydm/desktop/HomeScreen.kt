package dev.anydm.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun HomeScreen(
    initial: HomeState,
    initialDark: Boolean,
    clipboardText: suspend () -> String? = { withContext(Dispatchers.IO) { systemClipboardText() } },
) {
    val downloads = remember { mutableStateListOf<DownloadItem>().apply { addAll(initial.downloads) } }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var filter by remember { mutableStateOf(Filter.All) }
    var unit by remember { mutableStateOf(SpeedUnit.MBps) }
    var slow by remember { mutableStateOf(false) }
    var dark by remember { mutableStateOf(initialDark) }
    val networks = remember { mutableStateListOf<Network>().apply { addAll(initial.networks) } }
    var networksOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var chipBounds by remember { mutableStateOf(Rect.Zero) }
    var menuBounds by remember { mutableStateOf(Rect.Zero) }
    var dialogOpen by remember { mutableStateOf(false) }
    var dialogInitial by remember { mutableStateOf("") }
    var limits by remember { mutableStateOf(initialLimits(initial.networks, sampleUsage)) }
    var limitsOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val homeFocus = remember { FocusRequester() }
    LaunchedEffect(dialogOpen, networksOpen, limitsOpen) { if (!dialogOpen && !networksOpen && !limitsOpen) homeFocus.requestFocus() }

    val state = initial.copy(downloads = downloads.toList())
    val visible = filtered(state, filter)
    val active = visible.filter { it.status != DownloadStatus.Finished }
    val finished = visible.filter { it.status == DownloadStatus.Finished }

    fun select(
        ids: List<String>,
        on: Boolean,
    ) {
        selected = if (on) selected + ids else selected - ids.toSet()
    }

    AnydmTheme(dark) {
        val c = colors

        @Composable
        fun rowFor(r: DownloadItem) =
            DownloadRow(
                r,
                r.id in selected,
                unit,
                { select(listOf(r.id), it) },
                {
                    val i = downloads.indexOfFirst { d -> d.id == r.id }
                    if (i >= 0) {
                        val next = if (r.status == DownloadStatus.Paused) DownloadStatus.Downloading else DownloadStatus.Paused
                        downloads[i] = r.copy(status = next)
                    }
                },
            )
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(networksOpen) {
                    if (networksOpen) {
                        awaitPointerEventScope {
                            while (true) {
                                val e = awaitPointerEvent(PointerEventPass.Initial)
                                val p = e.changes.firstOrNull { it.pressed && !it.previousPressed }?.position
                                if (p != null && !menuBounds.contains(p) && !chipBounds.contains(p)) networksOpen = false
                            }
                        }
                    }
                }.focusRequester(homeFocus)
                .onPreviewKeyEvent { e ->
                    val paste = e.type == KeyEventType.KeyDown && e.key == Key.V && (e.isCtrlPressed || e.isMetaPressed)
                    if (!paste || dialogOpen || limitsOpen || renaming) {
                        false
                    } else {
                        scope.launch {
                            val link = pasteLink(clipboardText(), dialogOpen || limitsOpen)
                            if (link != null) {
                                dialogInitial = link
                                networksOpen = false
                                dialogOpen = true
                            }
                        }
                        true
                    }
                }.focusable(),
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .background(c.bg)
                    .testTag("home-root")
                    .semantics { stateDescription = if (dark) "dark" else "light" },
            ) {
                TopBar(
                    filter,
                    { filter = it },
                    downloads.count { it.status == DownloadStatus.Downloading },
                    networks,
                    unit,
                    { networksOpen = !networksOpen && !dialogOpen && !limitsOpen },
                    { chipBounds = it },
                    {
                        if (!dialogOpen && !limitsOpen) {
                            dialogInitial = ""
                            networksOpen = false
                            dialogOpen = true
                        }
                    },
                )
                Box(Modifier.weight(1f)) {
                    if (visible.isEmpty()) {
                        if (downloads.isEmpty()) {
                            EmptyState("No downloads yet", "Paste a link or drop a file to start")
                        } else {
                            EmptyState("Nothing here", null)
                        }
                    } else {
                        LazyColumn(Modifier.fillMaxSize()) {
                            if (active.isNotEmpty()) {
                                item(key = "h-downloading") {
                                    SectionHeader(
                                        "downloading",
                                        "DOWNLOADING",
                                        active.size,
                                        active.all { it.id in selected },
                                        { select(active.map { r -> r.id }, it) },
                                    )
                                }
                                items(active, key = { it.id }) { r -> rowFor(r) }
                            }
                            if (finished.isNotEmpty()) {
                                item(key = "h-finished") {
                                    SectionHeader(
                                        "finished",
                                        "FINISHED",
                                        finished.size,
                                        finished.all { it.id in selected },
                                        { select(finished.map { r -> r.id }, it) },
                                        onClear = {
                                            val gone = downloads.filter { it.status == DownloadStatus.Finished }
                                            downloads.removeAll(gone)
                                            selected = selected - gone.map { it.id }.toSet()
                                        },
                                    )
                                }
                                items(finished, key = { it.id }) { r -> rowFor(r) }
                            }
                        }
                    }
                }
                Footer(initial.freeBytes, slow, { slow = it }, unit, { unit = it }, dark) { dark = !dark }
            }
            if (networksOpen && !dialogOpen && !limitsOpen) {
                val density = LocalDensity.current
                NetworksMenu(
                    networks,
                    { next ->
                        networks.clear()
                        networks.addAll(next)
                    },
                    unit,
                    { networksOpen = false },
                    {
                        networksOpen = false
                        limitsOpen = true
                    },
                    onEditingChange = { renaming = it },
                    modifier =
                        Modifier
                            .offset {
                                val width = with(density) { NetworksMenuWidth.roundToPx() }
                                val gap = with(density) { 6.dp.roundToPx() }
                                IntOffset((chipBounds.right.toInt() - width).coerceAtLeast(0), chipBounds.bottom.toInt() + gap)
                            }.onGloballyPositioned { menuBounds = it.boundsInRoot() },
                )
            }
            if (limitsOpen) {
                LimitsDialog(
                    limits,
                    networks.toList(),
                    unit,
                    slow,
                    onCancel = { limitsOpen = false },
                    onSave = { l, s ->
                        limits = l
                        slow = s
                        limitsOpen = false
                    },
                )
            }
            if (dialogOpen) {
                NewDownloadDialog(
                    dialogInitial,
                    networks.filter { it.enabled },
                    onCancel = { dialogOpen = false },
                    onDownload = { preview, ticked ->
                        downloads.add(newDownloadItem(preview, ticked, nextId(downloads)))
                        filter = Filter.All
                        dialogOpen = false
                    },
                )
            }
        }
    }
}
