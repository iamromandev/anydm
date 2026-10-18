package dev.anydm.engine.files

import dev.anydm.engine.core.Download
import dev.anydm.engine.core.Placement
import dev.anydm.engine.core.PlacementPlan
import dev.anydm.engine.protocol.ProbeResult
import dev.anydm.engine.protocol.StepResult
import kotlinx.io.files.Path

/** The real [Placement]: names are cleaned for the OS (FIL-03). Categories and templates join in later. */
class FilesPlacement(
    private val cleaner: NameCleaner,
) : Placement {
    override suspend fun plan(
        download: Download,
        probe: ProbeResult,
        defaultFolder: Path,
    ): StepResult<PlacementPlan> {
        val folder = download.saveFolder?.let(::Path) ?: defaultFolder
        return StepResult.Ok(PlacementPlan(download.categoryId, folder, cleaner.clean(download.fileName ?: probe.name)))
    }
}
