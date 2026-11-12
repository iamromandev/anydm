package dev.anydm.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.drop
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
    var rootHeight by remember { mutableStateOf(0) }
    var dialogOpen by remember { mutableStateOf(false) }
    var dialogInitial by remember { mutableStateOf("") }
    var limits by remember { mutableStateOf(initialLimits(initial.networks, sampleUsage)) }
    var limitsOpen by remember { mutableStateOf(false) }
    var limitsPane by remember { mutableStateOf(GENERAL_PANE) }
    var openId by remember { mutableStateOf<String?>(null) }
    var filesOpen by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<Set<String>?>(null) }
    var menu by remember { mutableStateOf<RowMenuState?>(null) }
    var rowMenuBounds by remember { mutableStateOf(Rect.Zero) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val homeFocus = remember { FocusRequester() }
    val open = openId?.let { id -> downloads.firstOrNull { it.id == id } }
    LaunchedEffect(open == null) {
        if (openId != null && open == null) openId = null
        if (open == null) filesOpen = false
    }
    LaunchedEffect(dialogOpen, networksOpen, limitsOpen, removing, menu, open == null) {
        if (!dialogOpen && !networksOpen && !limitsOpen && removing == null && menu == null) homeFocus.requestFocus()
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .drop(1)
            .collect { menu = null }
    }
    val networkList by remember { derivedStateOf { networks.toList() } }

    val state = initial.copy(downloads = downloads.toList())
    val visible = filtered(state, filter)
    val active = visible.filter { it.status != DownloadStatus.Finished }
    val finished = visible.filter { it.status == DownloadStatus.Finished }
    // Only selected rows the filter shows count and are acted on; hidden ones stay selected for later.
    val visibleIds = visible.map { it.id }.toSet()
    val liveSelected = selected intersect visibleIds

    fun select(
        ids: List<String>,
        on: Boolean,
    ) {
        selected = if (on) selected + ids else selected - ids.toSet()
    }

    AnydmTheme(dark) {
        val c = colors

        fun togglePause(r: DownloadItem) {
            val i = downloads.indexOfFirst { d -> d.id == r.id }
            if (i >= 0) {
                val next = if (r.status == DownloadStatus.Paused) DownloadStatus.Downloading else DownloadStatus.Paused
                downloads[i] = r.copy(status = next)
            }
        }

        fun applyList(next: List<DownloadItem>) {
            downloads.clear()
            downloads.addAll(next)
        }

        @Composable
        fun rowFor(r: DownloadItem) =
            DownloadRow(
                r,
                r.id in selected,
                unit,
                { select(listOf(r.id), it) },
                { togglePause(r) },
                {
                    networksOpen = false
                    filesOpen = false
                    openId = r.id
                },
                { p ->
                    if (r.id !in selected) selected = (selected - visibleIds) + r.id
                    networksOpen = false
                    menu = RowMenuState(targetsFor(liveSelected, r.id), p.x, p.y)
                },
            )
        Box(
            Modifier
                .fillMaxSize()
                .onSizeChanged { rootHeight = it.height }
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
                }.pointerInput(menu != null) {
                    if (menu != null) {
                        awaitPointerEventScope {
                            while (true) {
                                val e = awaitPointerEvent(PointerEventPass.Initial)
                                val p = e.changes.firstOrNull { it.pressed && !it.previousPressed }?.position
                                if (p != null && !rowMenuBounds.contains(p)) menu = null
                            }
                        }
                    }
                }.focusRequester(homeFocus)
                .onPreviewKeyEvent { e ->
                    val down = e.type == KeyEventType.KeyDown
                    val paste = down && e.key == Key.V && (e.isCtrlPressed || e.isMetaPressed)
                    if (down && e.key == Key.Escape && menu != null && !dialogOpen && !limitsOpen && removing == null) {
                        menu = null
                        true
                    } else if (down && e.key == Key.Escape && open != null && !networksOpen && !dialogOpen && !limitsOpen && !renaming &&
                        removing == null
                    ) {
                        if (filesOpen) filesOpen = false else openId = null
                        true
                    } else if (!paste || dialogOpen || limitsOpen || renaming || open != null || removing != null) {
                        false
                    } else {
                        scope.launch {
                            val link = pasteLink(clipboardText(), dialogOpen || limitsOpen || removing != null)
                            if (link != null) {
                                dialogInitial = link
                                menu = null
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
                if (open != null) {
                    DetailScreen(
                        open,
                        networkList,
                        unit,
                        filesOpen,
                        { filesOpen = it },
                        onBack = {
                            filesOpen = false
                            openId = null
                        },
                        onTogglePause = { togglePause(open) },
                        onCancel = {
                            downloads.removeAll { it.id == open.id }
                            selected = selected - open.id
                            filesOpen = false
                            openId = null
                        },
                        onRemove = {
                            downloads.removeAll { it.id == open.id }
                            selected = selected - open.id
                            filesOpen = false
                            openId = null
                        },
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    TopBar(
                        filter,
                        { filter = it },
                        downloads.count { it.status == DownloadStatus.Downloading },
                        networks,
                        unit,
                        {
                            menu = null
                            networksOpen = !networksOpen && !dialogOpen && !limitsOpen && removing == null
                        },
                        { chipBounds = it },
                        {
                            if (!dialogOpen && !limitsOpen && removing == null) {
                                dialogInitial = ""
                                menu = null
                                networksOpen = false
                                dialogOpen = true
                            }
                        },
                    )
                    if (liveSelected.isNotEmpty()) {
                        val targets = targetsOf(state.downloads, liveSelected)
                        SelectionBar(
                            liveSelected.size,
                            canPause(targets),
                            canResume(targets),
                            { applyList(pause(state.downloads, liveSelected)) },
                            { applyList(resume(state.downloads, liveSelected)) },
                            { removing = liveSelected },
                            { selected = emptySet() },
                        )
                    }
                    Box(Modifier.weight(1f)) {
                        if (visible.isEmpty()) {
                            if (downloads.isEmpty()) {
                                EmptyState("No downloads yet", "Paste a link or drop a file to start")
                            } else {
                                EmptyState("Nothing here", null)
                            }
                        } else {
                            LazyColumn(Modifier.fillMaxSize().testTag("download-list"), listState) {
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
                }
                Footer(initial.freeBytes, slow, { slow = it }, unit, { unit = it }, dark) { dark = !dark }
            }
            if (networksOpen && !dialogOpen && !limitsOpen && removing == null) {
                val density = LocalDensity.current
                NetworksMenu(
                    networks,
                    { next ->
                        networks.clear()
                        networks.addAll(next)
                    },
                    unit,
                    { networksOpen = false },
                    { pane ->
                        networksOpen = false
                        limitsPane = pane ?: GENERAL_PANE
                        limitsOpen = true
                    },
                    onEditingChange = { renaming = it },
                    maxHeight =
                        if (rootHeight > 0) {
                            with(density) { (rootHeight - chipBounds.bottom).toDp() } - NETWORKS_MENU_GAP - NETWORKS_MENU_MARGIN
                        } else {
                            Dp.Unspecified
                        },
                    modifier =
                        Modifier
                            .offset {
                                val width = with(density) { NetworksMenuWidth.roundToPx() }
                                val gap = with(density) { NETWORKS_MENU_GAP.roundToPx() }
                                IntOffset((chipBounds.right.toInt() - width).coerceAtLeast(0), chipBounds.bottom.toInt() + gap)
                            }.onGloballyPositioned { menuBounds = it.boundsInRoot() },
                )
            }
            menu?.let { m ->
                val menuTargets = targetsOf(visible, m.ids)
                if (menuTargets.isEmpty()) {
                    LaunchedEffect(m) { menu = null }
                } else if (!dialogOpen && !limitsOpen && removing == null) {
                    RowMenu(
                        m,
                        menuActions(menuTargets),
                        onAction = { a ->
                            when (a) {
                                MenuAction.Pause -> applyList(pause(state.downloads, menuTargets.ids()))
                                MenuAction.Resume -> applyList(resume(state.downloads, menuTargets.ids()))
                                MenuAction.Remove -> removing = menuTargets.ids()
                                MenuAction.Open, MenuAction.ShowInFolder -> Unit
                            }
                            menu = null
                        },
                        onBounds = { rowMenuBounds = it },
                    )
                }
            }
            if (limitsOpen) {
                LimitsDialog(
                    limits,
                    networkList,
                    unit,
                    slow,
                    onCancel = { limitsOpen = false },
                    initialPane = limitsPane,
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
                    onProxySave = { id, proxy ->
                        val next = setProxy(networks.toList(), id, proxy)
                        networks.clear()
                        networks.addAll(next)
                    },
                )
            }
            removing?.let { ids ->
                RemoveDialog(
                    ids.size,
                    onRemove = {
                        val next = remove(downloads.toList(), ids)
                        applyList(next)
                        selected = prune(selected, next)
                        removing = null
                    },
                    onCancel = { removing = null },
                )
            }
        }
    }
}

private fun List<DownloadItem>.ids(): Set<String> = map { it.id }.toSet()

private val NETWORKS_MENU_GAP = 6.dp
private val NETWORKS_MENU_MARGIN = 12.dp
