package dev.anydm.store

import dev.anydm.api.ApiException
import dev.anydm.api.FetchedTorrent
import dev.anydm.api.SearchApi
import dev.anydm.api.Unauthorized
import dev.anydm.model.FoundTorrent
import dev.anydm.model.IndexerError
import dev.anydm.model.SearchAnswer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A search that failed everywhere: the API's message, and each source's reason. */
data class Failure(
    val message: String,
    val causes: List<IndexerError>,
)

/** Everything the Search view draws. */
data class SearchState(
    val query: String = "",
    val category: String = "all",
    /** What [answer] is: the latest releases, or a search. */
    val mode: SearchMode = SearchMode.BROWSE,
    val busy: Boolean = false,
    /** The query the answer is for ("" when browsing); the box may have changed since. */
    val searched: String = "",
    val answer: SearchAnswer? = null,
    val failure: Failure? = null,
    val sort: SortState = defaultSort(SearchMode.BROWSE),
    /** The link being fetched for Add, or "". */
    val fetching: String = "",
)

/**
 * The magnet hub, kept for the Search view: browse the indexers' latest releases or search them,
 * and hand a result to [addTorrent] (the download list's own add).
 *
 * Runs on [scope], which must be single-threaded (the desktop's `Dispatchers.Main`): a request's
 * `busy` flag is set before its coroutine starts, so a second call in the same tick is refused.
 */
class SearchStore(
    private val api: SearchApi,
    private val scope: CoroutineScope,
    private val addTorrent: suspend (String) -> Boolean,
) {
    private val mutableState = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = mutableState.asStateFlow()

    private val mutableAvailable = MutableStateFlow(false)

    /** Whether the API has anything to search; the sidebar shows Search only then. */
    val available: StateFlow<Boolean> = mutableAvailable.asStateFlow()

    private val mutableEvents = MutableSharedFlow<StoreEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<StoreEvent> = mutableEvents.asSharedFlow()

    /** Ask once whether search is on. Any failure, an older API without `/search` included, just means it isn't. */
    suspend fun checkAvailable() {
        mutableAvailable.value =
            try {
                api.searchSources().enabled
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                false
            }
    }

    /** The Search view opened: browse, unless there is already an answer, a failure or a request running. */
    fun open() {
        val current = state.value
        if (current.answer != null || current.failure != null || current.busy) return
        start(current.query, fresh = false)
    }

    fun setQuery(text: String) {
        mutableState.update { it.copy(query = text) }
    }

    /** A category re-runs the current mode; ignored while a request runs. */
    fun setCategory(id: String) {
        if (state.value.busy) return
        mutableState.update { it.copy(category = id) }
        start(state.value.query, fresh = false)
    }

    /** Search for the query in the box, or browse when it is empty; nothing for exactly one character. */
    fun run() = start(state.value.query, fresh = false)

    /** Browsing only: the same request again, skipping the API's cache. Searches are never cached. */
    fun refresh() {
        val current = state.value
        if (current.mode == SearchMode.BROWSE) start(current.searched, fresh = true)
    }

    /** After a failure: the request that failed, again. */
    fun retry() = start(state.value.searched, fresh = false)

    fun setSort(key: SortKey) {
        mutableState.update { it.copy(sort = nextSort(it.sort, key)) }
    }

    private fun start(
        text: String,
        fresh: Boolean,
    ) {
        val current = state.value
        if (!canRun(text, current.busy)) return
        val mode = modeFor(text) ?: return
        val q = text.trim()
        mutableState.update { it.copy(busy = true, failure = null) }
        scope.launch {
            try {
                val answer = api.search(q, current.category, fresh)
                mutableState.update {
                    it.copy(
                        busy = false,
                        answer = answer,
                        searched = q,
                        sort = if (it.mode != mode) defaultSort(mode) else it.sort,
                        mode = mode,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Unauthorized) {
                mutableState.update { it.copy(busy = false) }
                mutableEvents.emit(StoreEvent.SignedOut(error.message ?: "The API refused the key"))
            } catch (error: Exception) {
                mutableState.update { it.copy(busy = false, answer = null, searched = q, mode = mode, failure = failureOf(error)) }
            }
        }
    }

    /**
     * Give a result to the download list: its magnet, or its `.torrent` fetched through the API first.
     * Returns whether it was added; a failed fetch is a notice, never a throw.
     */
    suspend fun add(result: FoundTorrent): Boolean {
        result.magnet?.let { return addTorrent(it) }
        val link = result.link ?: return false
        if (state.value.fetching.isNotEmpty()) return false
        mutableState.update { it.copy(fetching = link) }
        return try {
            when (val fetched = api.fetchTorrent(link)) {
                is FetchedTorrent.Magnet -> addTorrent(fetched.value)
                is FetchedTorrent.File -> addTorrent(fetched.base64)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Unauthorized) {
            mutableEvents.emit(StoreEvent.SignedOut(error.message ?: "The API refused the key"))
            false
        } catch (error: Exception) {
            val from = result.indexers.firstOrNull() ?: "the indexer"
            mutableEvents.emit(
                StoreEvent.Said(Notice(Tone.ERROR, "Couldn't fetch the torrent from $from: ${error.message ?: "the transfer failed"}")),
            )
            false
        } finally {
            mutableState.update { it.copy(fetching = "") }
        }
    }

    private fun failureOf(error: Exception): Failure =
        if (error is ApiException) {
            Failure(error.message ?: "Search failed", error.details.map { IndexerError(it.subject ?: "", it.description ?: "") })
        } else {
            Failure("Search failed", emptyList())
        }
}
