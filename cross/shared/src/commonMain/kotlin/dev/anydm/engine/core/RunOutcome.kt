package dev.anydm.engine.core

import kotlinx.io.files.Path

/** How [StepRunner.run] ended, unless it was cancelled. */
sealed interface RunOutcome {
    val download: Download

    /** COMPLETED or SEEDING; [outputs] are the finished files' paths. */
    data class Finished(
        override val download: Download,
        val outputs: List<Path>,
    ) : RunOutcome

    data class Failed(
        override val download: Download,
        val failure: StepFailure,
    ) : RunOutcome
}
