package dev.anydm.desktop.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.anydm.desktop.chrome.EmptyState
import dev.anydm.desktop.chrome.Glyphs
import dev.anydm.desktop.chrome.PushButton
import dev.anydm.desktop.theme.LocalTokens
import dev.anydm.model.FoundTorrent
import dev.anydm.store.CATEGORIES
import dev.anydm.store.SearchMode
import dev.anydm.store.SearchState
import dev.anydm.store.SeederTone
import dev.anydm.store.SortKey
import dev.anydm.store.SortState
import dev.anydm.store.emptyText
import dev.anydm.store.errorChip
import dev.anydm.store.formatAge
import dev.anydm.store.formatSize
import dev.anydm.store.seederTone
import dev.anydm.store.sortFound
import dev.anydm.store.sourceLabel
import dev.anydm.store.statusLine

private val SIZE_W = 76.dp
private val COUNT_W = 68.dp
private val AGE_W = 76.dp
private val SOURCE_W = 96.dp
private val ACTIONS_W = 128.dp

/** Search and Browse: a query box, category chips, and the results as a sortable table (web: `SearchView`). */
@Composable
fun SearchScreen(
    state: SearchState,
    now: Long,
    focus: FocusRequester,
    onQuery: (String) -> Unit,
    onSubmit: () -> Unit,
    onCategory: (String) -> Unit,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    onSort: (SortKey) -> Unit,
    onAdd: (FoundTorrent) -> Unit,
    onCopy: (String) -> Unit,
    onFocusChange: (Boolean) -> Unit,
) {
    val t = LocalTokens.current
    // Local copies: `state` comes from another module, so its properties can't be smart-cast.
    val answer = state.answer
    val failure = state.failure
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QueryField(state.query, onQuery, onSubmit, focus, onFocusChange, Modifier.weight(1f))
            Box(Modifier.testTag("search-go")) {
                PushButton("Search", primary = true, enabled = !state.busy, onClick = onSubmit)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CATEGORIES.forEach { category ->
                Chip(category.label, category.id == state.category, !state.busy) { onCategory(category.id) }
            }
        }
        if (answer != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    statusLine(answer.results.size, answer.asked.size - answer.errors.size, answer.tookMs, state.mode),
                    fontSize = 12.sp,
                    color = t.secondaryText,
                )
                answer.errors.forEach { error ->
                    Text(
                        errorChip(error),
                        Modifier.border(1.dp, t.warn, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 1.dp),
                        fontSize = 11.sp,
                        color = t.warn,
                    )
                }
                Spacer(Modifier.weight(1f))
                if (state.mode == SearchMode.BROWSE) {
                    PushButton("Refresh", enabled = !state.busy, onClick = onRefresh)
                }
            }
        }
        when {
            failure != null -> {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(failure.message, color = t.text, fontWeight = FontWeight.Medium)
                    failure.causes.forEach { Text(errorChip(it), fontSize = 12.sp, color = t.secondaryText) }
                    PushButton("Try again", onClick = onRetry)
                }
            }

            answer == null -> {
                if (state.busy) {
                    Box(Modifier.fillMaxWidth().padding(top = 80.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp), color = t.secondaryText, strokeWidth = 2.dp)
                    }
                } else {
                    Box(Modifier.testTag("search-idle")) { EmptyState("Search") }
                }
            }

            answer.results.isEmpty() -> {
                EmptyState(emptyText(state.mode, state.searched))
            }

            else -> {
                Results(state, answer.results, now, onSort, onAdd, onCopy)
            }
        }
    }
}

@Composable
private fun QueryField(
    query: String,
    onQuery: (String) -> Unit,
    onSubmit: () -> Unit,
    focus: FocusRequester,
    onFocusChange: (Boolean) -> Unit,
    modifier: Modifier,
) {
    val t = LocalTokens.current
    val shape = RoundedCornerShape(6.dp)
    BasicTextField(
        value = query,
        onValueChange = onQuery,
        singleLine = true,
        textStyle = TextStyle(color = t.text, fontSize = 13.sp),
        cursorBrush = SolidColor(t.text),
        modifier =
            modifier
                .focusRequester(focus)
                .onFocusChanged { onFocusChange(it.isFocused) }
                .onPreviewKeyEvent { event ->
                    val enter = event.key == Key.Enter || event.key == Key.NumPadEnter
                    if (enter && event.type == KeyEventType.KeyDown) {
                        onSubmit()
                        true
                    } else {
                        false
                    }
                },
        decorationBox = { inner ->
            Row(
                Modifier.background(t.content, shape).border(1.dp, t.separator, shape).padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Glyphs.Search, null, Modifier.size(14.dp), tint = t.secondaryText)
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text("Search", fontSize = 13.sp, color = t.secondaryText)
                    inner()
                }
            }
        },
    )
}

@Composable
private fun Chip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val t = LocalTokens.current
    val shape = RoundedCornerShape(50)
    Text(
        label,
        Modifier
            .alpha(if (enabled) 1f else 0.5f)
            .clip(shape)
            .background(if (selected) t.selection else Color.Transparent)
            .border(1.dp, if (selected) Color.Transparent else t.separator, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 3.dp),
        fontSize = 12.sp,
        color = if (selected) t.onSelection else t.text,
    )
}

@Composable
private fun Results(
    state: SearchState,
    results: List<FoundTorrent>,
    now: Long,
    onSort: (SortKey) -> Unit,
    onAdd: (FoundTorrent) -> Unit,
    onCopy: (String) -> Unit,
) {
    val t = LocalTokens.current
    val rows = remember(results, state.sort) { sortFound(results, state.sort) }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Head("Name", SortKey.TITLE, state.sort, null, false, null, onSort)
            Head("Size", SortKey.SIZE, state.sort, SIZE_W, true, null, onSort)
            Head(
                "Seeders",
                SortKey.SEEDERS,
                state.sort,
                COUNT_W,
                true,
                if (state.mode == SearchMode.BROWSE) "Seeders among the latest releases" else null,
                onSort,
            )
            Head("Leechers", SortKey.LEECHERS, state.sort, COUNT_W, true, null, onSort)
            Head("Age", SortKey.PUBLISHED, state.sort, AGE_W, true, null, onSort)
            Head("Source", SortKey.INDEXER, state.sort, SOURCE_W, false, null, onSort)
            Spacer(Modifier.width(ACTIONS_W))
        }
        Box(Modifier.fillMaxWidth().size(1.dp).background(t.separator))
        LazyColumn(Modifier.fillMaxWidth()) {
            items(rows) { row -> ResultRow(row, state.fetching, now, onAdd, onCopy) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RowScope.Head(
    label: String,
    key: SortKey,
    sort: SortState,
    width: Dp?,
    end: Boolean,
    tip: String?,
    onSort: (SortKey) -> Unit,
) {
    val t = LocalTokens.current
    val arrow = if (sort.key == key) (if (sort.descending) " ↓" else " ↑") else ""
    val head =
        @Composable {
            Text(
                label + arrow,
                Modifier.clickable { onSort(key) }.padding(vertical = 2.dp).then(if (width == null) Modifier else Modifier.width(width)),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = t.secondaryText,
                textAlign = if (end) TextAlign.End else TextAlign.Start,
            )
        }
    val cell = if (width == null) Modifier.weight(1f) else Modifier
    Box(cell) {
        if (tip == null) {
            head()
        } else {
            TooltipArea(tooltip = { Text(tip, Modifier.background(t.content).padding(6.dp), fontSize = 11.sp, color = t.text) }) { head() }
        }
    }
}

@Composable
private fun ResultRow(
    row: FoundTorrent,
    fetching: String,
    now: Long,
    onAdd: (FoundTorrent) -> Unit,
    onCopy: (String) -> Unit,
) {
    val t = LocalTokens.current
    val fetchingThis = fetching.isNotEmpty() && fetching == row.link
    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(row.title, Modifier.weight(1f).padding(end = 8.dp), maxLines = 2, overflow = TextOverflow.Ellipsis, color = t.text)
        NumberCell(formatSize(row.sizeBytes), SIZE_W, t.secondaryText)
        NumberCell(row.seeders?.toString() ?: "—", COUNT_W, seederColor(row.seeders))
        NumberCell(row.leechers?.toString() ?: "—", COUNT_W, t.secondaryText)
        NumberCell(formatAge(row.published, now), AGE_W, t.secondaryText)
        Text(
            sourceLabel(row),
            Modifier.width(SOURCE_W),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = t.secondaryText,
            fontSize = 12.sp,
        )
        Row(Modifier.width(ACTIONS_W), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            PushButton(if (fetchingThis) "Fetching…" else "Add", enabled = !fetchingThis) { onAdd(row) }
            row.magnet?.let { magnet ->
                Text("Copy", Modifier.clickable { onCopy(magnet) }, fontSize = 12.sp, color = t.selection)
            }
        }
    }
}

@Composable
private fun NumberCell(
    text: String,
    width: Dp,
    color: Color,
) {
    Text(text, Modifier.width(width), textAlign = TextAlign.End, color = color, fontSize = 12.sp, maxLines = 1)
}

@Composable
private fun seederColor(seeders: Int?): Color {
    val t = LocalTokens.current
    return when (seederTone(seeders)) {
        SeederTone.GOOD -> t.ok
        SeederTone.SOME -> t.warn
        SeederTone.NONE -> t.secondaryText
    }
}
