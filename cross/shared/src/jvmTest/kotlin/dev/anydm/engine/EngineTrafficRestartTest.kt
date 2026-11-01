package dev.anydm.engine

import dev.anydm.engine.platform.deleteRecursively
import dev.anydm.engine.platform.newTempFolder
import dev.anydm.engine.protocol.FakeProtocol
import dev.anydm.engine.store.DATABASE_FILE
import dev.anydm.engine.store.Store
import dev.anydm.engine.store.openDatabaseFile
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/** A download's bytes per network across an engine stop and start on a real database file (plexo-networks-ui). */
class EngineTrafficRestartTest {
    private val root = newTempFolder("anydm-engine-share-test-")
    private val file = Path(root, DATABASE_FILE)
    private val stores = mutableListOf<Store>()
    private val whole = 64 * 1024L

    @AfterTest
    fun cleanUp() {
        stores.forEach { runCatching { it.close() } }
        deleteRecursively(root)
    }

    private fun TestScope.open(): Store = Store(openDatabaseFile(file), StandardTestDispatcher(testScheduler)).also { stores += it }

    private fun protocol(bytesPerSecond: Long) = FakeProtocol(bytesPerSecond = bytesPerSecond).also { it.network = "en5" }

    @Test
    fun aNetworkShareSurvivesARestartWithoutDoubleCounting() =
        runTest {
            val first = EngineHarness(this, open(), protocol(16 * 1024), network = { "en0" })
            first.engine.start()
            val id = first.addJob()
            advanceTimeBy(5.seconds)
            val before = first.snapshot(id)!!.networkShare
            first.engine.stop()
            before shouldBe mapOf("en5" to whole)

            val second = EngineHarness(this, open(), protocol(16 * 1024), fs = first.fs, network = { "en0" })
            second.engine.start()
            runCurrent()
            try {
                second.snapshot(id)!!.networkShare shouldBe before
            } finally {
                second.engine.stop()
            }
            open().traffic.downloadNetworkTraffic(id) shouldBe before
        }

    @Test
    fun aResumedDownloadAddsToItsStoredShare() =
        runTest {
            val first = EngineHarness(this, open(), protocol(4 * 1024), network = { "en0" }) // 16 s for the job
            first.engine.start()
            val id = first.addJob()
            advanceTimeBy(5.seconds)
            first.engine.stop()
            val stored = open().traffic.downloadNetworkTraffic(id).getValue("en5")

            val second = EngineHarness(this, open(), protocol(4 * 1024), fs = first.fs, network = { "en0" })
            second.engine.start()
            runCurrent()
            try {
                second.snapshot(id)!!.networkShare shouldBe mapOf("en5" to stored)
                second.engine.resume(id)
                advanceTimeBy(3.seconds)
                runCurrent()
                second.snapshot(id)!!.networkShare.getValue("en5") shouldBeGreaterThan stored
            } finally {
                second.engine.stop()
            }
            val total = open().traffic.downloadNetworkTraffic(id).getValue("en5")
            total shouldBeGreaterThan stored
            total shouldBe second.snapshot(id)!!.networkShare.getValue("en5")
        }
}
