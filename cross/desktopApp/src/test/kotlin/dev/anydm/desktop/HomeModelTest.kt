package dev.anydm.desktop

import dev.anydm.engine.SpeedWindow
import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.DownloadStatus.CANCELLED
import dev.anydm.engine.core.DownloadStatus.COMPLETED
import dev.anydm.engine.core.DownloadStatus.DOWNLOADING
import dev.anydm.engine.core.DownloadStatus.FAILED
import dev.anydm.engine.core.DownloadStatus.PAUSED
import dev.anydm.engine.core.DownloadStatus.PENDING
import dev.anydm.engine.core.DownloadStatus.QUEUED
import dev.anydm.engine.core.DownloadStatus.SEEDING
import dev.anydm.engine.core.Mirror
import dev.anydm.engine.core.MirrorId
import dev.anydm.engine.net.BindStatus
import dev.anydm.engine.net.BondedNetwork
import dev.anydm.engine.net.BondingState
import dev.anydm.engine.net.NetworkInfo
import dev.anydm.engine.queue.CommandResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
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
internal class HomeModelTest {
    private val mib = 1024L * 1024

    private fun home(block: suspend TestScope.(FakeSource, HomeModel) -> Unit) =
        runTest(UnconfinedTestDispatcher()) {
            val source = FakeSource()
            block(source, HomeModel(source, backgroundScope))
        }

    private fun id(value: Long) = DownloadId(value)

    private fun network(
        key: String,
        enabled: Boolean = true,
    ) = BondedNetwork(NetworkInfo(key, key, true, emptyList()), enabled, BindStatus.ISOLATED, null, false)

    @Test
    fun `an unanswered clear question does not come back when finished rows return`() =
        home { source, model ->
            source.downloads.value = listOf(snapshot(1, COMPLETED, name = "a.bin", size = 5))
            model.askClearFinished()
            assertTrue(model.state.value.confirmClear)
            source.downloads.value = emptyList()
            assertFalse(model.state.value.confirmClear)
            source.downloads.value = listOf(snapshot(2, COMPLETED, name = "b.bin", size = 5))
            assertFalse(model.state.value.confirmClear)
        }

    @Test
    fun `no downloads shows the empty state`() =
        home { _, model ->
            assertEquals(Empty.NO_DOWNLOADS, model.state.value.empty)
            assertTrue(
                model.state.value.groups
                    .isEmpty(),
            )
        }

    @Test
    fun `rows group by what they are doing in the reference order`() =
        home { source, model ->
            source.downloads.value =
                listOf(
                    snapshot(1, COMPLETED, name = "done.bin", size = 10),
                    snapshot(2, FAILED, name = "bad.bin"),
                    snapshot(3, PAUSED, name = "held.bin"),
                    snapshot(4, QUEUED, name = "next.bin"),
                    snapshot(5, DOWNLOADING, name = "now.bin"),
                    snapshot(6, PENDING, name = "probing.bin"),
                )
            val groups = model.state.value.groups
            assertEquals(
                listOf(GroupKind.DOWNLOADING, GroupKind.QUEUED, GroupKind.PAUSED, GroupKind.NEEDS_ATTENTION, GroupKind.FINISHED),
                groups.map { it.kind },
            )
            assertEquals(listOf(5L), groups[0].rows.map { it.id.value })
            assertEquals(listOf(4L, 6L), groups[1].rows.map { it.id.value })
            assertEquals(listOf("Downloading", "Queued", "Paused", "Needs attention", "Finished"), groups.map { it.kind.label })
        }

    @Test
    fun `cancelled jobs, items of a collection and engine tools are not listed`() =
        home { source, model ->
            source.downloads.value =
                listOf(
                    snapshot(1, CANCELLED, name = "gone"),
                    snapshot(2, DOWNLOADING, name = "item", parent = 9),
                    snapshot(3, DOWNLOADING, name = "yt-dlp", kind = DownloadKind.TOOL),
                    snapshot(4, DOWNLOADING, name = "shown"),
                )
            assertEquals(
                listOf(4L),
                model.state.value.groups
                    .flatMap { it.rows }
                    .map { it.id.value },
            )
        }

    @Test
    fun `seeding counts as finished`() =
        home { source, model ->
            source.downloads.value = listOf(snapshot(1, SEEDING, name = "t.iso", size = 5))
            assertEquals(
                GroupKind.FINISHED,
                model.state.value.groups
                    .single()
                    .kind,
            )
        }

    @Test
    fun `counts per filter and the summary text`() =
        home { source, model ->
            source.downloads.value =
                listOf(
                    snapshot(1, DOWNLOADING, name = "a"),
                    snapshot(2, DOWNLOADING, name = "b"),
                    snapshot(3, QUEUED, name = "c"),
                    snapshot(4, COMPLETED, name = "d"),
                    snapshot(5, FAILED, name = "e"),
                )
            val state = model.state.value
            assertEquals(5, state.counts.getValue(HomeFilter.ALL))
            assertEquals(3, state.counts.getValue(HomeFilter.IN_PROGRESS))
            assertEquals(1, state.counts.getValue(HomeFilter.FINISHED))
            assertEquals(1, state.counts.getValue(HomeFilter.NEEDS_ATTENTION))
            assertEquals("2 downloading · 1 queued · 1 need attention", state.summary)
        }

    @Test
    fun `the summary reads all done when nothing is in motion and is hidden for one group`() =
        home { source, model ->
            source.downloads.value = listOf(snapshot(1, DOWNLOADING, name = "a"))
            assertNull(model.state.value.summary, "one group already says it in its header")
            source.downloads.value = listOf(snapshot(1, COMPLETED, name = "a"), snapshot(2, COMPLETED, name = "b"))
            assertNull(model.state.value.summary)
            source.downloads.value = listOf(snapshot(1, DOWNLOADING, name = "a"), snapshot(2, COMPLETED, name = "b"))
            assertEquals("1 downloading", model.state.value.summary)
        }

    @Test
    fun `a filter keeps its groups and says so when none is left`() =
        home { source, model ->
            source.downloads.value = listOf(snapshot(1, DOWNLOADING, name = "a"), snapshot(2, COMPLETED, name = "b"))
            model.setFilter(HomeFilter.FINISHED)
            assertEquals(
                listOf(GroupKind.FINISHED),
                model.state.value.groups
                    .map { it.kind },
            )
            model.setFilter(HomeFilter.NEEDS_ATTENTION)
            assertTrue(
                model.state.value.groups
                    .isEmpty(),
            )
            assertEquals(Empty.NOTHING_FOR_FILTER, model.state.value.empty)
            model.setFilter(HomeFilter.ALL)
            assertEquals(Empty.NONE, model.state.value.empty)
        }

    @Test
    fun `changing the filter clears the selection`() =
        home { source, model ->
            source.downloads.value = listOf(snapshot(1, DOWNLOADING, name = "a"))
            model.toggle(id(1))
            model.setFilter(HomeFilter.IN_PROGRESS)
            assertTrue(
                model.state.value.selection
                    .isEmpty(),
            )
        }

    @Test
    fun `a row is selected and deselected`() =
        home { source, model ->
            source.downloads.value = listOf(snapshot(1, DOWNLOADING, name = "a"), snapshot(2, DOWNLOADING, name = "b"))
            model.toggle(id(1))
            assertEquals(setOf(id(1)), model.state.value.selection)
            assertTrue(
                model.state.value.groups
                    .single()
                    .rows
                    .first { it.id == id(1) }
                    .selected,
            )
            model.toggle(id(1))
            assertTrue(
                model.state.value.selection
                    .isEmpty(),
            )
        }

    @Test
    fun `a group box selects its rows and shows some or all`() =
        home { source, model ->
            source.downloads.value =
                listOf(snapshot(1, DOWNLOADING, name = "a"), snapshot(2, DOWNLOADING, name = "b"), snapshot(3, COMPLETED, name = "c"))
            model.toggle(id(1))
            val downloading =
                model.state.value.groups
                    .first { it.kind == GroupKind.DOWNLOADING }
            assertTrue(downloading.someSelected)
            assertFalse(downloading.allSelected)
            model.toggleGroup(GroupKind.DOWNLOADING)
            assertEquals(setOf(id(1), id(2)), model.state.value.selection)
            assertTrue(
                model.state.value.groups
                    .first { it.kind == GroupKind.DOWNLOADING }
                    .allSelected,
            )
            model.toggleGroup(GroupKind.DOWNLOADING)
            assertTrue(
                model.state.value.selection
                    .isEmpty(),
            )
        }

    @Test
    fun `select all takes every listed row and clear takes none`() =
        home { source, model ->
            source.downloads.value =
                listOf(snapshot(1, DOWNLOADING, name = "a"), snapshot(2, COMPLETED, name = "b"), snapshot(3, FAILED, name = "c"))
            model.selectAll()
            assertEquals(setOf(id(1), id(2), id(3)), model.state.value.selection)
            model.clearSelection()
            assertTrue(
                model.state.value.selection
                    .isEmpty(),
            )
        }

    @Test
    fun `select all respects the filter`() =
        home { source, model ->
            source.downloads.value = listOf(snapshot(1, DOWNLOADING, name = "a"), snapshot(2, COMPLETED, name = "b"))
            model.setFilter(HomeFilter.FINISHED)
            model.selectAll()
            assertEquals(setOf(id(2)), model.state.value.selection)
        }

    @Test
    fun `a row that is gone is no longer selected`() =
        home { source, model ->
            source.downloads.value = listOf(snapshot(1, DOWNLOADING, name = "a"), snapshot(2, DOWNLOADING, name = "b"))
            model.selectAll()
            source.downloads.value = listOf(snapshot(2, DOWNLOADING, name = "b"))
            assertEquals(setOf(id(2)), model.state.value.selection)
        }

    @Test
    fun `bulk pause touches only the selected rows that can pause`() =
        home { source, model ->
            source.downloads.value =
                listOf(
                    snapshot(1, DOWNLOADING, name = "a"),
                    snapshot(2, QUEUED, name = "b"),
                    snapshot(3, PAUSED, name = "c"),
                    snapshot(4, COMPLETED, name = "d"),
                    snapshot(5, DOWNLOADING, name = "e"),
                )
            model.toggle(id(1))
            model.toggle(id(2))
            model.toggle(id(3))
            model.toggle(id(4))
            assertEquals(BulkActions(pause = 2, resume = 1, retry = 0, remove = 4), model.state.value.bulk)
            model.pauseSelected()
            assertEquals(listOf("pause 1", "pause 2"), source.calls)
        }

    @Test
    fun `bulk resume and retry go to the rows they fit`() =
        home { source, model ->
            source.downloads.value =
                listOf(snapshot(1, PAUSED, name = "a"), snapshot(2, FAILED, name = "b"), snapshot(3, DOWNLOADING, name = "c"))
            model.selectAll()
            model.resumeSelected()
            model.retrySelected()
            assertEquals(listOf("resume 1", "retry 2"), source.calls)
        }

    @Test
    fun `bulk remove of a row still in motion asks first and keeps the files`() =
        home { source, model ->
            source.downloads.value = listOf(snapshot(1, COMPLETED, name = "a"), snapshot(2, FAILED, name = "b"))
            model.selectAll()
            model.askRemoveSelected()
            assertTrue(model.state.value.confirmRemove)
            assertTrue(source.calls.isEmpty(), "nothing is removed before the answer")
            model.dismissRemove()
            assertFalse(model.state.value.confirmRemove)
            assertTrue(source.calls.isEmpty())
            model.askRemoveSelected()
            model.confirmRemoveSelected()
            assertEquals(setOf("remove 1 KEEP", "remove 2 KEEP"), source.calls.toSet())
            assertFalse(model.state.value.confirmRemove)
            assertTrue(
                model.state.value.selection
                    .isEmpty(),
                "the selection is spent once its rows are removed",
            )
        }

    @Test
    fun `bulk remove of completed rows only needs no question`() =
        home { source, model ->
            source.downloads.value = listOf(snapshot(1, COMPLETED, name = "a"), snapshot(2, COMPLETED, name = "b"))
            model.selectAll()
            model.askRemoveSelected()
            assertFalse(model.state.value.confirmRemove)
            assertEquals(setOf("remove 1 KEEP", "remove 2 KEEP"), source.calls.toSet())
        }

    @Test
    fun `bulk remove of a seeding row asks first because removing stops the seeding`() =
        home { source, model ->
            source.downloads.value = listOf(snapshot(1, SEEDING, name = "a", size = 5))
            model.selectAll()
            model.askRemoveSelected()
            assertTrue(model.state.value.confirmRemove)
            assertTrue(source.calls.isEmpty())
        }

    @Test
    fun `the remove question goes away when the selection does`() =
        home { source, model ->
            source.downloads.value = listOf(snapshot(1, DOWNLOADING, name = "a"))
            model.selectAll()
            model.askRemoveSelected()
            model.clearSelection()
            assertFalse(model.state.value.confirmRemove)
        }

    @Test
    fun `a refused command is reported and the others still run`() =
        home { source, model ->
            source.downloads.value = listOf(snapshot(1, DOWNLOADING, name = "a"), snapshot(2, DOWNLOADING, name = "b"))
            source.results["pause 1"] = CommandResult.NotFound
            model.selectAll()
            model.pauseSelected()
            assertEquals(listOf("pause 1", "pause 2"), source.calls)
            assertEquals("1 of 2 downloads could not be paused.", model.state.value.message)
            model.dismissMessage()
            assertNull(model.state.value.message)
        }

    @Test
    fun `a stopped engine is reported not thrown`() =
        home { source, model ->
            source.downloads.value = listOf(snapshot(1, DOWNLOADING, name = "a"))
            source.refuseAll = true
            model.toggle(id(1))
            model.pauseSelected()
            assertEquals("The engine has stopped.", model.state.value.message)
        }

    @Test
    fun `the row button pauses a running row, resumes a paused one and retries a failed one`() =
        home { source, model ->
            source.downloads.value =
                listOf(
                    snapshot(1, DOWNLOADING, name = "a"),
                    snapshot(2, PAUSED, name = "b"),
                    snapshot(3, FAILED, name = "c"),
                    snapshot(4, COMPLETED, name = "d"),
                )
            val rows =
                model.state.value.groups
                    .flatMap { it.rows }
                    .associateBy { it.id.value }
            assertEquals(RowAction.PAUSE, rows.getValue(1).action)
            assertEquals(RowAction.RESUME, rows.getValue(2).action)
            assertEquals(RowAction.RETRY, rows.getValue(3).action)
            assertNull(rows.getValue(4).action)
            model.perform(id(1))
            model.perform(id(2))
            model.perform(id(3))
            assertEquals(listOf("pause 1", "resume 2", "retry 3"), source.calls)
        }

    @Test
    fun `clear finished asks first and does nothing until confirmed`() =
        home { source, model ->
            source.downloads.value = listOf(snapshot(1, COMPLETED, name = "a"), snapshot(2, DOWNLOADING, name = "b"))
            assertTrue(model.state.value.canClearFinished)
            model.askClearFinished()
            assertTrue(model.state.value.confirmClear)
            assertTrue(source.calls.isEmpty())
            model.dismissClearFinished()
            assertFalse(model.state.value.confirmClear)
            assertTrue(source.calls.isEmpty())
        }

    @Test
    fun `confirmed clear finished removes only finished rows and keeps their files`() =
        home { source, model ->
            source.downloads.value =
                listOf(
                    snapshot(1, COMPLETED, name = "a"),
                    snapshot(2, DOWNLOADING, name = "b"),
                    snapshot(3, COMPLETED, name = "c"),
                    snapshot(4, FAILED, name = "d"),
                )
            model.askClearFinished()
            model.confirmClearFinished()
            assertEquals(listOf("remove 1 KEEP", "remove 3 KEEP"), source.calls)
            assertFalse(model.state.value.confirmClear)
        }

    @Test
    fun `clear finished is not offered without a finished row`() =
        home { source, model ->
            source.downloads.value = listOf(snapshot(1, DOWNLOADING, name = "a"))
            assertFalse(model.state.value.canClearFinished)
            model.askClearFinished()
            assertFalse(model.state.value.confirmClear)
        }

    @Test
    fun `clear finished counts and removes only completed rows, not seeding ones`() =
        home { source, model ->
            source.downloads.value =
                listOf(
                    snapshot(1, COMPLETED, name = "a"),
                    snapshot(2, SEEDING, name = "b", size = 5),
                    snapshot(3, COMPLETED, name = "c"),
                )
            assertEquals(
                3,
                model.state.value.counts
                    .getValue(HomeFilter.FINISHED),
            )
            assertEquals(2, model.state.value.clearableCount)
            model.askClearFinished()
            model.confirmClearFinished()
            assertEquals(listOf("remove 1 KEEP", "remove 3 KEEP"), source.calls)
        }

    @Test
    fun `clear finished is not offered when only seeding rows are finished`() =
        home { source, model ->
            source.downloads.value = listOf(snapshot(1, SEEDING, name = "a", size = 5))
            assertEquals(0, model.state.value.clearableCount)
            assertFalse(model.state.value.canClearFinished)
            model.askClearFinished()
            assertFalse(model.state.value.confirmClear)
        }

    @Test
    fun `commands read the engine's rows even before the state has caught up`() =
        runTest(StandardTestDispatcher()) {
            val source = FakeSource()
            val model = HomeModel(source, backgroundScope)
            source.downloads.value =
                listOf(snapshot(1, DOWNLOADING, name = "a"), snapshot(2, PAUSED, name = "b"), snapshot(3, COMPLETED, name = "c"))
            model.selectAll()
            model.pauseSelected()
            model.resumeSelected()
            model.perform(id(1))
            runCurrent()
            assertEquals(listOf("pause 1", "resume 2", "pause 1"), source.calls)
        }

    @Test
    fun `clear finished asks even before the state has caught up`() =
        runTest(StandardTestDispatcher()) {
            val source = FakeSource()
            val model = HomeModel(source, backgroundScope)
            source.downloads.value = listOf(snapshot(1, COMPLETED, name = "a"))
            model.askClearFinished()
            runCurrent()
            assertTrue(model.state.value.confirmClear)
        }

    @Test
    fun `the pill counts networks, lists their keys and shows the combined speed`() =
        home { source, model ->
            assertEquals(0, model.state.value.pill.count)
            source.networks.value = BondingState(listOf(network("en0"), network("en7")))
            source.totalSpeed.value = SpeedWindow(5 * mib, emptyList())
            val pill = model.state.value.pill
            assertEquals(2, pill.count)
            assertEquals(listOf("en0", "en7"), pill.keys)
            assertEquals(5 * mib, pill.speed)
        }

    @Test
    fun `rows read as the reference reads them`() =
        home { source, model ->
            source.downloads.value =
                listOf(
                    snapshot(
                        1,
                        DOWNLOADING,
                        name = "Project.zip",
                        total = 200 * mib,
                        downloaded = 176 * mib,
                        speed = 4 * mib,
                        networkShare =
                            mapOf("en0" to 176 * mib),
                    ),
                    snapshot(
                        2,
                        COMPLETED,
                        name = "download",
                        size = 120 * 1024,
                        mirrors = listOf(Mirror(MirrorId(1), id(2), "https://getplexo.app/download", 0)),
                    ),
                    snapshot(3, PAUSED, name = "p.bin", total = 100, downloaded = 40),
                    snapshot(4, QUEUED, name = "q.bin", total = 100),
                    snapshot(5, FAILED, name = "f.bin"),
                    snapshot(6, DOWNLOADING, name = "dir", collection = dev.anydm.engine.core.CollectionKind.PLAYLIST),
                    snapshot(
                        7,
                        DOWNLOADING,
                        name = "ubuntu",
                        torrent =
                            dev.anydm.engine.torrent
                                .TorrentSummary(1, 1, 1.0, 1, 0, 0, null, 0),
                    ),
                )
            val rows =
                model.state.value.groups
                    .flatMap { it.rows }
                    .associateBy { it.id.value }
            assertEquals("ZIP", rows.getValue(1).badge)
            assertEquals("88% · 176.0 MB of 200.0 MB · 6s", rows.getValue(1).detail)
            assertEquals(listOf("en0" to 0.88f), rows.getValue(1).segments.map { it.key to it.fraction })
            assertEquals("FILE", rows.getValue(2).badge)
            assertEquals("120.0 KB · getplexo.app", rows.getValue(2).detail)
            assertEquals("Paused at 40% · 40 B of 100 B", rows.getValue(3).detail)
            assertEquals("Waiting for a turn · 100 B", rows.getValue(4).detail)
            assertEquals("Something went wrong", rows.getValue(5).detail)
            assertTrue(rows.getValue(5).failed)
            assertEquals("DIR", rows.getValue(6).badge)
            assertEquals("TORRENT", rows.getValue(7).badge)
        }
}
