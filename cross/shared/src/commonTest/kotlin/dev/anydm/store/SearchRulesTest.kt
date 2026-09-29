package dev.anydm.store

import dev.anydm.model.FoundTorrent
import dev.anydm.model.IndexerError
import kotlin.test.Test
import kotlin.test.assertEquals

private fun found(
    title: String,
    seeders: Int? = null,
    size: Long? = null,
    published: String? = null,
    indexers: List<String> = listOf("p"),
) = FoundTorrent(title = title, seeders = seeders, sizeBytes = size, published = published, indexers = indexers)

class SearchRulesTest {
    @Test
    fun `nothing typed browses, two characters search, and one does nothing`() {
        assertEquals(SearchMode.BROWSE, modeFor(""))
        assertEquals(SearchMode.BROWSE, modeFor("   "))
        assertEquals(null, modeFor(" a "))
        assertEquals(SearchMode.SEARCH, modeFor("ab"))
        assertEquals(SearchMode.SEARCH, modeFor("  big buck  "))
    }

    @Test
    fun `one request at a time, and never for one character`() {
        assertEquals(true, canRun("", false))
        assertEquals(true, canRun("ab", false))
        assertEquals(false, canRun("a", false))
        assertEquals(false, canRun("ab", true))
        assertEquals(false, canRun("", true))
    }

    @Test
    fun `a browse sorts newest first and a search most seeded first`() {
        assertEquals(SortState(SortKey.PUBLISHED, true), defaultSort(SearchMode.BROWSE))
        assertEquals(SortState(SortKey.SEEDERS, true), defaultSort(SearchMode.SEARCH))
    }

    @Test
    fun `a new column starts where it reads best and the same column flips`() {
        assertEquals(SortState(SortKey.SEEDERS, false), nextSort(SortState(SortKey.SEEDERS, true), SortKey.SEEDERS))
        assertEquals(SortState(SortKey.TITLE, false), nextSort(SortState(SortKey.SEEDERS, true), SortKey.TITLE))
        assertEquals(SortState(SortKey.SIZE, true), nextSort(SortState(SortKey.TITLE, false), SortKey.SIZE))
        assertEquals(SortState(SortKey.PUBLISHED, true), nextSort(SortState(SortKey.TITLE, false), SortKey.PUBLISHED))
    }

    @Test
    fun `unknown values sort last whichever way the column runs`() {
        val rows = listOf(found("b", seeders = 5), found("a", seeders = null), found("c", seeders = 40))
        assertEquals(listOf("c", "b", "a"), sortFound(rows, SortState(SortKey.SEEDERS, true)).map { it.title })
        assertEquals(listOf("b", "c", "a"), sortFound(rows, SortState(SortKey.SEEDERS, false)).map { it.title })
    }

    @Test
    fun `names sort without caring about case, sizes by bytes, dates by time`() {
        val names = listOf(found("b"), found("A"), found("c"))
        assertEquals(listOf("A", "b", "c"), sortFound(names, SortState(SortKey.TITLE, false)).map { it.title })
        val sized = listOf(found("b", size = 10), found("a", size = 30), found("c", size = 20))
        assertEquals(listOf("a", "c", "b"), sortFound(sized, SortState(SortKey.SIZE, true)).map { it.title })
        val dated =
            listOf(
                found("old", published = "2026-09-01T10:00:00Z"),
                found("none"),
                found("new", published = "2026-09-28T10:00:00Z"),
            )
        assertEquals(listOf("new", "old", "none"), sortFound(dated, SortState(SortKey.PUBLISHED, true)).map { it.title })
    }

    @Test
    fun `sorting keeps the order of ties and leaves its input alone`() {
        val rows = listOf(found("x", seeders = 1), found("y", seeders = 1), found("z", seeders = 1))
        assertEquals(listOf("x", "y", "z"), sortFound(rows, SortState(SortKey.SEEDERS, true)).map { it.title })
        assertEquals(listOf("x", "y", "z"), rows.map { it.title })
    }

    @Test
    fun `sizes read as the list writes them`() {
        assertEquals("692.0 MB", formatSize(725_614_592))
        assertEquals("1.5 KB", formatSize(1536))
        assertEquals("500 B", formatSize(500))
        assertEquals("0 B", formatSize(0))
        assertEquals("—", formatSize(null))
    }

    @Test
    fun `ages count in the largest whole unit`() {
        val now = 1_790_000_000_000L

        fun at(secondsAgo: Long) =
            kotlin.time.Instant
                .fromEpochMilliseconds(now - secondsAgo * 1000)
                .toString()
        assertEquals("3 days", formatAge(at(3 * 86_400), now))
        assertEquals("5 hours", formatAge(at(5 * 3_600), now))
        assertEquals("1 minute", formatAge(at(60), now))
        assertEquals("1 year", formatAge(at(366 * 86_400), now))
        assertEquals("just now", formatAge(at(20), now))
        assertEquals("—", formatAge(null, now))
        assertEquals("—", formatAge("not a date", now))
    }

    @Test
    fun `seeders are coloured by how likely the download is to finish`() {
        assertEquals(SeederTone.GOOD, seederTone(10))
        assertEquals(SeederTone.SOME, seederTone(9))
        assertEquals(SeederTone.SOME, seederTone(1))
        assertEquals(SeederTone.NONE, seederTone(0))
        assertEquals(SeederTone.NONE, seederTone(null))
    }

    @Test
    fun `the source column names the first and counts the rest`() {
        assertEquals("prowlarr-1", indexerLabel(listOf("prowlarr-1")))
        assertEquals("prowlarr-1 +2", indexerLabel(listOf("prowlarr-1", "jackett-all", "x")))
        assertEquals("", indexerLabel(emptyList()))
    }

    @Test
    fun `the status line says what came back, in both modes`() {
        assertEquals("42 results from 3 indexers · 0.9 s", statusLine(42, 3, 912, SearchMode.SEARCH))
        assertEquals("1 result from 1 indexer · 0.1 s", statusLine(1, 1, 50, SearchMode.SEARCH))
        assertEquals("Latest · 42 releases from 3 indexers · 0.9 s", statusLine(42, 3, 912, SearchMode.BROWSE))
        assertEquals("Latest · 1 release from 1 indexer · 0.0 s", statusLine(1, 1, 4, SearchMode.BROWSE))
    }

    @Test
    fun `an empty list says why, and a source that failed says so`() {
        assertEquals("Nothing recent from these indexers", emptyText(SearchMode.BROWSE, ""))
        assertEquals("No results for “bunny”", emptyText(SearchMode.SEARCH, "bunny"))
        assertEquals("jackett-all: timed out after 15 s", errorChip(IndexerError("jackett-all", "timed out after 15 s")))
    }

    @Test
    fun `the categories are the API's, in the web's order`() {
        assertEquals(
            listOf("all", "movies", "tv", "music", "software", "books", "other"),
            CATEGORIES.map { it.id },
        )
        assertEquals("TV", CATEGORIES.first { it.id == "tv" }.label)
    }
}
