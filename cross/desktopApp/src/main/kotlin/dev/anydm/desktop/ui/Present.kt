package dev.anydm.desktop.ui

import dev.anydm.api.ApiException
import dev.anydm.api.Unauthorized
import dev.anydm.desktop.files.isMedia
import dev.anydm.model.EntryCounts
import dev.anydm.model.Task
import dev.anydm.model.TaskKind
import dev.anydm.model.TaskStatus
import dev.anydm.store.Connection
import dev.anydm.store.RemovePrompt
import dev.anydm.store.RetryTone
import dev.anydm.store.RetryView
import dev.anydm.store.count
import dev.anydm.store.retryLabel
import java.util.Locale

/** "12.4 GB": powers of 1024, one decimal above bytes (web: `formatBytes`). */
fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var size = bytes.toDouble()
    var unit = 0
    while (size >= 1024 && unit < units.lastIndex) {
        size /= 1024
        unit += 1
    }
    return if (unit == 0) "$bytes B" else "${String.format(Locale.ROOT, "%.1f", size)} ${units[unit]}"
}

fun formatSpeed(bps: Long): String = "${formatBytes(bps)}/s"

/** "1h 2m", "5m 3s", "42s" (web: the card's `formatTime`). */
fun formatEta(seconds: Int): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m ${secs}s"
        else -> "${secs}s"
    }
}

private val SITE_NAMES =
    mapOf("Youtube" to "YouTube", "YoutubeTab" to "YouTube", "Soundcloud" to "SoundCloud", "Twitter" to "X", "TwitchVod" to "Twitch")

/** yt-dlp's name for a site, as the site writes its own. */
fun siteName(extractor: String?): String = extractor?.let { SITE_NAMES[it] ?: it } ?: ""

fun statusLabel(status: TaskStatus): String =
    when (status) {
        TaskStatus.PENDING, TaskStatus.QUEUED -> "Queued"
        TaskStatus.DOWNLOADING -> "Downloading"
        TaskStatus.MUXING -> "Processing"
        TaskStatus.PAUSED -> "Paused"
        TaskStatus.SEEDING -> "Seeding"
        TaskStatus.COMPLETED -> "Completed"
        TaskStatus.FAILED -> "Failed"
        TaskStatus.CANCELLED -> "Cancelled"
        TaskStatus.UNKNOWN -> "Unknown"
    }

fun connectionLabel(connection: Connection): String =
    when (connection) {
        Connection.Connecting -> "Connecting…"
        Connection.Live -> "Live"
        is Connection.Degraded -> "Reconnecting…"
        Connection.Offline -> "Offline"
    }

/** The API's `sort` values and the web's names for them. */
val SORT_OPTIONS =
    listOf(
        "-created_at" to "Newest first",
        "created_at" to "Oldest first",
        "title" to "Name",
        "-total_size" to "Largest first",
        "-progress" to "Most complete",
        "-speed_bps" to "Fastest first",
    )

/** The API's presets and the web's names for them. */
val PRESET_OPTIONS =
    listOf("best" to "Best", "2160" to "4K", "1440" to "1440p", "1080" to "1080p", "720" to "720p", "480" to "480p", "mp3" to "Audio")

private fun presetLabel(preset: String?) = PRESET_OPTIONS.firstOrNull { it.first == preset }?.second ?: ""

enum class CardAction { PAUSE, RESUME, RETRY, STOP_SEEDING, PLAY, SAVE, COPY_LINK, REMOVE }

/** Everything one card draws, decided here so it's tested without a window. */
data class CardView(
    val title: String,
    val meta: String,
    val status: String,
    val progress: Float,
    val detail: String,
    val retry: RetryView?,
    val actions: List<CardAction>,
)

private fun countsLine(counts: EntryCounts): String =
    listOf(
        counts.downloading to "downloading",
        maxOf(0, counts.active - counts.downloading) to "queued",
        counts.paused to "paused",
        counts.failed to "failed",
    ).filter { it.first > 0 }.joinToString(" · ") { "${count(it.first)} ${it.second}" }

private fun actionsOf(task: Task): List<CardAction> =
    buildList {
        val finished = task.status == TaskStatus.COMPLETED || task.status == TaskStatus.SEEDING
        if (task.status in setOf(TaskStatus.PENDING, TaskStatus.DOWNLOADING, TaskStatus.SEEDING)) add(CardAction.PAUSE)
        if (task.status == TaskStatus.PAUSED) add(CardAction.RESUME)
        if (task.status == TaskStatus.FAILED) add(CardAction.RETRY)
        if (task.status == TaskStatus.SEEDING) add(CardAction.STOP_SEEDING)
        if (finished && task.kind != TaskKind.PLAYLIST) {
            val playable =
                task.kind == TaskKind.VIDEO || task.kind == TaskKind.AUDIO ||
                    isMedia(task.filename ?: "") ||
                    task.files.orEmpty().any { it.selected && isMedia(it.path) }
            if (playable) add(CardAction.PLAY)
            add(CardAction.SAVE)
        }
        add(CardAction.REMOVE)
    }

/** A task's card: the web's `TorrentCard`, or `GroupCard` for a playlist. */
fun cardView(
    task: Task,
    nowMillis: Long,
): CardView {
    val counts = task.entryCounts
    if (task.kind == TaskKind.PLAYLIST && counts != null) {
        val total = counts.total
        val meta =
            listOf(
                "Playlist",
                siteName(task.extractor),
                "${count(total)} ${if (total == 1) "video" else "videos"}",
                presetLabel(task.preset),
            ).filter { it.isNotEmpty() }
                .joinToString(" · ")
        val bytes = if (task.downloadedBytes > 0) " · ${formatBytes(task.downloadedBytes)}" else ""
        val line = countsLine(counts)
        return CardView(
            title = task.title,
            meta = meta,
            status = statusLabel(task.status),
            progress = if (total > 0) counts.complete.toFloat() / total else 0f,
            detail = "${count(counts.complete)} of ${count(total)}$bytes${if (line.isNotEmpty()) " · $line" else ""}",
            retry = null,
            actions = actionsOf(task),
        )
    }
    val active = task.status in setOf(TaskStatus.PENDING, TaskStatus.QUEUED, TaskStatus.DOWNLOADING, TaskStatus.MUXING, TaskStatus.PAUSED)
    val size = if (active && task.totalBytes > 0) "${formatBytes(task.downloadedBytes)} / ${formatBytes(task.totalBytes)}" else ""
    val retry = retryLabel(task, nowMillis)
    val detail =
        when (task.status) {
            TaskStatus.DOWNLOADING -> {
                listOfNotNull(
                    "${task.progress}%",
                    task.downloadSpeed.takeIf { it > 0 }?.let(::formatSpeed),
                    task.etaSeconds?.takeIf { it > 0 }?.let(::formatEta),
                ).joinToString(" · ")
            }

            TaskStatus.PENDING, TaskStatus.QUEUED -> {
                if (retry == null) "Queued" else ""
            }

            TaskStatus.MUXING -> {
                "Processing…"
            }

            else -> {
                "${task.progress}%"
            }
        }
    return CardView(
        title = task.title,
        meta = listOf(siteName(task.extractor), size).filter { it.isNotEmpty() }.joinToString(" · "),
        status = statusLabel(task.status),
        progress = task.progress / 100f,
        detail = detail,
        retry = retry,
        actions = actionsOf(task),
    )
}

enum class IconKind { SITE, TORRENT, GROUP, FILE }

enum class Glyph { DOWN, PAUSED, DONE, RETRY, SEEDING }

enum class DetailTone { NORMAL, WARN, ERROR }

/** Everything one native row draws (spec: "Rows"), decided here so it's tested without a window. */
data class RowView(
    val title: String,
    val detail: String,
    val tone: DetailTone,
    val icon: IconKind,
    val glyph: Glyph,
    val progress: Float?,
    val hover: List<CardAction>,
    val menu: List<CardAction>,
    val expandable: Boolean,
)

private val ACTION_LABELS =
    mapOf(
        CardAction.PAUSE to "Pause",
        CardAction.RESUME to "Resume",
        CardAction.RETRY to "Retry",
        CardAction.STOP_SEEDING to "Stop seeding",
        CardAction.PLAY to "Open file",
        CardAction.SAVE to "Save to Downloads",
        CardAction.COPY_LINK to "Copy link",
        CardAction.REMOVE to "Remove…",
    )

fun actionLabel(action: CardAction): String = ACTION_LABELS.getValue(action)

private fun sizeOf(task: Task): String =
    if (task.totalBytes > 0) "${formatBytes(task.downloadedBytes)} of ${formatBytes(task.totalBytes)}" else "${task.progress}%"

fun rowView(
    task: Task,
    nowMillis: Long,
): RowView {
    val actions = actionsOf(task)
    val menu = if (task.url.isNotBlank()) actions.dropLast(1) + CardAction.COPY_LINK + CardAction.REMOVE else actions
    val primary = actions.firstOrNull { it != CardAction.REMOVE && it != CardAction.STOP_SEEDING }
    val hover = listOfNotNull(primary, CardAction.REMOVE)
    val retry = retryLabel(task, nowMillis)
    val glyph =
        when {
            retry != null || task.status == TaskStatus.FAILED -> Glyph.RETRY
            task.status == TaskStatus.PAUSED -> Glyph.PAUSED
            task.status == TaskStatus.SEEDING -> Glyph.SEEDING
            task.status == TaskStatus.COMPLETED -> Glyph.DONE
            else -> Glyph.DOWN
        }
    val counts = task.entryCounts
    if (task.kind == TaskKind.PLAYLIST) {
        val total = counts?.total ?: 0
        return RowView(
            title = task.title,
            detail = cardView(task, nowMillis).detail,
            tone = if ((counts?.failed ?: 0) > 0) DetailTone.WARN else DetailTone.NORMAL,
            icon = IconKind.GROUP,
            glyph = glyph,
            progress = if (total > 0 && task.status != TaskStatus.COMPLETED) (counts?.complete ?: 0).toFloat() / total else null,
            hover = hover,
            menu = menu,
            expandable = true,
        )
    }
    val site = siteName(task.extractor)
    val body =
        when {
            retry != null -> {
                retry.headline
            }

            task.status == TaskStatus.DOWNLOADING -> {
                listOfNotNull(
                    sizeOf(task),
                    task.downloadSpeed.takeIf { it > 0 }?.let(::formatSpeed),
                    task.etaSeconds?.takeIf { it > 0 }?.let { "${formatEta(it)} left" },
                ).joinToString(" · ")
            }

            task.status == TaskStatus.PENDING || task.status == TaskStatus.QUEUED -> {
                "Queued"
            }

            task.status == TaskStatus.MUXING -> {
                "Processing…"
            }

            task.status == TaskStatus.PAUSED -> {
                "Paused · ${sizeOf(task)}"
            }

            task.status == TaskStatus.SEEDING -> {
                "Seeding · ↑ ${formatSpeed(task.uploadSpeed)}"
            }

            task.status == TaskStatus.COMPLETED -> {
                if (task.totalBytes > 0) "${formatBytes(task.totalBytes)} · Finished" else "Finished"
            }

            task.status == TaskStatus.FAILED -> {
                listOfNotNull("Failed", task.error).joinToString(" · ")
            }

            else -> {
                statusLabel(task.status)
            }
        }
    val tone =
        when {
            retry?.tone == RetryTone.ERROR || task.status == TaskStatus.FAILED -> DetailTone.ERROR
            retry != null -> DetailTone.WARN
            else -> DetailTone.NORMAL
        }
    val icon =
        when {
            task.kind == TaskKind.TORRENT -> IconKind.TORRENT
            site.isNotEmpty() || task.kind == TaskKind.VIDEO || task.kind == TaskKind.AUDIO -> IconKind.SITE
            else -> IconKind.FILE
        }
    val inFlight = task.status in setOf(TaskStatus.PENDING, TaskStatus.QUEUED, TaskStatus.DOWNLOADING, TaskStatus.MUXING, TaskStatus.PAUSED)
    return RowView(
        title = task.title,
        detail = if (site.isNotEmpty() && retry == null) "$site · $body" else body,
        tone = tone,
        icon = icon,
        glyph = glyph,
        progress = if (inFlight && retry == null) task.progress / 100f else null,
        hover = hover,
        menu = menu,
        expandable = false,
    )
}

/** One action for many rows, and the rows it applies to. */
data class Intent(
    val action: CardAction,
    val targets: List<Task>,
)

private val RUNNING = setOf(TaskStatus.PENDING, TaskStatus.DOWNLOADING, TaskStatus.MUXING, TaskStatus.SEEDING)

/** Space on a selection: pause what runs; if nothing does, resume what's paused or failed. */
fun bulkIntent(tasks: List<Task>): Intent? {
    val running = tasks.filter { it.status in RUNNING }
    if (running.isNotEmpty()) return Intent(CardAction.PAUSE, running)
    val stopped = tasks.filter { it.status == TaskStatus.PAUSED || it.status == TaskStatus.FAILED }
    return if (stopped.isNotEmpty()) Intent(CardAction.RESUME, stopped) else null
}

/** One question for several removals; keeping files is offered only when every one allows it. */
fun removeManyPrompt(prompts: List<RemovePrompt>): RemovePrompt {
    if (prompts.size == 1) return prompts.single()
    val keep = prompts.all { it.canKeepFiles }
    return RemovePrompt(
        "Remove ${count(prompts.size)} downloads?",
        if (keep) {
            "What finished stays on disk unless you ask for it to go too."
        } else {
            "Anything still downloading stops, and what it downloaded is discarded."
        },
        keep,
        "Remove",
    )
}

fun removeTitle(titles: List<String>): String {
    val named = titles.take(3).joinToString(", ")
    return if (titles.size > 3) "$named and ${titles.size - 3} more" else named
}

/** What Connect says when a server won't do: the key, the API's own words, or no answer at all. */
fun connectError(
    error: Throwable,
    url: String,
): String =
    when (error) {
        is Unauthorized -> "The server refused the key. Check it matches API_KEY in api/.env."
        is ApiException -> error.message ?: "Couldn't reach $url"
        else -> "Couldn't reach $url"
    }
