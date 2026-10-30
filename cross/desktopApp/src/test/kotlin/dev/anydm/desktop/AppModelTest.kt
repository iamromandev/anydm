package dev.anydm.desktop

import dev.anydm.engine.EngineEvent
import dev.anydm.engine.EngineState
import dev.anydm.engine.SpeedWindow
import dev.anydm.engine.core.CategoryId
import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadStatus.COMPLETED
import dev.anydm.engine.core.DownloadStatus.DOWNLOADING
import dev.anydm.engine.core.DownloadStatus.FAILED
import dev.anydm.engine.core.DownloadStatus.MUXING
import dev.anydm.engine.core.DownloadStatus.PAUSED
import dev.anydm.engine.core.DownloadStatus.PENDING
import dev.anydm.engine.core.DownloadStatus.QUEUED
import dev.anydm.engine.core.DownloadStatus.SEEDING
import dev.anydm.engine.core.PauseReason
import dev.anydm.engine.files.Category
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.plus
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AppModelTest {
    private class MemoryFirstRun(
        var done: Boolean = false,
    ) : FirstRun {
        override fun isDone() = done

        override fun markDone() {
            done = true
        }
    }

    private fun TestScope.model(
        source: FakeSource,
        firstRun: FirstRun = MemoryFirstRun(done = true),
    ): AppModel = AppModel(source, firstRun, backgroundScope + UnconfinedTestDispatcher(testScheduler)).also { it.start() }

    @Test
    fun `counts follow the snapshots`() =
        runTest {
            val source = FakeSource()
            val model = model(source)
            source.downloads.value =
                listOf(
                    snapshot(1, DOWNLOADING),
                    snapshot(2, DOWNLOADING),
                    snapshot(3, MUXING),
                    snapshot(4, QUEUED),
                    snapshot(5, PENDING),
                    snapshot(6, PAUSED),
                    snapshot(7, COMPLETED),
                    snapshot(8, SEEDING),
                    snapshot(9, FAILED),
                )
            val counts = model.state.value.counts
            assertEquals(9, counts.all)
            assertEquals(3, counts[StatusGroup.ACTIVE])
            assertEquals(2, counts[StatusGroup.QUEUED])
            assertEquals(1, counts[StatusGroup.PAUSED])
            assertEquals(2, counts[StatusGroup.COMPLETED])
            assertEquals(1, counts[StatusGroup.FAILED])
        }

    @Test
    fun `cancelled counts only in all`() =
        runTest {
            val source = FakeSource()
            val model = model(source)
            source.downloads.value = listOf(snapshot(1, dev.anydm.engine.core.DownloadStatus.CANCELLED))
            val counts = model.state.value.counts
            assertEquals(1, counts.all)
            StatusGroup.entries.forEach { assertEquals(0, counts[it]) }
        }

    @Test
    fun `no downloads gives zeros`() =
        runTest {
            val model = model(FakeSource())
            val counts = model.state.value.counts
            assertEquals(0, counts.all)
            StatusGroup.entries.forEach { assertEquals(0, counts[it]) }
        }

    @Test
    fun `counts per category`() =
        runTest {
            val videos = Category(CategoryId(1), "Videos", "/v", null, emptyList(), 0)
            val source = FakeSource(categoryList = listOf(videos))
            val model = model(source)
            source.downloads.value =
                listOf(snapshot(1, DOWNLOADING, CategoryId(1)), snapshot(2, QUEUED, CategoryId(1)), snapshot(3, QUEUED))
            assertEquals(listOf(videos), model.state.value.categories)
            assertEquals(
                2,
                model.state.value.counts
                    .category(CategoryId(1)),
            )
        }

    @Test
    fun `starts the engine once`() =
        runTest {
            val source = FakeSource()
            val model = model(source)
            model.start()
            assertEquals(1, source.started)
            assertEquals(EngineState.Running, model.state.value.engine)
        }

    @Test
    fun `speed follows the total`() =
        runTest {
            val source = FakeSource()
            val model = model(source)
            source.totalSpeed.value = SpeedWindow(1_500_000, listOf(1, 2))
            assertEquals(1_500_000, model.state.value.speed)
        }

    @Test
    fun `navigation changes the route`() =
        runTest {
            val model = model(FakeSource())
            assertEquals(Route.Downloads(Filter.All), model.state.value.route)
            model.navigate(Route.Settings)
            assertEquals(Route.Settings, model.state.value.route)
            model.navigate(Route.Downloads(Filter.Status(StatusGroup.FAILED)))
            assertEquals(Route.Downloads(Filter.Status(StatusGroup.FAILED)), model.state.value.route)
        }

    @Test
    fun `selection toggles and clears`() =
        runTest {
            val source = FakeSource()
            val model = model(source)
            source.downloads.value = listOf(snapshot(1, QUEUED), snapshot(2, QUEUED))
            model.select(DownloadId(1))
            model.select(DownloadId(2), extend = true)
            assertEquals(setOf(DownloadId(1), DownloadId(2)), model.state.value.selection)
            model.select(DownloadId(1), extend = true)
            assertEquals(setOf(DownloadId(2)), model.state.value.selection)
            model.select(DownloadId(1))
            assertEquals(setOf(DownloadId(1)), model.state.value.selection)
            model.clearSelection()
            assertTrue(
                model.state.value.selection
                    .isEmpty(),
            )
        }

    @Test
    fun `selection drops a removed download`() =
        runTest {
            val source = FakeSource()
            val model = model(source)
            source.downloads.value = listOf(snapshot(1, QUEUED), snapshot(2, QUEUED))
            model.select(DownloadId(1))
            model.select(DownloadId(2), extend = true)
            source.downloads.value = listOf(snapshot(2, QUEUED))
            assertEquals(setOf(DownloadId(2)), model.state.value.selection)
        }

    @Test
    fun `selection drops an id on a Removed event`() =
        runTest {
            val source = FakeSource()
            val model = model(source)
            source.downloads.value = listOf(snapshot(1, QUEUED))
            model.select(DownloadId(1))
            // The snapshots still list it: only the event can drop it from the selection.
            source.events.emit(EngineEvent.Removed(DownloadId(1)))
            assertTrue(
                model.state.value.selection
                    .isEmpty(),
            )
        }

    @Test
    fun `a failed engine shows the unavailable banner`() =
        runTest {
            val source = FakeSource()
            val model = model(source)
            source.state.value = EngineState.Failed(IllegalStateException("db gone"))
            assertEquals(listOf(Banner.EngineUnavailable("db gone")), model.state.value.banners)
        }

    @Test
    fun `a start that throws shows the unavailable banner`() =
        runTest {
            val model = model(FakeSource(startError = IllegalStateException("no store")))
            assertEquals(listOf(Banner.EngineUnavailable("no store")), model.state.value.banners)
        }

    @Test
    fun `a full disk shows a banner until nothing is paused for it`() =
        runTest {
            val source = FakeSource()
            val model = model(source)
            source.downloads.value = listOf(snapshot(1, PAUSED, pauseReasons = setOf(PauseReason.LOW_DISK)))
            assertEquals(listOf(Banner.DiskFull), model.state.value.banners)
            source.downloads.value = listOf(snapshot(1, QUEUED))
            assertTrue(
                model.state.value.banners
                    .isEmpty(),
            )
        }

    @Test
    fun `an engine failure event shows an error banner that can be dismissed`() =
        runTest {
            val source = FakeSource()
            val model = model(source)
            source.events.emit(EngineEvent.CookiesNotSaved)
            val banner =
                model.state.value.banners
                    .single()
            assertTrue(banner is Banner.Error)
            model.dismiss(banner)
            assertTrue(
                model.state.value.banners
                    .isEmpty(),
            )
        }

    @Test
    fun `a dismissed unavailable banner stays dismissed while the engine stays failed`() =
        runTest {
            val source = FakeSource()
            val model = model(source)
            source.state.value = EngineState.Failed(IllegalStateException("x"))
            model.dismiss(
                model.state.value.banners
                    .single(),
            )
            assertTrue(
                model.state.value.banners
                    .isEmpty(),
            )
            // Another change while the engine is still failed does not bring it back.
            source.downloads.value = listOf(snapshot(1, QUEUED))
            assertTrue(
                model.state.value.banners
                    .isEmpty(),
            )
        }

    @Test
    fun `a dismissed disk banner comes back after the disk was freed and filled again`() =
        runTest {
            val source = FakeSource()
            val model = model(source)
            val full = listOf(snapshot(1, PAUSED, pauseReasons = setOf(PauseReason.LOW_DISK)))
            source.downloads.value = full
            model.dismiss(Banner.DiskFull)
            source.downloads.value = full + snapshot(2, QUEUED)
            assertTrue(
                model.state.value.banners
                    .isEmpty(),
            )
            source.downloads.value = listOf(snapshot(2, QUEUED))
            source.downloads.value = full
            assertEquals(listOf(Banner.DiskFull), model.state.value.banners)
        }

    @Test
    fun `an engine failure event adds no second banner`() =
        runTest {
            val source = FakeSource()
            val model = model(source)
            val error = IllegalStateException("db gone")
            source.state.value = EngineState.Failed(error)
            source.events.emit(EngineEvent.EngineFailed(error))
            assertEquals(1, model.state.value.banners.size)
        }

    @Test
    fun `the welcome shows on first run and not after it is finished`() =
        runTest {
            val firstRun = MemoryFirstRun(done = false)
            val model = model(FakeSource(), firstRun)
            assertTrue(model.state.value.showWelcome)
            model.finishWelcome()
            assertFalse(model.state.value.showWelcome)
            assertTrue(firstRun.done)
            assertFalse(model(FakeSource(), firstRun).state.value.showWelcome)
        }

    @Test
    fun `stopping twice stops the engine once`() =
        runTest {
            val source = FakeSource()
            val model = model(source)
            model.stop()
            model.stop()
            assertEquals(1, source.stopped)
            assertEquals(EngineState.Stopped, model.state.value.engine)
        }
}
