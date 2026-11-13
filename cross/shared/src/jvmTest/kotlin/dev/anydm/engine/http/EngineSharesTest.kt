package dev.anydm.engine.http

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** A [DownloadEngine] can run on a [RateShares] that is shared with other engines (the torrent engine). */
class EngineSharesTest {
    private val server = TestServer()
    private val dir: Path = Files.createTempDirectory("engine-shares")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() {
        scope.cancel()
        server.close()
        dir.toFile().deleteRecursively()
    }

    private fun await(
        what: String,
        cond: () -> Boolean,
    ) {
        val end = System.currentTimeMillis() + 15_000
        while (!cond()) {
            check(System.currentTimeMillis() < end) { "timed out waiting for $what" }
            Thread.sleep(10)
        }
    }

    private fun registered(
        shares: RateShares,
        id: String,
    ) = runCatching { shares.bucket(id) }.isSuccess

    @Test
    fun theEngineRegistersItsJobsInTheSharesItWasGivenAndLeavesThemAtTheEnd() {
        val shares = RateShares()
        val engine = DownloadEngine(JdkTransport(), RealDiskIo(), Clock { System.currentTimeMillis() }, scope, shares = shares)
        engine.start(JobSpec("a", "${server.base}/file?size=${1024 * 1024}&chunkDelayMs=20", dir.toString(), "a.bin", 0, null, null))
        await("the job to register") { registered(shares, "a") }
        // a cap set on the shared object reaches the running job's bucket
        shares.setRate(100_000)
        assertEquals(10_000, shares.bucket("a").maxChunk(), "100 ms of the whole cap: the job is alone")
        engine.stop("a")
        await("the job to leave") { !registered(shares, "a") }
        assertFailsWith<IllegalStateException> { shares.bucket("a") }
    }

    @Test
    fun setRateOnTheEngineStillSetsTheCapOfItsShares() {
        val shares = RateShares()
        val engine = DownloadEngine(JdkTransport(), RealDiskIo(), Clock { System.currentTimeMillis() }, scope, shares = shares)
        engine.setRate(50_000)
        val bucket = shares.register("x")
        assertEquals(5_000, bucket.maxChunk(), "100 ms of 50 kB/s")
    }
}
