package dev.anydm.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SectionHeader(
    key: String,
    title: String,
    count: Int,
    allSelected: Boolean,
    onSelectAll: (Boolean) -> Unit,
    onClear: (() -> Unit)? = null,
) {
    val c = colors
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().height(36.dp).padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CheckBox(allSelected, onSelectAll, "select-all-$key", "Select all $title")
            Spacer(Modifier.width(12.dp))
            Text(
                "$title $count",
                color = c.textSecondary,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                letterSpacing = 2.sp,
                maxLines = 1,
            )
            Spacer(Modifier.weight(1f))
            if (onClear != null) {
                Text(
                    "Clear finished list",
                    color = c.textSecondary,
                    fontSize = 13.sp,
                    maxLines = 1,
                    modifier =
                        Modifier
                            .testTag("clear-finished")
                            .clickable(role = Role.Button, onClick = onClear)
                            .padding(4.dp),
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.border))
    }
}

@Composable
fun DownloadRow(
    item: DownloadItem,
    selected: Boolean,
    unit: SpeedUnit,
    onSelect: (Boolean) -> Unit,
    onTogglePause: () -> Unit,
    onOpen: () -> Unit,
    onContextMenu: (Offset) -> Unit = {},
) {
    val c = colors
    val active = item.status != DownloadStatus.Finished
    val open by rememberUpdatedState(onOpen)
    val contextMenu by rememberUpdatedState(onContextMenu)
    var origin by remember { mutableStateOf(Offset.Zero) }
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier
            .testTag("row-${item.id}")
            .fillMaxWidth()
            .onGloballyPositioned { origin = it.positionInRoot() }
            // A secondary press (also on the checkbox, pause and chevron) is a right click on the row:
            // it is consumed on the way down so no primary handler below reacts to it.
            .pointerInput(item.id) {
                awaitPointerEventScope {
                    var secondary = false
                    while (true) {
                        val e = awaitPointerEvent(PointerEventPass.Initial)
                        val down = e.changes.firstOrNull { it.pressed && !it.previousPressed }
                        if (down != null) {
                            secondary = e.buttons.isSecondaryPressed
                            if (secondary) contextMenu(origin + down.position)
                        }
                        if (secondary) e.changes.forEach { it.consume() }
                        if (e.changes.none { it.pressed }) secondary = false
                    }
                }
            }.pointerInput(item.id) { detectTapGestures { open() } }
            .semantics {
                role = Role.Button
                onClick {
                    open()
                    true
                }
            }.onFocusChanged { focused = it.isFocused }
            .onKeyEvent { e ->
                val activate = e.key == Key.Enter || e.key == Key.NumPadEnter || e.key == Key.Spacebar
                if (focused && activate && e.type == KeyEventType.KeyDown) {
                    open()
                    true
                } else {
                    false
                }
            }.focusable()
            .background(if (selected) c.accent.copy(alpha = 0.10f) else Color.Transparent)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CheckBox(selected, onSelect, "select-${item.id}", "Select ${item.name}")
        Spacer(Modifier.width(12.dp))
        Box(
            Modifier
                .size(40.dp)
                .background(c.surface, RoundedCornerShape(Radius))
                .border(1.dp, c.border, RoundedCornerShape(Radius)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                badgeFor(item),
                modifier = Modifier.testTag("badge-${item.id}"),
                color = c.textSecondary,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    item.name,
                    color = c.text,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (active) {
                    Spacer(Modifier.width(12.dp))
                    Text(
                        if (item.status == DownloadStatus.Paused) "Paused" else formatSpeed(item.bytesPerSec, unit),
                        color = c.text,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        modifier = Modifier.testTag("speed-${item.id}"),
                    )
                }
            }
            if (active) {
                Spacer(Modifier.height(5.dp))
                ProgressBar(progress(item))
            }
            Spacer(Modifier.height(5.dp))
            Text(
                details(item),
                color = c.textSecondary,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        if (active) {
            val paused = item.status == DownloadStatus.Paused
            IconBox("pause-${item.id}", if (paused) "Resume" else "Pause", onTogglePause) {
                if (paused) PlayIcon(it) else PauseIcon(it)
            }
        } else {
            Spacer(Modifier.size(32.dp))
        }
        IconBox("chevron-${item.id}", "Open ${item.name}", onOpen) { ChevronIcon(it) }
    }
}

private fun details(item: DownloadItem): String =
    if (item.status == DownloadStatus.Finished) {
        formatBytes(item.totalBytes)
    } else {
        val pct = percent(item)
        val base = "$pct% · ${formatBytes(item.doneBytes)} of ${formatBytes(item.totalBytes)}"
        if (item.status == DownloadStatus.Downloading && item.bytesPerSec > 0) {
            "$base · ${formatEta((item.totalBytes - item.doneBytes).coerceAtLeast(0) / item.bytesPerSec)}"
        } else {
            base
        }
    }

@Composable
private fun ProgressBar(fraction: Float) {
    val c = colors
    Box(
        Modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(c.track),
    ) {
        Box(Modifier.fillMaxWidth(fraction).height(4.dp).background(c.accent))
    }
}
