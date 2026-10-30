package dev.anydm.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.anydm.engine.DownloadSnapshot
import dev.anydm.engine.TorrentDetailResult
import dev.anydm.engine.core.DownloadKind
import kotlinx.coroutines.delay

enum class DetailTab(
    val label: String,
) {
    FILES("Files"),
    SEGMENTS("Segments"),
    ATTEMPTS("Attempts"),
    MIRRORS("Mirrors"),
    ERRORS("Errors"),
    PEERS("Peers"),
}

/** A file split into many segments shows the first ones; every snapshot redraws the tab. */
private const val MAX_SEGMENTS_SHOWN = 200

/** How often the Peers tab asks the engine for the torrent's live peers. */
private const val PEER_REFRESH_MILLIS = 2_000L

/** The one selected job's files, segments, attempts, mirrors, errors and, for a torrent, its peers; shown only then. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DetailPane(
    s: DownloadSnapshot,
    model: AppModel,
) {
    Column(
        Modifier
            .width(300.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp)
            .testTag("detail"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        var tab by remember(s.download.id) { mutableStateOf(DetailTab.FILES) }
        Text(displayName(s), style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag("detail-name"))
        Text("Priority ${s.download.priority} · ${statusLabel(s)}", style = MaterialTheme.typography.labelMedium)
        val tabs = DetailTab.entries.filter { it != DetailTab.PEERS || s.download.kind == DownloadKind.TORRENT }
        FlowRow {
            tabs.forEach { t ->
                TextButton(onClick = { tab = t }, modifier = Modifier.testTag("tab-${t.name.lowercase()}")) {
                    Text(t.label, fontWeight = if (t == tab) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
        HorizontalDivider()
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).testTag("detail-${tab.name.lowercase()}")) {
            when (tab) {
                DetailTab.FILES -> Files(s)
                DetailTab.SEGMENTS -> Segments(s)
                DetailTab.ATTEMPTS -> Attempts(s, failedOnly = false)
                DetailTab.MIRRORS -> Mirrors(s)
                DetailTab.ERRORS -> Attempts(s, failedOnly = true)
                DetailTab.PEERS -> Peers(s, model)
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

@Composable
private fun Peers(
    s: DownloadSnapshot,
    model: AppModel,
) {
    val summary = s.torrent
    if (summary == null) {
        Line("Not in the torrent session.")
    } else {
        Line("Peers ${summary.peers} · Seeds ${summary.seeds}")
        Line("Down ${formatSpeed(summary.downloadRate)} · Up ${formatSpeed(summary.uploadRate)}")
        Line("Uploaded ${formatBytes(summary.uploaded)} · Ratio ${summary.ratio?.let { "%.2f".format(it) } ?: "—"}")
    }
    var live by remember(s.download.id) { mutableStateOf<TorrentDetailResult?>(null) }
    LaunchedEffect(s.download.id) {
        while (true) {
            live = model.torrentDetail(s.download.id)
            delay(PEER_REFRESH_MILLIS)
        }
    }
    when (val detail = live) {
        is TorrentDetailResult.Detail -> {
            detail.detail.peers.forEach { p ->
                Line(
                    "${p.address} ${p.client} · ${(p.progress * 100).toInt()}% · ↓${formatSpeed(
                        p.downloadRate,
                    )} ↑${formatSpeed(p.uploadRate)}",
                )
            }
            if (detail.detail.peers.isEmpty()) Line("No peers connected.")
        }

        else -> {
            Text("Live peers unavailable.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("peers-unavailable"))
        }
    }
}
