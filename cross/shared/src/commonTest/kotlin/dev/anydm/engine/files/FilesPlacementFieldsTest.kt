package dev.anydm.engine.files

import dev.anydm.engine.core.CategoryId
import dev.anydm.engine.core.Download
import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.Mirror
import dev.anydm.engine.core.MirrorId
import dev.anydm.engine.core.PlacementPlan
import dev.anydm.engine.platform.FakeFileSystem
import dev.anydm.engine.protocol.FakeProbe
import dev.anydm.engine.protocol.ProbeResult
import dev.anydm.engine.protocol.StepResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.Test

/** A probe can supply the fields a category's naming template uses (FIL-02, MED-01). */
class FilesPlacementFieldsTest {
    private val media =
        Category(CategoryId(3), "media", "/dl/media", "{uploader} - {title}", listOf(CategoryRule.Kind(setOf(DownloadKind.SITE))), 0)
    private val mirror = Mirror(MirrorId(1), DownloadId(1), "https://example.com/watch", 0)

    private class FieldedProbe(
        override val name: String,
        override val nameFields: NameFields,
    ) : ProbeResult {
        override val size: Long? = null
    }

    private fun placement() = FilesPlacement(NameCleaner(OsProfile.LINUX), { listOf(media) }, { ClashPolicy.RENAME }, FakeFileSystem())

    private suspend fun planned(probe: ProbeResult): PlacementPlan {
        val job = Download(DownloadId(1), DownloadKind.SITE)
        val plan = placement().plan(job, mirror, probe, Path("/default"), firstPrepare = true)
        return plan.shouldBeInstanceOf<StepResult.Ok<*>>().value as PlacementPlan
    }

    @Test
    fun aTemplateRendersTheProbesFields() =
        runTest {
            val fields = NameFields(mapOf(TemplateField.UPLOADER to "Ann", TemplateField.TITLE to "Intro"))

            planned(FieldedProbe("Intro.mp4", fields)).fileName shouldBe "Ann - Intro.mp4"
        }

    @Test
    fun aProbeWithoutFieldsKeepsTodaysBehaviour() =
        runTest {
            planned(FakeProbe("clip.mp4", 10)).fileName shouldBe "clip.mp4"
        }
}
