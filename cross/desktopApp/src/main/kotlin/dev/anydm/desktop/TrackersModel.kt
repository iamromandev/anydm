package dev.anydm.desktop

import dev.anydm.engine.TorrentActionResult
import dev.anydm.engine.TorrentDetailResult
import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.torrent.TorrentTracker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the dialog says in place of the add form for a private torrent. */
const val PRIVATE_TRACKERS_NOTICE = "This is a private torrent. External trackers cannot be added to private swarms."

private const val EMPTY_INPUT_ERROR = "Please enter at least one valid tracker URL (e.g. udp://... or http://...)"
private val TRACKER_SCHEMES = setOf("udp", "http", "https", "ws", "wss")

/**
 * The lines of a "New trackers" field read for adding: [fresh] are the tracker links not yet listed (in order, without
 * repeats), [invalid] counts the lines that are not tracker links, [duplicates] the links dropped as repeats or already listed,
 * and [empty] says the field has no text but blanks.
 */
data class TrackerParse(
    val fresh: List<String>,
    val invalid: Int,
    val duplicates: Int,
    val empty: Boolean,
)

/** Reads [text] (one link per line) against the trackers that are [existing] already. Schemes: udp, http, https, ws, wss. */
fun parseTrackers(
    text: String,
    existing: Collection<String>,
): TrackerParse {
    val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
    val seen = existing.toMutableSet()
    val fresh = mutableListOf<String>()
    var invalid = 0
    var duplicates = 0
    for (line in lines) {
        if (!isTrackerLink(line)) {
            invalid++
        } else if (seen.add(line)) {
            fresh += line
        } else {
            duplicates++
        }
    }
    return TrackerParse(fresh, invalid, duplicates, lines.isEmpty())
}

private fun isTrackerLink(line: String): Boolean {
    if (line.any { it.isWhitespace() || it.isISOControl() }) return false
    val scheme = line.substringBefore("://", "").lowercase()
    if (scheme !in TRACKER_SCHEMES) return false
    return line.substringAfter("://").isNotEmpty()
}

/** One tracker in the dialog's list. */
data class TrackerRow(
    val url: String,
    val custom: Boolean,
    val status: String,
    val seeds: Int?,
    val peers: Int?,
    /** The time to the next announce as a clock, or null when not known. */
    val next: String?,
)

/** How far the trackers got loading. */
sealed interface TrackersLoad {
    data object Loading : TrackersLoad

    data class Loaded(
        val trackers: List<TorrentTracker>,
        val private: Boolean,
    ) : TrackersLoad

    /** The torrent has no live handle (paused or stopped), so its trackers can't be read; adding still saves them. */
    data object NotRunning : TrackersLoad

    /** Like [NotRunning], and the engine says the torrent is private (from its stored metadata): nothing can be added. */
    data object NotRunningPrivate : TrackersLoad

    data class Failed(
        val message: String,
    ) : TrackersLoad
}

data class TrackersState(
    val load: TrackersLoad = TrackersLoad.Loading,
    val input: String = "",
    val submitting: Boolean = false,
    /** A red line: a refused add, or a failed reannounce. */
    val error: String? = null,
    /** An amber line: how many lines of an add were left out. */
    val ignored: String? = null,
    /** A plain line: the trackers were saved for the next start. */
    val info: String? = null,
    /** The engine refused to add trackers: the torrent is private. */
    val refusedPrivate: Boolean = false,
) {
    private val trackers: List<TorrentTracker> get() = (load as? TrackersLoad.Loaded)?.trackers.orEmpty()

    /** Default trackers first, then the ones added in the app, each in the engine's order. */
    val rows: List<TrackerRow>
        get() = trackers.sortedBy { it.custom }.map(::rowOf)

    val heading: String get() = "Trackers (${trackers.size})"

    val private: Boolean
        get() = refusedPrivate || load == TrackersLoad.NotRunningPrivate || (load as? TrackersLoad.Loaded)?.private == true

    /** What shows in place of the list: loading, none, not running or the load error; null when there are rows. */
    val loadMessage: String?
        get() =
            when (load) {
                TrackersLoad.Loading -> "Loading trackers…"
                TrackersLoad.NotRunning -> "Not running. Trackers can be added now and apply when it starts."
                TrackersLoad.NotRunningPrivate -> "Not running. Its trackers show here while it runs."
                is TrackersLoad.Failed -> load.message
                is TrackersLoad.Loaded -> if (load.trackers.isEmpty()) "No announce trackers configured." else null
            }

    /** The add form is on once the load is over (a stopped torrent counts: it keeps them), and not while an add runs. */
    val canAdd: Boolean get() = canSubmit && input.isNotBlank()

    /** The form is open to a submit (the keyboard's too, which on a blank field says what is missing). */
    internal val canSubmit: Boolean
        get() = !private && !submitting && (load is TrackersLoad.Loaded || load == TrackersLoad.NotRunning)

    val canReannounce: Boolean get() = load is TrackersLoad.Loaded && !submitting
}

private fun rowOf(tracker: TorrentTracker) =
    TrackerRow(
        url = tracker.url,
        custom = tracker.custom,
        status =
            tracker.message?.takeIf { it.isNotBlank() }
                ?: when {
                    tracker.updating -> "Updating…"
                    tracker.fails > 0 -> "Failed ×${tracker.fails}"
                    tracker.verified -> "Working"
                    !tracker.enabled -> "Disabled"
                    else -> "Not contacted yet"
                },
        seeds = tracker.scrapeSeeds,
        peers = tracker.scrapePeers,
        next = tracker.nextAnnounceSeconds?.let(::formatClock),
    )

private fun ignoredLine(invalid: Int): String? = if (invalid > 0) "$invalid line(s) weren't tracker link(s) and were ignored." else null

/**
 * The Trackers dialog of one torrent: loads the trackers from the engine when made, adds the links typed in the field
 * (checked here first) and reannounces. Commands run in [scope]; a failure shows as a line in [TrackersState], not a throw.
 */
class TrackersModel(
    private val source: EngineSource,
    private val id: DownloadId,
    private val scope: CoroutineScope,
) {
    private val mutable = MutableStateFlow(TrackersState())
    val state: StateFlow<TrackersState> = mutable

    init {
        scope.launch { load() }
    }

    private suspend fun read(): TrackersLoad =
        when (val result = source.torrentDetail(id)) {
            is TorrentDetailResult.Detail -> TrackersLoad.Loaded(result.detail.trackers, result.detail.isPrivate)
            TorrentDetailResult.PrivateNotInSession -> TrackersLoad.NotRunningPrivate
            else -> TrackersLoad.NotRunning
        }

    private suspend fun load() {
        val load =
            try {
                read()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                TrackersLoad.Failed("Couldn't load trackers: ${e.message ?: e::class.simpleName}")
            }
        mutable.update { it.copy(load = load) }
    }

    // After an add or a reannounce: a read that fails leaves the list that was shown and says so, rather than blanking it.
    private suspend fun reload() {
        try {
            val load = read()
            mutable.update { it.copy(load = load) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            mutable.update { it.copy(error = "Couldn't refresh the trackers: ${e.message ?: e::class.simpleName}") }
        }
    }

    fun setInput(text: String) = mutable.update { it.copy(input = text, error = null, ignored = null, info = null) }

    /** Adds what the field holds; the field clears only when the engine took it. */
    fun submit() {
        val now = mutable.value
        if (!now.canSubmit) return
        val existing = (now.load as? TrackersLoad.Loaded)?.trackers.orEmpty().map { it.url }
        val parsed = parseTrackers(now.input, existing)
        when {
            parsed.empty -> {
                mutable.update { it.copy(error = EMPTY_INPUT_ERROR, ignored = null, info = null) }
            }

            parsed.fresh.isEmpty() && parsed.duplicates == 0 -> {
                mutable.update { it.copy(error = "${parsed.invalid} line(s) weren't tracker link(s)", ignored = null, info = null) }
            }

            parsed.fresh.isEmpty() -> {
                // Everything valid typed is listed already: nothing to send, and what was left out is said.
                mutable.update { it.copy(input = "", error = null, ignored = ignoredLine(parsed.invalid), info = null) }
            }

            else -> {
                mutable.update { it.copy(submitting = true, error = null, ignored = null, info = null) }
                scope.launch { add(parsed) }
            }
        }
    }

    private suspend fun add(parsed: TrackerParse) {
        val outcome =
            try {
                // What the user asked for runs to its answer even when they close the dialog meanwhile (like the detail page's commands).
                withContext(NonCancellable) { source.addTorrentTrackers(id, parsed.fresh) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.update { it.copy(submitting = false, error = "Couldn't add trackers: ${e.message ?: e::class.simpleName}") }
                return
            }
        when (outcome) {
            TorrentActionResult.Done, TorrentActionResult.NotInSession -> {
                val saved = outcome == TorrentActionResult.NotInSession
                mutable.update {
                    it.copy(
                        submitting = false,
                        input = "",
                        ignored = ignoredLine(parsed.invalid),
                        info = if (saved) "Saved. The trackers are added when the torrent starts." else null,
                    )
                }
                reload()
            }

            TorrentActionResult.Private -> {
                mutable.update { it.copy(submitting = false, refusedPrivate = true) }
            }

            TorrentActionResult.Unavailable -> {
                mutable.update { it.copy(submitting = false, error = "Trackers can't be added right now.") }
            }
        }
    }

    /** Asks all trackers for peers now, then reads them again. */
    fun reannounce() {
        if (!mutable.value.canReannounce) return
        mutable.update { it.copy(error = null, info = null) }
        scope.launch {
            try {
                when (source.reannounceTorrent(id)) {
                    TorrentActionResult.Done -> reload()
                    TorrentActionResult.NotInSession -> mutable.update { it.copy(info = "Start the torrent to reannounce.") }
                    else -> mutable.update { it.copy(error = "Can't reannounce right now.") }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.update { it.copy(error = "Couldn't reannounce: ${e.message ?: e::class.simpleName}") }
            }
        }
    }
}
