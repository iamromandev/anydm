package dev.anydm.desktop

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.anydm.engine.DownloadSnapshot
import dev.anydm.engine.core.DownloadStatus
import java.awt.Cursor

/** Panel sizes of the bottom detail panel. */
internal object PanelTokens {
    val Default = 280.dp
    val Min = 160.dp
    val KeepListMin = 240.dp
    val Handle = 10.dp
}

/** [requested] kept within [min] and what [available] leaves after [keepListMin] for the list; the minimum wins when both cannot hold. */
internal fun clampPanelHeight(
    requested: Dp,
    available: Dp,
    min: Dp = PanelTokens.Min,
    keepListMin: Dp = PanelTokens.KeepListMin,
): Dp = requested.coerceIn(min, maxOf(min, available - keepListMin))

/** The height after dragging the handle [dragDown] (negative = up, which makes the panel taller). */
internal fun draggedPanelHeight(
    current: Dp,
    dragDown: Dp,
    available: Dp,
): Dp = clampPanelHeight(current - dragDown, available)

/** The downloads section: the card list, and the selected job's details in a resizable panel below it. */
@Composable
internal fun Downloads(
    state: UiState,
    filter: Filter,
    model: AppModel,
    focusList: () -> Unit,
    panelHeight: MutableState<Dp>,
) {
    Column(Modifier.fillMaxSize()) {
        NetworksStrip(model.strip) { model.typing = it }
        LinkInput(model)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val available = maxHeight - PanelTokens.Handle
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        state.rows.isNotEmpty() -> List(state, model, focusList)
                        state.counts.of(filter) == 0 -> Placeholder("Nothing here yet.", "empty")
                        else -> Placeholder("No downloads match.", "no-match")
                    }
                }
                state.detail?.let { detail ->
                    // Keyed on the job, so a drag in progress ends when the selection moves on.
                    key(detail.download.id) {
                        ResizeHandle { dragDown ->
                            // From the height as shown, so a window that shrank does not make the first drag jump.
                            panelHeight.value = draggedPanelHeight(clampPanelHeight(panelHeight.value, available), dragDown, available)
                        }
                    }
                    DetailPane(detail, model) { clampPanelHeight(panelHeight.value, available) }
                }
            }
        }
        Dialogs(state.dialog, model)
    }
}

/** The bar between the list and the detail panel; [onDrag] gets the vertical movement in dp (down is positive). */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ResizeHandle(onDrag: (Dp) -> Unit) {
    val density = LocalDensity.current
    val current by rememberUpdatedState(onDrag)
    Box(
        Modifier
            .fillMaxWidth()
            .height(PanelTokens.Handle)
            .pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR)))
            .draggable(
                orientation = Orientation.Vertical,
                state = rememberDraggableState { delta -> current(with(density) { delta.toDp() }) },
            ).semantics { contentDescription = "Resize detail panel" }
            .testTag("detail-handle"),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .width(40.dp)
                .height(4.dp)
                .background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(2.dp)),
        )
    }
}

@Composable
private fun List(
    state: UiState,
    model: AppModel,
    focusList: () -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize().testTag("list"),
        contentPadding = PaddingValues(Space.md),
        verticalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        items(state.rows, key = { it.id.value }) { row ->
            ListRow(row, row.id in state.selection, state, model, focusList)
        }
    }
}

/** The most room a card gives its share label in the title line. */
private val ShareLabelMaxWidth = 220.dp

/** The line under a card's name: where the download comes from. */
private fun source(s: DownloadSnapshot): String = s.mirrors.firstOrNull()?.url ?: s.download.sourceUrl ?: s.download.fileName ?: ""

@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun ListRow(
    row: Row,
    selected: Boolean,
    state: UiState,
    model: AppModel,
    focusList: () -> Unit,
) {
    val s = row.snapshot
    val id = row.id.value
    var menu by remember { mutableStateOf(false) }
    val dark = LocalDark.current
    val name = displayName(s)
    Box(Modifier.padding(start = 20.dp * row.depth)) {
        Surface(
            shape = CardTokens.shape,
            color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = CardTokens.elevation,
            modifier =
                Modifier
                    .fillMaxWidth()
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
                    }.testTag("row-$id"),
        ) {
            Column(Modifier.padding(horizontal = Space.md, vertical = Space.sm), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.sm), verticalAlignment = Alignment.CenterVertically) {
                    if (row.expandable) {
                        Text(
                            if (row.expanded) "▾" else "▸",
                            modifier =
                                Modifier
                                    .onPointerEvent(PointerEventType.Press) { event ->
                                        // A right-click goes on to the row: select and menu.
                                        if (!event.buttons.isPrimaryPressed) return@onPointerEvent
                                        event.changes.forEach { it.consume() }
                                        model.toggleExpanded(row.id)
                                    }.testTag("expand-$id"),
                        )
                    }
                    Tip(name, "name-tip-$id", Modifier.weight(1f)) {
                        Text(
                            name,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().semantics { contentDescription = name }.testTag("name-$id"),
                        )
                    }
                    val names by model.networkNames.collectAsState()
                    val share = remember(s.networkShare, names) { shareView(s.networkShare, names) }
                    if (share.multiple) {
                        // In the title line, one line of text: the card keeps its height.
                        Tip("Received per network, as counted so far: ${share.label}", "share-tip-$id", Modifier) {
                            Text(
                                share.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = ShareLabelMaxWidth).testTag("row-share-$id"),
                            )
                        }
                    }
                    val badge = ProtocolColors.badge(s.download.kind.protocol, dark)
                    Text(
                        s.download.kind.protocol.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = badge.content,
                        modifier =
                            Modifier
                                .background(badge.container, MaterialTheme.shapes.small)
                                .padding(horizontal = Space.sm, vertical = 2.dp)
                                .testTag("badge-$id"),
                    )
                    Text(statusLabel(s), style = MaterialTheme.typography.labelMedium, modifier = Modifier.testTag("status-$id"))
                }
                s.items?.let { items ->
                    Text(
                        "${items.completed}/${items.total} done",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.testTag("items-$id"),
                    )
                }
                s.lastFailure?.message?.takeIf { s.download.status == DownloadStatus.FAILED }?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("error-$id"),
                    )
                }
                // Wraps at narrow widths instead of dropping anything.
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Space.md),
                    verticalArrangement = Arrangement.spacedBy(Space.xs),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    val from = source(s)
                    if (from.isNotBlank() && from != name) {
                        Tip(from, "source-tip-$id", Modifier.widthIn(max = 280.dp)) {
                            Text(
                                from,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.semantics { contentDescription = from }.testTag("source-$id"),
                            )
                        }
                    }
                    Row(Modifier.width(150.dp), verticalAlignment = Alignment.CenterVertically) {
                        val done = progress(s)
                        if (done != null) {
                            LinearProgressIndicator(progress = { done }, modifier = Modifier.weight(1f))
                            Text(
                                " ${(done * 100).toInt()}%",
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.testTag("progress-$id"),
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.weight(1f))
                        }
                    }
                    Text(
                        if (s.speed > 0) formatSpeed(s.speed) else "—",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.testTag("speed-$id"),
                    )
                    Text(eta(s) ?: "—", style = MaterialTheme.typography.labelMedium, modifier = Modifier.testTag("eta-$id"))
                    Text(
                        (s.total ?: s.download.size)?.let(::formatBytes) ?: "—",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.testTag("size-$id"),
                    )
                    Text(
                        "Priority ${s.download.priority}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("priority-$id"),
                    )
                }
            }
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

/** Shows [full] in a tooltip on hover, for text the card cuts short. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Tip(
    full: String,
    tag: String,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    TooltipArea(
        tooltip = {
            Surface(shape = MaterialTheme.shapes.small, tonalElevation = 4.dp, shadowElevation = 4.dp) {
                Text(
                    full,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(Space.sm).widthIn(max = 600.dp).testTag(tag),
                )
            }
        },
        modifier = modifier,
        content = content,
    )
}

@Composable
internal fun Placeholder(
    text: String,
    tag: String,
) {
    Box(Modifier.fillMaxSize().testTag(tag), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
