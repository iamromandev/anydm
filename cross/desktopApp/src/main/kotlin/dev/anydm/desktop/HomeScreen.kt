package dev.anydm.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.draganddrop.dragAndDropTarget
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.Path

@OptIn(ExperimentalComposeUiApi::class, FlowPreview::class)
@Composable
fun HomeScreen(
    initial: HomeState,
    initialDark: Boolean,
    clipboardText: suspend () -> String? = { withContext(Dispatchers.IO) { systemClipboardText() } },
    files: FilesEnv = FilesEnv.inert(),
    bus: CommandBus = remember { CommandBus() },
    platform: Platform = Platform.current(),
    stores: AppStores = remember { AppStores.inMemory() },
) {
    val limits0 = remember { initialLimits(initial.networks, sampleUsage) }
    // Initial Queued rows wait in list order; settle starts them when slots are free, so the invariants hold from the start.
    val seeded =
        remember {
            settle(
                QueueState(initial.downloads, initial.downloads.filter { it.status == DownloadStatus.Queued }.map { it.id }),
                limits0.concurrency,
            )
        }
    val downloads = remember { mutableStateListOf<DownloadItem>().apply { addAll(seeded.list) } }
    var queue by remember { mutableStateOf(seeded.queue) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var filter by remember { mutableStateOf(Filter.All) }
    var unit by remember { mutableStateOf(SpeedUnit.MBps) }
    var slow by remember { mutableStateOf(false) }
    var themeChoice by remember { mutableStateOf(ThemeChoice.System) }
    // initialDark is the OS setting: it decides the theme only while the choice is System.
    val dark = resolveDark(themeChoice, initialDark)
    val networks = remember { mutableStateListOf<Network>().apply { addAll(initial.networks) } }
    var networksOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var chipBounds by remember { mutableStateOf(Rect.Zero) }
    var menuBounds by remember { mutableStateOf(Rect.Zero) }
    var rootHeight by remember { mutableStateOf(0) }
    var dialogOpen by remember { mutableStateOf(false) }
    var dialogInitial by remember { mutableStateOf("") }
    var dialogTorrentPath by remember { mutableStateOf<Path?>(null) }
    var notice by remember { mutableStateOf<Notice?>(null) }
    // Why a proxy password is not in the keychain, by network id. The password itself stays in memory for the session.
    val keychainNote = remember { mutableStateMapOf<String, String>() }
    // Keychain calls go one at a time, in the order they were asked for.
    val secretLock = remember { Mutex() }
    // Networks whose proxy the user changed after the start-up load: the keychain's password is older than that, so it is not filled in.
    val proxyEdited = remember { mutableSetOf<String>() }
    // Saving starts only after the load, so a state that was never loaded can not overwrite the file.
    var loaded by remember { mutableStateOf(false) }
    var saveNoticeShown by remember { mutableStateOf(false) }
    // The save folder for the next download; null means the default under home. It lasts for the session.
    var saveFolder by remember { mutableStateOf<String?>(null) }
    var limits by remember { mutableStateOf(limits0) }
    var settingsOpen by remember { mutableStateOf<SettingsSection?>(null) }
    var limitsPane by remember { mutableStateOf(GENERAL_PANE) }
    var openId by remember { mutableStateOf<String?>(null) }
    var filesOpen by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<Set<String>?>(null) }
    var menu by remember { mutableStateOf<RowMenuState?>(null) }
    var helpOpen by remember { mutableStateOf(false) }
    var pickOnStart by remember { mutableStateOf(false) }
    var focusedId by remember { mutableStateOf<String?>(null) }
    var filterOpen by remember { mutableStateOf(false) }
    val keys = remember(bus) { KeyTracker { bus.release() } }
    var rowMenuBounds by remember { mutableStateOf(Rect.Zero) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val homeFocus = remember { FocusRequester() }
    val open = openId?.let { id -> downloads.firstOrNull { it.id == id } }
    LaunchedEffect(open == null) {
        if (openId != null && open == null) openId = null
        if (open == null) filesOpen = false else filterOpen = false
    }
    // Back on Home when a dialog, the detail view or a menu closes. The help overlay never moves the focus, so it has no part here.
    LaunchedEffect(dialogOpen, networksOpen, settingsOpen, removing, menu, open == null) {
        if (!dialogOpen && !networksOpen && removing == null && menu == null && !helpOpen) homeFocus.requestFocus()
    }
    // Held keys may have lost their KeyUp (macOS sends none for a letter under Command; a dialog, a popup or another window took it).
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(windowFocused) { keys.clear() }
    // Layers inside this tree (dialogs, row menu, overlay, detail) keep the tracker, or a held Esc would look fresh after it closed one.
    // Popups and the native picker take the keys away from the tree, so their KeyUps may be missing.
    LaunchedEffect(networksOpen, filesOpen, filterOpen) {
        keys.clear()
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .drop(1)
            .collect { menu = null }
    }
    val networkList by remember { derivedStateOf { networks.toList() } }

    // Commands run later than they were composed, so they read the state as it is at that moment, not these snapshots.
    fun liveQueue() = QueueState(downloads.toList(), queue)

    fun liveVisible() = filtered(initial.copy(downloads = downloads.toList()), filter)

    fun liveTargets(): List<DownloadItem> {
        val visibleNow = liveVisible()
        val openNow = openId?.let { id -> downloads.firstOrNull { it.id == id } }
        val selectedNow = selected intersect visibleNow.map { it.id }.toSet()
        val focusedNow = visibleNow.firstOrNull { it.id == focusedId }
        return when {
            openNow != null -> listOf(openNow)
            selectedNow.isNotEmpty() -> targetsOf(visibleNow, selectedNow)
            focusedNow != null -> listOf(focusedNow)
            else -> emptyList()
        }
    }

    fun modalNow() = dialogOpen || removing != null || helpOpen

    fun menuNow() = menu != null || networksOpen || filesOpen || filterOpen

    fun liveContext(): CommandContext {
        val visibleNow = liveVisible()
        val ids = visibleNow.map { it.id }.toSet()
        return CommandContext(
            modalNow(),
            menuNow(),
            openId?.let { id -> downloads.any { it.id == id } } == true,
            liveTargets(),
            visibleNow.size,
            (selected intersect ids).isNotEmpty(),
            filter,
            unit,
            renaming,
            settingsOpen != null,
        )
    }

    val state = initial.copy(downloads = downloads.toList())
    val limit = limits.concurrency
    val queueState = QueueState(state.downloads, queue)
    val visible = filtered(state, filter)
    val active = visible.filter { it.status == DownloadStatus.Downloading || it.status == DownloadStatus.Paused }
    val queuedRows = queue.mapNotNull { id -> visible.firstOrNull { it.id == id && it.status == DownloadStatus.Queued } }
    val finished = visible.filter { it.status == DownloadStatus.Finished }
    // Only selected rows the filter shows count and are acted on; hidden ones stay selected for later.
    val visibleIds = visible.map { it.id }.toSet()
    val liveSelected = selected intersect visibleIds
    // List commands act on the visible selection, else the focused row, else nothing; in the detail view on the open download.
    val focusedRow = visible.firstOrNull { it.id == focusedId }
    val targets = liveTargets()
    val modalOpen = modalNow()
    val menuOpen = menuNow()
    // A hidden or removed row may have held the focus, which would leave every key press without a target.
    // Only then does Home take the focus back: never from a control the user is on, a detail view, a dialog or a menu.
    LaunchedEffect(focusedRow == null, filter, visible.size) {
        if (focusedRow == null) {
            val lost = focusedId != null
            focusedId = null
            if (lost && !modalOpen && !menuOpen && open == null) homeFocus.requestFocus()
        }
    }

    fun select(
        ids: List<String>,
        on: Boolean,
    ) {
        selected = if (on) selected + ids else selected - ids.toSet()
    }

    // Ticking with the mouse leaves the key focus on Home, so Space then acts on the selection, not on the checkbox.
    // Reaching the box with Tab and pressing Space keeps the focus where it is.
    val focusHome: () -> Unit = { homeFocus.requestFocus() }

    // Drops are only taken when nothing else is in front: no dialog, no menu, no detail view.
    val dropsEnabled = !dialogOpen && !networksOpen && removing == null && menu == null && open == null && settingsOpen == null && !helpOpen
    val dropsEnabledNow by rememberUpdatedState(dropsEnabled)
    val dropTarget =
        remember(files) {
            object : DragAndDropTarget {
                override fun onEntered(event: DragAndDropEvent) = files.drops.hover(true)

                override fun onExited(event: DragAndDropEvent) = files.drops.hover(false)

                override fun onEnded(event: DragAndDropEvent) = files.drops.hover(false)

                override fun onDrop(event: DragAndDropEvent): Boolean {
                    files.drops.drop(dropItemsOf(event.awtTransferable))
                    return true
                }
            }
        }
    LaunchedEffect(files) {
        files.drops.drops.collect { items ->
            if (!dropsEnabledNow) return@collect
            when (val a = classifyDrop(items)) {
                is DropAction.Link -> {
                    pickOnStart = false
                    dialogInitial = a.text
                    dialogTorrentPath = null
                    notice = if (a.more) Notice(ONLY_FIRST) else null
                    dialogOpen = true
                }

                is DropAction.Torrent -> {
                    pickOnStart = false
                    dialogInitial = ""
                    dialogTorrentPath = a.path
                    notice = if (a.more) Notice(ONLY_FIRST) else null
                    dialogOpen = true
                }

                DropAction.Rejected -> {
                    notice = Notice(ONLY_LINKS_AND_TORRENTS)
                }
            }
        }
    }
    val shownNotice = notice
    LaunchedEffect(shownNotice) {
        if (shownNotice != null) {
            delay(shownNotice.ms)
            notice = null
        }
    }

    AnydmTheme(dark) {
        val c = colors

        // Every change of a status goes through QueueModel, so the queue and the list stay consistent.
        fun applyQueue(next: QueueState) {
            downloads.clear()
            downloads.addAll(next.list)
            queue = next.queue
        }

        fun persistedFile() = toFile(themeChoice, unit, saveFolder, limits.copy(slowOn = slow), networks.toList())

        // Keychain calls run on IO and never throw: a failure comes back as a result and becomes a note.
        // [afterCall] runs on IO right after the call: an exit waiting in AppStores.flush on the UI thread must not need the UI.
        suspend fun secretCall(
            afterCall: () -> Unit = {},
            block: suspend () -> SecretResult,
        ): SecretResult =
            withContext(Dispatchers.IO) {
                try {
                    block()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    SecretResult.Failed(KEYCHAIN_ERROR)
                } finally {
                    afterCall()
                }
            }

        fun saveProxySecret(
            id: String,
            proxy: Proxy,
        ) {
            // Counted before the launch, so an exit right after the click still waits for this write.
            val written = stores.secretWriteStarted()
            scope
                .launch {
                    secretLock.withLock {
                        val account = proxyAccount(id)
                        if (proxy.type != ProxyType.None && proxy.password.isNotEmpty()) {
                            val r = secretCall(written) { stores.secrets.set(account, proxy.password) }
                            if (r is SecretResult.Failed) keychainNote[id] = "Password not saved: ${r.reason}" else keychainNote.remove(id)
                        } else {
                            // No password to keep: the entry goes, and so does any note about it.
                            secretCall(written) { stores.secrets.delete(account) }
                            keychainNote.remove(id)
                        }
                    }
                }.invokeOnCompletion { written() }
        }

        // Every edit of the networks goes through here, so a changed proxy always reaches the keychain.
        fun commitNetworks(next: List<Network>) {
            val before = networks.toList()
            networks.clear()
            networks.addAll(next)
            next.forEach { n ->
                val old = before.firstOrNull { it.id == n.id }?.proxy
                if (old != null && old != n.proxy) {
                    proxyEdited += n.id
                    saveProxySecret(n.id, n.proxy)
                }
            }
        }

        LaunchedEffect(stores) {
            val result =
                try {
                    withContext(Dispatchers.IO) { stores.settings.load() }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    LoadResult.Reset("unreadable")
                }
            when (result) {
                LoadResult.Missing -> {
                    Unit
                }

                is LoadResult.Reset -> {
                    notice = Notice(SETTINGS_RESET, SETTINGS_NOTICE_MS)
                }

                is LoadResult.Loaded -> {
                    val r = fromFile(result.file, networks.toList(), limits0)
                    themeChoice = r.theme
                    unit = r.unit
                    saveFolder = r.saveFolder
                    limits = r.limits
                    slow = r.limits.slowOn
                    networks.clear()
                    networks.addAll(r.networks)
                    applyQueue(changeLimit(liveQueue(), r.limits.concurrency))
                    stores.loaded(toFile(r.theme, r.unit, r.saveFolder, r.limits, r.networks))
                }
            }
            loaded = true
            // Passwords live in the keychain: fetch them for every network that has a user name.
            if (result is LoadResult.Loaded) {
                networks.toList().filter { it.proxy.type != ProxyType.None && it.proxy.user.isNotEmpty() }.forEach { n ->
                    secretLock.withLock {
                        when (val r = secretCall { stores.secrets.get(proxyAccount(n.id)) }) {
                            is SecretResult.Ok -> {
                                val i = networks.indexOfFirst { it.id == n.id }
                                val password = r.value
                                // Whatever the user did to this proxy in the meantime is newer than the keychain's password.
                                if (password != null && i >= 0 && n.id !in proxyEdited && networks[i].proxy.password.isEmpty()) {
                                    networks[i] = networks[i].copy(proxy = networks[i].proxy.copy(password = password))
                                }
                            }

                            is SecretResult.Failed -> {
                                keychainNote[n.id] = "Couldn't read the saved password: ${r.reason}"
                            }
                        }
                    }
                }
            }
        }

        LaunchedEffect(loaded) {
            if (!loaded) return@LaunchedEffect
            snapshotFlow { persistedFile() }
                .drop(1)
                .map { stores.changed(it) }
                .debounce(SAVE_DEBOUNCE_MS)
                .collect { change ->
                    val r =
                        withContext(Dispatchers.IO) {
                            try {
                                stores.save(change)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                SaveResult.Failed("couldn't write the settings file")
                            }
                        }
                    if (r is SaveResult.Failed && !saveNoticeShown) {
                        saveNoticeShown = true
                        notice = Notice(SETTINGS_NOT_SAVED, SETTINGS_NOTICE_MS)
                    }
                }
        }

        fun togglePause(r: DownloadItem) {
            val ids = setOf(r.id)
            val q = liveQueue()
            val l = limits.concurrency
            applyQueue(if (r.status == DownloadStatus.Paused) resumeAll(q, ids, l) else pauseAll(q, ids, l))
        }

        fun removeIds(ids: Set<String>) {
            val next = removeAll(liveQueue(), ids, limits.concurrency)
            applyQueue(next)
            selected = prune(selected, next.list)
        }

        fun openNewDownload(pick: Boolean) {
            dialogInitial = ""
            dialogTorrentPath = null
            pickOnStart = pick
            menu = null
            networksOpen = false
            filesOpen = false
            filterOpen = false
            dialogOpen = true
        }

        fun openSettings(
            section: SettingsSection,
            networkId: String?,
        ) {
            menu = null
            networksOpen = false
            filesOpen = false
            filterOpen = false
            openId = null
            limitsPane = networkId ?: GENERAL_PANE
            settingsOpen = section
        }

        // Every command, from a key or from the menu, ends up here and calls an action that already exists.
        fun runCommand(id: CommandId) {
            val targets = liveTargets()
            val ids = targets.ids()
            val queueState = liveQueue()
            val limit = limits.concurrency
            when (id) {
                CommandId.NewDownload -> {
                    openNewDownload(false)
                }

                CommandId.OpenTorrent -> {
                    openNewDownload(true)
                }

                CommandId.Settings -> {
                    // Already open: stay where you are, so a draft is not lost to a key press.
                    if (settingsOpen == null) openSettings(SettingsSection.General, null)
                }

                CommandId.SelectAll -> {
                    selected = selected + liveVisible().map { it.id }
                }

                CommandId.ClearSelection -> {
                    selected = emptySet()
                }

                CommandId.Pause -> {
                    applyQueue(pauseAll(queueState, ids, limit))
                }

                CommandId.Resume -> {
                    applyQueue(resumeAll(queueState, ids, limit))
                }

                CommandId.TogglePause -> {
                    when (spaceAction(targets)) {
                        CommandId.Pause -> applyQueue(pauseAll(queueState, ids, limit))
                        CommandId.Resume -> applyQueue(resumeAll(queueState, ids, limit))
                        else -> Unit
                    }
                }

                CommandId.Remove -> {
                    removing = ids
                }

                CommandId.FilterAll -> {
                    filter = Filter.All
                }

                CommandId.FilterDownloading -> {
                    filter = Filter.Downloading
                }

                CommandId.FilterFinished -> {
                    filter = Filter.Finished
                }

                CommandId.ToggleTheme -> {
                    themeChoice = if (dark) ThemeChoice.Light else ThemeChoice.Dark
                }

                CommandId.ToggleUnit -> {
                    unit = if (unit == SpeedUnit.MBps) SpeedUnit.Mbps else SpeedUnit.MBps
                }

                CommandId.SetUnitMBps -> {
                    unit = SpeedUnit.MBps
                }

                CommandId.SetUnitMbps -> {
                    unit = SpeedUnit.Mbps
                }

                CommandId.Shortcuts -> {
                    helpOpen = true
                }
            }
        }
        SideEffect {
            bus.context = liveContext()
            bus.contextSource = ::liveContext
            bus.handler = ::runCommand
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
                queuePosition = queuePosition(queueState, r.id),
                onFocusChange = { f ->
                    if (f) {
                        focusedId = r.id
                    } else if (focusedId == r.id && liveVisible().any { it.id == r.id }) {
                        // A row that is gone from the list keeps its id, so Home can tell the focus was lost with it.
                        focusedId = null
                    }
                },
                onPointerSelect = focusHome,
            )
        Box(
            Modifier
                .fillMaxSize()
                .onSizeChanged { rootHeight = it.height }
                .dragAndDropTarget(shouldStartDragAndDrop = { dropsEnabledNow }, target = dropTarget)
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
                    keys.track(e.key, e.type)
                    if (helpOpen) {
                        // The overlay takes no focus (a dialog under it would not let go of it), so it takes every key here:
                        // Esc closes it and nothing reaches the control that still holds the focus below.
                        if (down && e.key == Key.Escape) helpOpen = false
                        true
                    } else if (down && e.key == Key.Escape && menu != null && !dialogOpen && removing == null) {
                        menu = null
                        true
                    } else if (down && e.key == Key.Escape && settingsOpen != null && !dialogOpen && !renaming && removing == null) {
                        settingsOpen = null
                        true
                    } else if (down && e.key == Key.Escape && open != null && !networksOpen && !dialogOpen && !renaming &&
                        removing == null
                    ) {
                        if (filesOpen) filesOpen = false else openId = null
                        true
                    } else if (!paste || dialogOpen || settingsOpen != null || renaming || open != null || removing != null || helpOpen) {
                        // Mod commands. They are gated by the bus, so a dialog or a menu in front keeps them out.
                        // A key the bus refused goes on to the focused control: Mod+A belongs to a text field in a dialog.
                        val id =
                            if (down) {
                                matchKey(
                                    e.key,
                                    e.isMetaPressed,
                                    e.isCtrlPressed,
                                    e.isShiftPressed,
                                    platform,
                                    e.isAltPressed,
                                )
                            } else {
                                null
                            }
                        when {
                            id == null -> false

                            // No repeat guard: macOS sends no letter KeyUp under Command, so the bus's toggle lockout covers repeats.
                            else -> bus.run(id) || consumes(id, bus.current)
                        }
                    } else {
                        scope.launch {
                            val link = pasteLink(clipboardText(), dialogOpen || removing != null)
                            if (link != null) {
                                dialogInitial = link
                                dialogTorrentPath = null
                                pickOnStart = false
                                menu = null
                                networksOpen = false
                                dialogOpen = true
                            }
                        }
                        true
                    }
                }
                // Bare keys (Space, Delete, Backspace, Esc) bubble up, so a control that wants the key gets it first.
                // Dialogs and menus also keep them out through the bus.
                .onKeyEvent { e ->
                    val id =
                        if (e.type == KeyEventType.KeyDown) {
                            matchBareKey(e.key, e.isShiftPressed, e.isAltPressed, e.isMetaPressed, e.isCtrlPressed)
                        } else {
                            null
                        }
                    when {
                        id == null -> false
                        keys.repeating -> enabled(id, bus.current)
                        else -> bus.run(id, dedupe = false)
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
                if (settingsOpen != null) {
                    SettingsScreen(
                        section = settingsOpen ?: SettingsSection.General,
                        onSection = { settingsOpen = it },
                        onBack = { settingsOpen = null },
                        folder = displayFolder(saveFolder ?: defaultFolder(files.home), files.home),
                        onChangeFolder = {
                            files.picker.pickFolder(saveFolder ?: defaultFolder(files.home))?.let { saveFolder = it }
                        },
                        themeChoice = themeChoice,
                        onTheme = { themeChoice = it },
                        unit = unit,
                        onUnit = { unit = it },
                        limits = limits,
                        slow = slow,
                        limitsPane = limitsPane,
                        onLimitsPane = { limitsPane = it },
                        onSaveLimits = { l, s ->
                            limits = l
                            applyQueue(changeLimit(liveQueue(), l.concurrency))
                            slow = s
                        },
                        networks = networkList,
                        onNetworksChange = ::commitNetworks,
                        onEditingChange = { renaming = it },
                        modifier = Modifier.weight(1f),
                        keychainNotes = keychainNote,
                    )
                } else if (open != null) {
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
                            removeIds(setOf(open.id))
                            filesOpen = false
                            openId = null
                        },
                        onRemove = {
                            removeIds(setOf(open.id))
                            filesOpen = false
                            openId = null
                        },
                        queuePosition = queuePosition(queueState, open.id),
                        onStartNow = { applyQueue(startNow(queueState, setOf(open.id))) },
                        home = files.home,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    TopBar(
                        filter,
                        { filter = it },
                        filterOpen,
                        { filterOpen = it },
                        downloads.count { it.status == DownloadStatus.Downloading },
                        networks,
                        unit,
                        {
                            menu = null
                            networksOpen = !networksOpen && !dialogOpen && removing == null
                        },
                        { chipBounds = it },
                        {
                            if (!dialogOpen && removing == null) {
                                dialogInitial = ""
                                dialogTorrentPath = null
                                pickOnStart = false
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
                            { applyQueue(pauseAll(queueState, liveSelected, limit)) },
                            { applyQueue(resumeAll(queueState, liveSelected, limit)) },
                            { removing = liveSelected },
                            { selected = emptySet() },
                        )
                    }
                    Box(Modifier.weight(1f)) {
                        if (visible.isEmpty()) {
                            if (downloads.isEmpty()) {
                                EmptyState("No downloads yet", "Paste a link, drop a .torrent, or press New download") {
                                    if (dropsEnabled) {
                                        dialogInitial = ""
                                        dialogTorrentPath = null
                                        pickOnStart = false
                                        dialogOpen = true
                                    }
                                }
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
                                            onPointerSelect = focusHome,
                                        )
                                    }
                                    items(active, key = { it.id }) { r -> rowFor(r) }
                                }
                                if (queuedRows.isNotEmpty()) {
                                    item(key = "h-queued") {
                                        SectionHeader(
                                            "queued",
                                            "QUEUED",
                                            queuedRows.size,
                                            queuedRows.all { it.id in selected },
                                            { select(queuedRows.map { r -> r.id }, it) },
                                            onPointerSelect = focusHome,
                                        )
                                    }
                                    items(queuedRows, key = { it.id }) { r -> rowFor(r) }
                                }
                                if (finished.isNotEmpty()) {
                                    item(key = "h-finished") {
                                        SectionHeader(
                                            "finished",
                                            "FINISHED",
                                            finished.size,
                                            finished.all { it.id in selected },
                                            { select(finished.map { r -> r.id }, it) },
                                            onPointerSelect = focusHome,
                                            onClear = {
                                                removeIds(downloads.filter { it.status == DownloadStatus.Finished }.map { it.id }.toSet())
                                            },
                                        )
                                    }
                                    items(finished, key = { it.id }) { r -> rowFor(r) }
                                }
                            }
                        }
                    }
                }
                Footer(initial.freeBytes, slow, { slow = it }, unit, { unit = it }, dark) {
                    themeChoice = if (dark) ThemeChoice.Light else ThemeChoice.Dark
                }
            }
            if (networksOpen && !dialogOpen && removing == null) {
                val density = LocalDensity.current
                NetworksMenu(
                    networks,
                    ::commitNetworks,
                    unit,
                    { networksOpen = false },
                    { section, networkId -> openSettings(section, networkId) },
                    onEditingChange = { renaming = it },
                    keychainNotes = keychainNote,
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
                } else if (!dialogOpen && removing == null) {
                    RowMenu(
                        m,
                        menuActions(menuTargets, queue),
                        onAction = { a ->
                            when (a) {
                                MenuAction.Pause -> applyQueue(pauseAll(queueState, menuTargets.ids(), limit))
                                MenuAction.Resume -> applyQueue(resumeAll(queueState, menuTargets.ids(), limit))
                                MenuAction.Remove -> removing = menuTargets.ids()
                                MenuAction.MoveToTop -> applyQueue(moveToTop(queueState, menuTargets.ids()))
                                MenuAction.MoveUp -> applyQueue(moveUp(queueState, menuTargets.ids()))
                                MenuAction.MoveDown -> applyQueue(moveDown(queueState, menuTargets.ids()))
                                MenuAction.StartNow -> applyQueue(startNow(queueState, menuTargets.ids()))
                                MenuAction.Open, MenuAction.ShowInFolder -> Unit
                            }
                            menu = null
                        },
                        onBounds = { rowMenuBounds = it },
                    )
                }
            }
            if (dialogOpen) {
                NewDownloadDialog(
                    dialogInitial,
                    networks.filter { it.enabled },
                    onCancel = { dialogOpen = false },
                    onDownload = { preview, ticked, folder ->
                        applyQueue(addDownload(queueState, newDownloadItem(preview, ticked, nextId(downloads), folder), limit))
                        saveFolder = folder
                        filter = Filter.All
                        dialogOpen = false
                    },
                    files = files,
                    initialTorrentPath = dialogTorrentPath,
                    openPickerOnStart = pickOnStart,
                    saveFolder = saveFolder ?: defaultFolder(files.home),
                    startsAfter = startsAfter(queueState, limit),
                    onProxySave = { id, proxy -> commitNetworks(setProxy(networks.toList(), id, proxy)) },
                    keychainNotes = keychainNote,
                )
            }
            removing?.let { ids ->
                RemoveDialog(
                    ids.size,
                    onRemove = {
                        removeIds(ids)
                        removing = null
                    },
                    onCancel = { removing = null },
                )
            }
            if (helpOpen) ShortcutsOverlay(platform, onClose = { helpOpen = false })
            if (dropsEnabled && files.drops.hovering) DropOverlay()
            shownNotice?.let { NoticeBar(it.text, Modifier.align(Alignment.BottomCenter)) }
        }
    }
}

/** A transient bar message. Identity matters: a repeat of the same text still restarts the timer. */
private class Notice(
    val text: String,
    val ms: Long = DROP_MESSAGE_MS,
)

private const val DROP_MESSAGE_MS = 3_000L
private const val SETTINGS_NOTICE_MS = 6_000L
private const val SAVE_DEBOUNCE_MS = 500L
private const val SETTINGS_RESET = "Settings couldn't be read and were reset"
private const val SETTINGS_NOT_SAVED = "Settings couldn't be saved; changes last until you quit"
private const val ONLY_FIRST = "Only the first item was added"
private const val ONLY_LINKS_AND_TORRENTS = "Only links and .torrent files can be added"

private fun List<DownloadItem>.ids(): Set<String> = map { it.id }.toSet()

private val NETWORKS_MENU_GAP = 6.dp
private val NETWORKS_MENU_MARGIN = 12.dp
