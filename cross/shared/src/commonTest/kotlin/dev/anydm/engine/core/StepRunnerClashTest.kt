package dev.anydm.engine.core

import dev.anydm.engine.files.ClashPolicy
import dev.anydm.engine.files.FilesPlacement
import dev.anydm.engine.files.NameCleaner
import dev.anydm.engine.files.OsProfile
import dev.anydm.engine.platform.FakeFileSystem
import dev.anydm.engine.protocol.FakeProtocol
import dev.anydm.engine.protocol.fakeContent
import dev.anydm.engine.store.InMemoryPlanStore
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.Test

class StepRunnerClashTest {
    private val fs = FakeFileSystem()
    private val target = Path(stepRoot, "file.zip")
    private val renamed = Path(stepRoot, "file (1).zip")
    private val whole = fakeContent(0, 0, 64 * 1024)

    private fun runner(policy: ClashPolicy): StepRunner {
        val store = InMemoryPlanStore().also { it.register(DownloadId(1)) }
        store.register(DownloadId(2))
        return StepRunner(
            fs,
            store,
            mapOf(DownloadKind.DIRECT to FakeProtocol()),
            stepRoot,
            freeSpaceReserve = 0,
            placement = FilesPlacement(NameCleaner(OsProfile.LINUX), { emptyList() }, { policy }, fs),
        )
    }

    private suspend fun StepRunner.ready(id: Long = 1) =
        prepare(aPendingDownload(id = id), aMirror(DownloadId(id))).shouldBeInstanceOf<PrepareOutcome.Ready>()

    @Test
    fun renamePolicyPicksAFreeNameWhenTheTargetAppeared() =
        runTest {
            val runner = runner(ClashPolicy.RENAME)
            val ready = runner.ready()
            fs.put(target, byteArrayOf(7))

            val done = runner.run(ready).shouldBeInstanceOf<RunOutcome.Finished>()

            done.outputs shouldBe listOf(renamed)
            done.download.fileName shouldBe "file (1).zip"
            fs.read(renamed) shouldBe whole
            fs.read(target)?.toList() shouldBe listOf<Byte>(7)
        }

    @Test
    fun overwritePolicyReplacesTheFileThere() =
        runTest {
            val runner = runner(ClashPolicy.OVERWRITE)
            val ready = runner.ready()
            fs.put(target, byteArrayOf(7))

            val done = runner.run(ready).shouldBeInstanceOf<RunOutcome.Finished>()

            done.outputs shouldBe listOf(target)
            fs.read(target) shouldBe whole
        }

    @Test
    fun skipPolicyKeepsThePartialAndFails() =
        runTest {
            val runner = runner(ClashPolicy.SKIP)
            val ready = runner.ready()
            fs.put(target, byteArrayOf(7))

            runner.run(ready).shouldBeInstanceOf<RunOutcome.Failed>()

            fs.read(target)?.toList() shouldBe listOf<Byte>(7)
            fs.exists(Path(stepRoot, "file.zip.1.anydm")) shouldBe true
        }

    @Test
    fun aMoveToAnotherVolumeGoesThroughCopyAndVerify() =
        runTest {
            fs.volumeOf = { if (it.name.endsWith(".anydm")) "A" else "B" }
            val runner = runner(ClashPolicy.RENAME)

            val done = runner.run(runner.ready()).shouldBeInstanceOf<RunOutcome.Finished>()

            done.outputs shouldBe listOf(target)
            fs.read(target) shouldBe whole
            fs.paths().none { it.name.endsWith(".anydm") || it.name.endsWith(".copy") } shouldBe true
        }

    @Test
    fun twoJobsWithTheSameNameBothSurvive() =
        runTest {
            val runner = runner(ClashPolicy.RENAME)
            val first = runner.ready(1)
            val second = runner.ready(2)

            runner.run(first).shouldBeInstanceOf<RunOutcome.Finished>().outputs shouldBe listOf(target)
            runner.run(second).shouldBeInstanceOf<RunOutcome.Finished>().outputs shouldBe listOf(renamed)

            fs.read(target) shouldBe whole
            fs.read(renamed) shouldBe whole
        }
}
