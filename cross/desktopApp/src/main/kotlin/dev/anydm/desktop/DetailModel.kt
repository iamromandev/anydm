package dev.anydm.desktop

import dev.anydm.engine.DownloadSnapshot
import dev.anydm.engine.TorrentDetailResult
import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.DownloadStatus
import dev.anydm.engine.net.SpeedLimit
import dev.anydm.engine.queue.CommandResult
import dev.anydm.engine.queue.RemoveFiles
import dev.anydm.engine.torrent.PieceMap
import dev.anydm.engine.torrent.TorrentPeer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Instant

/** Which of the four pages a download gets. */
enum class DetailKind { ACTIVE, TORRENT, FINISHED, FAILED }

/** What a download's page is asking the user to confirm. */
enum class DetailConfirm { CANCEL, TRASH, REMOVE_SEEDING }

/** What the network checkbox of the table does, said beside it: the engine switches networks for every download. */
const val NETWORK_SWITCH_HINT = "Applies to all downloads"

/**
 * The page a download gets: by what it is doing, and for a torrent in progress, what it is. A CANCELLED download has no
 * page ([DetailModel] shows it as gone); it is listed with the finished ones only so the function covers every status.
 */
fun detailKindOf(snapshot: DownloadSnapshot): DetailKind =
    when (snapshot.download.status) {
        DownloadStatus.FAILED -> DetailKind.FAILED
        DownloadStatus.COMPLETED, DownloadStatus.SEEDING -> DetailKind.FINISHED
        DownloadStatus.CANCELLED -> DetailKind.FINISHED
        else -> if (snapshot.download.kind == DownloadKind.TORRENT) DetailKind.TORRENT else DetailKind.ACTIVE
    }

/** One network in the page's table. */
data class DetailNetwork(
    val key: String,
    val name: String,
    /** Switched on for downloads; its box in the table. */
    val enabled: Boolean,
    /** It carried bytes in the last second. */
    val active: Boolean,
    val bytes: Long,
    /** Whole percent of what the networks carried; they add up to 100. */
    val share: Int,
    val speed: Long,
    /** What it carried as a fraction of the whole file, for its bar. */
    val progress: Float,
    /** Drawn in the charts: in use, or it carried part of the file. */
    val inPlay: Boolean,
    /** The engine no longer lists it, or lists it down: what it carried stays, the name is the last one known. */
    val disconnected: Boolean = false,
)

/** One network's speeds over the last minute, oldest first; the key is empty for a speed that isn't split by network. */
data class ChartSeries(
    val key: String,
    val name: String,
    val values: List<Long>,
)

/** A torrent file in the list a torrent's page opens. */
data class TorrentFileLine(
    val index: Int,
    val path: String,
    val size: Long?,
    val selected: Boolean,
    /** Bytes of it in. */
    val done: Long,
)

/** The numbers of a finished download's summary. */
data class FinishedStats(
    val size: Long,
    /** Seconds it ran for, null when it never recorded a try. */
    val seconds: Long?,
    val avgSpeed: Long,
    val peak: Long?,
    val networks: Int,
    /** Peers of a torrent, chunks of a file; null when not known. */
    val third: Int?,
    val thirdLabel: String,
    val contributions: List<DetailNetwork>,
    val written: String,
    /** What bonding saved, when it is worth a line. */
    val timeSaved: TimeSaved? = null,
)

data class DetailState(
    /** The download is not (or no longer) listed: the page has nothing to show and goes back. */
    val gone: Boolean = true,
    val kind: DetailKind = DetailKind.ACTIVE,
    val status: DownloadStatus = DownloadStatus.PENDING,
    val name: String = "",
    val badge: String = "",
    val folder: Boolean = false,
    /** Where it saves to, as the user reads it (`~/Downloads`). */
    val savedTo: String? = null,
    val downloaded: Long = 0,
    val total: Long? = null,
    val percent: Int? = null,
    val timeLeft: String? = null,
    val paused: Boolean = false,
    val queued: Boolean = false,
    val speed: Long = 0,
    val avgSpeed: Long = 0,
    val peakSpeed: Long? = null,
    /** What limits the speed now, while it downloads; null when nothing does (#634). */
    val limit: SpeedLimit? = null,
    val badges: List<AloneBadge> = emptyList(),
    val retries: Int = 0,
    val networks: List<DetailNetwork> = emptyList(),
    /** Every network key the page knows, for coloring the same as the home screen does. */
    val colorKeys: List<String> = emptyList(),
    val chunks: ChunkMap = ChunkMap.Empty,
    val chunkLabel: String = "chunks",
    val chart: List<ChartSeries> = emptyList(),
    /** [chart] is what this page measured second by second; otherwise it is the engine's last minute of the whole. */
    val chartMeasured: Boolean = false,
    /** There is nothing to draw in [chart]: no samples were kept for this download. */
    val noSpeedHistory: Boolean = false,
    val peers: Int? = null,
    /** The peers of the last look at the torrent, fastest download first. */
    val peerList: List<TorrentPeer> = emptyList(),
    val peersOpen: Boolean = false,
    /** It is a torrent, whatever it is doing: its page has the Trackers button. */
    val isTorrent: Boolean = false,
    val trackersOpen: Boolean = false,
    val files: List<TorrentFileLine> = emptyList(),
    val filesOpen: Boolean = false,
    val uploaded: Long? = null,
    val finished: FinishedStats? = null,
    val failure: String? = null,
    val link: String? = null,
    val canFixLink: Boolean = false,
    val canOpen: Boolean = false,
    val confirm: DetailConfirm? = null,
    val fixLinkOpen: Boolean = false,
    val fixLinkError: String? = null,
    val message: String? = null,
) {
    val limitText: String? get() = limit?.let(::limitText)

    /** The Limits dialog can change it. */
    val limitFixable: Boolean get() = limit?.fixable == true
}

private data class DetailLocal(
    val confirm: DetailConfirm? = null,
    val fixLinkOpen: Boolean = false,
    val fixLinkError: String? = null,
    val message: String? = null,
    val series: Map<String, List<Long>> = emptyMap(),
    val seriesLength: Int = 0,
    val speeds: Map<String, Long> = emptyMap(),
    val peak: Long = 0,
    val pieces: PieceMap? = null,
    val peerCount: Int? = null,
    val peerList: List<TorrentPeer> = emptyList(),
    val peersOpen: Boolean = false,
    val trackersOpen: Boolean = false,
    val filesOpen: Boolean = false,
    /** File ticks sent and not yet seen in the engine's list, by torrent file index. */
    val fileOverlay: Map<Int, Boolean> = emptyMap(),
)

/**
 * The page of one download: what to show for it by what it is doing, the per-network speeds measured from the byte totals
 * (a [NetworkSampler] ticked every [tickMillis] while it downloads), a torrent's pieces polled every [pollMillis], and the
 * actions: pause and resume, cancel and move to the trash (both after a confirmation), retry, a new link, open, reveal.
 * Commands run in [scope], to their answer even if [scope] is cancelled meanwhile, and report a failure in [DetailState.message].
 */
class DetailModel(
    private val source: EngineSource,
    private val files: FileActions,
    val id: DownloadId,
    private val scope: CoroutineScope,
    private val tickMillis: Long = 1000,
    private val pollMillis: Long = 2000,
    private val homeFolder: String = System.getProperty("user.home").orEmpty(),
    private val clock: () -> Instant = { Clock.System.now() },
    private val exists: (Path) -> Boolean = { Files.exists(it) },
    /** The names people gave networks; where one has a name, it is shown in place of the OS's. */
    private val looks: StateFlow<NetworkLooks> = MutableStateFlow(NetworkLooks()),
) {
    private val local = MutableStateFlow(DetailLocal())
    private val sampler = NetworkSampler()
    private val filesLock = Mutex()
    private val knownNames = ConcurrentHashMap<String, String>()
    private var lastTickAt: Instant = clock()

    // The status the torrent had when the engine last answered with its detail; null until it has.
    private var detailStatus: DownloadStatus? = null
    private var sawNetworks = false

    val state: StateFlow<DetailState> =
        combine(source.downloads, source.networks, local, looks) { downloads, networks, local, looks ->
            build(downloads.firstOrNull { it.download.id == id }, networks.networks, local, looks.names)
        }.stateIn(
            scope,
            SharingStarted.Eagerly,
            build(
                source.downloads.value.firstOrNull {
                    it.download.id == id
                },
                source.networks.value.networks,
                DetailLocal(),
                looks.value.names,
            ),
        )

    init {
        scope.launch {
            while (isActive) {
                delay(tickMillis)
                tick()
            }
        }
        scope.launch {
            while (isActive) {
                poll()
                delay(pollMillis)
            }
        }
    }

    private fun snapshot(): DownloadSnapshot? = source.downloads.value.firstOrNull { it.download.id == id }

    private fun tick() {
        // The time that really passed, not the one asked for: a busy machine ticks late.
        val now = clock()
        val elapsed = (now - lastTickAt).inWholeMilliseconds.takeIf { it > 0 } ?: tickMillis
        lastTickAt = now
        val snapshot = snapshot() ?: return
        pruneFileOverlay(snapshot)
        // The history only grows while it downloads: a pause leaves it where it was.
        if (snapshot.download.status != DownloadStatus.DOWNLOADING) return
        sampler.tick(snapshot.networkShare, elapsed)
        local.update {
            it.copy(
                series = sampler.series,
                seriesLength = sampler.length,
                speeds = sampler.series.keys.associateWith { key -> sampler.latest(key) },
                peak = maxOf(it.peak, snapshot.speed),
            )
        }
    }

    private suspend fun poll() {
        val snapshot = snapshot() ?: return
        if (snapshot.download.kind != DownloadKind.TORRENT) return
        // Pieces and peers only change while it transfers: a paused or queued torrent is asked once per status, for a look
        // at where it stopped; an answer that is no detail (not in the session yet) is no look, so it is asked again.
        val status = snapshot.download.status
        val moving = status == DownloadStatus.DOWNLOADING || status == DownloadStatus.SEEDING
        if (detailStatus == status && !moving) return
        try {
            val result = source.torrentDetail(id)
            if (result is TorrentDetailResult.Detail) {
                detailStatus = status
                local.update {
                    it.copy(
                        pieces = result.detail.pieces,
                        peerCount = result.detail.peers.size,
                        peerList = result.detail.peers.sortedByDescending { peer -> peer.downloadRate },
                    )
                }
            } else if (!moving) {
                // A torrent that stopped (paused, queued) and is no longer in the session has no peers; what the last poll
                // showed is out of date.
                local.update { it.copy(peerCount = null, peerList = emptyList()) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A poll that fails leaves what was shown.
        }
    }

    // Actions

    /** The button next to Cancel: pause while it moves or waits, resume when paused. */
    fun pauseOrResume() {
        val status = snapshot()?.download?.status ?: return
        when (status) {
            DownloadStatus.PAUSED -> {
                command("resume") { source.resume(id) }
            }

            DownloadStatus.DOWNLOADING, DownloadStatus.QUEUED, DownloadStatus.PENDING, DownloadStatus.MUXING -> {
                command("pause") { source.pause(id) }
            }

            else -> {}
        }
    }

    fun askCancel() = local.update { it.copy(confirm = DetailConfirm.CANCEL) }

    fun askTrash() = local.update { it.copy(confirm = DetailConfirm.TRASH) }

    fun dismissConfirm() = local.update { it.copy(confirm = null) }

    /** Answers the open confirmation yes: the cancel, the trash or the removal of a seeding torrent it asked about. */
    fun confirm() {
        val asked = local.value.confirm ?: return
        local.update { it.copy(confirm = null) }
        when (asked) {
            DetailConfirm.CANCEL -> command("cancel") { source.cancel(id) }

            // The engine trashes each file of the download itself, stops a seed first, and keeps the job if the trash refuses.
            DetailConfirm.TRASH -> command("move to the trash") { source.remove(id, RemoveFiles.TRASH) }

            DetailConfirm.REMOVE_SEEDING -> command("remove") { source.remove(id, RemoveFiles.KEEP) }
        }
    }

    /**
     * Takes it off the list, the files stay; a seeding torrent asks first, as it stops seeding. On a FAILED download this
     * keeps its partial files too: only the entry goes.
     */
    fun remove() {
        if (snapshot()?.download?.status == DownloadStatus.SEEDING) {
            local.update { it.copy(confirm = DetailConfirm.REMOVE_SEEDING) }
        } else {
            command("remove") { source.remove(id, RemoveFiles.KEEP) }
        }
    }

    fun retry() = command("retry") { source.retry(id, allowDuplicate = false) }

    fun openFixLink() = local.update { it.copy(fixLinkOpen = true, fixLinkError = null) }

    fun dismissFixLink() = local.update { it.copy(fixLinkOpen = false, fixLinkError = null) }

    /** Replaces the link of the download with [url]; it carries on from where it stopped. */
    fun relink(url: String) {
        val link = url.trim()
        val mirror = snapshot()?.mirrors?.firstOrNull() ?: return
        if (link.isEmpty()) return
        scope.launch {
            val result =
                try {
                    withContext(NonCancellable) { source.relink(id, mirror.id, link) }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: IllegalStateException) {
                    local.update { it.copy(fixLinkError = "The engine has stopped.") }
                    return@launch
                }
            if (result is CommandResult.Done) {
                local.update { it.copy(fixLinkOpen = false, fixLinkError = null) }
            } else {
                local.update { it.copy(fixLinkError = describe(result)) }
            }
        }
    }

    fun open() = withOutput { files.open(it) }

    /** Shows the file in the file manager; one not on disk yet (a download in progress) shows the nearest folder that is. */
    fun reveal() =
        withOutput { path ->
            val base = snapshot()?.download?.saveFolder?.let { Path.of(it) }
            files.reveal(revealTarget(path, base, exists))
        }

    fun copyLink() {
        val link = snapshot()?.let(::linkOf) ?: return
        files.copy(link)?.let { problem -> local.update { it.copy(message = problem) } }
    }

    /** Switches [key] on or off for downloads: the engine's switch, so for every download, not this one (see [NETWORK_SWITCH_HINT]). */
    fun setNetworkEnabled(
        key: String,
        enabled: Boolean,
    ) = command("switch the network") { source.setNetworkEnabled(key, enabled) }

    fun toggleFiles() = local.update { it.copy(filesOpen = !it.filesOpen) }

    fun togglePeers() = local.update { it.copy(peersOpen = !it.peersOpen) }

    /** The model of this torrent's Trackers dialog; its commands run in [scope]. */
    fun newTrackersModel(scope: CoroutineScope) = TrackersModel(source, id, scope)

    fun openTrackers() = local.update { it.copy(trackersOpen = true) }

    fun closeTrackers() = local.update { it.copy(trackersOpen = false) }

    /**
     * Ticks or unticks a torrent file; at least one stays ticked. The tick shows at once and stays until the engine's list
     * agrees, so a second click before the first is answered builds on it.
     */
    fun setFileSelected(
        index: Int,
        selected: Boolean,
    ) {
        val snapshot = snapshot() ?: return
        val next = selection(snapshot, local.value.fileOverlay).mapValues { (i, on) -> if (i == index) selected else on }
        if (next.values.none { it }) {
            local.update { it.copy(message = "Keep at least one file.") }
            return
        }
        local.update { it.copy(fileOverlay = it.fileOverlay + (index to selected)) }
        scope.launch {
            val done =
                runCommand("change the files") {
                    // Sent in turn, each with everything ticked so far: the last to arrive holds them all.
                    filesLock.withLock {
                        val now = snapshot() ?: return@withLock CommandResult.NotFound
                        val priorities = selection(now, local.value.fileOverlay).values.map { if (it) NORMAL_PRIORITY else 0 }
                        source.setTorrentFiles(id, priorities)
                    }
                }
            if (!done) local.update { it.copy(fileOverlay = it.fileOverlay - index) }
        }
    }

    // Torrent file index to ticked: the engine's list with the ticks it has not shown yet laid over it.
    private fun selection(
        snapshot: DownloadSnapshot,
        overlay: Map<Int, Boolean>,
    ): Map<Int, Boolean> =
        snapshot.files
            .sortedBy { it.file.index }
            .associate { it.file.index to (overlay[it.file.index] ?: it.file.selected) }

    private fun pruneFileOverlay(snapshot: DownloadSnapshot) {
        if (local.value.fileOverlay.isEmpty()) return
        val engine = snapshot.files.associate { it.file.index to it.file.selected }
        local.update { it.copy(fileOverlay = it.fileOverlay.filter { (i, on) -> engine[i] != on }) }
    }

    fun dismissMessage() = local.update { it.copy(message = null) }

    private fun withOutput(action: (Path) -> String?) {
        val path = snapshot()?.let(::outputPath)
        if (path == null) {
            local.update { it.copy(message = "There is no file to show.") }
            return
        }
        action(path)?.let { problem -> local.update { it.copy(message = problem) } }
    }

    private fun command(
        verb: String,
        block: suspend () -> CommandResult,
    ) {
        scope.launch { runCommand(verb, block) }
    }

    // What the user asked for runs to its answer even when they leave the page meanwhile (Back cancels the page's scope).
    // True when the engine did it.
    private suspend fun runCommand(
        verb: String,
        block: suspend () -> CommandResult,
    ): Boolean {
        val message =
            try {
                val result = withContext(NonCancellable) { block() }
                if (result is CommandResult.Done) null else "Could not $verb: ${describe(result)}"
            } catch (e: CancellationException) {
                throw e
            } catch (_: IllegalStateException) {
                "The engine has stopped."
            }
        if (message != null) local.update { it.copy(message = message) }
        return message == null
    }

    private fun describe(result: CommandResult): String =
        when (result) {
            CommandResult.NotFound -> "the download is no longer there."
            is CommandResult.Rejected -> "it can't be done from where the download is."
            is CommandResult.Invalid -> result.reason
            CommandResult.TrashUnavailable -> "there is no trash on this machine."
            is CommandResult.TrashFailed -> "a file could not be moved there."
            else -> "it did not work."
        }

    // State

    private fun build(
        snapshot: DownloadSnapshot?,
        bonded: List<dev.anydm.engine.net.BondedNetwork>,
        local: DetailLocal,
        custom: Map<String, String>,
    ): DetailState {
        for (net in bonded) knownNames[net.info.key] = net.info.name
        if (bonded.isNotEmpty()) sawNetworks = true
        // A cancelled download has no page: it is gone from here, as one not listed.
        if (snapshot == null || snapshot.download.status == DownloadStatus.CANCELLED) return DetailState(gone = true)
        val download = snapshot.download
        val status = download.status
        val kind = detailKindOf(snapshot)
        val total = snapshot.total ?: download.size
        val paused = status == DownloadStatus.PAUSED || status == DownloadStatus.QUEUED || status == DownloadStatus.PENDING
        val moving = status == DownloadStatus.DOWNLOADING
        val torrent = download.kind == DownloadKind.TORRENT

        val enabled = bonded.associate { it.info.key to it.enabled }
        val up = bonded.associate { it.info.key to it.info.up }
        val keys = (bonded.map { it.info.key } + snapshot.networkShare.keys).toSortedSet().toList()
        val bytes = keys.map { snapshot.networkShare[it] ?: 0L }
        val shares = sharePercents(bytes)
        val networks =
            keys.mapIndexed { i, key ->
                val speed = if (moving) local.speeds[key] ?: 0L else 0L
                DetailNetwork(
                    key = key,
                    name = custom[key] ?: knownNames[key] ?: key,
                    enabled = enabled[key] ?: false,
                    active = speed > 0,
                    bytes = bytes[i],
                    share = shares[i],
                    speed = speed,
                    progress = if (total != null && total > 0) (bytes[i].toDouble() / total).toFloat().coerceIn(0f, 1f) else 0f,
                    inPlay = (enabled[key] ?: false) || bytes[i] > 0,
                    // Only a page that is still going has networks to lose; and before the engine has listed any, all are unknown.
                    disconnected = sawNetworks && (kind == DetailKind.ACTIVE || kind == DetailKind.TORRENT) && up[key] != true,
                )
            }
        val inPlay = networks.filter { it.inPlay }
        // The whole is the sum of what the networks measured, not the engine's own speed figure: like is compared with like.
        val bySpeed = inPlay.sumOf { it.speed } > 0
        val badges =
            if (paused || kind == DetailKind.FINISHED || kind == DetailKind.FAILED) {
                emptyList()
            } else {
                val metrics = inPlay.map { it.name to if (bySpeed) it.speed else it.bytes }
                aloneBadges(metrics.sumOf { it.second }, metrics)
            }

        val chartResult = chartOf(snapshot, kind, inPlay, local)
        val chart = chartResult.series
        val failed = snapshot.attempts.count { it.errorClass != null || it.message != null }
        val peakSpeed = (snapshot.speedSamples + listOf(snapshot.speed, local.peak)).max().takeIf { it > 0 }

        val sizes = snapshot.files.flatMap { f -> f.segments.sortedBy { it.start } }
        val chunkMap =
            when {
                torrent && local.pieces != null && local.pieces.count > 0 -> {
                    val pieces = local.pieces
                    chunkCells(List(pieces.count) { 1L }, List(pieces.count) { if (pieces.has(it)) 1L else 0L }, snapshot.networkShare)
                }

                torrent -> {
                    ChunkMap.Empty
                }

                else -> {
                    chunkCells(sizes.map { it.size }, sizes.map { it.written }, snapshot.networkShare)
                }
            }
        val seconds = elapsedSeconds(snapshot)

        val lines =
            if (torrent) {
                snapshot.files.sortedBy { it.file.index }.map {
                    TorrentFileLine(
                        it.file.index,
                        it.file.path,
                        it.file.size,
                        local.fileOverlay[it.file.index] ?: it.file.selected,
                        it.written,
                    )
                }
            } else {
                emptyList()
            }

        return DetailState(
            gone = false,
            kind = kind,
            status = status,
            name = displayName(snapshot),
            badge = badgeOf(snapshot),
            folder = download.collection != null || (torrent && snapshot.files.size > 1),
            savedTo = download.saveFolder?.let { toDisplayPath(it, homeFolder) },
            downloaded = snapshot.downloaded,
            total = total,
            percent = progress(snapshot)?.let { (it * 100).toInt() },
            timeLeft = eta(snapshot),
            paused = paused,
            queued = status == DownloadStatus.QUEUED || status == DownloadStatus.PENDING,
            speed = if (moving) snapshot.speed else 0,
            avgSpeed = if (seconds != null && seconds > 0) snapshot.downloaded / seconds else 0,
            peakSpeed = peakSpeed,
            limit = if (moving) snapshot.limitedBy else null,
            badges = badges,
            retries = failed,
            networks = networks,
            colorKeys = keys,
            chunks = chunkMap,
            chunkLabel = if (torrent) "pieces" else "chunks",
            chart = chart,
            chartMeasured = chartResult.measured,
            noSpeedHistory = chart.all { it.values.isEmpty() },
            peers = if (torrent) local.peerCount ?: snapshot.torrent?.peers else null,
            peerList = if (torrent) local.peerList else emptyList(),
            peersOpen = local.peersOpen,
            isTorrent = torrent,
            trackersOpen = local.trackersOpen,
            files = lines,
            filesOpen = local.filesOpen,
            uploaded = snapshot.torrent?.uploaded,
            finished = if (kind == DetailKind.FINISHED) finishedOf(snapshot, networks, chunkMap, seconds, chart, failed) else null,
            failure =
                if (kind ==
                    DetailKind.FAILED
                ) {
                    snapshot.lastFailure?.message ?: "The download stopped unexpectedly. Try again."
                } else {
                    null
                },
            link = linkOf(snapshot),
            canFixLink = kind == DetailKind.FAILED && !torrent && snapshot.mirrors.isNotEmpty(),
            canOpen = outputPath(snapshot) != null,
            confirm = local.confirm,
            fixLinkOpen = local.fixLinkOpen,
            fixLinkError = local.fixLinkError,
            message = local.message,
        )
    }

    private class ChartResult(
        val series: List<ChartSeries>,
        val measured: Boolean,
    )

    // The sampler's seconds once it has two. Before that, or for a page opened on a finished download, the engine's samples
    // of the whole: split by what each network carried only while it is still going (an estimate), as the total alone once
    // it is over, as the split would be shapes nobody measured.
    private fun chartOf(
        snapshot: DownloadSnapshot,
        kind: DetailKind,
        inPlay: List<DetailNetwork>,
        local: DetailLocal,
    ): ChartResult {
        if (local.seriesLength >= 2) {
            val keys = inPlay.map { it.key }.filter { it in local.series }
            if (keys.isNotEmpty()) {
                return ChartResult(
                    keys.map { key ->
                        ChartSeries(key, inPlay.first { it.key == key }.name, local.series.getValue(key))
                    },
                    true,
                )
            }
        }
        val samples = snapshot.speedSamples
        if (samples.isEmpty()) return ChartResult(emptyList(), false)
        val carried = inPlay.filter { it.bytes > 0 }
        val going = kind == DetailKind.ACTIVE || kind == DetailKind.TORRENT
        if (!going || carried.isEmpty()) return ChartResult(listOf(ChartSeries("", "Total", samples)), false)
        val total = carried.sumOf { it.bytes }.toDouble()
        return ChartResult(
            carried.map { net -> ChartSeries(net.key, net.name, samples.map { (it * (net.bytes / total)).toLong() }) },
            false,
        )
    }

    private fun finishedOf(
        snapshot: DownloadSnapshot,
        networks: List<DetailNetwork>,
        chunks: ChunkMap,
        seconds: Long?,
        chart: List<ChartSeries>,
        failed: Int,
    ): FinishedStats {
        val size = snapshot.total ?: snapshot.download.size ?: snapshot.downloaded
        val carried = networks.filter { it.bytes > 0 }
        val torrent = snapshot.download.kind == DownloadKind.TORRENT
        val pieces = if (torrent) chunks.chunks else snapshot.files.sumOf { it.segments.size }
        val written =
            listOfNotNull(
                "written in $pieces ${if (torrent) "pieces" else "chunks"}",
                if (torrent) "uploaded ${formatBytes(snapshot.torrent?.uploaded ?: 0)}" else null,
                "$failed ${if (failed == 1) "retry" else "retries"}",
            ).joinToString(" · ")
        return FinishedStats(
            size = size,
            seconds = seconds,
            avgSpeed = if (seconds != null && seconds > 0) size / seconds else 0,
            peak = snapshot.speedSamples.maxOrNull()?.takeIf { it > 0 },
            networks = carried.size,
            third = if (torrent) snapshot.torrent?.peers else pieces,
            thirdLabel = if (torrent) "Peers" else "Chunks",
            contributions = carried,
            written = written,
            // Deliberately an estimate ("about"): it uses every attempt's seconds and the lifetime shares.
            // Only a download that really finished saves time; FINISHED also covers CANCELLED.
            timeSaved =
                if (seconds != null && snapshot.download.status.let { it == DownloadStatus.COMPLETED || it == DownloadStatus.SEEDING }) {
                    timeSaved(carried.sumOf { it.bytes }, seconds, carried.map { it.name to it.bytes })
                } else {
                    null
                },
        )
    }

    // Time spent trying: each try's span, the running one up to now.
    private fun elapsedSeconds(snapshot: DownloadSnapshot): Long? {
        if (snapshot.attempts.isEmpty()) return null
        val now = clock()
        return snapshot.attempts.sumOf { ((it.endedAt ?: now) - it.startedAt).inWholeSeconds.coerceAtLeast(0) }
    }

    private companion object {
        const val NORMAL_PRIORITY = 4
    }
}

/** What to show in the file manager for [path]: itself if it exists, else the nearest parent that does, up to [base]. */
internal fun revealTarget(
    path: Path,
    base: Path?,
    exists: (Path) -> Boolean,
): Path {
    var target = path
    while (!exists(target) && target != base) target = target.parent ?: break
    return if (exists(target)) target else base ?: target
}

internal fun linkOf(snapshot: DownloadSnapshot): String? = snapshot.mirrors.firstOrNull()?.url ?: snapshot.download.sourceUrl

/**
 * Where a download's output is: one file's path, a folder's (a torrent or collection of several files that share a top
 * folder) or the save folder; null when it has no save folder.
 */
fun outputPath(snapshot: DownloadSnapshot): Path? {
    val folder = snapshot.download.saveFolder ?: return null
    val base = Path.of(folder)
    val placed = snapshot.files.sortedBy { it.file.index }.map { (it.file.placedPath ?: it.file.path).replace('\\', '/') }
    return when {
        placed.size == 1 -> {
            base.resolve(placed.single())
        }

        placed.size > 1 -> {
            val tops = placed.map { it.substringBefore('/', missingDelimiterValue = "") }.toSet()
            val top = tops.singleOrNull()
            if (top != null && top.isNotEmpty()) base.resolve(top) else base
        }

        else -> {
            snapshot.download.fileName?.let { base.resolve(it) } ?: base
        }
    }
}

/** A path under [home] as `~/...`; as it was otherwise. */
internal fun toDisplayPath(
    path: String,
    home: String,
): String {
    if (home.isEmpty()) return path
    val h = home.trimEnd('/', '\\')
    return if (path == h) {
        "~"
    } else if (path.startsWith("$h/") || path.startsWith("$h\\")) {
        "~" + path.substring(h.length)
    } else {
        path
    }
}
