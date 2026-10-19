package dev.anydm.engine.files

import dev.anydm.engine.core.CategoryId
import dev.anydm.engine.core.Download
import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.Mirror
import dev.anydm.engine.core.MirrorId
import dev.anydm.engine.core.PlacementPlan
import dev.anydm.engine.core.ProfileId
import dev.anydm.engine.platform.FakeFileSystem
import dev.anydm.engine.protocol.ProbeResult
import dev.anydm.engine.protocol.StepResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.Test

/** A job's profile supplies the folder, category and naming template the job doesn't give (SET-02). */
class FilesPlacementProfileTest {
    private val media = Category(CategoryId(3), "media", "/dl/media", "{title}", emptyList(), 0)
    private val mirror = Mirror(MirrorId(1), DownloadId(1), "https://example.com/watch", 0)
    private val fields = NameFields(mapOf(TemplateField.UPLOADER to "Ann", TemplateField.TITLE to "Intro"))

    private class FieldedProbe(
        override val name: String,
        override val nameFields: NameFields,
    ) : ProbeResult {
        override val size: Long? = null
    }

    private fun profile(
        saveFolder: String? = null,
        nameTemplate: String? = null,
        categoryId: CategoryId? = null,
    ) = Profile(ProfileId(5), "Music", null, categoryId, saveFolder, nameTemplate, null)

    private suspend fun planned(
        job: Download,
        profile: Profile?,
    ): PlacementPlan {
        val placement =
            FilesPlacement(
                NameCleaner(OsProfile.LINUX),
                { listOf(media) },
                { ClashPolicy.RENAME },
                FakeFileSystem(),
                profiles = { if (it == ProfileId(5)) profile else null },
            )
        val plan = placement.plan(job, mirror, FieldedProbe("Intro.mp4", fields), Path("/default"), firstPrepare = true)
        return plan.shouldBeInstanceOf<StepResult.Ok<*>>().value as PlacementPlan
    }

    private val job = Download(DownloadId(1), DownloadKind.SITE, profileId = ProfileId(5))

    @Test
    fun theProfilesFolderAndTemplateApply() =
        runTest {
            val plan = planned(job, profile(saveFolder = "/music", nameTemplate = "{uploader} - {title}"))

            plan.folder shouldBe Path("/music")
            plan.fileName shouldBe "Ann - Intro.mp4"
        }

    @Test
    fun aJobsFolderBeatsTheProfilesFolder() =
        runTest {
            planned(job.copy(saveFolder = "/jobs"), profile(saveFolder = "/music")).folder shouldBe Path("/jobs")
        }

    @Test
    fun theProfilesTemplateBeatsTheCategorys() =
        runTest {
            val plan = planned(job.copy(categoryId = media.id), profile(nameTemplate = "{uploader} - {title}"))

            plan.fileName shouldBe "Ann - Intro.mp4"
            plan.folder shouldBe Path("/dl/media")
        }

    @Test
    fun theProfilesCategoryApplies() =
        runTest {
            val plan = planned(job, profile(categoryId = media.id))

            plan.folder shouldBe Path("/dl/media")
            plan.categoryId shouldBe media.id
            plan.fileName shouldBe "Intro.mp4"
        }

    @Test
    fun aProfileCategoryThatWasDeletedFallsBack() =
        runTest {
            val plan = planned(job, profile(categoryId = CategoryId(99)))

            plan.folder shouldBe Path("/default")
            plan.fileName shouldBe "Intro.mp4"
        }

    @Test
    fun aProfileThatIsGoneBehavesAsNone() =
        runTest {
            val plan = planned(job.copy(profileId = ProfileId(77)), null)

            plan.folder shouldBe Path("/default")
        }
}
