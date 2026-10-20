package dev.anydm.engine

import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.net.NetworkIdentity
import dev.anydm.engine.queue.RemoveFiles
import dev.anydm.engine.store.StoreTestBase
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The engine counts traffic and saves it every 10 s and when it stops (NET-04). FakeProtocol: 64 KiB in 4 s. */
abstract class EngineTrafficContract : StoreTestBase() {
    private val whole = 64 * 1024L

    private suspend fun TestScope.started(network: NetworkIdentity = NetworkIdentity.Unknown): Pair<EngineHarness, RecordingTraffic> {
        val store = newStore()
        val traffic = RecordingTraffic(store.traffic)
        val h = EngineHarness(this, store, network = network, traffic = traffic)
        h.engine.start()
        return h to traffic
    }

    @Test
    fun nothingIsSavedBeforeTenSeconds() =
        runTest {
            val (h, traffic) = started()
            h.addJob()
            advanceTimeBy(9.seconds)
            traffic.adds shouldBe emptyList()
            advanceTimeBy(2.seconds)
            runCurrent()
            traffic.adds.size shouldBe 1
            traffic.bytes shouldBe whole
            h.engine.stop()
        }

    @Test
    fun savesEveryTenSeconds() =
        runTest {
            val (h, traffic) = started()
            h.protocol.bytesPerSecond = 4 * 1024 // 16 s for the job
            h.addJob()
            advanceTimeBy(11.seconds)
            traffic.adds.size shouldBe 1
            advanceTimeBy(10.seconds)
            traffic.adds.size shouldBe 2
            traffic.bytes shouldBe whole
            h.engine.stop()
        }

    @Test
    fun stopSavesWhatIsLeft() =
        runTest {
            val (h, traffic) = started()
            h.addJob()
            advanceTimeBy(5.seconds)
            traffic.adds shouldBe emptyList()

            h.engine.stop()

            traffic.bytes shouldBe whole
        }

    @Test
    fun uploadRecordedByAJobIsSavedWithItsKind() =
        runTest {
            val (h, traffic) = started()
            h.protocol.uploadPerJob = 8 * 1024
            val id = h.addJob()
            advanceTimeBy(11.seconds)
            runCurrent()

            val batch = traffic.adds.single()
            batch.daysUp.values.sum() shouldBe 8 * 1024L
            batch.daysUp.keys
                .single()
                .third shouldBe DownloadKind.DIRECT
            batch.downloadsUp shouldBe mapOf(id to 8 * 1024L)
            traffic.bytes shouldBe whole
            h.store.traffic.downloadUpload(id) shouldBe 8 * 1024L
            h.engine.stop()
        }

    @Test
    fun totalSpeedStaysDownloadOnly() =
        runTest {
            val (h, _) = started()
            h.protocol.uploadPerJob = 10L * 1024 * 1024
            h.addJob()
            advanceTimeBy(2.seconds)
            runCurrent()

            // 16 KiB/s of download; the 10 MiB sent is not in the speed readout
            h.engine.totalSpeed.value.current shouldBe 16 * 1024L
            h.engine.stop()
        }

    @Test
    fun theNetworkIsTheIdentitysKey() =
        runTest {
            val (h, traffic) = started(network = { "wlan0" })
            h.addJob()
            advanceTimeBy(11.seconds)
            val key =
                traffic.adds
                    .single()
                    .days.keys
                    .single()
            key.second shouldBe "wlan0"
            key.third shouldBe DownloadKind.DIRECT
            h.engine.stop()
        }

    @Test
    fun aFailedSaveIsRetried() =
        runTest {
            val (h, traffic) = started()
            traffic.failNext = 1
            h.addJob()
            advanceTimeBy(11.seconds)
            traffic.adds shouldBe emptyList()
            advanceTimeBy(10.seconds)
            traffic.bytes shouldBe whole
            h.engine.stop()
        }

    @Test
    fun aSaveCutShortByStopIsNotCountedTwice() =
        runTest {
            val (h, traffic) = started()
            traffic.gate = CompletableDeferred() // the first save is taken, then hangs
            h.addJob()
            advanceTimeBy(11.seconds)
            traffic.adds.size shouldBe 1

            h.engine.stop()

            traffic.bytes shouldBe whole
        }

    // Final review: the chunks after the last one-second sample (at 2.25 and 2.5 s) must count too.
    @Test
    fun aRemovedJobsBytesStillCountForTheDay() =
        runTest {
            val (h, traffic) = started()
            val a = h.addJob()
            advanceTimeBy(2600.milliseconds)
            h.engine.remove(a, RemoveFiles.KEEP)
            advanceTimeBy(10.seconds)
            h.engine.stop()

            traffic.bytes shouldBe h.protocol.fetched.sumOf { it.end - it.start }
            traffic.adds
                .flatMap { it.days.keys }
                .map { it.third }
                .toSet() shouldBe setOf(DownloadKind.DIRECT)
        }

    // Final review: a stop while a job still runs saves the bytes since its last sample.
    @Test
    fun aStopMidTransferSavesEveryByte() =
        runTest {
            val (h, traffic) = started()
            h.addJob()
            advanceTimeBy(2600.milliseconds)

            h.engine.stop()

            traffic.bytes shouldBe h.protocol.fetched.sumOf { it.end - it.start }
            traffic.bytes shouldBeGreaterThan 0L
        }

    @Test
    fun aFailedFinalSaveDoesntBlockStop() =
        runTest {
            val (h, traffic) = started()
            traffic.failAlways = true
            h.addJob()
            advanceTimeBy(5.seconds)

            h.engine.stop()

            h.engine.state.value shouldBe EngineState.Stopped
        }
}
