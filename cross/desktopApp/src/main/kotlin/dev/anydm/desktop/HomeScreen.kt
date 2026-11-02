package dev.anydm.desktop

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragData
import androidx.compose.ui.draganddrop.dragData
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import dev.anydm.engine.core.DownloadId
import kotlinx.coroutines.launch
import java.net.URI
import java.nio.file.Path

/**
 * The downloads list with its header, footer and dialogs. [onNewDownload] opens the new-download dialog, with the links or
 * `.torrent` paths to fill it in (none for a blank one); [onOpen] opens a download's page. The shortcuts (paste, select all,
 * Esc, Delete) are set on [keys], which the window feeds.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun HomeScreen(
    source: EngineSource,
    model: HomeModel,
    footer: FooterModel,
    onNewDownload: (List<String>) -> Unit,
    onOpen: (DownloadId) -> Unit,
    keys: WindowKeys = WindowKeys(),
    /** The networks menu and the rename popover it uses; none, and the pill does nothing. */
    networks: Pair<NetworksModel, NetworkEditor>? = null,
    /** Opens the Browsers (pairing) dialog. */
    onBrowsers: () -> Unit = {},
) {
    val colors = Plexo.colors
    val state by model.state.collectAsState()
    val footerState by footer.state.collectAsState()
    val scope = rememberCoroutineScope()
    var menuOpen by remember { mutableStateOf(false) }
    val swatches = LocalNetworkLooks.current.swatches(colors)
    val total = state.groups.sumOf { it.rows.size }
    val latest by rememberUpdatedState(state)
    val menuIsOpen by rememberUpdatedState(menuOpen)
    // The handler outlives a composition's lambdas: it asks for the newest one when a key comes.
    val newDownload by rememberUpdatedState(onNewDownload)
    DisposableEffect(keys, model, source) {
        val handler: (KeyEvent) -> Boolean = handler@{ event ->
            val now = latest
            // A menu or a dialog has its own keys.
            if (event.type != KeyEventType.KeyDown || menuIsOpen || now.confirmClear || now.confirmRemove) return@handler false
            val command = if (IsMac) event.isMetaPressed else event.isCtrlPressed
            when {
                command && event.key == Key.V -> {
                    // Not gated on a repeat: a key-up lost to the OS must not swallow the next paste, and a dialog for the same
                    // link is not opened twice.
                    scope.launch {
                        val link = clipboardLink(source.clipboard)
                        if (link != null) newDownload(listOf(link))
                    }
                    true
                }

                command && event.key == Key.A -> {
                    model.selectAll()
                    true
                }

                event.key == Key.Escape && now.selection.isNotEmpty() -> {
                    model.clearSelection()
                    true
                }

                (event.key == Key.Delete || event.key == Key.Backspace) && now.selection.isNotEmpty() -> {
                    // A held key repeats: one press asks once.
                    if (!keys.repeating) model.askRemoveSelected()
                    true
                }

                else -> {
                    false
                }
            }
        }
        keys.handler = handler
        onDispose { if (keys.handler === handler) keys.handler = null }
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(colors.bg)
            .dropTorrents { paths ->
                val torrents = droppedTorrents(paths)
                // Several torrents dropped together open one dialog.
                if (torrents.isNotEmpty()) onNewDownload(torrents)
                torrents.isNotEmpty()
            },
    ) {
        Column(Modifier.fillMaxSize()) {
            if (state.selection.isEmpty()) {
                HeaderBar(
                    state,
                    footerState.unit,
                    model::setFilter,
                    onNetworks = { networks?.first?.toggle() },
                    onMenuOpen = { menuOpen = it },
                    onNewDownload = { onNewDownload(emptyList()) },
                    onBrowsers = onBrowsers,
                    networksMenu = { networks?.let { (menu, editor) -> NetworksMenu(menu, editor, footerState.unit, keys) } },
                )
            } else {
                SelectionBar(state, total, model)
            }
            state.message?.let { Banner(it, model::dismissMessage) }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (state.empty) {
                    Empty.NO_DOWNLOADS -> {
                        EmptyState { onNewDownload(emptyList()) }
                    }

                    Empty.NOTHING_FOR_FILTER -> {
                        NothingForFilter(state.filter) { model.setFilter(HomeFilter.ALL) }
                    }

                    Empty.NONE -> {
                        LazyColumn(
                            Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 20.dp),
                        ) {
                            for (group in state.groups) {
                                item(key = "group-${group.kind}") {
                                    GroupHeader(group, model, canClear = state.canClearFinished)
                                }
                                items(group.rows, key = { it.id.value }) { row ->
                                    DownloadRow(
                                        row,
                                        footerState.unit,
                                        swatches,
                                        onSelect = { model.toggle(row.id) },
                                        onOpen = { onOpen(row.id) },
                                        onAction = { model.perform(row.id) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Footer(footerState, footer)
        }
        if (state.confirmClear) {
            ConfirmDialog(
                title = "Clear finished list?",
                body = "This takes ${downloads(state.clearableCount)} off the list. The files stay where they are.",
                confirmText = "Clear list",
                onConfirm = model::confirmClearFinished,
                onCancel = model::dismissClearFinished,
            )
        }
        if (state.confirmRemove) {
            ConfirmDialog(
                title = "Remove from list?",
                body =
                    "This takes ${downloads(state.selection.size)} off the list, stopping any that are still running. " +
                        "Files already on disk stay where they are.",
                confirmText = "Remove",
                onConfirm = model::confirmRemoveSelected,
                onCancel = model::dismissRemove,
                destructive = true,
            )
        }
    }
}

private fun downloads(count: Int) = if (count == 1) "1 download" else "$count downloads"

@Composable
private fun GroupHeader(
    group: HomeGroup,
    model: HomeModel,
    canClear: Boolean,
) {
    val colors = Plexo.colors
    Column(Modifier.fillMaxWidth().padding(top = 20.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PCheckbox(
                checked = group.allSelected,
                indeterminate = group.someSelected,
                onChange = { model.toggleGroup(group.kind) },
                label = "Select all ${group.kind.label.lowercase()} downloads",
            )
            Txt(group.kind.label.uppercase(), Plexo.type.groupLabel, color = colors.textTertiary)
            Txt(
                "${group.rows.size}",
                Plexo.type.groupLabel.copy(letterSpacing = TextUnit.Unspecified),
                color = colors.textTertiary,
            )
            Spacer(Modifier.weight(1f))
            if (group.kind == GroupKind.FINISHED && canClear) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(role = Role.Button, onClick = model::askClearFinished)
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                ) {
                    Txt("Clear finished list", Plexo.type.hint, color = colors.textSecondary)
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(colors.border))
    }
}

/** A line under the header for what a command could not do, with a way to dismiss it. */
@Composable
internal fun Banner(
    text: String,
    onDismiss: () -> Unit,
) {
    val colors = Plexo.colors
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.dangerBg)
            .padding(horizontal = 20.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Txt(text, Plexo.type.hint, Modifier.weight(1f), color = colors.danger)
        IconButton(Glyph.CLOSE, "Dismiss", onDismiss, tint = colors.danger)
    }
}

// Files dropped on the window: their paths go to [onDrop], which says whether it took them.
@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
internal fun Modifier.dropTorrents(onDrop: (List<String>) -> Boolean): Modifier =
    dragAndDropTarget(
        shouldStartDragAndDrop = { it.dragData() is DragData.FilesList },
        target =
            object : DragAndDropTarget {
                override fun onDrop(event: DragAndDropEvent): Boolean {
                    val files = (event.dragData() as? DragData.FilesList)?.readFiles().orEmpty()
                    val paths = files.mapNotNull { runCatching { Path.of(URI(it)).toString() }.getOrNull() }
                    return onDrop(paths)
                }
            },
    )
