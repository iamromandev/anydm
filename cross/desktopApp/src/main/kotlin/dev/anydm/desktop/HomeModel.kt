package dev.anydm.desktop

import dev.anydm.engine.DownloadSnapshot
import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.DownloadStatus
import dev.anydm.engine.queue.CommandResult
import dev.anydm.engine.queue.RemoveFiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the filter menu offers. */
enum class HomeFilter(
    val label: String,
) {
    ALL("All downloads"),
    IN_PROGRESS("In progress"),
    FINISHED("Finished"),
    NEEDS_ATTENTION("Needs attention"),
}

/** The sections of the list, in the order they show. */
enum class GroupKind(
    val label: String,
) {
    DOWNLOADING("Downloading"),
    QUEUED("Queued"),
    PAUSED("Paused"),
    NEEDS_ATTENTION("Needs attention"),
    FINISHED("Finished"),
    ;

    internal val filter: HomeFilter
        get() =
            when (this) {
                FINISHED -> HomeFilter.FINISHED
                NEEDS_ATTENTION -> HomeFilter.NEEDS_ATTENTION
                else -> HomeFilter.IN_PROGRESS
            }
}

/** Why the list has no rows. */
enum class Empty { NONE, NO_DOWNLOADS, NOTHING_FOR_FILTER }

/** The one thing a row's button does. */
enum class RowAction { PAUSE, RESUME, RETRY }

/** A stretch of a row's progress bar: [fraction] of the file, in the color of network [key] (the accent when null). */
data class BarSegment(
    val key: String?,
    val fraction: Float,
)

data class HomeRow(
    val id: DownloadId,
    val name: String,
    /** FILE, ZIP, DIR, TORRENT: what the row is, in a few letters. */
    val badge: String,
    val kind: GroupKind,
    val detail: String,
    /** Bytes per second while it downloads; null otherwise. */
    val speed: Long?,
    val action: RowAction?,
    val segments: List<BarSegment>,
    /** It stopped on an error: its detail line and bar are red. */
    val failed: Boolean,
    /** It is not moving: its bar is faded. */
    val idle: Boolean,
    val selected: Boolean,
)

data class HomeGroup(
    val kind: GroupKind,
    val rows: List<HomeRow>,
    val allSelected: Boolean,
    val someSelected: Boolean,
)

/** How many of the selected rows each bulk command fits. */
data class BulkActions(
    val pause: Int,
    val resume: Int,
    val retry: Int,
    val remove: Int,
)

/** The networks pill: how many networks there are, their keys in the order shown, and what they carry together. */
data class NetworksPill(
    val count: Int,
    val keys: List<String>,
    val speed: Long,
)

data class HomeState(
    val filter: HomeFilter = HomeFilter.ALL,
    val counts: Map<HomeFilter, Int> = HomeFilter.entries.associateWith { 0 },
    val groups: List<HomeGroup> = emptyList(),
    val summary: String? = null,
    val empty: Empty = Empty.NO_DOWNLOADS,
    val selection: Set<DownloadId> = emptySet(),
    val bulk: BulkActions = BulkActions(0, 0, 0, 0),
    /** How many rows "Clear finished list" would take off: the completed ones (a seeding row is finished but stays). */
    val clearableCount: Int = 0,
    val canClearFinished: Boolean = false,
    val confirmClear: Boolean = false,
    /** The selected rows are waiting for the answer to "Remove from list?". */
    val confirmRemove: Boolean = false,
    val message: String? = null,
    val pill: NetworksPill = NetworksPill(0, emptyList(), 0),
)

private data class Local(
    val filter: HomeFilter = HomeFilter.ALL,
    val selected: Set<DownloadId> = emptySet(),
    val confirmClear: Boolean = false,
    val confirmRemove: Boolean = false,
    val message: String? = null,
)

/**
 * The home screen's state and what it can do: what is listed and how it is grouped, the filter, the selection, the bulk
 * commands and the confirmed clearing of finished rows. Commands run in [scope] and report a failure in [HomeState.message].
 */
class HomeModel(
    private val source: EngineSource,
    private val scope: CoroutineScope,
) {
    private val local = MutableStateFlow(Local())

    val state: StateFlow<HomeState> =
        combine(source.downloads, source.networks, source.totalSpeed, local) { downloads, networks, speed, local ->
            build(downloads, local).copy(
                pill = NetworksPill(networks.networks.size, networks.networks.map { it.info.key }, speed.current),
            )
        }.stateIn(scope, SharingStarted.Eagerly, HomeState())

    init {
        // A question about rows that are gone is dropped, not kept for when other rows come: it must not pop up unasked. Only
        // the moment the rows go counts: the state may not have caught up with a question just asked.
        scope.launch {
            var clearable = 0
            var selected = 0
            state.collect { now ->
                val clearGone = clearable > 0 && now.clearableCount == 0
                val selectionGone = selected > 0 && now.selection.isEmpty()
                clearable = now.clearableCount
                selected = now.selection.size
                if (clearGone || selectionGone) {
                    local.update {
                        it.copy(confirmClear = it.confirmClear && !clearGone, confirmRemove = it.confirmRemove && !selectionGone)
                    }
                }
            }
        }
    }

    // Commands read the engine's rows and the local choices as they are now, not the state a scope may not have caught up to.
    private fun current(): HomeState = build(source.downloads.value, local.value)

    fun setFilter(filter: HomeFilter) = local.update { it.copy(filter = filter, selected = emptySet(), confirmRemove = false) }

    fun toggle(id: DownloadId) =
        local.update {
            it.copy(selected = if (id in it.selected) it.selected - id else it.selected + id, confirmRemove = false)
        }

    fun toggleGroup(kind: GroupKind) {
        val group = current().groups.firstOrNull { it.kind == kind } ?: return
        val ids = group.rows.map { it.id }.toSet()
        local.update { it.copy(selected = if (group.allSelected) it.selected - ids else it.selected + ids, confirmRemove = false) }
    }

    fun selectAll() {
        val ids =
            current()
                .groups
                .flatMap { it.rows }
                .map { it.id }
                .toSet()
        local.update { it.copy(selected = ids, confirmRemove = false) }
    }

    fun clearSelection() = local.update { it.copy(selected = emptySet(), confirmRemove = false) }

    /** What the row's own button does. */
    fun perform(id: DownloadId) {
        val row =
            current()
                .groups
                .flatMap { it.rows }
                .firstOrNull { it.id == id } ?: return
        when (row.action) {
            RowAction.PAUSE -> run("pause", listOf(id)) { source.pause(it) }
            RowAction.RESUME -> run("resume", listOf(id)) { source.resume(it) }
            RowAction.RETRY -> run("retry", listOf(id)) { source.retry(it, allowDuplicate = false) }
            null -> Unit
        }
    }

    fun pauseSelected() = selected { it.action == RowAction.PAUSE }.let { run("pause", it) { id -> source.pause(id) } }

    fun resumeSelected() = selected { it.action == RowAction.RESUME }.let { run("resume", it) { id -> source.resume(id) } }

    fun retrySelected() =
        selected { it.action == RowAction.RETRY }.let { run("retry", it) { id -> source.retry(id, allowDuplicate = false) } }

    /**
     * Asks before taking the selected rows off the list; only completed rows go at once (a row still moving, failed or
     * seeding would lose its progress or stop seeding).
     */
    fun askRemoveSelected() {
        val ids = selected { true }
        if (ids.isEmpty()) return
        val completed =
            source.downloads.value
                .filter { it.download.status == DownloadStatus.COMPLETED }
                .map { it.download.id }
                .toSet()
        if (completed.containsAll(ids)) removeNow(ids) else local.update { it.copy(confirmRemove = true) }
    }

    fun dismissRemove() = local.update { it.copy(confirmRemove = false) }

    /** Takes the selected rows off the list; their files stay where they are. */
    fun confirmRemoveSelected() {
        val ids = selected { true }
        local.update { it.copy(confirmRemove = false) }
        removeNow(ids)
    }

    private fun removeNow(ids: List<DownloadId>) {
        run("remove", ids) { id -> source.remove(id, RemoveFiles.KEEP) }
        local.update { it.copy(selected = it.selected - ids.toSet()) }
    }

    fun askClearFinished() {
        if (current().canClearFinished) local.update { it.copy(confirmClear = true) }
    }

    fun dismissClearFinished() = local.update { it.copy(confirmClear = false) }

    /** Takes every finished row off the list; the files stay where they are. */
    fun confirmClearFinished() {
        val ids = finished.map { it.download.id }
        local.update { it.copy(confirmClear = false) }
        run("remove", ids) { id -> source.remove(id, RemoveFiles.KEEP) }
    }

    fun dismissMessage() = local.update { it.copy(message = null) }

    private val finished: List<DownloadSnapshot>
        get() = listed(source.downloads.value).filter { it.download.status == DownloadStatus.COMPLETED }

    private fun selected(fits: (HomeRow) -> Boolean): List<DownloadId> {
        val state = current()
        return state.groups
            .flatMap { it.rows }
            .filter { it.id in state.selection && fits(it) }
            .map { it.id }
    }

    private fun run(
        verb: String,
        ids: List<DownloadId>,
        command: suspend (DownloadId) -> CommandResult,
    ) {
        if (ids.isEmpty()) return
        scope.launch {
            var failed = 0
            var stopped = false
            for (id in ids) {
                try {
                    if (command(id) !is CommandResult.Done) failed++
                } catch (e: CancellationException) {
                    throw e
                } catch (_: IllegalStateException) {
                    stopped = true
                    break
                }
            }
            val message =
                when {
                    stopped -> "The engine has stopped."
                    failed > 0 -> "$failed of ${ids.size} ${if (ids.size == 1) "download" else "downloads"} could not be ${verb.past()}."
                    else -> null
                }
            if (message != null) local.update { it.copy(message = message) }
        }
    }

    private fun String.past() =
        when (this) {
            "pause" -> "paused"
            "resume" -> "resumed"
            "retry" -> "retried"
            else -> "removed"
        }

    private fun build(
        downloads: List<DownloadSnapshot>,
        local: Local,
    ): HomeState {
        val all = listed(downloads).map { it to groupOf(it.download.status) }
        val counts =
            HomeFilter.entries.associateWith { filter ->
                all.count { (_, kind) -> filter == HomeFilter.ALL || kind.filter == filter }
            }
        val shown = all.filter { (_, kind) -> local.filter == HomeFilter.ALL || kind.filter == local.filter }
        // Only what is still listed counts: a row that went is no longer selected.
        val listedIds = shown.map { (snapshot, _) -> snapshot.download.id }.toSet()
        val selection = local.selected.filter { it in listedIds }.toSet()
        val groups =
            GroupKind.entries.mapNotNull { kind ->
                val rows =
                    shown
                        .filter { (_, k) -> k == kind }
                        .map { (snapshot, _) -> rowOf(snapshot, kind, snapshot.download.id in selection) }
                if (kind == GroupKind.QUEUED) {
                    // Waiting rows go in the order they were added.
                    return@mapNotNull rows.takeIf { it.isNotEmpty() }?.let { group(kind, it.sortedBy { r -> r.id.value }, selection) }
                }
                rows.takeIf { it.isNotEmpty() }?.let { group(kind, it, selection) }
            }
        val clearable = all.count { (snapshot, _) -> snapshot.download.status == DownloadStatus.COMPLETED }
        val chosen = groups.flatMap { it.rows }.filter { it.id in selection }
        return HomeState(
            filter = local.filter,
            counts = counts,
            groups = groups,
            summary = if (all.map { it.second }.distinct().size > 1) summaryOf(all.map { it.second }) else null,
            empty =
                when {
                    groups.isNotEmpty() -> Empty.NONE
                    local.filter == HomeFilter.ALL -> Empty.NO_DOWNLOADS
                    else -> Empty.NOTHING_FOR_FILTER
                },
            selection = selection,
            bulk =
                BulkActions(
                    pause = chosen.count { it.action == RowAction.PAUSE },
                    resume = chosen.count { it.action == RowAction.RESUME },
                    retry = chosen.count { it.action == RowAction.RETRY },
                    remove = chosen.size,
                ),
            clearableCount = clearable,
            canClearFinished = clearable > 0,
            confirmClear = local.confirmClear && clearable > 0,
            confirmRemove = local.confirmRemove && selection.isNotEmpty(),
            message = local.message,
        )
    }

    private fun group(
        kind: GroupKind,
        rows: List<HomeRow>,
        selection: Set<DownloadId>,
    ): HomeGroup {
        val chosen = rows.count { it.id in selection }
        return HomeGroup(kind, rows, allSelected = chosen == rows.size, someSelected = chosen in 1 until rows.size)
    }

    private fun summaryOf(kinds: List<GroupKind>): String {
        val parts =
            listOf(
                GroupKind.DOWNLOADING to "downloading",
                GroupKind.QUEUED to "queued",
                GroupKind.PAUSED to "paused",
                GroupKind.NEEDS_ATTENTION to "need attention",
            ).mapNotNull { (kind, text) -> kinds.count { it == kind }.takeIf { it > 0 }?.let { "$it $text" } }
        return if (parts.isEmpty()) "all done" else parts.joinToString(" · ")
    }
}

/** What the list shows: top-level jobs the user added, not cancelled ones, a collection's items or the engine's own tools. */
private fun listed(downloads: List<DownloadSnapshot>): List<DownloadSnapshot> =
    downloads.filter {
        it.download.status != DownloadStatus.CANCELLED && it.download.parentId == null && it.download.kind != DownloadKind.TOOL
    }

private fun groupOf(status: DownloadStatus): GroupKind =
    when (status) {
        DownloadStatus.DOWNLOADING, DownloadStatus.MUXING -> GroupKind.DOWNLOADING
        DownloadStatus.QUEUED, DownloadStatus.PENDING -> GroupKind.QUEUED
        DownloadStatus.PAUSED -> GroupKind.PAUSED
        DownloadStatus.FAILED -> GroupKind.NEEDS_ATTENTION
        DownloadStatus.COMPLETED, DownloadStatus.SEEDING, DownloadStatus.CANCELLED -> GroupKind.FINISHED
    }

private fun rowOf(
    snapshot: DownloadSnapshot,
    kind: GroupKind,
    selected: Boolean,
): HomeRow {
    val status = snapshot.download.status
    val failed = kind == GroupKind.NEEDS_ATTENTION
    val total = snapshot.total ?: snapshot.download.size
    val percent = progress(snapshot)?.let { (it * 100).toInt() }
    val sizes = sizesOf(snapshot.downloaded, total)
    val queuedSize = if (snapshot.downloaded == 0L && total != null) formatBytes(total) else sizes
    val detail =
        when {
            kind == GroupKind.FINISHED -> listOfNotNull(formatBytes(total ?: snapshot.downloaded), hostOf(snapshot)).joinToString(" · ")
            failed -> snapshot.lastFailure?.message ?: "Something went wrong"
            status == DownloadStatus.PAUSED -> if (percent != null) "Paused at $percent% · $sizes" else "Paused · $sizes"
            kind == GroupKind.QUEUED -> "Waiting for a turn · $queuedSize"
            status == DownloadStatus.MUXING -> "Finishing · $sizes"
            else -> listOfNotNull(percent?.let { "$it%" }, sizes, eta(snapshot)).joinToString(" · ")
        }
    val action =
        when (status) {
            DownloadStatus.DOWNLOADING, DownloadStatus.QUEUED, DownloadStatus.PENDING -> RowAction.PAUSE
            DownloadStatus.PAUSED -> RowAction.RESUME
            DownloadStatus.FAILED -> RowAction.RETRY
            else -> null
        }
    return HomeRow(
        id = snapshot.download.id,
        name = displayName(snapshot),
        badge = badgeOf(snapshot),
        kind = kind,
        detail = detail,
        speed = snapshot.speed.takeIf { status == DownloadStatus.DOWNLOADING },
        action = action,
        segments = if (kind == GroupKind.FINISHED) emptyList() else segmentsOf(snapshot, total, failed),
        failed = failed,
        idle = kind == GroupKind.PAUSED || kind == GroupKind.QUEUED,
        selected = selected,
    )
}

private fun sizesOf(
    downloaded: Long,
    total: Long?,
): String = if (total != null && total > 0) "${formatBytes(downloaded)} of ${formatBytes(total)}" else formatBytes(downloaded)

// Each network's share of the file in its own color; with no share told, what is done in the accent.
private fun segmentsOf(
    snapshot: DownloadSnapshot,
    total: Long?,
    failed: Boolean,
): List<BarSegment> {
    if (total == null || total <= 0) return emptyList()

    fun fraction(bytes: Long) = (bytes.toDouble() / total).toFloat().coerceIn(0f, 1f)
    if (failed) return listOf(BarSegment(null, fraction(snapshot.downloaded)))
    val shares = snapshot.networkShare.filterValues { it > 0 }.toSortedMap()
    return if (shares.isEmpty()) {
        listOf(BarSegment(null, fraction(snapshot.downloaded)))
    } else {
        shares.map { (key, bytes) -> BarSegment(key, fraction(bytes)) }
    }
}

internal fun badgeOf(snapshot: DownloadSnapshot): String {
    val download = snapshot.download
    if (download.collection != null) return "DIR"
    if (download.kind == DownloadKind.TORRENT) return "TORRENT"
    val extension = displayName(snapshot).substringAfterLast('.', "").takeIf { it.length in 1..4 && it.all(Char::isLetterOrDigit) }
    return extension?.uppercase() ?: "FILE"
}

private fun hostOf(snapshot: DownloadSnapshot): String? =
    snapshot.mirrors
        .firstOrNull()
        ?.url
        ?.let { runCatching { java.net.URI(it).host }.getOrNull() }
        ?.removePrefix("www.")
