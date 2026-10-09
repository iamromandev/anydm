package dev.anydm.store

import dev.anydm.model.BatchItem
import dev.anydm.model.BatchOutcome
import dev.anydm.model.Task
import dev.anydm.model.TaskKind
import dev.anydm.model.TaskStatus
import kotlin.math.ceil
import kotlin.time.ExperimentalTime

enum class Tone { INFO, SUCCESS, ERROR }

/** A line worth interrupting someone for. */
data class Notice(
    val tone: Tone,
    val message: String,
)

/** "1,234", as the web's `toLocaleString("en-US")`. */
fun count(n: Int): String =
    n
        .toString()
        .reversed()
        .chunked(3)
        .joinToString(",")
        .reversed()

/**
 * What to say about a row whose status moved from [previous], or `null` for silence
 * (web: `transitionToast`, and `groupToast` for a group). First sight says nothing, so a
 * reconnect's snapshot doesn't replay history.
 */
fun transition(
    previous: TaskStatus?,
    task: Task,
): Notice? {
    if (previous == null || previous == task.status) return null
    if (task.kind == TaskKind.PLAYLIST) return groupEnded(task)
    return when (task.status) {
        TaskStatus.COMPLETED -> Notice(Tone.SUCCESS, "Finished: ${task.title}")
        TaskStatus.SEEDING -> Notice(Tone.INFO, "Seeding: ${task.title}")
        TaskStatus.FAILED -> Notice(Tone.ERROR, task.error?.let { "Failed: ${task.title} — $it" } ?: "Failed: ${task.title}")
        else -> null
    }
}

/** A group raises one notice, when it ends: "Finished: 29C3, 95 of 96, 1 failed". */
private fun groupEnded(task: Task): Notice? {
    if (task.status != TaskStatus.COMPLETED && task.status != TaskStatus.FAILED) return null
    val counts =
        task.entryCounts
            ?: return Notice(if (task.status == TaskStatus.COMPLETED) Tone.SUCCESS else Tone.ERROR, "Finished: ${task.title}")
    val failed = if (counts.failed > 0) ", ${count(counts.failed)} failed" else ""
    return Notice(
        if (counts.failed > 0) Tone.ERROR else Tone.SUCCESS,
        "Finished: ${task.title}, ${count(counts.complete)} of ${count(counts.total)}$failed",
    )
}

enum class RetryTone { WARNING, ERROR }

/** What a card says about a task being retried, or one that has given up. */
data class RetryView(
    val tone: RetryTone,
    val headline: String,
    val detail: String?,
)

private const val DISK_WAIT_CODE = "insufficient_storage"

private fun attemptsPhrase(n: Int) = if (n == 1) "1 attempt" else "$n attempts"

/**
 * The retry line, or `null` when there's nothing to explain (web: `retryLabel`). A task
 * waiting to be retried is `pending` with a deadline; the deadline is what sets it apart
 * from one waiting for a free worker.
 */
@OptIn(ExperimentalTime::class)
fun retryLabel(
    task: Task,
    nowMillis: Long,
): RetryView? {
    if (task.status == TaskStatus.FAILED) {
        val code = task.errorCode?.let { " · $it" } ?: ""
        return RetryView(RetryTone.ERROR, "Failed after ${attemptsPhrase(task.attempts)}$code", task.error)
    }
    val next = task.nextAttemptAt
    if (task.status != TaskStatus.PENDING || next == null) return null
    val remaining = ceil((next.toEpochMilliseconds() - nowMillis) / 1000.0).toInt()
    if (task.errorCode == DISK_WAIT_CODE) {
        val check = if (remaining > 0) "checking again in ${remaining}s" else "checking…"
        return RetryView(RetryTone.WARNING, "Waiting for disk space · $check", task.error)
    }
    val budget = task.maxAttempts?.let { " of $it" } ?: ""
    val wait = if (remaining > 0) "Retrying in ${remaining}s" else "Retrying…"
    return RetryView(RetryTone.WARNING, "$wait · attempt ${task.attempts}$budget", task.error)
}

/** What the remove dialog says, and whether it offers to keep the files. */
data class RemovePrompt(
    val heading: String,
    val body: String,
    val canKeepFiles: Boolean,
    val confirmLabel: String,
)

/**
 * The web's `removePrompt`. The keep-files offer mirrors the API: `delete_files=false` is
 * refused for anything unfinished, except a group, which keeps whatever finished.
 */
fun removePrompt(
    status: TaskStatus,
    videos: Int? = null,
): RemovePrompt =
    when {
        videos != null -> {
            RemovePrompt(
                if (videos == 1) "Remove 1 video?" else "Remove ${count(videos)} videos?",
                "Any still downloading stop. What finished stays in its folder unless you ask for it to go too.",
                true,
                "Remove",
            )
        }

        status == TaskStatus.SEEDING -> {
            RemovePrompt("Remove this torrent?", "Sharing stops. The files stay on disk unless you ask for them to go too.", true, "Remove")
        }

        status == TaskStatus.COMPLETED -> {
            RemovePrompt("Remove from the list?", "The download stays on disk unless you ask for it to go too.", true, "Remove")
        }

        else -> {
            RemovePrompt("Stop and remove?", "Anything downloaded so far is discarded.", false, "Stop and remove")
        }
    }

/** "1 link", "120 links". */
fun linkCount(n: Int): String = if (n == 1) "1 link" else "${count(n)} links"

/** How many links a preview shows before "and N more". */
const val PREVIEW_SHOWN = 5

/** "…and 115 more" after the first [shown] links, or `null` when they are all shown. */
fun previewMore(
    total: Int,
    shown: Int = PREVIEW_SHOWN,
): String? = (total - shown).takeIf { it > 0 }?.let { "…and ${count(it)} more" }

/** "118 added · 2 already in your list · 1 failed": every part, a zero included. */
fun batchSummary(items: List<BatchItem>): String {
    fun of(outcome: BatchOutcome) = count(items.count { it.outcome == outcome })
    return "${of(BatchOutcome.ADDED)} added · " +
        "${of(BatchOutcome.DUPLICATE)} already in your list · " +
        "${of(BatchOutcome.ERROR)} failed"
}

/** Whether pasted text is several links: two or more lines with something on them. */
fun looksLikeMany(text: String): Boolean = text.lines().count { it.isNotBlank() } > 1
