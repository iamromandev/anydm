package dev.anydm.engine.http

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.OutputStream
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val MB = 1024 * 1024

class FakeClock(
    start: Long = 0,
) : Clock {
    private val t = AtomicLong(start)

    override fun nowMs() = t.get()

    fun advance(ms: Long) {
        t.addAndGet(ms)
    }
}

class DownloadEngineTest {
    private val server = TestServer()
    private val dir: Path = Files.createTempDirectory("engine")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val events = CopyOnWriteArrayList<Pair<Long, EngineEvent>>()
    private val disk = RealDiskIo()

    @AfterTest
    fun tearDown() {
        scope.cancel()
        server.close()
        dir.toFile().deleteRecursively()
    }

    private fun engine(
        disk: DiskIo = this.disk,
        clock: Clock = Clock { System.currentTimeMillis() },
        stallMs: Long = 30_000,
        reportMs: Long = 500,
        transport: HttpTransport = JdkTransport(),
        engineScope: CoroutineScope = scope,
    ): DownloadEngine {
        val e = DownloadEngine(transport, disk, clock, engineScope, stallMs, reportMs)
        scope.launch(start = CoroutineStart.UNDISPATCHED) { e.events.collect { events.add(System.nanoTime() to it) } }
        return e
    }

    private fun job(
        path: String,
        id: String = "1",
        name: String = "f.bin",
        done: Long = 0,
        etag: String? = null,
        total: Long? = null,
    ) = JobSpec(id, "${server.base}$path", dir.toString(), name, done, etag, null, total)

    private fun eventsOf(id: String = "1") = events.map { it.second }.filter { it.id == id }

    private fun await(
        what: String,
        timeoutMs: Long = 15_000,
        cond: () -> Boolean,
    ) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            check(System.currentTimeMillis() < end) { "timed out waiting for $what; events=${eventsOf()}" }
            Thread.sleep(10)
        }
    }

    private fun awaitTerminal(id: String = "1"): EngineEvent {
        await("Finished/Failed") { eventsOf(id).any { it is EngineEvent.Finished || it is EngineEvent.Failed } }
        return eventsOf(id).last { it is EngineEvent.Finished || it is EngineEvent.Failed }
    }

    private fun partSize(name: String = "f.bin") = disk.size(dir.resolve("$name.part")) ?: 0L

    private fun failed(id: String = "1") = awaitTerminal(id) as EngineEvent.Failed

    @Test
    fun downloadsFileAndRenamesPart() {
        val e = engine()
        e.start(job("/file"))
        val end = awaitTerminal()
        assertEquals(EngineEvent.Finished("1", 3L * MB), end)
        assertContentEquals(TestServer.expected(3 * MB), Files.readAllBytes(dir.resolve("f.bin")))
        assertFalse(Files.exists(dir.resolve("f.bin.part")))
        val started = eventsOf().first() as EngineEvent.Started
        assertEquals(3L * MB, started.total)
        assertEquals("\"v1\"", started.etag)
        assertEquals(0L, started.done)
    }

    @Test
    fun createsMissingFolder() {
        val sub = dir.resolve("a/b")
        engine().start(job("/file?size=1000").copy(folder = sub.toString()))
        assertTrue(awaitTerminal() is EngineEvent.Finished)
        assertTrue(Files.exists(sub.resolve("f.bin")))
    }

    @Test
    fun progressIsBatchedAndRegular() {
        val e = engine()
        e.start(job("/file?size=${3 * MB}&chunkDelayMs=30")) // 48 chunks, about 1.5 s
        awaitTerminal()
        val progress = events.filter { it.second is EngineEvent.Progress }
        val gaps = progress.zipWithNext { a, b -> (b.first - a.first) / 1_000_000 }
        val spanMs = (progress.last().first - progress.first().first) / 1_000_000
        // 500 ms is the target on a quiet machine; a loaded one may deliver late, never more often than reported.
        val slack = if (System.getenv("CI") == null) 200L else 1_000L
        assertTrue(progress.size >= 2, "progress events: ${progress.size}")
        assertTrue(progress.size <= spanMs / 400 + 2, "progress events: ${progress.size} in $spanMs ms (batched, not per chunk)")
        assertTrue(gaps.all { it < 500 + slack }, "gaps $gaps")
        val last = progress.last().second as EngineEvent.Progress
        assertEquals(3L * MB, last.total)
        assertTrue(last.bytesPerSec > 0 && last.done > 0)
    }

    @Test
    fun unknownTotalIsReportedAsNull() {
        val e = engine(reportMs = 0)
        e.start(job("/nolength?size=300000"))
        assertEquals(EngineEvent.Finished("1", 300_000), awaitTerminal())
        assertEquals(null, (eventsOf().first() as EngineEvent.Started).total)
        assertTrue(eventsOf().filterIsInstance<EngineEvent.Progress>().all { it.total == null })
        assertContentEquals(TestServer.expected(300_000), Files.readAllBytes(dir.resolve("f.bin")))
    }

    @Test
    fun stopHaltsTheTransferAndResumeSendsRangeAndIfRange() {
        val url = "/file?size=${4 * MB}&chunkDelayMs=20"
        val e = engine()
        e.start(job(url))
        await("some bytes") { partSize() >= 200_000 }
        e.stop("1")
        Thread.sleep(300)
        val s1 = partSize()
        Thread.sleep(300)
        assertEquals(s1, partSize(), "bytes still arriving after stop")
        assertTrue(eventsOf().none { it is EngineEvent.Finished || it is EngineEvent.Failed }, "stop must emit nothing")
        assertFalse(Files.exists(dir.resolve("f.bin")))

        val etag = (eventsOf().first() as EngineEvent.Started).etag
        e.start(job(url, done = s1, etag = etag))
        assertTrue(awaitTerminal() is EngineEvent.Finished)
        val get = server.requests("/file").last { it.method == "GET" }
        assertEquals("bytes=$s1-", get.headers["range"])
        assertEquals("\"v1\"", get.headers["if-range"])
        assertContentEquals(TestServer.expected(4 * MB), Files.readAllBytes(dir.resolve("f.bin")))
    }

    @Test
    fun serverWithoutRangesRestartsFromZero() {
        val url = "/norange?size=${3 * MB}&chunkDelayMs=20"
        val e = engine()
        e.start(job(url))
        await("some bytes") { partSize() >= 200_000 }
        e.stop("1")
        Thread.sleep(200)
        val done = partSize()
        e.start(job(url, done = done))
        assertTrue(awaitTerminal() is EngineEvent.Finished)
        val restarts = eventsOf().filterIsInstance<EngineEvent.Started>()
        assertEquals(2, restarts.size)
        assertEquals(0L, restarts[1].done)
        assertContentEquals(TestServer.expected(3 * MB), Files.readAllBytes(dir.resolve("f.bin")))
    }

    @Test
    fun changedEtagRestartsFromZero() {
        Files.write(dir.resolve("f.bin.part"), TestServer.expected(40_000))
        val e = engine()
        e.start(job("/changing?size=100000", done = 40_000, etag = "\"old\""))
        assertTrue(awaitTerminal() is EngineEvent.Finished)
        assertContentEquals(TestServer.expected(100_000), Files.readAllBytes(dir.resolve("f.bin")))
    }

    /** The engine's own steady clock: the wall clock can jump, and the measurement must be on the same time base as the bucket. */
    private val steady = Clock { System.nanoTime() / 1_000_000 }

    @Test
    fun speedLimitCapsCombinedThroughputAndRaisingItApplies() {
        val e = engine(clock = steady)
        val cap = 1_000_000.0
        // The AC6 target is 10% on a quiet machine; a CI machine shared with other jobs gets a wider band.
        val tolerance = if (System.getenv("CI") == null) 0.10 else 0.30
        e.setRate(1_000_000)
        e.start(job("/file?size=${20 * MB}", id = "1", name = "a.bin"))
        e.start(job("/file?size=${20 * MB}", id = "2", name = "b.bin"))
        await("both started") { partSize("a.bin") > 0 && partSize("b.bin") > 0 }

        fun size(n: String) = partSize(n).takeIf { it > 0 } ?: (disk.size(dir.resolve(n)) ?: 0L)

        fun total() = size("a.bin") + size("b.bin")

        // A starved test thread or a stalled disk can make one 3 s window read low, so up to three windows may be
        // measured; none may ever read above the cap, because a cap that leaks is a bug, not noise.
        val windows = ArrayList<Double>()
        while (windows.size < 3 && windows.none { it >= cap * (1 - tolerance) }) {
            val t0 = steady.nowMs()
            val b0 = total()
            Thread.sleep(3_000)
            val bytes = total() - b0
            windows += bytes * 1000.0 / (steady.nowMs() - t0)
        }
        assertTrue(windows.all { it <= cap * (1 + tolerance) }, "throughput over 3 s windows $windows B/s")
        assertTrue(windows.any { it >= cap * (1 - tolerance) }, "throughput over 3 s windows $windows B/s")
        val both = listOf(partSize("a.bin"), partSize("b.bin"))
        assertTrue(both.all { it > 0.3 * both.sum() }, "uneven split $both")

        val before = total()
        e.setRate(null)
        Thread.sleep(1_000)
        assertTrue(total() - before > 3_000_000, "raised cap not applied: ${total() - before}")
    }

    private fun terminals(id: String = "1") = eventsOf(id).filter { it is EngineEvent.Finished || it is EngineEvent.Failed }

    private fun awaitTerminals(
        n: Int,
        id: String = "1",
    ) {
        await("$n terminal events") { terminals(id).size >= n }
    }

    @Test
    fun aMalformedLocationFailsOnlyThatJobAndOthersKeepRunning() {
        // A scope without a supervisor: one job's uncaught exception would cancel every job.
        val e = engine(engineScope = CoroutineScope(Job() + Dispatchers.Default))
        e.start(job("/file?size=${3 * MB}&chunkDelayMs=20", id = "1", name = "a.bin"))
        await("first bytes") { partSize("a.bin") > 0 }
        e.start(job("/badlocation", id = "2", name = "b.bin"))
        assertEquals(Reasons.NETWORK, failed("2").reason)
        assertEquals(EngineEvent.Finished("1", 3L * MB), awaitTerminal("1"))
        assertContentEquals(TestServer.expected(3 * MB), Files.readAllBytes(dir.resolve("a.bin")))
    }

    @Test
    fun anUnexpectedExceptionInAJobFailsItAsNetworkAndOthersKeepRunning() {
        val flaky =
            object : DiskIo by disk {
                override fun openPart(
                    p: Path,
                    append: Boolean,
                ): OutputStream = if (p.fileName.toString().startsWith("bad")) error("boom") else disk.openPart(p, append)
            }
        val e = engine(disk = flaky, engineScope = CoroutineScope(Job() + Dispatchers.Default))
        e.start(job("/file?size=${2 * MB}&chunkDelayMs=20", id = "1", name = "a.bin"))
        await("first bytes") { partSize("a.bin") > 0 }
        e.start(job("/file?size=1000", id = "2", name = "bad.bin"))
        assertEquals(Reasons.NETWORK, failed("2").reason)
        assertTrue(awaitTerminal("1") is EngineEvent.Finished)
    }

    @Test
    fun aFolderThatIsNotAValidPathFailsTheJob() {
        engine().start(job("/file?size=100").copy(folder = "bad\u0000folder"))
        assertEquals(Reasons.NOT_WRITABLE, failed().reason)
    }

    @Test
    fun aSecondJobOnAPartFileInUseFailsAndTheFirstIsUntouched() {
        val e = engine()
        e.start(job("/file?size=${3 * MB}&chunkDelayMs=20", id = "1"))
        await("first bytes") { partSize() > 0 }
        e.start(job("/file?size=1000", id = "2"))
        assertEquals(Reasons.NOT_WRITABLE, failed("2").reason)
        assertEquals(EngineEvent.Finished("1", 3L * MB), awaitTerminal("1"))
        assertContentEquals(TestServer.expected(3 * MB), Files.readAllBytes(dir.resolve("f.bin")))
    }

    @Test
    fun twoNamesThatDifferOnlyInCaseNeverShareAPartFile() {
        // On a case-insensitive file system (macOS, Windows) these are one file: the second job must not write it.
        val e = engine()
        e.start(job("/file?size=${3 * MB}&chunkDelayMs=20", id = "1", name = "Foo.bin"))
        await("first bytes") { partSize("Foo.bin") > 0 }
        e.start(job("/file?size=1000", id = "2", name = "foo.bin"))
        assertEquals(Reasons.NOT_WRITABLE, failed("2").reason)
        assertTrue(awaitTerminal("1") is EngineEvent.Finished)
    }

    @Test
    fun aPartFileIsFreeAgainOnceItsJobHasEnded() {
        val e = engine()
        e.start(job("/file?size=1000", id = "1"))
        assertTrue(awaitTerminal("1") is EngineEvent.Finished)
        Files.delete(dir.resolve("f.bin"))
        e.start(job("/file?size=2000", id = "2"))
        assertEquals(EngineEvent.Finished("2", 2000), awaitTerminal("2"))
    }

    @Test
    fun theSizeOfThePartFileOnDiskBeatsAStaleDone() {
        // The spec says 50 000, but a predecessor wrote on after the spec was made: the file has 100 000.
        Files.write(dir.resolve("f.bin.part"), TestServer.expected(100_000))
        engine().start(job("/file?size=300000", done = 50_000, etag = "\"v1\""))
        assertEquals(EngineEvent.Finished("1", 300_000), awaitTerminal())
        assertEquals("bytes=100000-", server.requests("/file").last().headers["range"])
        assertContentEquals(TestServer.expected(300_000), Files.readAllBytes(dir.resolve("f.bin")))
    }

    @Test
    fun aMissingPartFileMeansAFreshStartWhateverTheSpecSays() {
        engine().start(job("/file?size=5000", done = 3_000, etag = "\"v1\""))
        assertEquals(EngineEvent.Finished("1", 5000), awaitTerminal())
        assertEquals(null, server.requests("/file").last().headers["range"])
        assertContentEquals(TestServer.expected(5000), Files.readAllBytes(dir.resolve("f.bin")))
    }

    @Test
    fun stopFollowedAtOnceByStartOfTheSameIdGivesACorrectFile() {
        val url = "/file?size=${4 * MB}&chunkDelayMs=5"
        val e = engine()
        e.start(job(url))
        await("some bytes") { partSize() >= 200_000 }
        val stale = partSize()
        e.stop("1")
        e.start(job(url, done = stale, etag = "\"v1\""))
        assertEquals(EngineEvent.Finished("1", 4L * MB), awaitTerminal())
        assertContentEquals(TestServer.expected(4 * MB), Files.readAllBytes(dir.resolve("f.bin")))
    }

    @Test
    fun aRangeNotSatisfiableClearsThePartSoARetryRestartsFromZero() {
        Files.write(dir.resolve("f.bin.part"), ByteArray(500))
        val e = engine()
        e.start(job("/file?size=100", done = 500, etag = "\"v1\""))
        val f = failed()
        assertEquals(Reasons.CHANGED, f.reason)
        assertEquals(0L, f.done)
        assertEquals(0L, partSize())
        e.start(job("/file?size=100", done = 500, etag = "\"v1\""))
        awaitTerminals(2)
        assertEquals(EngineEvent.Finished("1", 100), terminals().last())
        assertContentEquals(TestServer.expected(100), Files.readAllBytes(dir.resolve("f.bin")))
    }

    @Test
    fun aCompletePartFileIsRenamedWithoutARequest() {
        Files.write(dir.resolve("f.bin.part"), TestServer.expected(100))
        engine().start(job("/file?size=100", done = 100, etag = "\"v1\"", total = 100))
        assertEquals(EngineEvent.Finished("1", 100), awaitTerminal())
        assertContentEquals(TestServer.expected(100), Files.readAllBytes(dir.resolve("f.bin")))
        assertFalse(Files.exists(dir.resolve("f.bin.part")))
        assertEquals(emptyList(), server.requests("/file"))
    }

    @Test
    fun aServerThatNeverAnswersFailsTheJobAsNetworkError() {
        engine(transport = JdkTransport(requestTimeoutMs = 300)).start(job("/hang"))
        assertEquals(Reasons.NETWORK, failed().reason)
    }

    @Test
    fun aFinalNameThatAppearedDuringTheDownloadIsNotOverwritten() {
        val e = engine()
        e.start(job("/file?size=${2 * MB}&chunkDelayMs=20"))
        await("first bytes") { partSize() > 0 }
        Files.writeString(dir.resolve("f.bin"), "the user's file")
        val end = awaitTerminal()
        assertEquals(EngineEvent.Finished("1", 2L * MB, "f (1).bin"), end)
        assertEquals("the user's file", Files.readString(dir.resolve("f.bin")))
        assertContentEquals(TestServer.expected(2 * MB), Files.readAllBytes(dir.resolve("f (1).bin")))
        assertFalse(Files.exists(dir.resolve("f.bin.part")))
    }

    @Test
    fun progressKeepsComingDuringAStallAndTheSpeedDecaysToZero() {
        val clock = FakeClock()
        engine(clock = clock, stallMs = 600_000, reportMs = 500).start(job("/stall?size=$MB&after=50000"))
        await("first bytes") { partSize() >= 50_000 }
        clock.advance(600)
        await("a progress during the stall") { eventsOf().any { it is EngineEvent.Progress } }
        val first = eventsOf().filterIsInstance<EngineEvent.Progress>().first()
        assertEquals(50_000L, first.done)
        assertTrue(first.bytesPerSec > 0, "speed ${first.bytesPerSec}")
        clock.advance(4_000)
        await("speed back to 0") { eventsOf().filterIsInstance<EngineEvent.Progress>().last().bytesPerSec == 0L }
        assertTrue(eventsOf().none { it is EngineEvent.Failed })
    }

    @Test
    fun http404FailsWithReason() {
        engine().start(job("/404"))
        assertEquals(EngineEvent.Failed("1", "HTTP 404", 0), awaitTerminal())
    }

    @Test
    fun rangeNotSatisfiableIsFileChanged() {
        Files.write(dir.resolve("f.bin.part"), ByteArray(500))
        engine().start(job("/file?size=100", done = 500, etag = "\"v1\""))
        assertEquals(Reasons.CHANGED, failed().reason)
    }

    @Test
    fun droppedConnectionFailsAsNetworkAndKeepsPart() {
        engine().start(job("/drop?size=$MB&after=100000"))
        val f = failed()
        assertEquals(Reasons.NETWORK, f.reason)
        assertTrue(f.done in 1..100_000, "done ${f.done}")
        assertEquals(f.done, partSize())
        assertFalse(Files.exists(dir.resolve("f.bin")))
    }

    @Test
    fun stallFailsAfterStallMsOnTheFakeClock() {
        val clock = FakeClock()
        engine(clock = clock, stallMs = 200).start(job("/stall?size=$MB&after=50000"))
        await("first bytes") { partSize() >= 50_000 }
        Thread.sleep(100)
        assertTrue(eventsOf().none { it is EngineEvent.Failed }, "failed before the clock moved")
        clock.advance(300)
        val f = failed()
        assertEquals(Reasons.NETWORK, f.reason)
        assertEquals(50_000L, f.done)
    }

    @Test
    fun diskFullIsReported() {
        val full =
            object : DiskIo by disk {
                override fun openPart(
                    p: Path,
                    append: Boolean,
                ): OutputStream =
                    object : OutputStream() {
                        override fun write(b: Int) = throw IOException("No space left on device")
                    }
            }
        engine(disk = full).start(job("/file?size=100000"))
        assertEquals(Reasons.DISK_FULL, failed().reason)
    }

    @Test
    fun unwritableFolderIsReported() {
        val denied =
            object : DiskIo by disk {
                override fun mkdirs(p: Path) = throw AccessDeniedException(p.toString())
            }
        engine(disk = denied).start(job("/file?size=1000"))
        assertEquals(Reasons.NOT_WRITABLE, failed().reason)
    }

    @Test
    fun stopMidStreamLeavesNoFinalFile() {
        val e = engine()
        e.start(job("/file?size=${4 * MB}&chunkDelayMs=20"))
        await("some bytes") { partSize() >= 100_000 }
        e.stop("1")
        Thread.sleep(300)
        assertFalse(Files.exists(dir.resolve("f.bin")))
        assertTrue(Files.exists(dir.resolve("f.bin.part")))
    }

    @Test
    fun blankNameIsTakenFromTheResponseAndMadeUnique() {
        Files.writeString(dir.resolve("a.zip"), "existing")
        val e = engine()
        e.start(job("/cd?size=1000", name = ""))
        assertTrue(awaitTerminal() is EngineEvent.Finished)
        assertEquals("a (1).zip", (eventsOf().first() as EngineEvent.Started).name)
        assertTrue(Files.exists(dir.resolve("a (1).zip")))
        assertEquals("existing", Files.readString(dir.resolve("a.zip")))
    }

    @Test
    fun nameThatEscapesTheFolderIsRejected() {
        val inner = Files.createDirectories(dir.resolve("inner"))
        engine().start(job("/file?size=100", name = "../evil.bin").copy(folder = inner.toString()))
        assertEquals(Reasons.NOT_WRITABLE, failed().reason)
        assertFalse(Files.exists(dir.resolve("evil.bin")))
        assertFalse(Files.exists(dir.resolve("evil.bin.part")))
    }

    @Test
    fun throughputOnLocalhost() {
        val size = 200 * MB
        val e = engine()
        val t0 = System.nanoTime()
        e.start(job("/file?size=$size"))
        awaitTerminal()
        val mbPerSec = size / MB / ((System.nanoTime() - t0) / 1e9)
        println("throughput: $mbPerSec MB/s")
        assertNotNull(eventsOf().firstOrNull())
        if (System.getenv("CI") == null) assertTrue(mbPerSec >= 100, "throughput $mbPerSec MB/s")
    }
}
