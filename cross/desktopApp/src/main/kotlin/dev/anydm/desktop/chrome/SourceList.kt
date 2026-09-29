package dev.anydm.desktop.chrome

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.anydm.desktop.theme.LocalTokens
import dev.anydm.model.SummaryDto
import dev.anydm.store.Connection
import dev.anydm.store.ListFilter
import dev.anydm.store.count
import java.awt.Cursor

data class SourceItem(
    val filter: ListFilter,
    val label: String,
    val count: Int?,
)

fun sourceItems(summary: SummaryDto?): List<SourceItem> =
    listOf(
        SourceItem(ListFilter.ALL, "All", summary?.all),
        SourceItem(ListFilter.ACTIVE, "Active", summary?.downloading),
        SourceItem(ListFilter.SEEDING, "Seeding", summary?.seeding),
        SourceItem(ListFilter.COMPLETED, "Completed", summary?.completed),
    )

enum class Dot { OK, WARN, ERROR }

fun connectionDot(connection: Connection): Dot =
    when (connection) {
        Connection.Live -> Dot.OK
        Connection.Connecting, is Connection.Degraded -> Dot.WARN
        Connection.Offline -> Dot.ERROR
    }

fun hostOf(serverUrl: String): String = serverUrl.substringAfter("://").trimEnd('/')

fun clampSidebar(width: Float): Float = width.coerceIn(160f, 260f)

/** The sidebar: filters with counts, then the server with its connection dot. Its right edge resizes it. */
@Composable
fun SourceList(
    items: List<SourceItem>,
    selected: ListFilter,
    onSelect: (ListFilter) -> Unit,
    host: String,
    connection: Connection,
    onChangeServer: () -> Unit,
    onSettings: () -> Unit,
    onClearFinished: () -> Unit,
    width: Dp,
    onWidth: (Dp) -> Unit,
    showSearch: Boolean = false,
    searchSelected: Boolean = false,
    onSearch: () -> Unit = {},
) {
    val t = LocalTokens.current
    val density = LocalDensity.current
    Row(Modifier.fillMaxHeight()) {
        Column(
            Modifier
                .width(width)
                .fillMaxHeight()
                .background(t.sidebar)
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Heading("Downloads")
            items.forEach { item ->
                val row =
                    @Composable {
                        SourceRow(
                            item.label,
                            item.count?.let(::count),
                            item.filter == selected && !searchSelected,
                        ) { onSelect(item.filter) }
                    }
                if (item.filter == ListFilter.COMPLETED) {
                    ContextMenuArea(items = { listOf(ContextMenuItem("Clear finished…", onClearFinished)) }) { row() }
                } else {
                    row()
                }
            }
            if (showSearch) {
                Spacer(Modifier.size(10.dp))
                Heading("Discover")
                SourceRow("Search", null, searchSelected, onSearch)
            }
            Spacer(Modifier.size(10.dp))
            Heading("Server")
            ServerRow(host, connection, onChangeServer, onSettings)
        }
        Box(
            Modifier
                .width(4.dp)
                .fillMaxHeight()
                .background(t.separator.copy(alpha = 0.6f))
                .pointerHoverIcon(PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR)))
                .draggable(
                    rememberDraggableState { delta ->
                        onWidth(clampSidebar(width.value + with(density) { delta.toDp().value }).dp)
                    },
                    Orientation.Horizontal,
                ),
        )
    }
}

@Composable
private fun Heading(text: String) {
    val t = LocalTokens.current
    Text(
        text.uppercase(),
        Modifier.padding(start = 8.dp, top = 6.dp, bottom = 2.dp),
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        color = t.secondaryText,
    )
}

@Composable
private fun SourceRow(
    label: String,
    badge: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val t = LocalTokens.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) t.selection else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), color = if (selected) t.onSelection else t.text)
        badge?.let { Text(it, fontSize = 11.sp, color = if (selected) t.onSelection else t.secondaryText) }
    }
}

@Composable
private fun ServerRow(
    host: String,
    connection: Connection,
    onChangeServer: () -> Unit,
    onSettings: () -> Unit,
) {
    val t = LocalTokens.current
    var open by remember { mutableStateOf(false) }
    val dot =
        when (connectionDot(connection)) {
            Dot.OK -> t.ok
            Dot.WARN -> t.warn
            Dot.ERROR -> t.error
        }
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .clickable { open = true }
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(dot))
            Text(host, color = t.text, maxLines = 1)
        }
        DropdownMenu(open, { open = false }) {
            DropdownMenuItem(text = { Text("Change server…") }, onClick = {
                open = false
                onChangeServer()
            })
            DropdownMenuItem(text = { Text("Settings…") }, onClick = {
                open = false
                onSettings()
            })
        }
    }
}
