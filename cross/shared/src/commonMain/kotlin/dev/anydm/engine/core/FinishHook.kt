package dev.anydm.engine.core

import kotlinx.io.files.Path

/**
 * Runs after COMPLETED or SEEDING was reported: history (FIL-10, #539), notifications (OSI-09, #545). It runs shielded
 * from cancellation, so a pause or cancel that arrives meanwhile does not undo a finished job, and [StepRunner.run]
 * still returns Finished.
 *
 * It has no way to fail the job. If it throws (a bug), the exception leaves [StepRunner.run] after the job's last move
 * was reported: the job IS finished. #552 must treat a throw from `run` after COMPLETED or SEEDING was reported as a
 * finished job (and log the throw), not as a failure.
 */
interface FinishHook {
    suspend fun finished(
        download: Download,
        outputs: List<Path>,
    )

    data object None : FinishHook {
        override suspend fun finished(
            download: Download,
            outputs: List<Path>,
        ) = Unit
    }
}
