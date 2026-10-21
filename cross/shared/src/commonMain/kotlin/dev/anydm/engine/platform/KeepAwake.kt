package dev.anydm.engine.platform

/**
 * Keeps the machine from sleeping while it downloads (NET-11). Failure contract: never throws; a missing command or a
 * failing call is logged once per engine run and the engine carries on without it.
 */
interface KeepAwake {
    /** Keeps the machine from sleeping on idle. Idempotent; called again while still wanted, it repairs a lost hold. */
    suspend fun acquire()

    /** Lets it sleep again. Idempotent; kills any helper process. */
    suspend fun release()

    companion object {
        /** Does nothing. */
        val None: KeepAwake =
            object : KeepAwake {
                override suspend fun acquire() = Unit

                override suspend fun release() = Unit
            }
    }
}
