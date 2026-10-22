package dev.anydm.engine.os

/**
 * At most one app per user (OSI-08). The first launch is the running instance and listens; a later one hands its program
 * arguments to it and is told to exit.
 */
interface SingleInstance : AutoCloseable {
    /** What this launch is. */
    enum class Role {
        /** The running instance: later launches hand their arguments to [start]'s callback. */
        PRIMARY,

        /** The arguments reached the running instance. This launch should exit. */
        HANDED_OVER,

        /** The running instance did not answer in time (it hangs, or something else holds the lock): this launch goes on alone. */
        ALONE,
    }

    /**
     * Decides this launch's [Role]. As the primary, each later launch's arguments come to [onArguments] in order; as a second
     * launch, [arguments] go to the primary before this returns.
     */
    suspend fun start(
        arguments: List<String>,
        onArguments: suspend (List<String>) -> Unit,
    ): Role
}
