package dev.anydm.engine.core

import dev.anydm.engine.files.Category
import dev.anydm.engine.files.CategoryRule
import dev.anydm.engine.files.ClashPolicy
import dev.anydm.engine.files.FilesPlacement
import dev.anydm.engine.files.NameCleaner
import dev.anydm.engine.files.OsProfile
import dev.anydm.engine.platform.FakeFileSystem
import dev.anydm.engine.protocol.FakeFile
import dev.anydm.engine.protocol.FakeProtocol
import dev.anydm.engine.store.InMemoryPlanStore
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.Test

class StepRunnerPlacementTest {
    private val fs = FakeFileSystem()

    private fun placement(
        os: OsProfile = OsProfile.LINUX,
        categories: List<Category> = emptyList(),
        policy: ClashPolicy = ClashPolicy.RENAME,
    ) = FilesPlacement(NameCleaner(os), { categories }, { policy }, fs)

    private suspend fun outcome(
        download: Download,
        probeName: String,
        placement: Placement,
        mime: String? = null,
    ): PrepareOutcome {
        val store = InMemoryPlanStore().also { it.register(download.id) }
        val protocol = FakeProtocol(files = listOf(FakeFile(probeName, 64L * 1024)), name = probeName, mime = mime)
        val runner =
            StepRunner(
                fs,
                store,
                mapOf(DownloadKind.DIRECT to protocol),
                stepRoot,
                freeSpaceReserve = 0,
                placement = placement,
            )
        return runner.prepare(download, aMirror(download.id))
    }

    private suspend fun prepared(
        download: Download,
        probeName: String,
        placement: Placement,
        mime: String? = null,
    ): Download = outcome(download, probeName, placement, mime).shouldBeInstanceOf<PrepareOutcome.Ready>().download

    @Test
    fun prepareCleansTheProbeName() =
        runTest {
            prepared(aPendingDownload(), "a:b?.txt", placement(OsProfile.WINDOWS)).fileName shouldBe "a_b_.txt"
        }

    @Test
    fun theUsersNameIsCleanedToo() =
        runTest {
            prepared(aPendingDownload(fileName = "CON"), "file.zip", placement(OsProfile.WINDOWS)).fileName shouldBe "_CON"
        }

    @Test
    fun noPlacementKeepsTheProbeNameAsIs() =
        runTest {
            prepared(aPendingDownload(), "a:b?.txt", Placement.None).fileName shouldBe "a:b?.txt"
        }

    private val video =
        Category(CategoryId(7), "video", "/dl/video", null, listOf(CategoryRule.Extension(setOf("mkv"))), 0)

    @Test
    fun aMatchingCategoryChoosesTheFolderAndId() =
        runTest {
            val done = prepared(aPendingDownload(saveFolder = null), "clip.mkv", placement(categories = listOf(video)))
            done.saveFolder shouldBe "/dl/video"
            done.categoryId shouldBe CategoryId(7)
            done.fileName shouldBe "clip.mkv"
        }

    @Test
    fun theCategoryMatchesOnTheMimeTypeFromTheProbe() =
        runTest {
            val byMime = video.copy(rules = listOf(CategoryRule.Mime(setOf("video/*"))))
            val done = prepared(aPendingDownload(saveFolder = null), "stream", placement(categories = listOf(byMime)), mime = "video/mp4")
            done.categoryId shouldBe CategoryId(7)
        }

    @Test
    fun theCategoryMatchesOnTheHostOfTheMirror() =
        runTest {
            val byHost = video.copy(rules = listOf(CategoryRule.Domain(setOf("example.com"))))
            val done = prepared(aPendingDownload(saveFolder = null), "x.bin", placement(categories = listOf(byHost)))
            done.categoryId shouldBe CategoryId(7)
        }

    @Test
    fun noCategoryUsesTheDefaultFolder() =
        runTest {
            val done = prepared(aPendingDownload(saveFolder = null), "clip.zip", placement(categories = listOf(video)))
            done.saveFolder shouldBe stepRoot.toString()
            done.categoryId shouldBe null
        }

    @Test
    fun theJobsOwnFolderBeatsTheCategorys() =
        runTest {
            val done = prepared(aPendingDownload(saveFolder = "/mine"), "clip.mkv", placement(categories = listOf(video)))
            done.saveFolder shouldBe "/mine"
            done.categoryId shouldBe CategoryId(7)
        }

    @Test
    fun theTemplateNamesTheFileAndTheExtensionIsKept() =
        runTest {
            val templated = video.copy(template = "Fixed {title}")
            prepared(aPendingDownload(saveFolder = null), "clip.mkv", placement(categories = listOf(templated))).fileName shouldBe
                "Fixed.mkv"
        }

    @Test
    fun aTemplateThatRendersNothingFallsBackToTheProbeName() =
        runTest {
            val templated = video.copy(template = "{title}")
            prepared(aPendingDownload(saveFolder = null), "clip.mkv", placement(categories = listOf(templated))).fileName shouldBe
                "clip.mkv"
        }

    @Test
    fun theUsersFileNameBeatsTheTemplate() =
        runTest {
            val templated = video.copy(template = "Fixed {title}")
            val done =
                prepared(aPendingDownload(saveFolder = null, fileName = "mine.mkv"), "clip.mkv", placement(categories = listOf(templated)))
            done.fileName shouldBe "mine.mkv"
        }

    @Test
    fun skipPolicyFailsThePrepareWhenTheTargetExists() =
        runTest {
            fs.put(Path(stepRoot, "file.zip"), byteArrayOf(1))
            val failed =
                outcome(aPendingDownload(), "file.zip", placement(policy = ClashPolicy.SKIP)).shouldBeInstanceOf<PrepareOutcome.Failed>()
            failed.failure.message shouldBe "skipped: file.zip already exists in $stepRoot"
        }

    @Test
    fun renameAndOverwriteDoNotStopOnAnExistingTarget() =
        runTest {
            fs.put(Path(stepRoot, "file.zip"), byteArrayOf(1))
            for (policy in listOf(ClashPolicy.RENAME, ClashPolicy.OVERWRITE)) {
                prepared(aPendingDownload(), "file.zip", placement(policy = policy)).fileName shouldBe "file.zip"
            }
        }
}
