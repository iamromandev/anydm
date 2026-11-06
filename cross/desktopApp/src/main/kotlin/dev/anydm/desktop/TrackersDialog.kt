package dev.anydm.desktop

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp

private val KeyEvent.isSubmit: Boolean
    get() = type == KeyEventType.KeyDown && key == Key.Enter && (if (IsMac) isMetaPressed else isCtrlPressed)

/**
 * The Trackers dialog of one torrent: its trackers (default ones first, then the Custom ones), and for a torrent that isn't
 * private a field for new ones, one link per line. Cmd+Enter (Ctrl+Enter elsewhere) adds, Escape and Close shut it, a click
 * outside does not. [onClose] runs for Escape and Close.
 */
@Composable
fun TrackersDialog(
    model: TrackersModel,
    onClose: () -> Unit,
) {
    val state by model.state.collectAsState()
    val colors = Plexo.colors
    val focus = remember { FocusRequester() }
    // The dialog takes the keys when there is no field to focus (a private torrent), so Escape still finds it.
    LaunchedEffect(state.private) { if (state.private) runCatching { focus.requestFocus() } }
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .focusRequester(focus)
            .focusTarget()
            .semantics { testTag = "trackers-scrim" }
            .onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) {
                    onClose()
                    true
                } else {
                    false
                }
            }.pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = 620.dp)
                .fillMaxWidth(0.92f)
                .clip(shape)
                .background(colors.bgSecondary)
                .border(0.5.dp, colors.border, shape)
                .pointerInput(Unit) { detectTapGestures { } }
                .semantics {
                    testTag = "trackers-dialog"
                    contentDescription = "Trackers"
                }.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Txt(state.heading, Plexo.type.menu)
            TrackerList(state)
            if (state.private) {
                Notice(PRIVATE_TRACKERS_NOTICE)
            } else {
                AddForm(state, model)
            }
            state.error?.let { Txt(it, Plexo.type.hint, color = colors.danger, maxLines = 3) }
            state.ignored?.let { Txt(it, Plexo.type.hint, color = colors.usb.text, maxLines = 3) }
            state.info?.let { Txt(it, Plexo.type.hint, color = colors.textSecondary, maxLines = 3) }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                PButton(
                    "Reannounce",
                    model::reannounce,
                    Modifier.semantics { testTag = "trackers-reannounce" },
                    enabled = state.canReannounce,
                )
                PButton("Close", onClose, kind = ButtonKind.PRIMARY)
            }
        }
    }
}

@Composable
private fun TrackerList(state: TrackersState) {
    val colors = Plexo.colors
    val message = state.loadMessage
    if (message != null) {
        Txt(
            message,
            Plexo.type.hint,
            color = if (state.load is TrackersLoad.Failed) colors.danger else colors.textSecondary,
            maxLines = 3,
        )
        return
    }
    val shape = RoundedCornerShape(9.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 240.dp)
            .clip(shape)
            .border(0.5.dp, colors.border, shape)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        state.rows.forEachIndexed { index, row -> TrackerRowView(index, row) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrackerRowView(
    index: Int,
    row: TrackerRow,
) {
    val colors = Plexo.colors
    Column(Modifier.fillMaxWidth().semantics { testTag = "tracker-$index" }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TooltipArea(
                tooltip = {
                    Box(Modifier.clip(RoundedCornerShape(5.dp)).background(colors.bgTertiary).padding(horizontal = 8.dp, vertical = 4.dp)) {
                        Txt(row.url, Plexo.type.detail, maxLines = 3)
                    }
                },
                modifier = Modifier.weight(1f, fill = false),
            ) {
                Txt(row.url, Plexo.type.detail)
            }
            if (row.custom) {
                val tag = RoundedCornerShape(4.dp)
                Box(
                    Modifier
                        .clip(tag)
                        .background(colors.usb.bg)
                        .border(0.5.dp, colors.usb.border, tag)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Txt("Custom", Plexo.type.badge, color = colors.usb.text)
                }
            }
        }
        val facts =
            listOfNotNull(
                row.status,
                row.seeds?.let { "$it seeds" },
                row.peers?.let { "$it peers" },
                row.next?.let { "next in $it" },
            ).joinToString(" · ")
        Txt(facts, Plexo.type.detail, color = colors.textTertiary)
    }
}

@Composable
private fun Notice(text: String) {
    val colors = Plexo.colors
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.usb.bg)
            .border(0.5.dp, colors.usb.border, shape)
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Txt(text, Plexo.type.hint, color = colors.usb.text, maxLines = 3)
    }
}

@Composable
private fun AddForm(
    state: TrackersState,
    model: TrackersModel,
) {
    val colors = Plexo.colors
    val focus = remember { FocusRequester() }
    // On open, and again when an add is over (a disabled field gives its focus up).
    LaunchedEffect(state.submitting) { if (!state.submitting) runCatching { focus.requestFocus() } }
    val shape = RoundedCornerShape(PlexoRadius)
    Txt("New trackers", Plexo.type.badge, color = colors.textTertiary)
    BasicTextField(
        value = state.input,
        onValueChange = model::setInput,
        enabled = !state.submitting,
        textStyle = Plexo.type.detail.copy(color = colors.text),
        cursorBrush = SolidColor(colors.accent),
        modifier =
            Modifier
                .fillMaxWidth()
                .height(88.dp)
                .clip(shape)
                .background(colors.inputBg)
                .border(0.5.dp, colors.borderStrong, shape)
                .focusRequester(focus)
                .semantics {
                    testTag = "trackers-input"
                    contentDescription = "New trackers"
                }.onPreviewKeyEvent {
                    if (it.isSubmit) {
                        model.submit()
                        true
                    } else {
                        false
                    }
                },
        decorationBox = { inner ->
            Box(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                if (state.input.isEmpty()) {
                    Txt("One link per line: udp://…, http://…", Plexo.type.detail, color = colors.textTertiary)
                }
                inner()
            }
        },
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        PButton(
            if (state.submitting) "Adding…" else "Add trackers",
            model::submit,
            Modifier.semantics { testTag = "trackers-add" },
            kind = ButtonKind.PRIMARY,
            enabled = state.canAdd,
        )
    }
}

/** Makes the model of the dialog for the torrent of [detail], with the commands running in the dialog's own scope. */
@Composable
internal fun rememberTrackersModel(detail: DetailModel): TrackersModel {
    val scope = rememberCoroutineScope()
    return remember(detail) { detail.newTrackersModel(scope) }
}
