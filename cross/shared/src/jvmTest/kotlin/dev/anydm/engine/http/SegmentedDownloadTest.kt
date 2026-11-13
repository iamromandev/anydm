package dev.anydm.engine.http

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val MB = 1024 * 1024

class SegmentedDownloadTest {
    private val server = TestServer()
    private val dir: Path = Files.createTempDirectory("segmented")
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
        clock: Clock = Clock { System.nanoTime() / 1_000_000 },
        stallMs: Long = 30_000,
        reportMs: Long = 500,
        maxTotalConnections: Int = MAX_TOTAL_CONNECTIONS,
        transport: HttpTransport = JdkTransport(),
        minSplit: Long = MIN_SPLIT,
        retry: RetryPolicy = RetryPolicy(backoffMs = 5, yieldRetryMs = 50),
    ): DownloadEngine {
        val e = DownloadEngine(transport, disk, clock, scope, stallMs, reportMs, maxTotalConnections, minSplit, retry)
        scope.launch(start = CoroutineStart.UNDISPATCHED) { e.events.collect { events.add(System.nanoTime() to it) } }
        return e
    }

    /** Counts GET responses the engine holds open: up on `open()`, down on the first close. Unlike the server's count it can't lag. */
    private class CountingTransport(
        private val inner: HttpTransport = JdkTransport(),
    ) : HttpTransport {
        private val now = AtomicInteger()
        private val top = AtomicInteger()
        val peak: Int get() = top.get()

        override suspend fun open(
            url: String,
            method: String,
            headers: Map<String, String>,
        ): HttpOpen {
            val o = inner.open(url, method, headers)
            if (method != "GET") return o
            top.accumulateAndGet(now.incrementAndGet()) { a, b -> maxOf(a, b) }
            val closed =
                java.util.concurrent.atomic
                    .AtomicBoolean()
            val body =
                object : InputStream() {
                    override fun read() = o.body.read()

                    override fun read(
                        b: ByteArray,
                        off: Int,
                        len: Int,
                    ) = o.body.read(b, off, len)

                    override fun close() {
                        if (closed.compareAndSet(false, true)) now.decrementAndGet()
                        o.close()
                    }
                }
            return HttpOpen(o.status, o.headers, o.finalUrl, body)
        }
    }

    private fun job(
        path: String,
        connections: Int = 4,
        id: String = "1",
        name: String = "f.bin",
        total: Long? = null,
        spans: List<Span>? = null,
        etag: String? = null,
        partId: String? = null,
    ) = JobSpec(id, "${server.base}$path", dir.toString(), name, doneOf(spans.orEmpty()), etag, null, total, connections, spans, partId)

    private fun eventsOf(id: String = "1") = events.map { it.second }.filter { it.id == id }

    private fun await(
        what: String,
        timeoutMs: Long = 30_000,
        id: String = "1",
        cond: () -> Boolean,
    ) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            check(System.currentTimeMillis() < end) { "timed out waiting for $what; events=${eventsOf(id).takeLast(5)}" }
            Thread.sleep(10)
        }
    }

    private fun awaitTerminal(id: String = "1"): EngineEvent {
        await("Finished/Failed") { eventsOf(id).any { it is EngineEvent.Finished || it is EngineEvent.Failed } }
        return eventsOf(id).last { it is EngineEvent.Finished || it is EngineEvent.Failed }
    }

    /** The collector runs on another thread: a job that `join` says has ended may have its `Stopped` still on its way. */
    private fun awaitStopped(id: String = "1"): EngineEvent.Stopped {
        await("Stopped", id = id) { eventsOf(id).any { it is EngineEvent.Stopped } }
        return eventsOf(id).filterIsInstance<EngineEvent.Stopped>().first()
    }

    private fun failed(id: String = "1") = awaitTerminal(id) as EngineEvent.Failed

    private fun ranged(path: String) = server.requests(path).filter { it.method == "GET" && it.headers["range"] != null }

    private fun gets(path: String) = server.requests(path).filter { it.method == "GET" }

    private fun lengthOf(range: String): Long {
        val (a, b) = range.removePrefix("bytes=").split('-')
        return b.toLong() - a.toLong() + 1
    }

    private fun assertFileIs(
        size: Int,
        name: String = "f.bin",
    ) = assertContentEquals(TestServer.expected(size), Files.readAllBytes(dir.resolve(name)))

    // AC1
    @Test
    fun thirtyTwoMibOverFourConnectionsSendsFourConcurrentRangedGetsWithIfRange() {
        val size = 32 * MB
        engine().start(job("/file?size=$size&cap=8000000", total = size.toLong()))
        assertEquals(EngineEvent.Finished("1", size.toLong()), awaitTerminal())
        assertFileIs(size)
        assertFalse(Files.exists(dir.resolve("f.bin.part")))
        val gets = gets("/file")
        assertEquals(4, gets.size) // the first response serves the first range: no second request for it
        assertTrue(gets.all { it.headers["range"] != null })
        assertEquals(listOf("bytes=0-${8 * MB - 1}"), gets.map { it.headers["range"] }.filter { it!!.startsWith("bytes=0-") })
        assertNull(gets.first().headers["if-range"], "no validator is known for the first request")
        assertTrue(gets.drop(1).all { it.headers["if-range"] == "\"v1\"" }, "if-range on $gets")
        assertEquals(4, server.maxOpen)
        val started = eventsOf().filterIsInstance<EngineEvent.Started>().single()
        assertEquals(4, started.spans?.size)
        assertEquals(size.toLong(), started.total)
    }

    @Test
    fun withTheSizeNotKnownBeforehandTheFirstRequestIsOpenEndedAndTheSplitComesFromTheContentRange() {
        val size = 16 * MB
        engine().start(job("/file?size=$size&cap=8000000"))
        assertEquals(EngineEvent.Finished("1", size.toLong()), awaitTerminal())
        assertFileIs(size)
        val gets = gets("/file")
        assertEquals("bytes=0-", gets.first().headers["range"])
        assertEquals(4, gets.size)
        assertEquals(
            4,
            eventsOf()
                .filterIsInstance<EngineEvent.Started>()
                .single()
                .spans
                ?.size,
        )
    }

    // AC2
    @Test
    fun aServerWithoutRangeSupportIsDownloadedOverOneConnectionFromTheSameResponse() {
        engine().start(job("/norange?size=${3 * MB}"))
        assertEquals(EngineEvent.Finished("1", 3L * MB), awaitTerminal())
        assertFileIs(3 * MB)
        assertEquals(1, gets("/norange").size)
        assertNull(eventsOf().filterIsInstance<EngineEvent.Started>().single().spans)
    }

    @Test
    fun anUnknownSizeIsDownloadedOverOneConnection() {
        engine().start(job("/nolength?size=300000"))
        assertEquals(EngineEvent.Finished("1", 300_000), awaitTerminal())
        assertFileIs(300_000)
        assertEquals(1, gets("/nolength").size)
        assertNull(eventsOf().filterIsInstance<EngineEvent.Started>().single().spans)
    }

    @Test
    fun aFileUnderOneMibIsDownloadedOverOneConnection() {
        engine().start(job("/file?size=${500 * 1024}", total = 500L * 1024))
        assertEquals(EngineEvent.Finished("1", 500L * 1024), awaitTerminal())
        assertFileIs(500 * 1024)
        assertEquals(1, gets("/file").size)
        assertNull(gets("/file").single().headers["range"], "a small known file is fetched as today")
    }

    @Test
    fun aSmallFileOfUnknownSizeOnARangeServerIsStillOneConnection() {
        engine().start(job("/file?size=${500 * 1024}"))
        assertEquals(EngineEvent.Finished("1", 500L * 1024), awaitTerminal())
        assertFileIs(500 * 1024)
        assertEquals(1, gets("/file").size)
        assertNull(eventsOf().filterIsInstance<EngineEvent.Started>().single().spans)
    }

    @Test
    fun oneConnectionIsExactlyToday() {
        engine().start(job("/file?size=${4 * MB}", connections = 1, total = 4L * MB))
        assertEquals(EngineEvent.Finished("1", 4L * MB), awaitTerminal())
        assertEquals(1, gets("/file").size)
        assertNull(gets("/file").single().headers["range"])
    }

    // Finding 1: a pre-sized `.part` of a row with no ranges is never a finished file
    @Test
    fun aFullSizeZeroFilledPartFileOfARowWithoutRangesIsNotCompletedFromItself() {
        val size = 8 * MB
        Files.write(dir.resolve("f.bin.part"), ByteArray(size))
        engine().start(job("/file?size=$size&cap=16000000", total = size.toLong()))
        assertEquals(EngineEvent.Finished("1", size.toLong()), awaitTerminal())
        assertFileIs(size)
        assertTrue(gets("/file").isNotEmpty(), "the bytes came from the server")
    }

    /** Lets the first GET's answer through only when [gate] opens, and then without regard to a cancel: a probe that is slow to arrive. */
    private class GatedProbe(
        private val inner: HttpTransport = JdkTransport(),
    ) : HttpTransport {
        val gate = CountDownLatch(1)
        val answered = CountDownLatch(1)
        private val first =
            java.util.concurrent.atomic
                .AtomicBoolean(true)

        override suspend fun open(
            url: String,
            method: String,
            headers: Map<String, String>,
        ): HttpOpen {
            val o = inner.open(url, method, headers)
            if (method == "GET" && first.compareAndSet(true, false)) {
                answered.countDown()
                gate.await()
            }
            return o
        }
    }

    @Test
    fun aPauseAndResumeAroundTheProbeNeverRenamesThePreSizedFileOfThePredecessor() {
        val size = 8 * MB
        val gated = GatedProbe()
        val e = engine(transport = gated)
        val spec = job("/file?size=$size&cap=16000000", total = size.toLong()) // no ranges yet: the user paused before Started
        e.start(spec)
        assertTrue(gated.answered.await(10, TimeUnit.SECONDS), "the probe never reached the server")
        e.stop("1")
        e.start(spec) // joins the predecessor, which then goes on to size the file before it notices the stop
        Thread.sleep(100)
        gated.gate.countDown()
        assertEquals(EngineEvent.Finished("1", size.toLong()), awaitTerminal())
        assertFileIs(size)
    }

    @Test
    fun aRowOnSeveralConnectionsWhoseRunWasOneStreamKeepsItsPartialFileOnPauseAndResume() {
        val size = 900 * 1024 // under 1 MiB: never split, so spans stay null
        val e = engine(reportMs = 50)
        val spec = job("/file?size=$size&cap=300000", total = size.toLong())
        e.start(spec)
        await("some bytes") { progressDone() > 100_000 }
        e.stop("1")
        runBlocking { e.join("1") }
        val kept = Files.size(dir.resolve("f.bin.part"))
        assertTrue(kept in 1 until size, "a partial file: $kept")
        e.start(spec.copy(done = kept))
        assertEquals(EngineEvent.Finished("1", size.toLong()), awaitTerminal())
        assertFileIs(size)
        assertEquals("bytes=$kept-", gets("/file").last().headers["range"], "the second run continued where the first stopped")
    }

    @Test
    fun aRowOnSeveralConnectionsWithAPartialFileAndNoMemoryOfItStartsOver() {
        // After an app restart nothing says whether the file is one stream's or a segmented run's with gaps.
        val size = 900 * 1024
        Files.write(dir.resolve("f.bin.part"), TestServer.expected(size).copyOf(300_000))
        engine().start(job("/file?size=$size", total = size.toLong()).copy(done = 300_000))
        assertEquals(EngineEvent.Finished("1", size.toLong()), awaitTerminal())
        assertFileIs(size)
        assertNull(gets("/file").first().headers["range"], "a start from zero asks for no range")
    }

    // AC3
    @Test
    fun fasterConnectionsSplitTheRemainderOfASlowRange() {
        val size = 16 * MB
        server.slowRange(start = 4L * MB, delayMs = 40)
        val recording = RecordingDisk(disk)
        val counting = CountingTransport()
        engine(disk = recording, transport = counting).start(job("/file?size=$size&cap=16000000", total = size.toLong()))
        assertEquals(EngineEvent.Finished("1", size.toLong()), awaitTerminal(), "events ${eventsOf().takeLast(3)}")
        assertFileIs(size)
        val requests = ranged("/file")
        assertTrue(requests.size > 4, "nobody split the slow range: ${requests.map { it.headers["range"] }}")
        // the four first ranges are the equal split; everything after is a split and no split is under 1 MiB
        val later = requests.map { lengthOf(it.headers["range"]!!) }.filter { it != 4L * MB }
        assertTrue(later.all { it >= MIN_SPLIT }, "splits $later")
        val open = eventsOf().filterIsInstance<EngineEvent.Progress>().maxOf { it.connections }
        assertTrue(open <= 4, "connections reported open: $open")
        assertTrue(counting.peak <= 4, "engine held ${counting.peak} responses at once")
        recording.assertDisjointAndComplete(size.toLong()) // a range that was cut short never wrote on into the thief's bytes
    }

    /** A range server whose answers a test steers: the first range waits for [releaseFirst], the second stops short of its end. */
    private class SteeredTransport(
        private val total: Long,
        private val holdBefore: Long,
    ) : HttpTransport {
        val releaseFirst = CountDownLatch(1)
        val victimBlocked = CountDownLatch(1)
        val releaseVictim = CountDownLatch(1)
        val starts = CopyOnWriteArrayList<Long>()

        override suspend fun open(
            url: String,
            method: String,
            headers: Map<String, String>,
        ): HttpOpen {
            val (a, b) =
                headers
                    .getValue("Range")
                    .removePrefix("bytes=")
                    .split('-')
                    .map { it.toLong() }
            starts.add(a)
            val h =
                mapOf(
                    "content-range" to listOf("bytes $a-$b/$total"),
                    "etag" to listOf("\"v1\""),
                    "accept-ranges" to listOf("bytes"),
                )
            return HttpOpen(206, h, url, Body(a, b))
        }

        private inner class Body(
            private val from: Long,
            private val to: Long,
        ) : InputStream() {
            private var pos = from

            @Volatile private var closed = false

            override fun read(): Int = throw UnsupportedOperationException()

            override fun read(
                b: ByteArray,
                off: Int,
                len: Int,
            ): Int {
                if (from == 0L && pos == 0L) releaseFirst.await()
                val holdAt = to + 1 - 100 * 1024
                if (from == holdBefore && pos == holdAt) {
                    victimBlocked.countDown()
                    releaseVictim.await()
                }
                if (closed) throw IOException("closed")
                if (pos > to) return -1
                val stop = if (from == holdBefore && pos < holdAt) holdAt else to + 1
                val n = minOf(len.toLong(), stop - pos).toInt()
                for (i in 0 until n) b[off + i] = TestServer.byteAt(pos + i)
                pos += n
                return n
            }

            override fun close() {
                closed = true
            }
        }
    }

    @Test
    fun aRangeCutShortWhileAChunkIsInFlightWritesOnlyUpToItsNewEnd() {
        // The slow connection is 100 KiB from its end, blocked in a read of 64 KiB, when a free connection takes the second half
        // of what is left: the bytes that read returns must stop at the new end, or they land on the thief's.
        val total = 2L * MB
        val transport = SteeredTransport(total, holdBefore = MB.toLong())
        val recording = RecordingDisk(disk)
        engine(disk = recording, transport = transport, minSplit = 1024).start(job("/steered", connections = 2, total = total))
        // let the slow range run up to its last 100 KiB while the first range waits
        assertTrue(transport.victimBlocked.await(10, TimeUnit.SECONDS), "the slow range never got to its last stretch")
        transport.releaseFirst.countDown()
        // the free connection keeps taking the second half of what is left until under 2 KiB (twice the minimum split) remain
        await("the thief to write all but the last 2 KiB") { recording.writes.sumOf { it.last - it.first + 1 } >= total - 2048 }
        transport.releaseVictim.countDown()
        assertEquals(EngineEvent.Finished("1", total), awaitTerminal())
        assertFileIs(total.toInt())
        recording.assertDisjointAndComplete(total)
    }

    // AC6
    @Test
    fun doneIsTheSumOverTheRangesAndProgressKeepsComing() {
        val size = 8 * MB
        engine().start(job("/file?size=$size&cap=1000000", total = size.toLong())) // 4 x 1 MB/s: about 2 s
        awaitTerminal()
        val progress = events.filter { it.second is EngineEvent.Progress }
        assertTrue(progress.size >= 2, "progress events: ${progress.size}")
        for ((_, p) in progress) {
            p as EngineEvent.Progress
            assertEquals(doneOf(assertNotNull(p.spans)), p.done)
            assertEquals(size.toLong(), p.total)
            assertTrue(p.connections in 1..4)
        }
        val last = progress.last().second as EngineEvent.Progress
        assertTrue(last.bytesPerSec > 0)
        assertTrue(progress.map { (it.second as EngineEvent.Progress).done }.zipWithNext().all { (a, b) -> b >= a }, "done went backwards")
    }

    // AC6, the cadence: on the engine's own clock, so that how busy the test machine is cannot change the outcome
    @Test
    fun progressIsReportedEveryReportIntervalOfEngineTimeEvenWhenNoByteArrives() {
        val clock = FakeClock()
        val go = CountDownLatch(1)
        val parked = CountDownLatch(2)
        val transport = ScriptedTransport(2L * MB) { from, to -> ScriptedBody(from, to, parkAt = from, parked = parked, go = go) }
        val e = engine(clock = clock, transport = transport, reportMs = 500, stallMs = 600_000)
        try {
            e.start(twoRangeJobOfA())
            assertTrue(parked.await(10, TimeUnit.SECONDS), "both connections should be waiting for their first byte")
            repeat(4) { round ->
                val before = eventsOf("a").count { it is EngineEvent.Progress }
                clock.advance(500) // one report interval of engine time: a report is due, whatever the machine is doing
                await("a progress report for interval ${round + 1}", timeoutMs = 10_000, id = "a") {
                    eventsOf("a").count { it is EngineEvent.Progress } > before
                }
            }
        } finally {
            go.countDown()
            e.stop("a")
        }
    }

    // AC11
    @Test
    fun aDiskThatRefusesTheReservationFailsAsDiskFullWithoutFetchingTheBody() {
        val full =
            object : DiskIo by disk {
                override fun openSpans(
                    p: Path,
                    size: Long?,
                ): PartFile = throw IOException("No space left on device")
            }
        engine(disk = full).start(job("/file?size=${8 * MB}", total = 8L * MB))
        assertEquals(Reasons.DISK_FULL, failed().reason)
        assertEquals(1, gets("/file").size, "only the first response was requested: ${gets("/file").map { it.headers["range"] }}")
        assertTrue(eventsOf().none { it is EngineEvent.Started })
    }

    @Test
    fun aResumeWhoseReservationIsRefusedSendsNoRequestAtAll() {
        Files.write(dir.resolve("f.bin.part"), withTrailer(ByteArray(8 * MB), tokenA))
        val full =
            object : DiskIo by disk {
                override fun openSpans(
                    p: Path,
                    size: Long?,
                ): PartFile = throw IOException("No space left on device")
            }
        val spans = listOf(Span(0, 4L * MB - 1, 100), Span(4L * MB, 8L * MB - 1, 0))
        engine(disk = full).start(job("/file?size=${8 * MB}", spans = spans, partId = tokenA))
        assertEquals(Reasons.DISK_FULL, failed().reason)
        assertEquals(emptyList(), server.requests("/file"))
    }

    // NFR4
    @Test
    fun noMoreConnectionsThanTheAppWideCapAreOpen() {
        val size = 16 * MB
        val counting = CountingTransport()
        engine(maxTotalConnections = 3, transport = counting).start(job("/file?size=$size&cap=8000000", total = size.toLong()))
        assertEquals(EngineEvent.Finished("1", size.toLong()), awaitTerminal())
        assertFileIs(size)
        assertTrue(counting.peak <= 3, "engine held ${counting.peak} responses at once")
        assertTrue(eventsOf().filterIsInstance<EngineEvent.Progress>().all { it.connections <= 3 })
    }

    // Findings 3 and 4: ranges are only trusted next to the file they were written for
    @Test
    fun rangesThatStopShortOfTheKnownTotalAreNotResumedFromAndTheDownloadStartsOver() {
        val size = 16 * MB
        val half = 8L * MB
        Files.write(dir.resolve("f.bin.part"), TestServer.expected(half.toInt())) // all of the first half, which is all the ranges know of
        val spans = listOf(Span(0, half - 1, half))
        engine().start(job("/file?size=$size&cap=16000000", total = size.toLong(), spans = spans, etag = "\"v1\""))
        assertEquals(EngineEvent.Finished("1", size.toLong()), awaitTerminal())
        assertFileIs(size)
    }

    @Test
    fun anEmptyListOfRangesOverAPreSizedFileIsNotTakenForAFinishedFile() {
        val size = 8 * MB
        Files.write(dir.resolve("f.bin.part"), ByteArray(size))
        engine().start(job("/file?size=$size&cap=16000000", total = size.toLong(), spans = emptyList()))
        assertEquals(EngineEvent.Finished("1", size.toLong()), awaitTerminal())
        assertFileIs(size)
    }

    @Test
    fun rangesForABiggerFileThanThePartFileOnDiskAreNotTrustedAndTheDownloadStartsOver() {
        val size = 16 * MB
        // the part file was recreated (or cut): 8 MiB of zeros, while the ranges say the first 8 MiB are done
        Files.write(dir.resolve("f.bin.part"), ByteArray(8 * MB))
        val spans = listOf(Span(0, 8L * MB - 1, 8L * MB), Span(8L * MB, size - 1L, 0))
        engine().start(job("/file?size=$size&cap=16000000", total = size.toLong(), spans = spans, etag = "\"v1\""))
        assertEquals(EngineEvent.Finished("1", size.toLong()), awaitTerminal())
        assertFileIs(size)
        assertEquals(0L, eventsOf().filterIsInstance<EngineEvent.Started>().first().done)
    }

    // Finding 5: a download that holds every permit gives some back when another one has none for its first connection
    @Test
    fun downloadsThatStartAfterOneHoldingEveryPermitStillGetTheirFirstConnection() {
        val size = 32 * MB
        val e = engine(maxTotalConnections = 3, reportMs = 50)
        e.start(job("/file/a?size=$size&cap=1000000", id = "a", name = "a.bin", total = size.toLong()))
        // on its own it takes all three permits, and would need about 11 s with them
        await(
            "a to hold all three permits",
            id = "a",
        ) { eventsOf("a").filterIsInstance<EngineEvent.Progress>().any { it.connections == 3 } }
        listOf("b", "c").forEach { e.start(job("/file/$it?size=$size&cap=1000000", id = it, name = "$it.bin", total = size.toLong())) }
        for (id in listOf("b", "c", "a")) await("download $id to flow", timeoutMs = 5_000, id = id) { progressDone(id) > 100_000 }
        // Each holds a connection at the same time: all three move in one and the same window (a download that held none would not).
        val before = listOf("a", "b", "c").associateWith { progressDone(it) }
        Thread.sleep(600)
        for ((id, was) in before) assertTrue(progressDone(id) > was + 100_000, "$id did not move while the others did")
        listOf("a", "b", "c").forEach { e.stop(it) }
    }

    private val tokenA = "00112233445566778899aabbccddeeff"
    private val tokenB = "ffeeddccbbaa99887766554433221100"

    /** What a segmented run leaves on disk: [bytes] (the file's data) followed by the identity [token]. */
    private fun withTrailer(
        bytes: ByteArray,
        token: String,
    ) = bytes + trailerOf(token)!!

    /** The file of a two-range download of 8 MiB: the first range 1 MiB done, the second 3 MiB done. */
    private fun sixtyPercentFile(): ByteArray {
        val data = TestServer.expected(8 * MB)
        val bytes = ByteArray(8 * MB)
        System.arraycopy(data, 0, bytes, 0, MB)
        System.arraycopy(data, 4 * MB, bytes, 4 * MB, 3 * MB)
        return bytes
    }

    private val storedSpans = listOf(Span(0, 4L * MB - 1, MB.toLong()), Span(4L * MB, 8L * MB - 1, 3L * MB))

    @Test
    fun aResumeContinuesEachRangeFromItsStoredPosition() {
        val size = 8 * MB
        // the file is full size from the start, and ends in the identity the ranges were saved with
        Files.write(dir.resolve("f.bin.part"), withTrailer(sixtyPercentFile(), tokenA))
        engine().start(job("/file?size=$size", connections = 2, spans = storedSpans, etag = "\"v1\"", partId = tokenA))
        assertEquals(EngineEvent.Finished("1", size.toLong()), awaitTerminal())
        assertFileIs(size)
        val started = eventsOf().filterIsInstance<EngineEvent.Started>().single()
        assertEquals(4L * MB, started.done)
        assertEquals(tokenA, started.partId)
        val ranges = ranged("/file").map { it.headers["range"] }
        assertTrue("bytes=$MB-${4 * MB - 1}" in ranges, "$ranges")
        assertTrue(ranges.any { it!!.startsWith("bytes=${7 * MB}-") }, "$ranges")
        assertTrue(ranged("/file").all { it.headers["if-range"] == "\"v1\"" })
    }

    // M1: ranges are only trusted next to the file they were written for
    @Test
    fun aSameSizePartWithAnotherIdentityIsNotResumedFromAndTheDownloadStartsOver() {
        val size = 8 * MB
        // the part file was recreated (the server's file changed, or the user deleted it): the same length, all zeros, another identity
        Files.write(dir.resolve("f.bin.part"), withTrailer(ByteArray(size), tokenB))
        engine().start(job("/file?size=$size&cap=16000000", connections = 2, spans = storedSpans, etag = "\"v1\"", partId = tokenA))
        assertEquals(EngineEvent.Finished("1", size.toLong()), awaitTerminal())
        assertFileIs(size)
        val started = eventsOf().filterIsInstance<EngineEvent.Started>().first()
        assertEquals(0L, started.done)
        assertTrue(started.partId != tokenA && started.partId != tokenB, "a new file has a new identity: ${started.partId}")
        assertTrue(ranged("/file").none { it.headers["range"]!!.startsWith("bytes=$MB-") }, "the stored position was used")
    }

    @Test
    fun aPartWithAZeroedTrailerOrNoTrailerIsNotResumedFrom() {
        val size = 8 * MB
        val zeroed = ByteArray(size + TRAILER_BYTES)
        System.arraycopy(sixtyPercentFile(), 0, zeroed, 0, size)
        for (bytes in listOf(zeroed, sixtyPercentFile())) {
            events.clear()
            Files.write(dir.resolve("f.bin.part"), bytes)
            val e = engine()
            e.start(job("/file?size=$size&cap=16000000", connections = 2, spans = storedSpans, etag = "\"v1\"", partId = tokenA))
            assertEquals(EngineEvent.Finished("1", size.toLong()), awaitTerminal())
            assertFileIs(size)
            assertEquals(0L, eventsOf().filterIsInstance<EngineEvent.Started>().first().done)
            Files.delete(dir.resolve("f.bin"))
        }
    }

    @Test
    fun rangesWithoutAnIdentityAreNotTrustedEvenNextToTheFileTheyWereMadeFor() {
        val size = 8 * MB
        Files.write(dir.resolve("f.bin.part"), withTrailer(sixtyPercentFile(), tokenA))
        engine().start(job("/file?size=$size&cap=16000000", connections = 2, spans = storedSpans, etag = "\"v1\""))
        assertEquals(EngineEvent.Finished("1", size.toLong()), awaitTerminal())
        assertFileIs(size)
        assertEquals(0L, eventsOf().filterIsInstance<EngineEvent.Started>().first().done)
    }

    @Test
    fun theIdentityIsOnDiskBeforeStartedAndTheFileEndsInItWhileTheDownloadRuns() {
        val size = 32 * MB
        val e = engine(reportMs = 50)
        e.start(job("/file?size=$size&cap=1000000", total = size.toLong()))
        await("a progress with bytes") { progressDone() > 200_000 }
        val started = eventsOf().filterIsInstance<EngineEvent.Started>().single()
        val id = assertNotNull(started.partId)
        assertEquals(TRAILER_BYTES * 2, id.length)
        assertEquals(size.toLong() + TRAILER_BYTES, Files.size(dir.resolve("f.bin.part")))
        assertEquals(id, partIdOf(assertNotNull(disk.readTail(dir.resolve("f.bin.part"), TRAILER_BYTES))))
        assertTrue(eventsOf().filterIsInstance<EngineEvent.Progress>().all { it.partId == id })
        e.stop("1")
        runBlocking { e.join("1") }
        val stopped = awaitStopped()
        assertEquals(id, stopped.partId)
        assertEquals(size.toLong() + TRAILER_BYTES, Files.size(dir.resolve("f.bin.part")))
    }

    @Test
    fun aFinishedFileHasExactlyTheTotalBytesAndNoTrailer() {
        val size = 8 * MB
        engine().start(job("/file?size=$size&cap=16000000", total = size.toLong()))
        assertEquals(EngineEvent.Finished("1", size.toLong()), awaitTerminal())
        assertEquals(size.toLong(), Files.size(dir.resolve("f.bin")))
        assertFileIs(size)
        assertFalse(Files.exists(dir.resolve("f.bin.part")))
    }

    @Test
    fun aStopAndAResumeWithTheIdentityOfTheRunContinuesWithoutRefetchingFinishedRanges() {
        val size = 32 * MB
        val e = engine(reportMs = 50)
        e.start(job("/file?size=$size&cap=4000000", total = size.toLong()))
        await("bytes") { progressDone() > 4_000_000 }
        e.stop("1")
        runBlocking { e.join("1") }
        val stopped = awaitStopped()
        val second = engine() // no memory of the run: only the spans and the identity, as after a restart
        events.clear()
        second.start(
            job("/file?size=$size&cap=16000000", total = size.toLong(), spans = stopped.spans, etag = "\"v1\"", partId = stopped.partId),
        )
        assertEquals(EngineEvent.Finished("1", size.toLong()), awaitTerminal())
        assertFileIs(size)
        assertEquals(stopped.done, eventsOf().filterIsInstance<EngineEvent.Started>().first().done)
    }

    private fun part() = Files.readAllBytes(dir.resolve("f.bin.part"))

    /** Every byte of every range up to its `done` is the server's, and nothing is on disk past it. */
    private fun assertPartMatches(spans: List<Span>) {
        val bytes = part()
        val data = TestServer.expected(bytes.size)
        for (s in spans) {
            for (i in s.start until s.next) assertEquals(data[i.toInt()], bytes[i.toInt()], "byte $i of $s differs")
            for (i in s.next..s.end) assertEquals(0.toByte(), bytes[i.toInt()], "byte $i of $s is past what was reported")
        }
    }

    // AC9 (engine part): what is reported is never more than what is on disk, whichever side of the write the fault hits
    @Test
    fun aWriteThatFailsBeforeItsBytesAreOnDiskIsNeverReportedAsDone() {
        val size = 8 * MB
        engine(disk = FaultyDisk(disk, failAt = 40)).start(job("/file?size=$size&cap=4000000", total = size.toLong()))
        val f = failed()
        assertEquals(Reasons.NETWORK, f.reason)
        val spans = assertNotNull(f.spans)
        assertEquals(f.done, doneOf(spans))
        assertPartMatches(spans)
    }

    // AC4
    @Test
    fun aDroppedConnectionIsRetriedAndTheFileIsCorrect() {
        val size = 16 * MB
        server.dropAfter = { _, nth, _ -> if (nth == 2) 1_500_000L else null }
        engine().start(job("/file?size=$size&cap=8000000", total = size.toLong()))
        assertEquals(EngineEvent.Finished("1", size.toLong()), awaitTerminal())
        assertFileIs(size)
        assertTrue(gets("/file").size >= 5, "four ranges and a retry: ${gets("/file").map { it.headers["range"] }}")
    }

    @Test
    fun aRangeThatKeepsMakingProgressBetweenDropsNeverRunsOutOfRetries() {
        val size = 8 * MB
        // every response is cut after 500 KB: the file needs far more than four retries, but each one moved a range forward
        server.dropAfter = { _, _, _ -> 500_000L }
        engine().start(job("/file?size=$size&cap=16000000", total = size.toLong()))
        assertEquals(EngineEvent.Finished("1", size.toLong()), awaitTerminal())
        assertFileIs(size)
        assertTrue(gets("/file").size > 4 + RANGE_RETRIES, "requests: ${gets("/file").size}")
    }

    @Test
    fun aRangeThatFailsFourTimesFailsTheDownloadWithNetworkError() {
        val size = 16 * MB
        // every response that starts inside the second range is cut before its first byte: the range, and whoever steals from it,
        // fails again and again without moving forward
        server.dropAfter = { _, _, from -> if (from in 4L * MB until 8L * MB) 0L else null }
        engine().start(job("/file?size=$size&cap=8000000", total = size.toLong()))
        val f = failed()
        assertEquals(Reasons.NETWORK, f.reason)
        val inRange =
            ranged("/file").count {
                it.headers["range"]!!
                    .removePrefix("bytes=")
                    .substringBefore('-')
                    .toLong() in
                    4L * MB until 8L * MB
            }
        assertTrue(inRange >= RANGE_RETRIES + 1, "requests into the failing range: $inRange")
        val spans = assertNotNull(f.spans)
        assertEquals(f.done, doneOf(spans))
        assertPartMatches(spans)
        assertTrue(Files.exists(dir.resolve("f.bin.part")))
        assertFalse(Files.exists(dir.resolve("f.bin")))
    }

    // M2: a range that moves only a little between failures is not given endless chances
    @Test
    fun aServerThatDropsEveryResponseAfterFourKilobytesFailsTheDownloadAfterABoundedNumberOfRequests() {
        val size = 16 * MB
        server.dropAfter = { _, _, _ -> 4096L }
        engine(retry = RetryPolicy(backoffMs = 1)).start(job("/file?size=$size&cap=16000000", total = size.toLong()))
        val f = failed()
        assertEquals(Reasons.NETWORK, f.reason)
        val requests = gets("/file").size
        assertTrue(requests <= 4 * (RANGE_RETRIES + 1) + 4, "requests: $requests")
    }

    @Test
    fun aFlakyServerThatDropsEveryTwoMibStillCompletesAFileOfSixteen() {
        val size = 16 * MB
        server.dropAfter = { _, _, _ -> 2L * MB }
        engine().start(job("/file?size=$size&cap=32000000", total = size.toLong()))
        assertEquals(EngineEvent.Finished("1", size.toLong()), awaitTerminal())
        assertFileIs(size)
    }

    @Test
    fun aRunThatFailsMoreOftenThanItsBudgetFailsEvenWhenEveryRangeKeepsMovingForward() {
        val size = 24 * MB
        // every response moves its range a whole MiB, so no range ever runs out of retries: the run's own budget ends it
        server.dropAfter = { _, _, _ -> 1L * MB }
        engine(retry = RetryPolicy(backoffMs = 1, maxRunFailures = 5)).start(job("/file?size=$size&cap=32000000", total = size.toLong()))
        val f = failed()
        assertEquals(Reasons.NETWORK, f.reason)
        assertTrue(gets("/file").size <= 4 + 5 + 1 + 4, "requests: ${gets("/file").size}")
    }

    /** Notes when each request for a range was sent, by the start of the range. */
    private class RangeLog(
        private val inner: HttpTransport = JdkTransport(),
    ) : HttpTransport {
        val sent = CopyOnWriteArrayList<Pair<Long, Long>>()

        override suspend fun open(
            url: String,
            method: String,
            headers: Map<String, String>,
        ): HttpOpen {
            headers["Range"]
                ?.removePrefix("bytes=")
                ?.substringBefore('-')
                ?.toLongOrNull()
                ?.let { sent.add(it to System.nanoTime()) }
            return inner.open(url, method, headers)
        }
    }

    @Test
    fun aRangeThatFailsThreeTimesWaitsLongerBeforeEachRetry() {
        val size = 16 * MB
        val failures = AtomicInteger()
        server.dropAfter = { _, _, from -> if (from == 4L * MB && failures.getAndIncrement() < 3) 0L else null }
        val log = RangeLog()
        engine(transport = log, retry = RetryPolicy(backoffMs = 100)).start(job("/file?size=$size&cap=32000000", total = size.toLong()))
        assertEquals(EngineEvent.Finished("1", size.toLong()), awaitTerminal())
        assertFileIs(size)
        val times = log.sent.filter { it.first == 4L * MB }.map { it.second }
        assertEquals(4, times.size, "the range was asked for three times and then served")
        val gaps = times.zipWithNext { a, b -> (b - a) / 1_000_000 }
        assertTrue(gaps[0] >= 90 && gaps[1] >= 190 && gaps[2] >= 390, "waits between the requests: $gaps ms")
    }

    @Test
    fun theWaitBeforeARetryDoublesToACap() {
        assertEquals(listOf(250L, 500L, 1000L, 2000L, 4000L, 4000L, 4000L), (1..7).map { retryDelayMs(it, RETRY_BACKOFF_MS) })
        assertEquals(listOf(1L, 2L, 4L, 8L, 16L, 16L), (1..6).map { retryDelayMs(it, 1) })
        assertEquals(4000L, retryDelayMs(10_000, RETRY_BACKOFF_MS))
    }

    @Test
    fun aStopWhileRangesWaitToRetryEndsTheRunWithinASecond() {
        val size = 16 * MB
        server.dropAfter = { _, _, _ -> 0L }
        val e = engine(retry = RetryPolicy(backoffMs = 60_000))
        e.start(job("/file?size=$size&cap=32000000", total = size.toLong()))
        await("every range to have failed once") { gets("/file").size >= 4 }
        Thread.sleep(300)
        val t0 = System.nanoTime()
        e.stop("1")
        runBlocking { e.join("1") }
        val took = (System.nanoTime() - t0) / 1_000_000
        assertTrue(took < 1_000, "took $took ms to stop")
        awaitStopped()
        assertEquals(1, eventsOf().filterIsInstance<EngineEvent.Stopped>().size)
    }

    // m1: no response is ever open without a permit
    @Test
    fun threeOneConnectionDownloadsUnderACapOfTwoNeverHoldMoreThanTwoResponses() {
        val counting = CountingTransport()
        val e = engine(maxTotalConnections = 2, transport = counting)
        val size = 4 * MB
        for (id in listOf("a", "b", "c")) {
            e.start(job("/file/$id?size=$size&cap=2000000", connections = 1, id = id, name = "$id.bin", total = size.toLong()))
        }
        for (id in listOf("a", "b", "c")) assertEquals(EngineEvent.Finished(id, size.toLong()), awaitTerminal(id))
        assertTrue(counting.peak <= 2, "engine held ${counting.peak} responses at once")
    }

    @Test
    fun threeSegmentedDownloadsUnderACapOfTwoNeverHoldMoreThanTwoResponsesProbesIncluded() {
        val counting = CountingTransport()
        val e = engine(maxTotalConnections = 2, transport = counting)
        val size = 8 * MB
        for (id in listOf("a", "b", "c")) {
            e.start(job("/file/$id?size=$size&cap=8000000", id = id, name = "$id.bin", total = size.toLong()))
        }
        for (id in listOf("a", "b", "c")) assertEquals(EngineEvent.Finished(id, size.toLong()), awaitTerminal(id))
        assertTrue(counting.peak <= 2, "engine held ${counting.peak} responses at once")
        for (id in listOf("a", "b", "c")) assertFileIs(size, "$id.bin")
    }

    // m2: one waiting download makes one extra connection give way, not all of them
    @Test
    fun oneDownloadWaitingForItsFirstConnectionTakesOnlyOneOfTheOthersConnections() {
        val size = 32 * MB
        val e = engine(maxTotalConnections = 4, reportMs = 50)
        e.start(job("/file/a?size=$size&cap=1000000", id = "a", name = "a.bin", total = size.toLong()))
        await("a to hold all four permits", id = "a") { eventsOf("a").filterIsInstance<EngineEvent.Progress>().any { it.connections == 4 } }
        e.start(job("/file/b?size=$size&cap=1000000", id = "b", name = "b.bin", connections = 1, total = size.toLong()))
        await("b to flow", id = "b") { progressDone("b") > 100_000 }
        Thread.sleep(700) // however long the others take to notice, they have by now
        val recent = eventsOf("a").filterIsInstance<EngineEvent.Progress>().takeLast(8)
        assertTrue(recent.isNotEmpty() && recent.all { it.connections == 3 }, "a's connections: ${recent.map { it.connections }}")
        listOf("a", "b").forEach { e.stop(it) }
    }

    // m3: a connection that gave its permit away comes back when one is free again
    @Test
    fun aDownloadThatGaveAConnectionAwayGetsItBackWhenTheOtherDownloadEnds() {
        val size = 64 * MB
        val e = engine(maxTotalConnections = 4, reportMs = 50)
        e.start(job("/file/a?size=$size&cap=1000000", id = "a", name = "a.bin", total = size.toLong()))
        await("a to hold all four permits", id = "a") { eventsOf("a").filterIsInstance<EngineEvent.Progress>().any { it.connections == 4 } }
        val small = 2 * MB
        e.start(job("/file/b?size=$small&cap=1000000", id = "b", name = "b.bin", connections = 1, total = small.toLong()))
        await("a to give one away", id = "a") { eventsOf("a").filterIsInstance<EngineEvent.Progress>().lastOrNull()?.connections == 3 }
        assertEquals(EngineEvent.Finished("b", small.toLong()), awaitTerminal("b"))
        val mark = events.size
        await("a to be back on four connections", timeoutMs = 10_000, id = "a") {
            events.drop(mark).any { (_, ev) -> ev.id == "a" && ev is EngineEvent.Progress && ev.connections == 4 }
        }
        e.stop("a")
    }

    @Test
    fun a503OnAnExtraConnectionLeavesTheDownloadRunningOnTheOthers() {
        val size = 8 * MB
        engine().start(job("/file?size=$size&oneconn=1&chunkDelayMs=15", total = size.toLong()))
        assertEquals(EngineEvent.Finished("1", size.toLong()), awaitTerminal())
        assertFileIs(size)
        assertEquals(1, server.maxOpen)
        assertTrue(gets("/file").size >= 4, "the four connections each asked once")
        assertTrue(eventsOf().filterIsInstance<EngineEvent.Progress>().all { it.connections == 1 })
    }

    /** Answers 503 to the first GET that asks for a range starting at or after [from], and passes everything else on. */
    private class RefuseOnce(
        private val from: Long,
        private val inner: HttpTransport = JdkTransport(),
    ) : HttpTransport {
        private val refused =
            java.util.concurrent.atomic
                .AtomicBoolean()

        override suspend fun open(
            url: String,
            method: String,
            headers: Map<String, String>,
        ): HttpOpen {
            val start = headers["Range"]?.removePrefix("bytes=")?.substringBefore('-')?.toLongOrNull()
            if (method == "GET" && start != null && start >= from && refused.compareAndSet(false, true)) {
                return HttpOpen(503, emptyMap(), url, java.io.ByteArrayInputStream(ByteArray(0)))
            }
            return inner.open(url, method, headers)
        }
    }

    @Test
    fun a503OnTheFirstConnectionAskingForMoreWorkLaterInTheRunIsRetriedNotFatal() {
        val size = 4 * MB
        // the first range ends at once and its connection steals from the slow second one: that request is refused once
        server.slowRange(start = 2L * MB, delayMs = 40)
        val transport = RefuseOnce(from = 5L * MB / 2)
        engine(transport = transport, minSplit = 256 * 1024).start(job("/file?size=$size", connections = 2, total = size.toLong()))
        assertEquals(EngineEvent.Finished("1", size.toLong()), awaitTerminal(), "events ${eventsOf().takeLast(3)}")
        assertFileIs(size)
    }

    private class AlwaysRefusing(
        private val status: Int,
    ) : HttpTransport {
        override suspend fun open(
            url: String,
            method: String,
            headers: Map<String, String>,
        ) = HttpOpen(status, emptyMap(), url, java.io.ByteArrayInputStream(ByteArray(0)))
    }

    @Test
    fun a503OnTheFirstConnectionIsAnHttpFailure() {
        Files.write(dir.resolve("f.bin.part"), withTrailer(ByteArray(8 * MB), tokenA))
        val spans = listOf(Span(0, 4L * MB - 1, 0), Span(4L * MB, 8L * MB - 1, 0))
        engine(transport = AlwaysRefusing(503)).start(job("/file", connections = 2, spans = spans, partId = tokenA))
        assertEquals("HTTP 503", failed().reason)
    }

    // Finding 2: RFC 9110 has a weak ETag never match an If-Range, so it must not be sent as one
    @Test
    fun aWeakEtagServerIsStillSplitOverSeveralConnectionsWithTheLastModifiedAsIfRange() {
        val size = 16 * MB
        engine().start(job("/weak?size=$size&cap=16000000", total = size.toLong()))
        assertEquals(EngineEvent.Finished("1", size.toLong()), awaitTerminal())
        assertFileIs(size)
        assertEquals(1, eventsOf().filterIsInstance<EngineEvent.Started>().size, "no restart")
        val later = gets("/weak").drop(1)
        assertEquals(3, later.size)
        assertTrue(later.all { it.headers["if-range"] == TestServer.LAST_MODIFIED }, "if-range on $later")
    }

    @Test
    fun aWeakEtagServerWithoutALastModifiedGetsNoIfRangeAndIsStillSplit() {
        val size = 16 * MB
        engine().start(job("/weak?size=$size&cap=16000000&nolm=1", total = size.toLong()))
        assertEquals(EngineEvent.Finished("1", size.toLong()), awaitTerminal())
        assertFileIs(size)
        assertEquals(1, eventsOf().filterIsInstance<EngineEvent.Started>().size, "no restart")
        assertTrue(gets("/weak").none { it.headers["if-range"] != null }, "${gets("/weak").map { it.headers }}")
    }

    @Test
    fun aOneConnectionResumeAgainstAWeakEtagContinuesInsteadOfStartingOver() {
        val size = 900 * 1024
        Files.write(dir.resolve("f.bin.part"), TestServer.expected(size).copyOf(300_000))
        val spec = job("/weak?size=$size", connections = 1, etag = "W/\"v1\"").copy(done = 300_000, lastModified = TestServer.LAST_MODIFIED)
        engine().start(spec)
        assertEquals(EngineEvent.Finished("1", size.toLong()), awaitTerminal())
        assertFileIs(size)
        assertEquals(300_000L, eventsOf().filterIsInstance<EngineEvent.Started>().single().done)
        assertEquals(TestServer.LAST_MODIFIED, gets("/weak").single().headers["if-range"])
    }

    // AC5
    @Test
    fun a200ToARangeRequestRestartsFromZeroWithTheRulesOfTheNewResponse() {
        val size = 8 * MB
        engine().start(job("/file?size=$size&cap=8000000&full200from=1", total = size.toLong()))
        assertEquals(EngineEvent.Finished("1", size.toLong()), awaitTerminal())
        assertFileIs(size)
        val started = eventsOf().filterIsInstance<EngineEvent.Started>()
        assertEquals(2, started.size)
        assertNotNull(started[0].spans)
        assertNull(started[1].spans, "the new response was a 200: one connection")
        assertEquals(0L, started[1].done)
    }

    @Test
    fun aChangedEtagRestartsFromZeroAndTheFileIsCorrect() {
        val size = 8 * MB
        engine().start(job("/file?size=$size&cap=8000000&etagFrom=1&ignoreIfRange=1", total = size.toLong()))
        assertEquals(EngineEvent.Finished("1", size.toLong()), awaitTerminal())
        assertFileIs(size)
        val started = eventsOf().filterIsInstance<EngineEvent.Started>()
        assertEquals(2, started.size)
        assertEquals("\"v1\"", started[0].etag)
        assertEquals("\"v2\"", started[1].etag)
        assertEquals(0L, started[1].done)
    }

    @Test
    fun aSecondChangeInTheSameRunFailsAsChangedAndClearsThePart() {
        val size = 8 * MB
        engine().start(job("/changing?size=$size&cap=8000000", total = size.toLong()))
        val f = failed()
        assertEquals(Reasons.CHANGED, f.reason)
        assertEquals(0L, f.done)
        assertNull(f.spans)
        assertTrue(f.cleared, "the ranges of the row are gone with the file")
        assertFalse(Files.exists(dir.resolve("f.bin.part")))
    }

    @Test
    fun a416FailsAsChangedAndDeletesThePart() {
        val size = 8 * MB
        engine().start(job("/file?size=$size&cap=8000000&gone416from=1", total = size.toLong()))
        val f = failed()
        assertEquals(Reasons.CHANGED, f.reason)
        assertEquals(0L, f.done)
        assertNull(f.spans)
        assertTrue(f.cleared, "the ranges of the row are gone with the file")
        assertFalse(Files.exists(dir.resolve("f.bin.part")))
    }

    // m5: ranges that were not trusted are dropped with their file; a failure after that says so
    @Test
    fun aFailureAfterAnUntrustedPartWasDeletedSaysTheRangesAreGone() {
        val size = 8 * MB
        Files.write(dir.resolve("f.bin.part"), withTrailer(ByteArray(size), tokenB))
        engine().start(job("/404", connections = 2, spans = storedSpans, partId = tokenA))
        val f = failed()
        assertEquals("HTTP 404", f.reason)
        assertTrue(f.cleared)
        assertNull(f.spans)
        assertFalse(Files.exists(dir.resolve("f.bin.part")))
    }

    @Test
    fun aFailureThatKeepsTheFileAndItsRangesIsNotCleared() {
        val size = 16 * MB
        server.dropAfter = { _, _, from -> if (from in 4L * MB until 8L * MB) 0L else null }
        engine().start(job("/file?size=$size&cap=8000000", total = size.toLong()))
        val f = failed()
        assertFalse(f.cleared)
        assertNotNull(f.spans)
        assertEquals(eventsOf().filterIsInstance<EngineEvent.Started>().first().partId, f.partId)
    }

    // m4: a disk that cannot say how big the file is never costs the file
    @Test
    fun aDiskErrorReadingThePartSizeFailsTheJobAndKeepsTheFile() {
        val size = 8 * MB
        val bytes = withTrailer(sixtyPercentFile(), tokenA)
        Files.write(dir.resolve("f.bin.part"), bytes)
        val flaky =
            object : DiskIo by disk {
                override fun size(p: Path): Long? = throw IOException("transient")
            }
        engine(disk = flaky).start(job("/file?size=$size", connections = 2, spans = storedSpans, partId = tokenA))
        val f = failed()
        assertEquals(Reasons.NOT_WRITABLE, f.reason)
        assertFalse(f.cleared)
        assertContentEquals(bytes, Files.readAllBytes(dir.resolve("f.bin.part")))
        assertEquals(emptyList(), server.requests("/file"))
    }

    @Test
    fun aDiskErrorReadingThePartIdentityFailsTheJobAndKeepsTheFile() {
        val size = 8 * MB
        val bytes = withTrailer(sixtyPercentFile(), tokenA)
        Files.write(dir.resolve("f.bin.part"), bytes)
        val flaky =
            object : DiskIo by disk {
                override fun readTail(
                    p: Path,
                    len: Int,
                ): ByteArray? = throw IOException("transient")
            }
        engine(disk = flaky).start(job("/file?size=$size", connections = 2, spans = storedSpans, partId = tokenA))
        assertEquals(Reasons.NOT_WRITABLE, failed().reason)
        assertContentEquals(bytes, Files.readAllBytes(dir.resolve("f.bin.part")))
    }

    // m6: a start that carries no memory of the part (no ranges, nothing done) clears what the engine kept for its id
    @Test
    fun aFreshJobUnderAReusedIdIgnoresWhatTheEngineKeptForTheOldRow() {
        val size = 900 * 1024 // under 1 MiB: one stream, so the engine remembers a sequential partial for the id
        val e = engine(reportMs = 50)
        e.start(job("/file?size=$size&cap=300000", total = size.toLong()))
        await("some bytes") { progressDone() > 100_000 }
        e.stop("1")
        runBlocking { e.join("1") }
        // the row was removed (the engine was never told to forget it) and a new one took the id, with a pre-sized file in its way
        Files.write(dir.resolve("f.bin.part"), ByteArray(size))
        events.clear()
        e.start(job("/file?size=$size", total = size.toLong()))
        assertEquals(EngineEvent.Finished("1", size.toLong()), awaitTerminal())
        assertFileIs(size)
    }

    @Test
    fun aStalledConnectionIsFailedOnTheFakeClockAndEveryRangeIsTriedFourTimes() {
        val clock = FakeClock()
        val size = 8 * MB
        // the two first responses move their range 50 KB and stall; every retry stalls at once, so no range makes progress again
        server.dropAfter = { _, nth, _ -> if (nth < 2) 50_000L else 0L }
        engine(clock = clock, stallMs = 200).start(job("/stall?size=$size", connections = 2, total = size.toLong()))
        val end = System.currentTimeMillis() + 20_000
        while (eventsOf().none { it is EngineEvent.Failed }) {
            check(System.currentTimeMillis() < end) { "no failure: ${eventsOf().takeLast(3)}" }
            clock.advance(300)
            Thread.sleep(20)
        }
        val f = failed()
        assertEquals(Reasons.NETWORK, f.reason)
        assertTrue(f.done >= 50_000)
        assertPartMatches(assertNotNull(f.spans))
    }

    // AC8
    @Test
    fun pauseStopsEveryConnectionWithinASecondAndStoppedHasTheExactRanges() {
        val size = 32 * MB
        val e = engine(reportMs = 100)
        e.start(job("/file?size=$size&cap=2000000", total = size.toLong()))
        await("some bytes") { eventsOf().filterIsInstance<EngineEvent.Progress>().any { it.done > 1_000_000 } }
        val t0 = System.nanoTime()
        e.stop("1")
        runBlocking { e.join("1") }
        val joined = (System.nanoTime() - t0) / 1_000_000
        assertTrue(joined < 1_000, "took $joined ms to stop")
        val stopped = awaitStopped()
        assertEquals(doneOf(stopped.spans), stopped.done)
        assertTrue(eventsOf().none { it is EngineEvent.Finished || it is EngineEvent.Failed })
        val before = part().count { it != 0.toByte() }
        Thread.sleep(400)
        assertEquals(before, part().count { it != 0.toByte() }, "bytes still arriving after stop")
        assertPartMatches(stopped.spans)
        assertEquals(size.toLong() + TRAILER_BYTES, Files.size(dir.resolve("f.bin.part")))
        await("server side closed") { server.openNow == 0 }
    }

    @Test
    fun aStartRightAfterAStopResumesFromTheNewerRangesThanTheOnesItWasGiven() {
        val size = 32 * MB
        val e = engine(reportMs = 100)
        e.start(job("/file?size=$size&cap=2000000", total = size.toLong()))
        await("a first progress") { eventsOf().filterIsInstance<EngineEvent.Progress>().any { it.done > 200_000 } }
        val stale = eventsOf().filterIsInstance<EngineEvent.Progress>().first { it.done > 200_000 }.spans!!
        await("more bytes than the stale ones") {
            eventsOf().filterIsInstance<EngineEvent.Progress>().any {
                it.done >
                    doneOf(stale) + 500_000
            }
        }
        val partId = eventsOf().filterIsInstance<EngineEvent.Started>().first().partId
        e.stop("1")
        e.start(job("/file?size=$size&cap=2000000", total = size.toLong(), spans = stale, etag = "\"v1\"", partId = partId))
        await("restarted") { eventsOf().filterIsInstance<EngineEvent.Started>().size == 2 }
        val stopped = eventsOf().filterIsInstance<EngineEvent.Stopped>().single()
        val restarted = eventsOf().filterIsInstance<EngineEvent.Started>().last()
        assertTrue(stopped.done > doneOf(stale))
        assertEquals(stopped.done, restarted.done, "the stale ranges would have re-fetched bytes already on disk")
        assertEquals(stopped.spans, restarted.spans)
        e.stop("1")
    }

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).toList()

    private fun progressDone(id: String = "1") = eventsOf(id).filterIsInstance<EngineEvent.Progress>().lastOrNull()?.done ?: 0L

    // AC14: pause, kill and network loss at random offsets, then a resume from what was reported, always ends in the same bytes.
    @Test
    fun twentySeededInterruptionsAtRandomOffsetsAllEndInTheServersBytes() {
        val random = Random(42)
        repeat(20) { run ->
            events.clear()
            val id = "r$run"
            val size = 4 * MB + random.nextInt(0, 3 * MB) + random.nextInt(0, 1_000)
            val variant = run % 3
            val k = random.nextInt(0, 4)
            val x = random.nextLong(1, 200_000)
            val seen = AtomicInteger()
            val span = size / 4
            server.dropAfter =
                when (variant) {
                    // a network loss on one range at a random offset, and then no byte of it again: the download fails with its ranges
                    1 -> { _, _, from -> if (from / span == k.toLong()) (if (from % span == 0L) x else 0L) else null }

                    // a connection lost once at a random offset while the user pauses at another
                    else -> { _, _, _ -> if (seen.getAndIncrement() == k) x else null }
                }
            val name = "r$run.bin"
            // slow enough (4 x 1 MB/s) that a pause at up to 90% still finds the download running, however loaded the machine is
            val path = "/file/$run?size=$size&cap=1000000"
            val first = engine(reportMs = 10)
            first.start(job(path, id = id, name = name, total = size.toLong()))
            val spans: List<Span>
            val partId: String?
            if (variant == 1) {
                val f = failed(id)
                spans = assertNotNull(f.spans, "run $run")
                partId = f.partId
                runBlocking { first.join(id) } // the next start must not be swallowed by a job that is still winding down
            } else {
                val at = random.nextLong(size / 10L, size * 9L / 10L)
                await("run $run to reach $at") { progressDone(id) >= at }
                first.stop(id)
                runBlocking { first.join(id) }
                val stopped = awaitStopped(id)
                spans = stopped.spans
                partId = stopped.partId
            }
            val bytes = Files.readAllBytes(dir.resolve("$name.part"))
            val data = TestServer.expected(size)
            for (s in spans) {
                for (i in s.start until s.next) assertEquals(data[i.toInt()], bytes[i.toInt()], "run $run: byte $i of $s differs")
                for (i in s.next..s.end) assertEquals(0.toByte(), bytes[i.toInt()], "run $run: byte $i of $s is past what was reported")
            }
            assertEquals(size.toLong() + TRAILER_BYTES, bytes.size.toLong())
            server.dropAfter = { _, _, _ -> null }
            val resumed = if (variant == 2) engine(reportMs = 10) else first // after an app restart no memory of the run is left
            events.clear()
            resumed.start(
                job("/file/$run?size=$size", id = id, name = name, total = size.toLong(), spans = spans, etag = "\"v1\"", partId = partId),
            )
            assertEquals(EngineEvent.Finished(id, size.toLong()), awaitTerminal(id), "run $run")
            assertEquals(sha256(TestServer.expected(size)), sha256(Files.readAllBytes(dir.resolve(name))), "run $run, variant $variant")
        }
    }

    /** Remembers every positional write, to prove no byte is written by two connections. */
    private class RecordingDisk(
        private val real: DiskIo,
    ) : DiskIo by real {
        val writes = CopyOnWriteArrayList<LongRange>()

        override fun openSpans(
            p: Path,
            size: Long?,
        ): PartFile {
            val inner = real.openSpans(p, size)
            return object : PartFile by inner {
                override fun write(
                    offset: Long,
                    buf: ByteArray,
                    off: Int,
                    len: Int,
                ) {
                    writes.add(offset until offset + len)
                    inner.write(offset, buf, off, len)
                }
            }
        }

        fun assertDisjointAndComplete(size: Long) {
            val sorted = writes.filter { it.first < size }.sortedBy { it.first } // the identity trailer lies past the data
            sorted.zipWithNext { a, b -> assertTrue(a.last < b.first, "writes $a and $b overlap") }
            assertEquals(size, sorted.sumOf { it.last - it.first + 1 })
            assertEquals(0L, sorted.first().first)
            assertEquals(size - 1, sorted.last().last)
        }
    }

    /** Fails the [failAt]th write before any byte of it is written. */
    private class FaultyDisk(
        private val real: DiskIo,
        private val failAt: Int,
    ) : DiskIo by real {
        private val count = AtomicInteger()

        override fun openSpans(
            p: Path,
            size: Long?,
        ): PartFile {
            val inner = real.openSpans(p, size)
            return object : PartFile by inner {
                override fun write(
                    offset: Long,
                    buf: ByteArray,
                    off: Int,
                    len: Int,
                ) {
                    if (count.incrementAndGet() == failAt) throw IOException("injected")
                    inner.write(offset, buf, off, len)
                }
            }
        }
    }

    /** The bytes each file really received, as the engine wrote them: what crossed the socket buffers and was consumed. */
    private class CountingDisk(
        private val real: DiskIo,
    ) : DiskIo by real {
        val written = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>()

        fun of(name: String) = written[name]?.get() ?: 0L

        override fun openSpans(
            p: Path,
            size: Long?,
        ): PartFile {
            val inner = real.openSpans(p, size)
            val count =
                written.computeIfAbsent(p.fileName.toString()) {
                    java.util.concurrent.atomic
                        .AtomicLong()
                }
            return object : PartFile by inner {
                override fun write(
                    offset: Long,
                    buf: ByteArray,
                    off: Int,
                    len: Int,
                ) {
                    inner.write(offset, buf, off, len)
                    count.addAndGet(len.toLong())
                }
            }
        }
    }

    // AC7: one 3 s window after a warm-up. The combined rate is always asserted; the split between the downloads is logged only on CI,
    // where a shared machine makes the share of two competing downloads meaningless.
    @Test
    fun aSpeedLimitIsSharedByDownloadsNotConnections() {
        val counting = CountingDisk(disk)
        val e = engine(disk = counting)
        val cap = 1_000_000.0
        val tolerance = if (System.getenv("CI") == null) 0.10 else 0.30
        e.setRate(1_000_000)
        val size = 20L * MB
        e.start(job("/file/a?size=$size", id = "a", name = "a.bin", total = size))
        e.start(job("/file/b?size=$size", id = "b", name = "b.bin", total = size))
        await("both flowing") { counting.of("a.bin.part") > 0 && counting.of("b.bin.part") > 0 }
        Thread.sleep(2_000) // warm-up: the buckets' burst and the connections' start are behind us
        val t0 = System.nanoTime()
        val a0 = counting.of("a.bin.part")
        val b0 = counting.of("b.bin.part")
        Thread.sleep(3_000)
        val a = counting.of("a.bin.part") - a0
        val b = counting.of("b.bin.part") - b0
        val combined = (a + b) * 1e9 / (System.nanoTime() - t0)
        assertTrue(combined in cap * (1 - tolerance)..cap * (1 + tolerance), "combined throughput over 3 s $combined B/s")
        val even = listOf(a, b).all { it in ((a + b) * 0.4).toLong()..((a + b) * 0.6).toLong() }
        if (System.getenv("CI") ==
            null
        ) {
            assertTrue(even, "uneven split $a / $b")
        } else {
            println("share of a and b over 3 s: $a / $b (even: $even)")
        }
        assertTrue(server.maxOpen >= 8, "both downloads should have had four connections, saw ${server.maxOpen}")
    }

    // AC15 / NFR2: logged on CI, where a shared machine makes timing meaningless
    @Test
    fun eightConnectionsAreMuchFasterThanOneAgainstAServerThatCapsEachConnection() {
        val size = 12L * MB
        val e = engine()

        fun seconds(
            id: String,
            connections: Int,
        ): Double {
            val t0 = System.nanoTime()
            e.start(job("/file/$id?size=$size&cap=2000000", id = id, name = "$id.bin", connections = connections, total = size))
            assertEquals(EngineEvent.Finished(id, size), awaitTerminal(id))
            return (System.nanoTime() - t0) / 1e9
        }
        val one = seconds("one", 1)
        val eight = seconds("eight", 8)
        println("capped: 1 connection ${size / MB / one} MB/s, 8 connections ${size / MB / eight} MB/s, ratio ${one / eight}")
        if (System.getenv("CI") == null) assertTrue(one / eight >= 6, "8 connections were only ${one / eight}x faster")
    }

    @Test
    fun uncappedSegmentedThroughputIsLoggedNextToOneConnection() {
        val size = 256L * MB
        val e = engine()

        fun seconds(
            id: String,
            connections: Int,
        ): Double {
            val t0 = System.nanoTime()
            e.start(job("/file/$id?size=$size", id = id, name = "$id.bin", connections = connections, total = size))
            assertEquals(EngineEvent.Finished(id, size), awaitTerminal(id))
            return (System.nanoTime() - t0) / 1e9
        }
        val one = seconds("one", 1)
        val four = seconds("four", 4)
        println("uncapped: 1 connection ${size / MB / one} MB/s, 4 connections ${size / MB / four} MB/s")
        Files.delete(dir.resolve("one.bin"))
        assertContentEquals(TestServer.expected(1000), Files.newInputStream(dir.resolve("four.bin")).use { it.readNBytes(1000) })
    }

    @Test
    fun forgettingAJobDropsTheRangesTheEngineKeptForIt() {
        val size = 32 * MB
        val e = engine(reportMs = 100)
        e.start(job("/file?size=$size&cap=2000000", total = size.toLong()))
        await("a first progress") { eventsOf().filterIsInstance<EngineEvent.Progress>().any { it.done > 200_000 } }
        val stale = eventsOf().filterIsInstance<EngineEvent.Progress>().first { it.done > 200_000 }.spans!!
        await("more bytes than the stale ones") {
            eventsOf().filterIsInstance<EngineEvent.Progress>().any {
                it.done >
                    doneOf(stale) + 500_000
            }
        }
        e.stop("1")
        runBlocking { e.join("1") }
        e.forget("1")
        val partId = eventsOf().filterIsInstance<EngineEvent.Started>().first().partId
        e.start(job("/file?size=$size&cap=2000000", total = size.toLong(), spans = stale, etag = "\"v1\"", partId = partId))
        await("restarted") { eventsOf().filterIsInstance<EngineEvent.Started>().size == 2 }
        assertEquals(doneOf(stale), eventsOf().filterIsInstance<EngineEvent.Started>().last().done)
        e.stop("1")
    }

    /**
     * The body of a ranged answer, byte for byte the server's. At [parkAt] it waits, reporting on [parked], until [go] opens, and
     * then goes on slowly (16 KiB every 10 ms) so that a test has time to change what the run sees between two chunks.
     */
    private class ScriptedBody(
        private val from: Long,
        private val to: Long,
        private val parkAt: Long? = null,
        private val parked: CountDownLatch? = null,
        private val go: CountDownLatch? = null,
        private val served: CountDownLatch? = null,
    ) : InputStream() {
        private var pos = from
        private var released = false

        @Volatile private var closed = false

        override fun read(): Int = throw UnsupportedOperationException()

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int {
            if (parkAt != null && pos >= parkAt && !released) {
                parked?.countDown()
                while (!go!!.await(10, TimeUnit.MILLISECONDS)) if (closed) throw IOException("closed")
                released = true
            }
            if (closed) throw IOException("closed")
            if (pos > to) return -1
            val limit =
                if (parkAt != null && pos < parkAt) {
                    parkAt - pos
                } else if (released) {
                    16L * 1024
                } else {
                    Long.MAX_VALUE
                }
            if (released) Thread.sleep(10)
            val n = minOf(len.toLong(), to + 1 - pos, limit).toInt()
            for (i in 0 until n) b[off + i] = TestServer.byteAt(pos + i)
            pos += n
            if (pos > to) served?.countDown()
            return n
        }

        override fun close() {
            closed = true
        }
    }

    /** A body that never delivers a byte and ends in an error when it is closed: a download that holds its connection. */
    private class IdleBody : InputStream() {
        @Volatile private var closed = false

        override fun read(): Int = throw UnsupportedOperationException()

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int {
            while (!closed) Thread.sleep(10)
            throw IOException("closed")
        }

        override fun close() {
            closed = true
        }
    }

    /**
     * Download "a" (a link ending in `/a`) is served by ranged answers from [range], which may throw to refuse; any other link is a
     * single stream that never delivers. [opened] lists every request as `name` or `name:range`.
     */
    private class ScriptedTransport(
        private val total: Long,
        private val range: (from: Long, to: Long) -> InputStream,
    ) : HttpTransport {
        val opened = CopyOnWriteArrayList<String>()

        override suspend fun open(
            url: String,
            method: String,
            headers: Map<String, String>,
        ): HttpOpen {
            val name = url.substringAfterLast('/')
            val asked = headers["Range"]?.removePrefix("bytes=")
            opened.add(if (asked == null) name else "$name:$asked")
            if (name != "a" || asked == null) {
                return HttpOpen(200, mapOf("content-length" to listOf("1000000")), url, IdleBody())
            }
            val (a, b) = asked.split('-').map { it.toLong() }
            val h = mapOf("content-range" to listOf("bytes $a-$b/$total"), "etag" to listOf("\"v1\""), "accept-ranges" to listOf("bytes"))
            return HttpOpen(206, h, url, range(a, b))
        }
    }

    /**
     * Download "a" of 2 MiB over two connections, from scratch: the probe's answer is worker 0's, so worker 0 is the one on the
     * first range and the extra connection is the one on the second.
     */
    private fun twoRangeJobOfA() = job("/a", id = "a", name = "a.bin", connections = 2, total = 2L * MB)

    private val secondRangeOfA = initialSpans(2L * MB, 2).also { assertEquals(2, it.size) }[1].let { "a:${it.start}-${it.end}" }

    private fun idleJob(id: String) = job("/$id", id = id, name = "$id.bin", connections = 1, total = 1_000_000)

    // Finding 2: the last connection of a download is never the one that gives way
    @Test
    fun aDownloadKeepsItsLastConnectionWhenAnotherWaitsForItsFirstWithTheCapFull() {
        val parked = CountDownLatch(1)
        val go = CountDownLatch(1)
        val firstRangeServed = CountDownLatch(1)
        val transport =
            ScriptedTransport(2L * MB) { from, to ->
                if (from == 0L) ScriptedBody(from, to, served = firstRangeServed) else ScriptedBody(from, to, from + 256 * 1024, parked, go)
            }
        val e = engine(maxTotalConnections = 2, transport = transport, minSplit = 16L * MB)
        try {
            e.start(twoRangeJobOfA())
            // the second range is mid-read (its connection is the only one left once the first range is done and its worker is gone)
            assertTrue(parked.await(10, TimeUnit.SECONDS), "the second range never got going")
            assertTrue(firstRangeServed.await(10, TimeUnit.SECONDS), "the first range never ended")
            e.start(idleJob("c"))
            await("c to take the permit the first worker gave back", timeoutMs = 10_000, id = "c") { transport.opened.contains("c") }
            e.start(idleJob("b")) // the cap of two is full: this download has no connection and waits
            go.countDown()
            await("a to finish on the connection it has", timeoutMs = 10_000, id = "a") { eventsOf("a").any { it is EngineEvent.Finished } }
            assertFileIs(2 * MB, "a.bin")
            assertTrue(eventsOf("b").none { it is EngineEvent.Finished || it is EngineEvent.Failed })
        } finally {
            go.countDown()
            listOf("a", "b", "c").forEach { e.stop(it) }
        }
    }

    // Finding 5a: a connection that waits to retry gives its permit away, or a download without one waits out the whole backoff
    @Test
    fun anExtraConnectionWaitingToRetryGivesItsPermitToADownloadThatHasNone() {
        val go = CountDownLatch(1)
        val transport =
            ScriptedTransport(2L * MB) { from, to ->
                // the second range always fails
                if (from == 0L) ScriptedBody(from, to, parkAt = 0, go = go) else throw IOException("refused")
            }
        // a failed range waits a minute: only a permit given away can get b going within the test
        val e =
            engine(
                maxTotalConnections = 2,
                transport = transport,
                minSplit = 16L * MB,
                retry = RetryPolicy(backoffMs = 60_000, yieldRetryMs = 50),
            )
        try {
            e.start(twoRangeJobOfA())
            await("the second range to have failed once", timeoutMs = 10_000, id = "a") { transport.opened.contains(secondRangeOfA) }
            e.start(idleJob("b")) // both permits are a's: one on the first range, one waiting to retry the second
            await("b to get a permit while a's second range waits to retry", timeoutMs = 5_000, id = "b") { transport.opened.contains("b") }
            assertEquals(1, transport.opened.count { it == secondRangeOfA }, "the retry is still a minute away")
        } finally {
            go.countDown()
            listOf("a", "b").forEach { e.stop(it) }
        }
    }

    /** A disk whose first rename fails, as in a folder that has gone read-only or is not there for a moment. */
    private class RenameFailsOnce(
        private val real: DiskIo,
    ) : DiskIo by real {
        private val failed =
            java.util.concurrent.atomic
                .AtomicBoolean()

        override fun move(
            from: Path,
            to: Path,
        ) {
            if (failed.compareAndSet(false, true)) throw IOException("injected")
            real.move(from, to)
        }
    }

    // Finding 1: the file is cut to its total before it is renamed, and with the trailer gone only the ranges say it is finished
    @Test
    fun aFinishedDownloadWhoseRenameFailedIsRenamedOnTheRetryWithoutAnyRequest() {
        val size = 8 * MB
        val e = engine(disk = RenameFailsOnce(disk))
        e.start(job("/file?size=$size&cap=16000000", total = size.toLong()))
        val f = failed()
        assertEquals(Reasons.NOT_WRITABLE, f.reason)
        assertEquals(size.toLong(), Files.size(dir.resolve("f.bin.part")), "cut to its total: no identity left")
        val requests = server.requests("/file").size
        runBlocking { e.join("1") }
        events.clear()
        e.start(job("/file?size=$size", total = size.toLong(), spans = f.spans, partId = f.partId))
        assertEquals(EngineEvent.Finished("1", size.toLong()), awaitTerminal())
        assertFileIs(size)
        assertFalse(Files.exists(dir.resolve("f.bin.part")))
        assertEquals(requests, server.requests("/file").size, "the retry fetched nothing")
    }

    @Test
    fun aTotalSizedPartWithAllRangesFinishedIsRenamedWithoutAnyRequestOrDeletion() {
        // the app died between the cut and the rename: the ranges are all finished and the identity is gone from the file
        val size = 8 * MB
        Files.write(dir.resolve("f.bin.part"), TestServer.expected(size))
        val spans = listOf(Span(0, 4L * MB - 1, 4L * MB), Span(4L * MB, 8L * MB - 1, 4L * MB))
        engine().start(job("/file?size=$size", total = size.toLong(), spans = spans, partId = tokenA))
        assertEquals(EngineEvent.Finished("1", size.toLong()), awaitTerminal())
        assertFileIs(size)
        assertEquals(emptyList(), server.requests("/file"))
    }

    @Test
    fun aTotalSizedPartWithOneRangeUnfinishedIsNotTrustedAndTheDownloadStartsOver() {
        val size = 8 * MB
        Files.write(dir.resolve("f.bin.part"), ByteArray(size)) // the first range's bytes are not there either
        val spans = listOf(Span(0, 4L * MB - 1, 4L * MB), Span(4L * MB, 8L * MB - 1, 3L * MB))
        engine().start(job("/file?size=$size&cap=16000000", total = size.toLong(), spans = spans, etag = "\"v1\"", partId = tokenA))
        assertEquals(EngineEvent.Finished("1", size.toLong()), awaitTerminal())
        assertFileIs(size)
        assertEquals(0L, eventsOf().filterIsInstance<EngineEvent.Started>().first().done)
        assertTrue(gets("/file").isNotEmpty())
    }
}
