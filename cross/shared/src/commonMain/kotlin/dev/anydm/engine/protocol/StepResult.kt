package dev.anydm.engine.protocol

import dev.anydm.engine.core.ErrorClass
import kotlin.time.Duration

/** The outcome of one protocol call or runner step: a value, or an expected failure. Bugs throw instead. */
sealed interface StepResult<out T> {
    data class Ok<out T>(
        val value: T,
    ) : StepResult<T>

    /**
     * [errorClass] decides what happens next (the queue's RetryPolicy); null when none fits, and the job fails with
     * [message] (DIA-03). [retryAfter] is how long the source asked to wait (`Retry-After`), only for TRANSIENT and
     * RATE_LIMITED.
     */
    data class Failed(
        val errorClass: ErrorClass?,
        val message: String,
        val retryAfter: Duration? = null,
    ) : StepResult<Nothing> {
        init {
            require(message.isNotBlank()) { "message must not be blank" }
            require(retryAfter == null || errorClass == ErrorClass.TRANSIENT || errorClass == ErrorClass.RATE_LIMITED) {
                "only a TRANSIENT or RATE_LIMITED failure has a retryAfter: $errorClass"
            }
            require(retryAfter == null || retryAfter >= Duration.ZERO) { "retryAfter must be >= 0: $retryAfter" }
        }
    }
}
