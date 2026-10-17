package dev.anydm.engine

import dev.anydm.engine.platform.deleteRecursively
import dev.anydm.engine.platform.newTempFolder
import dev.anydm.engine.store.DATABASE_FILE
import dev.anydm.engine.store.Store
import dev.anydm.engine.store.openDatabaseFile
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/** The Engine on a real database file, so a stop and a restart can be checked from the file itself. */
class EngineFileTest {
    private val root = newTempFolder("anydm-engine-test-")
    private val file = Path(root, DATABASE_FILE)
    private val stores = mutableListOf<Store>()

    @AfterTest
    fun cleanUp() {
        stores.forEach { runCatching { it.close() } }
        deleteRecursively(root)
    }

    private fun TestScope.open(): Store = Store(openDatabaseFile(file), StandardTestDispatcher(testScheduler)).also { stores += it }

    @Test
    fun stopMidTransferSavesAndCloses() =
        runTest {
            val h = EngineHarness(this, open())
            h.engine.start()
            val a = h.addJob()
            advanceTimeBy(1.seconds)

            h.engine.stop()

            h.engine.state.value shouldBe EngineState.Stopped
            shouldThrow<IllegalStateException> { h.engine.pause(a) }
            val reopened = open()
            val written = reopened.downloads.files(a).sumOf { f -> reopened.downloads.segments(f.id).sumOf { it.written } }
            written shouldBe h.protocol.fetched.sumOf { it.end - it.start }
            (written > 0) shouldBe true
        }
}
