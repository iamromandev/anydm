package dev.anydm.engine.net

import kotlin.time.Instant

/** What to do with the machine once the queue is finished (NET-10). */
enum class AfterQueueAction { NONE, SLEEP, SHUT_DOWN }

/** The armed after-queue action, and when its 30 s countdown ends while it runs. */
data class AfterQueueState(
    /** Armed action; NONE = nothing armed. */
    val action: AfterQueueAction,
    /** Non-null only while the countdown runs. */
    val countdownEndsAt: Instant?,
) {
    companion object {
        val Idle = AfterQueueState(AfterQueueAction.NONE, null)
    }
}
