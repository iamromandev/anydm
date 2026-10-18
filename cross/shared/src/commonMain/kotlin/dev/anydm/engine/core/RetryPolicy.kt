package dev.anydm.engine.core

import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * What follows a failed step (FND-06, FND-07, FND-08), decided by its error class:
 *
 * | Error class                            | Action                                        |
 * |----------------------------------------|-----------------------------------------------|
 * | TRANSIENT, RATE_LIMITED                | [FailureAction.Retry] while the budget lasts  |
 * | GONE                                   | [FailureAction.NextMirror] if one is left     |
 * | DISK_FULL                              | [FailureAction.Pause] for LOW_DISK            |
 * | TOOL_MISSING                           | [FailureAction.Pause] for TOOLS               |
 * | INTEGRITY                              | [FailureAction.StartOver] the first time      |
 * | AUTH, FORMAT_WITHHELD, INTEGRITY, none | [FailureAction.Fail]                          |
 *
 * The budget is `maxRetries` automatic retries in a row; an attempt that made progress starts the count again, so a
 * long download with an occasional drop never runs out. A retry waits the source's `retryAfter` (at most
 * [rateLimitCap]) or else the backoff: [base] doubled per retry up to [cap], then jittered to between half and all of
 * it. Pure: the queue passes the clock's [Instant] and applies the action.
 */
class RetryPolicy(
    val base: Duration = 5.seconds,
    val cap: Duration = 5.minutes,
    val rateLimitCap: Duration = 1.hours,
    private val random: Random = Random.Default,
) {
    init {
        require(base > Duration.ZERO) { "base must be > 0: $base" }
        require(cap >= base) { "cap must be >= base: $cap, $base" }
        require(rateLimitCap > Duration.ZERO) { "rateLimitCap must be > 0: $rateLimitCap" }
    }

    /**
     * The action for [failure] of [job] (as the run left it). [nextMirror] is the mirror a GONE moves to, or null when
     * none is left; [madeProgress] is whether this attempt wrote bytes; [integrityBefore] is whether an earlier attempt
     * of the job already ended INTEGRITY, so a checksum mismatch starts over from zero only once (HTTP-14).
     */
    fun decide(
        job: Download,
        failure: StepFailure,
        madeProgress: Boolean,
        nextMirror: Mirror?,
        maxRetries: Int,
        now: Instant,
        integrityBefore: Boolean = false,
    ): FailureAction =
        when (failure.errorClass) {
            ErrorClass.TRANSIENT, ErrorClass.RATE_LIMITED -> {
                val count = (if (madeProgress) 0 else job.attemptCount) + 1
                if (count > maxRetries) {
                    FailureAction.Fail
                } else {
                    val wait = failure.retryAfter?.coerceAtMost(rateLimitCap) ?: jittered(backoff(count))
                    FailureAction.Retry(now + wait, count)
                }
            }

            ErrorClass.GONE -> {
                if (nextMirror != null) FailureAction.NextMirror(nextMirror) else FailureAction.Fail
            }

            ErrorClass.DISK_FULL -> {
                FailureAction.Pause(PauseReason.LOW_DISK)
            }

            ErrorClass.TOOL_MISSING -> {
                FailureAction.Pause(PauseReason.TOOLS)
            }

            ErrorClass.INTEGRITY -> {
                if (integrityBefore) FailureAction.Fail else FailureAction.StartOver
            }

            ErrorClass.AUTH, ErrorClass.FORMAT_WITHHELD, null -> {
                FailureAction.Fail
            }
        }

    // Doubles from base and stops at the cap, so a huge count can't overflow.
    private fun backoff(count: Int): Duration {
        var delay = base
        repeat(count - 1) {
            if (delay >= cap) return cap
            delay = (delay * 2).coerceAtMost(cap)
        }
        return delay
    }

    private fun jittered(delay: Duration): Duration = delay / 2 + delay / 2 * random.nextDouble()
}

/** What the queue does with a failed job; see [RetryPolicy]. */
sealed interface FailureAction {
    /** Wait until [at], then run again; [attemptCount] is the job's count from then. */
    data class Retry(
        val at: Instant,
        val attemptCount: Int,
    ) : FailureAction

    /** Run again at once, on [mirror]. */
    data class NextMirror(
        val mirror: Mirror,
    ) : FailureAction

    data class Pause(
        val reason: PauseReason,
    ) : FailureAction

    /** Throw away what was fetched and run again from zero (a checksum mismatch, once). */
    data object StartOver : FailureAction

    data object Fail : FailureAction
}
