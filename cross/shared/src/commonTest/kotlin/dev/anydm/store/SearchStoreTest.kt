package dev.anydm.store

import dev.anydm.api.ApiException
import dev.anydm.api.ErrorDetail
import dev.anydm.api.FetchedTorrent
import dev.anydm.model.IndexerError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SearchStoreTest {
    private val api = FakeSearchApi()
    private val seen = mutableListOf<StoreEvent>()
    private val added = mutableListOf<String>()
    private var addWorks = true

    private fun TestScope.store(): SearchStore {
        val store =
            SearchStore(api, backgroundScope) { torrent ->
                added += torrent
                addWorks
            }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { store.events.toList(seen) }
        return store
    }

    private fun said(message: String) = seen.filterIsInstance<StoreEvent.Said>().any { it.notice.message == message }

    @Test
    fun `opening browses the current category once, and a second open does nothing`() =
        runTest {
            val store = store()
            store.open()
            runCurrent()
            store.open()
            runCurrent()
            assertEquals(listOf(Triple("", "all", false)), api.searches)
            assertEquals(SearchMode.BROWSE, store.state.value.mode)
            assertEquals(api.answer, store.state.value.answer)
            assertFalse(store.state.value.busy)
        }

    @Test
    fun `a category re-runs the current mode`() =
        runTest {
            val store = store()
            store.open()
            runCurrent()
            store.setCategory("tv")
            runCurrent()
            assertEquals(Triple("", "tv", false), api.searches.last())
            store.setQuery("bunny")
            store.run()
            runCurrent()
            store.setCategory("movies")
            runCurrent()
            assertEquals(Triple("bunny", "movies", false), api.searches.last())
        }

    @Test
    fun `one character does nothing, and two search`() =
        runTest {
            val store = store()
            store.setQuery(" a ")
            store.run()
            runCurrent()
            assertTrue(api.searches.isEmpty())
            store.setQuery("ab")
            store.run()
            runCurrent()
            assertEquals(listOf(Triple("ab", "all", false)), api.searches)
            assertEquals(SearchMode.SEARCH, store.state.value.mode)
            assertEquals("ab", store.state.value.searched)
        }

    @Test
    fun `a second request while one runs is refused`() =
        runTest {
            api.gate = CompletableDeferred()
            val store = store()
            store.open()
            runCurrent()
            assertTrue(store.state.value.busy)
            store.run()
            store.setCategory("tv")
            runCurrent()
            assertEquals(1, api.searches.size)
            api.gate!!.complete(Unit)
            runCurrent()
            assertFalse(store.state.value.busy)
        }

    @Test
    fun `the sort resets only when the mode changes`() =
        runTest {
            val store = store()
            store.open()
            runCurrent()
            assertEquals(SortState(SortKey.PUBLISHED, true), store.state.value.sort)
            store.setSort(SortKey.TITLE)
            store.setQuery("bunny")
            store.run()
            runCurrent()
            assertEquals(SortState(SortKey.SEEDERS, true), store.state.value.sort)
            store.setSort(SortKey.TITLE)
            store.run()
            runCurrent()
            assertEquals(SortState(SortKey.TITLE, false), store.state.value.sort)
        }

    @Test
    fun `an answer that failed everywhere clears the results and lists each reason`() =
        runTest {
            api.failure =
                ApiException(
                    "No indexer answered the search",
                    502,
                    "search_failed",
                    listOf(ErrorDetail("apibay", "couldn't reach it")),
                )
            val store = store()
            store.open()
            runCurrent()
            val state = store.state.value
            assertNull(state.answer)
            assertEquals(Failure("No indexer answered the search", listOf(IndexerError("apibay", "couldn't reach it"))), state.failure)
            assertFalse(state.busy)
            api.failure = null
            store.retry()
            runCurrent()
            assertNull(store.state.value.failure)
            assertEquals(api.answer, store.state.value.answer)
        }

    @Test
    fun `refresh asks for the browse again, fresh, and does nothing for a search`() =
        runTest {
            val store = store()
            store.open()
            runCurrent()
            store.refresh()
            runCurrent()
            assertEquals(Triple("", "all", true), api.searches.last())
            store.setQuery("bunny")
            store.run()
            runCurrent()
            val calls = api.searches.size
            store.refresh()
            runCurrent()
            assertEquals(calls, api.searches.size)
        }

    @Test
    fun `add gives a result's magnet to the download list`() =
        runTest {
            val store = store()
            assertTrue(store.add(found("a", magnet = "magnet:?xt=urn:btih:aa")))
            assertEquals(listOf("magnet:?xt=urn:btih:aa"), added)
            assertTrue(api.fetches.isEmpty())
        }

    @Test
    fun `a result with only a link is fetched first, then added as a magnet or a file`() =
        runTest {
            val store = store()
            api.fetched = FetchedTorrent.File("ZDg6")
            assertTrue(store.add(found("b", magnet = null, link = "http://p/dl", indexers = listOf("prowlarr-1"))))
            assertEquals(listOf("http://p/dl"), api.fetches)
            assertEquals(listOf("ZDg6"), added)
            assertEquals("", store.state.value.fetching)
            api.fetched = FetchedTorrent.Magnet("magnet:?xt=urn:btih:bb")
            store.add(found("c", magnet = null, link = "http://p/dl2"))
            assertEquals("magnet:?xt=urn:btih:bb", added.last())
        }

    @Test
    fun `a link that can't be fetched says which indexer and why`() =
        runTest {
            val store = store()
            api.fetchFailure = ApiException("the indexer answered 404", 502, null)
            assertFalse(store.add(found("b", magnet = null, link = "http://p/dl", indexers = listOf("prowlarr-1"))))
            assertTrue(said("Couldn't fetch the torrent from prowlarr-1: the indexer answered 404"))
            assertTrue(added.isEmpty())
            assertEquals("", store.state.value.fetching)
        }

    @Test
    fun `a result with neither a magnet nor a link adds nothing, and a failed add says so`() =
        runTest {
            val store = store()
            assertFalse(store.add(found("x", magnet = null, link = null)))
            assertTrue(added.isEmpty())
            addWorks = false
            assertFalse(store.add(found("a")))
        }

    @Test
    fun `search is available only when the API says it's on`() =
        runTest {
            val store = store()
            assertFalse(store.available.value)
            store.checkAvailable()
            assertTrue(store.available.value)
            api.enabled = false
            store.checkAvailable()
            assertFalse(store.available.value)
            api.enabled = true
            api.sourcesFailure = ApiException("Not found", 404, null)
            store.checkAvailable()
            assertFalse(store.available.value)
        }
}
