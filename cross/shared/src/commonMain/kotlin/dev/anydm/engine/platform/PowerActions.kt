package dev.anydm.engine.platform

/** Puts the machine to sleep or shuts it down once the queue is finished (NET-10). */
interface PowerActions {
    /** Puts the machine to sleep. False when the command is missing or fails. */
    suspend fun sleep(): Boolean

    /** Shuts the machine down. False when the command is missing or fails. */
    suspend fun shutDown(): Boolean

    companion object {
        /** Both return false. */
        val None: PowerActions =
            object : PowerActions {
                override suspend fun sleep() = false

                override suspend fun shutDown() = false
            }
    }
}
