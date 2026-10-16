package dev.anydm.engine.core

/** What an Attempt records (DIA-03) and #553's policy decides on: the [step] that failed, its class if any, and why. */
data class StepFailure(
    val step: Step,
    val errorClass: ErrorClass?,
    val message: String,
)
