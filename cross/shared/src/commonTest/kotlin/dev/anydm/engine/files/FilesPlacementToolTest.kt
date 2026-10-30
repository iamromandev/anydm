package dev.anydm.engine.files

import dev.anydm.engine.core.CategoryId
import dev.anydm.engine.core.Download
import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.Mirror
import dev.anydm.engine.core.MirrorId
import dev.anydm.engine.platform.FakeFileSystem
import dev.anydm.engine.platform.FileResult
import dev.anydm.engine.protocol.FakeProbe
import dev.anydm.engine.protocol.StepResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.Test

/** A TOOL job is placed where it says, replacing what is there: no categories, no clash policy (BIN-01). */
class FilesPlacementToolTest {
    private val fs = FakeFileSystem()
    private val folder = Path("/cache/tools/ffmpeg/8.1.3")
    private val packages = Category(CategoryId(7), "packages", "/dl/pkg", null, listOf(CategoryRule.Extension(setOf("zip"))), 0)
    private val mirror = Mirror(MirrorId(1), DownloadId(1), "https://example.com/ffmpeg.zip", 0)
    private val probe = FakeProbe("ffmpeg.zip", 10)

    private fun placement(policy: ClashPolicy) = FilesPlacement(NameCleaner(OsProfile.LINUX), { listOf(packages) }, { policy }, fs)

    private fun job(kind: DownloadKind) = Download(DownloadId(1), kind, saveFolder = folder.toString(), fileName = "ffmpeg.zip")

    @Test
    fun aToolKeepsItsFolderAndHasNoCategory() =
        runTest {
            val plan = placement(ClashPolicy.RENAME).plan(job(DownloadKind.TOOL), mirror, probe, Path("/default"), firstPrepare = true)

            val planned = plan.shouldBeInstanceOf<StepResult.Ok<*>>().value as dev.anydm.engine.core.PlacementPlan
            planned.folder shouldBe folder
            planned.categoryId shouldBe null
            planned.fileName shouldBe "ffmpeg.zip"
        }

    @Test
    fun anOrdinaryJobStillGetsItsCategoryFolderWhereNoneWasChosen() =
        runTest {
            val plan =
                placement(
                    ClashPolicy.RENAME,
                ).plan(job(DownloadKind.DIRECT).copy(saveFolder = null), mirror, probe, Path("/default"), firstPrepare = true)

            val planned = plan.shouldBeInstanceOf<StepResult.Ok<*>>().value as dev.anydm.engine.core.PlacementPlan
            planned.folder shouldBe Path("/dl/pkg")
        }

    @Test
    fun skipDoesNotRefuseAToolWhoseFileIsAlreadyThere() =
        runTest {
            fs.put(Path(folder, "ffmpeg.zip"), byteArrayOf(1))

            placement(ClashPolicy.SKIP)
                .plan(job(DownloadKind.TOOL), mirror, probe, Path("/default"), firstPrepare = true)
                .shouldBeInstanceOf<StepResult.Ok<*>>()
        }

    @Test
    fun aToolFileReplacesWhatIsThereWhateverThePolicy() =
        runTest {
            for (policy in ClashPolicy.entries) {
                val target = Path(folder, "ffmpeg.zip")
                fs.put(target, byteArrayOf(1))
                val partial = Path(folder, "ffmpeg.zip.1.anydm").also { fs.put(it, byteArrayOf(2)) }

                placement(policy).place(job(DownloadKind.TOOL), partial, target) shouldBe FileResult.Ok(target)

                fs.read(target)?.toList() shouldBe listOf<Byte>(2)
                fs.paths() shouldBe setOf(target)
                fs.remove(target)
            }
        }
}
