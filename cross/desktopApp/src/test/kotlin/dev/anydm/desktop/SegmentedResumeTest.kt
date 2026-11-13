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
import dev.anydm.engine.http.HttpOpen
import dev.anydm.engine.http.HttpTransport
import dev.anydm.engine.http.ProbeOutcome
import dev.anydm.engine.http.ProbeResult
import dev.anydm.engine.http.Span
import dev.anydm.engine.http.TRAILER_BYTES
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.io.InputStream
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** A server in memory that takes ranges. Bodies stop serving when [limit] bytes have gone out, until the limit is raised. */
private class RangeServer(
    val data: ByteArray,
) : HttpTransport {
    val requests = CopyOnWriteArrayList<String>()

    @Volatile var limit = Long.MAX_VALUE
    private var served = 0L

    @Synchronized
    fun servedBytes() = served

    @Synchronized
    private fun take(n: Int): Int {
        val grant = minOf(n.toLong(), limit - served).coerceAtLeast(0).toInt()
        served += grant
        return grant
    }

    override suspend fun open(
        url: String,
        method: String,
        headers: Map<String, String>,
    ): HttpOpen {
        val range = headers["Range"]
        requests.add(range ?: "none")
        val size = data.size.toLong()
        if (range == null) {
            return HttpOpen(200, mapOf("content-length" to listOf("$size"), "etag" to listOf("\"v1\"")), url, body(0, size - 1))
        }
        val (from, to) = range.removePrefix("bytes=").split('-')
        val a = from.toLong()
        val b = (to.toLongOrNull() ?: (size - 1)).coerceAtMost(size - 1)
        val headersOut =
            mapOf("content-range" to listOf("bytes $a-$b/$size"), "content-length" to listOf("${b - a + 1}"), "etag" to listOf("\"v1\""))
        return HttpOpen(206, headersOut, url, body(a, b))
    }

    private fun body(
        from: Long,
        to: Long,
    ) = object : InputStream() {
        private var pos = from

        @Volatile private var closed = false

        override fun read(): Int = throw UnsupportedOperationException()

        override fun read(
            buf: ByteArray,
            off: Int,
            len: Int,
        ): Int {
            while (true) {
                if (closed) throw IOException("closed")
                if (pos > to) return -1
                val n = take(minOf(len, CHUNK, (to - pos + 1).toInt()))
                if (n > 0) {
                    System.arraycopy(data, pos.toInt(), buf, off, n)
                    pos += n
                    return n
                }
                Thread.sleep(2)
            }
        }

        override fun close() {
            closed = true
        }
    }

    private companion object {
        const val CHUNK = 16 * 1024
    }
}

/** The real engine over an in-memory server and disk, under the real Home screen: pause, restart, resume (AC9) and the gappy file. */
@OptIn(ExperimentalTestApi::class)
class SegmentedResumeTest {
    private val guard = ThreadGuard()
    private val disk = MemoryDiskIo(guard)
    private val folder = "/home/user/Downloads"
    private val part = Path.of("$folder/a.bin.part")
    private val final = Path.of("$folder/a.bin")
    private val total = 8 * 1024 * 1024
    private val data = Random(7).nextBytes(total)
    private val server = RangeServer(data)
    private val prober = FakeProber(guard, fallback = ProbeOutcome.Ok(ProbeResult("a.bin", total.toLong(), true)))
    private val downloads = GuardedDownloadsStore(guard)

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).toList()

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

    private fun last() = downloads.saves.last().downloads

    private fun ranges(spans: List<Span>) = spans.map { RangeDto(it.start, it.end, it.done) }

    @Test
    fun aFourConnectionDownloadPausedAtFortyPercentResumesFromItsStoredRangesToTheRightFile() {
        var stored: List<Span> = emptyList()
        runComposeUiTest {
            val s = Session()
            server.limit = total * 40L / 100
            home(s)
            onNodeWithTag("new-download").performClick()
            onNodeWithTag("url-field").performTextInput("https://x.test/a.bin")
            await("the probe") { runCatching { onNodeWithText("8.0 MB · Resumable · 8 connections").assertIsDisplayed() }.isSuccess }
            repeat(4) { onNodeWithTag("connections-minus").performClick() }
            onNodeWithTag("download").performClick()
            await("the budget is used") { server.servedBytes() >= server.limit }
            onNodeWithTag("pause-1").performClick()
            await("the job to stop") { s.stopped.isNotEmpty() }
            val spans = s.stopped.single().spans
            stored = spans
            assertTrue(spans.size >= 2, "four connections should have made several ranges: $spans")
            await("the stopped ranges saved") { downloads.saves.isNotEmpty() && last().singleOrNull()?.ranges == ranges(spans) }
            val row = last().single()
            assertEquals(DownloadStatus.Paused, row.status)
            assertEquals(4, row.connections)
            // The part file is the data and, after it, the identity that the saved ranges carry.
            val identity = assertNotNull(row.partId)
            assertEquals(total + TRAILER_BYTES, disk.files.getValue(part).size)
            assertEquals(
                identity,
                disk.files
                    .getValue(part)
                    .takeLast(TRAILER_BYTES)
                    .joinToString("") { "%02x".format(it) },
            )
            // What is stored was written: every stored byte of every range is on disk, and is the server's byte.
            val file = disk.files.getValue(part)
            for (r in spans) {
                assertContentEquals(
                    data.copyOfRange(r.start.toInt(), (r.start + r.done).toInt()),
                    file.copyOfRange(
                        r.start.toInt(),
                        (
                            r.start +
                                r.done
                        ).toInt(),
                    ),
                )
            }
            val done = spans.sumOf { it.done }
            assertTrue(done in (total * 25L / 100)..(total * 45L / 100), "paused near 40%: $done of $total")
            assertEquals(done, row.done)
        }
        val done = stored.sumOf { it.done }
        server.requests.clear()
        server.limit = Long.MAX_VALUE
        runComposeUiTest {
            val s = Session()
            home(s)
            await("the restored row") { shown("row-1") }
            onNodeWithTag("speed-1").assertIsDisplayed()
            onNodeWithText("${done * 100 / total}% · ${formatBytes(done)} of ${formatBytes(total.toLong())}").assertIsDisplayed()
            onNodeWithTag("pause-1").performClick()
            await("the download to finish") { disk.files.containsKey(final) }
            assertEquals(sha(data), sha(disk.files.getValue(final)), "the file must be the server's, byte for byte")
            assertEquals(total, disk.files.getValue(final).size, "the identity trailer must not stay on the finished file")
            assertFalse(disk.files.containsKey(part), "no part file may remain")
            val starts = server.requests.mapNotNull { it.removePrefix("bytes=").substringBefore('-').toLongOrNull() }
            for (r in stored.filter {
                !it.finished
            }) {
                assertTrue(r.next in starts, "no request from the stored position ${r.next} of $r: ${server.requests}")
            }
            for (r in stored.filter {
                it.finished
            }) {
                assertTrue(starts.none { it in r.start..r.end }, "a finished range was fetched again: $r, ${server.requests}")
            }
            await("the finished save") { last().single().status == DownloadStatus.Finished }
        }
        assertEquals(0, guard.violations)
    }

    @Test
    fun aRecreatedPartFileOfTheSameSizeNeverResumesFromStaleRangesAndTheFileIsStillRight() {
        // The ranges were saved at 60% next to one file; the file was recreated at the same size (all zeros, another identity).
        disk.files[part] = ByteArray(total + TRAILER_BYTES)
        val half = total / 2L
        val stale = listOf(Span(0, half - 1, half * 6 / 10), Span(half, total - 1L, half * 6 / 10))
        val row =
            DownloadDto(
                "1",
                "https://x.test/a.bin",
                "a.bin",
                folder,
                total.toLong(),
                stale.sumOf { it.done },
                DownloadStatus.Paused,
                etag = "\"v1\"",
                connections = 4,
                ranges = ranges(stale),
                partId = "00112233445566778899aabbccddeeff",
            )
        runComposeUiTest {
            val s = Session(DownloadsFile(downloads = listOf(row)))
            home(s)
            await("the restored row") { shown("row-1") }
            onNodeWithTag("pause-1").performClick()
            await("the download to finish") { disk.files[final]?.let { it.contentEquals(data) } == true }
            assertFalse(disk.files.containsKey(part))
            assertEquals(sha(data), sha(disk.files.getValue(final)))
            val starts = server.requests.mapNotNull { it.removePrefix("bytes=").substringBefore('-').toLongOrNull() }
            assertTrue(stale.none { it.next in starts }, "a stale position was resumed from: ${server.requests}")
        }
    }

    @Test
    fun aFullSizePartFileWithNoStoredRangesIsNeverRenamedAsFinishedAndTheDownloadStartsOver() {
        // Killed before the ranges were saved: the part file is full size, with gaps (here, all zeros).
        disk.files[part] = ByteArray(total)
        val row =
            DownloadDto(
                "1",
                "https://x.test/a.bin",
                "a.bin",
                folder,
                total.toLong(),
                total.toLong(),
                DownloadStatus.Paused,
                etag = "\"v1\"",
                connections = 4,
                ranges = null,
            )
        runComposeUiTest {
            val s = Session(DownloadsFile(downloads = listOf(row)))
            home(s)
            await("the restored row") { shown("row-1") }
            onNodeWithText("0% · 0 B of 8.0 MB").assertIsDisplayed()
            onNodeWithTag("pause-1").performClick()
            await("the download to finish") { disk.files[final]?.let { it.contentEquals(data) } == true }
            assertNotNull(disk.files[final])
            assertFalse(disk.files.containsKey(part))
            assertEquals(sha(data), sha(disk.files.getValue(final)))
            assertTrue(server.requests.isNotEmpty(), "the file came from the server")
        }
    }
}
