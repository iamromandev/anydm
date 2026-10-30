package dev.anydm.engine.core

import kotlin.time.Duration

/**
 * What an Attempt records (DIA-03) and the queue's RetryPolicy decides on: the [step] that failed, its class if any,
 * why, and how long the source asked to wait ([retryAfter], as on StepResult.Failed).
 */
data class StepFailure(
    val step: Step,
    val errorClass: ErrorClass?,
    val message: String,
    val retryAfter: Duration? = null,
)
