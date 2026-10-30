package dev.anydm.engine.queue

import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadStatus.COMPLETED
import dev.anydm.engine.core.stepRoot
import dev.anydm.engine.platform.FakeFileSystem
import dev.anydm.engine.platform.FileResult
import dev.anydm.engine.protocol.FakeFile
import dev.anydm.engine.protocol.FakeProtocol
import dev.anydm.engine.protocol.fakeContent
import dev.anydm.engine.store.Store
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The engine stopped at any moment, by a kill or a power cut, and started again on the same store and disk (#554): every
 * job finishes, and every file is byte for byte the source. Each case runs on a new database from [newDriver].
 */
abstract class CrashContract : QueueTestBase() {
    private val size = 256 * 1024

    @Test
    fun aKillOrPowerCutAtAnyMomentStillGivesTheSameFiles() =
        runTest {
            val test = this
            checkAll(100, Arb.int(1..3), Arb.long(0L..40_000L), Arb.boolean()) { jobs, stopAt, powerCut ->
                val store = Store(newDriver(), StandardTestDispatcher(testScheduler))
                val fs = FakeFileSystem()
                val protocol = FakeProtocol(files = listOf(FakeFile("file.zip", size.toLong())))
                val first = childScope()
                val before = QueueHarness(test, store, protocol, scope = first, fs = fs, checkpointEvery = 1.seconds)
                val second = childScope()
                try {
                    before.queue.start()
                    val ids = List(jobs) { before.addJob() }
                    advanceTimeBy(stopAt.milliseconds)
                    if (powerCut) fs.powerCut()
                    first.cancel()
                    runCurrent()

                    val after =
                        QueueHarness(test, store, protocol, scope = second, clock = before.clock, fs = fs, checkpointEvery = 1.seconds)
                    after.queue.start()
                    settle()

                    for ((i, id) in ids.withIndex()) {
                        withClue("job ${i + 1} of $jobs, stopped at $stopAt ms, power cut $powerCut") {
                            after.status(id) shouldBe COMPLETED
                            fs.read(Path(stepRoot, "job-${i + 1}.zip"))?.toList() shouldBe fakeContent(0, 0, size).toList()
                        }
                    }
                } finally {
                    first.cancel()
                    second.cancel()
                    runCurrent()
                    store.close()
                }
            }
        }

    @Test
    fun aPowerCutWhilePlacingOutputsStillFinishes() =
        runTest {
            val store = Store(newDriver(), StandardTestDispatcher(testScheduler))
            val fs = FakeFileSystem()
            val protocol = FakeProtocol(files = listOf(FakeFile("a.bin", 64 * 1024), FakeFile("b.bin", 64 * 1024)))
            val first = childScope()
            val before = QueueHarness(this, store, protocol, scope = first, fs = fs, checkpointEvery = 1.seconds)
            before.queue.start()
            val id: DownloadId = before.addJob()
            // The power goes when the second output is about to be placed: the first one already is.
            var cut = false
            fs.faults[FakeFileSystem.Op.ATOMIC_MOVE] = { source ->
                if (!cut && source.name.startsWith("b.bin")) {
                    cut = true
                    fs.powerCut()
                    first.cancel()
                    FileResult.IoError(source, "power cut")
                } else {
                    null
                }
            }
            settle()
            cut shouldBe true

            val after = QueueHarness(this, store, protocol, clock = before.clock, fs = fs, checkpointEvery = 1.seconds)
            after.queue.start()
            settle()

            after.status(id) shouldBe COMPLETED
            fs.read(Path(stepRoot, "a.bin"))?.toList() shouldBe fakeContent(0, 0, 64 * 1024).toList()
            fs.read(Path(stepRoot, "b.bin"))?.toList() shouldBe fakeContent(1, 0, 64 * 1024).toList()
            store.close()
        }
}
