package dev.anydm.desktop

import dev.anydm.engine.DownloadSnapshot
import dev.anydm.engine.EngineEvent
import dev.anydm.engine.EngineState
import dev.anydm.engine.TorrentDetailResult
import dev.anydm.engine.core.CategoryId
import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadStatus
import dev.anydm.engine.core.MirrorId
import dev.anydm.engine.core.PauseReason
import dev.anydm.engine.files.Category
import dev.anydm.engine.net.ProxyChoice
import dev.anydm.engine.net.ProxyEntry
import dev.anydm.engine.queue.CommandResult
import dev.anydm.engine.queue.RemoveFiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// What keeps quit after the queue waiting, as for the engine's own after-queue actions: a job that waits or runs, or is
// paused only until a network or schedule rule lets it go on. Collection rows are not jobs.
private val Busy = setOf(DownloadStatus.PENDING, DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING, DownloadStatus.MUXING)
private val SelfResuming = setOf(PauseReason.NETWORK, PauseReason.SCHEDULE)

private fun keepsQueueBusy(s: DownloadSnapshot): Boolean {
    val download = s.download
    if (download.isCollection) return false
    return download.status in Busy ||
        (download.status == DownloadStatus.PAUSED && download.pauseReasons.isNotEmpty() && SelfResuming.containsAll(download.pauseReasons))
}

/** The statuses the sidebar groups downloads under; a cancelled download is in none of them. */
enum class StatusGroup(
    val label: String,
    val statuses: Set<DownloadStatus>,
) {
    ACTIVE("Active", setOf(DownloadStatus.DOWNLOADING, DownloadStatus.MUXING)),
    QUEUED("Queued", setOf(DownloadStatus.PENDING, DownloadStatus.QUEUED)),
    PAUSED("Paused", setOf(DownloadStatus.PAUSED)),
    COMPLETED("Completed", setOf(DownloadStatus.COMPLETED, DownloadStatus.SEEDING)),
    FAILED("Failed", setOf(DownloadStatus.FAILED)),
}

sealed interface Filter {
    data object All : Filter

    data class Status(
        val group: StatusGroup,
    ) : Filter

    data class InCategory(
        val id: CategoryId,
    ) : Filter
}

sealed interface Route {
    data class Downloads(
        val filter: Filter = Filter.All,
    ) : Route

    data object Settings : Route
}

class Counts(
    val all: Int = 0,
    private val byGroup: Map<StatusGroup, Int> = emptyMap(),
    private val byCategory: Map<CategoryId, Int> = emptyMap(),
) {
    operator fun get(group: StatusGroup): Int = byGroup[group] ?: 0

    fun category(id: CategoryId): Int = byCategory[id] ?: 0

    fun of(filter: Filter): Int =
        when (filter) {
            Filter.All -> all
            is Filter.Status -> this[filter.group]
            is Filter.InCategory -> category(filter.id)
        }
}

sealed interface Banner {
    /** The engine failed or would not start; the app can't download until it is restarted. */
    data class EngineUnavailable(
        val reason: String,
    ) : Banner

    data object DiskFull : Banner

    data class Error(
        val message: String,
    ) : Banner
}

/** A question the list is waiting on. */
sealed interface Dialog {
    data class ConfirmRemove(
        val ids: List<DownloadId>,
    ) : Dialog

    /**
     * [error] is why the last link given was refused; the dialog stays until one is taken or it is closed. [busy] while a
     * link is being tried: no second one is sent.
     */
    data class Relink(
        val id: DownloadId,
        val mirrorId: MirrorId,
        val url: String,
        val error: String? = null,
        val busy: Boolean = false,
    ) : Dialog
}

data class UiState(
    val route: Route = Route.Downloads(),
    val engine: EngineState = EngineState.NotStarted,
    val counts: Counts = Counts(),
    val categories: List<Category> = emptyList(),
    val selection: Set<DownloadId> = emptySet(),
    /** Bytes per second, all downloads together. */
    val speed: Long = 0,
    val banners: List<Banner> = emptyList(),
    val showWelcome: Boolean = false,
    /** The add dialog while it is open. */
    val adding: AddModel? = null,
    val options: ListOptions = ListOptions(),
    /** What the list shows now, for the route's filter and [options]. */
    val rows: List<Row> = emptyList(),
    /** Where a Shift-click range starts, and the row the arrow keys move from. */
    val anchor: DownloadId? = null,
    val cursor: DownloadId? = null,
    val dialog: Dialog? = null,
    /** Pause all is on, as far as the jobs tell: some job is paused for it. */
    val globallyPaused: Boolean = false,
) {
    /** The one selected job, for the detail pane; null with none or several selected. */
    val detail: DownloadSnapshot? get() = selection.singleOrNull()?.let { id -> rows.firstOrNull { it.id == id }?.snapshot }

    /**
     * The selected jobs [command] applies to, in list order. An item whose collection takes the command too is left out:
     * the engine gives the collection's command to its items.
     */
    fun targets(command: JobCommand): List<DownloadSnapshot> {
        val applies = rows.filter { it.id in selection && command.appliesTo(it.snapshot) }.map { it.snapshot }
        val ids = applies.map { it.download.id }.toSet()
        return applies.filter { it.download.parentId !in ids }
    }

    fun enabled(command: JobCommand): Boolean = targets(command).let { if (command in SingleJob) it.size == 1 else it.isNotEmpty() }
}

/**
 * What every screen reads (the only copy of engine data in the app is the counts and the selection derived here): it
 * collects the engine's state, snapshots and events into one [UiState] and takes the user's intents.
 */
class AppModel(
    private val source: EngineSource,
    private val firstRun: FirstRun,
    private val scope: CoroutineScope,
    private val files: FileActions,
    /** Where the file actions run: opening a file can block (a slow share, xdg-open), so never on the UI thread. */
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** Quits the app, when the user chose that for after the queue. */
    private val onQuit: () -> Unit = {},
) {
    private val ui = MutableStateFlow(UiState(showWelcome = !firstRun.isDone()))
    val state: StateFlow<UiState> = ui.asStateFlow()

    private var started = false

    // The collectors run on several threads; every change to the fields below, and the refresh that reads them, holds this.
    private val lock = Any()
    private var stopJob: Deferred<Unit>? = null

    // Banners the user closed; the engine and disk ones are about a condition, so they stay closed while it lasts.
    private val dismissed = mutableSetOf<Banner>()
    private var startFailure: String? = null
    private var eventErrors = listOf<Banner.Error>()
    private var snapshots = emptyList<DownloadSnapshot>()
    private var engineState: EngineState = EngineState.NotStarted

    /** The settings screen; made the first time it is asked for, and kept, so a draft survives leaving the screen. */
    val settings: SettingsModel by lazy {
        SettingsModel(source, scope, io = io, onCategories = { categories -> ui.update { it.copy(categories = categories) } })
            .also { created = true }
    }

    /** The network, OS and tool sections of the settings screen (#609); each acts at once; only the tool folder field keeps a draft until Apply. */
    val panels: SettingsPanels by lazy { SettingsPanels(source, scope, io) }

    @Volatile
    private var created = false

    // The queue ran since quit after the queue was chosen; it quits once nothing is left to run.
    private var queueRan = false

    /** Starts the engine and follows it. Once. */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            source.state.collect {
                synchronized(lock) {
                    engineState = it
                    refresh()
                }
            }
        }
        scope.launch {
            source.downloads.collect {
                synchronized(lock) {
                    snapshots = it
                    refresh()
                }
            }
        }
        scope.launch {
            source.totalSpeed.collect { speed -> ui.update { it.copy(speed = speed.current) } }
        }
        scope.launch { source.events.collect(::onEvent) }
        scope.launch {
            try {
                source.start()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                synchronized(lock) {
                    startFailure = e.message ?: e::class.simpleName ?: "unknown error"
                    refresh()
                }
                return@launch
            }
            // The engine is up; a category list that can't be read leaves the sidebar without categories.
            val categories = runCatching { source.categories() }.getOrDefault(emptyList())
            ui.update { it.copy(categories = categories) }
        }
    }

    /** Stops the engine. Every caller, however late, waits for the one stop. */
    suspend fun stop() {
        val job = synchronized(lock) { stopJob ?: scope.async { source.stop() }.also { stopJob = it } }
        job.await()
    }

    /** True while a text field has the keys, so the list's shortcuts leave them alone. */
    @Volatile
    var typing = false

    fun navigate(route: Route) = relist { it.copy(route = route) }

    fun setSort(key: SortKey) =
        relist {
            val options = it.options
            // A second click on the same column turns it round.
            it.copy(options = options.copy(sort = key, descending = options.sort == key && !options.descending))
        }

    fun setQuery(query: String) = relist { it.copy(options = it.options.copy(query = query)) }

    fun toggleExpanded(id: DownloadId) =
        relist {
            val expanded = it.options.expanded
            it.copy(options = it.options.copy(expanded = if (id in expanded) expanded - id else expanded + id))
        }

    /** Selects [id] alone, or with [extend] toggles it in the selection. */
    fun select(
        id: DownloadId,
        extend: Boolean = false,
    ) = ui.update { current ->
        val selection =
            when {
                !extend -> setOf(id)
                id in current.selection -> current.selection - id
                else -> current.selection + id
            }
        current.copy(selection = selection, anchor = id, cursor = id)
    }

    /** Selects the rows from the anchor to [id], as the list shows them. */
    fun selectRange(id: DownloadId) =
        ui.update { current ->
            val ids = current.rows.map { it.id }
            val from = ids.indexOf(current.anchor ?: id).takeIf { it >= 0 } ?: ids.indexOf(id)
            val to = ids.indexOf(id)
            if (to < 0) return@update current
            current.copy(selection = ids.subList(minOf(from, to), maxOf(from, to) + 1).toSet(), anchor = ids[from], cursor = id)
        }

    fun selectAll() = ui.update { it.copy(selection = it.rows.map { row -> row.id }.toSet()) }

    /** Moves the cursor [delta] rows; with [extend] the selection runs from the anchor to it, else it is the cursor alone. */
    fun moveSelection(
        delta: Int,
        extend: Boolean = false,
    ) {
        val rows = state.value.rows
        if (rows.isEmpty()) return
        val at = rows.indexOfFirst { it.id == state.value.cursor }
        val next = rows[if (at < 0) (if (delta > 0) 0 else rows.lastIndex) else (at + delta).coerceIn(0, rows.lastIndex)].id
        if (extend) selectRange(next) else select(next)
    }

    fun clearSelection() = ui.update { it.copy(selection = emptySet()) }

    /** Gives [command] to the selected jobs it applies to; a refusal of any of them ends up in one banner. */
    fun run(command: JobCommand) {
        val current = state.value
        val targets = current.targets(command)
        if (!current.enabled(command)) return
        when (command) {
            JobCommand.REMOVE -> {
                ui.update { it.copy(dialog = Dialog.ConfirmRemove(targets.map { s -> s.download.id })) }
            }

            JobCommand.RELINK -> {
                val s = targets.single()
                val mirror = s.mirrors.firstOrNull { it.id == s.lastFailure?.mirrorId } ?: s.mirrors.first()
                ui.update { it.copy(dialog = Dialog.Relink(s.download.id, mirror.id, mirror.url)) }
            }

            JobCommand.OPEN, JobCommand.REVEAL, JobCommand.COPY_PATH -> {
                val path = placedFile(targets.single()) ?: return
                scope.launch(io) {
                    val failed =
                        when (command) {
                            JobCommand.OPEN -> files.open(path)
                            JobCommand.REVEAL -> files.reveal(path)
                            else -> files.copy(path.toString())
                        }
                    failed?.let(::addError)
                }
            }

            else -> {
                scope.launch {
                    each(command, targets) { s ->
                        val id = s.download.id
                        when (command) {
                            JobCommand.PAUSE -> source.pause(id)
                            JobCommand.RESUME -> source.resume(id)
                            JobCommand.CANCEL -> source.cancel(id)
                            JobCommand.RETRY -> source.retry(id, allowDuplicate = false)
                            JobCommand.DOWNLOAD_ANYWAY -> source.retry(id, allowDuplicate = true)
                            JobCommand.RAISE -> source.reprioritize(id, s.download.priority + 1)
                            JobCommand.LOWER -> source.reprioritize(id, s.download.priority - 1)
                            JobCommand.STOP_SEEDING -> source.stopSeeding(id)
                            else -> error("$command is not a per-job command")
                        }
                    }
                }
            }
        }
    }

    /** The saved proxies a job can take, read when the detail pane's Proxy tab opens. */
    private val proxyList = MutableStateFlow<List<ProxyEntry>>(emptyList())
    val proxies: StateFlow<List<ProxyEntry>> = proxyList.asStateFlow()

    fun loadProxies() {
        scope.launch {
            try {
                proxyList.value = source.proxies()
            } catch (e: CancellationException) {
                throw e
            } catch (_: IllegalStateException) {
                // A stopped engine has no proxies to offer; the tab shows the three built-in choices.
            }
        }
    }

    /** Sends [id] through [choice]; Inherit leaves it to its profile and the global choice. */
    fun setDownloadProxy(
        id: DownloadId,
        choice: ProxyChoice,
    ) = scope.launch { guarded { source.setDownloadProxy(id, choice) }?.let { refused("Changing the proxy", it) } }

    fun pauseAll() = scope.launch { guarded { source.pauseAll() }?.let { refused("Pause all", it) } }

    fun resumeAll() = scope.launch { guarded { source.resumeAll() }?.let { refused("Resume all", it) } }

    /** Removes the jobs the open remove dialog names, their files kept or sent to the trash. */
    fun confirmRemove(files: RemoveFiles) {
        val dialog = state.value.dialog as? Dialog.ConfirmRemove ?: return
        ui.update { it.copy(dialog = null) }
        val targets = dialog.ids.mapNotNull { id -> snapshotOf(id) }
        scope.launch { each(JobCommand.REMOVE, targets) { source.remove(it.download.id, files) } }
    }

    /** Gives the open relink dialog's job [url]; a link the engine refuses keeps the dialog open with the reason. */
    fun confirmRelink(url: String) {
        var dialog: Dialog.Relink? = null
        ui.update { current ->
            val open = current.dialog as? Dialog.Relink
            dialog = open?.takeUnless { it.busy }
            if (dialog == null) current else current.copy(dialog = open!!.copy(busy = true))
        }
        val asked = dialog ?: return
        scope.launch {
            val result = guarded { source.relink(asked.id, asked.mirrorId, url.trim()) }
            ui.update { current ->
                // Closed, or another job's dialog opened, while the link was tried: nothing to show it in.
                val open = current.dialog as? Dialog.Relink
                if (open == null || open.id != asked.id || open.mirrorId != asked.mirrorId) return@update current
                val dialog = open.copy(busy = false)
                when (result) {
                    is CommandResult.RelinkFailed -> {
                        current.copy(
                            dialog =
                                dialog.copy(
                                    url = url,
                                    error =
                                        result.failure.message ?: "the link was refused",
                                ),
                        )
                    }

                    is CommandResult.Done, null -> {
                        current.copy(dialog = null)
                    }

                    else -> {
                        current.copy(dialog = dialog.copy(url = url, error = reason(result)))
                    }
                }
            }
        }
    }

    fun closeDialog() = ui.update { it.copy(dialog = null) }

    /** The torrent's live peers, or null when the engine can't say. */
    suspend fun torrentDetail(id: DownloadId): TorrentDetailResult? =
        try {
            source.torrentDetail(id)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

    private fun snapshotOf(id: DownloadId) = synchronized(lock) { snapshots.firstOrNull { it.download.id == id } }

    private suspend fun each(
        command: JobCommand,
        targets: List<DownloadSnapshot>,
        call: suspend (DownloadSnapshot) -> CommandResult,
    ) {
        val refusals = mutableListOf<CommandResult>()
        for (s in targets) {
            val result = guarded { call(s) } ?: return
            if (result !is CommandResult.Done) refusals += result
        }
        if (refusals.isNotEmpty()) {
            addError("${refusals.size} of ${targets.size} could not be ${command.verb}: ${reason(refusals.first())}.")
        }
    }

    private fun refused(
        what: String,
        result: CommandResult,
    ) {
        if (result !is CommandResult.Done) addError("$what did not go through: ${reason(result)}.")
    }

    // A stopped engine takes no command (it throws); the banner says so once and the rest are not tried.
    private suspend fun guarded(call: suspend () -> CommandResult): CommandResult? =
        try {
            call()
        } catch (e: CancellationException) {
            throw e
        } catch (_: IllegalStateException) {
            addError("The download engine has stopped, so nothing was changed.")
            null
        }

    private fun reason(result: CommandResult): String =
        when (result) {
            is CommandResult.Done -> "done"
            CommandResult.NotFound -> "it is no longer in the list"
            is CommandResult.Rejected -> "it is ${result.rejected.from.name.lowercase()}"
            CommandResult.TrashUnavailable -> "this system has no trash, so the download and its files stay"
            is CommandResult.TrashFailed -> "a file could not be moved to the trash (${result.failure.detail ?: result.failure.path})"
            is CommandResult.Invalid -> result.reason
            is CommandResult.Duplicate -> "it is a duplicate"
            is CommandResult.RelinkFailed -> result.failure.message ?: "the link was refused"
        }

    private fun relist(change: (UiState) -> UiState) = synchronized(lock) { ui.update { listed(change(it)) } }

    // Rows for the route and options, and a selection of only what they show. Call it holding the lock.
    private fun listed(state: UiState): UiState {
        val filter = (state.route as? Route.Downloads)?.filter ?: Filter.All
        val rows = buildRows(snapshots, filter, state.options)
        val shown = rows.map { it.id }.toSet()
        return state.copy(rows = rows, selection = state.selection.filterTo(mutableSetOf()) { it in shown })
    }

    fun dismiss(banner: Banner) =
        synchronized(lock) {
            dismissed += banner
            eventErrors = eventErrors.filter { it != banner }
            refresh()
        }

    /** Opens the add dialog, with [links] in it; if it is open already, the links are added to what it holds. */
    fun openAdd(links: List<String> = emptyList()) {
        val open = state.value.adding
        if (open != null) {
            if (links.isNotEmpty()) open.setText((listOf(open.state.value.text) + links).filter { it.isNotBlank() }.joinToString("\n"))
            return
        }
        val add = AddModel(source, scope, links, onDone = ::closeAdd)
        ui.update { it.copy(adding = add) }
    }

    fun closeAdd() {
        val open = state.value.adding ?: return
        open.cancel()
        ui.update { if (it.adding === open) it.copy(adding = null) else it }
    }

    /** Closes the add dialog and selects the download a link duplicates, in the whole list. */
    fun showExisting(id: DownloadId) {
        closeAdd()
        navigate(Route.Downloads(Filter.All))
        select(id)
    }

    fun finishWelcome() {
        firstRun.markDone()
        ui.update { it.copy(showWelcome = false) }
    }

    private fun onEvent(event: EngineEvent) {
        when (event) {
            is EngineEvent.Removed -> {
                ui.update { it.copy(selection = it.selection - event.id) }
            }

            is EngineEvent.CookiesNotSaved -> {
                addError("Cookies could not be saved in the system keychain; they last until the app is closed.")
            }

            is EngineEvent.CredentialsNotSaved -> {
                addError("A login for ${event.origin} could not be saved in the system keychain; it lasts until the app is closed.")
            }

            is EngineEvent.ToolUnavailable -> {
                addError("The tool ${event.tool} can't be set up on this computer.")
            }

            is EngineEvent.StopTimedOut -> {
                addError("Some downloads were still saving when the app stopped; they resume from an earlier point.")
            }

            // No EngineFailed banner: the engine's Failed state already shows the unavailable one.
            else -> {
                Unit
            }
        }
    }

    private fun addError(message: String) =
        synchronized(lock) {
            val banner = Banner.Error(message)
            if (banner !in eventErrors) eventErrors = eventErrors + banner
            refresh()
        }

    private fun refresh() {
        val counts = count(snapshots)
        val ids = snapshots.map { it.download.id }.toSet()
        val banners = mutableListOf<Banner>()
        val unavailable =
            (engineState as? EngineState.Failed)?.let { it.error.message ?: it.error::class.simpleName ?: "unknown error" } ?: startFailure
        unavailable?.let { Banner.EngineUnavailable(it) }?.takeIf { it !in dismissed }?.let(banners::add)
        val diskFull = snapshots.any { PauseReason.LOW_DISK in it.download.pauseReasons }
        if (diskFull && Banner.DiskFull !in dismissed) banners += Banner.DiskFull
        if (!diskFull) dismissed -= Banner.DiskFull
        banners += eventErrors
        afterQueue()
        val globallyPaused = snapshots.any { PauseReason.GLOBAL in it.download.pauseReasons }
        ui.update {
            listed(
                it.copy(
                    engine = engineState,
                    counts = counts,
                    selection = it.selection.filterTo(mutableSetOf()) { id -> id in ids },
                    banners = banners,
                    globallyPaused = globallyPaused,
                ),
            )
        }
    }

    // Quit after the queue (#608): armed while something runs or waits, it fires when nothing does any more. Call it
    // holding the lock.
    private fun afterQueue() {
        if (!created || !settings.quitAfterQueue.value) {
            queueRan = false
            return
        }
        val busy = snapshots.any(::keepsQueueBusy)
        if (busy) {
            queueRan = true
        } else if (queueRan) {
            queueRan = false
            settings.quitAfterQueue.value = false
            onQuit()
        }
    }

    private fun count(all: List<DownloadSnapshot>): Counts {
        val statuses = all.map { it.download.status }
        return Counts(
            all = all.size,
            byGroup = StatusGroup.entries.associateWith { group -> statuses.count { it in group.statuses } },
            byCategory = all.mapNotNull { it.download.categoryId }.groupingBy { it }.eachCount(),
        )
    }
}
