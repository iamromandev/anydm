package dev.anydm.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadStatus
import dev.anydm.engine.platform.Clipboard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** The label of the button that shows a file in the OS's file manager. */
val RevealLabel: String = if (IsMac) "Show in Finder" else "Show in folder"

/** The label of the button that moves the file to the OS's trash. */
val TrashLabel: String = if (IsMac) "Move file to Trash" else "Move file to Recycle Bin"

/**
 * A download's page with its own model: made when the page opens, its timers and polls ended when it closes.
 * [onBack] returns to the list; it is also called when the download is gone (removed or cancelled).
 */
@Composable
fun DetailRoute(
    source: EngineSource,
    files: FileActions,
    footer: FooterModel,
    id: DownloadId,
    onBack: () -> Unit,
    keys: WindowKeys = WindowKeys(),
    /** The names people gave networks, and the popover that gives them; none, and the table has no pencil. */
    naming: Pair<StateFlow<NetworkLooks>, NetworkEditor>? = null,
    /** Links and `.torrent` paths pasted or dropped on the page go to the new download dialog. */
    onNewDownload: (List<String>) -> Unit = {},
) {
    // One key for both: a model never outlives or outruns the scope it runs in.
    val own = remember(source, files, id) { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    DisposableEffect(own) { onDispose { own.cancel() } }
    val model = remember(own) { DetailModel(source, files, id, own, looks = naming?.first ?: MutableStateFlow(NetworkLooks())) }
    DetailScreen(model, footer, onBack, keys, naming?.second, source.clipboard, onNewDownload)
}

@Composable
fun DetailScreen(
    model: DetailModel,
    footer: FooterModel,
    onBack: () -> Unit,
    keys: WindowKeys = WindowKeys(),
    editor: NetworkEditor? = null,
    clipboard: Clipboard = Clipboard.None,
    onNewDownload: (List<String>) -> Unit = {},
) {
    val colors = Plexo.colors
    val state by model.state.collectAsState()
    val footerState by footer.state.collectAsState()
    val latest by rememberUpdatedState(state)
    val back by rememberUpdatedState(onBack)
    val newDownload by rememberUpdatedState(onNewDownload)
    val scope = rememberCoroutineScope()
    DisposableEffect(keys, model) {
        val handler: (KeyEvent) -> Boolean = handler@{ event ->
            val now = latest
            // A dialog has its own keys, and a text field in it must get every one of them.
            if (event.type != KeyEventType.KeyDown || now.confirm != null || now.fixLinkOpen || now.trackersOpen) return@handler false
            val command = if (IsMac) event.isMetaPressed else event.isCtrlPressed
            when {
                event.key == Key.Escape -> {
                    back()
                    true
                }

                command && event.key == Key.V -> {
                    // Not gated on a repeat: a key-up lost to the OS must not swallow the next paste.
                    scope.launch {
                        val link = clipboardLink(clipboard)
                        if (link != null) newDownload(listOf(link))
                    }
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
    LaunchedEffect(state.gone) { if (state.gone) back() }
    Box(
        Modifier
            .fillMaxSize()
            .background(colors.bg)
            .dropTorrents { paths ->
                val torrents = droppedTorrents(paths)
                if (torrents.isNotEmpty()) onNewDownload(torrents)
                torrents.isNotEmpty()
            },
    ) {
        if (state.gone) return@Box
        Column(Modifier.fillMaxSize()) {
            DetailHeader(state, model, onBack)
            state.message?.let { Banner(it, model::dismissMessage) }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (state.kind) {
                    DetailKind.ACTIVE, DetailKind.TORRENT -> ActiveBody(state, model, footerState.unit, editor, keys)
                    DetailKind.FINISHED -> FinishedBody(state, footerState.unit)
                    DetailKind.FAILED -> FailedBody(state, model)
                }
            }
            Footer(footerState, footer)
        }
        DetailDialogs(state, model)
    }
}

// Header

@Composable
private fun DetailHeader(
    state: DetailState,
    model: DetailModel,
    onBack: () -> Unit,
) {
    val colors = Plexo.colors
    Row(
        Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable(role = Role.Button, onClick = onBack)
                .semantics { contentDescription = "Back to downloads" }
                .padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlyphIcon(Glyph.CHEVRON_LEFT, colors.textSecondary, size = 18.dp)
            Txt("Downloads", Plexo.type.title.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium), color = colors.textSecondary)
        }
        Spacer(Modifier.weight(1f))
        if (state.isTorrent) PButton("Trackers", model::openTrackers, Modifier.semantics { testTag = "trackers-open" })
        when (state.kind) {
            DetailKind.ACTIVE, DetailKind.TORRENT -> {
                PButton("Cancel download", model::askCancel, kind = ButtonKind.DESTRUCTIVE)
                PButton(if (state.status == DownloadStatus.PAUSED) "Resume" else "Pause", model::pauseOrResume)
                PButton(RevealLabel, model::reveal, kind = ButtonKind.PRIMARY, enabled = state.canOpen)
            }

            DetailKind.FINISHED -> {
                PButton("Remove from list", model::remove)
                PButton(TrashLabel, model::askTrash, kind = ButtonKind.DESTRUCTIVE, enabled = state.canOpen)
                PButton("Open", model::open, enabled = state.canOpen)
                PButton(RevealLabel, model::reveal, kind = ButtonKind.PRIMARY, enabled = state.canOpen)
            }

            DetailKind.FAILED -> {
                PButton("Remove from list", model::remove)
                if (state.canFixLink) PButton("Fix link", model::openFixLink)
                PButton("Retry", model::retry, kind = ButtonKind.PRIMARY)
            }
        }
    }
    HeaderRule()
}

// The page of a download in progress, a torrent too

@Composable
private fun ActiveBody(
    state: DetailState,
    model: DetailModel,
    unit: SpeedUnit,
    editor: NetworkEditor?,
    keys: WindowKeys,
) {
    val colors = Plexo.colors
    val swatches = LocalNetworkLooks.current.swatches(colors)
    val inPlay = state.networks.filter { it.inPlay }
    var chip by remember { mutableStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        HeroBand {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                CombineDiagram(
                    inPlay.map { CombineSource(it.name, swatches[it.key]?.solid ?: colors.accent, it.speed, it.active) },
                    unit,
                    state.paused,
                )
                Column(Modifier.widthIn(min = 130.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    val (value, speedUnit) =
                        if (state.paused) {
                            "—" to ""
                        } else {
                            formatRate(state.speed, unit).let {
                                it.substringBefore(' ') to
                                    it.substringAfter(' ')
                            }
                        }
                    BigStat("TOTAL SPEED", value, speedUnit, if (state.paused) colors.textTertiary else colors.text)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        InlineStat("AVG", formatRate(state.avgSpeed, unit))
                        Txt("·", Plexo.type.badge, color = colors.textTertiary)
                        InlineStat("PEAK", state.peakSpeed?.let { formatRate(it, unit) } ?: "—")
                    }
                    val badge = state.badges.takeIf { it.isNotEmpty() }?.let { it[chip % it.size] }
                    if (badge != null) {
                        val multiple = state.badges.size > 1
                        val visual = inPlay.firstOrNull { it.name == badge.name }?.let { swatches[it.key] }
                        val tint = visual?.solid ?: colors.accent
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(5.dp))
                                .background(tint.copy(alpha = 0.14f))
                                .border(0.5.dp, tint.copy(alpha = 0.5f), RoundedCornerShape(5.dp))
                                .let { if (multiple) it.clickable(role = Role.Button) { chip++ } else it }
                                .padding(horizontal = 8.dp, vertical = 5.dp),
                        ) {
                            Txt(badge.label, Plexo.type.badge, color = tint)
                        }
                    }
                }
                Column(Modifier.weight(1f).widthIn(max = 640.dp)) {
                    Txt(
                        "THROUGHPUT" +
                            when {
                                state.paused -> ""
                                state.chartMeasured -> " · LAST ${dev.anydm.engine.SpeedWindow.SIZE}S"
                                else -> " · LAST MINUTE"
                            },
                        Plexo.type.badge.copy(fontSize = 9.5.sp, letterSpacing = 1.2.sp),
                        color = colors.textTertiary,
                    )
                    if (state.noSpeedHistory) {
                        NoSpeedHistory()
                    } else {
                        ThroughputChart(
                            layersOf(state.chart, swatches, colors.accent),
                            unit,
                            if (state.paused) ChartEnd.PAUSE else ChartEnd.NOW,
                            Modifier.fillMaxWidth().alpha(if (state.paused) 0.45f else 1f),
                        )
                    }
                }
            }
        }
        FileInfo(state, model)
        if (state.peersOpen) PeersTable(state, unit)
        if (state.filesOpen) TorrentFiles(state, model)
        Box(Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp)) {
            ChunkMapView(
                state.chunks,
                inPlay.map { LegendEntry(it.key, it.name, swatches[it.key]?.solid ?: colors.accent) },
                state.chunkLabel,
                state.total,
            )
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 8.dp)) {
            NetworkTable(
                state.networks,
                swatches,
                unit,
                torrent = state.kind == DetailKind.TORRENT,
                onToggle = model::setNetworkEnabled,
                onEdit = editor?.let { e -> { key -> e.start(key, "table", state.networks.firstOrNull { it.key == key }?.name ?: key) } },
                editor = { key -> if (editor != null) NetworkEditPopupIf(editor, "table", key, keys) },
            )
        }
    }
}

@Composable
private fun NoSpeedHistory() {
    Box(Modifier.fillMaxWidth().height(96.dp), contentAlignment = Alignment.Center) {
        Txt("No speed history for this download", Plexo.type.badge, color = Plexo.colors.textTertiary)
    }
}

private fun layersOf(
    series: List<ChartSeries>,
    swatches: Map<String, Swatch>,
    fallback: Color,
): List<ChartLayer> = series.map { ChartLayer(swatches[it.key]?.solid ?: fallback, it.values) }

@Composable
private fun FileInfo(
    state: DetailState,
    model: DetailModel,
) {
    val colors = Plexo.colors
    val dot: @Composable () -> Unit = { Txt("·", Plexo.type.speed, color = colors.textTertiary) }
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FileBadge(state, 44.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Txt(
                    state.name,
                    Plexo.type.title.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                    Modifier.weight(1f, fill = false),
                )
                if (state.kind == DetailKind.TORRENT) TorrentTag()
            }
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                Txt(
                    formatBytes(state.downloaded) + (state.total?.takeIf { it > 0 }?.let { " of ${formatBytes(it)}" } ?: ""),
                    Plexo.type.speed.copy(fontWeight = null),
                    color = colors.textSecondary,
                )
                state.percent?.let {
                    dot()
                    Txt("$it%", Plexo.type.speed.copy(fontWeight = FontWeight.SemiBold))
                }
                state.peers?.let {
                    dot()
                    val shape = RoundedCornerShape(4.dp)
                    Row(
                        Modifier
                            .clip(shape)
                            .background(if (state.peersOpen) colors.bgTertiary else Color.Transparent)
                            .clickable(role = Role.Button, onClick = model::togglePeers)
                            .semantics { testTag = "peers-toggle" }
                            .padding(horizontal = 5.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Txt(
                            "$it ${if (it == 1) "peer" else "peers"}",
                            Plexo.type.speed.copy(fontWeight = null),
                            color = colors.textSecondary,
                        )
                        GlyphIcon(if (state.peersOpen) Glyph.CHEVRON_DOWN else Glyph.CHEVRON_RIGHT, colors.textSecondary, size = 10.dp)
                    }
                }
                if (state.kind == DetailKind.TORRENT && state.files.size > 1) {
                    dot()
                    val chosen = state.files.count { it.selected }
                    val shape = RoundedCornerShape(4.dp)
                    Row(
                        Modifier
                            .clip(shape)
                            .background(if (state.filesOpen) colors.bgTertiary else colors.bgSecondary)
                            .border(0.5.dp, colors.border, shape)
                            .clickable(role = Role.Button, onClick = model::toggleFiles)
                            .padding(horizontal = 7.dp, vertical = 3.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Txt(describeFileCount(chosen, state.files.size), Plexo.type.detail, color = colors.textSecondary)
                        Txt(if (state.filesOpen) "▲" else "▼", Plexo.type.detail.copy(fontSize = 7.5.sp), color = colors.textSecondary)
                    }
                }
                if (!state.paused) {
                    state.timeLeft?.let {
                        dot()
                        Txt("$it left", Plexo.type.speed.copy(fontWeight = null), color = colors.textSecondary)
                    }
                } else {
                    StatusBadge(if (state.queued) "QUEUED" else "PAUSED")
                }
                if (state.retries > 0) {
                    dot()
                    Txt(
                        "${state.retries} ${if (state.retries == 1) "retry" else "retries"}",
                        Plexo.type.speed.copy(fontWeight = null),
                        color = colors.usb.text,
                    )
                }
                Spacer(Modifier.weight(1f))
                state.savedTo?.let {
                    GlyphIcon(Glyph.FOLDER, colors.textTertiary, size = 12.dp)
                    Txt(it, Plexo.type.detail.copy(fontSize = 11.sp, fontFamily = Plexo.type.title.fontFamily), color = colors.textTertiary)
                }
            }
        }
    }
}

internal fun describeFileCount(
    chosen: Int,
    total: Int,
): String = if (chosen == total) "$total files" else "$chosen of $total files"

@Composable
private fun StatusBadge(text: String) {
    val colors = Plexo.colors
    val shape = RoundedCornerShape(3.5.dp)
    Box(
        Modifier
            .clip(shape)
            .background(colors.usb.bg)
            .border(0.5.dp, colors.usb.border, shape)
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        Txt(text, Plexo.type.badge.copy(fontSize = 9.5.sp, letterSpacing = 0.8.sp), color = colors.usb.text)
    }
}

@Composable
private fun TorrentTag() {
    val colors = Plexo.colors
    val shape = RoundedCornerShape(4.dp)
    Box(
        Modifier
            .clip(shape)
            .background(colors.bgSecondary)
            .border(0.5.dp, colors.border, shape)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Txt("TORRENT", Plexo.type.badge, color = colors.textSecondary)
    }
}

@Composable
private fun FileBadge(
    state: DetailState,
    size: androidx.compose.ui.unit.Dp,
) {
    val colors = Plexo.colors
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier
            .size(size)
            .clip(shape)
            .background(colors.bgSecondary)
            .border(0.5.dp, colors.borderStrong, shape),
        contentAlignment = Alignment.Center,
    ) {
        if (state.folder) {
            GlyphIcon(Glyph.FOLDER, colors.textSecondary, size = 18.dp)
        } else {
            val type = Plexo.type.badge.let { if (state.badge.length > 4) it.copy(fontSize = it.fontSize * 0.8f) else it }
            Txt(state.badge, type, color = colors.textSecondary)
        }
    }
}

/** The peers of a torrent, fastest download first as the model sorts them; seeds are marked. */
@Composable
private fun PeersTable(
    state: DetailState,
    unit: SpeedUnit,
) {
    val colors = Plexo.colors
    val shape = RoundedCornerShape(9.dp)
    Column(
        Modifier
            .padding(start = 20.dp, end = 20.dp, top = 12.dp)
            .fillMaxWidth()
            .heightIn(max = 220.dp)
            .clip(shape)
            .border(0.5.dp, colors.border, shape)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .semantics {
                testTag = "peers-table"
                contentDescription = "Peers"
            },
    ) {
        if (state.peerList.isEmpty()) {
            Txt("No peers connected.", Plexo.type.hint, Modifier.padding(vertical = 4.dp), color = colors.textSecondary)
            return@Column
        }
        PeerLine("ADDRESS", "CLIENT", "DONE", "DOWN", "UP", "", colors.textTertiary, Plexo.type.badge)
        Column(Modifier.verticalScroll(rememberScrollState())) {
            for (peer in state.peerList) {
                Box(Modifier.semantics(mergeDescendants = true) { testTag = "peer-${peer.address}" }) {
                    PeerLine(
                        peer.address,
                        peer.client,
                        "${(peer.progress * 100).toInt().coerceIn(0, 100)}%",
                        formatRate(peer.downloadRate, unit),
                        formatRate(peer.uploadRate, unit),
                        if (peer.seed) "Seed" else "",
                        colors.text,
                        Plexo.type.detail,
                    )
                }
            }
        }
    }
}

@Composable
private fun PeerLine(
    address: String,
    client: String,
    done: String,
    down: String,
    up: String,
    seed: String,
    color: Color,
    style: TextStyle,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Txt(address, style, Modifier.weight(2f), color = color)
        Txt(client, style, Modifier.weight(2f), color = color)
        Txt(done, style.copy(textAlign = TextAlign.End), Modifier.width(44.dp), color = color)
        Txt(down, style.copy(textAlign = TextAlign.End), Modifier.width(84.dp), color = color)
        Txt(up, style.copy(textAlign = TextAlign.End), Modifier.width(84.dp), color = color)
        Txt(seed, style, Modifier.width(36.dp), color = Plexo.colors.accent)
    }
}

@Composable
private fun TorrentFiles(
    state: DetailState,
    model: DetailModel,
) {
    val colors = Plexo.colors
    val shape = RoundedCornerShape(9.dp)
    Column(
        Modifier
            .padding(start = 20.dp, end = 20.dp, top = 12.dp)
            .fillMaxWidth()
            .heightIn(max = 176.dp)
            .clip(shape)
            .border(0.5.dp, colors.border, shape)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .semantics { contentDescription = "Files" },
    ) {
        for (file in state.files) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PCheckbox(file.selected, { model.setFileSelected(file.index, it) }, "Download ${file.path}")
                Txt(file.path.substringAfterLast('/'), Plexo.type.detail, Modifier.weight(1f))
                file.size?.let { Txt(formatBytes(it), Plexo.type.detail, color = colors.textTertiary) }
                val done =
                    when {
                        !file.selected -> "Skipped"
                        file.size != null && file.size > 0 && file.done >= file.size -> "Done"
                        file.size != null && file.size > 0 -> "${(file.done * 100 / file.size).toInt()}%"
                        else -> ""
                    }
                Txt(done, Plexo.type.detail.copy(textAlign = TextAlign.End), Modifier.widthIn(min = 56.dp), color = colors.textSecondary)
            }
        }
    }
}

// The page of a finished download

@Composable
private fun FinishedBody(
    state: DetailState,
    unit: SpeedUnit,
) {
    val colors = Plexo.colors
    val stats = state.finished ?: return
    val swatches = LocalNetworkLooks.current.swatches(colors)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        HeroBand {
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(colors.wifi.bg)
                        .border(1.dp, colors.wifi.border, CircleShape)
                        .semantics { contentDescription = "Download complete" },
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(Modifier.size(21.dp)) {
                        val u = size.minDimension / 24f
                        val tick = Path()
                        tick.moveTo(5f * u, 13f * u)
                        tick.lineTo(10f * u, 18f * u)
                        tick.lineTo(19f * u, 7f * u)
                        drawPath(tick, colors.wifi.solid, style = Stroke(2.4f * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                }
                Column(Modifier.weight(1f)) {
                    Txt(state.name, Plexo.type.title)
                    val where = state.savedTo
                    val files =
                        state.files.takeIf { it.size > 1 }?.let { "${describeFileCount(it.count { f -> f.selected }, it.size)} · " } ?: ""
                    Txt(
                        files + formatBytes(stats.size) + (if (where != null) " · $where" else ""),
                        Plexo.type.detail,
                        Modifier.padding(top = 5.dp),
                        color = colors.textTertiary,
                    )
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Txt("AVERAGE", Plexo.type.badge.copy(fontSize = 9.sp, letterSpacing = 1.4.sp), color = colors.textTertiary)
                    val (value, speedUnit) = formatRate(stats.avgSpeed, unit).let { it.substringBefore(' ') to it.substringAfter(' ') }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
                        Txt(value, Plexo.type.speed.copy(fontSize = 26.sp, fontWeight = FontWeight.SemiBold))
                        Txt(speedUnit, Plexo.type.speed.copy(fontSize = 11.sp), color = colors.textTertiary)
                    }
                }
            }
        }
        val statsShape = RoundedCornerShape(10.dp)
        Row(
            Modifier
                .padding(horizontal = 20.dp, vertical = 18.dp)
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .clip(statsShape)
                .background(colors.bgSecondary)
                .border(0.5.dp, colors.border, statsShape),
        ) {
            val cells =
                listOf(
                    "Size" to formatBytes(stats.size),
                    "Time" to (stats.seconds?.let(::formatClock) ?: "—"),
                    "Peak" to (stats.peak?.let { formatRate(it, unit) } ?: "—"),
                    "Networks" to stats.networks.toString(),
                    stats.thirdLabel to (stats.third?.toString() ?: "—"),
                )
            cells.forEachIndexed { index, (label, value) ->
                if (index > 0) Box(Modifier.width(0.5.dp).fillMaxHeight().background(colors.border))
                Column(
                    Modifier.weight(1f).padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Txt(label.uppercase(), Plexo.type.badge.copy(fontSize = 9.sp, letterSpacing = 1.2.sp), color = colors.textTertiary)
                    Txt(value, Plexo.type.speed.copy(fontSize = 14.sp))
                }
            }
        }
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel("Speed over the download")
            if (state.noSpeedHistory) {
                NoSpeedHistory()
            } else {
                ThroughputChart(layersOf(state.chart, swatches, colors.accent), unit, ChartEnd.END, Modifier.fillMaxWidth())
            }
        }
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            SectionLabel("Contribution by network")
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(CircleShape)
                    .background(colors.trackBg),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                for (net in stats.contributions) {
                    Box(
                        Modifier
                            .weight(
                                net.bytes.toFloat().coerceAtLeast(1f),
                            ).fillMaxHeight()
                            .background(swatches[net.key]?.solid ?: colors.accent),
                    )
                }
            }
            for (net in stats.contributions) {
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(swatches[net.key]?.solid ?: colors.accent))
                    Txt(net.name, Plexo.type.hint.copy(fontWeight = FontWeight.Medium))
                    Spacer(Modifier.weight(1f))
                    Txt("${formatBytes(net.bytes)} · ${net.share}%", Plexo.type.detail, color = colors.textSecondary)
                }
            }
            Txt(stats.written, Plexo.type.detail, Modifier.padding(top = 12.dp), color = colors.textTertiary)
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Txt(text.uppercase(), Plexo.type.badge.copy(fontSize = 10.sp, letterSpacing = 1.6.sp), color = Plexo.colors.textTertiary)
}

/** A span as `0:21` or `1:02:03`. */
internal fun formatClock(seconds: Long): String {
    val h = seconds / 3600
    val m = seconds % 3600 / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

// The page of a download that failed

@Composable
private fun FailedBody(
    state: DetailState,
    model: DetailModel,
) {
    val colors = Plexo.colors
    val card = RoundedCornerShape(12.dp)
    Box(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 24.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .widthIn(max = 440.dp)
                .fillMaxWidth()
                .clip(card)
                .background(colors.bgSecondary)
                .border(0.5.dp, colors.border, card)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Box(
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(colors.dangerBg)
                    .border(1.dp, colors.dangerBorder, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.size(22.dp)) {
                    val u = size.minDimension / 24f
                    drawCircle(colors.danger, radius = 9f * u, style = Stroke(2f * u))
                    drawLine(
                        colors.danger,
                        Offset(12f * u, 8f * u),
                        Offset(12f * u, 12.5f * u),
                        strokeWidth = 2.4f * u,
                        cap = StrokeCap.Round,
                    )
                    drawCircle(colors.danger, radius = 1.2f * u, center = Offset(12f * u, 15.5f * u))
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Txt("Download failed", Plexo.type.title.copy(fontSize = 17.sp))
                Txt(
                    state.failure ?: "",
                    Plexo.type.hint.copy(fontSize = 12.sp, textAlign = TextAlign.Center),
                    color = colors.textSecondary,
                    maxLines = 4,
                )
            }
            val capsule = RoundedCornerShape(9.dp)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(capsule)
                    .background(colors.bg)
                    .border(0.5.dp, colors.border, capsule)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FileBadge(state, 34.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Txt(state.name, Plexo.type.rowName.copy(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold))
                    val total = state.total?.takeIf { it > 0 }
                    Txt(
                        when {
                            state.downloaded <= 0 -> "No data downloaded"
                            total != null -> "${formatBytes(state.downloaded)} of ${formatBytes(total)} (${state.percent ?: 0}%)"
                            else -> "${formatBytes(state.downloaded)} downloaded"
                        },
                        Plexo.type.detail,
                        color = colors.textTertiary,
                    )
                }
            }
            state.link?.let { link ->
                Box(Modifier.fillMaxWidth().height(0.5.dp).background(colors.border))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Txt(link, Plexo.type.detail, Modifier.weight(1f), color = colors.textTertiary)
                    Box(Modifier.clickable(role = Role.Button, onClick = model::copyLink).padding(horizontal = 4.dp, vertical = 2.dp)) {
                        Txt("Copy link", Plexo.type.detail, color = colors.accent)
                    }
                }
            }
        }
    }
}

// Pieces

@Composable
internal fun HeroBand(content: @Composable () -> Unit) {
    val colors = Plexo.colors
    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(colors.heroTop, colors.heroBottom)))
                .padding(horizontal = 20.dp, vertical = 18.dp),
        ) {
            content()
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.heroBorder))
    }
}

@Composable
private fun BigStat(
    label: String,
    value: String,
    unit: String,
    valueColor: Color,
) {
    val colors = Plexo.colors
    Txt(label, Plexo.type.badge.copy(letterSpacing = 2.sp), color = colors.textTertiary)
    Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.Bottom) {
        Txt(
            value,
            Plexo.type.speed.copy(fontSize = 38.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.03).sp),
            color = valueColor,
        )
        if (unit.isNotEmpty()) {
            Txt(
                unit,
                Plexo.type.speed.copy(fontSize = 12.sp),
                Modifier.padding(bottom = 4.dp),
                color = colors.textTertiary,
            )
        }
    }
}

@Composable
private fun InlineStat(
    label: String,
    value: String,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Txt(label, Plexo.type.badge, color = Plexo.colors.textTertiary)
        Txt(value, Plexo.type.badge)
    }
}

// Dialogs

@Composable
private fun DetailDialogs(
    state: DetailState,
    model: DetailModel,
) {
    when (state.confirm) {
        DetailConfirm.CANCEL -> {
            ConfirmDialog(
                title = "Cancel download?",
                body = "The part already downloaded will be deleted.",
                confirmText = "Cancel download",
                dismissText = "Keep",
                onConfirm = model::confirm,
                onCancel = model::dismissConfirm,
                destructive = true,
            )
        }

        DetailConfirm.TRASH -> {
            ConfirmDialog(
                title = "Delete file?",
                body =
                    "The file will be moved to the ${if (IsMac) "Trash" else "Recycle Bin"}." +
                        if (state.status == DownloadStatus.SEEDING) " This also stops seeding." else "",
                confirmText = "Delete file",
                onConfirm = model::confirm,
                onCancel = model::dismissConfirm,
                destructive = true,
            )
        }

        DetailConfirm.REMOVE_SEEDING -> {
            ConfirmDialog(
                title = "Remove from list?",
                body = "This stops seeding and takes it off the list. The files stay where they are.",
                confirmText = "Remove",
                onConfirm = model::confirm,
                onCancel = model::dismissConfirm,
                destructive = true,
            )
        }

        null -> {}
    }
    if (state.fixLinkOpen) FixLinkDialog(state, model)
    if (state.trackersOpen) TrackersDialog(rememberTrackersModel(model), model::closeTrackers)
}

/** Asks for a new link to the same file; it carries on from where the download stopped. */
@Composable
private fun FixLinkDialog(
    state: DetailState,
    model: DetailModel,
) {
    val colors = Plexo.colors
    var link by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .focusRequester(focus)
            .focusTarget()
            .onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) {
                    model.dismissFixLink()
                    true
                } else {
                    false
                }
            }.pointerInput(Unit) { detectTapGestures { model.dismissFixLink() } },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = 460.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(colors.bgSecondary)
                .border(0.5.dp, colors.border, RoundedCornerShape(12.dp))
                .pointerInput(Unit) { detectTapGestures { } }
                .semantics { contentDescription = "Paste a new link" }
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Txt("Paste a new link", Plexo.type.menu)
            Txt(
                "Paste a new link to the same file: ${state.name}" +
                    (state.total?.takeIf { it > 0 }?.let { " (${formatBytes(it)})" } ?: "") +
                    ". anydm resumes from where the download stopped.",
                Plexo.type.hint,
                color = colors.textSecondary,
                maxLines = 4,
            )
            PTextField(link, { link = it }, "New link", placeholder = "https://…", autoFocus = true, onSubmit = { model.relink(link) })
            state.fixLinkError?.let { Txt(it, Plexo.type.hint, color = colors.danger, maxLines = 3) }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                PButton("Cancel", model::dismissFixLink)
                PButton("Continue download", { model.relink(link) }, kind = ButtonKind.PRIMARY, enabled = link.isNotBlank())
            }
        }
    }
}
