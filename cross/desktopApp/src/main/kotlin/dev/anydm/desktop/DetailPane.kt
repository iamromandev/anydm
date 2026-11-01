package dev.anydm.desktop

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.anydm.engine.DownloadSnapshot
import dev.anydm.engine.TorrentDetailResult
import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.net.ProxyChoice
import kotlinx.coroutines.delay

enum class DetailTab(
    val label: String,
) {
    FILES("Files"),
    SEGMENTS("Segments"),
    ATTEMPTS("Attempts"),
    MIRRORS("Mirrors"),
    PROXY("Proxy"),
    ERRORS("Errors"),
    PEERS("Peers"),
    OVERVIEW("Overview"),
    TRACKERS("Trackers"),
}

/** A torrent's tabs, Overview first; the other kinds keep theirs. */
private val TorrentTabs =
    listOf(DetailTab.OVERVIEW, DetailTab.FILES, DetailTab.TRACKERS, DetailTab.PEERS, DetailTab.PROXY, DetailTab.ATTEMPTS, DetailTab.ERRORS)

private val OtherTabs =
    listOf(DetailTab.FILES, DetailTab.SEGMENTS, DetailTab.ATTEMPTS, DetailTab.MIRRORS, DetailTab.PROXY, DetailTab.ERRORS)

/** A file split into many segments shows the first ones; every snapshot redraws the tab. */
private const val MAX_SEGMENTS_SHOWN = 200

/** How often a torrent's tabs ask the engine for the torrent's live peers. */
private const val PEER_REFRESH_MILLIS = 2_000L

/** The one selected job's files, segments, attempts, mirrors, errors and, for a torrent, its peers; shown only then. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DetailPane(
    s: DownloadSnapshot,
    model: AppModel,
    height: () -> Dp,
) {
    Column(
        Modifier
            .fillMaxWidth()
            // Read while measuring, so a drag moves the panel without recomposing its sections.
            .layout { measurable, constraints ->
                val h = height().roundToPx()
                val placeable = measurable.measure(constraints.copy(minHeight = h, maxHeight = h))
                layout(placeable.width, placeable.height) { placeable.place(0, 0) }
            }.testTag("detail")
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val torrent = s.download.kind == DownloadKind.TORRENT
        var tab by remember(s.download.id, torrent) { mutableStateOf(if (torrent) DetailTab.OVERVIEW else DetailTab.FILES) }
        val live = if (torrent) rememberTorrentDetail(s.download.id, model) else null
        val tabs = if (torrent) TorrentTabs else OtherTabs
        // Name and status, then the tabs: beside them when there is room, else on a line of their own so none is pushed out of reach.
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val title: @Composable () -> Unit = {
                Text(
                    displayName(s),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("detail-name"),
                )
            }
            val status: @Composable () -> Unit = {
                Text("Priority ${s.download.priority} · ${statusLabel(s)}", style = MaterialTheme.typography.labelMedium, maxLines = 1)
            }
            if (maxWidth >= StackTabsBelow) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.weight(1f, fill = false).widthIn(max = 240.dp)) { title() }
                    status()
                    TabRow(tabs, tab, { tab = it }, Modifier.weight(1f))
                }
            } else {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.weight(1f, fill = false)) { title() }
                        status()
                    }
                    TabRow(tabs, tab, { tab = it }, Modifier.fillMaxWidth())
                }
            }
        }
        HorizontalDivider()
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .testTag("detail-${tab.name.lowercase()}"),
        ) {
            when (tab) {
                DetailTab.FILES -> if (live != null) TorrentFiles(s, live, model) else Files(s)
                DetailTab.SEGMENTS -> Segments(s)
                DetailTab.ATTEMPTS -> Attempts(s, failedOnly = false)
                DetailTab.MIRRORS -> Mirrors(s)
                DetailTab.PROXY -> Proxy(s, model)
                DetailTab.ERRORS -> Attempts(s, failedOnly = true)
                DetailTab.PEERS -> live?.let { TorrentPeers(s, it) }
                DetailTab.OVERVIEW -> live?.let { TorrentOverview(s, it, model) }
                DetailTab.TRACKERS -> live?.let { TorrentTrackers(s, it, model) }
            }
        }
    }
}

/** The pane's width below which the tabs get a line of their own. */
private val StackTabsBelow = 1000.dp

/** The tabs in a row that scrolls sideways and keeps the selected one in view. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TabRow(
    tabs: List<DetailTab>,
    selected: DetailTab,
    onSelect: (DetailTab) -> Unit,
    modifier: Modifier,
) {
    Row(modifier.horizontalScroll(rememberScrollState())) {
        tabs.forEach { t ->
            val requester = remember { BringIntoViewRequester() }
            LaunchedEffect(selected) { if (t == selected) requester.bringIntoView() }
            TextButton(
                onClick = { onSelect(t) },
                modifier = Modifier.bringIntoViewRequester(requester).testTag("tab-${t.name.lowercase()}"),
            ) {
                Text(t.label, fontWeight = if (t == selected) FontWeight.Bold else FontWeight.Normal)
            }
        }
    }
}

@Composable
private fun Line(text: String) = Text(text, style = MaterialTheme.typography.bodySmall)

@Composable
private fun Files(s: DownloadSnapshot) {
    if (s.files.isEmpty()) return Line("No files yet.")
    s.files.forEach { f ->
        val size = f.file.size?.let(::formatBytes) ?: "size unknown"
        Line("${f.file.placedPath ?: f.file.path} — ${formatBytes(f.written)} of $size")
    }
}

@Composable
private fun Segments(s: DownloadSnapshot) {
    if (s.files.all { it.segments.isEmpty() }) return Line("No segments (as of the last checkpoint).")
    s.files.filter { it.segments.isNotEmpty() }.forEach { f ->
        Text(f.file.path, style = MaterialTheme.typography.labelMedium)
        f.segments.take(MAX_SEGMENTS_SHOWN).forEach { seg ->
            Line("${seg.start}–${seg.end}: ${formatBytes(seg.written)} of ${formatBytes(seg.size)}")
        }
        if (f.segments.size > MAX_SEGMENTS_SHOWN) Line("… ${f.segments.size - MAX_SEGMENTS_SHOWN} more")
    }
}

@Composable
private fun Attempts(
    s: DownloadSnapshot,
    failedOnly: Boolean,
) {
    val shown = s.attempts.filter { !failedOnly || it.errorClass != null || it.message != null }.reversed()
    if (shown.isEmpty()) return Line(if (failedOnly) "No errors." else "No attempts yet.")
    val mirrors = s.mirrors.associateBy { it.id }
    shown.forEach { a ->
        val where = a.mirrorId?.let { mirrors[it]?.url } ?: "—"
        val ended = a.endedAt?.toString() ?: "running"
        val error = listOfNotNull(a.errorClass?.name, a.message).joinToString(": ")
        Line("${a.startedAt} → $ended · $where" + if (error.isNotEmpty()) " · $error" else "")
    }
}

@Composable
private fun Mirrors(s: DownloadSnapshot) {
    if (s.mirrors.isEmpty()) return Line("No mirrors.")
    s.mirrors.forEach { Line("${it.position + 1}. ${it.url}") }
}

/** The torrent's heavy detail, polled while it is selected; the drafts and pending edits live here so a tab switch keeps them. */
@Composable
private fun rememberTorrentDetail(
    id: DownloadId,
    model: AppModel,
): TorrentLive {
    val scope = rememberCoroutineScope()
    val seed = remember(id) { SeedState(scope) }
    val files = remember(id) { FilesState(scope) }
    var answer by remember(id) { mutableStateOf(Answer(loaded = false, result = null)) }
    LaunchedEffect(id) {
        var polls = 0
        var misses = 0
        while (true) {
            val fresh = model.torrentDetail(id)
            polls++
            // One failed poll keeps the last answer on screen; a second one in a row says unavailable.
            if (fresh == null) misses++ else misses = 0
            val shown = if (fresh == null && misses < 2 && answer.result is TorrentDetailResult.Detail) answer.result else fresh
            answer = Answer(loaded = true, result = shown)
            if (fresh is TorrentDetailResult.Detail) seed.sync(fresh, polls)
            seed.polls = polls
            files.settle(polls)
            delay(PEER_REFRESH_MILLIS)
        }
    }
    return TorrentLive(answer.loaded, answer.result, seed, files, scope)
}

private class Answer(
    val loaded: Boolean,
    val result: TorrentDetailResult?,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Proxy(
    s: DownloadSnapshot,
    model: AppModel,
) {
    LaunchedEffect(s.download.id) { model.loadProxies() }
    val proxies by model.proxies.collectAsState()
    val id = s.download.id
    Line("Which proxy this download goes through. Default leaves it to its profile and the global choice (Settings, Proxies).")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("Default" to ProxyChoice.Inherit, "None" to ProxyChoice.Direct, "System" to ProxyChoice.System).forEach { (label, choice) ->
            FilterChip(
                selected = s.download.proxy == choice,
                onClick = { model.setDownloadProxy(id, choice) },
                label = { Text(label) },
                modifier = Modifier.testTag("detail-proxy-${choice.encode().lowercase()}"),
            )
        }
        proxies.forEach { proxy ->
            FilterChip(
                selected = s.download.proxy == ProxyChoice.Named(proxy.id),
                onClick = { model.setDownloadProxy(id, ProxyChoice.Named(proxy.id)) },
                label = { Text(proxy.name) },
                modifier = Modifier.testTag("detail-proxy-${proxy.id.value}"),
            )
        }
    }
}
