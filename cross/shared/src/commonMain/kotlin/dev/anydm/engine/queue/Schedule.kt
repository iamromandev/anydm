package dev.anydm.engine.queue

import dev.anydm.engine.core.Download
import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadStatus

/** A job that holds a slot; [stopping] means it was cancelled and the slot frees once it has stopped. */
internal data class SlotView(
    val download: Download,
    val stopping: Boolean,
)

/** What the queue does next: start [start] in this order, or stop [preempt] and send them back to wait. Never both. */
internal data class Decision(
    val start: List<DownloadId>,
    val preempt: List<DownloadId>,
)

/** The order jobs get a slot in (FND-04): highest priority, then longest waiting (unknown first), then first added. */
internal val waitingOrder: Comparator<Download> =
    compareByDescending<Download> { it.priority }
        .thenBy(nullsFirst()) { it.queuedAt }
        .thenBy { it.id.value }

// The first to give a slot back when the limit drops: the reverse of waitingOrder.
private val preemptOrder: Comparator<Download> = waitingOrder.reversed()

/**
 * Decides what to start or stop so that at most [limit] jobs hold a slot. A stopping slot still counts against the
 * limit, so nothing starts until it is gone; it is already leaving, so it is not counted when choosing what to
 * preempt. A MUXING job is never preempted: its transfer is done and only a short post-process is left. Reordering
 * never preempts: only a lower [limit] does.
 */
internal fun schedule(
    waiting: List<Download>,
    running: List<SlotView>,
    limit: Int,
): Decision {
    val staying = running.filterNot { it.stopping }
    val excess = staying.size - limit
    if (excess > 0) {
        val preempt =
            staying
                .map { it.download }
                .filter { it.status != DownloadStatus.MUXING }
                .sortedWith(preemptOrder)
                .take(excess)
                .map { it.id }
        return Decision(start = emptyList(), preempt = preempt)
    }
    val free = (limit - running.size).coerceAtLeast(0)
    return Decision(start = waiting.sortedWith(waitingOrder).take(free).map { it.id }, preempt = emptyList())
}
