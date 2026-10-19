package dev.anydm.engine

import dev.anydm.engine.protocol.StepResult

/**
 * What a sync of a collection did (MED-17): [added] new items became jobs, [skipped] were kept out by the duplicate check,
 * and [unavailable] listed entries had no usable address (private or removed videos).
 */
data class SyncResult(
    val added: Int,
    val skipped: Int,
    val unavailable: Int,
)

sealed interface SyncOutcome {
    data class Done(
        val result: SyncResult,
    ) : SyncOutcome

    /** The id is no collection of this engine, or it has no source address to list again. */
    data object NotACollection : SyncOutcome

    data class Failed(
        val failure: StepResult.Failed,
    ) : SyncOutcome

    /** This engine has no way to list playlists. */
    data object Unavailable : SyncOutcome
}
