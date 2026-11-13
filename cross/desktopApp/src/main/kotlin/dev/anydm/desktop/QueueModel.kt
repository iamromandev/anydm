package dev.anydm.desktop

import dev.anydm.engine.http.EngineEvent
import dev.anydm.engine.http.Span

/** The download list and the order of the Queued rows (their ids, first in line first). */
data class QueueState(
    val list: List<DownloadItem>,
    val queue: List<String>,
)

fun running(s: QueueState): Int = s.list.count { it.status == DownloadStatus.Downloading }

private fun QueueState.setStatus(
    ids: Set<String>,
    from: Set<DownloadStatus>,
    to: DownloadStatus,
): QueueState =
    copy(
        list =
            list.map {
                if (it.id in ids && it.status in from) {
                    it.copy(status = to, liveConnections = if (to == DownloadStatus.Downloading) it.liveConnections else 0)
                } else {
                    it
                }
            },
    )

/** Starts the first queued rows while a slot is free (R3). Never stops a running row. */
fun settle(
    s: QueueState,
    limit: Int,
): QueueState {
    val free = (limit - running(s)).coerceAtLeast(0)
    if (free == 0 || s.queue.isEmpty()) return s
    val start = s.queue.take(free)
    return s.setStatus(start.toSet(), setOf(DownloadStatus.Queued), DownloadStatus.Downloading).copy(queue = s.queue.drop(free))
}

fun addDownload(
    s: QueueState,
    item: DownloadItem,
    limit: Int,
): QueueState {
    val wait = running(s) >= limit
    val added = item.copy(status = if (wait) DownloadStatus.Queued else DownloadStatus.Downloading)
    return s.copy(list = s.list + added, queue = if (wait) s.queue + added.id else s.queue)
}

fun pauseAll(
    s: QueueState,
    ids: Set<String>,
    limit: Int,
): QueueState {
    val paused = s.setStatus(ids, setOf(DownloadStatus.Downloading, DownloadStatus.Queued), DownloadStatus.Paused)
    return settle(paused.copy(queue = s.queue.filter { it !in ids }), limit)
}

fun resumeAll(
    s: QueueState,
    ids: Set<String>,
    limit: Int,
): QueueState {
    var out = s
    for (it in s.list) {
        if (it.id !in ids || (it.status != DownloadStatus.Paused && it.status != DownloadStatus.Failed)) continue
        // A retry starts clean: the old reason goes with the Failed status.
        val cleared = out.copy(list = out.list.map { r -> if (r.id == it.id) r.copy(error = null) else r })
        val from = setOf(DownloadStatus.Paused, DownloadStatus.Failed)
        out =
            if (running(cleared) < limit) {
                cleared.setStatus(setOf(it.id), from, DownloadStatus.Downloading)
            } else {
                cleared.setStatus(setOf(it.id), from, DownloadStatus.Queued).copy(queue = cleared.queue + it.id)
            }
    }
    return settle(out, limit)
}

fun removeAll(
    s: QueueState,
    ids: Set<String>,
    limit: Int,
): QueueState = settle(QueueState(s.list.filter { it.id !in ids }, s.queue.filter { it !in ids }), limit)

private fun QueueState.update(
    id: String,
    only: DownloadStatus?,
    change: (DownloadItem) -> DownloadItem,
): QueueState = copy(list = list.map { if (it.id == id && (only == null || it.status == only)) change(it) else it })

/**
 * A progress report from the engine. It never changes a status; a report for a row that is no longer Downloading is stale and
 * dropped. [spans] are the ranges of a segmented download (null for a one-connection one), [connections] the ones open now.
 */
fun progressed(
    s: QueueState,
    id: String,
    done: Long,
    total: Long,
    bytesPerSec: Long,
    spans: List<Span>? = null,
    connections: Int = 1,
    partId: String? = null,
): QueueState =
    s.update(id, DownloadStatus.Downloading) {
        it.copy(
            doneBytes = done,
            totalBytes = total,
            bytesPerSec = bytesPerSec,
            spans = spans,
            partId = partId,
            liveConnections = connections,
        )
    }

/** The engine got its first response: the name, size and validators are now known (a restart reports done = 0). */
fun started(
    s: QueueState,
    e: EngineEvent.Started,
): QueueState =
    s.update(e.id, DownloadStatus.Downloading) {
        it.copy(
            name = e.name.ifBlank { it.name },
            totalBytes = e.total ?: UNKNOWN_SIZE,
            etag = e.etag,
            lastModified = e.lastModified,
            doneBytes = e.done,
            spans = e.spans,
            partId = e.partId,
            liveConnections = 1, // the first response is open
        )
    }

/**
 * The exact ranges of a job that stopped and closed its part file. They go to a row that is not running (Paused, Queued or
 * Failed) and never change its status; a Downloading row was resumed before this arrived, and runs from the engine's newer ranges.
 */
fun stopped(
    s: QueueState,
    id: String,
    done: Long,
    spans: List<Span>,
    partId: String? = null,
): QueueState =
    s.copy(
        list =
            s.list.map {
                val idle = it.status == DownloadStatus.Paused || it.status == DownloadStatus.Queued || it.status == DownloadStatus.Failed
                if (it.id == id && idle) it.copy(doneBytes = done, spans = spans, partId = partId) else it
            },
    )

/** The file is complete, also when a pause came a moment too late to stop it. The total becomes the real size. */
fun finished(
    s: QueueState,
    id: String,
    done: Long,
    limit: Int,
    /** The name the file really got, when the engine had to choose another one at the end; null keeps the row's. */
    name: String? = null,
): QueueState {
    val row = s.list.firstOrNull { it.id == id } ?: return s
    if (row.status == DownloadStatus.Finished) return s
    val next =
        s.update(id, null) {
            it.copy(
                name = name ?: it.name,
                status = DownloadStatus.Finished,
                doneBytes = done,
                totalBytes = done,
                bytesPerSec = 0,
                error = null,
                spans = null,
                partId = null,
                liveConnections = 0,
            )
        }
    return settle(next.copy(queue = next.queue.filter { it != id }), limit)
}

/** A failure frees the slot. One that arrives after the row was paused or removed is dropped. */
fun failed(
    s: QueueState,
    id: String,
    reason: String,
    done: Long,
    limit: Int,
    /** The ranges at the failure, with the identity of their file; null keeps the row's own. */
    spans: List<Span>? = null,
    partId: String? = null,
    /** The engine deleted the `.part` file and left none: the row keeps no ranges and shows nothing done. */
    cleared: Boolean = false,
): QueueState =
    settle(
        s.update(id, DownloadStatus.Downloading) {
            it.copy(
                status = DownloadStatus.Failed,
                error = reason,
                doneBytes = if (cleared) 0 else done,
                bytesPerSec = 0,
                spans = if (cleared) null else spans ?: it.spans,
                partId =
                    if (cleared) {
                        null
                    } else if (spans != null) {
                        partId
                    } else {
                        it.partId
                    },
                liveConnections = 0,
            )
        },
        limit,
    )

/** Starts the queued targets now, even above the limit. */
fun startNow(
    s: QueueState,
    ids: Set<String>,
): QueueState = s.setStatus(ids, setOf(DownloadStatus.Queued), DownloadStatus.Downloading).copy(queue = s.queue.filter { it !in ids })

fun moveToTop(
    s: QueueState,
    ids: Set<String>,
): QueueState = s.copy(queue = s.queue.filter { it in ids } + s.queue.filter { it !in ids })

/** Each target swaps with the nearest non-target above it. */
fun moveUp(
    s: QueueState,
    ids: Set<String>,
): QueueState {
    val q = s.queue.toMutableList()
    for (i in 1 until q.size) {
        if (q[i] in ids && q[i - 1] !in ids) q[i - 1] = q[i].also { q[i] = q[i - 1] }
    }
    return s.copy(queue = q)
}

fun moveDown(
    s: QueueState,
    ids: Set<String>,
): QueueState {
    val q = s.queue.toMutableList()
    for (i in q.size - 2 downTo 0) {
        if (q[i] in ids && q[i + 1] !in ids) q[i + 1] = q[i].also { q[i] = q[i + 1] }
    }
    return s.copy(queue = q)
}

/** Raising the limit starts rows; lowering it never stops running ones. */
fun changeLimit(
    s: QueueState,
    limit: Int,
): QueueState = settle(s, limit)

fun queuePosition(
    s: QueueState,
    id: String,
): Int? =
    s.queue
        .indexOf(id)
        .takeIf { it >= 0 }
        ?.plus(1)

/** How many downloads must go before a new one starts, or null when a slot is free. */
fun startsAfter(
    s: QueueState,
    limit: Int,
): Int? = running(s).let { if (it < limit) null else it - limit + 1 + s.queue.size }

fun startsAfterText(n: Int): String = if (n == 1) "Starts after 1 download" else "Starts after $n downloads"

fun invariantsHold(
    s: QueueState,
    limit: Int,
): Boolean {
    val queued = s.list.filter { it.status == DownloadStatus.Queued }.map { it.id }
    return s.queue.size == s.queue.toSet().size &&
        s.queue.toSet() == queued.toSet() &&
        queued.size == s.queue.size &&
        (running(s) >= limit || s.queue.isEmpty())
}
