package dev.anydm.store

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
        TaskStatus.COMPLETE -> Notice(Tone.SUCCESS, "Finished: ${task.title}")
        TaskStatus.SEEDING -> Notice(Tone.INFO, "Seeding: ${task.title}")
        TaskStatus.FAILED -> Notice(Tone.ERROR, task.error?.let { "Failed: ${task.title} — $it" } ?: "Failed: ${task.title}")
        else -> null
    }
}

/** A group raises one notice, when it ends: "Finished: 29C3, 95 of 96, 1 failed". */
private fun groupEnded(task: Task): Notice? {
    if (task.status != TaskStatus.COMPLETE && task.status != TaskStatus.FAILED) return null
    val counts =
        task.entryCounts
            ?: return Notice(if (task.status == TaskStatus.COMPLETE) Tone.SUCCESS else Tone.ERROR, "Finished: ${task.title}")
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

        status == TaskStatus.COMPLETE -> {
            RemovePrompt("Remove from the list?", "The download stays on disk unless you ask for it to go too.", true, "Remove")
        }

        else -> {
            RemovePrompt("Stop and remove?", "Anything downloaded so far is discarded.", false, "Stop and remove")
        }
    }
