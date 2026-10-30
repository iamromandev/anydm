package dev.anydm.engine

import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.net.NetworkIdentity
import dev.anydm.engine.queue.RemoveFiles
import dev.anydm.engine.store.StoreTestBase
import dev.anydm.engine.torrent.FakeTorrentService
import dev.anydm.engine.torrent.SeedSample
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

    private suspend fun TestScope.started(
        network: NetworkIdentity = NetworkIdentity.Unknown,
        torrents: FakeTorrentService? = null,
    ): Pair<EngineHarness, RecordingTraffic> {
        val store = newStore()
        val traffic = RecordingTraffic(store.traffic)
        val h = EngineHarness(this, store, network = network, traffic = traffic, torrents = torrents)
        h.engine.start()
        return h to traffic
    }

    @Test
    fun seedTrafficIsCountedWithoutASlot() =
        runTest {
            val service = FakeTorrentService()
            val (h, traffic) = started(torrents = service)
            val id = DownloadId(7)
            service.traffic = listOf(SeedSample(id, up = 5000, down = 300))
            advanceTimeBy(11.seconds)
            runCurrent()

            val batch = traffic.adds.single()
            batch.daysUp.values.sum() shouldBe 5000L
            batch.daysUp.keys
                .single()
                .third shouldBe DownloadKind.TORRENT
            batch.days.values.sum() shouldBe 300L
            batch.downloadsUp shouldBe mapOf(id to 5000L)
            h.engine.stop()
        }

    @Test
    fun seedTrafficAndSlotTrafficAreNotCountedTwice() =
        runTest {
            val service = FakeTorrentService()
            val (h, traffic) = started(torrents = service)
            h.protocol.uploadPerJob = 8 * 1024
            val job = h.addJob()
            service.traffic = listOf(SeedSample(DownloadId(7), up = 5000, down = 0))
            advanceTimeBy(11.seconds)
            runCurrent()

            val batch = traffic.adds.single()
            // the running job's 8 KiB and the seed's 5000 bytes, each once
            batch.daysUp.values.sum() shouldBe 8 * 1024L + 5000L
            batch.downloadsUp shouldBe mapOf(job to 8 * 1024L, DownloadId(7) to 5000L)
            batch.days.values.sum() shouldBe whole
            h.engine.stop()
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
    fun bytesOnABoundNetworkAreCountedUnderIt() =
        runTest {
            val (h, traffic) = started(network = { "en0" })
            h.protocol.network = "en5"
            h.addJob()
            advanceTimeBy(11.seconds)
            runCurrent()

            val batch = traffic.adds.single()
            batch.days.keys
                .map { it.second }
                .toSet() shouldBe setOf("en5")
            batch.days.values.sum() shouldBe whole
            h.engine.stop()
        }

    @Test
    fun bytesOnTwoNetworksAreCountedEachUnderItsOwnKeyAndNothingTwice() =
        runTest {
            val (h, traffic) = started(network = { "en0" })
            h.protocol.network = "en5"
            h.addJob()
            advanceTimeBy(2.seconds)
            h.protocol.network = null // the rest of the run goes by the default route
            advanceTimeBy(9.seconds)
            runCurrent()

            val batch = traffic.adds.single()
            val byNetwork =
                batch.days.entries
                    .groupBy({ it.key.second }, { it.value })
                    .mapValues { it.value.sum() }
            byNetwork.keys shouldBe setOf("en5", "en0")
            byNetwork.values.sum() shouldBe whole
            h.engine.stop()
        }

    @Test
    fun theLastBytesOfARunAreCountedUnderTheirNetworkToo() =
        runTest {
            val (h, traffic) = started(network = { "en0" })
            h.protocol.network = "en5"
            h.addJob()
            advanceTimeBy(5.seconds)
            h.engine.stop()

            traffic.adds.flatMap { it.days.keys.map { key -> key.second } }.toSet() shouldBe setOf("en5")
            traffic.bytes shouldBe whole
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
