package dev.anydm.desktop.ui

import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.anydm.desktop.theme.DesktopTheme
import dev.anydm.model.FoundTorrent
import dev.anydm.model.IndexerError
import dev.anydm.model.SearchAnswer
import dev.anydm.store.Failure
import dev.anydm.store.SearchMode
import dev.anydm.store.SearchState
import dev.anydm.store.SortKey
import dev.anydm.store.defaultSort
import kotlin.test.Test
import kotlin.test.assertEquals

private const val NOW = 1_790_000_000_000L

private fun result(
    title: String,
    magnet: String? = "magnet:?xt=urn:btih:aa",
    link: String? = null,
) = FoundTorrent(
    title = title,
    sizeBytes = 725_614_592,
    seeders = 12,
    leechers = 1,
    published = "2026-09-28T10:00:00Z",
    magnet = magnet,
    link = link,
    indexers = listOf("apibay"),
)

private fun answer(vararg rows: FoundTorrent) =
    SearchAnswer(
        results = rows.toList(),
        errors = listOf(IndexerError("nyaa", "couldn't reach it")),
        asked = listOf("apibay", "nyaa"),
        tookMs = 912,
    )

@OptIn(ExperimentalTestApi::class)
class SearchScreenUiTest {
    private class Calls {
        val queries = mutableListOf<String>()
        var submits = 0
        val categories = mutableListOf<String>()
        var refreshes = 0
        var retries = 0
        val sorts = mutableListOf<SortKey>()
        val added = mutableListOf<FoundTorrent>()
        val copied = mutableListOf<String>()
    }

    private fun ComposeUiTest.screen(
        state: SearchState,
        calls: Calls = Calls(),
    ): Calls {
        setContent {
            DesktopTheme(dark = true) {
                SearchScreen(
                    state = state,
                    now = NOW,
                    focus = FocusRequester(),
                    onQuery = { calls.queries += it },
                    onSubmit = { calls.submits++ },
                    onCategory = { calls.categories += it },
                    onRefresh = { calls.refreshes++ },
                    onRetry = { calls.retries++ },
                    onSort = { calls.sorts += it },
                    onAdd = { calls.added += it },
                    onCopy = { calls.copied += it },
                    onFocusChange = {},
                )
            }
        }
        return calls
    }

    @Test
    fun `a browse shows the latest, the failed source, and Refresh`() =
        runComposeUiTest {
            val calls = screen(SearchState(mode = SearchMode.BROWSE, answer = answer(result("Big Buck Bunny 4K"))))
            onNodeWithText("Latest · 1 release from 1 indexer · 0.9 s").assertExists()
            onNodeWithText("nyaa: couldn't reach it").assertExists()
            onNodeWithText("Big Buck Bunny 4K").assertExists()
            onNodeWithText("apibay").assertExists()
            onNodeWithText("Refresh").performClick()
            assertEquals(1, calls.refreshes)
        }

    @Test
    fun `a search reads plainly and has no Refresh`() =
        runComposeUiTest {
            screen(
                SearchState(
                    mode = SearchMode.SEARCH,
                    searched = "bunny",
                    sort = defaultSort(SearchMode.SEARCH),
                    answer = answer(result("Big Buck Bunny 4K")),
                ),
            )
            onNodeWithText("1 result from 1 indexer · 0.9 s").assertExists()
            onNodeWithText("Refresh").assertDoesNotExist()
        }

    @Test
    fun `a header sorts, and the sorted one shows its arrow`() =
        runComposeUiTest {
            val calls = screen(SearchState(answer = answer(result("a"))))
            onNodeWithText("Age ↓").assertExists()
            onNodeWithText("Name").performClick()
            assertEquals(listOf(SortKey.TITLE), calls.sorts)
        }

    @Test
    fun `a category chip runs it`() =
        runComposeUiTest {
            val calls = screen(SearchState(answer = answer(result("a"))))
            onNodeWithText("TV").performClick()
            assertEquals(listOf("tv"), calls.categories)
        }

    @Test
    fun `the chips and the button are off while busy`() =
        runComposeUiTest {
            screen(SearchState(busy = true, answer = answer(result("a"))))
            onNodeWithText("TV").assertIsNotEnabled()
            // The tag sits on a wrapper; the button, which carries the enabled state, is its only child.
            onNodeWithTag("search-go").onChildren().onFirst().assertIsNotEnabled()
        }

    @Test
    fun `Add hands over the row, and Copy magnet only shows for a magnet`() =
        runComposeUiTest {
            val calls =
                screen(
                    SearchState(
                        answer =
                            answer(
                                result("With a magnet"),
                                result("Link only", magnet = null, link = "http://p/dl"),
                            ),
                    ),
                )
            onAllNodesWithText("Add")[0].performClick()
            assertEquals("With a magnet", calls.added.single().title)
            assertEquals(1, onAllNodesWithText("Copy").fetchSemanticsNodes().size)
            onAllNodesWithText("Copy")[0].performClick()
            assertEquals(listOf("magnet:?xt=urn:btih:aa"), calls.copied)
        }

    @Test
    fun `a link being fetched says so`() =
        runComposeUiTest {
            screen(
                SearchState(
                    fetching = "http://p/dl",
                    answer = answer(result("Link only", magnet = null, link = "http://p/dl")),
                ),
            )
            onNodeWithText("Fetching…").assertIsNotEnabled()
        }

    @Test
    fun `an empty browse says nothing is recent`() =
        runComposeUiTest {
            screen(SearchState(mode = SearchMode.BROWSE, answer = SearchAnswer()))
            onNodeWithText("Nothing recent from these indexers").assertExists()
        }

    @Test
    fun `an empty search names what was searched`() =
        runComposeUiTest {
            screen(SearchState(mode = SearchMode.SEARCH, searched = "zzz", answer = SearchAnswer()))
            onNodeWithText("No results for “zzz”").assertExists()
        }

    @Test
    fun `every source failing lists the reasons and offers Try again`() =
        runComposeUiTest {
            val calls =
                screen(
                    SearchState(failure = Failure("No indexer answered the search", listOf(IndexerError("apibay", "couldn't reach it")))),
                )
            onNodeWithText("No indexer answered the search").assertExists()
            onNodeWithText("apibay: couldn't reach it").assertExists()
            onNodeWithText("Try again").performClick()
            assertEquals(1, calls.retries)
        }

    @Test
    fun `before any answer it invites a search`() =
        runComposeUiTest {
            screen(SearchState())
            onNodeWithTag("search-idle").assertExists()
        }
}
