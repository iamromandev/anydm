package dev.anydm.store

import dev.anydm.model.CategoryDto
import dev.anydm.model.DOWNLOADS_CATEGORY_ID
import dev.anydm.model.ProgressDto
import dev.anydm.model.Task
import dev.anydm.model.TaskKind
import dev.anydm.model.TaskStatus

/**
 * The list after a `task` or `tasks` frame (web: `placeRows`).
 *
 * A row already held is updated where it is, so a group's every recount doesn't jump it
 * to the top; only rows new to the list go on top. A group's videos never enter it, and
 * a cancelled row leaves it: cancelling publishes the row it just removed.
 */
fun placeRows(
    held: List<Task>,
    rows: List<Task>,
): List<Task> {
    val top = rows.filter { it.parentId == null }
    val removed = top.filter { it.status == TaskStatus.CANCELLED }.map { it.id }.toSet()
    val live = top.filter { it.status != TaskStatus.CANCELLED }.associateBy { it.id }
    val heldIds = held.map { it.id }.toSet()
    val fresh = live.values.filter { it.id !in heldIds }
    val updated = held.filter { it.id !in removed }.map { live[it.id] ?: it }
    return fresh + updated
}

/**
 * A fetched page, minus anything the stream said since it was asked for (web: `settlePage`).
 *
 * For each id in [touched], what is held wins: its current copy, its absence if the stream
 * removed it, or a row the page was read too early to include. Everything else is the page's.
 */
fun settlePage(
    fetched: List<Task>,
    held: List<Task>,
    touched: Set<String>,
): List<Task> {
    val live = held.associateBy { it.id }
    val listed = fetched.map { it.id }.toSet()
    val settled = fetched.mapNotNull { if (it.id in touched) live[it.id] else it }
    return held.filter { it.id in touched && it.id !in listed } + settled
}

/** A later page added to what's on screen: held rows keep their place, a newer copy wins in it. */
fun appendPage(
    existing: List<Task>,
    incoming: List<Task>,
): List<Task> {
    val byId = incoming.associateBy { it.id }
    val seen = existing.map { it.id }.toSet()
    return existing.map { byId[it.id] ?: it } + incoming.filter { it.id !in seen }
}

/** A progress frame's numbers on its row. An absent field means unchanged, never zero. */
fun applyProgress(
    tasks: List<Task>,
    progress: ProgressDto,
): List<Task> =
    tasks.map {
        if (it.id != progress.id) {
            it
        } else {
            val moved = progress.files.orEmpty().associate { file -> file.index to file.downloadedBytes }
            it.copy(
                progress = progress.progress ?: it.progress,
                downloadedBytes = progress.downloadedBytes ?: it.downloadedBytes,
                totalBytes = progress.totalBytes ?: it.totalBytes,
                downloadSpeed = progress.live?.speedBps ?: it.downloadSpeed,
                etaSeconds = progress.live?.etaSeconds ?: it.etaSeconds,
                uploadSpeed = progress.live?.uploadSpeedBps ?: it.uploadSpeed,
                peersConnected = progress.live?.peers ?: it.peersConnected,
                files = it.files?.map { file -> moved[file.index]?.let { bytes -> file.copy(downloadedBytes = bytes) } ?: file },
            )
        }
    }

/** Whether a row belongs under the chosen category; no choice lets every row through. web: `inCategory`. */
fun inCategory(
    task: Task,
    categoryId: String?,
): Boolean = categoryId == null || task.category?.id == categoryId

/** The API refuses the rest: a torrent stays where it was added, a group's video moves with its group, a running download waits. */
fun canMoveCategory(task: Task): Boolean =
    task.kind != TaskKind.TORRENT &&
        task.parentId == null &&
        task.status != TaskStatus.DOWNLOADING &&
        task.status != TaskStatus.MUXING

/** Where new links save: the remembered category while it exists, else Downloads. */
fun chosenCategory(
    remembered: String?,
    categories: List<CategoryDto>,
): String = remembered?.takeIf { id -> categories.any { it.id == id } } ?: DOWNLOADS_CATEGORY_ID

/**
 * Rows from a frame, with what frames never carry kept from the held copies: playback
 * positions, and a group's watched count (web: `keepPositions`, `keepWatched`).
 */
fun keepHeld(
    rows: List<Task>,
    held: List<Task>,
): List<Task> {
    val prior = held.associateBy { it.id }
    return rows.map { row ->
        val old = prior[row.id] ?: return@map row
        var kept = row
        if (kept.positions == null && old.positions != null) kept = kept.copy(positions = old.positions)
        val counts = kept.entryCounts
        val watched = old.entryCounts?.watched
        if (counts != null && counts.watched == null && watched != null) {
            kept = kept.copy(entryCounts = counts.copy(watched = watched))
        }
        kept
    }
}
