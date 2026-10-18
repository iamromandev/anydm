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

    @Test
    fun aRemovedJobsBytesStillCountForTheDay() =
        runTest {
            val (h, traffic) = started()
            val a = h.addJob()
            advanceTimeBy(2.seconds)
            h.engine.remove(a, RemoveFiles.KEEP)
            advanceTimeBy(10.seconds)
            h.engine.stop()

            traffic.bytes shouldBeGreaterThan 0L
            traffic.adds
                .flatMap { it.days.keys }
                .map { it.third }
                .toSet() shouldBe setOf(DownloadKind.DIRECT)
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
