package dev.anydm.engine.protocol

/** Paces a transfer to its speed limit (NET-01); [None] never waits until #538 brings the real one. */
interface Limiter {
    /** Returns once [bytes] more may be taken in. */
    suspend fun acquire(bytes: Int)

    data object None : Limiter {
        override suspend fun acquire(bytes: Int) = Unit
    }
}
