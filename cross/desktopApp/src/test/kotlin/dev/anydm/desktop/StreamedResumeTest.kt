package dev.anydm.desktop

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.anydm.engine.http.Clock
import dev.anydm.engine.http.DownloadEngine
import dev.anydm.engine.http.EngineEvent
import dev.anydm.engine.http.ProbeOutcome
import dev.anydm.engine.http.ProbeResult
import dev.anydm.engine.http.TRAILER_BYTES
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A row set to several connections that ran on one stream comes back from its `.part` size after a restart (AC1 to AC3). */
@OptIn(ExperimentalTestApi::class)
class StreamedResumeTest {
    private val guard = ThreadGuard()
    private val disk = MemoryDiskIo(guard)
    private val folder = "/home/user/Downloads"
    private val part = Path.of("$folder/a.bin.part")
    private val final = Path.of("$folder/a.bin")
    private val total = 1536 * 1024
    private val data = Random(11).nextBytes(total)
    private val server = RangeServer(data)
    private val prober = FakeProber(guard, fallback = ProbeOutcome.Ok(ProbeResult("a.bin", total.toLong(), true)))
    private val downloads = GuardedDownloadsStore(guard)

    private inner class Session(
        initial: DownloadsFile? = null,
    ) {
        val store = if (initial == null) downloads else GuardedDownloadsStore(guard, InMemoryDownloadsStore(initial))
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val engine = DownloadEngine(server, disk, Clock { System.currentTimeMillis() }, scope)
        val stopped = CopyOnWriteArrayList<EngineEvent.Stopped>()

        init {
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                engine.events.collect { if (it is EngineEvent.Stopped) stopped.add(it) }
            }
        }

        val stores = AppStores(InMemorySettingsStore(), InMemorySecretStore(), 500, store)
        val env = DownloadEnv(engine, prober, FakeOpener(guard), disk)
    }

    private fun ComposeUiTest.home(s: Session) =
        setContent {
            guard.forbidden.add(Thread.currentThread())
            HomeScreen(emptySample(), true, clipboardText = { null }, stores = s.stores, downloadEnv = s.env)
        }

    private fun ComposeUiTest.await(
        what: String,
        cond: () -> Boolean,
    ) {
        val end = System.nanoTime() + 10_000_000_000L
        while (!cond()) {
            check(System.nanoTime() < end) { "timed out waiting for $what" }
            mainClock.advanceTimeByFrame()
            Thread.sleep(5)
        }
    }

    private fun ComposeUiTest.shown(tag: String) = runCatching { onNodeWithTag(tag).assertIsDisplayed() }.isSuccess

    private fun row(
        done: Long,
        totalOrNull: Long? = total.toLong(),
        streamed: Boolean = true,
    ) = DownloadDto(
        "1",
        "https://x.test/a.bin",
        "a.bin",
        folder,
        totalOrNull,
        done,
        DownloadStatus.Paused,
        etag = "\"v1\"",
        connections = 8,
        streamed = streamed,
    )

    private fun requestsAfterResume(
        file: DownloadsFile,
        shows: String,
    ): List<String> {
        server.requests.clear()
        runComposeUiTest {
            val s = Session(file)
            home(s)
            await("the restored row") { shown("row-1") }
            onNodeWithText(shows).assertIsDisplayed()
            onNodeWithTag("pause-1").performClick() // Paused rows show the resume control under the same tag
            await("the download to finish") { disk.files[final]?.contentEquals(data) == true }
        }
        assertFalse(disk.files.containsKey(part))
        return server.requests.toList()
    }

    @Test
    fun aRowOnEightConnectionsThatRanOnOneStreamResumesFromItsPartSizeAfterARestart() {
        val done = 900 * 1024
        runComposeUiTest {
            val s = Session()
            server.limit = done.toLong()
            home(s)
            onNodeWithTag("new-download").performClick()
            onNodeWithTag("url-field").performTextInput("https://x.test/a.bin")
            await("the probe") { runCatching { onNodeWithText("1.5 MB · Resumable · 8 connections").assertIsDisplayed() }.isSuccess }
            onNodeWithTag("download").performClick()
            await("the budget is used") { server.servedBytes() >= server.limit }
            onNodeWithTag("pause-1").performClick()
            await("the row saved as streamed") {
                downloads.saves.isNotEmpty() &&
                    downloads.saves
                        .last()
                        .downloads
                        .singleOrNull()
                        ?.let { it.streamed && it.status == DownloadStatus.Paused } == true
            }
            assertEquals(
                8,
                downloads.saves
                    .last()
                    .downloads
                    .single()
                    .connections,
            )
        }
        server.requests.clear()
        server.limit = Long.MAX_VALUE
        val saved = downloads.saves.last()
        val partSize = disk.files.getValue(part).size
        assertTrue(partSize > 0, "the part holds the stream's bytes")
        runComposeUiTest {
            val s = Session(saved)
            home(s)
            await("the restored row") { shown("row-1") }
            onNodeWithText(
                "${partSize * 100L / total}% · ${formatBytes(partSize.toLong())} of ${formatBytes(total.toLong())}",
            ).assertIsDisplayed()
            onNodeWithTag("pause-1").performClick()
            await("the download to finish") { disk.files[final]?.contentEquals(data) == true }
        }
        assertEquals(listOf("bytes=$partSize-"), server.requests.toList())
        assertFalse(disk.files.containsKey(part))
    }

    /**
     * A first run that cannot be split (the server ignores `Range`, [reportsLength] or not) paused at [done] bytes, then a restart on
     * a server that takes ranges: the resume asks from the part's size and the file comes out byte for byte.
     */
    private fun ranOnOneStreamThenResumedOnAServerWithRanges(
        done: Long,
        reportsLength: Boolean,
    ) {
        server.ranges = false
        server.reportsLength = reportsLength
        runComposeUiTest {
            val s = Session()
            server.limit = done
            home(s)
            onNodeWithTag("new-download").performClick()
            onNodeWithTag("url-field").performTextInput("https://x.test/a.bin")
            await("the probe") { runCatching { onNodeWithText("1.5 MB · Resumable · 8 connections").assertIsDisplayed() }.isSuccess }
            onNodeWithTag("download").performClick()
            await("the budget is used") { server.servedBytes() >= server.limit }
            onNodeWithTag("pause-1").performClick()
            await("the row saved as streamed") {
                downloads.saves.isNotEmpty() &&
                    downloads.saves
                        .last()
                        .downloads
                        .singleOrNull()
                        ?.let { it.streamed && it.status == DownloadStatus.Paused } == true
            }
            val saved =
                downloads.saves
                    .last()
                    .downloads
                    .single()
            assertEquals(8, saved.connections)
            assertEquals(if (reportsLength) total.toLong() else null, saved.total)
        }
        val saved = downloads.saves.last()
        val partSize = disk.files.getValue(part).size
        assertTrue(partSize > 0, "the part holds the stream's bytes")
        server.requests.clear()
        server.limit = Long.MAX_VALUE
        server.ranges = true
        runComposeUiTest {
            val s = Session(saved)
            home(s)
            await("the restored row") { shown("row-1") }
            onNodeWithTag("pause-1").performClick()
            await("the download to finish") { disk.files[final]?.contentEquals(data) == true }
        }
        assertEquals(listOf("bytes=$partSize-"), server.requests.toList())
        assertFalse(disk.files.containsKey(part))
    }

    @Test
    fun aRowWhoseFirstRunHadNoLengthAndNoRangesResumesFromItsPartSizeOnceTheServerTakesRanges() {
        ranOnOneStreamThenResumedOnAServerWithRanges(700L * 1024, reportsLength = false)
    }

    @Test
    fun aRowWhoseServerIgnoredRangeOnTheFirstRunResumesFromItsPartSizeOnceTheServerHonoursIt() {
        ranOnOneStreamThenResumedOnAServerWithRanges(900L * 1024, reportsLength = true)
    }

    @Test
    fun aStreamedRowOfUnknownSizeResumesFromItsPartSize() {
        val size = 700 * 1024
        disk.files[part] = data.copyOf(size)
        val requests =
            requestsAfterResume(
                DownloadsFile(downloads = listOf(row(size.toLong(), totalOrNull = null))),
                formatBytes(size.toLong()),
            )
        assertEquals(listOf("bytes=$size-"), requests)
    }

    @Test
    fun aStreamedRowWhosePartWasDeletedComesBackAtZeroAndStartsFromByteZero() {
        val requests =
            requestsAfterResume(DownloadsFile(downloads = listOf(row(900L * 1024))), "0% · 0 B of ${formatBytes(total.toLong())}")
        assertEquals(listOf("none"), requests)
    }

    @Test
    fun aStreamedRowWhosePartHasTheLengthOfASegmentedFileComesBackAtZeroAndStartsFromByteZero() {
        disk.files[part] = ByteArray(total + TRAILER_BYTES)
        val requests =
            requestsAfterResume(DownloadsFile(downloads = listOf(row(900L * 1024))), "0% · 0 B of ${formatBytes(total.toLong())}")
        assertEquals(listOf("none"), requests)
    }

    @Test
    fun aRowOnSeveralConnectionsThatIsNotMarkedStreamedStillStartsOverFromAnExistingPart() {
        disk.files[part] = data.copyOf(900 * 1024)
        val requests =
            requestsAfterResume(
                DownloadsFile(downloads = listOf(row(900L * 1024, streamed = false))),
                "0% · 0 B of ${formatBytes(total.toLong())}",
            )
        assertEquals(listOf("none"), requests)
    }
}
