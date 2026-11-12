package dev.anydm.desktop

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
): QueueState = copy(list = list.map { if (it.id in ids && it.status in from) it.copy(status = to) else it })

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
        if (it.id !in ids || it.status != DownloadStatus.Paused) continue
        out =
            if (running(out) < limit) {
                out.setStatus(setOf(it.id), setOf(DownloadStatus.Paused), DownloadStatus.Downloading)
            } else {
                out.setStatus(setOf(it.id), setOf(DownloadStatus.Paused), DownloadStatus.Queued).copy(queue = out.queue + it.id)
            }
    }
    return settle(out, limit)
}

fun removeAll(
    s: QueueState,
    ids: Set<String>,
    limit: Int,
): QueueState = settle(QueueState(s.list.filter { it.id !in ids }, s.queue.filter { it !in ids }), limit)

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
