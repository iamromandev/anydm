package dev.anydm.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun DetailScreen(
    item: DownloadItem,
    networks: List<Network>,
    unit: SpeedUnit,
    filesOpen: Boolean,
    onFilesOpen: (Boolean) -> Unit,
    onBack: () -> Unit,
    onTogglePause: () -> Unit,
    onCancel: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var off by remember(item.id) { mutableStateOf(emptySet<String>()) }
    var chip by remember { mutableStateOf(Rect.Zero) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val detail = remember(item, networks, off) { buildDetail(item, networks, off) }
    Box(
        modifier
            .fillMaxSize()
            .testTag("detail-screen")
            .onGloballyPositioned { origin = it.positionInWindow() },
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            if (item.status == DownloadStatus.Finished) {
                FinishedBody(detail, unit, onBack, onRemove)
            } else {
                ActiveBody(
                    detail,
                    networks,
                    unit,
                    off,
                    { off = it },
                    onBack,
                    onTogglePause,
                    onCancel,
                    { onFilesOpen(!filesOpen) },
                    { chip = it },
                )
            }
        }
        if (filesOpen) FilesMenuOverlay(chip, origin) { onFilesOpen(false) }
    }
}

@Composable
private fun ActiveBody(
    detail: Detail,
    networks: List<Network>,
    unit: SpeedUnit,
    off: Set<String>,
    setOff: (Set<String>) -> Unit,
    onBack: () -> Unit,
    onTogglePause: () -> Unit,
    onCancel: () -> Unit,
    onFiles: () -> Unit,
    onChip: (Rect) -> Unit,
) {
    ActiveHeader(detail.item.status == DownloadStatus.Paused, onBack, onTogglePause, onCancel)
    Divider()
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        SpeedDiagram(detail, unit, Modifier.weight(1f))
        ThroughputGraph(detail, unit, Modifier.width(300.dp))
    }
    Divider()
    FileCard(detail, onFiles, onChip)
    if (showMap(detail)) {
        ChunkMap(detail, Modifier.padding(horizontal = 20.dp).padding(bottom = 14.dp))
    }
    NetworkTable(detail, unit) { id -> setOff(toggleOff(off, networks, id)) }
}

@Composable
private fun BackButton(onBack: () -> Unit) {
    val c = colors
    Row(
        Modifier
            .testTag("detail-back")
            .clickable(role = Role.Button, onClick = onBack)
            .padding(horizontal = 6.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BackIcon(c.textSecondary)
        Text("Downloads", color = c.textSecondary, fontSize = 15.sp, maxLines = 1)
    }
}

@Composable
private fun ActiveHeader(
    paused: Boolean,
    onBack: () -> Unit,
    onTogglePause: () -> Unit,
    onCancel: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BackButton(onBack)
        Spacer(Modifier.weight(1f))
        DangerButton("Cancel download", "detail-cancel", onCancel)
        Spacer(Modifier.width(8.dp))
        OutlineButton(if (paused) "Resume" else "Pause", "detail-pause", onTogglePause)
        Spacer(Modifier.width(8.dp))
        PrimaryButton("Show in Finder", "detail-finder", true) {}
    }
}

@Composable
private fun DangerButton(
    label: String,
    tag: String,
    onClick: () -> Unit,
) {
    val c = colors
    Box(
        Modifier
            .testTag(tag)
            .height(36.dp)
            .background(c.danger.copy(alpha = 0.16f), RoundedCornerShape(Radius))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = c.danger, fontSize = 14.sp, maxLines = 1) }
}

@Composable
private fun FinishedBody(
    d: Detail,
    unit: SpeedUnit,
    onBack: () -> Unit,
    onRemove: () -> Unit,
) {
    val c = colors
    val item = d.item
    val torrent = item.kind == DownloadKind.Torrent
    Row(
        Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BackButton(onBack)
        Spacer(Modifier.weight(1f))
        OutlineButton("Remove from list", "detail-remove", onRemove)
        Spacer(Modifier.width(8.dp))
        DangerButton("Move file to Trash", "detail-trash", onRemove)
        Spacer(Modifier.width(8.dp))
        OutlineButton("Open", "detail-open")
        Spacer(Modifier.width(8.dp))
        PrimaryButton("Show in Finder", "detail-finder", true) {}
    }
    Divider()
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(44.dp)
                .background(c.success.copy(alpha = 0.12f), CircleShape)
                .border(1.dp, c.success.copy(alpha = 0.4f), CircleShape)
                .semantics { contentDescription = "Download finished" },
            contentAlignment = Alignment.Center,
        ) { CheckIcon(c.success) }
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.name,
                color = c.text,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("detail-name"),
            )
            Spacer(Modifier.height(4.dp))
            val prefix = if (torrent) "${sintelFiles.size} files · " else ""
            Text(
                "$prefix${formatBytes(item.totalBytes)} · ${d.folder}",
                color = c.textSecondary,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("detail-subtitle"),
            )
        }
        Spacer(Modifier.width(16.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                "AVERAGE",
                color = c.textSecondary,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 2.sp,
                maxLines = 1,
            )
            BigSpeed(formatSpeed(d.avg, unit), 32, Modifier.testTag("detail-average"))
        }
    }
    Divider()
    StatsStrip(d, unit)
    Column(Modifier.padding(horizontal = 20.dp)) {
        ThroughputGraph(
            d,
            unit,
            Modifier.fillMaxWidth(),
            title = "SPEED OVER THE DOWNLOAD",
            startLabel = "60s before the end",
            endLabel = "end",
            plotHeight = 120.dp,
        )
        Spacer(Modifier.height(18.dp))
        Text(
            "CONTRIBUTION BY NETWORK",
            color = c.textSecondary,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 1.5.sp,
            maxLines = 1,
        )
        Spacer(Modifier.height(8.dp))
        ContributionBar(d)
        Spacer(Modifier.height(10.dp))
        d.rows.filter { it.on }.forEach { r ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(networkColor(r.kind, c), CircleShape))
                Spacer(Modifier.width(10.dp))
                Text(r.name, color = c.text, fontSize = 13.sp, maxLines = 1, modifier = Modifier.weight(1f))
                Text(
                    "${formatBytes(r.downloaded)} · ${r.share}%",
                    color = c.textSecondary,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    modifier = Modifier.testTag("detail-contrib-${r.id}"),
                )
            }
        }
        Spacer(Modifier.height(28.dp))
        val written =
            if (torrent) {
                "written in ${d.cells.size} pieces · uploaded ${formatBytes(d.rows.sumOf { it.uploaded })} · ${d.retries} retries"
            } else {
                "written in ${d.cells.size} chunks · ${d.retries} retries"
            }
        Text(
            written,
            color = c.textSecondary,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            modifier = Modifier.testTag("detail-footnote"),
        )
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun BigSpeed(
    text: String,
    size: Int,
    modifier: Modifier = Modifier,
) {
    val c = colors
    val split = text.indexOf(' ')
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(fontSize = size.sp, fontWeight = FontWeight.Bold)) { append(if (split > 0) text.take(split) else text) }
            if (split > 0) withStyle(SpanStyle(fontSize = 13.sp, color = c.textSecondary)) { append(text.drop(split)) }
        },
        color = c.text,
        fontFamily = FontFamily.Monospace,
        maxLines = 1,
        modifier = modifier,
    )
}

@Composable
private fun StatsStrip(
    d: Detail,
    unit: SpeedUnit,
) {
    val c = colors
    val shape = RoundedCornerShape(Radius)
    val last =
        if (d.item.kind ==
            DownloadKind.Torrent
        ) {
            Triple("PEERS", "peers", d.peers)
        } else {
            Triple("STREAMS", "streams", d.rows.sumOf { it.streams })
        }
    val stats =
        listOf(
            Triple("SIZE", "size", formatBytes(d.item.totalBytes)),
            Triple("TIME", "time", formatClock(d.elapsedSec)),
            Triple("PEAK", "peak", formatSpeed(d.peak, unit)),
            Triple("NETWORKS", "networks", d.rows.count { it.on }.toString()),
            Triple(last.first, last.second, last.third.toString()),
        )
    Row(
        Modifier
            .fillMaxWidth()
            .padding(20.dp)
            .height(IntrinsicSize.Min)
            .background(c.surface, shape)
            .border(1.dp, c.border, shape),
    ) {
        stats.forEachIndexed { i, (label, tag, value) ->
            Column(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                Text(
                    label,
                    color = c.textSecondary,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.5.sp,
                    maxLines = 1,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    value,
                    color = c.text,
                    fontSize = 16.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    modifier = Modifier.testTag("detail-stat-$tag"),
                )
            }
            if (i < stats.lastIndex) Box(Modifier.width(1.dp).fillMaxHeight().background(c.border))
        }
    }
}

@Composable
private fun CheckIcon(color: Color) {
    Canvas(Modifier.size(18.dp)) {
        val w = size.width
        val st = 2f * density
        drawLine(color, Offset(w * .15f, w * .55f), Offset(w * .4f, w * .8f), st, StrokeCap.Round)
        drawLine(color, Offset(w * .4f, w * .8f), Offset(w * .88f, w * .22f), st, StrokeCap.Round)
    }
}

@Composable
private fun FilesMenuOverlay(
    chip: Rect,
    origin: Offset,
    onClose: () -> Unit,
) {
    val c = colors
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .testTag("detail-files-outside")
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose),
        )
        val density = LocalDensity.current
        val gap = 4.dp
        val margin = 8.dp
        val left = with(density) { (chip.left - origin.x).toDp() }
        val chipTop = with(density) { (chip.top - origin.y).toDp() }
        val chipBottom = with(density) { (chip.bottom - origin.y).toDp() }
        val roomBelow = (maxHeight - chipBottom - gap - margin).coerceAtLeast(0.dp)
        val roomAbove = (chipTop - gap - margin).coerceAtLeast(0.dp)
        val flip = roomBelow < FilesMenuMax && roomAbove > roomBelow
        val area = minOf(FilesMenuMax, if (flip) roomAbove else roomBelow)
        val top = if (flip) chipTop - gap - area else chipBottom + gap
        val shape = RoundedCornerShape(Radius)
        Box(
            Modifier.offset(left, top).width(280.dp).height(area),
            contentAlignment = if (flip) Alignment.BottomStart else Alignment.TopStart,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = area)
                    .testTag("detail-files-menu")
                    .background(c.surface, shape)
                    .border(1.dp, c.border, shape)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 6.dp),
            ) {
                sintelFiles.forEachIndexed { i, f ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .semantics(mergeDescendants = true) {}
                            .testTag("detail-file-$i")
                            .padding(horizontal = 12.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            f.name,
                            color = c.text,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            formatBytes(f.bytes),
                            color = c.textSecondary,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

private val FilesMenuMax = 260.dp

@Composable
private fun BackIcon(color: Color) {
    Canvas(Modifier.size(14.dp)) {
        val w = size.width
        val st = 1.5f * density
        drawLine(color, Offset(w * .62f, w * .2f), Offset(w * .34f, w * .5f), st, StrokeCap.Round)
        drawLine(color, Offset(w * .34f, w * .5f), Offset(w * .62f, w * .8f), st, StrokeCap.Round)
    }
}

@Composable
private fun FileCard(
    d: Detail,
    onFiles: () -> Unit,
    onChip: (Rect) -> Unit,
) {
    val c = colors
    val item = d.item
    val torrent = item.kind == DownloadKind.Torrent
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp).testTag("detail-card-box")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(56.dp)
                    .background(c.surface, RoundedCornerShape(Radius))
                    .border(1.dp, c.border, RoundedCornerShape(Radius)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    badgeFor(item),
                    modifier = Modifier.testTag("detail-badge"),
                    color = c.textSecondary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        item.name,
                        color = c.text,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false).testTag("detail-name"),
                    )
                    if (torrent) {
                        Text(
                            "TORRENT",
                            color = c.textSecondary,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 1.sp,
                            maxLines = 1,
                            modifier =
                                Modifier
                                    .testTag("detail-torrent-tag")
                                    .border(1.dp, c.border, RoundedCornerShape(4.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        cardLine(d),
                        color = c.textSecondary,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        modifier = Modifier.testTag("detail-card"),
                    )
                    if (torrent) {
                        FilesChip(onFiles, onChip)
                        etaText(item)?.let {
                            Text(
                                "· $it",
                                color = c.textSecondary,
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                modifier = Modifier.testTag("detail-eta"),
                            )
                        }
                    }
                    if (d.retries > 0) {
                        Text("·", color = c.textSecondary, fontSize = 13.sp)
                        Text(
                            "${d.retries} retries",
                            color = c.danger,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            modifier = Modifier.testTag("detail-retries"),
                        )
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FolderIcon(c.textSecondary)
                Text(d.folder, color = c.textSecondary, fontSize = 12.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
            }
        }
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier
                .testTag("detail-progress")
                .semantics { progressBarRangeInfo = ProgressBarRangeInfo(progress(item), 0f..1f) }
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(c.track),
        ) {
            Box(Modifier.fillMaxWidth(progress(item)).height(6.dp).background(c.accent))
        }
    }
}

private fun etaText(item: DownloadItem): String? =
    if (item.status == DownloadStatus.Downloading && item.bytesPerSec > 0) {
        "${formatEta((item.totalBytes - item.doneBytes).coerceAtLeast(0) / item.bytesPerSec)} left"
    } else {
        null
    }

private fun cardLine(d: Detail): String {
    val item = d.item
    val base = "${formatBytes(item.doneBytes)} of ${formatBytes(item.totalBytes)} · ${percent(item)}%"
    return if (item.kind == DownloadKind.Torrent) {
        "$base · ${d.peers} ${if (d.peers == 1) "peer" else "peers"}"
    } else {
        etaText(item)?.let { "$base · $it" } ?: base
    }
}

@Composable
private fun FilesChip(
    onClick: () -> Unit,
    onBounds: (Rect) -> Unit,
) {
    val c = colors
    Row(
        Modifier
            .testTag("detail-files")
            .onGloballyPositioned { onBounds(it.boundsInWindow()) }
            .border(1.dp, c.border, RoundedCornerShape(6.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("${sintelFiles.size} files", color = c.text, fontSize = 12.sp, maxLines = 1)
        ChevronIcon(c.textSecondary, down = true, size = 10)
    }
}

private val columnWeights = listOf(3f, 2.2f, 0.9f, 1.3f, 1.4f)

@Composable
private fun NetworkTable(
    d: Detail,
    unit: SpeedUnit,
    onToggle: (String) -> Unit,
) {
    val c = colors
    Column(Modifier.fillMaxWidth().testTag("detail-table")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
            listOf(
                "NETWORK",
                "PROGRESS",
                "SHARE",
                "SPEED",
                if (d.item.kind ==
                    DownloadKind.Torrent
                ) {
                    "TRANSFERRED"
                } else {
                    "DOWNLOADED"
                },
            ).forEachIndexed { i, label ->
                Text(
                    label,
                    color = c.textSecondary,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.5.sp,
                    maxLines = 1,
                    textAlign = if (i >= 2) TextAlign.End else TextAlign.Start,
                    modifier = Modifier.weight(columnWeights[i]).padding(start = if (i == 0) 20.dp else 0.dp),
                )
            }
        }
        Divider()
        d.rows.forEach { r ->
            NetworkTableRow(r, d, unit, onToggle)
            Divider()
        }
    }
}

@Composable
private fun NetworkTableRow(
    r: NetworkRow,
    d: Detail,
    unit: SpeedUnit,
    onToggle: (String) -> Unit,
) {
    val c = colors
    val color = networkColor(r.kind, c)
    val torrent = d.item.kind == DownloadKind.Torrent
    val fraction = if (d.item.totalBytes <= 0) 0f else (r.downloaded.toFloat() / d.item.totalBytes).coerceIn(0f, 1f)
    Row(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 20.dp)
            .testTag("detail-net-${r.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(columnWeights[0]), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(color, CircleShape))
            Spacer(Modifier.width(12.dp))
            CheckBox(r.on, { onToggle(r.id) }, "detail-check-${r.id}", "Use ${r.name}")
            Spacer(Modifier.width(10.dp))
            Text(
                r.name,
                color = c.text,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(10.dp))
            if (r.disabled) {
                Text("Off", color = c.textSecondary, fontSize = 12.sp, maxLines = 1, modifier = Modifier.testTag("detail-off-${r.id}"))
            } else {
                val noun = if (torrent) "peer" else "stream"
                StreamsPill("${r.streams} $noun${if (r.streams == 1) "" else "s"}", r.id)
            }
        }
        Box(Modifier.weight(columnWeights[1]).padding(end = 16.dp)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(c.track),
            ) {
                Box(Modifier.fillMaxWidth(fraction).height(4.dp).background(color))
            }
        }
        cell(columnWeights[2], "${r.share}%", c.text, "detail-share-${r.id}", bold = true)
        if (torrent) {
            Column(Modifier.weight(columnWeights[3]), horizontalAlignment = Alignment.End) {
                Text(
                    "↓ ${formatSpeed(r.speed, unit)}",
                    color = color,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    modifier = Modifier.testTag("detail-speed-${r.id}"),
                )
                Text(
                    if (r.up >
                        0
                    ) {
                        "↑ ${formatSpeed(r.up, unit)}"
                    } else {
                        "↑ –"
                    },
                    color = c.textSecondary,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    modifier = Modifier.testTag("detail-up-${r.id}"),
                )
            }
            Column(Modifier.weight(columnWeights[4]), horizontalAlignment = Alignment.End) {
                Text(
                    "↓ ${formatBytes(r.downloaded)}",
                    color = c.text,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    modifier = Modifier.testTag("detail-downloaded-${r.id}"),
                )
                Text(
                    "↑ ${formatBytes(r.uploaded)}",
                    color = c.textSecondary,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    modifier = Modifier.testTag("detail-uploaded-${r.id}"),
                )
            }
        } else {
            cell(columnWeights[3], formatSpeed(r.speed, unit), color, "detail-speed-${r.id}")
            cell(columnWeights[4], formatBytes(r.downloaded), c.text, "detail-downloaded-${r.id}")
        }
    }
}

@Composable
private fun RowScope.cell(
    weight: Float,
    text: String,
    color: Color,
    tag: String,
    bold: Boolean = false,
) {
    Text(
        text,
        color = color,
        fontSize = 13.sp,
        fontFamily = FontFamily.Monospace,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        maxLines = 1,
        textAlign = TextAlign.End,
        modifier = Modifier.weight(weight).testTag(tag),
    )
}

/** Display-only streams control. */
@Composable
private fun StreamsPill(
    label: String,
    id: String,
) {
    val c = colors
    Row(
        Modifier.border(1.dp, c.border, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(label, color = c.textSecondary, fontSize = 12.sp, maxLines = 1, modifier = Modifier.testTag("detail-streams-$id"))
        ChevronIcon(c.textSecondary, down = true, size = 10)
    }
}
