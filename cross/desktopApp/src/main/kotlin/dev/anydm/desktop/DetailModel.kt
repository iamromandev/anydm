package dev.anydm.desktop

import dev.anydm.engine.http.Span
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

private const val MIB = 1024L * 1024
private const val POINTS = 60
private const val RAMP = 5
private const val PAUSED_TAIL = 10
private const val MAX_CHUNKS = 128

/** The most cells a real torrent's piece map has; a torrent with more pieces puts several in each cell. */
private const val MAX_PIECE_CELLS = 2_048
private const val MIN_PIECE_LENGTH = 16_384L
private const val CHUNK_BYTES = 3 * MIB
private const val PIECE_BYTES = 128L * 1024
private const val TORRENT_PEERS = 9
private const val DIRECT_STREAMS = 4
private const val FINISHED_RATE = 2 * MIB
private const val PEAK_FACTOR = 1.4
private const val WOBBLE = 0.05

enum class CellState { Done, InFlight, Pending }

/** [network] indexes [Detail.rows]; it is -1 unless the cell is done. */
data class Cell(
    val state: CellState,
    val network: Int,
)

data class NetworkRow(
    val id: String,
    val name: String,
    val kind: NetworkKind,
    val on: Boolean,
    val speed: Long,
    val downloaded: Long,
    val share: Int,
    val streams: Int,
    val up: Long,
    val uploaded: Long,
    /** True when the network is switched off in the Networks menu, as opposed to unchecked here. */
    val disabled: Boolean = false,
    val color: NetworkColor? = null,
)

data class Detail(
    val item: DownloadItem,
    val rows: List<NetworkRow>,
    val total: Long,
    val avg: Long,
    val peak: Long,
    val aloneFactor: Double?,
    val series: List<List<Long>>,
    val cells: List<Cell>,
    val cellBytes: Long,
    val retries: Int,
    val peers: Int,
    val elapsedSec: Long,
    val folder: String = "~/Downloads",
    /** A real download: [series] is its one measured speed history, and there are no per-network rows, shares or chunks. */
    val real: Boolean = false,
    /** A real torrent's piece count, when it is more than [cells] (several pieces share a cell); else 0. */
    val groupedPieces: Int = 0,
)

fun detailSample(): HomeState {
    val base = populatedSample()
    val total = sintelFiles.sumOf { it.bytes }
    val sintel = DownloadItem("3", "Sintel", total, total * 74 / 100, 11 * MIB, DownloadStatus.Downloading, DownloadKind.Torrent)
    return base.copy(downloads = base.downloads + sintel)
}

/** Equal parts of [total] over [n] buckets, the remainder going to the first. */
private fun splitEven(
    total: Long,
    n: Int,
): List<Long> = List(n) { i -> total / n + if (i == 0) total % n else 0L }

/** Largest-remainder rounding of [weights] to whole percents that sum to 100. */
private fun shares(weights: List<Long>): List<Int> {
    if (weights.isEmpty()) return emptyList()
    val sum = weights.sum()
    if (sum <= 0) return splitEven(100, weights.size).map { it.toInt() }
    val floors = weights.map { (it * 100 / sum).toInt() }
    val order = weights.indices.sortedWith(compareByDescending<Int> { weights[it] * 100 % sum }.thenBy { it })
    val out = floors.toMutableList()
    repeat(100 - floors.sum()) { k -> out[order[k]]++ }
    return out
}

private class Lcg(
    seed: Int,
) {
    private var state = seed.toLong() and 0xFFFFFFFFL

    fun next(): Double {
        state = (state * 1664525L + 1013904223L) and 0xFFFFFFFFL
        return state / 4294967296.0
    }
}

private fun seedOf(id: String) = id.hashCode() and Int.MAX_VALUE

private fun activeSeries(
    speed: Long,
    rng: Lcg,
): List<Long> =
    List(POINTS) { i ->
        when {
            i == POINTS - 1 -> speed
            i < RAMP -> speed * i / RAMP
            else -> (speed * (1 + WOBBLE * (2 * rng.next() - 1))).toLong()
        }
    }

private fun finishedCurve(peak: Long): List<Long> =
    List(POINTS) { i -> (peak * (0.7 + 0.3 * cos(PI * (i - POINTS / 2) / (POINTS / 2)))).toLong() }

private fun buildCells(
    item: DownloadItem,
    onIdx: List<Int>,
    torrent: Boolean,
): Pair<List<Cell>, Long> {
    if (item.totalBytes <= 0) return emptyList<Cell>() to 0L
    val count =
        if (torrent) {
            ceil(item.totalBytes.toDouble() / PIECE_BYTES).toInt()
        } else {
            minOf(MAX_CHUNKS, ceil(item.totalBytes.toDouble() / CHUNK_BYTES).toInt())
        }
    val done = (item.doneBytes.coerceIn(0, item.totalBytes) * count / item.totalBytes).toInt()
    val flight = if (item.status == DownloadStatus.Downloading) minOf(2 * onIdx.size, count - done) else 0
    val offset = seedOf(item.id).toLong()
    val cells =
        List(count) { i ->
            when {
                i < done -> Cell(CellState.Done, if (onIdx.isEmpty()) -1 else onIdx[((i + offset) % onIdx.size).toInt()])
                i < done + flight -> Cell(CellState.InFlight, -1)
                else -> Cell(CellState.Pending, -1)
            }
        }
    val cellBytes =
        when {
            torrent -> PIECE_BYTES
            ceil(item.totalBytes.toDouble() / CHUNK_BYTES) <= MAX_CHUNKS -> CHUNK_BYTES
            else -> (item.totalBytes + count - 1) / count
        }
    return cells to cellBytes
}

/**
 * The chunk map of a real download, from the bytes it really has: [MAX_CHUNKS] cells of equal size over the file. A cell is done
 * when every byte of it is done (in one span's done part, or in the touching done parts of neighbours), in flight when it holds
 * the next byte of a span that is still being fetched, else pending. Only the row is read, and the work is a sweep over the cells
 * and the spans, so a 5 GB file costs no more than a small one.
 */
private fun realCells(item: DownloadItem): Pair<List<Cell>, Long> {
    val total = item.totalBytes
    if (total <= 0) return emptyList<Cell>() to 0L
    val spans = item.spans?.takeIf { it.isNotEmpty() } ?: listOf(Span(0, total - 1, item.doneBytes.coerceIn(0, total)))
    val count = minOf(MAX_CHUNKS.toLong(), total).toInt()
    val size = (total + count - 1) / count
    val doneRuns = doneRuns(spans)
    val running = item.status == DownloadStatus.Downloading
    val inFlight = BooleanArray(count)
    if (running) {
        for (s in spans) if (!s.finished && s.next in 0 until total) inFlight[(s.next / size).toInt().coerceAtMost(count - 1)] = true
    }
    var run = 0
    val cells =
        List(count) { i ->
            val from = i * size
            val to = minOf(total - 1, from + size - 1)
            while (run < doneRuns.size && doneRuns[run].last < from) run++
            val whole = run < doneRuns.size && doneRuns[run].first <= from && doneRuns[run].last >= to
            when {
                whole -> Cell(CellState.Done, -1)
                inFlight[i] -> Cell(CellState.InFlight, -1)
                else -> Cell(CellState.Pending, -1)
            }
        }
    return cells to size
}

/**
 * The piece map of a real torrent, from its verified [DownloadItem.pieces] and the pieces [DownloadItem.inFlight] (only while it
 * runs): one cell per piece up to [MAX_PIECE_CELLS], above that a cell holds the same number of pieces each and is done when all of
 * them are, in flight when any is, else missing. A finished torrent is all done. A row with no pieces yet has no map. The work is one
 * pass over the pieces (bit tests) and over the in-flight set, so a 100,000-piece torrent costs a few milliseconds.
 */
private fun torrentCells(item: DownloadItem): Pair<List<Cell>, Long> {
    val pieces = item.pieces ?: return emptyList<Cell>() to 0L
    val n = pieces.size
    if (n == 0 || item.totalBytes <= 0) return emptyList<Cell>() to 0L
    val group = (n + MAX_PIECE_CELLS - 1) / MAX_PIECE_CELLS
    val count = (n + group - 1) / group
    val flight = BooleanArray(count)
    if (item.status == DownloadStatus.Downloading) {
        for (i in item.inFlight) if (i in 0 until n) flight[i / group] = true
    }
    val finished = item.status == DownloadStatus.Finished
    val cells =
        List(count) { c ->
            val from = c * group
            val to = minOf(n, from + group)
            var whole = true
            if (!finished) {
                for (i in from until to) {
                    if (!pieces.has(i)) {
                        whole = false
                        break
                    }
                }
            }
            when {
                whole -> Cell(CellState.Done, -1)
                flight[c] -> Cell(CellState.InFlight, -1)
                else -> Cell(CellState.Pending, -1)
            }
        }
    // The piece length is not kept with the row: it is the power of two the size and the count allow.
    val pieceLength = if (n == 1) item.totalBytes else generateSequence(MIN_PIECE_LENGTH) { it * 2 }.first { it * n >= item.totalBytes }
    return cells to pieceLength * group
}

/** The byte runs that are done, in file order, with touching ones joined. */
private fun doneRuns(spans: List<Span>): List<LongRange> {
    val runs = ArrayList<LongRange>()
    for (s in spans.sortedBy { it.start }) {
        if (s.done <= 0) continue
        val last = s.start + s.done - 1
        val tail = runs.lastOrNull()
        if (tail != null && s.start <= tail.last + 1) {
            runs[runs.lastIndex] = tail.first..maxOf(tail.last, last)
        } else {
            runs += s.start..last
        }
    }
    return runs
}

/**
 * A real download's detail: what was measured, nothing invented. Its traffic is not bound to a network, so there are no per-network
 * rows or shares; the chunk map is built from the row's own ranges, and the chart is the last [POINTS] speeds [history] holds (this
 * session only), older ones as 0.
 */
private fun realDetail(
    item: DownloadItem,
    history: List<Long>,
    folder: String,
): Detail {
    val running = item.status == DownloadStatus.Downloading
    // A row that is not moving ends its chart at zero instead of at the speed it last reported.
    val samples = if (running || item.status == DownloadStatus.Finished) history else history + 0L
    val tail = samples.takeLast(POINTS)
    val points = List(POINTS - tail.size) { 0L } + tail
    val (cells, cellBytes) = if (item.kind == DownloadKind.Torrent) torrentCells(item) else realCells(item)
    return Detail(
        item = item,
        rows = emptyList(),
        total = if (running) item.bytesPerSec else 0L,
        avg = if (history.isEmpty()) 0L else history.sum() / history.size,
        peak = points.max(),
        aloneFactor = null,
        series = listOf(points),
        cells = cells,
        cellBytes = cellBytes,
        retries = 0,
        // A torrent's peers are the ones connected now; nothing is connected to a row that is not running.
        peers = if (item.kind == DownloadKind.Torrent && running) item.peers else 0,
        elapsedSec = 0,
        folder = folder,
        real = true,
        groupedPieces = item.pieces?.size?.takeIf { item.kind == DownloadKind.Torrent && it > cells.size } ?: 0,
    )
}

fun buildDetail(
    item: DownloadItem,
    networks: List<Network>,
    off: Set<String> = emptySet(),
    folder: String = "~/Downloads",
    /** The speeds a real download reported this session, oldest first. */
    history: List<Long> = emptyList(),
): Detail {
    if ((item.kind == DownloadKind.Direct && item.url != null) || item.realTorrent()) return realDetail(item, history, folder)
    val torrent = item.kind == DownloadKind.Torrent
    val finished = item.status == DownloadStatus.Finished
    val flags = networks.map { it.enabled && it.id !in off }
    val onIdx = flags.indices.filter { flags[it] }
    val n = onIdx.size
    val nominal = if (n == 0) emptyList() else splitEven(item.bytesPerSec, n)
    val doneParts = if (n == 0) emptyList() else splitEven(if (finished) item.totalBytes else item.doneBytes, n)
    val peerParts = if (n == 0 || !torrent) emptyList() else splitEven(TORRENT_PEERS.toLong(), n)
    val rng = Lcg(seedOf(item.id))
    val elapsed = if (finished) maxOf(1L, item.totalBytes / FINISHED_RATE) else 0L
    val finishedAvg = if (finished) item.totalBytes / elapsed else 0L
    val finishedPeak = (finishedAvg * PEAK_FACTOR).toLong()
    val curve = finishedCurve(finishedPeak)
    val curveParts = if (n == 0) emptyList() else curve.map { splitEven(it, n) }

    fun pos(row: Int) = onIdx.indexOf(row)
    val speeds = networks.indices.map { r -> if (item.status == DownloadStatus.Downloading && pos(r) >= 0) nominal[pos(r)] else 0L }
    val downloaded = networks.indices.map { r -> if (pos(r) >= 0) doneParts[pos(r)] else 0L }
    val shareList = shares(onIdx.map { downloaded[it] }.let { w -> if (w.sum() > 0) w else onIdx.map { speeds[it] } })
    val series =
        networks.indices.map { r ->
            val p = pos(r)
            when {
                p < 0 -> {
                    List(POINTS) { 0L }
                }

                finished -> {
                    curveParts.map { it[p] }
                }

                item.status == DownloadStatus.Queued -> {
                    List(POINTS) { 0L }
                }

                else -> {
                    val s = activeSeries(nominal[p], rng)
                    if (item.status == DownloadStatus.Paused) s.mapIndexed { i, v -> if (i >= POINTS - PAUSED_TAIL) 0L else v } else s
                }
            }
        }
    val totals = List(POINTS) { i -> series.sumOf { it[i] } }
    val rows =
        networks.mapIndexed { r, net ->
            val p = pos(r)
            NetworkRow(
                id = net.id,
                name = net.name,
                kind = net.kind,
                on = p >= 0,
                speed = speeds[r],
                downloaded = downloaded[r],
                share = if (p >= 0) shareList[p] else 0,
                streams =
                    if (p < 0) {
                        0
                    } else if (torrent) {
                        peerParts[p].toInt()
                    } else {
                        DIRECT_STREAMS
                    },
                up = 0,
                uploaded = 0,
                disabled = !net.enabled,
                color = net.color,
            )
        }
    val total = speeds.sum()
    val fastest = speeds.maxOrNull() ?: 0L
    val (cells, cellBytes) = buildCells(item, onIdx, torrent)
    return Detail(
        item = item,
        rows = rows,
        total = total,
        avg = if (finished) finishedAvg else totals.sum() / POINTS,
        peak = totals.max(),
        aloneFactor = if (n >= 2 && fastest > 0) total.toDouble() / fastest else null,
        series = series,
        cells = cells,
        cellBytes = cellBytes,
        retries = if (!torrent && n >= 2 && item.status != DownloadStatus.Queued) seedOf(item.id) % 40 else 0,
        peers = if (torrent && n > 0) TORRENT_PEERS else 0,
        elapsedSec = elapsed,
        folder = folder,
    )
}

/** Flips [id] in [off]; the last enabled network stays on and networks disabled in the menu are ignored. */
fun toggleOff(
    off: Set<String>,
    networks: List<Network>,
    id: String,
): Set<String> {
    val target = networks.firstOrNull { it.id == id && it.enabled } ?: return off
    if (id in off) return off - id
    val live = networks.count { it.enabled && it.id !in off }
    return if (live <= 1) off else off + target.id
}

fun showMap(d: Detail): Boolean = d.cells.isNotEmpty() && (d.real || d.item.kind == DownloadKind.Torrent || d.rows.count { it.on } >= 2)

/** The connections a real download shows: the open ones while it runs (none included), else the count it was added with. */
fun connectionCount(item: DownloadItem): Int = if (item.status == DownloadStatus.Downloading) item.liveConnections else item.connections

/** Three round tick values in the display unit, returned in bytes per second. */
fun niceTicks(
    maxBytesPerSec: Long,
    unit: SpeedUnit,
): List<Long> {
    val perUnit = if (unit == SpeedUnit.MBps) MIB.toDouble() else MIB / 8.0
    val max = maxBytesPerSec / perUnit
    val raw = if (max <= 0) 1.0 else max / 3
    val base = 10.0.pow(floor(log10(raw)))
    val step = listOf(1.0, 2.0, 5.0, 10.0).map { it * base }.first { it >= raw - 1e-9 }
    return List(3) { ((it + 1) * step * perUnit).toLong() }
}

fun formatClock(seconds: Long): String = "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"

fun graphDescription(
    d: Detail,
    unit: SpeedUnit,
): String =
    if (d.real) {
        "Speed during this session, peak ${formatSpeed(d.peak, unit)}"
    } else if (d.item.status == DownloadStatus.Finished) {
        "Speed over the download, peak ${formatSpeed(d.peak, unit)}"
    } else {
        "Throughput, last 60 seconds, peak ${formatSpeed(d.peak, unit)}"
    }
