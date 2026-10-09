package dev.anydm.store

import dev.anydm.api.Duplicate
import dev.anydm.model.DiskDto
import dev.anydm.model.SummaryDto
import dev.anydm.model.Task
import dev.anydm.model.TaskStatus

/** Rows per page. The API caps a page at 100. */
const val PAGE_SIZE = 25

/** Waits before each reconnect: 1 s, 2 s, 5 s, then every 10 s. */
val RECONNECT_BACKOFF_MS = listOf(1_000L, 2_000L, 5_000L, 10_000L)

/** How often the list is fetched while the stream is down. */
const val FALLBACK_POLL_MS = 10_000L

/** How long a gap lasts before it's worth saying so. */
const val WARN_AFTER_MS = 20_000L

const val LOST_CONTACT = "Lost contact with the API. Still trying, and the list may be out of date."
const val BACK_IN_CONTACT = "Back in contact"

/** The sidebar's four views, as the API names them (`group=`). */
enum class ListFilter(
    val wire: String,
) {
    ALL("all"),
    ACTIVE("downloading"),
    SEEDING("seeding"),
    COMPLETED("completed"),
}

/** Whether a row belongs in this view: frames arrive for every row, whatever is shown. */
fun ListFilter.matches(task: Task): Boolean =
    when (this) {
        ListFilter.ALL -> true
        ListFilter.ACTIVE -> task.status in setOf(TaskStatus.PENDING, TaskStatus.QUEUED, TaskStatus.DOWNLOADING, TaskStatus.MUXING)
        ListFilter.SEEDING -> task.status == TaskStatus.SEEDING
        ListFilter.COMPLETED -> task.status == TaskStatus.COMPLETED
    }

/** Which of the sidebar's counts a status falls in besides All, as the API groups them. */
private fun countedAs(status: TaskStatus): String =
    when (status) {
        TaskStatus.PENDING, TaskStatus.QUEUED, TaskStatus.DOWNLOADING, TaskStatus.MUXING -> "downloading"
        TaskStatus.SEEDING -> "seeding"
        TaskStatus.COMPLETED -> "completed"
        TaskStatus.CANCELLED -> "gone"
        else -> "all"
    }

/**
 * Whether a row's new status moves the sidebar's counts (web: `countsMoved`). A row new
 * to the list says nothing, and neither does a frame that changes nothing.
 */
fun countsMoved(
    previous: TaskStatus?,
    next: TaskStatus,
): Boolean = previous != null && countedAs(previous) != countedAs(next)

/** Whether live updates are arriving. `Offline` is a refused key: nothing retries until it's changed. */
sealed interface Connection {
    data object Connecting : Connection

    data object Live : Connection

    data class Degraded(
        val since: Long,
    ) : Connection

    data object Offline : Connection
}

/** Everything the list draws. */
data class ListState(
    val tasks: List<Task> = emptyList(),
    val page: Int = 1,
    val totalPages: Int = 1,
    val filter: ListFilter = ListFilter.ALL,
    val sort: String = "-created_at",
    val summary: SummaryDto? = null,
    val disk: DiskDto? = null,
    val connection: Connection = Connection.Connecting,
    val loadingMore: Boolean = false,
    /** The videos of each group that's open, by group id, in playlist order. */
    val entries: Map<String, List<Task>> = emptyMap(),
)

/** One-off things the UI shows once: a notice, a refused duplicate, or being signed out by a 401. */
sealed interface StoreEvent {
    data class Said(
        val notice: Notice,
    ) : StoreEvent

    /**
     * An add the list already holds. [link] is what was added when a second copy is possible, so
     * Add anyway can send it again; it is null for a torrent, which the API holds only once.
     */
    data class Duplicated(
        val held: Duplicate,
        val link: String?,
    ) : StoreEvent

    data class SignedOut(
        val message: String,
    ) : StoreEvent
}

/** The sidebar's sweeps, and what the notice after one says it did. */
enum class BulkAction(
    val wire: String,
    val verb: String,
) {
    PAUSE_ALL("pause_all", "Paused"),
    RESUME_ALL("resume_all", "Resumed"),
    CLEAR_FINISHED("clear_finished", "Cleared"),
}
