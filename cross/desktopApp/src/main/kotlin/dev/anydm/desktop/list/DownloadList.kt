package dev.anydm.desktop.list

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.anydm.desktop.chrome.EmptyState
import dev.anydm.desktop.theme.LocalTokens
import dev.anydm.desktop.ui.CardAction
import dev.anydm.desktop.ui.rowView
import dev.anydm.model.Task
import dev.anydm.store.ListFilter
import dev.anydm.store.matches

fun emptyText(filter: ListFilter): String =
    when (filter) {
        ListFilter.ALL -> "No downloads yet. Paste a link above."
        ListFilter.ACTIVE -> "Nothing downloading right now."
        ListFilter.SEEDING -> "Nothing seeding."
        ListFilter.COMPLETED -> "Nothing finished yet."
    }

/** Whether the list is close enough to its end to fetch the next page. */
fun wantsMore(
    lastVisible: Int,
    total: Int,
    page: Int,
    totalPages: Int,
    loading: Boolean,
): Boolean = !loading && page < totalPages && lastVisible >= total - 5

/** The rows for [filter], a group's videos under it when open, and the next page fetched on the way down. */
@Composable
fun DownloadList(
    tasks: List<Task>,
    entries: Map<String, List<Task>>,
    now: Long,
    saving: Map<String, String>,
    filter: ListFilter,
    page: Int,
    totalPages: Int,
    loadingMore: Boolean,
    onLoadMore: () -> Unit,
    onExpand: (String) -> Unit,
    onCollapse: (String) -> Unit,
    selection: Set<String> = emptySet(),
    lead: String? = null,
    onPress: (String, Gesture) -> Unit = { _, _ -> },
    onAction: (Task, CardAction) -> Unit,
) {
    val t = LocalTokens.current
    val shown = tasks.filter { filter.matches(it) }
    if (shown.isEmpty()) {
        EmptyState(emptyText(filter))
        return
    }
    val state = rememberLazyListState()
    val order = visibleOrder(tasks, entries, filter)
    LaunchedEffect(lead) {
        val index = lead?.let(order::indexOf)?.takeIf { it >= 0 } ?: return@LaunchedEffect
        val visible = state.layoutInfo.visibleItemsInfo.map { it.index }
        if (index !in visible) state.scrollToItem(index)
    }
    LaunchedEffect(state, page, totalPages, loadingMore, shown.size) {
        snapshotFlow {
            state.layoutInfo.visibleItemsInfo
                .lastOrNull()
                ?.index ?: 0
        }.collect { last ->
            if (wantsMore(last, state.layoutInfo.totalItemsCount, page, totalPages, loadingMore)) onLoadMore()
        }
    }
    LazyColumn(Modifier.fillMaxSize(), state, verticalArrangement = Arrangement.spacedBy(1.dp)) {
        shown.forEach { task ->
            val open = entries[task.id]
            item(key = task.id) {
                val view = rowView(task, now)
                DownloadRow(
                    view,
                    selected = task.id in selection,
                    saving = saving[task.id],
                    expanded = open != null,
                    onExpand = if (view.expandable) ({ if (open != null) onCollapse(task.id) else onExpand(task.id) }) else null,
                    onPress = { onPress(task.id, it) },
                ) { action -> onAction(task, action) }
            }
            if (open != null) {
                items(open, key = { "${task.id}/${it.id}" }) { video ->
                    DownloadRow(
                        rowView(video, now),
                        selected = video.id in selection,
                        saving = saving[video.id],
                        indent = 28.dp,
                        onPress = { onPress(video.id, it) },
                    ) { action -> onAction(video, action) }
                }
            }
        }
        if (loadingMore) item(key = "loading") { Text("Loading…", fontSize = 11.sp, color = t.secondaryText) }
    }
}
