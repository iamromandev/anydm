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
import dev.anydm.engine.queue.RemoveFiles
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.nio.file.Path
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
    fun `bulk remove of completed rows still asks first`() =
        home { source, model ->
            source.downloads.value = listOf(snapshot(1, COMPLETED, name = "a"), snapshot(2, COMPLETED, name = "b"))
            model.selectAll()
            model.askRemoveSelected()
            assertTrue(model.state.value.confirmRemove)
            assertTrue(source.calls.isEmpty(), "nothing is removed before the answer")
        }

    @Test
    fun `remove with the trash answer sends TRASH for every selected row`() =
        home { source, model ->
            source.downloads.value = listOf(snapshot(1, COMPLETED, name = "a"), snapshot(2, FAILED, name = "b"))
            model.selectAll()
            model.askRemoveSelected()
            model.confirmRemoveSelected(RemoveFiles.TRASH)
            assertEquals(setOf("remove 1 TRASH", "remove 2 TRASH"), source.calls.toSet())
        }

    @Test
    fun `remove with nothing selected does not ask`() =
        home { source, model ->
            source.downloads.value = listOf(snapshot(1, COMPLETED, name = "a"))
            model.askRemoveSelected()
            assertFalse(model.state.value.confirmRemove)
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

    // Queue order and the row menu.

    private fun queuedIds(model: HomeModel) =
        model.state.value.groups
            .first { it.kind == GroupKind.QUEUED }
            .rows
            .map { it.id.value }

    @Test
    fun `queued rows list by priority then by id`() =
        home { source, model ->
            source.downloads.value =
                listOf(
                    snapshot(1, QUEUED, name = "a", priority = 0),
                    snapshot(2, QUEUED, name = "b", priority = 5),
                    snapshot(4, QUEUED, name = "d", priority = 0),
                    snapshot(3, PENDING, name = "c", priority = 0),
                )
            assertEquals(listOf(2L, 1L, 3L, 4L), queuedIds(model))
        }

    private fun menuHome(
        existing: Set<Path> = emptySet(),
        block: suspend TestScope.(FakeSource, FakeFileActions, HomeModel) -> Unit,
    ) = runTest(UnconfinedTestDispatcher()) {
        val source = FakeSource()
        val files = FakeFileActions()
        block(source, files, HomeModel(source, backgroundScope, files, exists = { it in existing }))
    }

    private fun queuedThree(source: FakeSource) {
        source.downloads.value = (1L..3L).map { snapshot(it, QUEUED, name = "f$it") }
    }

    @Test
    fun `right-click on an unselected row selects only it and opens its menu`() =
        menuHome { source, _, model ->
            source.downloads.value = listOf(snapshot(1, COMPLETED, name = "a"), snapshot(2, COMPLETED, name = "b"))
            model.toggle(id(1))
            model.openMenu(id(2))
            assertEquals(setOf(id(2)), model.state.value.selection)
            assertEquals(
                listOf(id(2)),
                model.state.value.menu
                    ?.ids,
            )
        }

    @Test
    fun `right-click on a selected row keeps the whole selection`() =
        menuHome { source, _, model ->
            source.downloads.value = (1L..3L).map { snapshot(it, COMPLETED, name = "f$it") }
            model.selectAll()
            model.openMenu(id(2))
            assertEquals(3, model.state.value.selection.size)
            assertEquals(
                3,
                model.state.value.menu
                    ?.ids
                    ?.size,
            )
            val actions =
                model.state.value.menu!!
                    .actions
            assertTrue(MenuAction.REMOVE in actions)
            assertFalse(MenuAction.OPEN in actions || MenuAction.REVEAL in actions || MenuAction.COPY_LINK in actions)
        }

    @Test
    fun `the menu closes when the selection changes or closeMenu is called`() =
        menuHome { source, _, model ->
            source.downloads.value = listOf(snapshot(1, COMPLETED, name = "a"), snapshot(2, COMPLETED, name = "b"))
            model.openMenu(id(1))
            model.toggle(id(2))
            assertNull(model.state.value.menu)
            model.openMenu(id(1))
            model.closeMenu()
            assertNull(model.state.value.menu)
        }

    @Test
    fun `a completed row's menu offers open reveal copy and remove, open enabled only when the file exists`() =
        menuHome(existing = setOf(Path.of("/dl/a.bin"))) { source, _, model ->
            source.downloads.value =
                listOf(
                    snapshot(
                        1,
                        COMPLETED,
                        name = "a.bin",
                        saveFolder = "/dl",
                        mirrors = listOf(Mirror(MirrorId(1), id(1), "http://h/a.bin", 0)),
                    ),
                    snapshot(2, COMPLETED, name = "gone.bin", saveFolder = "/dl"),
                )
            model.openMenu(id(1))
            val menu = model.state.value.menu!!
            assertEquals(setOf(MenuAction.OPEN, MenuAction.REVEAL, MenuAction.COPY_LINK, MenuAction.REMOVE), menu.actions)
            assertTrue(menu.openable)
            assertTrue(menu.locatable)
            model.openMenu(id(2))
            val gone = model.state.value.menu!!
            assertFalse(gone.openable)
            assertFalse(gone.locatable)
            assertTrue(MenuAction.OPEN in gone.actions, "shown, but disabled")
            assertFalse(MenuAction.COPY_LINK in gone.actions, "no link to copy")
        }

    @Test
    fun `a downloading row has pause and no file or move items`() =
        menuHome { source, _, model ->
            source.downloads.value = listOf(snapshot(1, DOWNLOADING, name = "a"))
            model.openMenu(id(1))
            assertEquals(
                setOf(MenuAction.PAUSE, MenuAction.REMOVE),
                model.state.value.menu!!
                    .actions,
            )
        }

    @Test
    fun `paused and failed rows offer resume and retry`() =
        menuHome { source, _, model ->
            source.downloads.value = listOf(snapshot(1, PAUSED, name = "a"), snapshot(2, FAILED, name = "b"))
            model.openMenu(id(1))
            assertTrue(
                MenuAction.RESUME in
                    model.state.value.menu!!
                        .actions,
            )
            model.openMenu(id(2))
            assertTrue(
                MenuAction.RETRY in
                    model.state.value.menu!!
                        .actions,
            )
        }

    @Test
    fun `move items depend on the queue position`() =
        menuHome { source, _, model ->
            queuedThree(source)
            val moves = setOf(MenuAction.MOVE_TOP, MenuAction.MOVE_UP, MenuAction.MOVE_DOWN)
            model.openMenu(id(1))
            assertEquals(
                setOf(MenuAction.MOVE_DOWN),
                model.state.value.menu!!
                    .actions
                    .intersect(moves),
            )
            model.openMenu(id(2))
            assertEquals(
                moves,
                model.state.value.menu!!
                    .actions
                    .intersect(moves),
            )
            model.openMenu(id(3))
            assertEquals(
                setOf(MenuAction.MOVE_TOP, MenuAction.MOVE_UP),
                model.state.value.menu!!
                    .actions
                    .intersect(moves),
            )
        }

    @Test
    fun `open calls the file action for a completed row and closes the menu`() =
        menuHome(existing = setOf(Path.of("/dl/a.bin"))) { source, files, model ->
            source.downloads.value = listOf(snapshot(1, COMPLETED, name = "a.bin", saveFolder = "/dl"))
            model.openMenu(id(1))
            model.perform(MenuAction.OPEN)
            assertEquals(listOf("open /dl/a.bin"), files.calls)
            assertNull(model.state.value.menu)
        }

    @Test
    fun `open reports a problem in the banner`() =
        menuHome { source, files, model ->
            source.downloads.value = listOf(snapshot(1, COMPLETED, name = "a.bin", saveFolder = "/dl"))
            files.problem = "a.bin is no longer there."
            model.openMenu(id(1))
            model.perform(MenuAction.OPEN)
            assertEquals("a.bin is no longer there.", model.state.value.message)
        }

    @Test
    fun `reveal on the selected download shows the nearest folder that exists`() =
        menuHome(existing = setOf(Path.of("/dl"))) { source, files, model ->
            source.downloads.value = listOf(snapshot(1, DOWNLOADING, name = "a.bin", saveFolder = "/dl"))
            model.openMenu(id(1))
            model.reveal()
            assertEquals(listOf("reveal /dl"), files.calls)
        }

    @Test
    fun `copy link copies the first mirror and reports a problem`() =
        menuHome { source, files, model ->
            source.downloads.value =
                listOf(
                    snapshot(
                        1,
                        DOWNLOADING,
                        name = "a",
                        mirrors = listOf(Mirror(MirrorId(1), id(1), "http://h/a", 0), Mirror(MirrorId(2), id(1), "http://i/a", 1)),
                    ),
                )
            model.openMenu(id(1))
            model.perform(MenuAction.COPY_LINK)
            assertEquals(listOf("copy http://h/a"), files.calls)
            assertNull(model.state.value.message)
            files.problem = "no clipboard"
            model.openMenu(id(1))
            model.copyLink()
            assertEquals("no clipboard", model.state.value.message)
        }

    @Test
    fun `perform pause resume retry and remove act on the selection`() =
        menuHome { source, _, model ->
            source.downloads.value =
                listOf(snapshot(1, DOWNLOADING, name = "a"), snapshot(2, PAUSED, name = "b"), snapshot(3, FAILED, name = "c"))
            model.selectAll()
            model.openMenu(id(1))
            model.perform(MenuAction.PAUSE)
            model.perform(MenuAction.RESUME)
            model.perform(MenuAction.RETRY)
            assertEquals(listOf("pause 1", "resume 2", "retry 3"), source.calls)
            model.perform(MenuAction.REMOVE)
            assertTrue(model.state.value.confirmRemove)
        }

    @Test
    fun `move to top renumbers so the row is first`() =
        menuHome { source, _, model ->
            queuedThree(source)
            model.moveToTop(id(3))
            assertEquals(listOf(3L, 1L, 2L), queuedIds(model))
            val priority = source.downloads.value.associate { it.download.id.value to it.download.priority }
            assertTrue(priority.getValue(3) > priority.getValue(1))
            assertTrue(priority.getValue(1) > priority.getValue(2))
            assertEquals(listOf("reprioritize 3 2", "reprioritize 1 1"), source.calls, "only the rows that changed")
        }

    @Test
    fun `move up and move down swap with the neighbour`() =
        menuHome { source, _, model ->
            queuedThree(source)
            model.moveUp(id(3))
            assertEquals(listOf(1L, 3L, 2L), queuedIds(model))
            model.moveDown(id(1))
            assertEquals(listOf(3L, 1L, 2L), queuedIds(model))
        }

    @Test
    fun `moving the first row up or the last row down does nothing, as does moving a row that is not queued`() =
        menuHome { source, _, model ->
            source.downloads.value =
                listOf(snapshot(1, QUEUED, name = "a"), snapshot(2, QUEUED, name = "b"), snapshot(3, DOWNLOADING, name = "c"))
            model.moveUp(id(1))
            model.moveDown(id(2))
            model.moveToTop(id(1))
            model.moveToTop(id(3))
            assertTrue(source.calls.isEmpty())
        }

    @Test
    fun `a failed move shows the could-not-be-moved banner`() =
        menuHome { source, _, model ->
            queuedThree(source)
            source.results["reprioritize 3 2"] = CommandResult.Invalid("no")
            model.moveToTop(id(3))
            assertEquals("1 of 2 downloads could not be moved.", model.state.value.message)
        }

    @Test
    fun `a move on a stopped engine says so`() =
        menuHome { source, _, model ->
            queuedThree(source)
            source.refuseAll = true
            model.moveUp(id(3))
            assertEquals("The engine has stopped.", model.state.value.message)
        }

    @Test
    fun `double-click opens a completed or seeding row but no other`() =
        menuHome { source, files, model ->
            source.downloads.value =
                listOf(
                    snapshot(1, COMPLETED, name = "a", saveFolder = "/dl"),
                    snapshot(2, SEEDING, name = "b", saveFolder = "/dl"),
                    snapshot(3, DOWNLOADING, name = "c", saveFolder = "/dl"),
                )
            model.openFile(id(1))
            model.openFile(id(2))
            model.openFile(id(3))
            assertEquals(listOf("open /dl/a", "open /dl/b"), files.calls)
        }

    @Test
    fun `moving with gapped priorities renumbers from the lowest and sends the moved row first`() =
        menuHome { source, _, model ->
            source.downloads.value = listOf(snapshot(1, QUEUED, name = "a", priority = 10), snapshot(2, QUEUED, name = "b", priority = 0))
            model.moveDown(id(1))
            assertEquals(listOf(2L, 1L), queuedIds(model))
            assertEquals(listOf("reprioritize 1 0", "reprioritize 2 1"), source.calls)
        }

    @Test
    fun `move down on a middle row swaps with the next`() =
        menuHome { source, _, model ->
            queuedThree(source)
            model.moveDown(id(2))
            assertEquals(listOf(1L, 3L, 2L), queuedIds(model))
        }

    @Test
    fun `equal priority rows get distinct priorities after a move`() =
        menuHome { source, _, model ->
            queuedThree(source)
            model.moveUp(id(3))
            val priorities = source.downloads.value.map { it.download.priority }
            assertEquals(3, priorities.toSet().size)
            assertEquals(listOf(1L, 3L, 2L), queuedIds(model))
        }

    @Test
    fun `move items on a multi-selection do nothing`() =
        menuHome { source, _, model ->
            queuedThree(source)
            model.selectAll()
            model.openMenu(id(2))
            MenuAction.entries.filter { it.name.startsWith("MOVE") }.forEach { model.perform(it) }
            assertTrue(source.calls.isEmpty())
        }

    @Test
    fun `open and reveal on a multi-selection do nothing`() =
        menuHome(existing = setOf(Path.of("/dl/f1"), Path.of("/dl/f2"))) { source, files, model ->
            source.downloads.value = (1L..2L).map { snapshot(it, COMPLETED, name = "f$it", saveFolder = "/dl") }
            model.selectAll()
            model.openMenu(id(1))
            model.perform(MenuAction.OPEN)
            model.openMenu(id(1))
            model.perform(MenuAction.REVEAL)
            assertTrue(files.calls.isEmpty())
        }

    @Test
    fun `every menu action closes the menu`() =
        menuHome { source, _, model ->
            source.downloads.value = listOf(snapshot(1, QUEUED, name = "a"), snapshot(2, QUEUED, name = "b"))
            for (action in MenuAction.entries) {
                model.openMenu(id(1))
                model.perform(action)
                assertNull(model.state.value.menu, "$action leaves the menu open")
                model.dismissRemove()
            }
        }

    @Test
    fun `the disk is checked when the menu opens, not on every state change`() {
        var checks = 0
        runTest(UnconfinedTestDispatcher()) {
            val source = FakeSource()
            val model = HomeModel(source, backgroundScope, FakeFileActions(), exists = { checks++ >= 0 })
            source.downloads.value = listOf(snapshot(1, COMPLETED, name = "a", saveFolder = "/dl"))
            model.openMenu(id(1))
            val after = checks
            source.downloads.value = listOf(snapshot(1, COMPLETED, name = "a", saveFolder = "/dl", downloaded = 5))
            model.state.value
            assertEquals(after, checks)
            assertTrue(
                model.state.value.menu!!
                    .openable,
            )
        }
    }
}
