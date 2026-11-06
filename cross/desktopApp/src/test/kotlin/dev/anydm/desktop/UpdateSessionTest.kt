package dev.anydm.desktop

import dev.anydm.engine.core.DownloadStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A dispatcher that holds what it is given until [runQueued]: stands for the thread a blocking call runs on. */
private class QueuedDispatcher : CoroutineDispatcher() {
    private val queue = ArrayDeque<Runnable>()

    override fun dispatch(
        context: CoroutineContext,
        block: Runnable,
    ) {
        queue.addLast(block)
    }

    fun runQueued() {
        while (queue.isNotEmpty()) queue.removeFirst().run()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
internal class UpdateSessionTest {
    @Test
    fun `events become notices`() =
        runTest {
            val controls = FakeUpdateControls()
            val session = UpdateSession(controls, FakeFileActions(), backgroundScope)
            backgroundScope.launch { session.collectEvents() }
            runCurrent()
            assertNull(session.notice.value)
            controls.emit(UpdateEvent.UpToDate)
            runCurrent()
            assertEquals(UpdateNotice.UpToDate, session.notice.value)
            controls.emit(UpdateEvent.CheckFailed)
            runCurrent()
            assertEquals(UpdateNotice.CheckFailed, session.notice.value)
            session.dismiss()
            assertNull(session.notice.value)
        }

    @Test
    fun `restart that needs a confirmation raises the question and a failure raises none`() =
        runTest {
            val controls = FakeUpdateControls()
            val session = UpdateSession(controls, FakeFileActions(), backgroundScope)
            controls.unconfirmed = RestartResult.Failed("no")
            session.restart()
            runCurrent()
            assertNull(session.notice.value)
            controls.unconfirmed = RestartResult.NeedsConfirmation(2)
            session.restart()
            runCurrent()
            assertEquals(UpdateNotice.ConfirmRestart(2), session.notice.value)
            session.confirmRestart()
            runCurrent()
            assertNull(session.notice.value)
            assertEquals(listOf("restart false", "restart false", "restart true"), controls.calls)
        }

    @Test
    fun `only transferring downloads count as running`() {
        val downloads =
            listOf(
                snapshot(1, DownloadStatus.DOWNLOADING),
                snapshot(2, DownloadStatus.MUXING),
                snapshot(3, DownloadStatus.PAUSED),
                snapshot(4, DownloadStatus.COMPLETED),
                snapshot(5, DownloadStatus.QUEUED),
                snapshot(6, DownloadStatus.FAILED),
            )
        assertEquals(2, runningDownloads(downloads))
        assertEquals(0, runningDownloads(emptyList()))
    }

    @Test
    fun `the website is opened off the calling thread`() =
        runTest {
            val files = FakeFileActions()
            val io = QueuedDispatcher()
            val session = UpdateSession(FakeUpdateControls(), files, backgroundScope, io)

            session.openWebsite("https://github.com/iamromandev/anydm/releases/tag/v1.2.0")
            runCurrent()
            assertEquals(emptyList(), files.calls)

            io.runQueued()
            runCurrent()
            assertEquals(listOf("openUrl https://github.com/iamromandev/anydm/releases/tag/v1.2.0"), files.calls)
            assertNull(session.notice.value)
        }

    @Test
    fun `a page that could not be opened becomes a notice`() =
        runTest {
            val files = FakeFileActions()
            files.problem = "This system can't open web pages from anydm."
            val session = UpdateSession(FakeUpdateControls(), files, backgroundScope, UnconfinedTestDispatcher(testScheduler))

            session.openWebsite("https://github.com/iamromandev/anydm/releases/tag/v1.2.0")
            runCurrent()

            assertEquals(UpdateNotice.OpenFailed("This system can't open web pages from anydm."), session.notice.value)
            session.dismiss()
            assertNull(session.notice.value)
        }
}
