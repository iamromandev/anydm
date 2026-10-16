package dev.anydm.engine.protocol

import dev.anydm.engine.core.ErrorClass

/** The outcome of one protocol call or runner step: a value, or an expected failure. Bugs throw instead. */
sealed interface StepResult<out T> {
    data class Ok<out T>(
        val value: T,
    ) : StepResult<T>

    /** [errorClass] decides what happens next (#553); null when none fits, and the job fails with [message] (DIA-03). */
    data class Failed(
        val errorClass: ErrorClass?,
        val message: String,
    ) : StepResult<Nothing> {
        init {
            require(message.isNotBlank()) { "message must not be blank" }
        }
    }
}
