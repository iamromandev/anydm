package dev.anydm.engine

/** Where an [Engine] is in its life. It goes one way: an engine is never started again. */
sealed interface EngineState {
    data object NotStarted : EngineState

    data object Running : EngineState

    /** stop() is waiting for the jobs to save their last checkpoint. */
    data object Stopping : EngineState

    data object Stopped : EngineState

    /** The store failed and the engine stopped. */
    data class Failed(
        val error: Throwable,
    ) : EngineState
}
