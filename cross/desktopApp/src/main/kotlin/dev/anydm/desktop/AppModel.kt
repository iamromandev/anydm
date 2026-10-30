package dev.anydm.desktop

import dev.anydm.engine.DownloadSnapshot
import dev.anydm.engine.EngineEvent
import dev.anydm.engine.EngineState
import dev.anydm.engine.core.CategoryId
import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadStatus
import dev.anydm.engine.core.PauseReason
import dev.anydm.engine.files.Category
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
)

/**
 * What every screen reads (the only copy of engine data in the app is the counts and the selection derived here): it
 * collects the engine's state, snapshots and events into one [UiState] and takes the user's intents.
 */
class AppModel(
    private val source: EngineSource,
    private val firstRun: FirstRun,
    private val scope: CoroutineScope,
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

    fun navigate(route: Route) = ui.update { it.copy(route = route) }

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
        current.copy(selection = selection)
    }

    fun clearSelection() = ui.update { it.copy(selection = emptySet()) }

    fun dismiss(banner: Banner) =
        synchronized(lock) {
            dismissed += banner
            eventErrors = eventErrors.filter { it != banner }
            refresh()
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
        ui.update {
            it.copy(
                engine = engineState,
                counts = counts,
                selection = it.selection.filterTo(mutableSetOf()) { id -> id in ids },
                banners = banners,
            )
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
