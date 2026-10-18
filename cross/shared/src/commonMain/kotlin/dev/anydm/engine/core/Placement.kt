package dev.anydm.engine.core

import dev.anydm.engine.platform.FileResult
import dev.anydm.engine.platform.FileSystem
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
 * The two decisions about where files end up. [plan] runs in prepare, right after the probe; [firstPrepare] is false
 * when the job has been prepared before (it has file rows), so a file already placed for it isn't mistaken for a clash. [place] runs when a
 * finished file is moved from its partial into its final path. [DirectPlacement] keeps the plain rules;
 * [dev.anydm.engine.files.FilesPlacement] is the real one.
 */
interface Placement {
    suspend fun plan(
        download: Download,
        mirror: Mirror,
        probe: ProbeResult,
        defaultFolder: Path,
        firstPrepare: Boolean,
    ): StepResult<PlacementPlan>

    /**
     * Moves [partial] to [target], or to the path the clash policy picks instead; returns where the file ended up.
     * A [FileResult.NotFound] for [partial] is how the runner learns a stopped run already placed it.
     */
    suspend fun place(
        download: Download,
        partial: Path,
        target: Path,
    ): FileResult<Path>
}

/** The plain rules: the job's own folder or the default one, its own name or the probe's, and never replace a file. */
class DirectPlacement(
    private val fileSystem: FileSystem,
) : Placement {
    override suspend fun plan(
        download: Download,
        mirror: Mirror,
        probe: ProbeResult,
        defaultFolder: Path,
        firstPrepare: Boolean,
    ): StepResult<PlacementPlan> =
        StepResult.Ok(
            PlacementPlan(download.categoryId, download.saveFolder?.let(::Path) ?: defaultFolder, download.fileName ?: probe.name),
        )

    override suspend fun place(
        download: Download,
        partial: Path,
        target: Path,
    ): FileResult<Path> =
        when (val moved = fileSystem.atomicMove(partial, target, replace = false)) {
            is FileResult.Ok -> FileResult.Ok(target)
            is FileResult.Failed -> moved
        }
}
