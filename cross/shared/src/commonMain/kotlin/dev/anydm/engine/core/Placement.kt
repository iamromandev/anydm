package dev.anydm.engine.core

import dev.anydm.engine.protocol.ProbeResult
import dev.anydm.engine.protocol.StepResult
import kotlinx.io.files.Path

/** Where a job's files go and what the main file is called, decided once the probe has answered (FIL-01 to FIL-04). */
data class PlacementPlan(
    val categoryId: CategoryId?,
    val folder: Path,
    val fileName: String,
)

/**
 * Asked in prepare, right after the probe. `None` keeps today's rule: the job's own folder or the default one, and
 * the job's own name or the probe's. [dev.anydm.engine.files.FilesPlacement] is the real one.
 */
interface Placement {
    suspend fun plan(
        download: Download,
        mirror: Mirror,
        probe: ProbeResult,
        defaultFolder: Path,
    ): StepResult<PlacementPlan>

    data object None : Placement {
        override suspend fun plan(
            download: Download,
            mirror: Mirror,
            probe: ProbeResult,
            defaultFolder: Path,
        ): StepResult<PlacementPlan> =
            StepResult.Ok(
                PlacementPlan(download.categoryId, download.saveFolder?.let(::Path) ?: defaultFolder, download.fileName ?: probe.name),
            )
    }
}
