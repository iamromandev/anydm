package dev.anydm.store

import dev.anydm.api.ServerEvent
import dev.anydm.api.TaskApi
import dev.anydm.api.Unauthorized
import dev.anydm.model.Task
import dev.anydm.model.toTask
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.max

/**
 * The download list, kept live: pages from the API, frames from its event stream, a
 * reconnect with backoff when the stream drops, and a fallback poll while it's down.
 *
 * Runs on [scope], which must be single-threaded (the desktop's `Dispatchers.Main`):
 * `seq` and `touched` are plain fields. Actions are `suspend`, never throw, and return
 * whether they worked; a failure becomes a [StoreEvent.Said] with the API's own message.
 */
class TaskStore(
    private val api: TaskApi,
    private val scope: CoroutineScope,
    private val now: () -> Long,
) {
    private val mutableState = MutableStateFlow(ListState())
    val state: StateFlow<ListState> = mutableState.asStateFlow()

    private val mutableEvents = MutableSharedFlow<StoreEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<StoreEvent> = mutableEvents.asSharedFlow()

    // A count of stream writes, and the count at each row's latest one: what lets a page
    // fetch tell which of its rows went stale while it was out (web: `streamTouched`).
    private var seq = 0L
    private val touched = mutableMapOf<String, Long>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var warned = false
    private var lastPoll = 0L
    private var running: Job? = null

    fun start() {
        if (running != null) return
        lastPoll = now()
        running =
            scope.launch {
                launch { refresh() }
                launch { follow() }
                launch { watch() }
            }
    }

    fun stop() {
        running?.cancel()
        running = null
    }

    /** Reconnect now rather than at the end of the current backoff. */
    fun retryNow() {
        wake.trySend(Unit)
    }

    fun setFilter(filter: ListFilter) {
        mutableState.update { it.copy(filter = filter, page = 1, totalPages = 1) }
        scope.launch { loadPage(1) }
    }

    fun setSort(sort: String) {
        mutableState.update { it.copy(sort = sort, page = 1, totalPages = 1) }
        scope.launch { loadPage(1) }
    }

    /** Open a group: fetch its videos; frames keep them current until [collapse]. */
    fun expand(id: String) {
        scope.launch {
            val rows = read { api.entries(id) } ?: return@launch
            mutableState.update {
                it.copy(
                    entries =
                        it.entries + (id to rows.map { dto -> dto.toTask() }.sortedBy { t -> t.position ?: Int.MAX_VALUE }),
                )
            }
        }
    }

    fun collapse(id: String) {
        mutableState.update { it.copy(entries = it.entries - id) }
    }

    internal suspend fun onFrame(event: ServerEvent) = handle(event)

    /** A video's frame, kept when its group is open; the top list never shows videos. */
    private fun mergeEntries(rows: List<Task>) {
        mutableState.update { state ->
            var entries = state.entries
            rows.filter { it.parentId != null && it.parentId in entries }.forEach { row ->
                val list = entries.getValue(row.parentId!!)
                val next = if (list.any { it.id == row.id }) list.map { if (it.id == row.id) row else it } else list + row
                entries = entries + (row.parentId to next.sortedBy { it.position ?: Int.MAX_VALUE })
            }
            state.copy(entries = entries)
        }
    }

    fun loadMore() {
        val current = state.value
        if (current.loadingMore || current.page >= current.totalPages) return
        mutableState.update { it.copy(loadingMore = true) }
        scope.launch {
            try {
                loadPage(current.page + 1)
            } finally {
                mutableState.update { it.copy(loadingMore = false) }
            }
        }
    }

    suspend fun add(
        link: String,
        preferredPreset: String,
    ): Boolean = act { merge(listOf(api.addLink(link, preferredPreset).toTask())) }.also { if (it) loadSummary() }

    suspend fun addTorrent(torrent: String): Boolean =
        act { merge(listOf(api.addTorrent(torrent).toTask())) }.also { if (it) loadSummary() }

    suspend fun pause(id: String): Boolean = act { merge(listOf(api.pause(id).toTask())) }

    suspend fun resume(id: String): Boolean = act { merge(listOf(api.resume(id).toTask())) }

    /** The row goes whatever the answer: a failure here is nearly always a row the API has already forgotten. */
    suspend fun remove(
        id: String,
        deleteFiles: Boolean,
    ): Boolean {
        val removed = act { api.remove(id, deleteFiles) }
        mutableState.update { it.copy(tasks = it.tasks.filterNot { row -> row.id == id }) }
        loadSummary()
        return removed
    }

    suspend fun stopSeeding(id: String): Boolean = act { api.stopSeeding(id) }.also { if (it) refresh() }

    suspend fun bulk(action: BulkAction): Boolean =
        act {
            val affected = api.bulk(action.wire)
            val message = if (affected == 0) "Nothing to do" else "${action.verb} $affected download${if (affected == 1) "" else "s"}"
            say(Notice(Tone.INFO, message))
        }.also { refresh() }

    private suspend fun act(block: suspend () -> Unit): Boolean =
        try {
            block()
            true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Unauthorized) {
            signOut(error)
            false
        } catch (error: Exception) {
            say(Notice(Tone.ERROR, error.message ?: "Something went wrong"))
            false
        }

    private suspend fun refresh() {
        loadPage(1)
        loadSummary()
    }

    private suspend fun loadSummary() {
        read { api.summary() }?.let { summary -> mutableState.update { it.copy(summary = summary) } }
    }

    /**
     * One page of the current view. Page 1 replaces the list; later pages add to it. Rows
     * the stream wrote while this was out keep the stream's copy (web: `loadPage`).
     */
    private suspend fun loadPage(page: Int) {
        val since = seq
        val current = state.value
        val result = read { api.listTasks(page, PAGE_SIZE, current.filter.wire, current.sort) } ?: return
        val fresh = result.items.map { it.toTask() }
        val stale = touched.filterValues { it > since }.keys
        // Read and written with no suspension between: the store runs on one thread, so no
        // frame can land in the middle (web: the same rule, for the same reason).
        val before = state.value.tasks
        val rows = keepHeld(settlePage(fresh, before, stale), before)
        mutableState.update {
            it.copy(
                tasks = if (page == 1) rows else appendPage(before, rows),
                page = result.meta.page,
                totalPages = result.meta.totalPages,
            )
        }
        // The settled rows, not the page's: a stale copy must never announce a transition.
        announce(rows, before)
    }

    /** Rows from a frame or an action's answer, placed as the web places them. */
    private suspend fun merge(rows: List<Task>) {
        val top = rows.filter { it.parentId == null }
        if (top.isEmpty()) return
        seq += 1
        top.forEach { touched[it.id] = seq }
        val before = state.value.tasks
        mutableState.update { it.copy(tasks = placeRows(it.tasks, keepHeld(top, it.tasks))) }
        announce(top, before)
    }

    /** A notice for each row whose status moved; the counts are fetched again when any did. */
    private suspend fun announce(
        rows: List<Task>,
        before: List<Task>,
    ) {
        val previous = before.associate { it.id to it.status }
        var moved = false
        rows.forEach { row ->
            transition(previous[row.id], row)?.let {
                moved = true
                say(it)
            }
        }
        if (moved) scope.launch { loadSummary() }
    }

    private suspend fun handle(event: ServerEvent) {
        when (event) {
            is ServerEvent.Snapshot -> {
                mergeEntries(event.tasks)
                merge(event.tasks)
            }

            is ServerEvent.TaskChanged -> {
                mergeEntries(listOf(event.task))
                merge(listOf(event.task))
            }

            is ServerEvent.Progress -> {
                val parent = event.progress.parentId
                if (parent == null) {
                    mutableState.update { it.copy(tasks = applyProgress(it.tasks, event.progress)) }
                } else if (parent in state.value.entries) {
                    mutableState.update {
                        it.copy(
                            entries =
                                it.entries + (parent to applyProgress(it.entries.getValue(parent), event.progress)),
                        )
                    }
                }
            }

            is ServerEvent.Disk -> {
                mutableState.update { it.copy(disk = event.disk) }
            }
        }
    }

    /** Follow the stream; when it drops, back off and open it again. A refused key ends this. */
    private suspend fun follow() {
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            try {
                api.events().collect { event ->
                    if (state.value.connection != Connection.Live) goLive()
                    attempt = 0
                    handle(event)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Unauthorized) {
                signOut(error)
                return
            } catch (error: Exception) {
                // Dropped, or never opened: the backoff below retries it.
            }
            goDegraded()
            withTimeoutOrNull(RECONNECT_BACKOFF_MS[minOf(attempt, RECONNECT_BACKOFF_MS.lastIndex)]) { wake.receive() }
            attempt += 1
        }
    }

    /** While degraded: fetch the list every [FALLBACK_POLL_MS], and say so once after [WARN_AFTER_MS]. */
    private suspend fun watch() {
        while (currentCoroutineContext().isActive) {
            delay(1_000)
            val connection = state.value.connection as? Connection.Degraded ?: continue
            val at = now()
            if (at - max(lastPoll, connection.since) >= FALLBACK_POLL_MS) {
                lastPoll = at
                refresh()
            }
            if (!warned && at - connection.since >= WARN_AFTER_MS) {
                warned = true
                say(Notice(Tone.ERROR, LOST_CONTACT))
            }
        }
    }

    private suspend fun goLive() {
        mutableState.update { it.copy(connection = Connection.Live) }
        if (warned) {
            warned = false
            say(Notice(Tone.INFO, BACK_IN_CONTACT))
        }
    }

    private fun goDegraded() {
        mutableState.update { if (it.connection is Connection.Degraded) it else it.copy(connection = Connection.Degraded(now())) }
    }

    private suspend fun signOut(error: Unauthorized) {
        mutableState.update { it.copy(connection = Connection.Offline) }
        mutableEvents.emit(StoreEvent.SignedOut(error.message ?: "The API refused the key"))
    }

    private suspend fun say(notice: Notice) {
        mutableEvents.emit(StoreEvent.Said(notice))
    }

    /** An API read, or `null` if it failed; a refused key signs out. */
    private suspend fun <T> read(block: suspend () -> T): T? =
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Unauthorized) {
            signOut(error)
            null
        } catch (error: Exception) {
            null
        }
}
