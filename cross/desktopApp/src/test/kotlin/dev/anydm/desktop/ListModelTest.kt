package dev.anydm.desktop

import dev.anydm.engine.FileSnapshot
import dev.anydm.engine.core.Attempt
import dev.anydm.engine.core.AttemptId
import dev.anydm.engine.core.CollectionKind
import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadStatus.COMPLETED
import dev.anydm.engine.core.DownloadStatus.DOWNLOADING
import dev.anydm.engine.core.DownloadStatus.FAILED
import dev.anydm.engine.core.DownloadStatus.PAUSED
import dev.anydm.engine.core.DownloadStatus.QUEUED
import dev.anydm.engine.core.ErrorClass
import dev.anydm.engine.core.File
import dev.anydm.engine.core.FileId
import dev.anydm.engine.core.Mirror
import dev.anydm.engine.core.MirrorId
import dev.anydm.engine.core.PauseReason
import dev.anydm.engine.core.Step
import dev.anydm.engine.core.StepFailure
import dev.anydm.engine.core.TransitionResult
import dev.anydm.engine.platform.FileResult
import dev.anydm.engine.queue.CommandResult
import dev.anydm.engine.queue.RemoveFiles
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.plus
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** The list's side of [AppModel]: rows, selection, commands and their dialogs. */
@OptIn(ExperimentalCoroutinesApi::class)
class ListModelTest {
    private val source = FakeSource()
    private val files = FakeFiles()

    private fun TestScope.model(): AppModel =
        AppModel(
            source,
            object : FirstRun {
                override fun isDone() = true

                override fun markDone() = Unit
            },
            backgroundScope + UnconfinedTestDispatcher(testScheduler),
            files,
            io = UnconfinedTestDispatcher(testScheduler),
        ).also { it.start() }

    private fun ids(vararg values: Long) = values.map(::DownloadId).toSet()

    private fun failedWith(
        id: Long,
        errorClass: ErrorClass,
    ) = snapshot(
        id,
        FAILED,
        attempts =
            listOf(
                Attempt(AttemptId(1), DownloadId(id), Instant.fromEpochSeconds(0), MirrorId(5), errorClass = errorClass, message = "gone"),
            ),
        mirrors =
            listOf(
                Mirror(MirrorId(4), DownloadId(id), "https://a.example/f", 0),
                Mirror(MirrorId(5), DownloadId(id), "https://b.example/f", 1),
            ),
    )

    @Test
    fun `rows follow the snapshots and the route`() =
        runTest {
            val model = model()
            source.downloads.value = listOf(snapshot(1, QUEUED), snapshot(2, FAILED))
            assertEquals(
                listOf(1L, 2L),
                model.state.value.rows
                    .map { it.id.value },
            )
            model.navigate(Route.Downloads(Filter.Status(StatusGroup.FAILED)))
            assertEquals(
                listOf(2L),
                model.state.value.rows
                    .map { it.id.value },
            )
        }

    @Test
    fun `a second click on a column turns the sort round`() =
        runTest {
            val model = model()
            model.setSort(SortKey.SIZE)
            assertEquals(ListOptions(sort = SortKey.SIZE), model.state.value.options)
            model.setSort(SortKey.SIZE)
            assertEquals(true, model.state.value.options.descending)
            model.setSort(SortKey.NAME)
            assertEquals(false, model.state.value.options.descending)
        }

    @Test
    fun `rows the query hides leave the selection`() =
        runTest {
            val model = model()
            source.downloads.value = listOf(snapshot(1, QUEUED, name = "a.iso"), snapshot(2, QUEUED, name = "b.mp3"))
            model.select(DownloadId(1))
            model.select(DownloadId(2), extend = true)
            model.setQuery("iso")
            assertEquals(ids(1), model.state.value.selection)
        }

    @Test
    fun `shift selects a range in list order, and all selects every row`() =
        runTest {
            val model = model()
            source.downloads.value = (1L..5L).map { snapshot(it, QUEUED) }
            model.select(DownloadId(2))
            model.selectRange(DownloadId(4))
            assertEquals(ids(2, 3, 4), model.state.value.selection)
            model.selectRange(DownloadId(1))
            assertEquals(ids(1, 2), model.state.value.selection)
            model.selectAll()
            assertEquals(ids(1, 2, 3, 4, 5), model.state.value.selection)
        }

    @Test
    fun `arrow keys move the selection and shift extends it`() =
        runTest {
            val model = model()
            source.downloads.value = (1L..4L).map { snapshot(it, QUEUED) }
            model.moveSelection(1)
            assertEquals(ids(1), model.state.value.selection)
            model.moveSelection(1)
            assertEquals(ids(2), model.state.value.selection)
            model.moveSelection(1, extend = true)
            assertEquals(ids(2, 3), model.state.value.selection)
            model.moveSelection(-5)
            assertEquals(ids(1), model.state.value.selection)
        }

    @Test
    fun `a bulk command goes to each selected job it applies to`() =
        runTest {
            val model = model()
            source.downloads.value = listOf(snapshot(1, DOWNLOADING), snapshot(2, DOWNLOADING), snapshot(3, COMPLETED))
            model.selectAll()
            assertTrue(model.state.value.enabled(JobCommand.PAUSE))
            model.run(JobCommand.PAUSE)
            assertEquals(listOf("pause 1", "pause 2"), source.calls)
            assertTrue(
                model.state.value.banners
                    .isEmpty(),
            )
        }

    @Test
    fun `refusals end up in one banner and do not stop the rest`() =
        runTest {
            val model = model()
            source.downloads.value = (1L..3L).map { snapshot(it, DOWNLOADING) }
            source.results["pause 1"] = CommandResult.Rejected(TransitionResult.Rejected(COMPLETED, PAUSED))
            model.selectAll()
            model.run(JobCommand.PAUSE)
            assertEquals(listOf("pause 1", "pause 2", "pause 3"), source.calls)
            assertEquals(listOf(Banner.Error("1 of 3 could not be paused: it is completed.")), model.state.value.banners)
        }

    @Test
    fun `a disabled command does nothing`() =
        runTest {
            val model = model()
            source.downloads.value = listOf(snapshot(1, COMPLETED))
            model.select(DownloadId(1))
            model.run(JobCommand.RETRY)
            assertTrue(source.calls.isEmpty())
        }

    @Test
    fun `priority moves one step`() =
        runTest {
            val model = model()
            source.downloads.value = listOf(snapshot(1, QUEUED, priority = 3))
            model.select(DownloadId(1))
            model.run(JobCommand.RAISE)
            model.run(JobCommand.LOWER)
            assertEquals(listOf("priority 1 4", "priority 1 2"), source.calls)
        }

    @Test
    fun `remove asks first, then keeps or trashes the files`() =
        runTest {
            val model = model()
            source.downloads.value = listOf(snapshot(1, COMPLETED), snapshot(2, FAILED))
            model.selectAll()
            model.run(JobCommand.REMOVE)
            assertEquals(Dialog.ConfirmRemove(listOf(DownloadId(1), DownloadId(2))), model.state.value.dialog)
            assertTrue(source.calls.isEmpty())
            model.confirmRemove(RemoveFiles.KEEP)
            assertNull(model.state.value.dialog)
            assertEquals(listOf("remove 1 KEEP", "remove 2 KEEP"), source.calls)
        }

    @Test
    fun `a trash that fails keeps the job and says why`() =
        runTest {
            val model = model()
            source.downloads.value = listOf(snapshot(1, COMPLETED))
            source.results["remove 1 TRASH"] = CommandResult.TrashUnavailable
            model.select(DownloadId(1))
            model.run(JobCommand.REMOVE)
            model.confirmRemove(RemoveFiles.TRASH)
            assertEquals(
                listOf(Banner.Error("1 of 1 could not be removed: this system has no trash, so the download and its files stay.")),
                model.state.value.banners,
            )
        }

    @Test
    fun `a failed trash names the file`() =
        runTest {
            val model = model()
            source.downloads.value = listOf(snapshot(1, COMPLETED))
            source.results["remove 1 TRASH"] = CommandResult.TrashFailed(FileResult.NotFound(Path("/dl/a.iso")))
            model.select(DownloadId(1))
            model.run(JobCommand.REMOVE)
            model.confirmRemove(RemoveFiles.TRASH)
            assertTrue(
                (
                    model.state.value.banners
                        .single() as Banner.Error
                ).message.contains("/dl/a.iso"),
            )
        }

    @Test
    fun `relink starts from the failing mirror and keeps the dialog on a refusal`() =
        runTest {
            val model = model()
            source.downloads.value = listOf(failedWith(1, ErrorClass.GONE))
            model.select(DownloadId(1))
            model.run(JobCommand.RELINK)
            assertEquals(Dialog.Relink(DownloadId(1), MirrorId(5), "https://b.example/f"), model.state.value.dialog)

            source.results["relink 1 5 https://c.example/f"] =
                CommandResult.RelinkFailed(StepFailure(Step.PROBE, ErrorClass.GONE, "not found"))
            model.confirmRelink(" https://c.example/f ")
            val refused = assertIs<Dialog.Relink>(model.state.value.dialog)
            assertEquals("not found", refused.error)

            model.confirmRelink("https://d.example/f")
            assertNull(model.state.value.dialog)
            assertEquals(listOf("relink 1 5 https://c.example/f", "relink 1 5 https://d.example/f"), source.calls)
        }

    @Test
    fun `a duplicate can be downloaded anyway`() =
        runTest {
            val model = model()
            source.downloads.value = listOf(failedWith(1, ErrorClass.DUPLICATE))
            model.select(DownloadId(1))
            assertTrue(model.state.value.enabled(JobCommand.DOWNLOAD_ANYWAY))
            model.run(JobCommand.DOWNLOAD_ANYWAY)
            assertEquals(listOf("retry 1 anyway"), source.calls)
        }

    @Test
    fun `a finished file is opened, shown and copied`() =
        runTest {
            val model = model()
            val file = FileSnapshot(File(FileId(1), DownloadId(1), 0, "a.iso", placedPath = "a.iso"), emptyList())
            source.downloads.value = listOf(snapshot(1, COMPLETED, files = listOf(file), saveFolder = "/dl"))
            model.select(DownloadId(1))
            model.run(JobCommand.OPEN)
            model.run(JobCommand.REVEAL)
            model.run(JobCommand.COPY_PATH)
            assertEquals(listOf("open /dl/a.iso", "reveal /dl/a.iso", "copy /dl/a.iso"), files.done)

            files.failure = "/dl/a.iso is no longer there."
            model.run(JobCommand.OPEN)
            assertEquals(listOf(Banner.Error("/dl/a.iso is no longer there.")), model.state.value.banners)
        }

    @Test
    fun `a stopped engine refuses with one banner and nothing else is tried`() =
        runTest {
            val model = model()
            source.downloads.value = listOf(snapshot(1, DOWNLOADING), snapshot(2, DOWNLOADING))
            source.refuseAll = true
            model.selectAll()
            model.run(JobCommand.PAUSE)
            assertEquals(listOf(Banner.Error("The download engine has stopped, so nothing was changed.")), model.state.value.banners)
        }

    @Test
    fun `pause all and resume all go to the engine, and global pause shows`() =
        runTest {
            val model = model()
            model.pauseAll()
            model.resumeAll()
            assertEquals(listOf("pauseAll", "resumeAll"), source.calls)
            source.downloads.value = listOf(snapshot(1, PAUSED, pauseReasons = setOf(PauseReason.GLOBAL)))
            assertTrue(model.state.value.globallyPaused)
        }

    @Test
    fun `the detail is the one selected job`() =
        runTest {
            val model = model()
            source.downloads.value = listOf(snapshot(1, QUEUED), snapshot(2, QUEUED))
            assertNull(model.state.value.detail)
            model.select(DownloadId(2))
            assertEquals(
                DownloadId(2),
                model.state.value.detail
                    ?.download
                    ?.id,
            )
            model.select(DownloadId(1), extend = true)
            assertNull(model.state.value.detail)
        }

    @Test
    fun `an item whose collection is also selected is left to the collection`() =
        runTest {
            val model = model()
            source.downloads.value =
                listOf(
                    snapshot(10, DOWNLOADING, collection = CollectionKind.PLAYLIST),
                    snapshot(11, DOWNLOADING, parent = 10, playlistIndex = 0),
                    snapshot(12, DOWNLOADING),
                )
            model.toggleExpanded(DownloadId(10))
            model.selectAll()
            model.run(JobCommand.PAUSE)
            assertEquals(listOf("pause 10", "pause 12"), source.calls)
            model.run(JobCommand.REMOVE)
            assertEquals(Dialog.ConfirmRemove(listOf(DownloadId(10), DownloadId(12))), model.state.value.dialog)
        }

    @Test
    fun `a relink in flight takes no second one`() =
        runTest {
            val model = model()
            source.downloads.value = listOf(failedWith(1, ErrorClass.GONE))
            model.select(DownloadId(1))
            model.run(JobCommand.RELINK)
            val gate = CompletableDeferred<Unit>()
            source.gate = gate
            model.confirmRelink("https://c.example/f")
            assertEquals(true, (model.state.value.dialog as Dialog.Relink).busy)
            model.confirmRelink("https://c.example/f")
            source.gate = null
            gate.complete(Unit)
            assertEquals(listOf("relink 1 5 https://c.example/f"), source.calls)
            assertNull(model.state.value.dialog)
        }

    @Test
    fun `an item is retried when its selected collection can't be`() =
        runTest {
            val model = model()
            source.downloads.value =
                listOf(
                    snapshot(10, DOWNLOADING, collection = CollectionKind.PLAYLIST),
                    snapshot(11, FAILED, parent = 10, playlistIndex = 0),
                )
            model.toggleExpanded(DownloadId(10))
            model.selectAll()
            model.run(JobCommand.RETRY)
            assertEquals(listOf("retry 11"), source.calls)
        }
}
