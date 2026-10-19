package dev.anydm.engine.core

import dev.anydm.engine.files.Profile
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class EffectiveOptionsTest {
    private val profile =
        Profile(ProfileId(1), "Music", Format(height = 480), CategoryId(7), "/music", "{title}", 1000)
    private val job = Download(DownloadId(1), DownloadKind.SITE)

    @Test
    fun aJobsOwnFormatWins() {
        effectiveFormat(Format(height = 720), profile) shouldBe Format(height = 720)
        effectiveFormat(null, profile) shouldBe Format(height = 480)
        effectiveFormat(null, null) shouldBe null
        effectiveFormat(null, profile.copy(format = null)) shouldBe null
    }

    @Test
    fun aJobsOwnSpeedLimitWins() {
        effectiveSpeedLimit(job.copy(speedLimit = 500), profile) shouldBe 500
        effectiveSpeedLimit(job, profile) shouldBe 1000
        effectiveSpeedLimit(job, null) shouldBe null
        effectiveSpeedLimit(job, profile.copy(speedLimit = null)) shouldBe null
    }

    @Test
    fun aJobsOwnCategoryAndFolderWin() {
        effectiveCategory(job.copy(categoryId = CategoryId(2)), profile) shouldBe CategoryId(2)
        effectiveCategory(job, profile) shouldBe CategoryId(7)
        effectiveCategory(job, null) shouldBe null
        effectiveSaveFolder(job.copy(saveFolder = "/jobs"), profile) shouldBe "/jobs"
        effectiveSaveFolder(job, profile) shouldBe "/music"
        effectiveSaveFolder(job, null) shouldBe null
    }

    @Test
    fun theProfilesTemplateBeatsTheCategorys() {
        effectiveTemplate(profile, "{uploader}") shouldBe "{title}"
        effectiveTemplate(profile.copy(nameTemplate = null), "{uploader}") shouldBe "{uploader}"
        effectiveTemplate(null, "{uploader}") shouldBe "{uploader}"
        effectiveTemplate(null, null) shouldBe null
    }
}
